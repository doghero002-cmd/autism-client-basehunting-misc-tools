// SPDX-License-Identifier: GPL-3.0-only
package com.autism.seedcracker.spectre.palette;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.compat.EspRenderTypes;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/** Native HUD/line adapter for Spectre labels, tracers and radius rings. Boxes use BlockEspRenderer. */
public final class PaletteOverlay {
    private static final long TTL_NANOS = 300_000_000L;
    private static final Map<String, Frame> FRAMES = new ConcurrentHashMap<>();
    private static boolean initialized;
    public record Line(Vec3 from, Vec3 to, int color, float width) {}
    public record Label(Vec3 anchor, BlockPos block, String title, String detail, int color, float alpha) {}
    private record Frame(ClientLevel level, List<Line> lines, List<Label> labels, long at) {}

    private PaletteOverlay() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> prune(mc.level));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> FRAMES.clear());
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            Minecraft mc = Minecraft.getInstance();
            prune(mc.level);
            if (FRAMES.isEmpty()) return;
            Vec3 camera = context.levelState().cameraRenderState.pos;
            List<Line> lines = FRAMES.values().stream().flatMap(frame -> frame.lines().stream()).toList();
            if (lines.isEmpty()) return;
            context.submitNodeCollector().submitCustomGeometry(context.poseStack(), EspRenderTypes.LINES_SEE_THROUGH,
                (pose, buffer) -> {
                    for (Line line : lines) {
                        Vec3 from = line.from() == null ? Vec3.ZERO : line.from().subtract(camera);
                        Vec3 to = line.to().subtract(camera);
                        EspRenderTypes.line(pose, buffer, from.x, from.y, from.z, to.x, to.y, to.z, line.color(), line.width());
                    }
                });
        });
        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath(SeedcrackerAddon.ID, "spectre-palette-labels"), (graphics, delta) -> renderHud(graphics));
    }

    public static void feed(String id, ClientLevel level, List<Line> lines, List<Label> labels) {
        if (level == null || (lines.isEmpty() && labels.isEmpty())) {
            clear(id);
            return;
        }
        init();
        FRAMES.put(id, new Frame(level, List.copyOf(lines.subList(0, Math.min(4096, lines.size()))),
            List.copyOf(labels.subList(0, Math.min(64, labels.size()))), System.nanoTime()));
    }

    public static void clear(String id) { FRAMES.remove(id); }

    private static void prune(ClientLevel level) {
        long now = System.nanoTime();
        FRAMES.values().removeIf(frame -> frame.level() != level || now - frame.at() > TTL_NANOS);
    }

    public static void ring(List<Line> lines, Vec3 center, double radius, int color, float width) {
        if (!(radius > 0) || !Double.isFinite(radius)) return;
        Vec3 previous = center.add(radius, 0, 0);
        for (int i = 1; i <= 64; i++) {
            double angle = 2 * Math.PI * i / 64;
            Vec3 point = center.add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            lines.add(new Line(previous, point, color, width));
            previous = point;
        }
    }

    private static void renderHud(GuiGraphicsExtractor graphics) {
        Minecraft mc = Minecraft.getInstance();
        prune(mc.level);
        if (mc.player == null || mc.gui.hud.isHidden()) return;
        List<Label> labels = new ArrayList<>();
        for (Frame frame : FRAMES.values()) labels.addAll(frame.labels());
        Vec3 eye = mc.gameRenderer.mainCamera().position();
        labels.sort(java.util.Comparator.comparingDouble((Label label) -> label.anchor().distanceToSqr(eye)).reversed());
        for (Label label : labels) {
            float alpha = Math.clamp(label.alpha(), 0, 1);
            if (alpha < 0.02f) continue;
            float[] screen = project(mc, label.anchor(), graphics.guiWidth(), graphics.guiHeight());
            if (screen == null) continue;
            if (label.block() != null) {
                BlockPos block = label.block();
                for (int corner = 0; corner < 4; corner++) {
                    float[] top = project(mc, new Vec3(block.getX() + (corner & 1), block.getY() + 1,
                        block.getZ() + (corner >> 1)), graphics.guiWidth(), graphics.guiHeight());
                    if (top != null) screen[1] = Math.min(screen[1], top[1]);
                }
            }
            int width = Math.max(mc.font.width(label.title()), mc.font.width(label.detail()));
            int x = Math.round(screen[0] - width / 2f);
            int y = Math.round(screen[1]) - 25;
            graphics.fill(x - 3, y - 2, x + width + 3, y + 21, withAlpha(0xb0101018, alpha));
            graphics.text(mc.font, label.title(), x, y, withAlpha(0xffffffff, alpha), true);
            graphics.text(mc.font, label.detail(), x, y + 11, withAlpha(label.color(), alpha), true);
        }
    }

    private static float[] project(Minecraft mc, Vec3 world, int width, int height) {
        var camera = mc.gameRenderer.mainCamera();
        if (!camera.isInitialized()) return null;
        Vec3 relative = world.subtract(camera.position());
        var forward = camera.forwardVector();
        if (relative.x * forward.x() + relative.y * forward.y() + relative.z * forward.z() <= 0) return null;
        Vec3 ndc = mc.gameRenderer.projectPointToScreen(world);
        if (!Double.isFinite(ndc.x) || !Double.isFinite(ndc.y)) return null;
        float x = (float) ((ndc.x + 1) * width / 2);
        float y = (float) ((1 - ndc.y) * height / 2);
        return x < -200 || x > width + 200 || y < -100 || y > height + 100 ? null : new float[]{x, y};
    }

    public static int withAlpha(int color, float alpha) {
        return (Math.round((color >>> 24) * Math.clamp(alpha, 0, 1)) << 24) | (color & 0xffffff);
    }

    public static int mix(int near, int far, double fraction) {
        double t = Math.clamp(fraction, 0, 1);
        int result = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int a = (near >>> shift) & 255;
            int b = (far >>> shift) & 255;
            result |= (int) Math.round(a + (b - a) * t) << shift;
        }
        return result;
    }
}
