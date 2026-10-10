package com.autism.seedcracker.util.pure;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StorageClearStateTest {
    @Test
    void clearNeedsAFreshGameplayPressAndForgetsOnlyKnownPositions() {
        for (int bind : new int[]{Integer.MIN_VALUE, -1008, -999, -2, -1, 0, 31, 349, Integer.MAX_VALUE}) {
            assertFalse(StorageClearState.validBind(bind), "Invalid bind " + bind);
        }
        for (int bind : new int[]{-1007, -1000, 32, 65, 348}) {
            assertTrue(StorageClearState.validBind(bind), "Valid bind " + bind);
        }

        StorageClearState state = new StorageClearState();
        assertFalse(state.poll(65, true, true), "Enabling with a held key must not clear");
        assertFalse(state.poll(65, false, true));
        assertTrue(state.poll(65, true, true));
        assertFalse(state.poll(65, true, true), "A held key only clears once");
        assertFalse(state.poll(65, false, true));
        assertFalse(state.poll(65, true, false), "Screens and focus loss block the bind");
        assertFalse(state.poll(65, true, true), "Closing a screen with a held key must not clear");
        assertFalse(state.poll(65, false, false), "Release inside a screen does not arm the bind");
        assertFalse(state.poll(65, true, true));
        assertFalse(state.poll(65, false, true));
        assertTrue(state.poll(65, true, true));
        assertFalse(state.poll(-1001, true, true), "Rebinding to a held mouse button must not clear");
        assertFalse(state.poll(-1001, false, true));
        assertTrue(state.poll(-1001, true, true));
        state.disarm();
        assertFalse(state.poll(-1001, true, true), "Disconnecting disarms the bind");

        Map<Long, Byte> record = new HashMap<>(Map.of(10L, (byte) 0, 20L, (byte) 1));
        state.clear(record);
        assertTrue(record.isEmpty());
        assertFalse(state.allowsRecord(10L), "The next sweep must not restore cleared storage");
        assertFalse(state.allowsRecord(20L));
        assertTrue(state.allowsRecord(30L), "Genuinely new positions can still be recorded");
        record.put(30L, (byte) 0);
        state.clear(record);
        assertFalse(state.allowsRecord(10L), "Repeated clears preserve earlier forgotten positions");
        assertFalse(state.allowsRecord(30L));
        state.observeMissing(position -> false);
        assertFalse(state.allowsRecord(10L), "Unloaded positions remain forgotten");
        state.observeMissing(position -> position == 10L);
        assertTrue(state.allowsRecord(10L), "Storage replaced after being observed gone is new");
        assertFalse(state.allowsRecord(20L));
        state.reset();
        assertTrue(state.allowsRecord(20L), "Forgotten positions never leak into another context");
        assertFalse(state.poll(-1001, true, true), "A context switch also disarms the bind");
    }
}
