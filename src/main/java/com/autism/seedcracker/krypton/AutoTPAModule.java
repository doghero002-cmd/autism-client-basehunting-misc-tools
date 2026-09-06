package com.autism.seedcracker.krypton;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Auto TPA.
 *
 * Periodically sends /tpa (or /tpahere) to a target player on a randomized delay - used to
 * teleport a specific player (e.g. a friend / streamer account) to you repeatedly.
 *
 * Port of the Krypton "AutoTpa" module to the AUTISM API (Mojang 26.2).
 */
public final class AutoTPAModule extends Module {

    public enum Mode { TPA, TPAHERE }

    private final EnumSetting<Mode> mode = add(new EnumSetting<>(
            "mode", "Mode", Mode.TPAHERE, Mode.values())
        .description("TPA = teleport to them. TPAHERE = teleport them to you.")
        .group("General"));
    private final StringSetting player = add(new StringSetting("player", "Player", "")
        .description("Target player name.").group("General"));
    private final IntSetting minDelay = add(new IntSetting("min-delay", "Min delay (ticks)", 10, 1, 200, 1)
        .description("Minimum ticks between teleports.").group("General"));
    private final IntSetting maxDelay = add(new IntSetting("max-delay", "Max delay (ticks)", 30, 1, 400, 1)
        .description("Maximum ticks between teleports.").group("General"));

    private int delayCounter = 0;

    public AutoTPAModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":auto-tpa", "Auto TPA", category,
            "Spam /tpa or /tpahere to a target player on a randomized delay.");
    }

    @Override
    public void onGameLeft() {
        setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;
        if (player.get().trim().isEmpty()) return;

        if (delayCounter > 0) {
            delayCounter--;
            return;
        }
        String cmd = (mode.get() == Mode.TPA ? "tpa " : "tpahere ") + player.get().trim();
        mc.getConnection().sendCommand(cmd);

        int lo = Math.min(minDelay.get(), maxDelay.get());
        int hi = Math.max(minDelay.get(), maxDelay.get());
        delayCounter = ThreadLocalRandom.current().nextInt(lo, hi + 1);
    }
}
