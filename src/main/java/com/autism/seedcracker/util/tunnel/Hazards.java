package com.autism.seedcracker.util.tunnel;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Shared hazard classification for the tunnel bots (single source of truth - the Finder and
 * Water modules previously each had their own drifting copies).
 *
 * Fluid checks use the block's FLUID STATE, which catches flowing lava/water and waterlogged
 * blocks that a block-identity compare misses. Gravity blocks use the FallingBlock class so all
 * sands / concrete powders / anvils / dripstone are covered.
 */
public final class Hazards {

    private Hazards() {}

    /** Any liquid (source or flowing) or waterlogged block. */
    public static boolean isLiquid(Minecraft mc, BlockPos pos) {
        return !mc.level.getBlockState(pos).getFluidState().isEmpty();
    }

    /** Lava specifically (the liquid we can't swim out of). */
    public static boolean isLava(Minecraft mc, BlockPos pos) {
        return mc.level.getBlockState(pos).getFluidState().is(FluidTags.LAVA);
    }

    /** Gravity block that falls when its support is disturbed. */
    public static boolean isFalling(BlockState state) {
        return state.getBlock() instanceof FallingBlock;
    }

    /** Blocks that hurt on contact while tunneling through/over them. */
    public static boolean isContactHazard(Block b) {
        return b == Blocks.MAGMA_BLOCK || b == Blocks.FIRE || b == Blocks.SOUL_FIRE
            || b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE
            || b == Blocks.CACTUS || b == Blocks.SWEET_BERRY_BUSH
            || b == Blocks.POWDER_SNOW || b == Blocks.WITHER_ROSE
            || b == Blocks.POINTED_DRIPSTONE || b == Blocks.TNT;
    }

    /** Full hazard check: liquid, gravity block, or contact hazard at the position. */
    public static boolean isHazardous(Minecraft mc, BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) return true;
        if (isFalling(state)) return true;
        return isContactHazard(state.getBlock());
    }

    /**
     * Hazard hanging above a position within {@code maxHeight} blocks. A solid non-hazard roof
     * shields anything higher (mirrors what would actually fall/flow on us).
     */
    public static boolean hasHazardAbove(Minecraft mc, BlockPos headPos, int maxHeight) {
        for (int y = 1; y <= maxHeight; y++) {
            BlockPos above = headPos.above(y);
            if (isHazardous(mc, above)) return true;
            if (!mc.level.getBlockState(above).isAir()) break;
        }
        return false;
    }
}
