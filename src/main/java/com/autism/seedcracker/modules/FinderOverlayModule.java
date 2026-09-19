package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;

import autismclient.api.module.IntSetting;
import autismclient.modules.Module;

/**
 * Finder Overlay.
 *
 * Smart overlap condensation for all finder chunk markers: in flooded areas (many flags close
 * together, or several finder modules agreeing) only the MOST-overlapped chunks render - as
 * distinct hotspot beacons (orange pyramid + white-hot core) - instead of the whole flooded
 * field. Sparse, isolated flags render normally.
 *
 * Toggle the module off to always draw every flag (old behaviour).
 */
public final class FinderOverlayModule extends Module {

    private final IntSetting overlapModules = add(new IntSetting(
            "overlap-modules", "Hotspot min modules", 2, 2, 6, 1)
        .description("Distinct finder modules that must agree in an area before it condenses to hotspots.")
        .group("General"));
    private final IntSetting floodChunks = add(new IntSetting(
            "flood-chunks", "Flood chunk count", 9, 3, 50, 1)
        .description("Flags within a 5x5-chunk area before it counts as flooded (single noisy module, e.g. a kelp forest).")
        .group("General"));

    public FinderOverlayModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":finder-overlay", "Finder Overlay", category,
            "Condenses flooded finder-flag areas to the most-overlapped hotspot chunks.");
    }

    @Override
    public void onEnable() {
        ChunkFlagRenderer.configureSmartOverlap(true, overlapModules.get(), floodChunks.get());
    }

    @Override
    public void onDisable() {
        ChunkFlagRenderer.configureSmartOverlap(false, overlapModules.get(), floodChunks.get());
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    protected void onOptionValueChanged(String settingId) {
        if (isEnabled()) {
            ChunkFlagRenderer.configureSmartOverlap(true, overlapModules.get(), floodChunks.get());
        }
    }
}
