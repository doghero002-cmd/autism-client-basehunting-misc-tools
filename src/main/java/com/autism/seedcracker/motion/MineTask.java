package com.autism.seedcracker.motion;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Legit mine loop for {@code .goto mine-ore}: only ores that touch air (a player could see them),
 * nearest first. Walks next to one with {@link Motion}, breaks it, repeats until {@code count} are
 * mined or none are visible. Ores it couldn't reach are skipped for the rest of the run.
 */
public final class MineTask {
    private MineTask() {}

    private static final int SCAN_CHUNKS = 3;
    private static final int MAX_CANDIDATES = 24;
    private static final String ROT = "mine-task";

    private static Set<Block> targets;
    private static int remaining;
    private static int mined;
    private static BlockPos current;
    private static final Set<BlockPos> blacklist = new HashSet<>();
    private static int breakTicks;
    private static boolean walking;
    private static int failedTrips;
    private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
    private static String status = "idle";
    private static boolean collectDrops = true;
    /** Where the last ore was mined and how long we've spent picking up what it dropped. */
    private static BlockPos collectAt;
    private static int collectTicks;
    private static final int COLLECT_TICKS = 20 * 6;
    private static final double COLLECT_RADIUS = 4.5;

    /** Walk over to the items a mined ore dropped before moving on (GoTo setting). */
    public static void setCollectDrops(boolean on) {
        collectDrops = on;
    }

    public static boolean start(Minecraft mc, Set<Block> blocks, int count) {
        if (mc.player == null || blocks.isEmpty()) return false;
        stop(mc);
        targets = Set.copyOf(blocks);
        remaining = Math.max(1, count);
        mined = 0;
        walking = false;
        failedTrips = 0;
        dimension = mc.level == null ? null : mc.level.dimension();
        blacklist.clear();
        collectFailed.clear();
        return next(mc);
    }

    public static boolean active() {
        return targets != null;
    }

    public static String status() {
        return status;
    }

