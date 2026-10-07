package com.autism.seedcracker.flip;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.autism.seedcracker.flip.core.FlipModel.Listing;
import com.autism.seedcracker.flip.core.FlipModel.Sale;
import com.autism.seedcracker.flip.core.SaleInference;
import com.example.donutflipscanner.api.DonutApiClient;
import com.example.donutflipscanner.api.FileApiCredentialsStore;
import com.example.donutflipscanner.api.model.ApiAuctionItem;
import com.example.donutflipscanner.api.model.ApiAuctionListing;
import com.example.donutflipscanner.api.model.ApiCompletedTransaction;
import com.example.donutflipscanner.api.model.ApiEnchantment;

/**
 * Optional DonutSMP API feed: real completed sales (the strongest valuation evidence) and the
 * recently-listed book. Rate limiting, key redaction and response validation come from the
 * bundled {@link DonutApiClient}; this only maps its models onto the flip core.
 */
public final class ApiFlipSource implements AutoCloseable {

    private static final int TRANSACTION_PAGES = 2;
    private static final int LISTING_PAGES = 2;

    private final FileApiCredentialsStore credentials;
    private final DonutApiClient client;
    private volatile boolean polling;
    private volatile String lastError = "";

    public ApiFlipSource(Path keyFile) {
        this.credentials = new FileApiCredentialsStore(keyFile);
        this.client = new DonutApiClient(credentials);
    }

    public boolean hasKey() {
        char[] k = credentials.copyApiKey().orElse(null);
        if (k == null) return false;
        java.util.Arrays.fill(k, '\0');
        return true;
    }

    public void saveKey(String key) {
        char[] chars = key.trim().toCharArray();
        try {
            credentials.setApiKey(chars);
        } finally {
            java.util.Arrays.fill(chars, '\0');
        }
    }

    public String lastError() {
        return lastError;
    }

    /** One poll cycle; callbacks run on the HTTP thread, so callers must hop to the client thread. */
    public void poll(Consumer<List<Sale>> onSales, Consumer<List<Listing>> onListings) {
        if (polling) return;
        polling = true;
        long now = System.currentTimeMillis();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int p = 1; p <= TRANSACTION_PAGES; p++) {
            int page = p;
            chain = chain.thenCompose(v -> client.fetchCompletedTransactions(page))
                .thenAccept(tp -> onSales.accept(toSales(tp.transactions())));
        }
        for (int p = 1; p <= LISTING_PAGES; p++) {
            int page = p;
            chain = chain.thenCompose(v -> client.fetchRecentlyListedAuctions(page))
                .thenAccept(lp -> onListings.accept(toListings(lp.listings(), now)));
        }
        chain.whenComplete((v, err) -> {
            lastError = err == null ? "" : rootMessage(err);
            polling = false;
        });
    }

    static List<Sale> toSales(List<ApiCompletedTransaction> txs) {
        List<Sale> out = new ArrayList<>();
        for (ApiCompletedTransaction t : txs) {
            if (t.item().isEmpty() || t.price().isEmpty() || t.soldAt().isEmpty()) continue;
            ApiAuctionItem item = t.item().get();
            String key = itemKey(item);
            int count = item.count().orElse(1);
            long price = toCoins(t.price().get());
            String seller = t.seller().flatMap(s -> s.name()).orElse("?").toLowerCase(Locale.ROOT);
            long soldAt = t.soldAt().get().toEpochMilli();
            if (key == null || price <= 0 || count <= 0) continue;
            out.add(new Sale("api:" + seller + "|" + key + "|" + count + "|" + price + "|" + soldAt,
                soldAt, seller, key, count, price, false));
        }
        return out;
    }

    static List<Listing> toListings(List<ApiAuctionListing> ls, long now) {
        List<Listing> out = new ArrayList<>();
        for (ApiAuctionListing l : ls) {
            if (l.item().isEmpty() || l.price().isEmpty()) continue;
            ApiAuctionItem item = l.item().get();
            String key = itemKey(item);
            int count = item.count().orElse(1);
            long price = toCoins(l.price().get());
            if (key == null || price <= 0 || count <= 0) continue;
            String seller = l.seller().flatMap(s -> s.name()).orElse("?").toLowerCase(Locale.ROOT);
            out.add(new Listing(SaleInference.listingKey(seller, key, count, price), now, seller, key, count, price));
        }
        return out;
    }

    /** Same shape as {@link GuiListingReader#itemKey}: path id plus sorted "+enchLevel" suffixes. */
    static String itemKey(ApiAuctionItem item) {
        if (item.id().isEmpty()) return null;
        String id = item.id().get().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(id.contains(":") ? id.substring(id.indexOf(':') + 1) : id);
        if (item.itemData().isPresent()) {
            TreeMap<String, Integer> sorted = new TreeMap<>();
            for (ApiEnchantment e : item.itemData().get().enchantments()) {
                String eid = e.id().toLowerCase(Locale.ROOT);
                sorted.put(eid.contains(":") ? eid.substring(eid.indexOf(':') + 1) : eid, e.level());
            }
            sorted.forEach((k, v) -> sb.append('+').append(k).append(v));
        }
        return sb.toString();
    }

    private static long toCoins(BigDecimal price) {
        try {
            return price.setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException e) {
            return -1;
        }
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    @Override
    public void close() {
        client.close();
    }
}
