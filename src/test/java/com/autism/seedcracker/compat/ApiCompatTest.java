package com.autism.seedcracker.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiCompatTest {
    @Test
    void publicClientGetsItsOwnOlderVersion() {
        // Built against V4 (API 4), running on the public 5.0 client (API 3): declaring 4 would get the addon skipped.
        assertEquals(3, ApiCompat.declared(4, 3));
    }

    @Test
    void matchingClientGetsTheSameVersion() {
        assertEquals(4, ApiCompat.declared(4, 4));
    }

    @Test
    void newerClientIsCappedAtWhatWeWereBuiltFor() {
        assertEquals(4, ApiCompat.declared(4, 5));
    }

    @Test
    void unknownHostVersionFallsBackToTheCompiledOne() {
        assertEquals(4, ApiCompat.declared(4, -1));
        assertEquals(4, ApiCompat.declared(4, 0));
    }
}
