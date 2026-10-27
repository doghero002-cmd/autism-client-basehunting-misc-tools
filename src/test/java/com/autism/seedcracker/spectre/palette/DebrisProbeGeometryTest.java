package com.autism.seedcracker.spectre.palette;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DebrisProbeGeometryTest {
    @Test void probeReachUsesSectionAndBlockBoundsRatherThanSectionCenters() {
        var section = DebrisProbe.Section.at(new BlockPos(-1, -1, -17));
        assertEquals(new DebrisProbe.Section(-1, -2, -1), section);
        assertEquals(0, DebrisProbe.distanceSq(section.box(), new Vec3(-8, -8, -24)));
        assertEquals(9, DebrisProbe.distanceSq(section.box(), new Vec3(3, -8, -24)));
        assertEquals(48, DebrisProbe.distanceSq(section.box(), new Vec3(-20, -20, -36)));
    }

    @Test void farSectionsDoNotAliasAcrossPositiveAndNegativeCoordinates() {
        var east = new DebrisProbe.Section(1 << 20, 0, 0);
        var west = new DebrisProbe.Section(-(1 << 20), 0, 0);
        assertNotEquals(east, west);
        assertTrue(east.box().minX > 0);
        assertTrue(west.box().maxX < 0);
    }
}
