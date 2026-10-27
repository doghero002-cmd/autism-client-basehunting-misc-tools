// SPDX-License-Identifier: GPL-3.0-only
// Spectre PrimeChunkFinderModule source classification, d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da.
package com.autism.seedcracker.spectre.chunks;

import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.*;
import net.minecraft.world.level.block.state.BlockState;

public final class PrimeEvidence {
    private PrimeEvidence() {}
    public static boolean redstone(BlockState state) {
        Block block = state.getBlock();
        return block instanceof RedStoneWireBlock || block instanceof DiodeBlock || block instanceof RedstoneTorchBlock
            || block instanceof RedstoneLampBlock || block instanceof ObserverBlock || block instanceof PistonBaseBlock
            || block instanceof PistonHeadBlock || block instanceof MovingPistonBlock || block instanceof DispenserBlock
            || block instanceof HopperBlock || block instanceof NoteBlock || block instanceof DaylightDetectorBlock
            || block instanceof LeverBlock || block instanceof ButtonBlock || block instanceof BasePressurePlateBlock
            || block instanceof TripWireHookBlock || block instanceof TargetBlock || block instanceof CrafterBlock || block instanceof PoweredBlock;
    }
    public static boolean lamp(BlockState state) {
        return state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH) || state.is(Blocks.SOUL_TORCH) || state.is(Blocks.SOUL_WALL_TORCH)
            || state.is(Blocks.COPPER_TORCH) || state.is(Blocks.COPPER_WALL_TORCH) || CaveVines.hasGlowBerries(state)
            || (state.getBlock() instanceof CandleBlock || state.getBlock() instanceof CandleCakeBlock) && state.getLightEmission() > 0;
    }
    public static boolean ore(BlockState state) { return state.is(Blocks.REDSTONE_ORE) || state.is(Blocks.DEEPSLATE_REDSTONE_ORE); }
    public static boolean amethyst(BlockState state) {
        return state.is(Blocks.AMETHYST_BLOCK) || state.is(Blocks.BUDDING_AMETHYST) || state.is(Blocks.AMETHYST_CLUSTER)
            || state.is(Blocks.SMALL_AMETHYST_BUD) || state.is(Blocks.MEDIUM_AMETHYST_BUD) || state.is(Blocks.LARGE_AMETHYST_BUD);
    }
    public static boolean source(BlockState state) { return amethyst(state) || ore(state) || lamp(state); }
}
