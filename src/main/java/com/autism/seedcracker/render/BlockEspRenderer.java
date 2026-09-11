package com.autism.seedcracker.render;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.AutismRenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import autismclient.util.AutismWorldGeometry;

/**
 * Shared per-block ESP renderer for simple "highlight these blocks" modules (Amethyst ESP,
 * Bedrock Hole ESP, ...).
 *
 * Self-registers one {@link LevelRenderEvents#COLLECT_SUBMITS} listener on {@link #init()}.
 * A module feeds its current set of blocks (plus a colour and an optional tracer) every tick via
 * {@link #feed}; entries expire after a short TTL so disabling a module (or un-flagging a block)
 * makes its markers fade without bookkeeping.
 */
public final class BlockEspRenderer {

    private static final long TTL_MS = 300L;
    private static final float LINE_WIDTH = 2.0f;
    private static final double INFLATE = 0.02;

    private static final Map<String, Feed> FEEDS = new ConcurrentHashMap<>();
    private static volatile boolean initialised = false;

    private static final class Feed {
        Set<BlockPos> blocks = Set.of();
        int argb;
        boolean tracer;
        boolean fill;
        long lastFeedMs;
    }

    private BlockEspRenderer() {}

    public static void init() {
        if (initialised) return;
        initialised = true;
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            if (FEEDS.isEmpty()) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null) return;

            CameraRenderState cameraState = context.levelState().cameraRenderState;
            Vec3 origin = cameraState.pos;
            PoseStack poseStack = context.poseStack();

            long now = System.currentTimeMillis();
            FEEDS.entrySet().removeIf(e -> now - e.getValue().lastFeedMs > TTL_MS);
            if (FEEDS.isEmpty()) return;

            for (Feed feed : FEEDS.values()) {
                if (feed.blocks.isEmpty()) continue;
                int lineArgb = feed.argb;
                int fillArgb = (feed.argb & 0x00FFFFFF) | 0x33000000;

                BlockPos nearest = null;
                double nearestDist = Double.MAX_VALUE;
                for (BlockPos pos : feed.blocks) {
                    AABB box = new AABB(
                        pos.getX() - origin.x, pos.getY() - origin.y, pos.getZ() - origin.z,
                        pos.getX() + 1 - origin.x, pos.getY() + 1 - origin.y, pos.getZ() + 1 - origin.z)
                        .inflate(INFLATE);
                    context.submitNodeCollector().submitCustomGeometry(poseStack,
                        AutismRenderTypes.storageEspLinesSeeThrough(), (pose, buffer) -> outlineBox(pose, buffer, box, lineArgb));
                    if (feed.fill) {
                        context.submitNodeCollector().submitCustomGeometry(poseStack,
                            AutismRenderTypes.storageEspFillSeeThrough(), (pose, buffer) -> fillBox(pose, buffer, box, fillArgb));
                    }
                    if (feed.tracer) {
                        double d = pos.distSqr(new BlockPos((int) origin.x, (int) origin.y, (int) origin.z));
                        if (d < nearestDist) { nearestDist = d; nearest = pos; }
                    }
                }

                if (feed.tracer && nearest != null) {
                    Vec3 centre = new Vec3(
                        nearest.getX() + 0.5 - origin.x,
                        nearest.getY() + 0.5 - origin.y,
                        nearest.getZ() + 0.5 - origin.z);
                    context.submitNodeCollector().submitCustomGeometry(poseStack,
                        AutismRenderTypes.storageEspLinesSeeThrough(),
                        (pose, buffer) -> AutismWorldGeometry.line(pose, buffer, 0, 0, 0,
                            centre.x, centre.y, centre.z, lineArgb, LINE_WIDTH));
                }
            }
        });
    }

    /** Feed a module's current block set. Call every tick while enabled. */
    public static void feed(String moduleId, Set<BlockPos> blocks, int argb, boolean tracer, boolean fill) {
        if (moduleId == null || blocks == null) return;
        Feed f = FEEDS.computeIfAbsent(moduleId, k -> new Feed());
        f.blocks = blocks;
        f.argb = argb;
        f.tracer = tracer;
        f.fill = fill;
        f.lastFeedMs = System.currentTimeMillis();
    }

    /** Clear a module's markers (on disable). */
    public static void clear(String moduleId) {
        if (moduleId != null) FEEDS.remove(moduleId);
    }

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

    private static void fillBox(PoseStack.Pose pose, VertexConsumer buffer, AABB box, int color) {
        quad(pose, buffer, box.minX, box.minY, box.minZ, box.maxX, box.minY, box.minZ, box.maxX, box.minY, box.maxZ, box.minX, box.minY, box.maxZ, color);
        quad(pose, buffer, box.minX, box.maxY, box.maxZ, box.maxX, box.maxY, box.maxZ, box.maxX, box.maxY, box.minZ, box.minX, box.maxY, box.minZ, color);
        quad(pose, buffer, box.minX, box.minY, box.maxZ, box.maxX, box.minY, box.maxZ, box.maxX, box.maxY, box.maxZ, box.minX, box.maxY, box.maxZ, color);
        quad(pose, buffer, box.maxX, box.minY, box.minZ, box.minX, box.minY, box.minZ, box.minX, box.maxY, box.minZ, box.maxX, box.maxY, box.minZ, color);
        quad(pose, buffer, box.minX, box.minY, box.minZ, box.minX, box.minY, box.maxZ, box.minX, box.maxY, box.maxZ, box.minX, box.maxY, box.minZ, color);
        quad(pose, buffer, box.maxX, box.minY, box.maxZ, box.maxX, box.minY, box.maxZ, box.maxX, box.maxY, box.minZ, box.maxX, box.maxY, box.maxZ, color);
    }

    private static void line(PoseStack.Pose pose, VertexConsumer buffer, double x1, double y1, double z1,
                             double x2, double y2, double z2, int color) {
        AutismWorldGeometry.line(pose, buffer, x1, y1, z1, x2, y2, z2, color, LINE_WIDTH);
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
