package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
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
    private final IntSetting health = add(new IntSetting("health", "Health threshold", 10, 1, 40, 1)
        .description("Disconnect at or below this total health (hearts x2, includes absorption).")
        .group("Conditions"));
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
        .group("Conditions"));
    private final StringSetting whitelist = add(new StringSetting("whitelist", "Whitelisted players", "")
        .description("Comma-separated player names that never trigger a logout.")
        .group("Filters"));
    private final BoolSetting notify = add(new BoolSetting("notify", "Notify before disconnect", true)
        .description("Show a toast / chat message explaining why you logged out.")
        .group("General"));

    private float lastHealth = -1.0f;
    private boolean loggedOut = false;

    public AutoLogModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-auto-log", "Auto Log", category,
            "Automatically disconnects under configurable danger conditions (low health, damage, players).");
    }

    @Override
    public void onEnable() {
        lastHealth = -1.0f;
        loggedOut = false;
    }

    @Override
    public void onGameLeft() {
        // We left the world (usually because we just disconnected ourselves); reset state.
        loggedOut = false;
        lastHealth = -1.0f;
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

        // Took damage this tick (health decreased).
        if (reason == null && onDamage.get() && lastHealth >= 0.0f && total + 0.001f < lastHealth) {
            reason = "took damage";
        }

        // Recently hurt by another player (age-based: within the last second).
        if (reason == null && onPlayerHurt.get()) {
            Player attacker = mc.player.getLastHurtByPlayer();
            if (attacker != null && attacker != mc.player && !attacker.isSpectator()
                && !isWhitelisted(attacker.getName().getString())
                && (mc.player.tickCount - mc.player.getLastHurtByPlayerMemoryTime()) < 20) {
                reason = "hurt by " + attacker.getName().getString();
            }
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

        if (reason != null) {
            loggedOut = true;
            if (notify.get()) {
                AutismClientMessaging.sendPrefixed("§c[Auto Log] Disconnecting: §f" + reason);
                autismclient.util.AutismNotifications.warning("Auto Log: " + reason);
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

    private boolean isWhitelisted(String name) {
        if (name == null) return false;
        return whitelistNames().contains(name.toLowerCase(Locale.ROOT));
    }

    private Set<String> whitelistNames() {
        String raw = whitelist.get();
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.replace('\n', ',').replace('\r', ',').split(",")) {
            String trimmed = part == null ? "" : part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed.toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
