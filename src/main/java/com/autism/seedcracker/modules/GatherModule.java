package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.motion.MineTask;
import com.autism.seedcracker.motion.Motion;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Block;

import java.util.HashSet;
import java.util.Set;

/**
 * Periodic block gatherer: every {@code interval} seconds, scan for the configured block(s) near the
 * player (e.g. oak_log, dirt, sand), walk over, mine {@code amount} of them (plus collect the drops),
 * then idle until the next cycle. Runs standalone, or — with {@code pause-goto} — briefly pauses an
 * active .goto trip to gather, then resumes the trip.
 *
 * Uses {@link MineTask} for the actual mine loop, so it shares the exposed-block scan, reach checks,
 * and drop collection.
 */
public final class GatherModule extends Module {

    private final StringSetting blocks = add(new StringSetting("blocks", "Blocks", "oak_log")
        .description("Comma-separated block ids to gather, e.g. oak_log,spruce_log or dirt,sand.")
        .group("General"));
    private final IntSetting interval = add(new IntSetting("interval", "Interval (s)", 20, 3, 600, 1)
        .description("Seconds between gather cycles.")
        .group("General"));
    private final IntSetting amount = add(new IntSetting("amount", "Amount per cycle", 8, 1, 64, 1)
        .description("How many blocks to mine each cycle.")
        .group("General"));
    private final BoolSetting pauseGoto = add(new BoolSetting("pause-goto", "Pause .goto to gather", true)
        .description("Pause the current .goto trip while a gather cycle runs, then resume it.")
        .group("General"));

    private int ticksUntilNext;
    private int emptyCycles;
    private boolean gathering;
    private boolean pausedTrip;
    private net.minecraft.core.BlockPos savedGoal;
    private String status = "idle";

    public GatherModule() {
        super(SeedcrackerAddon.ID + ":gather", "Gather",
            "Periodically mine nearby blocks (wood, dirt, etc.) and collect the drops.");
    }

    @Override
    public void onEnable() {
        ticksUntilNext = 20; // first cycle ~1s in
        emptyCycles = 0;
        gathering = false;
        pausedTrip = false;
        savedGoal = null;
        status = "idle";
    }

    @Override
    public void onDisable() {
        if (gathering) MineTask.stop(Minecraft.getInstance());
        gathering = false;
        pausedTrip = false;
        savedGoal = null;
        status = "idle";
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) { status = "idle"; return; }

        // A gather cycle is running: watch it finish.
        if (gathering) {
            if (!MineTask.active()) {
                gathering = false;
                emptyCycles = MineTask.status().startsWith("done") ? 0 : emptyCycles + 1;
                // Back off exponentially after empty/unreachable cycles (cap 8x) so we don't hot-loop a doomed mine.
                ticksUntilNext = interval.get() * 20 * Math.min(8, 1 + emptyCycles);
                status = "idle";
                resumeTrip(mc);
            } else {
                status = "gathering (" + MineTask.status() + ")";
            }
            return;
        }

        // Count down to the next cycle.
        if (--ticksUntilNext > 0) { status = "waiting"; return; }

        Set<Block> targets = parseBlocks();
        if (targets.isEmpty()) { status = "bad block ids"; return; }

        // Optionally pause an active trip so the gather can run, then resume it after.
        if (pauseGoto.get() && Motion.isBusy()) {
            savedGoal = Motion.goal();
            Motion.stop(mc);
            pausedTrip = true;
        }

        if (!MineTask.start(mc, targets, amount.get())) {
            status = "nothing nearby";
            emptyCycles++;
            ticksUntilNext = interval.get() * 20 * Math.min(8, 1 + emptyCycles); // nothing found: back off
            resumeTrip(mc);
            return;
        }
        gathering = true;
        status = "gathering";
    }

    private void resumeTrip(Minecraft mc) {
        if (!pausedTrip) return;
        pausedTrip = false;
        if (savedGoal != null && pauseGoto.get()) {
            Motion.goToXZ(mc, savedGoal.getX(), savedGoal.getZ(), Motion.Backend.BUILT_IN, false);
            savedGoal = null;
        }
    }

    private Set<Block> parseBlocks() {
        Set<Block> out = new HashSet<>();
        for (String part : blocks.get().split(",")) {
            String id = part.trim();
            if (id.isEmpty()) continue;
            var key = net.minecraft.resources.Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
            if (key == null) continue;
            net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(key).ifPresent(out::add);
        }
        return out;
    }

    @Override
    public String info() {
        return gathering ? "gathering" : status;
    }
}
