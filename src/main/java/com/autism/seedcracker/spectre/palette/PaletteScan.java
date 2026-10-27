// SPDX-License-Identifier: GPL-3.0-only
package com.autism.seedcracker.spectre.palette;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntPredicate;

/** Spectre HiddenDebrisScanner, adapted to the 26.2 two-count section header. */
public final class PaletteScan {
    public static final int MAX_SECTIONS = 256;
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final Result INVALID = new Result(false, List.of());

    public record Section(int y, boolean unused) {}
    public record Result(boolean valid, List<Section> sections) {
        public Result { sections = List.copyOf(sections); }
        public List<Integer> hiddenSections() {
            return sections.stream().filter(Section::unused).map(Section::y).toList();
        }
    }
    private record Palette(boolean contains, boolean used) {}

    private PaletteScan() {}

    /** Leaves the supplied buffer's position/limit untouched. A direct palette is never a leak. */
    public static Result read(ByteBuffer source, int sectionCount, int minSectionY, IntPredicate target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (sectionCount < 0 || sectionCount > MAX_SECTIONS || minSectionY < -128
            || (long) minSectionY + sectionCount > 128 || source.remaining() > MAX_BYTES) return INVALID;
        ByteBuffer data = source.slice().order(ByteOrder.BIG_ENDIAN);
        try {
            List<Section> found = new ArrayList<>();
            for (int i = 0; i < sectionCount; i++) {
                int blocks = Short.toUnsignedInt(data.getShort());
                int fluids = Short.toUnsignedInt(data.getShort());
                if (blocks > 4096 || fluids > 4096) return INVALID;
                Palette palette = palette(data, 4096, 8, 4, target);
                palette(data, 64, 3, 1, ignored -> false);
                if (palette.contains()) found.add(new Section(minSectionY + i, blocks > 0 && !palette.used()));
            }
            return data.hasRemaining() ? INVALID : new Result(true, found);
        } catch (IllegalArgumentException | java.nio.BufferUnderflowException | IndexOutOfBoundsException malformed) {
            return INVALID;
        }
    }

    private static Palette palette(ByteBuffer data, int cells, int maxLocalBits, int minBits, IntPredicate target) {
        int encodedBits = Byte.toUnsignedInt(data.get());
        int bits = encodedBits == 0 ? 0 : encodedBits <= maxLocalBits ? Math.max(minBits, encodedBits) : encodedBits;
        if (bits > 30) throw new IllegalArgumentException("Palette width");
        int[] ids = null;
        if (bits == 0) {
            ids = new int[]{varInt(data)};
        } else if (encodedBits <= maxLocalBits) {
            int size = varInt(data);
            if (size <= 0 || size > (1 << bits)) throw new IllegalArgumentException("Palette size");
            ids = new int[size];
            for (int i = 0; i < size; i++) ids[i] = varInt(data);
        }
        if (bits == 0) {
            boolean matches = target.test(ids[0]);
            return new Palette(matches, matches);
        }
        int perLong = 64 / bits;
        int longs = (cells + perLong - 1) / perLong;
        int bytes = longs * Long.BYTES;
        if (bytes > data.remaining()) throw new IllegalArgumentException("Truncated palette");
        if (ids == null) {
            data.position(data.position() + bytes);
            return new Palette(false, false);
        }
        boolean[] targets = new boolean[ids.length];
        boolean contains = false;
        for (int i = 0; i < ids.length; i++) contains |= targets[i] = target.test(ids[i]);
        long mask = (1L << bits) - 1;
        boolean used = false;
        int remaining = cells;
        for (int i = 0; i < longs; i++) {
            long word = data.getLong();
            int count = Math.min(perLong, remaining);
            for (int j = 0; j < count; j++) {
                int index = (int) ((word >>> (j * bits)) & mask);
                if (index >= ids.length) throw new IllegalArgumentException("Palette index");
                used |= targets[index];
            }
            remaining -= count;
        }
        return new Palette(contains, used);
    }

    private static int varInt(ByteBuffer data) {
        int result = 0;
        for (int i = 0; i < 5; i++) {
            int value = Byte.toUnsignedInt(data.get());
            if (i == 4 && (value & 0xf8) != 0) throw new IllegalArgumentException("Invalid state id");
            result |= (value & 0x7f) << (i * 7);
            if ((value & 0x80) == 0) return result;
        }
        throw new IllegalArgumentException("Overlong VarInt");
    }

    public static boolean validChunk(int x, int z) {
        long minX = (long) x * 16, minZ = (long) z * 16;
        return minX >= -30_000_000 && minX + 15 <= 30_000_000 && minZ >= -30_000_000 && minZ + 15 <= 30_000_000;
    }

    public static int scatter(int x, int y, int z) {
        long hash = x * -7046029254386353131L ^ y * -4417276706812531889L ^ z * 1609587929392839161L;
        hash ^= hash >>> 29;
        hash *= -4658895280553007687L;
        hash ^= hash >>> 32;
        return Math.floorMod(hash, 100);
    }

    public static boolean budLight(int emission, int light, int[] neighbours, boolean touchesGeode) {
        if (emission > 0 || !touchesGeode || (light != 1 && light != 2 && light != 4 && light != 5)
            || neighbours.length != 6) return false;
        for (int neighbour : neighbours) if (neighbour >= light || neighbour < 0) return false;
        return true;
    }

    public static boolean glowEdge(int light, int[] neighbours, boolean[] open) {
        if (light != 0 || neighbours.length != 6 || open.length != 6) return false;
        boolean litOpening = false;
        for (int i = 0; i < 6; i++) {
            if (neighbours[i] < 0 || neighbours[i] > 4) return false;
            litOpening |= neighbours[i] == 4 && open[i];
        }
        return litOpening;
    }
}
