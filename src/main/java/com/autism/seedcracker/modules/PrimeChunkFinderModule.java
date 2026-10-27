// SPDX-License-Identifier: GPL-3.0-only
// Native port of Spectre PrimeChunkFinderModule, d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da.
package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.finder.FinderNotify;
import com.autism.seedcracker.finder.FinderReport;
import com.autism.seedcracker.spectre.chunks.PrimeTracker;
import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.level.block.state.BlockState;

public final class PrimeChunkFinderModule extends Module {
    private final IntSetting range = add(new IntSetting("range", "Initial snapshot radius", 32, 1, 32, 1).group("Detect"));
    private final BoolSetting notifications = add(new BoolSetting("notification", "Notification", true).group("Alerts"));
    private final BoolSetting sound = add(new BoolSetting("sound", "Sound", true).group("Alerts").visibleWhen(notifications::get));
    private final ColorSetting color = add(new ColorSetting("color", "Colour", 0xFFFFB030).group("Render"));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Tracer", true).group("Render"));
    private final FinderReport reporter = new FinderReport("Prime", 55);
    private final PrimeTracker tracker = new PrimeTracker(pos -> {
        if (notifications.get()) FinderNotify.flag("[Prime Finder]", "Underground change hypothesis at X:" + pos.getX() + " Y:" + pos.getY() + " Z:" + pos.getZ(), sound.get());
    });
    private ClientLevel world;

    public PrimeChunkFinderModule() {
        super(SeedcrackerAddon.ID + ":prime-chunk-finder", "Prime Chunk Finder",
            "Spectre unexplained light and redstone changes below Y 0; excludes visited chunks and known light sources.");
    }
    private void clear(ClientLevel level) { tracker.reset(level); reporter.clear(); ChunkFlagRenderer.clear(id()); world = level; }
    @Override public void onEnable() {
        var mc = Minecraft.getInstance(); clear(mc.level); ChunkFlagRenderer.init();
        if (mc.player != null && mc.player.getY() >= 0) {
            FinderNotify.flag("[Prime Finder]", "Go below Y 0 before enabling Prime detection.", false);
            clear(null); setEnabledSilently(false);
        }
    }
    @Override public void onDisable() { clear(null); }
    @Override protected void onSettingsReset() { clear(isEnabled() ? Minecraft.getInstance().level : null); }
    @Override public void onGameLeft() {
        clear(null);
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }
    @Override public void tick() {
        var mc = Minecraft.getInstance();
        if (world != mc.level) clear(mc.level);
        var shown = tracker.tick(mc, range.get());
        if (mc.level == null || mc.player == null) return;
        ChunkFlagRenderer.feed(id(), shown, color.get(), tracer.get()); reporter.tick(mc, shown);
    }

    /** ClientPacketListener TAIL: chunk-with-light, light-update and forget-chunk packets. */
    public void onPacketApplied(ClientLevel level, Packet<?> packet) {
        if (!isEnabled() || !Minecraft.getInstance().isSameThread() || level != Minecraft.getInstance().level) return;
        if (world != level) clear(level);
        tracker.applied(level, packet);
    }

    /** Existing ClientLevelMixin HEAD hook preserves the previous state before vanilla replaces it. */
    public void onBlockChange(ClientLevel level, BlockPos pos, BlockState previous, BlockState incoming) {
        var mc = Minecraft.getInstance();
        if (!isEnabled() || !mc.isSameThread() || level == null || level != mc.level) return;
        if (world != level) clear(level);
        tracker.blockChange(level, pos, previous, incoming);
    }
    @Override public String info() { return tracker.size() + " change candidates"; }
}
