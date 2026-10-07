package com.autism.seedcracker.market;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/**
 * Per-item rolling price history for the DonutSMP auction house. Samples come from passively
 * reading listings while you browse /ah (and optionally the DonutSMP API poller). Persisted as
 * one JSON file so knowledge builds across sessions. All stats are per-UNIT prices so stack
 * listings compare against singles honestly.
 */
public final class PriceTracker {

    /** One observed listing: unit price + when seen. */
    public record Sample(double unitPrice, long seenAtMs) {}

    /** Summary stats for an item over a window. */
    public record Stats(int samples, double min, double median, double mean, double max, double recentMedian) {}

    private static final int MAX_SAMPLES_PER_ITEM = 600;
    private static final Gson GSON = new GsonBuilder().create();

    private static final Map<String, List<Sample>> BY_ITEM = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;
    private static volatile long lastSaveMs = 0;
    private static final long SAVE_DEBOUNCE_MS = 15_000;

    private PriceTracker() {}

    private static Path file() {
        return autismclient.AutismClientAddon.FOLDER.toPath().resolve("price-history.json");
    }

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            String json = Files.readString(f, StandardCharsets.UTF_8);
            Map<String, List<Sample>> data = GSON.fromJson(json,
                new TypeToken<Map<String, List<Sample>>>() {}.getType());
            if (data != null) {
                for (Map.Entry<String, List<Sample>> e : data.entrySet()) {
                    if (e.getValue() != null) BY_ITEM.put(e.getKey(), Collections.synchronizedList(new ArrayList<>(e.getValue())));
                }
            }
        } catch (Throwable t) {
            // corrupt history is not fatal: start fresh, keep the broken file aside
            try {
                Files.move(file(), file().resolveSibling("price-history.corrupt-" + System.currentTimeMillis() + ".json"));
            } catch (Throwable ignored) {}
        }
    }

    /** Debounced save (call freely; writes at most every 15s). Pass force=true on shutdown. */
    public static void save(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastSaveMs < SAVE_DEBOUNCE_MS) return;
        lastSaveMs = now;
        try {
            Map<String, List<Sample>> copy = new java.util.HashMap<>();
            for (Map.Entry<String, List<Sample>> e : BY_ITEM.entrySet()) {
                synchronized (e.getValue()) {
                    copy.put(e.getKey(), new ArrayList<>(e.getValue()));
                }
            }
            Path f = file();
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling("price-history.json.tmp");
            Files.writeString(tmp, GSON.toJson(copy), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable ignored) {}
    }

    /** Record one observed listing (totalPrice for a stack of count items). */
    public static void record(String itemId, double totalPrice, int count) {
        if (itemId == null || itemId.isBlank() || totalPrice <= 0 || count <= 0) return;
        load();
        double unit = totalPrice / count;
        List<Sample> list = BY_ITEM.computeIfAbsent(normalize(itemId),
            k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            long now = System.currentTimeMillis();
            // Dedup rule lives in PriceStats (tested): same unit price within 60s = re-render.
            if (com.autism.seedcracker.util.pure.PriceStats.isDuplicateListing(
                    toPure(list), unit, now, 60_000, 30)) {
                return;
            }
            list.add(new Sample(unit, now));
            if (list.size() > MAX_SAMPLES_PER_ITEM) list.subList(0, list.size() - MAX_SAMPLES_PER_ITEM).clear();
        }
        save(false);
    }

    /** View of our samples as the pure type (same shape; records can't share a hierarchy). */
    private static List<com.autism.seedcracker.util.pure.PriceStats.Sample> toPure(List<Sample> list) {
        List<com.autism.seedcracker.util.pure.PriceStats.Sample> out = new ArrayList<>(list.size());
        for (Sample s : list) out.add(new com.autism.seedcracker.util.pure.PriceStats.Sample(s.unitPrice(), s.seenAtMs()));
        return out;
    }

    /** Stats over all samples; recentMedian = last 24h (falls back to all-time). Null if no data. */
    public static Stats stats(String itemId) {
        load();
        List<Sample> list = BY_ITEM.get(normalize(itemId));
        if (list == null || list.isEmpty()) return null;
        List<Sample> snap;
        synchronized (list) { snap = new ArrayList<>(list); }
        var s = com.autism.seedcracker.util.pure.PriceStats.summarize(
            toPure(snap), System.currentTimeMillis(), 24L * 3600_000);
        if (s == null) return null;
        return new Stats(s.samples(), s.min(), s.median(), s.mean(), s.max(), s.recentMedian());
    }

    /** Raw samples (oldest first) for charting. Empty list if unknown. */
    public static List<Sample> samples(String itemId) {
        load();
        List<Sample> list = BY_ITEM.get(normalize(itemId));
        if (list == null) return List.of();
        synchronized (list) { return new ArrayList<>(list); }
    }

    /** Item ids with data, sorted by sample count descending. */
    public static List<Map.Entry<String, Integer>> knownItems() {
        load();
        List<Map.Entry<String, Integer>> out = new ArrayList<>();
        for (Map.Entry<String, List<Sample>> e : BY_ITEM.entrySet()) {
            out.add(Map.entry(e.getKey(), e.getValue().size()));
        }
        out.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return out;
    }

    /**
     * Deal rating for a unit price vs history: fraction below recent median (0.55 = 45% under).
     * Negative result = above market. NaN when unknown item.
     */
    /** Hour-of-day price breakdown for "when are the best deals" (local time zone). */
    public static com.autism.seedcracker.util.pure.DealTiming.HourStat[] byHour(String itemId) {
        load();
        List<Sample> list = BY_ITEM.get(normalize(itemId));
        List<Sample> snap;
        if (list == null) snap = List.of();
        else synchronized (list) { snap = new ArrayList<>(list); }
        return com.autism.seedcracker.util.pure.DealTiming.byHour(toPure(snap), java.time.ZoneId.systemDefault());
    }

    public static double dealScore(String itemId, double unitPrice) {
        Stats s = stats(itemId);
        if (s == null) return Double.NaN;
        return com.autism.seedcracker.util.pure.PriceStats.dealScore(unitPrice, s.recentMedian());
    }

    public static String normalize(String itemId) {
        return com.autism.seedcracker.util.pure.PriceStats.normalizeItemId(itemId);
    }
}
