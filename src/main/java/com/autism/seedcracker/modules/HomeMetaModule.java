package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Home Meta (port of the Anubis HomeMetaModule).
 *
 * One-shot sequence to set a home at your current spot, RTP away, then return home - handy for
 * stashing loot / escaping, or for testing your home setup:
 *   1. /sethome N
 *   2. /sethome N   (confirm, some servers need it twice)
 *   3. /rtp          (random teleport away)
 *   4. wait until you've actually teleported (moved > 5 blocks, or the timeout hits)
 *   5. /home N       (return)
 *   6. auto-disable
 *
 * Each step waits `delay` seconds so the commands read as deliberate, not a bot burst.
 */
public final class HomeMetaModule extends Module {

    private enum Step { SETHOME, CONFIRM, RTP, WAIT_TELEPORT, HOME, DONE }

    private final IntSetting homeNumber = add(new IntSetting("home-number", "Home number", 1, 1, 10, 1)
        .description("Which /home slot to set and return to.").group("General"));
    private final IntSetting delay = add(new IntSetting("delay", "Delay between steps (s)", 2, 1, 20, 1)
        .description("Seconds between each command so they read as deliberate, not a macro burst.")
        .group("General"));
    private final IntSetting rtpTimeout = add(new IntSetting("rtp-timeout", "RTP timeout (s)", 15, 3, 60, 1)
        .description("Give up waiting for the teleport after this long and go home anyway.")
        .group("General"));
    private final BoolSetting autoDisable = add(new BoolSetting("auto-disable", "Auto-disable after", true)
        .description("Turn the module off once the sequence completes.")
        .group("General"));

    private Step step = Step.SETHOME;
    private long nextAtMs = 0;
    private long rtpDeadlineMs = 0;
    private Vec3 rtpStart = null;

    public HomeMetaModule() {
        super(SeedcrackerAddon.ID + ":home-meta", "Home Meta",
            "One-shot: /sethome, /rtp away, then /home back (with delays so it reads as deliberate).");
    }

    @Override
    public void onEnable() {
        step = Step.SETHOME;
        nextAtMs = 0;
        rtpStart = null;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) {
            setEnabledSilently(false);
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextAtMs) return;
        // Jittered: exact fixed gaps between sethome/rtp/home read as a scripted chain in logs.
        long delayMs = com.autism.seedcracker.util.Humanizer.delayMs((long) (delay.get() * 1000.0));

        switch (step) {
            case SETHOME -> {
                command(mc, "sethome " + homeNumber.get());
                step = Step.CONFIRM;
                nextAtMs = now + delayMs;
            }
            case CONFIRM -> {
                command(mc, "sethome " + homeNumber.get());
                step = Step.RTP;
                nextAtMs = now + delayMs;
            }
            case RTP -> {
                rtpStart = mc.player.position();
                command(mc, "rtp");
                rtpDeadlineMs = now + (long) (rtpTimeout.get() * 1000.0);
                step = Step.WAIT_TELEPORT;
                nextAtMs = now + delayMs;
            }
            case WAIT_TELEPORT -> {
                boolean moved = mc.player.position().distanceToSqr(rtpStart) > 25.0;
                if (moved || now >= rtpDeadlineMs) {
                    step = Step.HOME;
                    nextAtMs = now + delayMs;
                }
            }
            case HOME -> {
                command(mc, "home " + homeNumber.get());
                step = Step.DONE;
                if (autoDisable.get()) setEnabledSilently(false);
            }
            case DONE -> { if (autoDisable.get()) setEnabledSilently(false); }
        }
    }

    private static void command(Minecraft mc, String cmd) {
        if (mc.getConnection() != null) mc.getConnection().sendCommand(cmd);
    }

    @Override
    public String info() {
        return step.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }
}
