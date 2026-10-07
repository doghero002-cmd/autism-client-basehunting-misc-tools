package com.autism.seedcracker.motion.pure;

import org.junit.jupiter.api.Test;

import com.autism.seedcracker.motion.pure.TripPolicy.Verdict;

import static org.junit.jupiter.api.Assertions.*;

class TripPolicyTest {

    @Test
    void longTripWithManyProgressingSegmentsNeverGivesUp() {
        TripPolicy t = new TripPolicy(6, 4);
        for (double d = 10_000; d > 0; d -= 150) assertEquals(Verdict.CONTINUE, t.segmentDone(d));
    }

    @Test
    void wedgedBotGivesUpAfterBudget() {
        TripPolicy t = new TripPolicy(3, 4);
        assertEquals(Verdict.CONTINUE, t.failure(500));
        assertEquals(Verdict.CONTINUE, t.failure(500));
        assertEquals(Verdict.CONTINUE, t.failure(499));
        assertEquals(Verdict.GIVE_UP, t.failure(500));
    }

    @Test
    void segmentsThatGoNowhereCountAsFailures() {
        TripPolicy t = new TripPolicy(2, 4);
        assertEquals(Verdict.CONTINUE, t.segmentDone(300));
        assertEquals(Verdict.CONTINUE, t.segmentDone(302), "planner loop: no closer");
        assertEquals(Verdict.CONTINUE, t.segmentDone(299));
        assertEquals(Verdict.GIVE_UP, t.segmentDone(301));
    }

    @Test
    void realProgressRefundsFailures() {
        TripPolicy t = new TripPolicy(2, 4);
        t.failure(400);
        t.failure(400);
        assertEquals(2, t.failures());
        assertEquals(Verdict.CONTINUE, t.segmentDone(300));
        assertEquals(0, t.failures());
    }
}
