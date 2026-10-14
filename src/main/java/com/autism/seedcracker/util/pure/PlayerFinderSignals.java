package com.autism.seedcracker.util.pure;

import java.util.BitSet;
import java.util.List;

public final class PlayerFinderSignals {
    private PlayerFinderSignals() {}

    public static boolean changed(Object previous, Object incoming) {
        return previous != null && incoming != null && !previous.equals(incoming);
    }

    public static int classify(byte[] payload) {
        if (payload == null || payload.length != 2048) return 0;
        for (byte value : payload) if (value != 0) return 2;
        return 1;
    }

    public static void apply(byte[] sections, BitSet present, BitSet empty, List<byte[]> updates) {
        int next = 0;
        for (int index = 0; index < sections.length; index++) {
            if (present.get(index)) sections[index] = (byte) classify(next < updates.size() ? updates.get(next++) : null);
            else if (empty.get(index)) sections[index] = 3;
        }
    }

    public static boolean darkSignal(byte[] sections, int bottomSection) {
        for (int index = 1; index < sections.length - 1; index++) {
            if ((bottomSection + index - 1) * 16 + 15 < 62 && sections[index] == 1) return true;
        }
        return false;
    }

    public static boolean outsideOwnSimulation(int distance, int serverSimulationDistance) {
        return serverSimulationDistance > 0 && distance > serverSimulationDistance + 1;
    }

    public static boolean active(long timestamp, long now, long lifetime) {
        return timestamp <= now && now - timestamp < lifetime;
    }
}