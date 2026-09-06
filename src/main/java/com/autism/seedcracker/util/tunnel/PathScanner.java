package com.autism.seedcracker.util.tunnel;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Scans the path ahead for hazards while tunneling: fluids (lava/water), falling blocks
 * (sand/gravel), unsafe ground (drops), dangerous blocks (fire, magma, cactus, powder snow),
 * and "sandwich traps" (a solid block at foot level with air above and a solid ceiling, which
 * would suffocate the player).
 *
 * Port of the Krypton AI PathScanner (dev.FORE.AI) to Mojang 26.2 mappings. The scan returns a
 * {@link ScanResult} describing whether the path is safe and, if not, what hazard was hit and how
 * far ahead it is.
 */
public final class PathScanner {
    private final Minecraft mc = Minecraft.getInstance();

    private int scanWidthFallingBlocks = 2;
    private int scanWidthFluids = 4;
    private int tunnelWidth = 3;
    private int tunnelHeight = 3;

    public void updateScanWidths(int fallingBlocks, int fluids) {
        this.scanWidthFallingBlocks = fallingBlocks;
        this.scanWidthFluids = fluids;
    }

    public void updateTunnelDimensions(int width, int height) {
        this.tunnelWidth = width;
        this.tunnelHeight = height;
    }

    public enum HazardType {
        NONE, LAVA, WATER, FALLING_BLOCK, UNSAFE_GROUND, DANGEROUS_BLOCK, SANDWICH_TRAP
    }

    public static final class ScanResult {
        private final boolean safe;
        private final HazardType hazardType;
        private final int hazardDistance;
        private final Set<BlockPos> hazardPositions;

        public ScanResult(boolean safe, HazardType hazardType, int hazardDistance) {
            this(safe, hazardType, hazardDistance, new HashSet<>());
        }

        public ScanResult(boolean safe, HazardType hazardType, int hazardDistance, Set<BlockPos> hazardPositions) {
            this.safe = safe;
            this.hazardType = hazardType;
            this.hazardDistance = hazardDistance;
            this.hazardPositions = hazardPositions;
        }

        public boolean isSafe() { return safe; }
        public HazardType getHazardType() { return hazardType; }
        public int getHazardDistance() { return hazardDistance; }
        public Set<BlockPos> getHazardPositions() { return hazardPositions; }
    }

    /** Scan forward from {@code start} in {@code direction} for {@code depth} blocks. */
    public ScanResult scanDirection(BlockPos start, Direction direction, int depth, int height, boolean strictGround) {
        Level world = mc.level;
        Set<BlockPos> detected = new HashSet<>();
        if (world == null) return new ScanResult(true, HazardType.NONE, -1, detected);

        SandwichTrapResult trap = checkForSandwichTraps(world, start, direction, depth);
        if (trap.found) {
            detected.add(trap.hazardPos);
            return new ScanResult(false, HazardType.SANDWICH_TRAP, trap.distance, detected);
        }

        for (int forward = 1; forward <= depth; forward++) {
            // Fluids sweep (wider).
            for (int sideways = -scanWidthFluids; sideways <= scanWidthFluids; sideways++) {
                for (int vertical = -1; vertical <= height; vertical++) {
                    if (vertical < 0 && sideways != 0) continue;
                    BlockPos checkPos = offsetPosition(start, direction, forward, sideways, vertical);
                    HazardType hazard = checkForFluids(world.getBlockState(checkPos));
                    if (hazard != HazardType.NONE) {
                        detected.add(checkPos.immutable());
                        return new ScanResult(false, hazard, forward, detected);
                    }
                }
            }

            // Falling / ground / dangerous sweep (narrower).
            for (int sideways = -scanWidthFallingBlocks; sideways <= scanWidthFallingBlocks; sideways++) {
                for (int vertical = -1; vertical <= height; vertical++) {
                    BlockPos checkPos = offsetPosition(start, direction, forward, sideways, vertical);
                    boolean checkFalling = sideways == 0 && vertical == 3;
                    HazardType hazard = checkBlock(world, checkPos, vertical, strictGround, forward, sideways, checkFalling);
                    if (hazard != HazardType.NONE) {
                        // Grace: a single unsafe ground right at our feet is fine if there's ground within 2 below.
                        if (hazard == HazardType.UNSAFE_GROUND && forward == 1 && vertical == -1) {
                            if (hasWalkableWithin(world, checkPos, 2)) continue;
                        }
                        detected.add(checkPos.immutable());
                        return new ScanResult(false, hazard, forward, detected);
                    }
                }
            }
        }
        return new ScanResult(true, HazardType.NONE, -1, detected);
    }

    private boolean hasWalkableWithin(Level world, BlockPos pos, int maxDown) {
        for (int i = 0; i <= maxDown; i++) {
            BlockPos p = pos.below(i);
            if (canWalkOn(world, p, world.getBlockState(p))) return true;
        }
        return false;
    }

