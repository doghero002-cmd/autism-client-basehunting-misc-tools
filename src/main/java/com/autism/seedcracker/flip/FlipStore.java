package com.autism.seedcracker.flip;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.autism.seedcracker.flip.core.FlipModel.Listing;
import com.autism.seedcracker.flip.core.FlipModel.Sale;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/**
 * Persistent market memory for the flipper: completed (or inferred) sales per item, plus the
 * latest active book per item. Sales survive restarts in flip-market.json so the valuation keeps
 * sharpening across sessions; the book is in-memory only (asks go stale in minutes).
 */
public final class FlipStore {

    private static final int MAX_SALES_PER_ITEM = 800;
    private static final long BOOK_TTL_MS = 30 * 60_000L;
    private static final long SAVE_DEBOUNCE_MS = 20_000L;
    private static final Gson GSON = new GsonBuilder().create();

    private final Map<String, List<Sale>> sales = new HashMap<>();
    private final Map<String, Map<String, Listing>> book = new HashMap<>();
    private final Path file;
    private long lastSaveMs;
    private boolean dirty;

    public FlipStore(Path file) {
        this.file = file;
    }

    public synchronized void load() {
        try {
            if (!Files.exists(file)) return;
            Map<String, List<Sale>> data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                new TypeToken<Map<String, List<Sale>>>() {}.getType());
            if (data == null) return;
            for (var e : data.entrySet()) {
                List<Sale> kept = new ArrayList<>();
                for (Sale s : e.getValue()) if (s != null && s.isValid()) kept.add(s);
                sales.put(e.getKey(), kept);
            }
        } catch (Throwable t) {
            try {
                Files.move(file, file.resolveSibling("flip-market.corrupt-" + System.currentTimeMillis() + ".json"));
            } catch (Throwable ignored) {}
        }
    }

    public synchronized void save(boolean force) {
        long now = System.currentTimeMillis();
        if (!dirty || (!force && now - lastSaveMs < SAVE_DEBOUNCE_MS)) return;
        lastSaveMs = now;
        dirty = false;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(sales), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable ignored) {}
    }

    /** Adds sales, ignoring ones already known by key. Returns how many were new. */
    public synchronized int addSales(List<Sale> incoming) {
        int added = 0;
        Map<String, java.util.Set<String>> keysByItem = new HashMap<>();
        for (Sale s : incoming) {
            if (!s.isValid()) continue;
            List<Sale> list = sales.computeIfAbsent(s.itemKey(), k -> new ArrayList<>());
            java.util.Set<String> keys = keysByItem.computeIfAbsent(s.itemKey(), k -> {
                java.util.Set<String> set = new java.util.HashSet<>();
                for (Sale x : list) set.add(x.saleKey());
                return set;
            });
            if (!keys.add(s.saleKey())) continue;
            list.add(s);
            if (list.size() > MAX_SALES_PER_ITEM) list.subList(0, list.size() - MAX_SALES_PER_ITEM).clear();
            added++;
        }
        if (added > 0) dirty = true;
        return added;
    }

    /** Replaces the whole known book for one item (after a complete scan of it). */
    public synchronized void replaceBook(String itemKey, List<Listing> listings) {
        Map<String, Listing> m = new LinkedHashMap<>();
        for (Listing l : listings) m.put(l.listingKey(), l);
        book.put(itemKey, m);
    }

    /** Merges listings into the book without dropping unseen ones (partial page / API page). */
    public synchronized void mergeBook(List<Listing> listings) {
        for (Listing l : listings) book.computeIfAbsent(l.itemKey(), k -> new LinkedHashMap<>()).put(l.listingKey(), l);
    }

    public synchronized void removeListing(String itemKey, String listingKey) {
        Map<String, Listing> m = book.get(itemKey);
        if (m != null) m.remove(listingKey);
    }

    public synchronized List<Sale> sales(String itemKey) {
        List<Sale> l = sales.get(itemKey);
        return l == null ? List.of() : List.copyOf(l);
    }

    public synchronized List<Sale> allSalesSince(long since) {
        List<Sale> out = new ArrayList<>();
        for (List<Sale> l : sales.values()) for (Sale s : l) if (s.soldAt() > since) out.add(s);
        return out;
    }

    public synchronized List<Listing> book(String itemKey, long now) {
        Map<String, Listing> m = book.get(itemKey);
        if (m == null) return List.of();
        m.values().removeIf(l -> now - l.observedAt() > BOOK_TTL_MS);
        return List.copyOf(m.values());
    }

    public synchronized List<String> bookItems() {
        return List.copyOf(book.keySet());
    }

    public synchronized int saleCount() {
        int n = 0;
        for (List<Sale> l : sales.values()) n += l.size();
        return n;
    }

    public synchronized int marketCount() {
        return sales.size();
    }
}
