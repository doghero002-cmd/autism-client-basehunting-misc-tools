package com.autism.seedcracker.finder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Base-discovery heat tracker for the Region Map (local + optional global).
 *
 * Local: finder modules call {@link #recordFind(int, int, int)} with the world coords of each
 * confirmed base they flag; finds are bucketed into the 9x9 region-cell grid and rendered as a
 * colour-coded heat overlay on the Region Map.
 *
 * Global: if a heatmap URL is configured (e.g. a GitHub raw JSON file shared by all users),
 * {@link #refreshGlobal(String, int)} downloads it on a background thread and merges the shared
 * counts into the heat overlay. Expected JSON shape (very forgiving - any cell->count map):
 *   { "cells": { "12": 5, "34": 17, ... }, "cellBlocks": 50000 }
 * The "cells" keys are the same 1-based region-cell indices the Region Map uses.
 */
public final class BaseHeatTracker {

    private static final int GRID = 9;
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    /** cellIndex (1-based, row-major) -> local find count. */
    private static final Map<Integer, Integer> LOCAL = new ConcurrentHashMap<>();
    /** cellIndex -> global (fetched) find count. */
    private static final Map<Integer, Integer> GLOBAL = new ConcurrentHashMap<>();
    private static volatile int maxHeat = 1;
    private static volatile long lastFetchMs = 0;
    private static volatile boolean fetching = false;

    private BaseHeatTracker() {}

    /** A base find awaiting manual confirmation before it joins the heatmap. */
    public record Pending(int blockX, int blockZ, int cellBlocks) {}
    private static final java.util.List<Pending> PENDING = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Record a local base find at the given world coords (immediate, auto mode). */
    public static void recordFind(int blockX, int blockZ, int cellBlocks) {
        int cell = cellIndexOf(blockX, blockZ, GRID, cellBlocks);
        int v = LOCAL.merge(cell, 1, Integer::sum);
        if (v > maxHeat) maxHeat = v;
    }

    /** Record a NATURAL structure (village / dungeon / etc.) at the given coords. Negative heat
     *  marks the cell as natural so the heatmap renders it cold and base-finders skip it. */
    public static void recordNatural(int blockX, int blockZ, int cellBlocks) {
        int cell = cellIndexOf(blockX, blockZ, GRID, cellBlocks);
        LOCAL.merge(cell, -1000, Integer::sum); // strongly negative -> always cold
    }

    /** True if a cell is marked as a natural structure (should be skipped by base-finders). */
    public static boolean isNatural(int blockX, int blockZ, int cellBlocks) {
        int cell = cellIndexOf(blockX, blockZ, GRID, cellBlocks);
        return LOCAL.getOrDefault(cell, 0) + GLOBAL.getOrDefault(cell, 0) < 0;
    }

    /** Queue a find for manual confirmation (manual mode): held until confirmPending(). */
    public static void queueFind(int blockX, int blockZ, int cellBlocks) {
        PENDING.add(new Pending(blockX, blockZ, cellBlocks));
    }

    /** Number of finds waiting for manual confirmation. */
    public static int pendingCount() {
        return PENDING.size();
    }

    /** Move every pending find into the heatmap. Returns how many were added. */
    public static int confirmPending() {
        int n = 0;
        for (Pending p : PENDING) {
            recordFind(p.blockX(), p.blockZ(), p.cellBlocks());
            n++;
        }
        PENDING.clear();
        return n;
    }

    /** Discard all pending finds without adding them. */
    public static void discardPending() {
        PENDING.clear();
    }

    /** Heat (0.0 - 1.0) for a cell, normalized against the hottest cell (local + global). */
    public static float heat(int cell) {
        int v = LOCAL.getOrDefault(cell, 0) + GLOBAL.getOrDefault(cell, 0);
        if (v <= 0 || maxHeat <= 0) return 0f; // natural / cold cells render no heat
        return Math.min(1f, v / (float) maxHeat);
    }

    public static int finds(int cell) {
        return Math.max(0, LOCAL.getOrDefault(cell, 0) + GLOBAL.getOrDefault(cell, 0));
    }

    public static void clear() {
        LOCAL.clear();
        GLOBAL.clear();
        maxHeat = 1;
    }

    /**
     * Fetch the shared heatmap JSON from a URL on a background thread and merge it. Safe to call
     * every render - it throttles itself and never blocks the game thread. Recomputes max heat.
     */
    public static void refreshGlobal(String url, int cellBlocks) {
        if (url == null || url.isBlank()) return;
        long now = System.currentTimeMillis();
        if (fetching || now - lastFetchMs < 60_000) return; // at most once a minute
        fetching = true;
        lastFetchMs = now;
        HTTP.sendAsync(HttpRequest.newBuilder(URI.create(url.trim())).GET()
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString())
            .thenAccept(resp -> {
                if (resp.statusCode() / 100 != 2) return;
                parseGlobal(resp.body(), cellBlocks);
            })
            .exceptionally(t -> null)
            .whenComplete((r, t) -> fetching = false);
    }

    /** Very forgiving JSON parse: pulls every "cell":count pair out of a "cells" object. */
    private static void parseGlobal(String json, int cellBlocks) {
        if (json == null) return;
        GLOBAL.clear();
        // Match "123": 45  (cell index as a quoted key -> numeric count)
        Pattern p = Pattern.compile("\"(\\d{1,3})\"\\s*:\\s*(\\d+)");
        Matcher m = p.matcher(json);
        int max = 1;
        while (m.find()) {
            int cell = Integer.parseInt(m.group(1));
            int count = Integer.parseInt(m.group(2));
            if (cell >= 1 && cell <= GRID * GRID && count >= 0) {
                GLOBAL.put(cell, count);
                int total = count + LOCAL.getOrDefault(cell, 0);
                if (total > max) max = total;
            }
        }
        for (Map.Entry<Integer, Integer> e : LOCAL.entrySet()) {
            int total = e.getValue() + GLOBAL.getOrDefault(e.getKey(), 0);
            if (total > max) max = total;
        }
        maxHeat = max;
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
