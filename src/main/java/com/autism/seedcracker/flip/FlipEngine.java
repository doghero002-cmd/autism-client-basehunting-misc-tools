package com.autism.seedcracker.flip;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.autism.seedcracker.flip.core.FlipModel.Listing;
import com.autism.seedcracker.flip.core.FlipModel.Opportunity;
import com.autism.seedcracker.flip.core.FlipModel.Sale;
import com.autism.seedcracker.flip.core.MarketAnalyzer;
import com.autism.seedcracker.flip.core.OpportunityDetector;
import com.autism.seedcracker.flip.core.PaperLedger;
import com.autism.seedcracker.flip.core.SaleInference;

/**
 * Glue between the data sources (keyless GUI scans, optional API) and the pure flip core.
 * Main-thread only except {@link #ingestApiSales}/{@link #ingestApiListings}, which callers hop
 * onto the client thread before invoking.
 */
public final class FlipEngine {

    /** Live-tunable knobs the module copies in from its settings each tick. */
    public static final class Tuning {
        public long minProfit = 5_000;
        public double minRoi = 12.0;
        public int minSamples = 12;
        public double minConfidence = 0.35;
        public double maxHoldHours = 3.0;
        public long maxBuy = Long.MAX_VALUE;
        public double salesTaxPercent = 0.0;
        public int halfLifeHours = 6;
        public boolean askFallback = true;
        public boolean benchLosers = true;
    }

    private final FlipStore store;
    private final SaleInference inference = new SaleInference(SaleInference.Config.defaults());
    private final PaperLedger ledger;
    private final Map<String, SaleInference.Scan> lastCompleteScan = new HashMap<>();
    private final List<Listing> scanBuffer = new ArrayList<>();
    private String scanSearchKey;
    private List<Opportunity> ranked = List.of();
    private Set<String> benched = Set.of();
    private long lastRankAt;
    private int inferredThisSession;
    private int apiSalesThisSession;
    private int ownSalesThisSession;

    public final Tuning tuning = new Tuning();

    public FlipEngine(Path dir) {
        this.store = new FlipStore(dir.resolve("flip-market.json"));
        this.ledger = new PaperLedger(OpportunityDetector.FeeConfig.zero(), 24 * MarketAnalyzer.Config.defaults().halfLifeMillis(), 12);
        store.load();
    }

    // ---- keyless GUI scanning ----

    /** Starts buffering a full pass for {@code searchKey} (the AH title/search the user is on). */
    public void beginScan(String searchKey) {
        scanSearchKey = searchKey;
        scanBuffer.clear();
    }

    /** One page's listings; they also merge into the live book so alerts work mid-scan. */
    public void addPage(List<Listing> page) {
        store.mergeBook(page);
        if (scanSearchKey == null) return;
        for (Listing l : page) {
            boolean dup = false;
            for (Listing b : scanBuffer) if (b.listingKey().equals(l.listingKey())) { dup = true; break; }
            if (!dup) scanBuffer.add(l);
        }
    }

    /** The pass reached the last page: diff against the previous complete pass to infer sales. */
    public int completeScan(long now) {
        return completeScan(now, null);
    }

    /**
     * As {@link #completeScan(long)}; when {@code localPlayer} is set, our own vanished listings
     * are recorded as confirmed fills (full weight) instead of discounted inferences.
     */
    public int completeScan(long now, String localPlayer) {
        if (scanSearchKey == null || scanBuffer.isEmpty()) return 0;
        SaleInference.Scan current = new SaleInference.Scan(scanSearchKey, now, scanBuffer);
        List<Sale> inferred = inference.infer(lastCompleteScan.get(scanSearchKey), current, localPlayer);
        lastCompleteScan.put(scanSearchKey, current);
        Map<String, List<Listing>> byItem = new HashMap<>();
        for (Listing l : scanBuffer) byItem.computeIfAbsent(l.itemKey(), k -> new ArrayList<>()).add(l);
        byItem.forEach(store::replaceBook);
        int added = store.addSales(inferred);
        inferredThisSession += added;
        scanSearchKey = null;
        scanBuffer.clear();
        store.save(false);
        return added;
    }

    /** Abandon a pass (screen closed or player moved): a partial pass can't prove anything sold. */
    public void abortScan() {
        scanSearchKey = null;
        scanBuffer.clear();
    }

    public boolean scanning() {
        return scanSearchKey != null;
    }

    public int scanBufferSize() {
        return scanBuffer.size();
    }

