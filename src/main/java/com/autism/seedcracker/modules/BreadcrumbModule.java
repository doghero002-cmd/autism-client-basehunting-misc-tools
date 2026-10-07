package com.autism.seedcracker.modules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.render.BlockEspRenderer;

import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;

/**
 * Breadcrumb Trail.
 *
 * Renders a dotted trail of your own recent path, so you can backtrack out of caves, tunnel
 * networks, and nether mazes without placing torch markers. Points are dropped at a minimum
 * spacing and expire oldest-first; per-dimension so a nether trail doesn't draw in the overworld.
 */
public final class BreadcrumbModule extends Module {

    private final IntSetting spacing = add(new IntSetting("spacing", "Point spacing (blocks)", 3, 1, 10, 1)
        .description("Minimum distance between trail points.").group("General"));
    private final IntSetting maxPoints = add(new IntSetting("max-points", "Max points", 500, 50, 2000, 50)
        .description("Trail length (oldest points drop off).").group("General"));
    private final ColorSetting color = add(new ColorSetting("color", "Colour", 0x904DFFB0)
        .description("Trail colour.").group("Render"));

    private record Crumb(double x, double y, double z, String dim) {}

    private final Deque<Crumb> trail = new ArrayDeque<>();
    private double lastX = Double.NaN, lastY, lastZ;

    public BreadcrumbModule() {
        super(SeedcrackerAddon.ID + ":breadcrumb", "Breadcrumb Trail",
            "Dotted trail of your own path for backtracking out of caves/tunnels.");
    }

    @Override
    public void onEnable() {
        lastX = Double.NaN;
    }

    @Override
    public void onDisable() {
        BlockEspRenderer.clearBox(id());
    }

    @Override
    public void onGameLeft() {
        trail.clear();
        lastX = Double.NaN;
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        String dim = mc.level.dimension().identifier().toString();
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();

        double sp = spacing.get();
        if (Double.isNaN(lastX)
            || (px - lastX) * (px - lastX) + (py - lastY) * (py - lastY) + (pz - lastZ) * (pz - lastZ) >= sp * sp) {
            lastX = px; lastY = py; lastZ = pz;
            trail.addLast(new Crumb(px, py, pz, dim));
            while (trail.size() > maxPoints.get()) trail.removeFirst();
        }

        List<AABB> boxes = new ArrayList<>();
        for (Crumb c : trail) {
            if (!c.dim().equals(dim)) continue;
            boxes.add(new AABB(c.x() - 0.12, c.y() + 0.05, c.z() - 0.12,
                c.x() + 0.12, c.y() + 0.3, c.z() + 0.12));
        }
        BlockEspRenderer.feedBoxes(id(), boxes, color.get(), 0x50);
    }

    @Override
    public String info() {
        return trail.isEmpty() ? "" : trail.size() + " pts";
    }
}
