package com.autism.seedcracker.util.pure;

import java.util.function.IntConsumer;

public final class PrimeLight {
    private PrimeLight() {}

    public static int value(byte[] data, int index) {
        if (data == null || data.length != 2048 || index < 0 || index >= 4096) throw new IllegalArgumentException("Invalid light cell");
        return ((data[index >> 1] & 255) >> ((index & 1) * 4)) & 15;
    }

    public static int changes(byte[] previous, byte[] incoming, IntConsumer changed) {
        if (previous == null || incoming == null || previous.length != 2048 || incoming.length != 2048) return 0;
        int count = 0;
        for (int offset = 0; offset < 2048; offset++) {
            int difference = (previous[offset] ^ incoming[offset]) & 255;
            if ((difference & 15) != 0) { changed.accept(offset * 2); count++; }
            if ((difference & 240) != 0) { changed.accept(offset * 2 + 1); count++; }
        }
        return count;
    }

    public static boolean amethystExplains(int value, int distance) {
        return value >= 0 && value <= 5 && distance >= 0 && distance <= 5;
    }

    public static boolean sourceExplains(int emission, int value, int distance) {
        return emission > 0 && emission <= 14 && value >= 0 && value <= emission
            && distance >= 0 && distance <= emission - value;
    }
}