    private static final class SandwichTrapResult {
        final boolean found;
        final int distance;
        final BlockPos hazardPos;
        SandwichTrapResult(boolean found, int distance, BlockPos hazardPos) {
            this.found = found;
            this.distance = distance;
            this.hazardPos = hazardPos;
        }
    }

    /** Detect the foot-block + air-gap + ceiling pattern that suffocates a player. */
    private SandwichTrapResult checkForSandwichTraps(Level world, BlockPos start, Direction direction, int maxDepth) {
        BlockPos ceiling = start.above(2);
        BlockPos footAhead = start.relative(direction);
        BlockPos headAhead = start.relative(direction).above();
        if (!world.getBlockState(ceiling).isAir() && !world.getBlockState(footAhead).isAir()
            && world.getBlockState(headAhead).isAir()) {
            return new SandwichTrapResult(true, 1, footAhead);
        }
        BlockPos ceilingAhead = start.relative(direction).above(2);
        if (!world.getBlockState(footAhead).isAir() && world.getBlockState(headAhead).isAir()
            && !world.getBlockState(ceilingAhead).isAir()) {
            return new SandwichTrapResult(true, 1, footAhead);
        }
        for (int distance = 2; distance <= Math.min(maxDepth, 8); distance++) {
            BlockPos checkPos = start.relative(direction, distance);
            BlockPos p1Ceiling = checkPos.above(2);
            BlockPos p1Foot = checkPos.relative(direction);
            BlockPos p1Head = checkPos.relative(direction).above();
            if (!world.getBlockState(p1Ceiling).isAir() && !world.getBlockState(p1Foot).isAir()
                && world.getBlockState(p1Head).isAir()) {
                return new SandwichTrapResult(true, distance + 1, p1Foot);
            }
            BlockPos p2Head = checkPos.above();
            BlockPos p2Ceiling = checkPos.above(2);
            if (!world.getBlockState(checkPos).isAir() && world.getBlockState(p2Head).isAir()
                && !world.getBlockState(p2Ceiling).isAir()) {
                return new SandwichTrapResult(true, distance, checkPos);
            }
        }
        return new SandwichTrapResult(false, -1, null);
    }

    private HazardType checkForFluids(BlockState state) {
        if (state.is(Blocks.LAVA)) return HazardType.LAVA;
        if (state.is(Blocks.WATER)) return HazardType.WATER;
        return HazardType.NONE;
    }

    private HazardType checkBlock(Level world, BlockPos pos, int yOffset, boolean strictGround,
                                  int forwardDist, int sidewaysDist, boolean checkFalling) {
        BlockState state = world.getBlockState(pos);
        Block block = state.getBlock();

        if (yOffset == -1 && sidewaysDist == 0) {
            if (!canWalkOn(world, pos, state)) {
                if (strictGround) return HazardType.UNSAFE_GROUND;
                if (!hasWalkableWithin(world, pos.below(), 2)) return HazardType.UNSAFE_GROUND;
            }
        }

        if (checkFalling && isFallingBlock(block)) return HazardType.FALLING_BLOCK;
        return isDangerousBlock(block) ? HazardType.DANGEROUS_BLOCK : HazardType.NONE;
    }

    /** True if there's a 1-2 block drop ahead (used to decide walking vs jumping). */
    public boolean hasDropAhead(BlockPos playerPos, Direction direction) {
        Level world = mc.level;
        if (world == null) return false;
        BlockPos frontGround = playerPos.relative(direction).below();
        BlockState frontGroundState = world.getBlockState(frontGround);
        BlockState frontFootState = world.getBlockState(playerPos.relative(direction));
        if (frontFootState.isAir() && !frontGroundState.isAir()) {
            return world.getBlockState(frontGround.below()).isAir();
        }
        return false;
    }

    private boolean canWalkOn(Level world, BlockPos pos, BlockState state) {
        if (state.isAir()) return false;
        if (state.is(Blocks.FIRE) || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.LAVA)) return false;
        // Solid enough to stand on (has a collision shape) and not dangerous.
        return !state.getCollisionShape(world, pos).isEmpty();
    }

    private boolean isFallingBlock(Block block) {
        return block == Blocks.GRAVEL || block == Blocks.SAND || block == Blocks.RED_SAND
            || block == Blocks.ANVIL || block == Blocks.POWDER_SNOW || block instanceof FallingBlock;
    }

    private boolean isDangerousBlock(Block block) {
        return block == Blocks.FIRE || block == Blocks.MAGMA_BLOCK || block == Blocks.CAMPFIRE
            || block == Blocks.SOUL_CAMPFIRE || block == Blocks.CACTUS || block == Blocks.SWEET_BERRY_BUSH
            || block == Blocks.POWDER_SNOW || block == Blocks.LAVA || block == Blocks.WITHER_ROSE
            || block == Blocks.POINTED_DRIPSTONE;
    }

    private static BlockPos offsetPosition(BlockPos start, Direction forward, int forwardDist, int sidewaysDist, int verticalDist) {
        Direction left = forward.getCounterClockWise();
        return start.relative(forward, forwardDist).relative(left, sidewaysDist).relative(Direction.UP, verticalDist);
    }
}
