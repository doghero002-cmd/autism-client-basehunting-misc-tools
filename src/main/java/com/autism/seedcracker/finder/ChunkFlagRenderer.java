package com.autism.seedcracker.finder;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.AutismRenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import autismclient.util.AutismWorldGeometry;

/**
 * Shared world renderer for the chunk-scanner finder modules.
 *
 * The six finder modules each keep their own {@code Set<ChunkPos>} of flagged chunks, but the
 * AUTISM {@code onRenderLevel(float)} hook does not hand a {@link PoseStack} to modules, so boxes
 * cannot be submitted from there. Instead, every module feeds its flagged chunks (plus a colour and
 * whether to draw a tracer) into this single collector via {@link #feed}. This class self-registers
 * one {@link LevelRenderEvents#COLLECT_SUBMITS} listener on {@link #init()} and draws, each frame:
 *
 * <ul>
 *   <li>a 1-block-tall outline box at each flagged chunk (at a display Y derived from the camera),
 *   <li>an optional translucent fill, and
 *   <li>an optional tracer line from the camera to the chunk centre.
 * </ul>
 *
 * Entries live for a short TTL (refreshed by {@code feed} every tick), so when a module is disabled
 * or un-flags a chunk its marker simply fades away without extra bookkeeping.
 */
public final class ChunkFlagRenderer {

    /** How long (ms) a fed entry stays visible without being refreshed. Modules feed every tick. */
    private static final long TTL_MS = 250L;

    /** Height of the drawn chunk marker box. */
    private static final double BOX_HEIGHT = 1.0;
    private static final float LINE_WIDTH = 2.0f;
    private static final double INFLATE = 0.02;

    private static final Map<Key, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static volatile boolean initialised = false;

    // ---- smart overlap (hotspot) mode ----
    /** Enable cross-module overlap condensation (owned by the Finder Overlay module). */
    private static volatile boolean smartOverlap = false;
    /** Distinct modules flagging within the cluster radius before the area counts as "flooded". */
    private static volatile int hotspotMinModules = 2;
    /** Chunks flagged (any module) within the cluster radius before the area counts as "flooded". */
    private static volatile int floodMinChunks = 9;
    /** Chebyshev chunk radius used to cluster flags into an area. */
    private static final int CLUSTER_RADIUS = 2;
    // Overlap-analysis cache (the analysis is O(n^2); never run it per frame).
    private static volatile OverlapResult cachedAnalysis = null;
    private static volatile long lastAnalysisMs = 0;
    private static volatile int lastAnalysisEntryCount = -1;

    private ChunkFlagRenderer() {
    }

    /** Configure smart-overlap condensation (called by the module owning the setting). */
    public static void configureSmartOverlap(boolean enabled, int minModules, int minChunks) {
        smartOverlap = enabled;
        hotspotMinModules = Math.max(2, minModules);
        floodMinChunks = Math.max(2, minChunks);
        cachedAnalysis = null; // re-analyze under the new thresholds
    }

    // ---- display Y override (Chunk Waypoints module) ----
    /** When set, markers draw at this fixed Y instead of following the camera. */
    private static volatile boolean fixedYEnabled = false;
    private static volatile int fixedY = 16;

    /** Fix the marker display Y (Chunk Waypoints module) or return to camera-following (enabled=false). */
    public static void configureDisplayY(boolean enabled, int y) {
        fixedYEnabled = enabled;
        fixedY = y;
    }

    /** Identity of a single module's marker stream, so modules don't clobber each other's flags. */
    private record Key(String moduleId, ChunkPos pos) {
    }

    private static final class Entry {
        int argb;
        boolean tracer;
        long lastFeedMs;
    }

    /** Registers the level-render collector. Idempotent; call once from the addon entrypoint. */
    public static void init() {
        if (initialised) return;
        initialised = true;
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            if (ENTRIES.isEmpty()) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null) return;

            CameraRenderState cameraState = context.levelState().cameraRenderState;
            Vec3 origin = cameraState.pos;
            PoseStack poseStack = context.poseStack();

