package com.autism.seedcracker.spectre.mechanics;

import org.junit.jupiter.api.Test;

import static com.autism.seedcracker.spectre.mechanics.HomeMetaSequence.Step.*;
import static org.junit.jupiter.api.Assertions.*;

class HomeMetaSequenceTest {
    private final HomeMetaSequence sequence = new HomeMetaSequence();

    @Test
    void legacySetKeepsDeleteDelaySetAndCustomTemplates() {
        sequence.startSet(4, true, 750, "/homes create %slot%", " homes remove %slot% ", 0);
        assertEquals("homes remove 4", next(0));
        sequence.commandSent(0);
        assertNull(next(749));
        assertEquals("homes create 4", next(750));
        sequence.commandSent(750);
        assertEquals(COMPLETE, sequence.step());
        assertNull(next(751));
    }

    @Test
    void legacySetWithoutDeleteStillHonorsConfiguredDelay() {
        sequence.startSet(1, false, 750, "sethome %slot%", "delhome %slot%", 0);
        assertNull(next(0));
        assertNull(next(749));
        assertEquals("sethome 1", next(750));
        sequence.commandSent(750);
        assertEquals(COMPLETE, sequence.step());
    }

    @Test
    void pacingDenialDoesNotConsumeACommandOrStartTheNextDelay() {
        startMeta();
        assertEquals("delhome 3", next(0));
        assertEquals("delhome 3", next(2_000));
        sequence.commandSent(2_000);
        assertNull(next(2_499));
        assertEquals("sethome 3", next(2_500));
    }

    @Test
    void metaUsesDeleteThenSetNotTheOldDoubleSethomeSequence() {
        startMeta();
        assertEquals("delhome 3", next(0));
        sequence.commandSent(0);
        assertEquals("sethome 3", next(500));
        sequence.commandSent(500);
        assertEquals(WAIT_SET, sequence.step());
        assertNull(next(1_000));
        sequence.onChatMessage("§aHome 3 has been set!", 1_000);
        assertNull(next(1_499));
        assertEquals("rtp", next(1_500));
    }

    @Test
    void wrongHomeAndFutureTenseDoNotConfirmHomeSet() {
        waitForSet();
        sequence.onChatMessage("Home 2 has been set!", 600);
        sequence.onChatMessage("Your home will be set later", 650);
        sequence.onChatMessage("Use /sethome to set your home", 700);
        assertEquals(WAIT_SET, sequence.step());
        sequence.onChatMessage("Successfully set your home 3", 750);
        assertEquals(RTP, sequence.step());
    }

    @Test
    void missingSetConfirmationAbortsWithoutSendingRtp() {
        waitForSet();
        assertNull(next(10_500));
        assertEquals(FAILED, sequence.step());
        assertTrue(sequence.failure().contains("confirmation"));
        assertNull(next(90_000));
    }

    @Test
    void lateConfirmationCannotReviveAnExpiredSetStep() {
        waitForSet();
        sequence.onChatMessage("Home 3 has been set", 10_500);
        assertEquals(WAIT_SET, sequence.step());
        next(10_500);
        sequence.onChatMessage("Home 3 has been set", 10_501);
        assertEquals(FAILED, sequence.step());
    }

    @Test
    void manualWalkingCannotMasqueradeAsRtp() {
        waitForRtp();
        assertNull(sequence.nextCommand(2_000, 10_000, 10_000));
        assertEquals(WAIT_RTP, sequence.step());
        sequence.onServerTeleport();
        assertNull(sequence.nextCommand(2_000, 25.0, 10_000));
        assertEquals(WAIT_RTP, sequence.step());
        assertNull(sequence.nextCommand(2_001, 25.01, 10_000));
        assertEquals(HOME, sequence.step());
        assertEquals("home 3", sequence.nextCommand(2_501, 25.01, 10_000));
    }

