package com.autism.seedcracker.spectre.map;

import com.autism.seedcracker.hud.RegionMapHud;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpectreRegionMapTest {
    @Test
    void usesSpectreRegionIdsAndClusterOrder() {
        assertEquals(82, RegionMapHud.regionIdOf(1));
        assertEquals(RegionMapHud.Cluster.OCEANIA, RegionMapHud.layoutClusterOf(1));
        assertEquals(RegionMapHud.Cluster.NA_WEST, RegionMapHud.layoutClusterOf(2));
        int origin = RegionMapHud.cellAt(0, 0, 50_000);
        assertEquals(41, origin);
        assertEquals(5, RegionMapHud.regionIdOf(origin));
        assertEquals(RegionMapHud.Cluster.NA_EAST, RegionMapHud.clusterOf(origin, 9));
        assertEquals(99, RegionMapHud.regionIdOf(81));
        assertEquals(RegionMapHud.Cluster.EU_CENTRAL, RegionMapHud.layoutClusterOf(81));
        assertEquals(0xFF4FADEA, RegionMapHud.Cluster.NA_EAST.argb);
    }

    @Test
    void rejectsOutsideMapInsteadOfClampingToAFalseRegion() {
        assertEquals(1, RegionMapHud.cellAt(-225_000, -225_000, 50_000));
        assertEquals(-1, RegionMapHud.cellAt(-225_000.1, 0, 50_000));
        assertEquals(-1, RegionMapHud.cellAt(225_000, 0, 50_000));
        assertEquals(-1, RegionMapHud.cellAt(0, Double.NaN, 50_000));
        assertEquals(-1, RegionMapHud.cellAt(Double.POSITIVE_INFINITY, 0, 50_000));
        assertEquals(-1, RegionMapHud.cellAt(0, 0, 0));
        assertEquals(1, RegionMapHud.cellIndexOf(-300_000, -300_000, 9, 50_000));
        assertEquals(81, RegionMapHud.cellIndexOf(300_000, 300_000, 9, 50_000));
    }

    @Test
    void projectsPinsAndHoverThroughCellGapsConsistently() {
        for (int size : new int[] {80, 180, 600}) {
            for (int gap : new int[] {0, 2, 10}) {
                for (double fraction : new double[] {0, .01, .125, .5, .875, .999}) {
                    double pixel = RegionMapHud.mapPixel(fraction, size, gap);
                    assertTrue(pixel >= 0 && pixel < size);
                    assertEquals(fraction, RegionMapHud.mapFraction(pixel, size, gap), 1e-12);
                }
            }
        }
        double gapPixel = RegionMapHud.cellWidth(180, 2) + 1;
        assertTrue(Double.isNaN(RegionMapHud.mapFraction(gapPixel, 180, 2)));
        assertTrue(Double.isNaN(RegionMapHud.mapPixel(1, 180, 2)));
        assertTrue(Double.isNaN(RegionMapHud.mapPixel(Double.NaN, 180, 2)));
        assertArrayEquals(new double[] {.5, .5}, RegionMapHud.cellPositionOf(0, 0, 50_000), 1e-12);
        assertTrue(RegionMapHud.cellPositionOf(-225_001, 0, 50_000)[0] > .99);
    }
}
