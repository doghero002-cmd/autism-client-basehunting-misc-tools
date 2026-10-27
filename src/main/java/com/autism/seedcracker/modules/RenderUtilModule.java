// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Spectre RenderUtilModule, d5a7dd3 (Cody / cody-raves); GoNuts portions retain their MIT notice.
package com.autism.seedcracker.modules;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.modules.Module;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.compat.ModuleLookup;
import com.autism.seedcracker.spectre.activity.ActivityRender;
import java.util.Collection;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;

public final class RenderUtilModule extends Module {
    private final BoolSetting clearOnToggle = add(new BoolSetting("clear-chunks-on-toggle", "Clear on toggle", true).group("General"));
    private final BoolSetting highlightSources = add(new BoolSetting("render-flagged-chunk", "Highlight source chunks", true).group("Render"));
    private final ColorSetting fillColor = add(new ColorSetting("side-color", "Fill colour", 0x28ff2828).group("Render"));
    private final ColorSetting lineColor = add(new ColorSetting("line-color", "Line colour", 0xffff2828).group("Render"));

    public RenderUtilModule() {
        super(SeedcrackerAddon.ID + ":render-util", "Render Util", "Shows Quantum's candidate area and source chunks, not confirmed player locations.");
    }

    @Override public void onEnable() {
        clearRender();
        QuantumModule quantum = quantum();
        if (clearOnToggle.get() && quantum != null) quantum.clearRenderFlags();
    }
    @Override public void onDisable() {
        clearRender();
        QuantumModule quantum = quantum();
        if (clearOnToggle.get() && quantum != null) quantum.clearRenderFlags();
    }
    @Override public void onGameLeft() {
        clearRender();
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    // Detection state belongs to Quantum, so disabling this view cannot leave a second stale world cache.
    private static QuantumModule quantum() {
        return ModuleLookup.get(SeedcrackerAddon.ID + ":quantum") instanceof QuantumModule module ? module : null;
    }
    public boolean hasFlaggedChunks() {
        QuantumModule module = quantum();
        return module != null && !module.flaggedChunks().isEmpty();
    }
    public boolean isFlagged(ChunkPos pos) {
        QuantumModule module = quantum();
        return module != null && pos != null && module.flaggedChunks().contains(pos);
    }
    public void addFlaggedChunks(Collection<ChunkPos> chunks) {
        QuantumModule module = quantum();
        if (module != null) module.addFlaggedChunks(chunks);
    }
    public void clearFlaggedChunks() {
        QuantumModule module = quantum();
        if (module != null) module.clearFlaggedChunks();
        clearRender();
    }
    public void addSourceChunk(ChunkPos pos) {
        QuantumModule module = quantum();
        if (module != null) module.addSourceChunk(pos);
    }

    @Override public void tick() {
        Minecraft mc = Minecraft.getInstance();
        QuantumModule quantum = quantum();
        if (mc.level == null || quantum == null || !quantum.isEnabled()) { clearRender(); return; }
        Set<ChunkPos> sources = quantum.sourceChunks();
        ActivityRender.feed(id() + ":candidates", quantum.flaggedChunks().stream()
            .filter(pos -> !highlightSources.get() || !sources.contains(pos))
            .filter(pos -> mc.level.getChunkSource().hasChunk(pos.x(), pos.z()))
            .map(QuantumModule::marker).toList(), lineColor.get(), fillColor.get());
        if (highlightSources.get()) {
            ActivityRender.feed(id() + ":sources", sources.stream()
                .filter(pos -> mc.level.getChunkSource().hasChunk(pos.x(), pos.z()))
                .map(QuantumModule::marker).toList(), 0xff00ffff, 0x2800ffff);
        } else ActivityRender.clear(id() + ":sources");
    }

    public void clearRender() {
        ActivityRender.clear(id() + ":candidates");
        ActivityRender.clear(id() + ":sources");
    }
    @Override public String info() {
        QuantumModule module = quantum();
        return module == null || !module.isEnabled() ? "Quantum disabled" : module.flaggedChunks().size() + " candidate chunks";
    }
}
