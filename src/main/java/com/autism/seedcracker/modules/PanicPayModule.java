package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.util.FlagLog;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * Panic Pay (Radium PanicPay port).
 *
 * Dead-man's switch for carrying big balances: when a non-whitelisted player appears within
 * range, immediately /pay your alt the configured amount and disconnect. You lose nothing when
 * ambushed at a stash - the money is already on the alt by the time they can act.
 */
public final class PanicPayModule extends Module {

    private final StringSetting payTarget = add(new StringSetting("pay-target", "Pay target", "")
        .description("Username to /pay when panicking (your alt).").group("General"));
    private final StringSetting amount = add(new StringSetting("amount", "Amount", "all")
        .description("Amount for the /pay command (many servers accept 'all').").group("General"));
    private final IntSetting radius = add(new IntSetting("radius", "Trigger radius", 64, 8, 256, 8)
        .description("A player inside this radius triggers the panic.").group("General"));
    private final StringSetting whitelist = add(new StringSetting("whitelist", "Whitelist", "")
        .description("Comma-separated usernames that never trigger the panic.").group("General"));
    private final BoolSetting alsoDisconnect = add(new BoolSetting("disconnect", "Disconnect after pay", true)
        .description("Disconnect right after sending the /pay.").group("General"));
    private final IntSetting graceSeconds = add(new IntSetting("join-grace", "Join grace (s)", 10, 0, 60, 1)
        .description("No triggering for this long after joining (spawn crowds).").group("General"));

    private long enabledAtMs = 0;
    private boolean fired = false;

    public PanicPayModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":panic-pay", "Panic Pay", category,
            "Player detected nearby -> /pay your alt everything + disconnect (dead-man's switch).");
    }

    @Override
    public void onEnable() {
        enabledAtMs = System.currentTimeMillis();
        fired = false;
    }

    @Override
    public void onGameJoin() {
        enabledAtMs = System.currentTimeMillis();
        fired = false;
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;
        if (fired) return;
        if (System.currentTimeMillis() - enabledAtMs < graceSeconds.get() * 1000L) return;

        String target = payTarget.get() == null ? "" : payTarget.get().trim();
        if (target.isEmpty()) return; // not configured - do nothing rather than pay nobody

        java.util.Set<String> safe = new java.util.HashSet<>();
        safe.add(mc.player.getGameProfile().name().toLowerCase());
        safe.add(target.toLowerCase());
        if (whitelist.get() != null) {
            for (String w : whitelist.get().split(",")) {
                String t = w.trim().toLowerCase();
                if (!t.isEmpty()) safe.add(t);
            }
        }

        double r2 = (double) radius.get() * radius.get();
        for (Player p : mc.level.players()) {
            if (p == mc.player || p.isSpectator()) continue;
            if (safe.contains(p.getPlainTextName().toLowerCase())) continue;
            if (mc.player.distanceToSqr(p) > r2) continue;

            fired = true;
            String threat = p.getPlainTextName();
            FlagLog.warn("PANIC", "PanicPay", "player " + threat + " in range -> paying " + target);
            AutismClientMessaging.sendPrefixed("§c[PanicPay] §f" + threat + " detected! Paying " + target + "...");
            mc.getConnection().sendCommand("pay " + target + " " + amount.get());

            if (alsoDisconnect.get()) {
                // Give the pay command one tick to leave the pipe, then drop.
                mc.execute(() -> {
                    try {
                        mc.getConnection().getConnection().disconnect(Component.literal("[PanicPay] " + threat));
                    } catch (Throwable t) {
                        mc.disconnect(new JoinMultiplayerScreen(new TitleScreen()), false);
                    }
                });
            }
            return;
        }
    }
}