    // ---- own-sale chat feed (keyless ground truth) ----

    /**
     * A "<buyer> bought your <item> for $X" chat line: a CONFIRMED sale of our own listing, no API
     * key needed. Enters the store as inferred=false (full weight) and removes the matching ask
     * from the book. Returns true if the sale was new.
     */
    public boolean ingestOwnSale(String localPlayer, String itemKey, int count, long totalPrice, long now) {
        if (itemKey == null || itemKey.isBlank() || count <= 0 || totalPrice <= 0) return false;
        String saleKey = "own:" + localPlayer + ":" + itemKey + ":" + count + ":" + totalPrice + ":" + (now / 60_000);
        Sale sale = new Sale(saleKey, now, localPlayer, itemKey, count, totalPrice, false);
        int added = store.addSales(List.of(sale));
        if (added > 0) {
            ownSalesThisSession++;
            store.removeListing(itemKey, SaleInference.listingKey(localPlayer, itemKey, count, totalPrice));
            store.save(false);
        }
        return added > 0;
    }

    public int ownSalesThisSession() {
        return ownSalesThisSession;
    }

    // ---- API feed ----

    public void ingestApiSales(List<Sale> sales) {
        apiSalesThisSession += store.addSales(sales);
        // A real sale proves the matching ask is gone.
        for (Sale s : sales) store.removeListing(s.itemKey(), SaleInference.listingKey(s.seller(), s.itemKey(), s.count(), s.totalPrice()));
        store.save(false);
    }

    public void ingestApiListings(List<Listing> listings) {
        store.mergeBook(listings);
    }

    // ---- evaluation ----

    /** Best-first opportunities across every item with a known book; cached for 2s. */
    public List<Opportunity> rank(long now) {
        if (now - lastRankAt < 2_000) return ranked;
        lastRankAt = now;
        MarketAnalyzer analyzer = new MarketAnalyzer(MarketAnalyzer.Config.defaults().withHalfLifeHours(tuning.halfLifeHours));
        OpportunityDetector detector = new OpportunityDetector(
            new OpportunityDetector.FeeConfig(0, 0, tuning.salesTaxPercent),
            new OpportunityDetector.RiskConfig(tuning.minSamples, tuning.minProfit, tuning.minRoi, true, 0.25,
                3 * 3_600_000L, tuning.maxHoldHours, tuning.minConfidence, 1, tuning.maxBuy, 6));

        ledger.settle(store.allSalesSince(now - 48 * 3_600_000L), now);
        benched = tuning.benchLosers ? ledger.losingMarkets(2, 24 * 3_600_000L, now) : Set.of();

        List<Opportunity> out = new ArrayList<>();
        for (String item : store.bookItems()) {
            if (benched.contains(item)) continue;
            List<Listing> book = store.book(item, now);
            if (book.isEmpty()) continue;
            List<Sale> sales = store.sales(item);
            Map<com.autism.seedcracker.flip.core.FlipModel.StackBucket, MarketAnalyzer.Analysis> byBucket = new HashMap<>();
            for (Listing l : book) {
                MarketAnalyzer.Analysis a = byBucket.computeIfAbsent(l.bucket(), b -> analyzer.analyze(item, b, sales, now));
                OpportunityDetector.Evaluation e = a.stats().hasPrices()
                    ? detector.evaluate(l, a.stats(), a.keptSales(), book)
                    : tuning.askFallback ? detector.evaluateAgainstAsks(l, book, now) : null;
                if (e != null && e.accepted()) out.add(e.opportunity().get());
            }
        }
        out.sort(Comparator.comparingDouble(Opportunity::score).reversed());
        ranked = List.copyOf(out.size() > 50 ? out.subList(0, 50) : out);
        return ranked;
    }

    /** Records a paper position for an opportunity the user (or auto-paper) picked. */
    public boolean paper(Opportunity o, long now) {
        return ledger.open(o, now);
    }

    public PaperLedger.Summary paperSummary() {
        return ledger.summary();
    }

    public List<PaperLedger.Position> paperPositions() {
        return ledger.positions();
    }

    public Set<String> benched() {
        return benched;
    }

    public int inferredThisSession() {
        return inferredThisSession;
    }

    public int apiSalesThisSession() {
        return apiSalesThisSession;
    }

    public int knownSales() {
        return store.saleCount();
    }

    public int knownMarkets() {
        return store.marketCount();
    }

    public List<Sale> sales(String itemKey) {
        return store.sales(itemKey);
    }

    public void save() {
        store.save(true);
    }
}
