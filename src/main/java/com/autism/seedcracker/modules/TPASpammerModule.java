package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;

/**
 * TPA Spammer.
 *
 * Repeatedly sends /tpa (or /tpahere) requests to a target player on a configurable tick delay.
 * Auto-disables if no target name is set.
 *
 * Clean-room port of the obfuscated Zelith "TPASpammer" module.
 */
public final class TPASpammerModule extends Module {

    private final StringSetting target = add(new StringSetting("target", "Target player", "")
        .description("Player name to spam teleport requests at.")
        .group("General"));
    private final BoolSetting tpaHere = add(new BoolSetting("tpa-here", "TPA Here", false)
        .description("Send /tpahere (pull them to you) instead of /tpa (you to them).")
        .group("General"));
    private final IntSetting delay = add(new IntSetting("delay", "Delay (ticks)", 40, 10, 400, 5)
        .description("Ticks between each teleport request (20 ticks = 1 second).")
        .group("General"));
    private final BoolSetting echo = add(new BoolSetting("echo", "Echo requests", false)
        .description("Print a chat message each time a request is sent.")
        .group("General"));

    private int tickCounter = 0;

    public TPASpammerModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-tpa-spammer", "TPA Spammer", category,
            "Repeatedly sends /tpa or /tpahere requests to a target player on a delay.");
    }

    @Override
    public void onEnable() {
        tickCounter = 0;
        String name = target.get().trim();
        if (name.isEmpty()) {
            AutismClientMessaging.sendPrefixed("§c[TPA Spammer] Set a target player name first.");
            setEnabledSilently(false);
        } else {
            AutismClientMessaging.sendPrefixed("§a[TPA Spammer] Spamming requests to: §f" + name);
        }
    }

    @Override
    public void onDisable() {
        AutismClientMessaging.sendPrefixed("§c[TPA Spammer] Stopped.");
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;
        if (++tickCounter < delay.get()) return;
        tickCounter = 0;

        String name = target.get().trim();
        if (name.isEmpty()) {
            AutismClientMessaging.sendPrefixed("§c[TPA Spammer] Target player name is empty.");
            setEnabledSilently(false);
            return;
        }

        String command = (tpaHere.get() ? "tpahere" : "tpa") + " " + name;
        mc.getConnection().sendCommand(command);
        if (echo.get()) {
            AutismClientMessaging.sendPrefixed("§7[TPA Spammer] Sent §f/" + command);
        }
    }
}
