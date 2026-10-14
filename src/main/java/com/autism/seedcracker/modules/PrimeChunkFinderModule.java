package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.PrimePacketTracker;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.level.block.state.BlockState;
import autismclient.util.AutismClientMessaging;

/**
 * Prime Chunk Finder.
 *
 * Anubis-derived block-light and redstone change detection, with bounded client-thread work.
 */
public final class PrimeChunkFinderModule extends Module {

    private final IntSetting range = add(new IntSetting("range", "Initial snapshot radius", 32, 1, 32, 1).group("Detect"));
    private final BoolSetting notifications = add(new BoolSetting("notification", "Notification", true).group("Alerts"));
    private final ColorSetting color = add(new ColorSetting("color", "Colour", 0xFFFFB030)
        .description("Highlight colour for flagged chunks.").group("Render"));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Tracer", true)
        .description("Draw a line to the nearest flagged chunk.").group("Render"));

    private final PrimePacketTracker tracker;

    public PrimeChunkFinderModule() {
        super(SeedcrackerAddon.ID + ":prime-chunk-finder", "Prime Chunk Finder",
            "Tracks unexplained underground block-light and redstone changes.");
        tracker = new PrimePacketTracker(id(), pos -> {
            if (notifications.get()) AutismClientMessaging.sendPrefixed("[Prime Finder] Change at X:" + pos.getX() + " Y:" + pos.getY() + " Z:" + pos.getZ());
        });
    }

    @Override
    public void onEnable() {
        tracker.reset();
    }

    @Override
    public void onDisable() {
        tracker.reset();
    }

    @Override
    public void onGameLeft() {
        onDisable();
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        tracker.tick(Minecraft.getInstance(), range.get(), color.get(), tracer.get());
    }

    @Override public boolean onPacketReceive(Packet<?> packet) {
        tracker.offer(packet, Minecraft.getInstance().level);
        return false;
    }

    public void onBlockChange(ClientLevel level, BlockPos pos, BlockState previous, BlockState incoming) {
        tracker.blockChange(level, pos, previous, incoming);
    }

    @Override public String info() { return tracker.size() + " chunks"; }
}
