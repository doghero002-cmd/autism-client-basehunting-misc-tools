package com.autism.seedcracker.oresim;

import java.util.Map;
import java.util.Set;

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
 * Renders the OreSim results: one outlined box per simulated ore block, coloured by ore type.
 *
 * Self-registers a single {@link LevelRenderEvents#COLLECT_SUBMITS} listener on {@link #init()}.
 * The {@link OreSimModule} feeds it the engine's chunk map plus which ore ids are enabled; boxes
 * are drawn relative to the camera each frame, only for chunks within the configured range.
 */
public final class OreSimRenderer {

    private static volatile boolean initialised = false;
    private static volatile boolean active = false;
    private static volatile int range = 5;
    private static volatile Set<String> enabledOres = Set.of();

    private static final float LINE_WIDTH = 1.5f;
    private static final double INFLATE = 0.02;

    private OreSimRenderer() {}

    public static void init() {
        if (initialised) return;
        initialised = true;
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            if (!active) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null || mc.player == null) return;

            CameraRenderState cameraState = context.levelState().cameraRenderState;
            Vec3 origin = cameraState.pos;
            PoseStack poseStack = context.poseStack();

            int pcx = mc.player.chunkPosition().x();
            int pcz = mc.player.chunkPosition().z();

            for (Map.Entry<Long, Map<Ore, Set<BlockPos>>> chunkEntry : OreSimEngine.chunkOres().entrySet()) {
                long key = chunkEntry.getKey();
                int cx = (int) (key >> 32);
                int cz = (int) (key & 0xffffffffL);
                if (Math.abs(cx - pcx) > range || Math.abs(cz - pcz) > range) continue;

                for (Map.Entry<Ore, Set<BlockPos>> oreEntry : chunkEntry.getValue().entrySet()) {
                    Ore ore = oreEntry.getKey();
                    if (!enabledOres.contains(ore.id)) continue;
                    int argb = ore.color;
                    for (BlockPos pos : oreEntry.getValue()) {
                        AABB box = new AABB(
                            pos.getX() - origin.x, pos.getY() - origin.y, pos.getZ() - origin.z,
                            pos.getX() + 1 - origin.x, pos.getY() + 1 - origin.y, pos.getZ() + 1 - origin.z)
                            .inflate(INFLATE);
                        context.submitNodeCollector().submitCustomGeometry(poseStack,
                            AutismRenderTypes.storageEspLinesSeeThrough(),
                            (pose, buffer) -> outlineBox(pose, buffer, box, argb));
                    }
                }
            }
        });
    }

    /** Feed the renderer each tick: which ores to draw, and the chunk range. */
    public static void feed(boolean isActive, Set<String> enabled, int chunkRange) {
        active = isActive;
        enabledOres = enabled == null ? Set.of() : enabled;
        range = Math.max(1, chunkRange);
    }

    public static void stop() {
        active = false;
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

    private static void line(PoseStack.Pose pose, VertexConsumer buffer, double x1, double y1, double z1,
                             double x2, double y2, double z2, int color) {
        AutismWorldGeometry.line(pose, buffer, x1, y1, z1, x2, y2, z2, color, LINE_WIDTH);
    }
}
