package com.autism.seedcracker.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiCompatTest {
    @Test
    void retiredSusModesMigrateThroughTheRealSettingsDecoder() {
        var setting = new autismclient.api.module.EnumSetting<>("mode", "Detection mode",
            com.autism.seedcracker.modules.SusChunkFinderModule.Mode.CLASSIC,
            com.autism.seedcracker.modules.SusChunkFinderModule.Mode.values(),
            com.autism.seedcracker.modules.SusChunkFinderModule.Mode::toString);
        assertEquals(3, com.autism.seedcracker.modules.SusChunkFinderModule.Mode.values().length);
        for (String retired : new String[]{"GEODE", "DOGS", "BETA", "WATER", "XENON", "TYPES",
            "NEW_CHUNKS", "OLD_CHUNKS", "TUNNEL", "ACTIVITY", "SIGNAL", "FARM"}) {
            assertEquals("Classic", setting.deserialize(retired), retired);
        }
        assertEquals("Classic", setting.deserialize("CLASSIC"));
        assertEquals("Dog+", setting.deserialize("ANBS_PLUS"));
        assertEquals("Dog+", setting.deserialize("Dog+"));
        assertEquals("Dog+", setting.deserialize(com.autism.seedcracker.modules.SusChunkFinderModule.Mode.migrate("ANBS+ Scan", com.autism.seedcracker.modules.SusChunkFinderModule.Mode.values())));
        assertEquals("ANBS+ Scan", com.autism.seedcracker.modules.SusChunkFinderModule.Mode.migrate("ANBS+ Scan", com.autism.seedcracker.modules.PlayerChunksModule.Mode.values()));
    }

    @Test
    void bothPlayerFinderModesRoundTripThroughTheRealSettingsDecoder() {
        var setting = new autismclient.api.module.EnumSetting<>("mode", "Detection mode",
            com.autism.seedcracker.modules.PlayerChunksModule.Mode.LIGHT_DATA,
            com.autism.seedcracker.modules.PlayerChunksModule.Mode.values());
        assertEquals(2, com.autism.seedcracker.modules.PlayerChunksModule.Mode.values().length);
        assertEquals("Light Data", setting.deserialize("LIGHT_DATA"));
        assertEquals("Simulation Activity", setting.deserialize("SIMULATION_ACTIVITY"));
        assertEquals("Simulation Activity", setting.deserialize("Simulation Activity"));
    }

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
