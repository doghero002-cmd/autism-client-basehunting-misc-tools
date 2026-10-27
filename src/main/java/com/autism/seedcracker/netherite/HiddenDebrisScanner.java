// SPDX-License-Identifier: GPL-3.0-only
package com.autism.seedcracker.netherite;

import java.util.List;
import java.util.Objects;

import com.autism.seedcracker.spectre.palette.PaletteScan;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Spectre HiddenDebrisScanner, with the addon's 26.2 fluid-count header and fail-closed bounds. */
public final class HiddenDebrisScanner {
    private static final int ANCIENT_STATE_ID = Block.getId(Blocks.ANCIENT_DEBRIS.defaultBlockState());
    private HiddenDebrisScanner() {}

    public static int ancientStateId() { return ANCIENT_STATE_ID; }

    /** Borrows the buffer; the packet hook receiving getReadBuffer() owns and releases its wrapper. */
    public static List<Integer> findHiddenSections(FriendlyByteBuf data, int sectionCount, int minY, int targetStateId) {
        Objects.requireNonNull(data, "data");
        if (targetStateId < 0) throw new IllegalArgumentException("targetStateId must be non-negative");
        return PaletteScan.read(data.nioBuffer(data.readerIndex(), data.readableBytes()), sectionCount, minY,
            id -> id == targetStateId).hiddenSections();
    }
}
