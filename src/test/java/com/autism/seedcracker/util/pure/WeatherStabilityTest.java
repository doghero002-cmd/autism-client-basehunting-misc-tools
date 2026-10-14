package com.autism.seedcracker.util.pure;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WeatherStabilityTest {
    @Test void onlyTwentyConsecutiveChangedSamplesPublishATransition() {
        WeatherStability state = new WeatherStability();
        assertFalse(state.update(false));
        for (int tick = 0; tick < 19; tick++) assertFalse(state.update(true));
        assertTrue(state.update(true));
        assertTrue(state.value());
        assertFalse(state.update(true));
    }

    @Test void interruptedChangesAndWorldResetsDoNotProducePhantomAlerts() {
        WeatherStability state = new WeatherStability();
        state.update(false);
        for (int tick = 0; tick < 19; tick++) state.update(true);
        assertFalse(state.update(false));
        assertFalse(state.update(true));
        assertFalse(state.value());
        state.reset();
        assertFalse(state.update(true));
        assertTrue(state.value());
    }
}