            long now = System.currentTimeMillis();
            // Drop stale entries (module disabled / chunk un-flagged).
            ENTRIES.entrySet().removeIf(e -> now - e.getValue().lastFeedMs > TTL_MS);
            if (ENTRIES.isEmpty()) return;

            // Smart overlap: in flooded areas (many chunks / many modules agreeing) draw only the
            // most-overlapped chunks as HOTSPOTS instead of the whole flooded field.
            // The analysis is O(n^2) over flagged chunks, so it's CACHED and recomputed at most
            // every 400ms (or when the flag set changes) - never per frame.
            java.util.Set<ChunkPos> hotspots = java.util.Set.of();
            java.util.Set<ChunkPos> suppressed = java.util.Set.of();
            if (smartOverlap) {
                OverlapResult analysis = cachedAnalysis;
                if (analysis == null || now - lastAnalysisMs > 400 || ENTRIES.size() != lastAnalysisEntryCount) {
                    analysis = analyzeOverlap();
                    cachedAnalysis = analysis;
                    lastAnalysisMs = now;
                    lastAnalysisEntryCount = ENTRIES.size();
                }
                hotspots = analysis.hotspots();
                suppressed = analysis.suppressed();
            }

            // Beam extents follow the actual dimension height (nether/end are not -64..320).
            double beamMinY = mc.level.getMinY() - origin.y;
            double beamMaxY = mc.level.getMaxY() - origin.y;

            // Marker Y: fixed (Chunk Waypoints) or a band just under the camera (default).
            double displayY = fixedYEnabled ? fixedY : Math.floor(origin.y) - 1.0;

            // Accumulate geometry, then submit ONCE per render type: 4 submits per flagged
            // chunk per frame was the frame-time cost once dozens of chunks were flagged.
            java.util.List<java.util.function.BiConsumer<PoseStack.Pose, VertexConsumer>> fillOps = new java.util.ArrayList<>();
            java.util.List<java.util.function.BiConsumer<PoseStack.Pose, VertexConsumer>> lineOps = new java.util.ArrayList<>();

            // Hotspot markers once per chunk (not per module entry).
            for (ChunkPos hpos : hotspots) {
                double hMinX = hpos.getMinBlockX() - origin.x;
                double hMinZ = hpos.getMinBlockZ() - origin.z;
                double hMinY = displayY - origin.y;
                double hcx = hMinX + 8, hcz = hMinZ + 8;
                // Distinct look: hot orange, double-height box, pyramid spire + white-hot core.
                int hot = 0xFFFFA020;
                int hotFill = 0x30FFA020;
                AABB hbox = new AABB(hMinX, hMinY, hMinZ, hMinX + 16, hMinY + BOX_HEIGHT * 2, hMinZ + 16).inflate(INFLATE);
                fillOps.add((pose, buffer) -> fillBox(pose, buffer, hbox, hotFill));
                lineOps.add((pose, buffer) -> {
                    cornerBrackets(pose, buffer, hbox, hot);
                    double apexY = hbox.maxY + 48;
                    line(pose, buffer, hbox.minX, hbox.maxY, hbox.minZ, hcx, apexY, hcz, hot);
                    line(pose, buffer, hbox.maxX, hbox.maxY, hbox.minZ, hcx, apexY, hcz, hot);
                    line(pose, buffer, hbox.maxX, hbox.maxY, hbox.maxZ, hcx, apexY, hcz, hot);
                    line(pose, buffer, hbox.minX, hbox.maxY, hbox.maxZ, hcx, apexY, hcz, hot);
                    line(pose, buffer, hcx, beamMinY, hcz, hcx, beamMaxY, hcz, 0xCCFFFFFF);
                });
            }

