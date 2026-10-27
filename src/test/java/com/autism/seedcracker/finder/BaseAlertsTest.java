package com.autism.seedcracker.finder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BaseAlertsTest {
    @BeforeEach void before() { BaseAlerts.reset(); BaseTracker.clear(); }
    @AfterEach void after() { BaseAlerts.reset(); BaseTracker.clear(); }

    @Test void weakNearbySignalsCannotHideTheNinthMultiFinderCandidate() {
        for (int x = -64; x <= 64; x += 64) {
            for (int z = -64; z <= 64; z += 64) {
                if (x != 0 || z != 0) BaseTracker.storeReport(x, z, 40, "Chunk", 1000);
            }
        }
        BaseTracker.storeReport(128, 0, 85, "Stash", 1000);
        BaseTracker.storeReport(128, 0, 55, "Prime", 1000);
        var candidate = BaseAlerts.claimNext(0, 0, 1001);
        assertNotNull(candidate);
        assertEquals(128, candidate.blockX());
        assertTrue(candidate.sources().contains("Stash"));
        assertTrue(candidate.sources().contains("Prime"));
    }

    @Test void candidateSelectionKeepsTheCooldownAndPerLocationDedupe() {
        for (int x : new int[]{128, 256}) {
            BaseTracker.storeReport(x, 0, 85, "Stash", 1000);
            BaseTracker.storeReport(x, 0, 55, "Prime", 1000);
        }
        assertEquals(128, BaseAlerts.claimNext(0, 0, 1001).blockX());
        assertNull(BaseAlerts.claimNext(0, 0, 2000));
        for (int x : new int[]{128, 256}) {
            BaseTracker.storeReport(x, 0, 85, "Stash", 11000);
            BaseTracker.storeReport(x, 0, 55, "Prime", 11000);
        }
        assertEquals(256, BaseAlerts.claimNext(0, 0, 11001).blockX());
        for (int x : new int[]{128, 256}) {
            BaseTracker.storeReport(x, 0, 85, "Stash", 22000);
            BaseTracker.storeReport(x, 0, 55, "Prime", 22000);
        }
        assertNull(BaseAlerts.claimNext(0, 0, 22001));
    }

    @Test void cooldownDoesNotConsumeUnannouncedCells() {
        assertTrue(BaseAlerts.claim(1, 1000));
        assertFalse(BaseAlerts.claim(2, 2000));
        assertTrue(BaseAlerts.claim(2, 11000));
        assertFalse(BaseAlerts.claim(2, 22000));
    }

    @Test void resetAllowsTheSameLocationInAnotherWorld() {
        assertTrue(BaseAlerts.claim(1, 1000));
        BaseAlerts.reset();
        assertTrue(BaseAlerts.claim(1, 1001));
    }
}