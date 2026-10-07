package com.autism.seedcracker.flip.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.autism.seedcracker.flip.core.FlipModel.Listing;
import com.autism.seedcracker.flip.core.FlipModel.Sale;

/**
 * API-key-less sale detection. Without the transactions endpoint, the only evidence of a sale is
 * a listing that was on the book in one COMPLETE scan of an item and gone in the next. That also
 * catches cancellations and expiries, so this is deliberately conservative:
 * <ul>
 *   <li>both scans must be complete (every page read) for the same search,</li>
 *   <li>the gap between scans must be short (a long gap lets listings expire),</li>
 *   <li>listings priced far above the cheapest ask are ignored (overpriced asks get cancelled, not bought),</li>
 *   <li>a scan that loses most of its book is treated as a broken scan, not a buying spree.</li>
 * </ul>
 * Inferred sales are tagged so the analyzer and manipulation check can discount them.
 */
public final class SaleInference {

    public record Config(long maxGapMillis, double maxAboveFloor, double maxVanishShare) {
        public static Config defaults() {
            return new Config(20 * 60_000L, 1.5, 0.6);
        }
    }

    /** One complete pass over every page of a search. */
    public record Scan(String searchKey, long completedAt, List<Listing> listings) {
        public Scan {
            listings = List.copyOf(listings);
        }
    }

    private final Config config;

    public SaleInference(Config config) {
        this.config = config;
    }

    /** Sales inferred from {@code previous} -> {@code current}; empty when the pair isn't trustworthy. */
    public List<Sale> infer(Scan previous, Scan current) {
        if (previous == null || current == null || !previous.searchKey().equals(current.searchKey())) return List.of();
        long gap = current.completedAt() - previous.completedAt();
        if (gap <= 0 || gap > config.maxGapMillis()) return List.of();
        if (previous.listings().isEmpty()) return List.of();

        Map<String, Listing> now = new HashMap<>();
        for (Listing l : current.listings()) now.put(l.listingKey(), l);

        Map<String, Double> floorByMarket = new HashMap<>();
        for (Listing l : previous.listings()) {
            if (!l.isValid()) continue;
            floorByMarket.merge(marketKey(l), l.unitPrice(), Math::min);
        }

        List<Listing> vanished = new ArrayList<>();
        for (Listing l : previous.listings()) {
            if (l.isValid() && !now.containsKey(l.listingKey())) vanished.add(l);
        }
        if ((double) vanished.size() / previous.listings().size() > config.maxVanishShare()) return List.of();

        // Midpoint is the best unbiased guess for when it sold inside the gap.
        long soldAt = previous.completedAt() + gap / 2;
        List<Sale> out = new ArrayList<>();
        for (Listing l : vanished) {
            Double floor = floorByMarket.get(marketKey(l));
            if (floor == null || l.unitPrice() > floor * config.maxAboveFloor()) continue;
            out.add(new Sale("inf:" + l.listingKey(), soldAt, l.seller(), l.itemKey(), l.count(), l.totalPrice(), true));
        }
        return out;
    }

    private static String marketKey(Listing l) {
        return l.itemKey() + "|" + l.bucket();
    }

    /** Stable identity for a GUI listing that has no server id: seller + item + count + price. */
    public static String listingKey(String seller, String itemKey, int count, long totalPrice) {
        return (seller == null ? "?" : seller.toLowerCase(java.util.Locale.ROOT)) + "|" + itemKey + "|" + count + "|" + totalPrice;
    }
}
