/* SPDX-License-Identifier: GPL-3.0-only */
package com.autism.seedcracker.spectre.mechanics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpawnerStorageProofTest {
    @Test void disappearanceWithoutConservedTransferNeverCompletesBatch() {
        var proof = new SpawnerStorageProof(); proof.begin(64);
        assertFalse(proof.complete()); proof.transferred(4); assertFalse(proof.complete());
        proof.transferred(60); assertTrue(proof.complete());
    }
    @Test void newBatchAndCancellationCannotReuseOldSuccess() {
        var proof = new SpawnerStorageProof(); proof.begin(1); proof.transferred(1); assertTrue(proof.complete());
        proof.begin(12); assertFalse(proof.complete()); proof.clear(); assertFalse(proof.complete());
        assertThrows(IllegalStateException.class, () -> proof.transferred(1));
        assertThrows(IllegalArgumentException.class, () -> proof.begin(0));
    }
    @Test void placingRequiresBothServerBlockAndInventoryEvidenceForTheExactItem() {
        assertTrue(SpawnerStorageProof.placed(true, true, true, 1, 0));
        assertTrue(SpawnerStorageProof.placed(true, true, true, 4, 3));
        assertFalse(SpawnerStorageProof.placed(false, true, true, 1, 0));
        assertFalse(SpawnerStorageProof.placed(true, false, true, 1, 0));
        assertFalse(SpawnerStorageProof.placed(true, true, false, 4, 3));
        assertFalse(SpawnerStorageProof.placed(true, true, true, 1, 1));
        assertFalse(SpawnerStorageProof.placed(true, true, true, 0, -1));
    }
}
