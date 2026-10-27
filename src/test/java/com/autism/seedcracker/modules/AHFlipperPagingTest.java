package com.autism.seedcracker.modules;

import com.autism.seedcracker.flip.FlipEngine;
import com.autism.seedcracker.flip.core.FlipModel.Listing;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class AHFlipperPagingTest {
    private static final long NOW = 1_800_000_000_000L;
    @TempDir Path dir;

    @Test void replacementMenusMustNotRestartAnAutomaticPassOrItsPageBudget() {
        var paging = new AHFlipperModule.Paging();
        assertTrue(paging.openMenu(10, "auction house - elytra"));
        assertTrue(paging.readPage("first", 20));
        paging.turn(20, NOW);
        assertFalse(paging.openMenu(11, "auction house - elytra"));
        assertEquals(1, paging.turns);
        assertFalse(paging.read, "must wait for the next page's actual contents");
        assertTrue(paging.readPage("second", 20));
        paging.turn(20, NOW + 2000);
        assertFalse(paging.openMenu(12, "auction house - elytra"));
        assertEquals(2, paging.turns);
    }

    @Test void changingSearchInTheSameMenuStartsANewPass() {
        var paging = new AHFlipperModule.Paging();
        assertTrue(paging.openMenu(10, "auction house - elytra"));
        paging.readPage("first", 20);
        assertTrue(paging.openMenu(10, "auction house - diamond"));
        assertEquals("auction house - diamond", paging.searchKey);
        assertEquals(0, paging.turns);
    }

    @Test void decoratedPageTitlesDoNotSplitOnePassButDifferentSearchesStillDo() {
        var paging = new AHFlipperModule.Paging();
        assertTrue(paging.openMenu(1, "§6Auction House - Elytra (Page 1/3)"));
        paging.readPage("first", 20);
        paging.turn(20, NOW);
        assertFalse(paging.openMenu(2, "§6Auction House - Elytra (Page 2/3)"));
        assertEquals("auction house - elytra", paging.searchKey);
        assertEquals(1, paging.turns);
        assertTrue(paging.openMenu(3, "Auction House - Diamonds (Page 1/3)"));
    }

    @Test void startingMidwayCannotMasqueradeAsACompletePass() {
        var paging = new AHFlipperModule.Paging();
        paging.openMenu(1, "Auction House - Page 3/4");
        assertFalse(paging.fromFirstPage);
        paging.readPage("third", 20);
        paging.turn(20, NOW);
        assertFalse(paging.openMenu(2, "Auction House - Page 4/4"));
        assertFalse(paging.fromFirstPage);
        paging.reset();
        paging.openMenu(3, "Auction House - Page 1/4");
        assertTrue(paging.fromFirstPage);
    }

    @Test void aPageThatNeverArrivesTimesOutInsteadOfWaitingForever() {
        var paging = new AHFlipperModule.Paging();
        assertFalse(paging.timedOut(NOW));
        paging.openMenu(1, "auction house");
        paging.readPage("first", 20);
        paging.turn(20, NOW);
        assertFalse(paging.timedOut(NOW + 7999));
        assertTrue(paging.timedOut(NOW + 8000));
        assertTrue(paging.timedOut(NOW - 1));
        paging.readPage("second", 20);
        assertFalse(paging.timedOut(NOW + 9000));
    }

    @Test void aBriefGapBetweenServerMenusKeepsThePassButUserScreensAndTimeoutsDoNot() {
        var paging = new AHFlipperModule.Paging();
        assertFalse(paging.waitingForMenu(NOW, false));
        paging.openMenu(1, "auction house");
        paging.readPage("first", 20);
        paging.turn(20, NOW);
        assertTrue(paging.waitingForMenu(NOW + 100, false));
        assertFalse(paging.waitingForMenu(NOW + 100, true));
        assertFalse(paging.waitingForMenu(NOW + 8000, false));
        paging.reset();
        assertFalse(paging.waitingForMenu(NOW + 100, false));
    }

    @Test void unchangedSlotsCannotBeMistakenForTheNextPage() {
        var paging = new AHFlipperModule.Paging();
        paging.openMenu(1, "auction house");
        paging.readPage("first", 20);
        paging.turn(20, NOW);
        assertFalse(paging.readPage("first", 20));
        assertFalse(paging.read);
        assertTrue(paging.awaitingPage);
        assertFalse(paging.readPage("", 20));
    }

    @Test void transientEmptySlotsCannotCompleteAPassAndTheSameDataCanRecover() {
        var paging = new AHFlipperModule.Paging();
        paging.openMenu(1, "auction house");
        paging.readPage("first", 20);
        assertFalse(paging.readPage("", 20));
        assertFalse(paging.read);
        assertFalse(paging.readPage("first", 20));
        assertTrue(paging.read);
    }

    @Test void differentCommandsKeepIdenticalServerTitlesInSeparateMarkets() {
        var paging = new AHFlipperModule.Paging();
        paging.openMenu(1, "ah diamond | Auction House - Page 1");
        paging.turn(20, NOW);
        assertFalse(paging.openMenu(2, "ah diamond | Auction House - Page 2"));
        assertTrue(paging.openMenu(2, "ah elytra | Auction House - Page 1"));
    }

    @Test void watchlistMayCloseOnlyItsOwnCompletedStationaryPass() {
        var watch = new AHFlipperModule.WatchPass();
        Object first = new Object(), next = new Object(), manual = new Object();
        var position = net.minecraft.world.phys.Vec3.ZERO;
        assertFalse(watch.canClose(manual, position));
        watch.request(position, NOW);
        watch.observe(first, true, position, NOW + 100);
        assertFalse(watch.canClose(first, position));
        watch.observe(next, false, position, NOW + 1000);
        watch.finished = true;
        assertTrue(watch.canClose(next, position));
        assertFalse(watch.canClose(first, position));
        assertFalse(watch.canClose(manual, position));
        watch.observe(manual, true, position, NOW + 1100);
        watch.finished = true;
        assertFalse(watch.canClose(manual, position));
    }

    @Test void movementLateResponsesAndCancellationReleaseWatchlistOwnership() {
        var watch = new AHFlipperModule.WatchPass();
        Object menu = new Object();
        var position = net.minecraft.world.phys.Vec3.ZERO;
        watch.request(position, NOW);
        assertTrue(watch.expired(NOW + 8000));
        watch.observe(menu, true, position, NOW + 8000);
        watch.finished = true;
        assertFalse(watch.canClose(menu, position));
        watch.request(position, NOW);
        watch.observe(menu, true, position, NOW + 100);
        watch.finished = true;
        assertFalse(watch.canClose(menu, new net.minecraft.world.phys.Vec3(1, 0, 0)));
        watch.observe(menu, false, new net.minecraft.world.phys.Vec3(1, 0, 0), NOW + 200);
        watch.finished = true;
        assertFalse(watch.canClose(menu, position));
        watch.request(position, NOW);
        watch.observe(menu, true, position, NOW + 100);
        watch.clear();
        watch.finished = true;
        assertFalse(watch.canClose(menu, position));
    }

    @Test void twoMultiMenuPassesRetainAllPagesAndInferTheRightVanishedListing() {
        var engine = new FlipEngine(dir);
        var paging = new AHFlipperModule.Paging();
        Listing a = listing("a", 90_000), b = listing("b", 95_000);
        Listing c = listing("c", 97_000), d = listing("d", 99_000);
        feed(engine, paging, 10, List.of(a, b));
        paging.turn(20, NOW);
        feed(engine, paging, 11, List.of(c, d));
        assertEquals(4, engine.scanBufferSize());
        assertEquals(0, engine.completeScan(NOW, "Me"));
        paging.reset();
        feed(engine, paging, 20, List.of(b, c));
        paging.turn(20, NOW + 300_000);
        feed(engine, paging, 21, List.of(d));
        assertEquals(1, engine.completeScan(NOW + 300_000, "Me"));
        assertEquals(90_000, engine.sales("elytra").getFirst().totalPrice());
        assertTrue(engine.sales("elytra").getFirst().inferred());
    }

    private static Listing listing(String id, long price) {
        return new Listing(id, NOW, "Seller" + id, "elytra", 1, price);
    }

    private static void feed(FlipEngine engine, AHFlipperModule.Paging paging, int menu, List<Listing> page) {
        String key = "auction house - elytra";
        if (paging.openMenu(menu, key)) engine.beginScan(key);
        if (paging.readPage(page.toString(), 20)) engine.addPage(page);
    }
}
