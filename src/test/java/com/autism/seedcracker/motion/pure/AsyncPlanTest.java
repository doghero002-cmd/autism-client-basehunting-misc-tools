package com.autism.seedcracker.motion.pure;

import org.junit.jupiter.api.Test;

import com.autism.seedcracker.motion.pure.GridPathfinder.Kind;
import com.autism.seedcracker.motion.pure.GridPathfinder.Status;

import static org.junit.jupiter.api.Assertions.*;

class AsyncPlanTest {

    private static GridPathfinder.World flat(int radius) {
        return new GridPathfinder.World() {
            public Kind kind(int x, int y, int z) {
                if (Math.abs(x) > radius || Math.abs(z) > radius) return Kind.UNLOADED;
                return y <= 63 ? Kind.SOLID : Kind.OPEN;
            }

            public double breakCost(int x, int y, int z) {
                return Double.POSITIVE_INFINITY;
            }
        };
    }

    private static void await(AsyncPlan p) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (!p.done() && System.currentTimeMillis() < until) Thread.sleep(2);
        assertTrue(p.done(), "planner thread never finished");
    }

    @Test
    void plansOnTheWorkerThread() throws InterruptedException {
        var s = GridPathfinder.search(flat(100), GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(60, 64, 10));
        AsyncPlan p = AsyncPlan.start(s, 5_000);
        await(p);
        assertEquals(Status.FOUND, p.status());
        assertNull(p.error());
        var last = p.path().get(p.path().size() - 1);
        assertEquals(60, last.x());
        assertEquals(10, last.z());
    }

    @Test
    void deadlineEndsAsAPartialRouteTowardTheGoal() {
        // An unreachable goal in a big world would expand to maxNodes; a 0 ms limit stops after the first batch.
        var s = GridPathfinder.search(flat(400), GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(0, 200, 300));
        AsyncPlan p = AsyncPlan.runNow(s, 0);
        assertTrue(p.done());
        assertEquals(Status.PARTIAL, p.status());
        assertTrue(p.expanded() < GridPathfinder.Config.defaults().maxNodes());
        var last = p.path().get(p.path().size() - 1);
        assertTrue(last.z() > 5, "partial route should head toward the goal, ended at z=" + last.z());
    }

    @Test
    void softDeadlineWaitsUntilAPartialIsWorthWalking() {
        // Soft limit 0: stops at the first batch that already has a partial route 5+ blocks out, not before.
        var s = GridPathfinder.search(flat(400), GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(0, 200, 300));
        AsyncPlan p = AsyncPlan.runNow(s, 0, 60_000);
        assertEquals(Status.PARTIAL, p.status());
        assertTrue(p.path().size() >= 2);
    }

    @Test
    void cancelledPlanNeverPublishesAPath() throws InterruptedException {
        var s = GridPathfinder.search(flat(400), GridPathfinder.Config.defaults(), 0, 64, 0, GridPathfinder.block(0, 200, 300));
        AsyncPlan p = AsyncPlan.start(s, 5_000);
        p.cancel();
        await(p);
        assertTrue(p.cancelled());
        assertTrue(p.path().isEmpty());
    }

    @Test
    void worldExceptionFailsThePlanInsteadOfKillingTheThread() throws InterruptedException {
        GridPathfinder.World broken = new GridPathfinder.World() {
            public Kind kind(int x, int y, int z) {
                if (x > 3) throw new IllegalStateException("boom");
                return y <= 63 ? Kind.SOLID : Kind.OPEN;
            }

            public double breakCost(int x, int y, int z) {
                return Double.POSITIVE_INFINITY;
            }
        };
        AsyncPlan bad = AsyncPlan.start(GridPathfinder.search(broken, GridPathfinder.Config.defaults(), 0, 64, 0,
            GridPathfinder.block(10, 64, 0)), 5_000);
        await(bad);
        assertEquals(Status.FAILED, bad.status());
        assertNotNull(bad.error());
        // The worker must survive for the next plan.
        AsyncPlan next = AsyncPlan.start(GridPathfinder.search(flat(50), GridPathfinder.Config.defaults(), 0, 64, 0,
            GridPathfinder.block(5, 64, 0)), 5_000);
        await(next);
        assertEquals(Status.FOUND, next.status());
    }
}
