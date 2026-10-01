package com.autism.seedcracker.netherite;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Hidden amethyst (geode) scanner - the AmethystBypass palette-leak trick from Anubis, generalized
 * from the ancient-debris scanner. Anti-xray strips amethyst blocks from a section's packed block
 * data but leaves the amethyst state id in the section's block PALETTE. A section whose palette
 * contains an amethyst block/cluster state id while ZERO packed blocks resolve to it is a section
 * that had a geode edited out - flag it.
 *
 * Reads the raw chunk-packet buffer (same wire format as the netherite scanner), so it sees palette
 * data the world never exposes through mc.level.getBlockState.
 */
public final class HiddenAmethystScanner {

    /** Amethyst state ids we hunt (block + cluster + all bud stages). */
    private static final int[] AMETHYST_IDS = buildIds();

    private static int[] buildIds() {
        return new int[] {
            Block.getId(Blocks.AMETHYST_BLOCK.defaultBlockState()),
            Block.getId(Blocks.BUDDING_AMETHYST.defaultBlockState()),
            Block.getId(Blocks.AMETHYST_CLUSTER.defaultBlockState()),
            Block.getId(Blocks.LARGE_AMETHYST_BUD.defaultBlockState()),
            Block.getId(Blocks.MEDIUM_AMETHYST_BUD.defaultBlockState()),
            Block.getId(Blocks.SMALL_AMETHYST_BUD.defaultBlockState())
        };
    }

    private HiddenAmethystScanner() {}

    private record PaletteRead(boolean paletteHasTarget, boolean targetUnused) {
        boolean hiddenTarget() {
            return paletteHasTarget && targetUnused;
        }
    }

    /** Scan a chunk's section data for hidden amethyst. Returns section indices (from chunk bottom). */
    public static List<Integer> findHiddenSections(FriendlyByteBuf data, int sectionCount, int minY) {
        Objects.requireNonNull(data, "data");
        if (data.readableBytes() <= 0) return List.of();
        try {
            return scanSections(data, sectionCount, minY);
        } catch (Throwable t) {
            return List.of();
        }
    }

    private static List<Integer> scanSections(FriendlyByteBuf buf, int sectionCount, int minY) {
        List<Integer> hidden = new ArrayList<>();
        for (int section = 0; section < sectionCount; section++) {
            int nonAir = buf.readUnsignedShort();   // nonEmptyBlockCount
            buf.readUnsignedShort();                // fluidCount (unused)
            PaletteRead blocks = readPalette(buf, 4096, 8, 4);
            readPalette(buf, 64, 3, 1);             // biome palette (just advances the buffer)
            if (nonAir > 0 && blocks.hiddenTarget()) {
                hidden.add(minY + section);
            }
        }
        return List.copyOf(hidden);
    }

    /**
     * Read one section palette. Layout matches the netherite scanner. Target = any amethyst id.
     * paletteHasTarget = local palette contains an amethyst id; targetUnused = no packed block
     * resolves to any amethyst id (so it was declared but stripped).
     */
    private static PaletteRead readPalette(FriendlyByteBuf buf, int entriesPerSection, int maxLocalBits,
                                           int globalFloorBits) {
        int bitsPerEntry = buf.readUnsignedByte();
        int[] palette;
        int bits;
        if (bitsPerEntry == 0) {
            bits = 0;
            palette = new int[1];
            palette[0] = buf.readVarInt();
        } else if (bitsPerEntry <= maxLocalBits) {
            bits = Math.max(globalFloorBits, bitsPerEntry);
            int size = buf.readVarInt();
            if (size <= 0 || size > (1 << bits)) throw new IllegalArgumentException("invalid local palette size");
            palette = new int[size];
            for (int i = 0; i < size; i++) palette[i] = buf.readVarInt();
        } else {
            bits = bitsPerEntry;
            if (bits > 30) throw new IllegalArgumentException("direct palette is too wide");
            palette = null; // direct/global palette
        }

        // A single-value palette (bits == 0) writes ZERO storage longs (the one value fills the
        // section - no packed array). Skipping bytes here desyncs the buffer (see the netherite
        // scanner for the full explanation). Only non-zero palettes have a packed array to skip.
        int storageWidth = bits == 0 ? 0 : 64 / bits;
        long storageBytes = bits == 0 ? 0L
            : (long) (((entriesPerSection + storageWidth - 1) / storageWidth) * 8L);
        if (storageBytes > buf.readableBytes()) throw new IndexOutOfBoundsException("truncated palette data");

        // Does the LOCAL palette contain any amethyst id? (direct palettes are skipped, matching
        // the netherite scanner - the leak only works via local palettes.)
        boolean paletteHasTarget = palette != null && containsAny(palette);
        if (!paletteHasTarget) {
            buf.skipBytes((int) storageBytes);
            return new PaletteRead(false, true);
        }

        // The local palette contains amethyst. Check whether any packed block index uses it.
        int used = 0;
        if (bits == 0) {
            if (isAmethyst(palette[0])) used = entriesPerSection;
        } else {
            int startIndex = buf.readerIndex();
            long mask = (1L << bits) - 1L;
            for (int i = 0; i < entriesPerSection; i++) {
                int storageIndex = i / storageWidth;
                int bitIndex = i - storageIndex * storageWidth;
                long packed = buf.getLong(startIndex + storageIndex * 8);
                int paletteIndex = (int) ((packed >>> (bitIndex * bits)) & mask);
                int value = palette == null ? paletteIndex : palette[paletteIndex];
                if (isAmethyst(value)) used++;
            }
        }
        buf.skipBytes((int) storageBytes);
        return new PaletteRead(true, used == 0);
    }

    private static boolean isAmethyst(int stateId) {
        for (int id : AMETHYST_IDS) if (stateId == id) return true;
        return false;
    }

    private static boolean containsAny(int[] palette) {
        for (int v : palette) if (isAmethyst(v)) return true;
        return false;
    }
}