            for (Map.Entry<Key, Entry> e : ENTRIES.entrySet()) {
                ChunkPos pos = e.getKey().pos();
                if (hotspots.contains(pos) || suppressed.contains(pos)) continue; // condensed
                Entry entry = e.getValue();
                int argb = entry.argb;

                double minX = pos.getMinBlockX() - origin.x;
                double minZ = pos.getMinBlockZ() - origin.z;
                double minY = displayY - origin.y;
                AABB box = new AABB(minX, minY, minZ, minX + 16, minY + BOX_HEIGHT, minZ + 16).inflate(INFLATE);

                int fillArgb = (argb & 0x00FFFFFF) | 0x2E000000; // translucent fill derived from line colour
                fillOps.add((pose, buffer) -> fillBox(pose, buffer, box, fillArgb));

                // Corner brackets + beacon column + optional tracer, all in the one line batch.
                double cx = pos.getMinBlockX() + 8 - origin.x;
                double cz = pos.getMinBlockZ() + 8 - origin.z;
                int beaconArgb = (argb & 0x00FFFFFF) | 0x66000000; // softer than the outline
                boolean tracer = entry.tracer;
                lineOps.add((pose, buffer) -> {
                    cornerBrackets(pose, buffer, box, argb);
                    line(pose, buffer, cx, beamMinY, cz, cx, beamMaxY, cz, beaconArgb);
                    if (tracer) {
                        AutismWorldGeometry.line(pose, buffer, 0, 0, 0,
                            cx, displayY + BOX_HEIGHT * 0.5 - origin.y, cz, argb, LINE_WIDTH);
                    }
                });
            }

