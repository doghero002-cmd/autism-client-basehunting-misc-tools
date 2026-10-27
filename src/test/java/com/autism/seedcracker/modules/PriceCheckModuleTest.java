package com.autism.seedcracker.modules;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PriceCheckModuleTest {
    @Test void stoppedScanCannotRestartOnTheNextPollOrAfterMovement() {
        var scan = new PriceCheckModule.Scan();
        assertTrue(scan.begin(Vec3.ZERO));
        scan.stop();
        for (int poll = 0; poll < 20; poll++) {
            assertFalse(scan.begin(new Vec3(poll, 0, 0)), "poll " + poll);
        }
    }

    @Test void reopeningRearmsTheScanButStoppingItAgainStillLatches() {
        var scan = new PriceCheckModule.Scan();
        assertTrue(scan.begin(Vec3.ZERO));
        scan.stop();
        scan.reset();
        assertTrue(scan.begin(new Vec3(5, 0, 0)));
        scan.stop();
        assertFalse(scan.begin(Vec3.ZERO));
    }

    @Test void aNewContainerForEachAutomaticPageDoesNotResetTheBudget() {
        var scan = new PriceCheckModule.Scan();
        scan.begin(Vec3.ZERO);
        for (int page = 1; page <= 10; page++) {
            scan.advance(40);
            scan.menuChanged(true);
            assertEquals(page, scan.turns);
            assertEquals(40, scan.cooldown);
            assertEquals(Vec3.ZERO, scan.start);
        }
    }

    @Test void aLatePageResponseCannotUndoAStopButAnUnrelatedMenuResetsTheScan() {
        var scan = new PriceCheckModule.Scan();
        scan.begin(Vec3.ZERO);
        scan.advance(40);
        scan.stop();
        scan.menuChanged(true);
        assertFalse(scan.begin(Vec3.ZERO));
        assertEquals(1, scan.turns);
        scan.menuChanged(false);
        assertTrue(scan.begin(Vec3.ZERO));
        assertEquals(0, scan.turns);
    }

    @Test void pollingDoesNotResetThePageCountAndResetClearsAllScanState() {
        var scan = new PriceCheckModule.Scan();
        scan.begin(Vec3.ZERO);
        scan.turns = 10;
        scan.cooldown = 20;
        scan.hadData = true;
        assertTrue(scan.begin(new Vec3(2, 0, 0)));
        assertEquals(10, scan.turns);
        assertEquals(Vec3.ZERO, scan.start);
        scan.reset();
        assertEquals(0, scan.turns);
        assertEquals(0, scan.cooldown);
        assertFalse(scan.hadData);
        assertNull(scan.start);
    }
}
