package com.autism.seedcracker.motion.pure;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.autism.seedcracker.motion.pure.GridPathfinder.Move;
import com.autism.seedcracker.motion.pure.GridPathfinder.Step;

import static org.junit.jupiter.api.Assertions.*;

class PathGeometryTest {

    private static List<Step> path(int[][] xz) {
        List<Step> out = new ArrayList<>();
        for (int i = 0; i < xz.length; i++) out.add(new Step(xz[i][0], 64, xz[i][1], i == 0 ? Move.START : Move.WALK, new long[0]));
        return out;
    }

    @Test
    void projectsOntoTheNearestSegment() {
        List<Step> p = path(new int[][]{{0, 0}, {1, 0}, {2, 0}, {3, 0}});
        var pr = PathGeometry.project(p, 1, 3, 1.7, 64, 0.9, 1.5);
        assertEquals(2, pr.seg(), "between node 1 and 2");
        assertEquals(1.7, pr.x(), 1e-9);
        assertEquals(0.5, pr.z(), 1e-9);
        assertEquals(0.16, pr.distSq(), 1e-9);
    }

    @Test
    void passingANodeOffCentreStillAdvances() {
        // The old follower needed to be within 0.35 of node 1's centre; 0.6 off to the side never counted.
        List<Step> p = path(new int[][]{{0, 0}, {1, 0}, {2, 0}, {3, 0}});
        var pr = PathGeometry.project(p, 1, 3, 1.9, 64, 1.1, 1.5);
        assertTrue(pr.seg() >= 2, "node 1 is behind us: projection must be on a later segment");
    }

    @Test
    void ignoresSegmentsOnAnotherLevel() {
        List<Step> p = new ArrayList<>(path(new int[][]{{0, 0}, {1, 0}}));
        p.add(new Step(1, 70, 0, Move.CLIMB_UP, new long[0]));
        p.add(new Step(0, 70, 0, Move.WALK, new long[0]));
        var pr = PathGeometry.project(p, 1, 3, 0.5, 70, 0.5, 1.5);
        assertEquals(3, pr.seg(), "standing at y=70 projects onto the y=70 stretch");
    }

    @Test
    void carrotSlidesAlongAndRoundsCorners() {
        // L-shape: east 3 then north 3. A carrot 2 ahead from the corner's approach turns the corner.
        List<Step> p = path(new int[][]{{0, 0}, {1, 0}, {2, 0}, {3, 0}, {3, -1}, {3, -2}, {3, -3}});
        var pr = PathGeometry.project(p, 1, 6, 2.5, 64, 0.5, 1.5);
        double[] c = PathGeometry.carrot(p, pr, 2.0, Integer.MAX_VALUE);
        assertEquals(3.5, c[0], 1e-9, "past the corner on the north leg");
        assertEquals(-0.5, c[1], 1e-9);
    }

    @Test
    void carrotStopsAtAMoveChange() {
        List<Step> p = path(new int[][]{{0, 0}, {1, 0}, {2, 0}, {3, 0}, {4, 0}});
        var pr = PathGeometry.project(p, 1, 4, 0.5, 64, 0.5, 1.5);
        double[] c = PathGeometry.carrot(p, pr, 3.0, 2);
        assertEquals(2.5, c[0], 1e-9, "carrot pinned at the jump node, not past it");
        assertEquals(2, (int) c[2]);
    }

    @Test
    void carrotAtPathEndReturnsLastNode() {
        List<Step> p = path(new int[][]{{0, 0}, {1, 0}});
        var pr = PathGeometry.project(p, 1, 1, 0.5, 64, 0.5, 1.5);
        double[] c = PathGeometry.carrot(p, pr, 10.0, Integer.MAX_VALUE);
        assertEquals(1.5, c[0], 1e-9);
        assertEquals(0.5, c[1], 1e-9);
    }
}
