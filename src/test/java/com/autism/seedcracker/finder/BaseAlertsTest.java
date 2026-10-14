package com.autism.seedcracker.finder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BaseAlertsTest {
    @BeforeEach void before() { BaseAlerts.reset(); }
    @AfterEach void after() { BaseAlerts.reset(); }

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