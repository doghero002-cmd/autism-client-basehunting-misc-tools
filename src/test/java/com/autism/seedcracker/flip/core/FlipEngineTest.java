package com.autism.seedcracker.flip.core;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.autism.seedcracker.flip.core.FlipModel.Basis;
import com.autism.seedcracker.flip.core.FlipModel.Listing;
import com.autism.seedcracker.flip.core.FlipModel.MarketStats;
import com.autism.seedcracker.flip.core.FlipModel.Sale;
import com.autism.seedcracker.flip.core.FlipModel.StackBucket;

import static org.junit.jupiter.api.Assertions.*;

class FlipEngineTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long MIN = 60_000L;
    private static final String ITEM = "ender_pearl";

    private static List<Sale> sales(int n, long base, boolean inferred) {
        List<Sale> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new Sale("s" + i, NOW - i * 3 * MIN, "seller" + (i % 9), ITEM, 16, base + (i % 5) * 1000, inferred));
        }
        return out;
    }

    private static Listing listing(String key, long price) {
        return new Listing(key, NOW, "bob", ITEM, 16, price);
    }

    private static OpportunityDetector detector() {
        return new OpportunityDetector(OpportunityDetector.FeeConfig.zero(), OpportunityDetector.RiskConfig.defaults());
    }

    @Test
    void bandsAreOrderedAndTrollIsAnOutlier() {
        List<Sale> s = sales(40, 90_000, false);
        s.add(new Sale("troll", NOW - 30_000, "troll", ITEM, 16, 90_000_000, false));
        MarketAnalyzer.Analysis a = new MarketAnalyzer(MarketAnalyzer.Config.defaults()).analyze(ITEM, StackBucket.X16, s, NOW);
        MarketStats st = a.stats();
        assertEquals(1, a.outliers().size());
        assertTrue(st.lowerBound() <= st.quickSalePrice());
        assertTrue(st.quickSalePrice() <= st.weightedMedian());
        assertTrue(st.weightedMedian() <= st.patientSalePrice());
        assertTrue(st.patientSalePrice() <= st.upperBound());
    }

    @Test
    void futureSalesNeverLeakIntoStats() {
        List<Sale> s = sales(30, 90_000, false);
        s.add(new Sale("future", NOW + 10 * MIN, "x", ITEM, 16, 1, false));
        MarketStats st = new MarketAnalyzer(MarketAnalyzer.Config.defaults()).analyze(ITEM, StackBucket.X16, s, NOW).stats();
        assertEquals(30, st.sampleCount() + st.outlierCount());
    }

    @Test
    void cheapListingIsAcceptedAndCappedByNextAsk() {
        List<Sale> s = sales(47, 94_000, false);
        MarketAnalyzer.Analysis a = new MarketAnalyzer(MarketAnalyzer.Config.defaults()).analyze(ITEM, StackBucket.X16, s, NOW);
        Listing candidate = listing("c", 40_000);
        OpportunityDetector.Evaluation e = detector().evaluate(candidate, a.stats(), a.keptSales(),
            List.of(candidate, listing("next", 91_000)));
        assertTrue(e.accepted(), () -> e.rejections().toString());
        assertTrue(e.opportunity().get().sellPrice() <= 90_999);
        assertEquals(Basis.SALES, e.opportunity().get().basis());
    }

    @Test
    void competitorBelowCostKillsTheMargin() {
        List<Sale> s = sales(47, 94_000, false);
        MarketAnalyzer.Analysis a = new MarketAnalyzer(MarketAnalyzer.Config.defaults()).analyze(ITEM, StackBucket.X16, s, NOW);
        Listing candidate = listing("c", 40_000);
        OpportunityDetector.Evaluation e = detector().evaluate(candidate, a.stats(), a.keptSales(), List.of(listing("cheap", 41_000)));
        assertFalse(e.accepted());
    }

    @Test
    void inferredSalesLowerConfidence() {
        MarketAnalyzer analyzer = new MarketAnalyzer(MarketAnalyzer.Config.defaults());
        ManipulationDetector m = new ManipulationDetector();
        MarketAnalyzer.Analysis real = analyzer.analyze(ITEM, StackBucket.X16, sales(40, 90_000, false), NOW);
        MarketAnalyzer.Analysis inf = analyzer.analyze(ITEM, StackBucket.X16, sales(40, 90_000, true), NOW);
        assertEquals(1.0, inf.stats().inferredShare(), 1e-9);
        assertTrue(m.assess(inf.stats(), inf.keptSales()).penalty() < m.assess(real.stats(), real.keptSales()).penalty());
    }

    @Test
    void unreadableSellersAreNotTreatedAsOneWashTrader() {
        List<Sale> s = new ArrayList<>();
        for (int i = 0; i < 40; i++) s.add(new Sale("u" + i, NOW - i * 3 * MIN, "?", ITEM, 16, 90_000 + (i % 5) * 1000, false));
        MarketAnalyzer.Analysis a = new MarketAnalyzer(MarketAnalyzer.Config.defaults()).analyze(ITEM, StackBucket.X16, s, NOW);
        ManipulationDetector.Assessment m = new ManipulationDetector().assess(a.stats(), a.keptSales());
        assertEquals(1.0, m.penalty(), 1e-9, () -> m.warnings().toString());
        assertEquals(4, a.stats().uniqueSellers());
    }

    @Test
    void askOnlyFallbackNeedsEnoughAsksAndIsLowConfidence() {
        Listing candidate = listing("c", 40_000);
        List<Listing> few = List.of(listing("a", 90_000), listing("b", 95_000));
        assertFalse(detector().evaluateAgainstAsks(candidate, few, NOW).accepted());

        List<Listing> many = new ArrayList<>();
        for (int i = 0; i < 10; i++) many.add(listing("k" + i, 90_000 + i * 1000));
        OpportunityDetector.Evaluation e = detector().evaluateAgainstAsks(candidate, many, NOW);
        assertTrue(e.accepted(), () -> e.rejections().toString());
        assertEquals(Basis.ASKS, e.opportunity().get().basis());
        assertTrue(e.opportunity().get().confidence() <= 0.35);
        assertEquals(89_999, e.opportunity().get().sellPrice());
    }

    @Test
    void vanishedCheapListingsBecomeInferredSales() {
        SaleInference inf = new SaleInference(SaleInference.Config.defaults());
        List<Listing> before = List.of(listing("a", 90_000), listing("b", 92_000), listing("c", 400_000), listing("d", 95_000));
        List<Listing> after = List.of(listing("b", 92_000), listing("d", 95_000));
        List<Sale> got = inf.infer(new SaleInference.Scan("q", NOW, before), new SaleInference.Scan("q", NOW + 5 * MIN, after));
        // "a" sold; "c" was far over the floor (more likely cancelled) so it's ignored.
        assertEquals(1, got.size());
        assertEquals(90_000, got.get(0).totalPrice());
        assertTrue(got.get(0).inferred());
        assertEquals(NOW + 150_000, got.get(0).soldAt());
    }

    @Test
    void ownVanishedListingIsConfirmedFillEvenAboveFloor() {
        SaleInference inf = new SaleInference(SaleInference.Config.defaults());
        // "mine" is priced way above floor - as a stranger's listing it would be ignored
        // (probably cancelled), but as OUR listing it's a confirmed fill at full weight.
        Listing mine = new Listing("mine", NOW, "Me_Player", ITEM, 16, 400_000);
        List<Listing> before = List.of(listing("a", 90_000), mine, listing("d", 95_000));
        List<Listing> after = List.of(listing("a", 90_000), listing("d", 95_000));
        List<Sale> got = inf.infer(new SaleInference.Scan("q", NOW, before),
            new SaleInference.Scan("q", NOW + 5 * MIN, after), "me_player");
        assertEquals(1, got.size());
        assertEquals(400_000, got.get(0).totalPrice());
        assertFalse(got.get(0).inferred(), "own fill is ground truth, not an inference");
        assertTrue(got.get(0).saleKey().startsWith("fill:"));
    }

    @Test
    void ownSaleChatLinesParse() {
        OwnSaleParser.OwnSale s1 = OwnSaleParser.parse("PlayerX bought your 64x Diamond Block for $1,200,000");
        assertNotNull(s1);
        assertEquals("PlayerX", s1.buyer());
        assertEquals(64, s1.count());
        assertEquals(1_200_000, s1.totalPrice());
        assertEquals("diamond_block", OwnSaleParser.itemKeyFromDisplayName(s1.itemDisplayName()));

        OwnSaleParser.OwnSale s2 = OwnSaleParser.parse("\u00a7aSomeGuy purchased your Enchanted Golden Apple for $500k\u00a7r");
        assertNotNull(s2);
        assertEquals(1, s2.count());
        assertEquals(500_000, s2.totalPrice());
        assertEquals("enchanted_golden_apple", OwnSaleParser.itemKeyFromDisplayName(s2.itemDisplayName()));

        assertNull(OwnSaleParser.parse("PlayerX bought 64x Diamond Block for $100"), "not OUR sale - no 'your'");
        assertNull(OwnSaleParser.parse("You paid Bob $500"), "unrelated money line");
        assertNull(OwnSaleParser.parse(null));
    }

    @Test
    void untrustworthyScanPairsInferNothing() {
        SaleInference inf = new SaleInference(SaleInference.Config.defaults());
        List<Listing> before = List.of(listing("a", 90_000), listing("b", 91_000), listing("c", 92_000));
        SaleInference.Scan first = new SaleInference.Scan("q", NOW, before);
        assertTrue(inf.infer(first, new SaleInference.Scan("q", NOW + 60 * MIN, List.of())).isEmpty(), "gap too long");
        assertTrue(inf.infer(first, new SaleInference.Scan("other", NOW + MIN, List.of())).isEmpty(), "different search");
        assertTrue(inf.infer(first, new SaleInference.Scan("q", NOW + MIN, List.of())).isEmpty(), "book wiped = broken scan");
    }

    @Test
    void paperFlipSettlesOnlyOnLaterSaleAtTarget() {
        List<Sale> s = sales(47, 94_000, false);
        MarketAnalyzer.Analysis a = new MarketAnalyzer(MarketAnalyzer.Config.defaults()).analyze(ITEM, StackBucket.X16, s, NOW);
        Listing candidate = listing("c", 40_000);
        var opp = detector().evaluate(candidate, a.stats(), a.keptSales(), List.of()).opportunity().orElseThrow();

        PaperLedger ledger = new PaperLedger(OpportunityDetector.FeeConfig.zero(), 24 * 60 * MIN, 8);
        assertTrue(ledger.open(opp, NOW));
        assertFalse(ledger.open(opp, NOW), "same listing twice");
        ledger.settle(List.of(new Sale("old", NOW - MIN, "x", ITEM, 16, 999_999, false)), NOW + MIN);
        assertEquals(1, ledger.summary().open(), "sale before open must not count");
        ledger.settle(List.of(new Sale("new", NOW + 2 * MIN, "x", ITEM, 16, opp.sellPrice(), false)), NOW + 3 * MIN);
        assertEquals(1, ledger.summary().sold());
        assertTrue(ledger.summary().realizedProfit() > 0);
    }

    @Test
    void expiredLosersGetBenched() {
        PaperLedger ledger = new PaperLedger(OpportunityDetector.FeeConfig.zero(), MIN, 8);
        List<Listing> many = new ArrayList<>();
        for (int i = 0; i < 10; i++) many.add(listing("k" + i, 90_000 + i * 1000));
        var opp = detector().evaluateAgainstAsks(listing("c", 40_000), many, NOW).opportunity().orElseThrow();
        ledger.open(opp, NOW);
        ledger.settle(List.of(), NOW + 2 * MIN);
        assertEquals(1, ledger.summary().expired());
        assertTrue(ledger.losingMarkets(1, 60 * MIN, NOW + 3 * MIN).contains(ITEM));
    }
}
