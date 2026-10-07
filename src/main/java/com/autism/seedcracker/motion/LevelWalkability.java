package com.autism.seedcracker.motion;

import com.autism.seedcracker.motion.pure.GridPathfinder;
import com.autism.seedcracker.motion.pure.GridPathfinder.Kind;
import com.autism.seedcracker.util.tunnel.Hazards;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Classifies blocks for {@link GridPathfinder}. {@link #live} reads the client level (game thread
 * only); {@link #snapshot} reads a {@link ChunkSnapshot} and is safe on the planner thread. Every
 * player-dependent input (tools, effects, mobs, border) is captured when it's built.
 */
public final class LevelWalkability implements GridPathfinder.World {

    private final Minecraft mc;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final net.minecraft.world.level.BlockGetter blocks;
    private final ChunkSnapshot snapshot;
    private final ToolPicker.MiningProfile mining;
    private final double borderMinX, borderMaxX, borderMinZ, borderMaxZ;
    private final int minY, maxY;

    /** Packed cell -> avoid cost, built once per plan from hostile mobs near the player. */
    private final java.util.Map<Long, Double> avoid = new java.util.HashMap<>();
    /** Bounding box of every avoid cell: most offers are nowhere near a mob and skip the map lookup. */
    private int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

    private LevelWalkability(Minecraft mc, boolean avoidMobs, ChunkSnapshot snapshot) {
        this.mc = mc;
        this.snapshot = snapshot;
        this.blocks = snapshot != null ? snapshot : mc.level;
        this.mining = ToolPicker.MiningProfile.capture(mc);
        var border = mc.level.getWorldBorder();
        borderMinX = border.getMinX();
        borderMaxX = border.getMaxX();
        borderMinZ = border.getMinZ();
        borderMaxZ = border.getMaxZ();
        minY = mc.level.getMinY();
        maxY = mc.level.getMaxY();
        if (avoidMobs) snapshotMobs();
    }

    /** Reads the live level: game thread only (the follower's per-tick re-pricing). */
    public static LevelWalkability live(Minecraft mc, boolean avoidMobs) {
        return new LevelWalkability(mc, avoidMobs, null);
    }

    /** Reads chunks captured now: safe to search on the planner thread. Call on the game thread. */
    public static LevelWalkability snapshot(Minecraft mc, boolean avoidMobs, int radiusChunks) {
        return new LevelWalkability(mc, avoidMobs, ChunkSnapshot.capture(mc, radiusChunks));
    }

    private BlockState state(int x, int y, int z) {
        if (snapshot != null) return snapshot.state(x, y, z);
        pos.set(x, y, z);
        return mc.level.getBlockState(pos);
    }

    private boolean loaded(int cx, int cz) {
        return snapshot != null ? snapshot.hasChunk(cx, cz) : mc.level != null && mc.level.hasChunk(cx, cz);
    }

    /** Packed blocks the server refused to let us break this session (claims, spawn protection): never plan through them. */
    private java.util.Set<Long> refused = java.util.Set.of();

    /** {@code blocks} is copied: the planner thread must never see the game thread adding to it. */
    public LevelWalkability withRefusedBreaks(java.util.Collection<Long> blocks) {
        refused = java.util.Set.copyOf(blocks);
        return this;
    }

    /** Charges the cells where recent trips got stuck, so a replan tries another way first. */
    public LevelWalkability withFailures(com.autism.seedcracker.motion.pure.FailureMemory memory, long now) {
        memory.forEachActive(now, (x, y, z, cost) -> {
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
            avoid.merge(GridPathfinder.pack(x, y, z), cost, Double::sum);
        });
        return this;
    }

    @Override
    public double avoidCost(int x, int y, int z) {
        if (avoid.isEmpty() || x < minX || x > maxX || z < minZ || z > maxZ) return 0;
        Double c = avoid.get(GridPathfinder.pack(x, y, z));
        return c == null ? 0 : c;
    }

    // Cells within 3 blocks of a hostile get a cost that falls off with distance (creepers count double).
    private void snapshotMobs() {
        if (mc.player == null || mc.level == null) return;
        net.minecraft.world.phys.AABB box = mc.player.getBoundingBox().inflate(48, 16, 48);
        for (var e : mc.level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, box,
                m -> m instanceof net.minecraft.world.entity.monster.Enemy && m.isAlive() && threatens(m))) {
            double weight = e instanceof net.minecraft.world.entity.monster.Creeper ? 2.0 : 1.0;
            BlockPos c = e.blockPosition();
            minX = Math.min(minX, c.getX() - 3);
            maxX = Math.max(maxX, c.getX() + 3);
            minZ = Math.min(minZ, c.getZ() - 3);
            maxZ = Math.max(maxZ, c.getZ() + 3);
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -1; dy <= 1; dy++) {
                double d = Math.sqrt(dx * dx + dz * dz + dy * dy);
                if (d > 3.5) continue;
                avoid.merge(GridPathfinder.pack(c.getX() + dx, c.getY() + dy, c.getZ() + dz), weight * (12 - d * 3), Double::sum);
            }
        }
    }

    /** Neutral mobs only matter once provoked; walking a wide berth around every one wastes the route. */
    private boolean threatens(net.minecraft.world.entity.Mob m) {
        if (m instanceof net.minecraft.world.entity.monster.EnderMan ender) return ender.isCreepy();
        if (m instanceof net.minecraft.world.entity.monster.zombie.ZombifiedPiglin) return m.isAggressive();
        // Piglins leave gold-wearers alone.
        if (m instanceof net.minecraft.world.entity.monster.piglin.Piglin) {
            return m.isAggressive() || !net.minecraft.world.entity.monster.piglin.PiglinAi.isWearingSafeArmor(mc.player);
        }
        // Spiders are passive in bright light unless already chasing.
        if (m instanceof net.minecraft.world.entity.monster.spider.Spider) {
            return m.isAggressive() || m.getLightLevelDependentMagicValue() < 0.5f;
        }
        return true;
    }

    /** Closed door/fence gate a player can open by hand (iron ones need redstone: treat as walls). */
    private static boolean isOpenableClosed(BlockState s) {
        if (s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)
            && s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)) return false;
        if (s.getBlock() instanceof net.minecraft.world.level.block.DoorBlock door) return door.type().canOpenByHand();
        return s.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock;
    }

    /**
     * Kind per block state. Shared across plans and threads: a state classifies the same everywhere
     * unless its shape depends on position (offset plants, modded dynamic shapes), which skip it.
     */
    private static final java.util.concurrent.ConcurrentHashMap<BlockState, Kind> STATE_KINDS = new java.util.concurrent.ConcurrentHashMap<>();

    /** New world or server: block tags (climbable etc.) come from its datapacks and may differ. */
    static void clearStateCache() {
        STATE_KINDS.clear();
    }

    @Override
    public Kind kind(int x, int y, int z) {
        if (!loaded(x >> 4, z >> 4)) return Kind.UNLOADED;
        double bx = x + 0.5, bz = z + 0.5;
        if (bx < borderMinX || bx >= borderMaxX || bz < borderMinZ || bz >= borderMaxZ) return Kind.UNLOADED;
        if (y < minY || y >= maxY) return y < minY ? Kind.DANGER : Kind.OPEN;
        BlockState s = state(x, y, z);
        if (s.isAir()) return Kind.OPEN;
        Kind cached = STATE_KINDS.get(s);
        if (cached != null) return cached;
        pos.set(x, y, z);
        Kind k = classify(s);
        if (k == null) return Kind.WALL;
        if (!s.hasOffsetFunction() && !s.getBlock().hasDynamicShape()) STATE_KINDS.put(s, k);
        return k;
    }

    /** null when the collision shape can't be computed here (then treated as a wall, never cached). */
    private Kind classify(BlockState s) {
        var fluid = s.getFluidState();
        if (fluid.is(FluidTags.LAVA)) return Kind.DANGER;
        Block b = s.getBlock();
        // Magma only burns when walked on normally: the follower sneaks across it, and speedFactor prices that crawl.
        // As DANGER it walled off whole nether floors and soul-sand valleys.
        if (b == Blocks.MAGMA_BLOCK) return Kind.SOLID;
        if (Hazards.isContactHazard(b) || b == Blocks.COBWEB || b == Blocks.LAVA_CAULDRON) return Kind.DANGER;
        // Portals teleport (end portal is a walkable 0.75 slab!); bubble columns drag you up or down.
        if (b == Blocks.NETHER_PORTAL || b == Blocks.END_PORTAL || b == Blocks.END_GATEWAY || b == Blocks.BUBBLE_COLUMN) return Kind.DANGER;
        // Tilts and drops whoever stands on it.
        if (b == Blocks.BIG_DRIPLEAF) return Kind.WALL;
        // Twisting/weeping vines and scaffolding are climbable too; powder snow is not (it's a trap).
        if (s.is(net.minecraft.tags.BlockTags.CLIMBABLE) && b != Blocks.POWDER_SNOW) return Kind.CLIMB;
        if (isOpenableClosed(s)) return Kind.DOOR;
        VoxelShape shape = shape(s);
        if (shape == null) return null;
        if (!fluid.isEmpty() && shape.isEmpty()) {
            // Flowing water shoves the player, so speedFactor prices it high; as a WALL it made every stream and
            // waterfall edge impassable. Other (modded) fluids stay walls.
            if (!fluid.is(FluidTags.WATER)) return Kind.WALL;
            // A waterfall column holds nobody up (it drags you down): plan it as air, so a route can't "stand" in
            // one halfway up a cliff and walking under a curtain at its foot still works.
            if (!fluid.isSource() && fluid.hasProperty(net.minecraft.world.level.material.FlowingFluid.FALLING)
                && fluid.getValue(net.minecraft.world.level.material.FlowingFluid.FALLING)) return Kind.OPEN;
            return Kind.WATER;
        }
        if (shape.isEmpty()) return Kind.OPEN;
        double top = shape.max(net.minecraft.core.Direction.Axis.Y);
        if (top > 1.0) return Kind.WALL;
        Kind panel = edgePanel(shape);
        if (panel != null) return panel;
        // Under ~half a block: the 0.6 step height walks onto it without jumping.
        if (top <= 0.5625) return Kind.LOW;
        return Kind.SOLID;
    }

    /** Open door / open trapdoor: a thin full-width slab hugging one edge leaves room to walk along it. */
    static Kind edgePanel(VoxelShape shape) {
        var ax = net.minecraft.core.Direction.Axis.X;
        var az = net.minecraft.core.Direction.Axis.Z;
        double x0 = shape.min(ax), x1 = shape.max(ax), z0 = shape.min(az), z1 = shape.max(az);
        boolean thinX = x1 - x0 <= 0.2 && (x1 <= 0.2 || x0 >= 0.8) && z0 <= 0.01 && z1 >= 0.99;
        boolean thinZ = z1 - z0 <= 0.2 && (z1 <= 0.2 || z0 >= 0.8) && x0 <= 0.01 && x1 >= 0.99;
        if (thinX) return Kind.PANEL_X;
        if (thinZ) return Kind.PANEL_Z;
        return null;
    }

    @Override
    public double breakCost(int x, int y, int z) {
        if (!refused.isEmpty() && refused.contains(GridPathfinder.pack(x, y, z))) return Double.POSITIVE_INFINITY;
        BlockState s = state(x, y, z);
        pos.set(x, y, z);
        float hardness = s.getDestroySpeed(blocks, pos);
        if (hardness < 0) return Double.POSITIVE_INFINITY;
        // Next to fluid: a flood. Under gravel when this block isn't gravel: a cave-in onto the head. A gravel block in a
        // gravel stack is fine (the stack is priced below and mined through as it drops).
        if (touchesFluid(x, y, z)) return Double.POSITIVE_INFINITY;
        if (!Hazards.isFalling(s) && Hazards.isFalling(state(x, y + 1, z))) return Double.POSITIVE_INFINITY;
        // Whatever the stack rests on at the top must not be fluid, or breaking it lets the fluid down too.
        if (Hazards.isFalling(s) && fluidAboveStack(x, y, z)) return Double.POSITIVE_INFINITY;
        if (s.hasBlockEntity()) return Double.POSITIVE_INFINITY;
        // Ice melts into water that floods the path; infested stone spawns silverfish.
        if (s.getBlock() == Blocks.ICE || s.getBlock() instanceof net.minecraft.world.level.block.InfestedBlock) return Double.POSITIVE_INFINITY;
        // Without a harvesting tool, obsidian-class blocks take minutes: route around them instead.
        if (!mining.canHarvest(s) && hardness > 3f) return Double.POSITIVE_INFINITY;
        double ticks = mining.breakTicks(s, hardness);
        if (Hazards.isFalling(s)) ticks += fallingStackTicks(x, y, z);
        return 1.0 + ticks / GridPathfinder.WALK_TICKS_PER_BLOCK;
    }

    private boolean fluidAboveStack(int x, int y, int z) {
        for (int dy = 1; dy <= 9; dy++) {
            BlockState above = state(x, y + dy, z);
            if (!above.getFluidState().isEmpty()) return true;
            if (!Hazards.isFalling(above)) return false;
        }
        // Taller than we'll price: treat as unsafe rather than guess.
        return true;
    }

    /** Gravel/sand piled on top drops into the gap one block at a time: each one is mined again. */
    private double fallingStackTicks(int x, int y, int z) {
        double extra = 0;
        for (int dy = 1; dy <= 8; dy++) {
            BlockState above = state(x, y + dy, z);
            if (!Hazards.isFalling(above)) break;
            pos.set(x, y + dy, z);
            extra += mining.breakTicks(above, above.getDestroySpeed(blocks, pos));
        }
        return extra;
    }

    @Override
    public double floorHeight(int x, int y, int z) {
        BlockState s = state(x, y, z);
        pos.set(x, y, z);
        VoxelShape shape = shape(s);
        return shape == null || shape.isEmpty() ? 0 : shape.max(net.minecraft.core.Direction.Axis.Y);
    }

    /** Collision shape at {@link #pos}; null when a (modded) block can't compute it from a snapshot. */
    private VoxelShape shape(BlockState s) {
        try {
            return s.getCollisionShape(blocks, pos);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public double jumpFactor(int x, int y, int z) {
        // Same order vanilla uses: the block at the feet, and only if that's neutral, the one underneath.
        float f = state(x, y, z).getBlock().getJumpFactor();
        if (f == 1.0f) f = state(x, y - 1, z).getBlock().getJumpFactor();
        return f;
    }

    @Override
    public double trampleCost(int x, int y, int z) {
        Block floor = state(x, y - 1, z).getBlock();
        Block feet = state(x, y, z).getBlock();
        // Someone's crops / a turtle nest: a landing ruins them, so only when there's no other way down.
        if (floor instanceof net.minecraft.world.level.block.FarmlandBlock || feet instanceof net.minecraft.world.level.block.TurtleEggBlock
            || floor instanceof net.minecraft.world.level.block.TurtleEggBlock) return TRAMPLE_COST;
        // Landing isn't sneaking: magma burns on the landing tick.
        return floor == Blocks.MAGMA_BLOCK ? MAGMA_LANDING_COST : 0;
    }

    private static final double TRAMPLE_COST = 25.0;
    private static final double MAGMA_LANDING_COST = 8.0;

    @Override
    public double speedFactor(int x, int y, int z) {
        BlockState feet = state(x, y, z);
        // Same block vanilla reads for movement speed: the one at the feet, else the one just under.
        float f = feet.getBlock().getSpeedFactor();
        Block floor = state(x, y - 1, z).getBlock();
        if (f == 1.0f) f = floor.getSpeedFactor();
        // Crossed sneaking (0.3x) so it doesn't burn.
        if (floor == Blocks.MAGMA_BLOCK) f = Math.min(f, SNEAK_SPEED);
        // A current pushes us sideways off the line: worth crossing a stream, not worth walking along one.
        var fluid = feet.getFluidState();
        if (!fluid.isEmpty() && !fluid.isSource() && fluid.getAmount() < 8) f = Math.min(f, FLOWING_SPEED);
        return f;
    }

    private static final float FLOWING_SPEED = 0.4f;
    private static final float SNEAK_SPEED = 0.3f;

    @Override
    public boolean softLanding(int x, int y, int z) {
        var fluid = state(x, y, z).getFluidState();
        // A source or a full (falling) column absorbs a fall; a thin flowing sheet over the floor doesn't.
        return fluid.is(FluidTags.WATER) && (fluid.isSource() || fluid.getAmount() >= 8);
    }

    private static final int[][] FLUID_SIDES = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}};

    private boolean touchesFluid(int x, int y, int z) {
        for (int[] d : FLUID_SIDES) {
            if (!state(x + d[0], y + d[1], z + d[2]).getFluidState().isEmpty()) return true;
        }
        return false;
    }
}
