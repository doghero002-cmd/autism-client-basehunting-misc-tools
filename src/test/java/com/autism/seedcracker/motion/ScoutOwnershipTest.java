package com.autism.seedcracker.motion;

import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScoutOwnershipTest {
    @Test void foreignControllerCannotStopAnOwnedGuardedTrip() throws Exception {
        var field=Motion.class.getDeclaredField("movementGuard"); field.setAccessible(true);
        Object before=field.get(null);
        BooleanSupplier owner=()->true, foreign=()->true;
        try {
            field.set(null,owner);
            assertTrue(Motion.guardedBy(owner)); assertFalse(Motion.guardedBy(foreign)); assertFalse(Motion.guardedBy(null));
            Motion.stopGuarded(null,foreign);
            assertSame(owner,field.get(null));
        } finally { field.set(null,before); }
    }
}