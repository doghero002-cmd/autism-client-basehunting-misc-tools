package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import com.autism.seedcracker.compat.ClientNotify;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Auto Log.
 *
 * Watches configurable "danger" conditions every tick and automatically disconnects from the
 * server when one triggers. Conditions include: health dropping to/below a threshold, taking
 * damage (health actually decreased), being recently hurt by another player, and a
 * non-whitelisted player coming within a configurable radius.
 *
 * Clean-room port of the obfuscated Zelith "AutoLog" module. Only the disconnect / condition
 * logic is reproduced; the original's scoreboard "combat" text scraping was intentionally
 * dropped as unreliable.
 */
public final class AutoLogModule extends Module {

    private final BoolSetting onLowHealth = add(new BoolSetting("low-health", "Log on low health", true)
        .description("Disconnect when your health (+ absorption) falls to or below the threshold.")
        .group("Conditions"));
    private final IntSetting health = add(new IntSetting("health", "Health (½-hearts)", 10, 1, 40, 1)
        .description("Disconnect at or below this total health in half-hearts (10 = 5 hearts; includes absorption).")
        .group("Conditions").visibleWhen(() -> onLowHealth.get()));
    private final BoolSetting onDamage = add(new BoolSetting("on-damage", "Log on damage taken", false)
        .description("Disconnect the moment your health decreases.")
        .group("Conditions"));
    private final BoolSetting onPlayerHurt = add(new BoolSetting("on-player-hurt", "Log when hurt by player", true)
        .description("Disconnect when another player damages you (within the recent-hurt window).")
        .group("Conditions"));
    private final BoolSetting onPlayerNear = add(new BoolSetting("on-player-near", "Log on player nearby", false)
        .description("Disconnect when a non-whitelisted player enters the radius.")
        .group("Conditions"));
    private final IntSetting playerRange = add(new IntSetting("player-range", "Player range", 32, 4, 128, 1)
        .description("Radius (blocks) for the nearby-player check.")
        .group("Conditions").visibleWhen(() -> onPlayerNear.get()));
    private final StringSetting whitelist = add(new StringSetting("whitelist", "Whitelisted players", "")
        .description("Comma-separated player names that never trigger a logout.")
        .group("Filters").visibleWhen(() -> onPlayerNear.get()));
    private final BoolSetting onFall = add(new BoolSetting("on-fall", "Log on big fall", false)
        .description("Disconnect when you are falling far enough to take heavy damage (into a hole/void).")
        .group("Conditions"));
    private final IntSetting fallDistance = add(new IntSetting("fall-distance", "Fall distance", 12, 3, 100, 1)
        .description("Falling this many blocks triggers the big-fall logout.")
        .group("Conditions")
        .visibleWhen(() -> onFall.get()));
    private final BoolSetting onTotem = add(new BoolSetting("on-totem", "Log on totem pop", false)
        .description("Disconnect when a Totem of Undying saves you (you nearly died).")
        .group("Conditions"));
    private final BoolSetting notify = add(new BoolSetting("notify", "Notify before disconnect", true)
        .description("Show a toast / chat message explaining why you logged out.")
        .group("General"));

    private float lastHealth = -1.0f;
    private float lastRawHealth = -1.0f;
    private boolean loggedOut = false;

    public AutoLogModule() {
        super(SeedcrackerAddon.ID + ":z-auto-log", "Auto Log",
            "Automatically disconnects under configurable danger conditions (low health, damage, players).");
    }

    @Override
    public void onEnable() {
        lastHealth = -1.0f;
        lastRawHealth = -1.0f;
        lastTotemCount = -1; // else fewer totems than last session = false "totem popped" log
        loggedOut = false;
    }

