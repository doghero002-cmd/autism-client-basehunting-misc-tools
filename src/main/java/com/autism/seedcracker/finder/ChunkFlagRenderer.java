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

    private ChunkFlagRenderer() {
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

            // A fixed display Y band just under the camera so markers sit near the player's level.
            double displayY = Math.floor(origin.y) - 1.0;

            for (Map.Entry<Key, Entry> e : ENTRIES.entrySet()) {
                ChunkPos pos = e.getKey().pos();
                Entry entry = e.getValue();
                int argb = entry.argb;

                double minX = pos.getMinBlockX() - origin.x;
                double minZ = pos.getMinBlockZ() - origin.z;
                double minY = displayY - origin.y;
                AABB box = new AABB(minX, minY, minZ, minX + 16, minY + BOX_HEIGHT, minZ + 16).inflate(INFLATE);

                int fillArgb = (argb & 0x00FFFFFF) | 0x33000000; // translucent fill derived from line colour
                context.submitNodeCollector().submitCustomGeometry(poseStack,
                    AutismRenderTypes.storageEspFillSeeThrough(), (pose, buffer) -> fillBox(pose, buffer, box, fillArgb));
                context.submitNodeCollector().submitCustomGeometry(poseStack,
                    AutismRenderTypes.storageEspLinesSeeThrough(), (pose, buffer) -> outlineBox(pose, buffer, box, argb));

                if (entry.tracer) {
                    Vec3 centre = new Vec3(
                        pos.getMinBlockX() + 8 - origin.x,
                        displayY + BOX_HEIGHT * 0.5 - origin.y,
                        pos.getMinBlockZ() + 8 - origin.z);
                    context.submitNodeCollector().submitCustomGeometry(poseStack,
                        AutismRenderTypes.storageEspLinesSeeThrough(),
                        (pose, buffer) -> AutismWorldGeometry.line(pose, buffer, 0, 0, 0,
                            centre.x, centre.y, centre.z, argb, LINE_WIDTH));
                }
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
