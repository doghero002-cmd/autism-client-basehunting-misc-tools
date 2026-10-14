package com.autism.seedcracker.motion.pure;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScoutLeaseTest {
    @Test void onlyOwnerCanRenewAndExpiredSessionsCannotResume() {
        var lease = new ScoutLease();
        UUID owner = UUID.randomUUID(), foreign = UUID.randomUUID();
        lease.bind(owner, 100);
        assertTrue(lease.owns(owner)); assertFalse(lease.owns(foreign)); assertFalse(lease.owns(null));
        assertFalse(lease.heartbeat(foreign, 1000));
        assertEquals(ScoutLease.WINDOW_NANOS - 900, lease.remaining(1000));
        assertTrue(lease.heartbeat(owner, 1000));
        assertFalse(lease.expired(1000 + ScoutLease.WINDOW_NANOS - 1));
        assertTrue(lease.expired(1000 + ScoutLease.WINDOW_NANOS));
        assertFalse(lease.heartbeat(owner, 1000 + ScoutLease.WINDOW_NANOS));
        assertEquals(0, lease.remaining(1000 + ScoutLease.WINDOW_NANOS));
    }

    @Test void clockRollbackFailsClosedAndClearingInvalidatesOldOwner() {
        var lease = new ScoutLease();
        UUID oldOwner = UUID.randomUUID(), nextOwner = UUID.randomUUID();
        lease.bind(oldOwner, 1000);
        assertTrue(lease.expired(999)); assertFalse(lease.heartbeat(oldOwner, 999));
        assertThrows(IllegalStateException.class, () -> lease.bind(nextOwner, 2000));
        lease.clear(); assertFalse(lease.bound()); assertFalse(lease.expired(999));
        lease.bind(nextOwner, 2000);
        assertFalse(lease.heartbeat(oldOwner, 2100)); assertTrue(lease.heartbeat(nextOwner, 2100));
    }
}