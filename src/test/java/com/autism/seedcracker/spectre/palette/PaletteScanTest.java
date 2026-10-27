package com.autism.seedcracker.spectre.palette;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaletteScanTest {
    private static final int TARGET = 42;

    private static byte[] section(int encodedBits, int[] ids, int changedWord, long word) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeShort(4096);
        data.writeShort(0);
        data.writeByte(encodedBits);
        if (encodedBits == 0) varInt(data, ids[0]);
        else if (encodedBits <= 8) {
            varInt(data, ids.length);
            for (int id : ids) varInt(data, id);
        }
        int bits = encodedBits == 0 ? 0 : encodedBits <= 8 ? Math.max(4, encodedBits) : encodedBits;
        if (bits > 0 && bits <= 30) {
            int perLong = 64 / bits;
            int longs = (4096 + perLong - 1) / perLong;
            for (int i = 0; i < longs; i++) data.writeLong(i == changedWord ? word : 0);
        }
        data.writeByte(0);
        varInt(data, 0);
        return bytes.toByteArray();
    }
    private static void varInt(DataOutputStream data, int value) throws IOException {
        do {
            int next = value & 127;
            value >>>= 7;
            data.writeByte(next | (value == 0 ? 0 : 128));
        } while (value != 0);
    }
    private static PaletteScan.Result scan(byte[] bytes) {
        return PaletteScan.read(ByteBuffer.wrap(bytes), 1, -4, id -> id == TARGET);
    }

    @Test void unusedDebrisAndBudPalettePresenceHaveDifferentMeanings() throws Exception {
        var hidden = scan(section(4, new int[]{1, TARGET}, -1, 0));
        assertTrue(hidden.valid());
        assertEquals(List.of(-4), hidden.hiddenSections());
        var visible = scan(section(4, new int[]{1, TARGET}, 0, 1));
        assertTrue(visible.valid());
        assertTrue(visible.hiddenSections().isEmpty());
        assertEquals(List.of(new PaletteScan.Section(-4, false)), visible.sections());
    }

    @Test void constantAndDirectPalettesNeverClaimHiddenDebris() throws Exception {
        assertTrue(scan(section(0, new int[]{TARGET}, -1, 0)).hiddenSections().isEmpty());
        assertEquals(1, scan(section(0, new int[]{TARGET}, -1, 0)).sections().size());
        assertTrue(scan(section(9, new int[0], 0, TARGET)).sections().isEmpty());
        assertTrue(scan(section(0, new int[]{1}, -1, 0)).sections().isEmpty());
    }

    @Test void blockPaletteMinimumBitsAndNonDividingStorageWidthsWork() throws Exception {
        assertEquals(List.of(-4), scan(section(1, new int[]{1, TARGET}, -1, 0)).hiddenSections());
        assertTrue(scan(section(1, new int[]{1, TARGET}, 0, 1L << 60)).hiddenSections().isEmpty());
        assertEquals(List.of(-4), scan(section(6, new int[]{1, TARGET}, -1, 0)).hiddenSections());
        assertTrue(scan(section(6, new int[]{1, TARGET}, 409, 1L << 30)).hiddenSections().isEmpty());
        assertEquals(List.of(-4), scan(section(6, new int[]{1, TARGET}, 409, 1L << 36)).hiddenSections());
    }

    @Test void sectionAndBufferOffsetsRoundTripWithoutMutatingInput() throws Exception {
        byte[] section = section(4, new int[]{1, TARGET}, -1, 0);
        ByteBuffer source = ByteBuffer.allocate(section.length * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        source.putInt(9).put(section).put(section).flip().position(4);
        var result = PaletteScan.read(source, 2, -5, id -> id == TARGET);
        assertEquals(List.of(-5, -4), result.hiddenSections());
        assertEquals(4, source.position());
        assertEquals(ByteOrder.LITTLE_ENDIAN, source.order());
    }

    @Test void malformedPayloadIsNeverPartiallyAccepted() throws Exception {
        byte[] good = section(4, new int[]{1, TARGET}, -1, 0);
        assertFalse(scan(Arrays.copyOf(good, 8)).valid());
        assertFalse(scan(Arrays.copyOf(good, good.length + 1)).valid());
        assertFalse(scan(section(4, new int[]{1, TARGET}, 0, 15)).valid());
        assertFalse(scan(section(4, new int[]{1, 2}, 0, 15)).valid());
        assertFalse(scan(section(4, new int[0], -1, 0)).valid());
        assertFalse(scan(section(4, new int[17], -1, 0)).valid());
        assertFalse(scan(section(31, new int[0], -1, 0)).valid());
        assertFalse(scan(section(4, new int[]{1, -1}, -1, 0)).valid());
        ByteBuffer invalidSecond = ByteBuffer.allocate(good.length + 4).put(good).putInt(0).flip();
        var result = PaletteScan.read(invalidSecond, 2, -4, id -> id == TARGET);
        assertFalse(result.valid());
        assertTrue(result.sections().isEmpty());
    }

    @Test void corruptBiomeAndCountFieldsAlsoFailClosed() throws Exception {
        byte[] bytes = section(4, new int[]{1, TARGET}, -1, 0);
        byte[] biome = bytes.clone();
        biome[biome.length - 2] = 31;
        assertFalse(scan(biome).valid());
        ByteBuffer.wrap(bytes).putShort(0, (short) 4097);
        assertFalse(scan(bytes).valid());
        ByteBuffer.wrap(bytes).putShort(0, (short) 4096).putShort(2, (short) 4097);
        assertFalse(scan(bytes).valid());
    }

    @Test void emptySectionsCannotProveHiddenDebris() throws Exception {
        byte[] bytes = section(4, new int[]{1, TARGET}, -1, 0);
        ByteBuffer.wrap(bytes).putShort(0, (short) 0);
        assertTrue(scan(bytes).valid());
        assertEquals(1, scan(bytes).sections().size());
        assertTrue(scan(bytes).hiddenSections().isEmpty());
    }

    @Test void untrustedSizeAndWorldHeightAreBounded() throws Exception {
        ByteBuffer data = ByteBuffer.wrap(section(4, new int[]{1, TARGET}, -1, 0));
        assertFalse(PaletteScan.read(data, -1, 0, id -> true).valid());
        assertFalse(PaletteScan.read(data, PaletteScan.MAX_SECTIONS + 1, 0, id -> true).valid());
        assertFalse(PaletteScan.read(data, 1, -129, id -> true).valid());
        assertFalse(PaletteScan.read(data, 1, 128, id -> true).valid());
        assertFalse(PaletteScan.read(data, Integer.MAX_VALUE, Integer.MAX_VALUE, id -> true).valid());
        assertFalse(PaletteScan.read(ByteBuffer.allocate(PaletteScan.MAX_BYTES + 1), 1, 0, id -> true).valid());
        assertTrue(PaletteScan.read(ByteBuffer.allocate(0), 0, 0, id -> true).valid());
    }

    @Test void chunkCoordinatesCannotOverflowIntoAnotherWorldPosition() {
        assertTrue(PaletteScan.validChunk(0, -1));
        assertTrue(PaletteScan.validChunk(-30_000_000 >> 4, (30_000_000 - 16) >> 4));
        assertFalse(PaletteScan.validChunk(30_000_000 >> 4, 0));
        assertFalse(PaletteScan.validChunk(Integer.MAX_VALUE, 0));
        assertFalse(PaletteScan.validChunk(0, Integer.MIN_VALUE));
    }

    @Test void hiddenBudLightNeedsAStrictPeakBesideAGeode() {
        for (int light : new int[]{1, 2, 4, 5}) assertTrue(PaletteScan.budLight(0, light, new int[6], true));
        assertFalse(PaletteScan.budLight(0, 3, new int[6], true));
        assertFalse(PaletteScan.budLight(1, 4, new int[6], true));
        assertFalse(PaletteScan.budLight(0, 4, new int[6], false));
        assertFalse(PaletteScan.budLight(0, 4, new int[]{0, 0, 4, 0, 0, 0}, true));
        assertFalse(PaletteScan.budLight(0, 4, new int[]{0, 0, -1, 0, 0, 0}, true));
    }

    @Test void anbsEdgeNeedsAnOpenLevelFourNeighbourAndNoBrighterLight() {
        int[] sides = {0, 4, 0, 0, 0, 0};
        boolean[] openings = {false, true, false, false, false, false};
        assertTrue(PaletteScan.glowEdge(0, sides, openings));
        assertFalse(PaletteScan.glowEdge(1, sides, openings));
        assertFalse(PaletteScan.glowEdge(0, sides, new boolean[6]));
        sides[3] = 5;
        assertFalse(PaletteScan.glowEdge(0, sides, openings));
        sides[3] = -1;
        assertFalse(PaletteScan.glowEdge(0, sides, openings));
        assertFalse(PaletteScan.glowEdge(0, new int[7], openings));
    }

    @Test void markerScatterIsDeterministicAndBoundedAtNegativeCoordinates() {
        for (int i = -1000; i < 1000; i++) {
            int score = PaletteScan.scatter(i, -58, 30_000_000 - i);
            assertTrue(score >= 0 && score < 100);
            assertEquals(score, PaletteScan.scatter(i, -58, 30_000_000 - i));
        }
    }
}
