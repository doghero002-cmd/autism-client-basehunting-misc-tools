// SPDX-License-Identifier: GPL-3.0-only
package com.autism.seedcracker.modules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.spectre.palette.BlockEntityCapture;
import com.autism.seedcracker.spectre.palette.PaletteOverlay;
import com.autism.seedcracker.spectre.palette.SpawnerScan;
import com.autism.seedcracker.util.RelogPersistence;
import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Spectre SpawnerNametags (d5a7dd3de0be), using native GUI extraction and shared ESP line types. */
public final class SpawnerNametagsModule extends Module {
    private static final String ID = SeedcrackerAddon.ID + ":spawner-nametags";
    private final IntSetting range = add(new IntSetting("range", "Range", 64, 8, 256, 1).group("General"));
    private final BoolSetting radius = add(new BoolSetting("activation-radius", "Activation radius", true).group("General"));
    private final ColorSetting accent = add(new ColorSetting("accent", "Active range color", 0xff55ffff).group("Render"));
    private final SpawnerScan scan = new SpawnerScan();
    private ClientLevel level;
    private boolean unloadHook;
    private int count;

    public SpawnerNametagsModule() {
        super(ID, "Spawner Nametags", "Shows spawner mobs, distance and activation radius; includes raw hidden block-entity data.");
    }
    @Override public void onEnable() {
        reset();
        BlockEntityCapture.init();
        PaletteOverlay.init();
        if (!unloadHook) {
            unloadHook = true;
            ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
                if (world == level) PaletteOverlay.clear(ID);
            });
        }
    }
    @Override public void onDisable() { reset(); }
    @Override public void onGameLeft() {
        reset();
        if (RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }
    private void reset() {
        scan.clear();
        level = null;
        count = 0;
        PaletteOverlay.clear(ID);
    }

    @Override public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level) { reset(); level = mc.level; }
        if (level == null || mc.player == null) { PaletteOverlay.clear(ID); return; }
        var positions = scan.tick(mc, (range.get() + 15) / 16 + 1, 4).stream()
            .filter(pos -> pos.distToCenterSqr(mc.player.position()) <= (double) range.get() * range.get())
            .sorted(Comparator.comparingDouble(pos -> pos.distToCenterSqr(mc.player.position()))).limit(48).toList();
        count = positions.size();
        List<PaletteOverlay.Line> lines = new ArrayList<>();
        List<PaletteOverlay.Label> labels = new ArrayList<>();
        for (BlockPos pos : positions) {
            var data = BlockEntityCapture.spawnerData(level, pos);
            String mob = BlockEntityCapture.mobName(data);
            double distanceSquared = pos.distToCenterSqr(mc.player.position());
            double distance = Math.sqrt(distanceSquared);
            boolean inside = data.active(distanceSquared);
            float fade = (float) Math.clamp((range.get() - distance) / (range.get() * 0.125), 0, 1);
            String detail = Math.round(distance) + "m";
            if (radius.get()) {
                if (data.activationRange() > 0) detail += "  ·  " + data.activationRange() + "m range";
                else if (data.activationRange() < 0) detail += "  ·  always active";
                else detail += "  ·  inactive range";
                if (data.activationRange() > 0) PaletteOverlay.ring(lines, Vec3.atCenterOf(pos), data.activationRange(),
                    PaletteOverlay.withAlpha(accent.get(), (inside ? 0.8f : 0.4f) * fade), 1.5f);
            }
            labels.add(new PaletteOverlay.Label(new Vec3(pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5), pos,
                mob == null ? "Spawner" : mob + " Spawner", detail, inside ? accent.get() : 0xffdddddd, fade));
        }
        PaletteOverlay.feed(ID, level, lines, labels);
    }

    @Override public String info() { return count == 0 ? "" : count + " labels"; }
}
