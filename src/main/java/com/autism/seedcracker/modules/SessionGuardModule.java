package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.util.FlagLog;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

/**
 * Session Guard.
 *
 * Hard limits on an automation session: max session length and a disconnect-at-low-durability
 * check on all worn armor. Long unbroken bot sessions are the #1 account-flag heuristic on
 * managed servers (12h+ of continuous play reads as a bot regardless of in-game behavior), and
 * armor hitting zero mid-automation gets you killed at a stash. This module is the "walk away
 * safely" umbrella the individual automations don't provide.
 */
public final class SessionGuardModule extends Module {

    private final IntSetting maxHours = add(new IntSetting("max-hours", "Max session (hours)", 6, 1, 24, 1)
        .description("Disconnect after this many hours online (long unbroken sessions read as botting).")
        .group("General"));
    private final BoolSetting armorGuard = add(new BoolSetting("armor-guard", "Armor guard", true)
        .description("Disconnect when any worn armor piece drops below the durability threshold.")
        .group("General"));
    private final IntSetting armorPercent = add(new IntSetting("armor-percent", "Armor threshold (%)", 10, 1, 50, 1)
        .description("Durability % that triggers the armor disconnect.").group("General"));
    private final BoolSetting warn = add(new BoolSetting("warn", "Warn before disconnect", true)
        .description("Chat warning 60s before the session-length disconnect.").group("General"));

    private long joinedAtMs = 0;
    private boolean warned = false;

    public SessionGuardModule() {
        super(SeedcrackerAddon.ID + ":session-guard", "Session Guard",
            "Auto-disconnect on session-length and armor-durability limits (bot-session hygiene).");
    }

    @Override
    public void onEnable() {
        joinedAtMs = System.currentTimeMillis();
        warned = false;
    }

    @Override
    public void onGameJoin() {
        joinedAtMs = System.currentTimeMillis();
        warned = false;
    }

    @Override
    public void onGameLeft() {
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;

        long elapsed = System.currentTimeMillis() - joinedAtMs;
        long limit = maxHours.get() * 3600_000L;

        if (warn.get() && !warned && elapsed >= limit - 60_000L) {
            warned = true;
            AutismClientMessaging.sendPrefixed("§e[SessionGuard] §fSession limit in 60s - wrapping up.");
        }
        if (elapsed >= limit) {
            bail(mc, "session limit (" + maxHours.get() + "h)");
            return;
        }

        if (armorGuard.get()) {
            // Armor slots 36-39 in the player inventory.
            for (int i = 36; i <= 39; i++) {
                var s = mc.player.getInventory().getItem(i);
                if (s.isEmpty() || s.getMaxDamage() <= 0) continue;
                int remaining = s.getMaxDamage() - s.getDamageValue();
                if (remaining * 100 < s.getMaxDamage() * armorPercent.get()) {
                    bail(mc, s.getHoverName().getString() + " at "
                        + (remaining * 100 / s.getMaxDamage()) + "% durability");
                    return;
                }
            }
        }
    }

    private void bail(Minecraft mc, String reason) {
        FlagLog.warn("SESSION", "SessionGuard", "disconnect: " + reason);
        AutismClientMessaging.sendPrefixed("§c[SessionGuard] §fDisconnecting: " + reason);
        setEnabledSilently(false);
        mc.execute(() -> {
            try {
                mc.getConnection().getConnection().disconnect(Component.literal("[SessionGuard] " + reason));
            } catch (Throwable t) {
                mc.disconnect(new JoinMultiplayerScreen(new TitleScreen()), false);
            }
        });
    }

    @Override
    public String info() {
        long mins = (System.currentTimeMillis() - joinedAtMs) / 60_000L;
        return mins + "m/" + (maxHours.get() * 60) + "m";
    }
}
