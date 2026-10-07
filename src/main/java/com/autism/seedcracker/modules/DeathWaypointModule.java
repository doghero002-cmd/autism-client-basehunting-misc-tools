package com.autism.seedcracker.modules;

import java.util.ArrayList;
import java.util.List;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.render.BlockEspRenderer;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;

/**
 * Death Waypoint.
 *
 * Drops a rendered marker at every spot you die, with coordinates in chat, so item recovery
 * doesn't depend on remembering numbers mid-panic. Keeps the last few deaths (gear from the
 * death before last is often still on the ground too). Detects death via the health transition
 * on YOUR player - no server dependency.
 */
public final class DeathWaypointModule extends Module {

    private final IntSetting keep = add(new IntSetting("keep", "Deaths kept", 3, 1, 10, 1)
        .description("How many recent death spots stay marked.").group("General"));
    private final BoolSetting chatCoords = add(new BoolSetting("chat", "Coords in chat", true)
        .description("Print the death position in chat.").group("General"));
    private final ColorSetting color = add(new ColorSetting("color", "Colour", 0xC0FF3030)
        .description("Marker colour.").group("Render"));

    private record Death(double x, double y, double z, String dim, long atMs) {}

    private final List<Death> deaths = new ArrayList<>();
    private boolean wasDead = false;
    private static DeathWaypointModule instance;

    /** Most recent death in {@code dim}, or null (only recorded while this module is on). */
    public static net.minecraft.core.BlockPos lastDeath(String dim) {
        if (instance == null) return null;
        for (int i = instance.deaths.size() - 1; i >= 0; i--) {
            Death d = instance.deaths.get(i);
            if (d.dim().equals(dim)) return net.minecraft.core.BlockPos.containing(d.x(), d.y(), d.z());
        }
        return null;
    }

    public DeathWaypointModule() {
        super(SeedcrackerAddon.ID + ":death-waypoint", "Death Waypoint",
            "Marks where you died with a box + chat coords (last few deaths kept).");
        instance = this;
    }

    @Override
    public void onDisable() {
        BlockEspRenderer.clearBox(id());
    }

    @Override
    public void onGameLeft() {
        // Deaths persist across relogs on the same server session (you relog to run back),
        // but the dead-state latch must reset.
        wasDead = false;
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        boolean dead = mc.player.isDeadOrDying();
        if (dead && !wasDead) {
            Death d = new Death(mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                mc.level.dimension().identifier().toString(), System.currentTimeMillis());
            deaths.add(d);
            while (deaths.size() > keep.get()) deaths.remove(0);
            if (chatCoords.get()) {
                AutismClientMessaging.sendPrefixed("§c[Death] §fYou died at §e"
                    + (int) d.x() + " " + (int) d.y() + " " + (int) d.z() + "§f (" + d.dim() + ")");
            }
        }
        wasDead = dead;

        // Render only deaths in the current dimension.
        String dim = mc.level.dimension().identifier().toString();
        List<AABB> boxes = new ArrayList<>();
        for (Death d : deaths) {
            if (!d.dim().equals(dim)) continue;
            boxes.add(new AABB(d.x() - 0.5, d.y(), d.z() - 0.5, d.x() + 0.5, d.y() + 2.0, d.z() + 0.5));
        }
        BlockEspRenderer.feedBoxes(id(), boxes, color.get());
    }

    @Override
    public String info() {
        return deaths.isEmpty() ? "" : deaths.size() + " deaths";
    }
}