    public static void stop(Minecraft mc) {
        if (targets != null && current != null && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        boolean was = targets != null;
        targets = null;
        current = null;
        walking = false;
        collectAt = null;
        collectFailed.clear();
        RotationEngine.release(ROT);
        if (was && Motion.isBusy()) Motion.stop(mc);
    }

    /** Drive from the GoTo module tick, after Motion.tick. */
    public static void tick(Minecraft mc) {
        if (targets == null || mc.player == null || mc.level == null) return;
        // Died, changed dimension, or the player took over and stopped the trip: don't resume on our own.
        if (!mc.player.isAlive()) {
            finish("stopped: died (" + mined + " mined)");
            return;
        }
        if (dimension != null && dimension != mc.level.dimension()) {
            finish("stopped: changed dimension (" + mined + " mined)");
            return;
        }
        dimension = mc.level.dimension();
        if (collectAt != null) {
            tickCollect(mc);
            return;
        }
        if (Motion.isBusy()) {
            status = "walking to ore (" + mined + " mined)";
            return;
        }
        if (walking) {
            walking = false;
            boolean arrived = "arrived".equals(Motion.status());
            // The trip ended; it may have ended next to a different ore than the one we picked from afar.
            current = reachable(mc);
            if (current == null) {
                // The trip headed for the nearest ore and didn't get there: blacklist the ORE (not the near-goal
                // point), so every retry doesn't re-pick the same unreachable one. Then give up after a few.
                if (!arrived) {
                    if (tripTarget != null) blacklist.add(tripTarget);
                    else if (Motion.goal() != null) blacklist.add(Motion.goal());
                }
                tripTarget = null;
                if (++failedTrips >= 3) {
                    finish("couldn't reach the visible ore (" + mined + " mined)");
                    return;
                }
            } else {
                failedTrips = 0;
                tripTarget = null;
            }
        }
        if (current == null) {
            if (!next(mc)) finish("no visible ore left (" + mined + " mined)");
            return;
        }
        BlockState s = mc.level.getBlockState(current);
        if (!targets.contains(s.getBlock())) {
            mined++;
            BlockPos done = current;
            current = null;
            breakTicks = 0;
            if (--remaining <= 0) {
                finish("done: " + mined + " mined");
                return;
            }
            if (collectDrops) {
                collectAt = done;
                collectTicks = 0;
            }
            return;
        }
        var sight = sightLine(mc, current);
        if (sight == null || unsafeToBreak(mc, current)) {
            blacklist.add(current);
            current = null;
            breakTicks = 0;
            return;
        }
        if (!RotationEngine.lookAt(ROT, RotationEngine.PRIORITY_INTERACT, sight.getLocation())) return;
        if (breakTicks == 0 && ToolPicker.equip(mc, s)) return;
        var face = sight.getDirection();
        if (breakTicks++ == 0) mc.gameMode.startDestroyBlock(current, face);
        else mc.gameMode.continueDestroyBlock(current, face);
        mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        status = "mining " + current.toShortString() + " (" + mined + " mined)";
        if (breakTicks > 20 * 15) {
            blacklist.add(current);
            mc.gameMode.stopDestroyBlock();
            current = null;
            breakTicks = 0;
        }
    }

    /**
     * After an ore breaks: walk over its drops (they land within a block or two, or roll into a hole) until
     * they're picked up or a few seconds pass. Items further than a short walk are left.
     */
    private static void tickCollect(Minecraft mc) {
        if (Motion.isBusy()) {
            status = "collecting drops";
            if (++collectTicks > COLLECT_TICKS * 2) Motion.stop(mc);
            return;
        }
        var items = mc.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
            new net.minecraft.world.phys.AABB(collectAt).inflate(COLLECT_RADIUS), e -> e.isAlive() && !e.getItem().isEmpty());
        // Vanilla picks up anything within 1 block sideways / 0.5 vertically of the hitbox once the pickup delay ends.
        var pickup = mc.player.getBoundingBox().inflate(1.0, 0.5, 1.0);
        items.removeIf(e -> pickup.intersects(e.getBoundingBox()));
        // Skip items we already failed to reach (fell into an unreachable spot): don't spam a fresh plan every tick.
        items.removeIf(e -> collectFailed.contains(e.blockPosition()));
        if (items.isEmpty() || ++collectTicks > COLLECT_TICKS || mc.player.getInventory().getFreeSlot() < 0) {
            collectAt = null;
            return;
        }
        var nearest = items.stream().min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(mc.player))).get();
        if (!Motion.goTo(mc, nearest.blockPosition(), Motion.Backend.BUILT_IN, false)) {
            // Blacklist the unreachable item (and anything sharing its cell) so the next tick doesn't retry it.
            collectFailed.add(nearest.blockPosition());
            collectAt = null;
        } else status = "collecting drops";
    }

    /** Item cells we already failed to path to this trip (fell somewhere unreachable). */
    private static final Set<BlockPos> collectFailed = new HashSet<>();

    /**
     * Breaking it would drop us (it's under our feet with a hole or lava below) or let lava/water in on us.
     * The walk there checks its own breaks; this is for the ore itself.
     */
    private static boolean unsafeToBreak(Minecraft mc, BlockPos p) {
        for (var d : net.minecraft.core.Direction.values()) {
            if (d == net.minecraft.core.Direction.DOWN) continue;
            if (mc.level.getBlockState(p.relative(d)).getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) return true;
        }
        var box = mc.player.getBoundingBox();
        boolean underUs = p.getY() == (int) Math.floor(box.minY - 0.01)
            && p.getX() >= Math.floor(box.minX) && p.getX() <= Math.floor(box.maxX)
            && p.getZ() >= Math.floor(box.minZ) && p.getZ() <= Math.floor(box.maxZ);
        if (!underUs) return false;
        // Still standing on something else after it goes?
        for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX); x++) {
            for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ); z++) {
                BlockPos f = new BlockPos(x, p.getY(), z);
                if (!f.equals(p) && !mc.level.getBlockState(f).getCollisionShape(mc.level, f).isEmpty()) return false;
            }
        }
        BlockPos below = p.below();
        var bs = mc.level.getBlockState(below);
        return bs.getCollisionShape(mc.level, below).isEmpty() || !bs.getFluidState().isEmpty();
    }

    private static boolean next(Minecraft mc) {
        breakTicks = 0;
        BlockPos inReach = reachable(mc);
        if (inReach != null) {
            current = inReach;
            return true;
        }
        List<BlockPos> ores = scan(mc);
        if (ores.isEmpty()) return false;
        current = null;
        // Walk to within reach of any visible ore; the planner picks whichever is cheapest to reach.
        // Remember the nearest so a failed trip blacklists the ORE (not the near-goal point), or every
        // retry re-picks the same unreachable ore forever (the flood/no-path loop).
        tripTarget = ores.get(0);
        if (!Motion.goNearAny(mc, ores, 3, true)) { blacklist.add(tripTarget); tripTarget = null; return false; }
        walking = true;
        status = "heading to " + ores.size() + " visible ore";
        return true;
    }

    /** The ore the current trip is heading for (blacklisted if the trip floods/fails). */
    private static BlockPos tripTarget;

    /** Nearest exposed target ore we can see, reach and safely break from here, or null. */
    private static BlockPos reachable(Minecraft mc) {
        for (BlockPos p : scan(mc)) {
            if (sightLine(mc, p) != null && !unsafeToBreak(mc, p)) return p;
        }
        return null;
    }

    /**
     * Ray from the eyes that hits {@code p} first (centre, else the middle of an open face), within the
     * player's real reach. Mining through another block is what reach/raytrace anti-cheats flag.
     */
    private static net.minecraft.world.phys.BlockHitResult sightLine(Minecraft mc, BlockPos p) {
        if (!mc.player.isWithinBlockInteractionRange(p, 0)) return null;
        var eye = mc.player.getEyePosition();
        var centre = net.minecraft.world.phys.Vec3.atCenterOf(p);
        var hit = clip(mc, eye, centre, p);
        if (hit != null) return hit;
        for (var d : net.minecraft.core.Direction.values()) {
            if (!mc.level.getBlockState(p.relative(d)).isAir()) continue;
            hit = clip(mc, eye, centre.add(d.getStepX() * 0.45, d.getStepY() * 0.45, d.getStepZ() * 0.45), p);
            if (hit != null) return hit;
        }
        return null;
    }

    private static net.minecraft.world.phys.BlockHitResult clip(Minecraft mc, net.minecraft.world.phys.Vec3 from,
                                                                net.minecraft.world.phys.Vec3 to, BlockPos want) {
        var r = mc.level.clip(new net.minecraft.world.level.ClipContext(from, to,
            net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
        return r.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && r.getBlockPos().equals(want)
            && from.distanceToSqr(r.getLocation()) <= sq(mc.player.blockInteractionRange()) ? r : null;
    }

    private static double sq(double v) {
        return v * v;
    }

    private static void finish(String why) {
        status = why;
        Minecraft mc = Minecraft.getInstance();
        stop(mc);
        com.autism.seedcracker.compat.ClientNotify.success("[Mine] " + why);
    }

    /** Exposed target ores in the loaded chunks around the player, nearest first. */
    private static List<BlockPos> scan(Minecraft mc) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos me = mc.player.blockPosition();
        int pcx = me.getX() >> 4, pcz = me.getZ() >> 4;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int cx = pcx - SCAN_CHUNKS; cx <= pcx + SCAN_CHUNKS; cx++) {
            for (int cz = pcz - SCAN_CHUNKS; cz <= pcz + SCAN_CHUNKS; cz++) {
                if (!mc.level.hasChunk(cx, cz)) continue;
                var chunk = mc.level.getChunk(cx, cz);
                var sections = chunk.getSections();
                for (int si = 0; si < sections.length; si++) {
                    var sec = sections[si];
                    if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(st -> targets.contains(st.getBlock()))) continue;
                    int baseY = mc.level.getSectionYFromSectionIndex(si) << 4;
                    for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        if (!targets.contains(sec.getBlockState(x, y, z).getBlock())) continue;
                        m.set((cx << 4) + x, baseY + y, (cz << 4) + z);
                        if (blacklist.contains(m) || !exposed(mc, m)) continue;
                        out.add(m.immutable());
                    }
                }
            }
        }
        out.sort((a, b) -> Double.compare(a.distSqr(me), b.distSqr(me)));
        return out.size() > MAX_CANDIDATES ? new ArrayList<>(out.subList(0, MAX_CANDIDATES)) : out;
    }

    private static boolean exposed(Minecraft mc, BlockPos p) {
        for (var d : net.minecraft.core.Direction.values()) {
            if (mc.level.getBlockState(p.relative(d)).isAir()) return true;
        }
        return false;
    }
}
