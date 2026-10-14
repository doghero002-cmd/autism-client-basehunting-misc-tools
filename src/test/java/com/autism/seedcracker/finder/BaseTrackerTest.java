package com.autism.seedcracker.finder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BaseTrackerTest {
    @Test void weakNearestReportsCannotHideAQualifyingNinthCandidate() {
        for (int offsetX=-64; offsetX<=64; offsetX+=64) {
            for (int offsetZ=-64; offsetZ<=64; offsetZ+=64) {
                BaseTracker.storeReport(offsetX,offsetZ,offsetX==64 && offsetZ==64 ? 80 : 30,"Stash",1000);
            }
        }
        assertTrue(BaseTracker.fusedNearestAt(0,0,8,1001).stream().noneMatch(candidate->candidate.confidence()>=70));
        var candidate=BaseTracker.nearestCandidateAt(0,0,70,128,1001);
        assertNotNull(candidate);
        assertEquals(64,candidate.blockX()); assertEquals(64,candidate.blockZ());
        assertNull(BaseTracker.nearestCandidateAt(0,0,70,64,1001));
        assertNull(BaseTracker.nearestCandidateAt(0,0,90,128,1001));
        assertNull(BaseTracker.nearestCandidateAt(0,0,70,128,3500));
    }

    @Test void correlatedFinderNamesCannotInflateTheScoreOrMoveTheEvidence() {
        BaseTracker.storeReport(8, 8, 55, "Chunk", 1000);
        BaseTracker.storeReport(8, 8, 55, "Activity", 1000);
        BaseTracker.storeReport(8, 8, 55, "Growth", 1000);
        assertEquals(55, BaseTracker.fusedNearestAt(0, 0, 10, 1001).getFirst().confidence());
        BaseTracker.storeReport(40, 8, 90, "Stash", 1000);
        var fused = BaseTracker.fusedNearestAt(0, 0, 10, 1001).getFirst();
        assertEquals(90, fused.confidence());
        assertEquals(40, fused.blockX());
        assertEquals(8, fused.blockZ());
        assertTrue(fused.sources().contains("Stash"));
    }

    @Test void diagonalReportsOutsideTheRadiusStaySeparate() {
        BaseTracker.storeReport(8, 8, 55, "Chunk", 1000);
        BaseTracker.storeReport(40, 56, 80, "Stash", 1000);
        assertEquals(2, BaseTracker.fusedNearestAt(0, 0, 10, 1001).size());
    }

    @BeforeEach void before() { BaseTracker.clear(); }
    @AfterEach void after() { BaseTracker.clear(); }

    @Test void collidingJavaSourceHashesCannotOverwriteIndependentReports() {
        assertEquals("Aa".hashCode(), "BB".hashCode());
        BaseTracker.storeReport(8, 8, 20, "Aa", 1000);
        BaseTracker.storeReport(8, 8, 20, "BB", 1000);
        assertEquals(2, BaseTracker.nearestAt(0, 0, 10, 1001).size());
        assertEquals(2, BaseTracker.retainedEntries());
    }

    @Test void expiryRemovesStorageAndOutlivesTheOneSecondReportingCadence() {
        BaseTracker.storeReport(8, 8, 60, "Stash", 1000);
        assertEquals(1, BaseTracker.nearestAt(0, 0, 10, 2100).size());
        assertTrue(BaseTracker.nearestAt(0, 0, 10, 3500).isEmpty());
        assertEquals(0, BaseTracker.retainedEntries());
    }

    @Test void zeroLimitsInvalidSourcesAndClockRollbackFailClosed() {
        assertFalse(BaseTracker.storeReport(8, 8, 60, "", 1000));
        assertFalse(BaseTracker.storeReport(8, 8, 0, "Stash", 1000));
        BaseTracker.storeReport(8, 8, 150, "Stash", 1000);
        assertEquals(100, BaseTracker.nearestAt(0, 0, 10, 1001).get(0).confidence());
        assertTrue(BaseTracker.nearestAt(0, 0, 0, 1001).isEmpty());
        assertTrue(BaseTracker.fusedNearestAt(0, 0, 0, 1001).isEmpty());
        assertTrue(BaseTracker.nearestAt(0, 0, 10, 999).isEmpty());
        assertEquals(0, BaseTracker.retainedEntries());
    }

    @Test void reportInsertionOrderCannotChangeTheFusedGroups() {
        BaseTracker.storeReport(8, 8, 50, "Stash", 1000);
        BaseTracker.storeReport(40, 8, 40, "Chunk", 1000);
        var first = BaseTracker.fusedNearestAt(0, 0, 10, 1001);
        BaseTracker.clear();
        BaseTracker.storeReport(40, 8, 40, "Chunk", 1000);
        BaseTracker.storeReport(8, 8, 50, "Stash", 1000);
        assertEquals(first, BaseTracker.fusedNearestAt(0, 0, 10, 1001));
    }
}