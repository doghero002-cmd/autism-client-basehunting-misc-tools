package com.autism.seedcracker.flip;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.autism.seedcracker.flip.core.FlipModel.Listing;
import com.autism.seedcracker.flip.core.FlipModel.Sale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class FlipStateTest {
    private static final long NOW = 1_800_000_000_000L;
    @TempDir Path dir;

    private static Listing listing(String key, long price) {
        return new Listing(key, NOW, "Alice", "diamond", 1, price);
    }

    @Test
    void bookChangesAndFilterChangesInvalidateRankWithoutWaitingTwoSeconds() {
        FlipEngine engine = new FlipEngine(dir);
        engine.tuning.minProfit = 1;
        engine.tuning.minRoi = 1;
        assertTrue(engine.rank(NOW).isEmpty());
        List<Listing> book = new ArrayList<>();
        Listing cheap = listing("cheap", 10);
        book.add(cheap);
        for (int i = 0; i < 8; i++) book.add(listing("ask" + i, 100));
        engine.ingestApiListings(book);
        assertFalse(engine.rank(NOW + 1).isEmpty(), "fresh listings must not use an old empty rank");
        engine.tuning.maxBuy = 0;
        engine.invalidateRank();
        assertTrue(engine.rank(NOW + 2).isEmpty(), "an invalid cap must stop buys immediately");
        engine.tuning.maxBuy = Long.MAX_VALUE;
        engine.invalidateRank();
        assertFalse(engine.rank(NOW + 3).isEmpty());
        engine.forgetListing(cheap);
        assertTrue(engine.rank(NOW + 4).isEmpty(), "a bought listing must leave the cached rank");
    }

    @Test
    void collectingTheFirstSaleMustNotMakeAskOnlyDealsDisappear() {
        FlipEngine engine = new FlipEngine(dir);
        engine.tuning.minProfit = 1;
        engine.tuning.minRoi = 1;
        List<Listing> book = new ArrayList<>();
        book.add(listing("cheap", 10));
        for (int i = 0; i < 8; i++) book.add(listing("ask" + i, 100));
        engine.addPage(book);
        assertEquals(1, engine.rank(NOW).size());
        engine.ingestApiSales(List.of(new Sale("first-sale", NOW - 1000, "Bob", "diamond", 1, 100, false)));
        var opportunities = engine.rank(NOW + 1);
        assertEquals(1, opportunities.size(), "thin sale history must still use the enabled ask-only fallback");
        assertEquals(com.autism.seedcracker.flip.core.FlipModel.Basis.ASKS, opportunities.getFirst().basis());
        assertFalse(com.autism.seedcracker.market.AhGui.canAutoBuy(opportunities.getFirst(), "Me", NOW + 1, 1000));
        engine.tuning.askFallback = false;
        engine.invalidateRank();
        assertTrue(engine.rank(NOW + 2).isEmpty(), "fallback off must still require the configured sale count");
    }

    @Test
    void enoughSalesDoNotBypassConfidenceFiltersByFallingBackToAsks() {
        FlipEngine engine = new FlipEngine(dir);
        engine.tuning.minProfit = 1;
        engine.tuning.minRoi = 1;
        engine.tuning.minConfidence = 0.95;
        List<Listing> book = new ArrayList<>();
        book.add(listing("cheap", 10));
        for (int i = 0; i < 8; i++) book.add(listing("ask" + i, 100));
        engine.addPage(book);
        List<Sale> sales = new ArrayList<>();
        for (int i = 0; i < engine.tuning.minSamples; i++) {
            sales.add(new Sale("sale" + i, NOW - 1000 - i, "SameSeller", "diamond", 1, 100, false));
        }
        engine.ingestApiSales(sales);
        assertTrue(engine.rank(NOW).isEmpty(), "established but low-confidence history must stay rejected");
    }

    @Test
    void anEmptyListExplainsWhetherDataIsMissingOrFiltersRejectedIt() {
        FlipEngine engine = new FlipEngine(dir);
        assertTrue(engine.rank(NOW).isEmpty());
        assertEquals(0, engine.liveListings());
        engine.addPage(List.of(listing("one", 100)));
        assertTrue(engine.rank(NOW + 1).isEmpty());
        assertEquals(1, engine.liveListings());
        assertTrue(engine.rejections().stream().anyMatch(reason -> reason.contains("other asks")));
        engine.resetBook();
        engine.rank(NOW + 2);
        assertEquals(0, engine.liveListings());
        assertTrue(engine.rejections().isEmpty());
    }

    @Test
    void reconnectDropsTransientBookAndIncompletePassesButKeepsSales() {
        FlipEngine engine = new FlipEngine(dir);
        engine.ingestApiSales(List.of(new Sale("sale", NOW - 1000, "Alice", "diamond", 1, 100, false)));
        engine.beginScan("auction house");
        engine.addPage(List.of(listing("ask", 100)));
        assertTrue(engine.scanning());
        engine.resetBook();
        assertFalse(engine.scanning());
        assertEquals(0, engine.scanBufferSize());
        assertTrue(engine.rank(NOW).isEmpty());
        assertEquals(1, engine.knownSales());
    }

    @Test
    void failedSaveDoesNotClearDirtySalesOrDebounceTheRetry() throws Exception {
        Path blocked = dir.resolve("blocked");
        Files.writeString(blocked, "not a directory");
        Path file = blocked.resolve("market.json");
        FlipStore store = new FlipStore(file);
        store.addSales(List.of(new Sale("sale", NOW, "Alice", "diamond", 1, 100, false)));
        store.save(false);
        assertFalse(Files.exists(file));
        Files.delete(blocked);
        Files.createDirectory(blocked);
        store.save(false);
        FlipStore loaded = new FlipStore(file);
        loaded.load();
        assertEquals(1, loaded.saleCount(), "failed disk writes must remain retryable");
    }
}
