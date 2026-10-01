package com.autism.seedcracker.modules;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * Player Panic (port of the Anubis PlayerDetectionModule).
 *
 * Watches for non-whitelisted players entering render range and reacts so you're not caught
 * botting on DonutSMP:
 *  - GO DARK: disable a configurable list of automation modules (TunnelBase, NetheriteFinder, etc).
 *  - DISCONNECT: drop from the server with a believable reason.
 *  - PANIC PAY: send your balance to a safe account before leaving.
 *  - NOTIFY: chat ping + toast so you can react manually instead.
 *
 * A player counts as a threat only if they're not you, not a spectator, and not on the whitelist.
 * Fires once per appearance (re-arms when everyone leaves), so it doesn't spam.
 */
public final class PlayerPanicModule extends Module {

    private final StringSetting whitelist = add(new StringSetting(
            "whitelist", "Whitelist (comma names)", "")
        .description("Players to ignore (friends, alts, your team). Comma-separated, case-insensitive.")
        .group("General"));
    private final IntSetting range = add(new IntSetting("range", "Trigger range (blocks)", 64, 8, 256, 8)
        .description("Only react to players within this distance.").group("General"));
    private final BoolSetting goDark = add(new BoolSetting("go-dark", "Disable automation", true)
        .description("Turn OFF the listed automation modules when a threat appears.")
        .group("Reaction"));
    private final StringSetting modulesToToggle = add(new StringSetting(
            "modules-to-toggle", "Modules to disable",
            "tunnel-base-water,netherite-finder,tunnel-base-finder,schematic-builder,mace-pvp")
        .description("Comma-separated module name suffixes (the part after the last ':') to disable on threat.")
        .group("Reaction"));
    private final BoolSetting doPanicPay = add(new BoolSetting("panic-pay", "Panic pay", false)
        .description("Send your balance to the pay target before reacting.")
        .group("Reaction"));
    private final StringSetting payTarget = add(new StringSetting("pay-target", "Pay target", "")
        .description("Account to /pay your balance to on panic.").group("Reaction")
        .visibleWhen(() -> doPanicPay.get()));
    private final IntSetting payAmount = add(new IntSetting("pay-amount", "Pay amount", 1000000, 1, 100000000, 1000)
        .description("Amount to /pay on panic.").group("Reaction")
        .visibleWhen(() -> doPanicPay.get()));
    private final BoolSetting disconnect = add(new BoolSetting("disconnect", "Disconnect", false)
        .description("Drop from the server after reacting.").group("Reaction"));
    private final BoolSetting notify = add(new BoolSetting("notify", "Notify in chat", true)
        .description("Chat ping naming the threat so you can react manually.").group("Reaction"));

    private boolean armed = true;

    public PlayerPanicModule() {
        super(SeedcrackerAddon.ID + ":player-panic", "Player Panic",
            "Disables automation / disconnects / panic-pays when a non-whitelisted player comes near.");
    }

    @Override
    public void onDisable() {
        armed = true;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        List<String> threats = findThreats(mc);
        if (threats.isEmpty()) {
            armed = true; // re-arm once everyone's gone
            return;
        }
        if (!armed) return;
        armed = false;

        react(mc, threats);
    }

    private List<String> findThreats(Minecraft mc) {
        java.util.Set<String> safe = new java.util.HashSet<>();
        safe.add(mc.player.getGameProfile().name().toLowerCase(Locale.ROOT));
        String wl = whitelist.get();
        if (wl != null) {
            for (String w : wl.split(",")) {
                String t = w.trim().toLowerCase(Locale.ROOT);
                if (!t.isEmpty()) safe.add(t);
            }
        }
        double r2 = (double) range.get() * range.get();
        List<String> out = new ArrayList<>();
        for (Player p : mc.level.players()) {
            if (p == mc.player || p.isSpectator()) continue;
            String name = p.getPlainTextName();
            if (safe.contains(name.toLowerCase(Locale.ROOT))) continue;
            if (mc.player.distanceToSqr(p) > r2) continue;
            out.add(name);
        }
        return out;
    }

    private void react(Minecraft mc, List<String> threats) {
        String names = String.join(", ", threats);
        if (notify.get()) {
            AutismClientMessaging.sendPrefixed("§c[PlayerPanic] §f" + names + " nearby - going dark!");
        }
        // 1. Disable automation (go dark) FIRST so nothing keeps botting while we react.
        if (goDark.get()) disableAutomation();
        // 2. Panic pay.
        if (doPanicPay.get() && !payTarget.get().trim().isEmpty() && mc.getConnection() != null) {
            mc.getConnection().sendCommand("pay " + payTarget.get().trim() + " " + payAmount.get());
        }
        // 3. Disconnect (one tick later so the pay command leaves the pipe first).
        if (disconnect.get()) {
            mc.execute(() -> {
                try {
                    mc.getConnection().getConnection().disconnect(
                        Component.literal("[PlayerPanic] " + names));
                } catch (Throwable t) {
                    mc.disconnect(new net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen(
                        new net.minecraft.client.gui.screens.TitleScreen()), false);
                }
            });
        }
    }

    /** Disable the configured automation modules by name suffix. */
    private void disableAutomation() {
        java.util.Set<String> wanted = new java.util.HashSet<>();
        String raw = modulesToToggle.get();
        if (raw != null) {
            for (String w : raw.split(",")) {
                String t = w.trim().toLowerCase(Locale.ROOT);
                if (!t.isEmpty()) wanted.add(t);
            }
        }
        if (wanted.isEmpty()) return;
        for (autismclient.modules.Module m : com.autism.seedcracker.compat.ModuleLookup.all()) {
            if (m == null || m == this || !m.isEnabled()) continue;
            String id = m.id() == null ? "" : m.id().toLowerCase(Locale.ROOT);
            String suffix = id.contains(":") ? id.substring(id.lastIndexOf(':') + 1) : id;
            if (wanted.contains(suffix)) {
                try { m.setEnabled(false); } catch (Throwable ignored) {}
            }
        }
    }

    @Override
    public String info() {
        return armed ? "armed" : "triggered";
    }
}
