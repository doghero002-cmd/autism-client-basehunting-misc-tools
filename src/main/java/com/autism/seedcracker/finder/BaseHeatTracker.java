package com.autism.seedcracker.finder;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base-discovery heat tracker for the Region Map (local + optional global).
 *
 * Local: finder modules call {@link #recordFind(int, int, int)} with the world coords of each
 * confirmed base they flag; finds are bucketed into the 9x9 region-cell grid and rendered as a
 * colour-coded heat overlay on the Region Map.
 *
 * Global: if a heatmap URL is configured (e.g. a GitHub raw JSON file shared by all users),
 * {@link #refreshGlobal(String, int)} downloads it on a background thread and merges the shared
 * counts into the heat overlay. Expected JSON shape (legacy flat cell->count maps also work):
 *   { "cells": { "12": 5, "34": 17, ... }, "cellBlocks": 50000 }
 * The "cells" keys are the same 1-based region-cell indices the Region Map uses.
 */
public final class BaseHeatTracker {

    private static final int GRID = 9;
    private static final int MAX_GLOBAL_BYTES = 65_536;
    private static final HttpClient HTTP = com.autism.seedcracker.util.Http.CLIENT;
    private static final Logger LOG = LoggerFactory.getLogger(BaseHeatTracker.class);

    // ponytail: one monitor keeps 81 cells and their normalization consistent; use snapshots if the grid grows.
    private static final Map<Integer, Integer> LOCAL = new HashMap<>();
    private static Map<Integer, Integer> global = Map.of();
    private static long maxHeat = 1;
    private static long lastFetchMs;
    private static long fetchGeneration;
    private static String sourceUrl = "";
    private static int sourceCellBlocks;
    private static CompletableFuture<HttpResponse<String>> fetch;

    private BaseHeatTracker() {}

    /** A base find awaiting manual confirmation before it joins the heatmap. */
    public record Pending(int blockX, int blockZ, int cellBlocks) {}
    private static final java.util.List<Pending> PENDING = new java.util.ArrayList<>();

    /** Record a local base find at the given world coords (immediate, auto mode). */
    public static synchronized void recordFind(int blockX, int blockZ, int cellBlocks) {
        int cell = cellIndexOf(blockX, blockZ, GRID, cellBlocks);
        LOCAL.put(cell, (int) Math.min(Integer.MAX_VALUE, (long) LOCAL.getOrDefault(cell, 0) + 1));
        recomputeMaxHeat();
    }

    /** Negative heat marks natural-structure cells as cold. */
    public static synchronized void recordNatural(int blockX, int blockZ, int cellBlocks) {
        int cell = cellIndexOf(blockX, blockZ, GRID, cellBlocks);
        LOCAL.put(cell, (int) Math.max(Integer.MIN_VALUE, (long) LOCAL.getOrDefault(cell, 0) - 1000));
        recomputeMaxHeat();
    }

    public static synchronized boolean isNatural(int blockX, int blockZ, int cellBlocks) {
        return total(cellIndexOf(blockX, blockZ, GRID, cellBlocks)) < 0;
    }

    /** Queue a find for manual confirmation (manual mode): held until confirmPending(). */
    public static synchronized void queueFind(int blockX, int blockZ, int cellBlocks) {
        PENDING.add(new Pending(blockX, blockZ, cellBlocks));
    }

    public static synchronized int pendingCount() { return PENDING.size(); }

    /** Move every pending find into the heatmap without losing concurrently queued finds. */
    public static synchronized int confirmPending() {
        int count = PENDING.size();
        for (Pending pending : PENDING) recordFind(pending.blockX(), pending.blockZ(), pending.cellBlocks());
        PENDING.clear();
        return count;
    }

    public static synchronized void discardPending() { PENDING.clear(); }

    /** Heat (0.0 - 1.0), normalized against the hottest combined local/global cell. */
    public static synchronized float heat(int cell) {
        return Math.max(0f, Math.min(1f, total(cell) / (float) maxHeat));
    }

    public static synchronized int finds(int cell) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, total(cell)));
    }

    public static synchronized void clear() {
        cancelFetch();
        sourceUrl = "";
        sourceCellBlocks = 0;
        LOCAL.clear();
        global = Map.of();
        PENDING.clear();
        maxHeat = 1;
    }

    private static long total(int cell) {
        return (long) LOCAL.getOrDefault(cell, 0) + global.getOrDefault(cell, 0);
    }

    private static void recomputeMaxHeat() {
        maxHeat = 1;
        for (int cell = 1; cell <= GRID * GRID; cell++) maxHeat = Math.max(maxHeat, total(cell));
    }

    private static void cancelFetch() {
        fetchGeneration++;
        var previous = fetch;
        fetch = null;
        lastFetchMs = 0;
        if (previous != null) previous.cancel(true);
    }

    /** Fetch at most once a minute; source changes cancel old requests and clear only global heat. */
    public static synchronized void refreshGlobal(String url, int cellBlocks) {
        String normalized = url == null ? "" : url.trim();
        if (!normalized.equals(sourceUrl) || cellBlocks != sourceCellBlocks) {
            cancelFetch();
            sourceUrl = normalized;
            sourceCellBlocks = cellBlocks;
            global = Map.of();
            recomputeMaxHeat();
        }
        if (normalized.isEmpty() || cellBlocks <= 0) return;
        long now = System.currentTimeMillis();
        if (fetch != null || lastFetchMs != 0 && now >= lastFetchMs && now - lastFetchMs < 60_000) return;
        lastFetchMs = now;
        final HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(normalized)).GET().timeout(Duration.ofSeconds(10)).build();
        } catch (IllegalArgumentException invalidUrl) {
            LOG.warn("[Heatmap] Global heatmap URL must be a valid HTTP(S) URL.");
            return;
        }
        long generation = ++fetchGeneration;
        try {
            fetch = HTTP.sendAsync(request, HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofString(), MAX_GLOBAL_BYTES));
        } catch (RuntimeException failure) {
            LOG.warn("[Heatmap] Could not start global refresh: {}", failure.getClass().getSimpleName());
            return;
        }
        fetch.whenComplete((response, failure) -> completeFetch(generation, cellBlocks, response, failure));
    }

    private static synchronized void completeFetch(long generation, int cellBlocks, HttpResponse<String> response, Throwable failure) {
        if (generation != fetchGeneration) return;
        fetch = null;
        if (failure != null) {
            LOG.warn("[Heatmap] Global refresh failed: {}", failure.getClass().getSimpleName());
        } else if (response.statusCode() / 100 != 2) {
            LOG.warn("[Heatmap] Global refresh returned HTTP {}.", response.statusCode());
        } else if (!parseGlobal(response.body(), cellBlocks)) {
            LOG.warn("[Heatmap] Ignoring invalid global heatmap or mismatched cellBlocks; retaining the last valid map.");
        }
    }

    /** Validate the entire bounded document before replacing the last good snapshot. */
    private static synchronized boolean parseGlobal(String json, int cellBlocks) {
        if (json == null || json.length() > MAX_GLOBAL_BYTES || cellBlocks <= 0) return false;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) return false;
            JsonObject root = parsed.getAsJsonObject();
            if (root.has("cellBlocks") && positiveInt(root.get("cellBlocks")) != cellBlocks) return false;
            JsonElement data = root.has("cells") ? root.get("cells") : root;
            if (!data.isJsonObject()) return false;
            Map<Integer, Integer> next = new HashMap<>();
            for (var entry : data.getAsJsonObject().entrySet()) {
                if (!root.has("cells") && entry.getKey().equals("cellBlocks")) continue;
                int cell = Integer.parseInt(entry.getKey());
                if (cell < 1 || cell > GRID * GRID) return false;
                JsonElement value = entry.getValue();
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return false;
                int count = value.getAsBigDecimal().intValueExact();
                if (count < 0) return false;
                next.put(cell, count);
            }
            global = Map.copyOf(next);
            recomputeMaxHeat();
            return true;
        } catch (JsonParseException | ArithmeticException | IllegalArgumentException invalidData) {
            return false;
        }
    }

    private static int positiveInt(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return -1;
        int number = value.getAsBigDecimal().intValueExact();
        return number > 0 ? number : -1;
    }

    /** CodeEngine cell-index math (same as RegionMapHud.cellIndexOf). */
    private static int cellIndexOf(int blockX, int blockZ, int grid, int cellBlocks) {
        if (grid < 1) grid = 1;
        if (cellBlocks < 1) cellBlocks = 1;
        long half = (long) grid * cellBlocks / 2L;
        int cx = (int) Math.floorDiv(blockX + half, (long) cellBlocks);
        int cz = (int) Math.floorDiv(blockZ + half, (long) cellBlocks);
        if (cx < 0) cx = 0; else if (cx >= grid) cx = grid - 1;
        if (cz < 0) cz = 0; else if (cz >= grid) cz = grid - 1;
        return cz * grid + cx + 1;
    }
}
