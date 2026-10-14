package com.autism.seedcracker.finder;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GeodeGlowSnapshotTest {
    @Test void cachedCoordinatesCrossNegativeChunkAndSectionBoundaries() {
        byte[][] layers = new byte[72][];
        byte[] layer = new byte[2048];
        layer[2047] = (byte) 0x40;
        layers[GeodeGlowSnapshot.slot(-1, 0, 4)] = layer;
        assertEquals(4, GeodeGlowSnapshot.lightAt(layers, 0, -1, -1, -1, -1));
        assertEquals(0, GeodeGlowSnapshot.lightAt(layers, 0, -1, 0, -1, -1));
        assertEquals(0, GeodeGlowSnapshot.lightAt(layers, 0, -1, -1, -81, -1));
        assertEquals(0, GeodeGlowSnapshot.lightAt(layers, 0, -1, -33, -1, -1));
    }
}