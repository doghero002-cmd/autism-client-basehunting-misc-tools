package com.autism.seedcracker.render;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import autismclient.util.AutismWorldGeometry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.AutismRenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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
    private static final Map<String, BoxFeed> BOX_FEEDS = new ConcurrentHashMap<>();
    private static volatile boolean initialised = false;

    private static final class Feed {
        Set<BlockPos> blocks = Set.of();
        int argb;
        boolean tracer;
        boolean fill;
        long lastFeedMs;
    }

    private static final class BoxFeed {
        java.util.List<AABB> boxes;
        int argb;
        long lastFeedMs;
    }

    private BlockEspRenderer() {}

    public static void init() {
        if (initialised) return;
        initialised = true;
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            // Run when there is anything to draw: block-ESP feeds OR bounding boxes. Previously
            // this early-returned when FEEDS was empty, which hid geode boxes unless block ESP
            // was also enabled.
            if (FEEDS.isEmpty() && BOX_FEEDS.isEmpty()) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null) return;

            CameraRenderState cameraState = context.levelState().cameraRenderState;
            Vec3 origin = cameraState.pos;
            PoseStack poseStack = context.poseStack();

            long now = System.currentTimeMillis();
            FEEDS.entrySet().removeIf(e -> now - e.getValue().lastFeedMs > TTL_MS);
            if (FEEDS.isEmpty() && BOX_FEEDS.isEmpty()) return;

            for (Feed feed : FEEDS.values()) {
                if (feed.blocks.isEmpty()) continue;
                int lineArgb = feed.argb;
                int fillArgb = (feed.argb & 0x00FFFFFF) | 0x33000000;
                // Snapshot the set: the module swaps feed.blocks on its own tick thread.
                java.util.List<BlockPos> blocks = new java.util.ArrayList<>(feed.blocks);
                boolean fill = feed.fill;

                // ONE geometry submit per feed (a lambda per box at 1000s of boxes was the
                // per-box overhead that made massive storage fields drop frames / stop rendering).
                context.submitNodeCollector().submitCustomGeometry(poseStack,
                    AutismRenderTypes.storageEspLinesSeeThrough(), (pose, buffer) -> {
                        for (BlockPos pos : blocks) {
                            outlineBox(pose, buffer, boxAt(pos, origin), lineArgb);
                        }
                    });
                if (fill) {
                    context.submitNodeCollector().submitCustomGeometry(poseStack,
                        AutismRenderTypes.storageEspFillSeeThrough(), (pose, buffer) -> {
                            for (BlockPos pos : blocks) {
                                fillBox(pose, buffer, boxAt(pos, origin), fillArgb);
                            }
                        });
                }

                if (feed.tracer) {
                    BlockPos nearest = null;
                    double nearestDist = Double.MAX_VALUE;
                    BlockPos eye = new BlockPos((int) origin.x, (int) origin.y, (int) origin.z);
                    for (BlockPos pos : blocks) {
                        double d = pos.distSqr(eye);
                        if (d < nearestDist) { nearestDist = d; nearest = pos; }
                    }
                    if (nearest != null) {
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
            }

            // Bounding boxes (e.g. per-geode outlines / storage-recorder ghosts) - batched too.
            BOX_FEEDS.entrySet().removeIf(e -> now - e.getValue().lastFeedMs > TTL_MS);
            for (BoxFeed bf : BOX_FEEDS.values()) {
                if (bf.boxes == null || bf.boxes.isEmpty()) continue;
                java.util.List<AABB> boxes = bf.boxes;
                int argb = bf.argb;
                context.submitNodeCollector().submitCustomGeometry(poseStack,
                    AutismRenderTypes.storageEspLinesSeeThrough(), (pose, buffer) -> {
                        for (AABB b : boxes) {
                            AABB rel = new AABB(
                                b.minX - origin.x, b.minY - origin.y, b.minZ - origin.z,
                                b.maxX - origin.x, b.maxY - origin.y, b.maxZ - origin.z).inflate(INFLATE);
                            outlineBox(pose, buffer, rel, argb);
                        }
                    });
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
        if (moduleId != null) {
            FEEDS.remove(moduleId);
            BOX_FEEDS.remove(moduleId);
        }
    }

    /** Feed a list of bounding boxes for a module (e.g. per-geode outlines). Call every tick while enabled. */
    public static void feedBoxes(String moduleId, java.util.List<AABB> boxes, int argb) {
        if (moduleId == null || boxes == null) return;
        BoxFeed f = BOX_FEEDS.computeIfAbsent(moduleId, k -> new BoxFeed());
        f.boxes = boxes;
        f.argb = argb;
        f.lastFeedMs = System.currentTimeMillis();
    }

    /** Feed a single bounding box for a module (e.g. a geode outline). Call every tick while enabled. */
    public static void feedBox(String moduleId, AABB box, int argb) {
        if (moduleId == null || box == null) return;
        feedBoxes(moduleId, java.util.List.of(box), argb);
    }

    /** Clear a module's single bounding box. */
    public static void clearBox(String moduleId) {
        if (moduleId != null) BOX_FEEDS.remove(moduleId);
    }

    private static AABB boxAt(BlockPos pos, Vec3 origin) {
        return new AABB(
            pos.getX() - origin.x, pos.getY() - origin.y, pos.getZ() - origin.z,
            pos.getX() + 1 - origin.x, pos.getY() + 1 - origin.y, pos.getZ() + 1 - origin.z)
            .inflate(INFLATE);
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
