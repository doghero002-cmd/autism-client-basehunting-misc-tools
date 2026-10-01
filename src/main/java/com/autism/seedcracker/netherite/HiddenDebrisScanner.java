package com.autism.seedcracker.netherite;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Hidden ancient-debris section scanner (faithful port of Anubis Client's HiddenDebrisScanner).
 *
 * Reads a chunk packet's RAW block/biome section data and detects ancient debris the server tried
 * to hide from the client's block view (anti-xray). The trick: the section's block PALETTE still
 * contains the ancient-debris state id even when the server stripped the actual blocks out of the
 * packed block data. A section where the palette contains the target state id but ZERO packed
 * blocks resolve to it ("paletteHasTarget && targetUnused") is a section that had debris edited
 * out - flag it.
 *
 * The scanner reads directly from the chunk packet buffer, so it sees palette data the world
 * never exposes to mc.level.getBlockState (which is already server-filtered).
 */
public final class HiddenDebrisScanner {

    /** Global state id of ancient debris (Block.BLOCK_STATE_REGISTRY id). */
    private static final int ANCIENT_STATE_ID = Block.getId(Blocks.ANCIENT_DEBRIS.defaultBlockState());

    private HiddenDebrisScanner() {}

    /** The ancient-debris state id this scanner looks for. */
    public static int ancientStateId() {
        return ANCIENT_STATE_ID;
    }

    /** Result of reading one palette: did the palette contain the target id, and was the target
     * absent from the packed block data. */
    private record PaletteRead(boolean paletteHasTarget, boolean targetUnused) {
        boolean hiddenTarget() {
            return paletteHasTarget && targetUnused;
        }
    }

    /**
     * Scan a chunk's section data for hidden ancient debris.
     *
     * @param data         the chunk packet's raw read buffer (positioned at the section data start)
     * @param sectionCount number of chunk sections (world height / 16)
     * @param minY         the world's minimum section Y (e.g. 0 overworld, 0 or nether band)
     * @param targetStateId the block-state id to hunt (ANCIENT_STATE_ID)
     * @return list of section indices (0-based from the chunk's bottom) that hide the target
     */
    private static int dbgScan = 0;

    public static List<Integer> findHiddenSections(FriendlyByteBuf data, int sectionCount, int minY, int targetStateId) {
        Objects.requireNonNull(data, "data");
        if (targetStateId < 0) throw new IllegalArgumentException("targetStateId must be non-negative");
        if (data.readableBytes() <= 0) return List.of();
        try {
            List<Integer> out = scanSections(data, sectionCount, minY, targetStateId);
            if (dbgScan < 4) {
                dbgScan++;
                com.autism.seedcracker.compat.ClientNotify.warning(
                    "[NetheriteFinder-scan] chunk scanned sec=" + sectionCount + " target=" + targetStateId
                    + " found=" + out + " dbgBits=" + dbgLastBits);
            }
            return out;
        } catch (Throwable t) {
            if (dbgScan < 8) {
                dbgScan++;
                com.autism.seedcracker.compat.ClientNotify.error(
                    "[NetheriteFinder-scan] threw " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            return List.of();
        }
    }

    private static String dbgLastBits = "";

    private static List<Integer> scanSections(FriendlyByteBuf buf, int sectionCount, int minY, int targetStateId) {
        Objects.requireNonNull(buf, "buf");
        if (sectionCount < 0 || targetStateId < 0) throw new IllegalArgumentException("invalid section scan arguments");
        List<Integer> hidden = new ArrayList<>();
        for (int section = 0; section < sectionCount; section++) {
            // Vanilla LevelChunkSection.write: nonEmptyBlockCount(short) + fluidCount(short), THEN
            // the block palette. Reading only one short here left the buffer 2 bytes off, so the
            // palette read was garbage and nothing ever detected.
            int nonAir = buf.readUnsignedShort();   // nonEmptyBlockCount
            buf.readUnsignedShort();                // fluidCount (unused)
            // Block palette (4096 entries/section, up to 8 bits local, 4 bits global floor).
            PaletteRead blocks = readPalette(buf, 4096, 8, 4, targetStateId);
            // Biome palette (64 entries/section, up to 3 bits local, 1 bit global floor); target n/a.
            readPalette(buf, 64, 3, 1, -1);
            if (nonAir > 0 && blocks.hiddenTarget()) {
                hidden.add(minY + section);
            }
        }
        return List.copyOf(hidden);
    }

    /**
     * Read one section palette. Layout (1.18+ chunk format):
     *   ubyte bitsPerEntry
     *   if bitsPerEntry == 0: single-value palette -> [varint value]
     *   else if bitsPerEntry <= maxLocalBits: local palette -> varint size, then size varints
     *   else: direct (global) palette, no local list
     * then the packed block data (storageWidth * 8 bytes).
     *
     * When the target id is present in a LOCAL palette, we also verify whether any packed block
     * index actually resolves to it (so "declared but unused" is detectable). A direct palette
     * can't be enumerated cheaply, so presence there counts as used (not hidden).
     */
    private static PaletteRead readPalette(FriendlyByteBuf buf, int entriesPerSection, int maxLocalBits,
                                           int globalFloorBits, int targetStateId) {
        int bitsPerEntry = buf.readUnsignedByte();
        if (entriesPerSection == 4096 && dbgScan <= 4) {
            dbgLastBits = (dbgLastBits.length() > 40 ? "" : dbgLastBits) + bitsPerEntry + ",";
        }
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

        // A single-value palette (bits == 0) writes ZERO storage longs: the one palette value fills
        // the whole section, so there is no packed block array. Skipping any bytes here desyncs the
        // buffer by ~32KB into the light arrays, which then read as garbage bit-widths (the "direct
        // palette is too wide" / "truncated palette data" cascade). Only non-zero palettes have a
        // packed array to skip.
        int storageWidth = bits == 0 ? 0 : 64 / bits;
        long storageBytes = bits == 0 ? 0L
            : (long) (((entriesPerSection + storageWidth - 1) / storageWidth) * 8L);
        if (storageBytes > buf.readableBytes()) throw new IndexOutOfBoundsException("truncated palette data");

        // Does the LOCAL palette contain the target state id? (matches Anubis: a DIRECT/global
        // palette - palette == null - is skipped, because there the packed values ARE the ids and
        // anti-xray can't hide a block by omitting it from a local palette. The detection only
        // works via local palettes, where the server omits the real block from the palette list.)
        boolean paletteHasTarget = palette != null && contains(palette, targetStateId);
        if (!paletteHasTarget) {
            buf.skipBytes((int) storageBytes);
            return new PaletteRead(false, true);
        }

        // The local palette contains the target. Check whether any packed block index uses it.
        int used = 0;
        if (bits == 0) {
            if (palette[0] == targetStateId) used = entriesPerSection;
        } else {
            int startIndex = buf.readerIndex();
            long mask = (1L << bits) - 1L;
            for (int i = 0; i < entriesPerSection; i++) {
                int storageIndex = i / storageWidth;
                int bitIndex = i - storageIndex * storageWidth;
                long packed = buf.getLong(startIndex + storageIndex * 8);
                int paletteIndex = (int) ((packed >>> (bitIndex * bits)) & mask);
                int value = palette == null ? paletteIndex : paletteValue(palette, paletteIndex);
                if (value == targetStateId) used++;
            }
        }
        buf.skipBytes((int) storageBytes);
        return new PaletteRead(true, used == 0);
    }

    private static int paletteValue(int[] palette, int index) {
        if (index < 0 || index >= palette.length) throw new IllegalArgumentException("packed palette index is invalid");
        return palette[index];
    }

    private static boolean contains(int[] palette, int value) {
        for (int v : palette) if (v == value) return true;
        return false;
    }
}
