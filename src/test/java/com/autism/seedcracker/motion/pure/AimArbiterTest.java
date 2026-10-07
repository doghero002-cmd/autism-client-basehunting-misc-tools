package com.autism.seedcracker.motion.pure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AimArbiterTest {

    @Test
    void higherPriorityWinsWithinATick() {
        AimArbiter a = new AimArbiter(3);
        assertTrue(a.request("walk", 10, 1));
        assertTrue(a.request("combat", 30, 1));
        assertFalse(a.request("walk", 10, 1));
        assertEquals("combat", a.owner());
    }

    @Test
    void equalPriorityCannotStealAnActiveOwner() {
        AimArbiter a = new AimArbiter(3);
        assertTrue(a.request("mine", 20, 1));
        for (long t = 2; t < 10; t++) {
            assertFalse(a.request("build", 20, t), "no ping-pong at tick " + t);
            assertTrue(a.request("mine", 20, t));
        }
    }

    @Test
    void quietOwnerHandsOffAfterTheWindow() {
        AimArbiter a = new AimArbiter(3);
        assertTrue(a.request("mine", 20, 1));
        assertFalse(a.request("build", 20, 3), "mine asked 2 ticks ago - still owns it");
        assertTrue(a.request("build", 20, 5), "4 ticks quiet > 3 tick window");
        assertNull(new AimArbiter(3).activeOwner(0));
        assertEquals("build", a.activeOwner(6));
        assertNull(a.activeOwner(9));
    }

    @Test
    void lowerPriorityWaitsButTakesOverWhenOwnerStops() {
        AimArbiter a = new AimArbiter(2);
        assertTrue(a.request("combat", 30, 1));
        assertFalse(a.request("afk", 0, 2));
        assertTrue(a.request("afk", 0, 4));
    }

    @Test
    void canTakeMatchesRequestWithoutSideEffects() {
        AimArbiter a = new AimArbiter(3);
        assertTrue(a.request("mine", 20, 1));
        assertFalse(a.canTake("build", 20, 2));
        assertTrue(a.canTake("combat", 30, 2));
        assertTrue(a.canTake("mine", 20, 2));
        assertEquals("mine", a.owner(), "canTake never transfers ownership");
    }

    @Test
    void releaseFreesImmediately() {
        AimArbiter a = new AimArbiter(10);
        assertTrue(a.request("mine", 20, 1));
        a.release("mine");
        assertTrue(a.request("afk", 0, 2));
        a.release("not-owner");
        assertEquals("afk", a.owner());
    }
}
