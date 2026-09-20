package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;

import autismclient.api.module.IntSetting;
import autismclient.modules.Module;

/**
 * Chunk Waypoints.
 *
 * Pins every finder's chunk markers (Stash/Sus/Activity/Growth/Spawner/Prime/etc - everything
 * drawn by the shared {@link ChunkFlagRenderer}) at a configurable Y level instead of following
 * your camera height. Useful when you tunnel at bedrock but want the markers up at ground level
 * (or vice versa), or want a consistent "waypoint plane" while flying above the terrain.
 *
 * While disabled, markers follow the camera as before.
 */
public final class ChunkWaypointsModule extends Module {

    private final IntSetting yLevel = add(new IntSetting("y-level", "Marker Y level", 16, -64, 320, 1)
        .description("World Y where all finder chunk markers are drawn. Markers always stay pinned here - they never follow the camera.")
        .group("General"));

    public ChunkWaypointsModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":chunk-waypoints", "Chunk Waypoints", category,
            "Pins all finder chunk markers at a fixed Y level instead of your camera height.");
    }

    @Override
    public void onEnable() {
        ChunkFlagRenderer.configureDisplayY(true, yLevel.get());
    }

    @Override
    public void onDisable() {
        ChunkFlagRenderer.configureDisplayY(false, 0);
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        // Re-assert every tick so nothing (stale config, other callers) can flip it back to camera-follow.
        ChunkFlagRenderer.configureDisplayY(true, yLevel.get());
    }

    @Override
    protected void onOptionValueChanged(String settingId) {
        if (isEnabled()) ChunkFlagRenderer.configureDisplayY(true, yLevel.get());
    }

    @Override
    public String info() {
        return "Y=" + yLevel.get();
    }
}
