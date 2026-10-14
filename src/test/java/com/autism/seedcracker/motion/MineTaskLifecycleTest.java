package com.autism.seedcracker.motion;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MineTaskLifecycleTest {
    @Test
    void missingClientCannotStartOrActivateMining() {
        assertFalse(MineTask.start(null, Set.of(), 1));
        assertFalse(MineTask.start(null, null, 1));
        assertFalse(MineTask.active());
    }

    @Test
    void cancellationIsSafeWithoutAClientAndIsIdempotent() {
        assertDoesNotThrow(() -> MineTask.stop(null));
        assertDoesNotThrow(() -> MineTask.stop(null));
        assertFalse(MineTask.active());
    }
}