    @Override
    public void onGameLeft() {
        // We left the world (usually because we just disconnected ourselves); reset state.
        loggedOut = false;
        lastHealth = -1.0f;
        lastRawHealth = -1.0f;
        lastTotemCount = -1;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;
        if (loggedOut) return; // already triggered; wait until we land on a screen

        float total = mc.player.getHealth() + mc.player.getAbsorptionAmount();

        String reason = null;

        // Low health.
        if (reason == null && onLowHealth.get() && total <= health.get()) {
            reason = "low health (" + (int) Math.ceil(total) + ")";
        }

        // Took damage this tick: the HEALTH portion dropped (absorption naturally decays, so a
        // total-health drop alone would false-trigger when a totem/absorption effect expires).
        if (reason == null && onDamage.get() && lastHealth >= 0.0f
            && mc.player.getHealth() + 0.001f < lastRawHealth) {
            reason = "took damage";
        }

        // Hurt by another player. getLastHurtByPlayer() is only ever set server-side (hurtServer), so on a
        // server it was always null and this never fired. The client does get the damage event packet, which
        // stores the DamageSource (with the attacker, or the shooter for arrows/tridents) and its game time.
        if (reason == null && onPlayerHurt.get()) {
            Player attacker = recentPlayerAttacker(mc);
            if (attacker != null && !isWhitelisted(attacker.getName().getString())) {
                reason = "hurt by " + attacker.getName().getString();
            }
        }

        // Big fall (about to take heavy fall damage / fell into a hole or the void).
        if (reason == null && onFall.get() && !mc.player.onGround()
            && mc.player.fallDistance >= fallDistance.get()) {
            reason = "falling " + (int) mc.player.fallDistance + " blocks";
        }

        // Totem pop: a totem just saved the player (health low + a totem was used this tick).
        if (reason == null && onTotem.get() && usedTotem(mc)) {
            reason = "totem popped";
        }

        // Non-whitelisted player within range.
        if (reason == null && onPlayerNear.get()) {
            double rangeSq = (double) playerRange.get() * playerRange.get();
            for (Player other : mc.level.players()) {
                if (other == mc.player || other.isSpectator()) continue;
                if (isWhitelisted(other.getName().getString())) continue;
                if (mc.player.distanceToSqr((Entity) other) <= rangeSq) {
                    reason = "player nearby: " + other.getName().getString();
                    break;
                }
            }
        }

        lastHealth = total;
        lastRawHealth = mc.player.getHealth();

        if (reason != null) {
            loggedOut = true;
            if (notify.get()) {
                AutismClientMessaging.sendPrefixed("§c[Auto Log] Disconnecting: §f" + reason);
                ClientNotify.warning("Auto Log: " + reason);
            }
            disconnect(mc, reason);
        }
    }

    private void disconnect(Minecraft mc, String reason) {
        try {
            mc.disconnect(new JoinMultiplayerScreen(new TitleScreen()), false);
        } catch (Throwable t) {
            try {
                mc.getConnection().getConnection().disconnect(Component.literal("[Auto Log] " + reason));
            } catch (Throwable ignored) {
            }
        }
    }

    /** The player behind damage taken in the last second, from the client's copy of the damage event. */
    private static Player recentPlayerAttacker(Minecraft mc) {
        var src = mc.player.getLastDamageSource();
        if (src == null || mc.player.hurtTime <= 0) return null;
        Entity e = src.getEntity();
        if (!(e instanceof Player p) || p == mc.player || p.isSpectator()) return null;
        return p;
    }

    /**
     * A totem saved us this tick. The old "totem count went down" check also fired on moving a totem into a chest
     * or dropping one. A real pop consumes the one in a HAND (main or off) and comes with the totem particles +
     * health snapping back from a lethal hit, so count only the held totems and require the hurt animation.
     */
    private int lastTotemCount = -1;
    private boolean usedTotem(Minecraft mc) {
        int held = (mc.player.getMainHandItem().is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING) ? 1 : 0)
            + (mc.player.getOffhandItem().is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING) ? 1 : 0);
        boolean popped = lastTotemCount >= 0 && held < lastTotemCount && mc.gui.screen() == null
            && (mc.player.hurtTime > 0 || mc.player.hasEffect(net.minecraft.world.effect.MobEffects.ABSORPTION));
        lastTotemCount = held;
        return popped;
    }

    private boolean isWhitelisted(String name) {
        if (name == null) return false;
        return whitelistNames().contains(name.toLowerCase(Locale.ROOT));
    }

    /** Parsed-whitelist cache: rebuilding the set per player per tick was wasted churn. */
    private String whitelistRawCache;
    private Set<String> whitelistSetCache = java.util.Set.of();

    private Set<String> whitelistNames() {
        String raw = whitelist.get();
        if (java.util.Objects.equals(raw, whitelistRawCache)) return whitelistSetCache;
        Set<String> out = new LinkedHashSet<>();
        if (raw != null && !raw.isBlank()) {
            for (String part : raw.replace('\n', ',').replace('\r', ',').split(",")) {
                String trimmed = part == null ? "" : part.trim();
                if (!trimmed.isEmpty()) out.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        whitelistRawCache = raw;
        whitelistSetCache = out;
        return out;
    }
}
