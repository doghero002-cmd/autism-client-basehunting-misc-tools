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