    @Test
    void rtpTimeoutAbortsInsteadOfBlindlySendingHome() {
        waitForRtp();
        assertNull(next(11_000));
        assertEquals(FAILED, sequence.step());
        assertNull(next(20_000));
    }

    @Test
    void returnHomeRequiresANewServerTeleportAndActualArrival() {
        waitForRtp();
        sequence.onServerTeleport();
        sequence.nextCommand(2_000, 100, 100);
        assertEquals("home 3", sequence.nextCommand(2_500, 100, 100));
        sequence.commandSent(2_500);
        sequence.nextCommand(3_000, 100, 0);
        assertEquals(WAIT_HOME, sequence.step());
        sequence.onServerTeleport();
        sequence.nextCommand(3_001, 100, 36);
        assertEquals(WAIT_HOME, sequence.step());
        sequence.nextCommand(3_002, 100, 25);
        assertEquals(COMPLETE, sequence.step());
    }

    @Test
    void missingDeleteSlotIsHarmlessButSetFailurePreventsRtp() {
        startMeta();
        sequence.commandSent(0);
        sequence.onChatMessage("Home 3 does not exist", 100);
        assertEquals(SET, sequence.step());
        sequence.commandSent(500);
        sequence.onChatMessage("You cannot set a home here", 600);
        assertEquals(FAILED, sequence.step());
        assertNull(next(1_500));
    }

    @Test
    void serverCancellationStopsTheSequence() {
        waitForRtp();
        sequence.onChatMessage("Teleportation cancelled because you moved", 1_050);
        assertEquals(FAILED, sequence.step());
        assertNull(next(2_000));
    }

    @Test
    void cancelAndRestartDiscardTimersAndTeleportEvidence() {
        waitForRtp();
        sequence.onServerTeleport();
        long previousVersion = sequence.version();
        sequence.cancel();
        assertEquals(IDLE, sequence.step());
        assertNull(next(5_000));
        assertTrue(sequence.version() > previousVersion);
        sequence.startSet(7, false, 100, "sethome %slot%", null, 6_000);
        assertNull(next(6_099));
        assertEquals("sethome 7", next(6_100));
    }

    @Test
    void prolongedPacerHoldIsBounded() {
        startMeta();
        assertNull(next(180_000));
        assertEquals(FAILED, sequence.step());
        sequence.startSet(1, true, 750, null, null, 0);
        assertNull(next(30_000));
        assertEquals(FAILED, sequence.step());
    }

    @Test
    void nonfiniteMovementNeverConfirmsTeleport() {
        waitForRtp();
        sequence.onServerTeleport();
        sequence.nextCommand(2_000, Double.NaN, 0);
        sequence.nextCommand(2_001, Double.POSITIVE_INFINITY, 0);
        assertEquals(WAIT_RTP, sequence.step());
    }

    @Test
    void commandTemplatesKeepLegacySyntaxButRejectMultilineAndOversizedCommands() {
        assertEquals("sethome 4", HomeMetaSequence.buildCommand(" /sethome %slot% ", 4, "unused"));
        assertEquals("delhome 1", HomeMetaSequence.buildCommand(null, 1, "delhome %slot%"));
        assertThrows(IllegalArgumentException.class,
            () -> HomeMetaSequence.buildCommand("sethome 1\nrtp", 1, "unused"));
        assertThrows(IllegalArgumentException.class,
            () -> HomeMetaSequence.buildCommand("x".repeat(257), 1, "unused"));
    }

    private String next(long now) { return sequence.nextCommand(now, 0, 0); }

    private void startMeta() {
        sequence.startMeta(3, 500, 10_000, "sethome %slot%", "delhome %slot%", "rtp", 0);
    }

    private void waitForSet() {
        startMeta();
        sequence.commandSent(0);
        sequence.commandSent(500);
    }

    private void waitForRtp() {
        waitForSet();
        sequence.onChatMessage("Home 3 has been set", 500);
        assertEquals("rtp", next(1_000));
        sequence.commandSent(1_000);
    }
}
