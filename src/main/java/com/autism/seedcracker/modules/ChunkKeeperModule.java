package com.autism.seedcracker.modules;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.util.FlagLog;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Chunk Keeper (nyx ChunkKeeperModule port, detection variant).
 *
 * Anti-void-chunk watchdog: some servers "strip" chunks on re-send (anti-xray plugins or grief
 * protection replacing sub-chunks with air) which silently blinds finders. This module
 * fingerprints every loaded chunk (non-empty section count) and, when the server re-sends the
 * same chunk with FEWER populated sections, flags it - a stripped chunk is itself intel (the
 * server is hiding something there) and the finders should re-scan it.
 */
public final class ChunkKeeperModule extends Module {

    private final IntSetting maxTracked = add(new IntSetting("max-tracked", "Max tracked chunks", 4096, 256, 16384, 256)
        .description("Chunk fingerprints kept in memory (oldest evicted).").group("General"));
    private final BoolSetting notify = add(new BoolSetting("notification", "Notification", true)
        .description("Chat ping when a stripped chunk re-send is detected.").group("General"));
    private final ColorSetting color = add(new ColorSetting("color", "Stripped colour", 0xA0FF00FF)
        .description("Marker colour for stripped chunks.").group("Render"));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Tracer", true)
        .description("Tracer to stripped chunk markers.").group("Render"));

    /** chunk key -> last seen populated-section count. */
    private final Map<Long, Integer> sections = new ConcurrentHashMap<>();
    private final Set<ChunkPos> stripped = ConcurrentHashMap.newKeySet();
    private int feedTicks = 0;

    public ChunkKeeperModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":chunk-keeper", "Chunk Keeper", category,
            "Detects servers stripping chunk contents on re-send (anti-xray hiding = intel).");
    }

    @Override
    public void onDisable() {
        ChunkFlagRenderer.clear(id());
    }

    @Override
    public void onGameLeft() {
        sections.clear();
        stripped.clear();
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public boolean onPacketReceive(Packet<?> packet) {
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunkPacket) {
            // Compare against the previous fingerprint AFTER the world applies it: defer one tick.
            final int x = chunkPacket.getX();
            final int z = chunkPacket.getZ();
            Minecraft.getInstance().execute(() -> inspect(x, z));
        } else if (packet instanceof ClientboundForgetLevelChunkPacket forget) {
            // Chunk unloads keep their fingerprint (that's the point: compare on re-send).
            stripped.remove(forget.pos());
        }
        return false;
    }

    private void inspect(int x, int z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        LevelChunk chunk = mc.level.getChunkSource().getChunk(x, z, false);
        if (chunk == null) return;

        int populated = 0;
        var chunkSections = chunk.getSections();
        for (var section : chunkSections) {
            if (section != null && !section.hasOnlyAir()) populated++;
        }

        long key = (((long) x) << 32) | (z & 0xffffffffL);
        Integer prev = sections.put(key, populated);
        if (prev != null && populated < prev - 1) { // allow 1-section jitter (lighting edge)
            ChunkPos cp = new ChunkPos(x, z);
            if (stripped.add(cp)) {
                FlagLog.warn("CHUNKKEEP", "ChunkKeeper", "chunk " + x + "," + z
                    + " re-sent with " + populated + " sections (was " + prev + ") - stripped!");
                if (notify.get()) {
                    AutismClientMessaging.sendPrefixed("§d[ChunkKeeper] §fChunk " + (x << 4) + ", " + (z << 4)
                        + " was re-sent STRIPPED (" + prev + " -> " + populated + " sections).");
                }
            }
        }

        // Evict oldest fingerprints over the cap (cheap random eviction is fine here).
        if (sections.size() > maxTracked.get()) {
            var it = sections.keySet().iterator();
            for (int i = 0; i < 64 && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
    }

    @Override
    public void tick() {
        if (++feedTicks < 10) return;
        feedTicks = 0;
        ChunkFlagRenderer.feed(id(), stripped, color.get(), tracer.get());
    }

    @Override
    public String info() {
        return stripped.size() + " stripped";
    }
}