            if (!fillOps.isEmpty()) {
                context.submitNodeCollector().submitCustomGeometry(poseStack,
                    AutismRenderTypes.storageEspFillSeeThrough(),
                    (pose, buffer) -> { for (var op : fillOps) op.accept(pose, buffer); });
            }
            if (!lineOps.isEmpty()) {
                context.submitNodeCollector().submitCustomGeometry(poseStack,
                    AutismRenderTypes.storageEspLinesSeeThrough(),
                    (pose, buffer) -> { for (var op : lineOps) op.accept(pose, buffer); });
            }
        });
    }

    /**
     * Feeds a module's currently-flagged chunks into the renderer. Call every tick while enabled.
     *
     * @param moduleId unique id of the calling module (keeps each module's markers independent)
     * @param flagged  the module's current set of flagged chunk positions
     * @param argb     line colour (alpha honoured for the line; fill is derived translucently)
     * @param tracer   whether to draw a tracer line from the camera to each chunk centre
     */
    public static void feed(String moduleId, Set<ChunkPos> flagged, int argb, boolean tracer) {
        if (moduleId == null || flagged == null) return;
        long now = System.currentTimeMillis();
        for (ChunkPos pos : flagged) {
            if (pos == null) continue;
            Entry entry = ENTRIES.computeIfAbsent(new Key(moduleId, pos), k -> new Entry());
            entry.argb = argb;
            entry.tracer = tracer;
            entry.lastFeedMs = now;
        }
    }

    /** Clears every marker owned by a module (called when the module is disabled). */
    public static void clear(String moduleId) {
        if (moduleId == null) return;
        ENTRIES.keySet().removeIf(k -> k.moduleId().equals(moduleId));
    }

    // ---- smart overlap analysis ----

    private record OverlapResult(java.util.Set<ChunkPos> hotspots, java.util.Set<ChunkPos> suppressed) {}

    /**
     * Cluster flagged chunks and, in "flooded" areas, pick only the most-overlapped chunks as
     * hotspots while suppressing the rest of the flood.
     *
     * A chunk's overlap score = number of DISTINCT modules flagging it. An area is flooded when,
     * within CLUSTER_RADIUS of a chunk, either enough distinct modules agree (hotspotMinModules)
     * or the sheer flag count passes floodMinChunks (one noisy module carpeting the region). In a
     * flooded neighbourhood only its local score maxima render (as hotspot beacons); the rest of
     * that neighbourhood is hidden.
     */
    private static OverlapResult analyzeOverlap() {
        // Per-chunk distinct-module count.
        Map<ChunkPos, java.util.Set<String>> byChunk = new java.util.HashMap<>();
        for (Key k : ENTRIES.keySet()) {
            byChunk.computeIfAbsent(k.pos(), p -> new java.util.HashSet<>()).add(k.moduleId());
        }
        if (byChunk.size() < floodMinChunks) return new OverlapResult(java.util.Set.of(), java.util.Set.of());

        // Precompute per-chunk flag density (flags within CLUSTER_RADIUS) for peak picking.
        Map<ChunkPos, Integer> density = new java.util.HashMap<>();
        for (ChunkPos pos : byChunk.keySet()) {
            int d = 0;
            for (ChunkPos np : byChunk.keySet()) {
                if (Math.abs(np.x() - pos.x()) <= CLUSTER_RADIUS && Math.abs(np.z() - pos.z()) <= CLUSTER_RADIUS) d++;
            }
            density.put(pos, d);
        }

        java.util.Set<ChunkPos> hotspots = new java.util.HashSet<>();
        java.util.Set<ChunkPos> suppressed = new java.util.HashSet<>();

        for (Map.Entry<ChunkPos, java.util.Set<String>> e : byChunk.entrySet()) {
            ChunkPos pos = e.getKey();
            int myScore = e.getValue().size();
            int myDensity = density.get(pos);
            long myKey = pos.pack();

            // Neighbourhood stats within the cluster radius.
            java.util.Set<String> neighbourModules = new java.util.HashSet<>();
            int bestScore = myScore;
            boolean densityPeak = true; // no neighbour is denser (ties broken by chunk key)
            for (Map.Entry<ChunkPos, java.util.Set<String>> n : byChunk.entrySet()) {
                ChunkPos np = n.getKey();
                if (Math.abs(np.x() - pos.x()) > CLUSTER_RADIUS || Math.abs(np.z() - pos.z()) > CLUSTER_RADIUS) continue;
                neighbourModules.addAll(n.getValue());
                if (n.getValue().size() > bestScore) bestScore = n.getValue().size();
                int nd = density.get(np);
                if (nd > myDensity || (nd == myDensity && np.pack() < myKey)) densityPeak = false;
            }

            boolean flooded = myDensity >= floodMinChunks || neighbourModules.size() >= hotspotMinModules;
            if (!flooded) continue; // sparse area: draw normally

            if (bestScore >= 2) {
                // Cross-module area: only the top-score chunks are hotspots (real agreement).
                if (myScore >= bestScore) hotspots.add(pos);
                else suppressed.add(pos);
            } else {
                // Single-module flood (kelp forest, bamboo jungle): all scores tie at 1, so pick
                // DENSITY peaks with a deterministic tie-break - approx one hotspot per cluster,
                // the rest of the field is hidden.
                if (densityPeak) hotspots.add(pos);
                else suppressed.add(pos);
            }
        }
        return new OverlapResult(hotspots, suppressed);
    }



    // ---- geometry helpers (mirror AutismFreecamHighlightRenderer's box drawing) ----

    private static void outlineBox(PoseStack.Pose pose, VertexConsumer buffer, AABB box, int color) {
        double x1 = box.minX, y1 = box.minY, z1 = box.minZ;
        double x2 = box.maxX, y2 = box.maxY, z2 = box.maxZ;
        line(pose, buffer, x1, y1, z1, x2, y1, z1, color);
        line(pose, buffer, x2, y1, z1, x2, y1, z2, color);
        line(pose, buffer, x2, y1, z2, x1, y1, z2, color);
        line(pose, buffer, x1, y1, z2, x1, y1, z1, color);
        line(pose, buffer, x1, y2, z1, x2, y2, z1, color);
        line(pose, buffer, x2, y2, z1, x2, y2, z2, color);
        line(pose, buffer, x2, y2, z2, x1, y2, z2, color);
        line(pose, buffer, x1, y2, z2, x1, y2, z1, color);
        line(pose, buffer, x1, y1, z1, x1, y2, z1, color);
        line(pose, buffer, x2, y1, z1, x2, y2, z1, color);
        line(pose, buffer, x2, y1, z2, x2, y2, z2, color);
        line(pose, buffer, x1, y1, z2, x1, y2, z2, color);
    }

    private static void line(PoseStack.Pose pose, VertexConsumer buffer, double x1, double y1, double z1,
                             double x2, double y2, double z2, int color) {
        AutismWorldGeometry.line(pose, buffer, x1, y1, z1, x2, y2, z2, color, LINE_WIDTH);
    }

    /**
     * Draws "target bracket" corners around a box (only the corner edges, not the full outline).
     * Reads much cleaner than a full wireframe when several chunks are flagged at once.
     */
    private static void cornerBrackets(PoseStack.Pose pose, VertexConsumer buffer, AABB box, int color) {
        double x1 = box.minX, y1 = box.minY, z1 = box.minZ;
        double x2 = box.maxX, y2 = box.maxY, z2 = box.maxZ;
        double arm = Math.min(4.0, (x2 - x1) * 0.3); // bracket arm length
        // Bottom four corners (two arms each: along X and along Z).
        line(pose, buffer, x1, y1, z1, x1 + arm, y1, z1, color);
        line(pose, buffer, x1, y1, z1, x1, y1, z1 + arm, color);
        line(pose, buffer, x2, y1, z1, x2 - arm, y1, z1, color);
        line(pose, buffer, x2, y1, z1, x2, y1, z1 + arm, color);
        line(pose, buffer, x2, y1, z2, x2 - arm, y1, z2, color);
        line(pose, buffer, x2, y1, z2, x2, y1, z2 - arm, color);
        line(pose, buffer, x1, y1, z2, x1 + arm, y1, z2, color);
        line(pose, buffer, x1, y1, z2, x1, y1, z2 - arm, color);
        // Top four corners.
        line(pose, buffer, x1, y2, z1, x1 + arm, y2, z1, color);
        line(pose, buffer, x1, y2, z1, x1, y2, z1 + arm, color);
        line(pose, buffer, x2, y2, z1, x2 - arm, y2, z1, color);
        line(pose, buffer, x2, y2, z1, x2, y2, z1 + arm, color);
        line(pose, buffer, x2, y2, z2, x2 - arm, y2, z2, color);
        line(pose, buffer, x2, y2, z2, x2, y2, z2 - arm, color);
        line(pose, buffer, x1, y2, z2, x1 + arm, y2, z2, color);
        line(pose, buffer, x1, y2, z2, x1, y2, z2 - arm, color);
        // Vertical corner posts.
        line(pose, buffer, x1, y1, z1, x1, y2, z1, color);
        line(pose, buffer, x2, y1, z1, x2, y2, z1, color);
        line(pose, buffer, x2, y1, z2, x2, y2, z2, color);
        line(pose, buffer, x1, y1, z2, x1, y2, z2, color);
    }

    private static void fillBox(PoseStack.Pose pose, VertexConsumer buffer, AABB box, int color) {
        quad(pose, buffer, box.minX, box.minY, box.minZ, box.maxX, box.minY, box.minZ, box.maxX, box.minY, box.maxZ, box.minX, box.minY, box.maxZ, color);
        quad(pose, buffer, box.minX, box.maxY, box.maxZ, box.maxX, box.maxY, box.maxZ, box.maxX, box.maxY, box.minZ, box.minX, box.maxY, box.minZ, color);
        quad(pose, buffer, box.minX, box.minY, box.maxZ, box.maxX, box.minY, box.maxZ, box.maxX, box.maxY, box.maxZ, box.minX, box.maxY, box.maxZ, color);
        quad(pose, buffer, box.maxX, box.minY, box.minZ, box.minX, box.minY, box.minZ, box.minX, box.maxY, box.minZ, box.maxX, box.maxY, box.minZ, color);
        quad(pose, buffer, box.minX, box.minY, box.minZ, box.minX, box.minY, box.maxZ, box.minX, box.maxY, box.maxZ, box.minX, box.maxY, box.minZ, color);
        quad(pose, buffer, box.maxX, box.minY, box.maxZ, box.maxX, box.minY, box.maxZ, box.maxX, box.maxY, box.minZ, box.maxX, box.maxY, box.maxZ, color);
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer buffer, double x1, double y1, double z1,
                             double x2, double y2, double z2, double x3, double y3, double z3,
                             double x4, double y4, double z4, int color) {
        buffer.addVertex(pose, (float) x1, (float) y1, (float) z1).setColor(color);
        buffer.addVertex(pose, (float) x2, (float) y2, (float) z2).setColor(color);
        buffer.addVertex(pose, (float) x3, (float) y3, (float) z3).setColor(color);
        buffer.addVertex(pose, (float) x4, (float) y4, (float) z4).setColor(color);
    }
}
