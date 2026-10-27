// SPDX-License-Identifier: GPL-3.0-only
// LIGHT_DATA adapted from Spectre PlayerBypassModule/tracker, d5a7dd3 (Cody / cody-raves).
// The addon's independent SIMULATION_ACTIVITY mode and stable module/settings IDs are retained.
package com.autism.seedcracker.modules;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.finder.FinderNotify;
import com.autism.seedcracker.finder.FinderReport;
import com.autism.seedcracker.spectre.activity.PlayerLightTracker;
import com.autism.seedcracker.spectre.activity.QuantumRules;
import com.autism.seedcracker.util.pure.PlayerFinderSignals;
import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

public final class PlayerChunksModule extends Module {
    public enum Mode { LIGHT_DATA, SIMULATION_ACTIVITY }
    private final EnumSetting<Mode> mode = add(new EnumSetting<>("mode", "Detection mode", Mode.LIGHT_DATA,
        Mode.values()).group("Detection"));
    private final IntSetting memory = add(new IntSetting("memory", "Memory seconds", 45, 5, 180, 5)
        .group("Detection").visibleWhen(() -> mode.get() == Mode.SIMULATION_ACTIVITY));
    private final IntSetting sources = add(new IntSetting("min-sources", "Independent activity sources", 4, 2, 16, 1)
        .group("Detection").visibleWhen(() -> mode.get() == Mode.SIMULATION_ACTIVITY));
    private final BoolSetting notify = add(new BoolSetting("notification", "Notification", true).group("Alerts"));
    private final BoolSetting sound = add(new BoolSetting("sound", "Sound", true).group("Alerts").visibleWhen(() -> notify.get()));
    private final BoolSetting disconnect = add(new BoolSetting("disconnect-on-spotted", "Auto disconnect", false).group("Alerts"));
    private final BoolSetting showCoordinates = add(new BoolSetting("show-coordinates", "Show coordinates", true)
        .group("Alerts").visibleWhen(() -> notify.get() || disconnect.get()));
    private final ColorSetting color = add(new ColorSetting("color", "Chunk colour", 0xffff2828).group("Render"));
    private final IntSetting opacity = add(new IntSetting("opacity", "Opacity", 140, 10, 255, 1).group("Render"));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Tracer", false).group("Render"));
    private final PlayerLightTracker lightTracker = new PlayerLightTracker();
    private final FinderReport reporter = new FinderReport("Player Finder", 35);
    private final Map<ChunkPos, Long> loadedAt = new LinkedHashMap<>();
    private final Map<ChunkPos, Map<String, Long>> activity = new LinkedHashMap<>();
    private final Map<ChunkPos, Map<BlockPos, net.minecraft.world.level.block.state.BlockState>> blockStates = new LinkedHashMap<>();
    private final Map<Integer, Vec3> entityPositions = new HashMap<>();
    private final Set<ChunkPos> flagged = new HashSet<>();
    private final Set<ChunkPos> alerted = new HashSet<>();
    private ClientLevel world;
    private ClientPacketListener connection;
    private Vec3 lastPosition;
    private long quietUntil;
    private boolean disconnectTriggered;
    private int ticks;

    public PlayerChunksModule() {
        super(SeedcrackerAddon.ID + ":player-chunks", "Player Finder",
            "Light-data anomalies or simulation activity outside your own range. Signals, not player proof.");
    }

    private void clear() {
        lightTracker.clear();
        loadedAt.clear();
        activity.clear();
        blockStates.clear();
        entityPositions.clear();
        flagged.clear();
        alerted.clear();
        reporter.clear();
        lastPosition = null;
        ticks = 0;
        disconnectTriggered = false;
        quietUntil = System.nanoTime() + 3_000_000_000L;
        ChunkFlagRenderer.clear(id());
    }
    @Override public void onEnable() { clear(); world = null; connection = null; }
    @Override public void onDisable() { clear(); world = null; connection = null; }
    @Override public void onGameLeft() {
        onDisable();
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }
    @Override protected void onOptionValueChanged(String settingId) {
        if ("mode".equals(settingId) || "memory".equals(settingId) || "min-sources".equals(settingId)) clear();
    }

    private boolean ready() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) return false;
        if (world != mc.level || connection != mc.getConnection()) {
            clear();
            world = mc.level;
            connection = mc.getConnection();
        }
        return isEnabled() && world != null && mc.player != null && world.dimensionType().hasSkyLight();
    }

    /** ClientPacketListener TAIL hook: preserve packet order and never inspect the world on Netty. */
    public void onPacketApplied(Packet<?> packet) {
        if (!ready()) return;
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        if (packet instanceof ClientboundForgetLevelChunkPacket forget) {
            onChunkUnload(forget.pos());
        } else if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            ChunkPos pos = new ChunkPos(chunk.getX(), chunk.getZ());
            if (distance(pos, mc.player.chunkPosition()) > 64) return;
            if (mode.get() == Mode.LIGHT_DATA) ingest(pos, chunk.getLightData());
            else {
                loadedAt.put(pos, now);
                activity.remove(pos);
                blockStates.remove(pos);
                while (loadedAt.size() > PlayerLightTracker.MAX_CHUNKS) forget(loadedAt.keySet().iterator().next());
            }
        } else if (mode.get() == Mode.LIGHT_DATA && packet instanceof ClientboundLightUpdatePacket light) {
            ingest(new ChunkPos(light.getX(), light.getZ()), light.getLightData());
        } else if (mode.get() == Mode.SIMULATION_ACTIVITY && packet instanceof ClientboundBlockUpdatePacket block) {
            sampleBlock(mc, block.getPos(), block.getBlockState(), now);
        } else if (mode.get() == Mode.SIMULATION_ACTIVITY && packet instanceof ClientboundSectionBlocksUpdatePacket section) {
            section.runUpdates((pos, state) -> sampleBlock(mc, pos, state, now));
        }
    }

    @Override public void tick() {
        if (!ready()) return;
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        Vec3 position = mc.player.position();
        if (lastPosition != null && lastPosition.distanceToSqr(position) > 64) {
            activity.clear();
            blockStates.clear();
            entityPositions.clear();
            quietUntil = now + 3_000_000_000L;
        }
        lastPosition = position;
        if (mode.get() == Mode.LIGHT_DATA) {
            ChunkPos current = mc.player.chunkPosition();
            lightTracker.updateEntry(current.x(), current.z(), key -> world.getChunkSource().hasChunk(QuantumRules.x(key), QuantumRules.z(key)));
        }
        if (++ticks % 10 == 0) {
            if (mode.get() == Mode.SIMULATION_ACTIVITY) sampleEntities(mc, now);
            refresh(mc, now);
        }
        int alpha = Math.round((color.get() >>> 24) * Math.max(20, opacity.get()) / 255.0f);
        ChunkFlagRenderer.feed(id(), flagged, (color.get() & 0x00ffffff) | (alpha << 24), tracer.get());
        reporter.tick(mc, flagged);
    }

    private void ingest(ChunkPos pos, ClientboundLightUpdatePacketData data) {
        Minecraft mc = Minecraft.getInstance();
        if (data == null || !world.getChunkSource().hasChunk(pos.x(), pos.z()) || distance(pos, mc.player.chunkPosition()) > 64) return;
        if (lightTracker.accept(pos.x(), pos.z(), world.getSectionsCount(), world.getMinSectionY(),
            data.getSkyYMask(), data.getEmptySkyYMask(), data.getSkyUpdates(), mc.player.getY() < 0)) onSignal(pos);
    }

    private void onSignal(ChunkPos pos) {
        String location = showCoordinates.get() ? " at X:" + (pos.getMinBlockX() + 8) + " Z:" + (pos.getMinBlockZ() + 8) : "";
        String message = mode.get() + " signal" + location + " (not player/base proof)";
        if (notify.get()) FinderNotify.flag("[Player Finder]", message, sound.get());
        if (disconnect.get() && !disconnectTriggered && connection != null) {
            disconnectTriggered = true;
            connection.getConnection().disconnect(Component.literal("[Player Finder] Auto disconnect: " + message));
        }
    }

    public void onChunkUnload(ChunkPos pos) {
        if (!ready() || pos == null) return;
        forget(pos);
        reporter.clear();
        ChunkFlagRenderer.clear(id());
    }

    private void forget(ChunkPos pos) {
        lightTracker.forget(QuantumRules.key(pos.x(), pos.z()));
        loadedAt.remove(pos);
        activity.remove(pos);
        blockStates.remove(pos);
        flagged.remove(pos);
        alerted.remove(pos);
    }

    private boolean candidate(Minecraft mc, ChunkPos pos, long now) {
        int sim = world.getServerSimulationDistance();
        if (now < quietUntil || mc.player.getY() < 0 || !world.getChunkSource().hasChunk(pos.x(), pos.z())
            || distance(pos, mc.player.chunkPosition()) > 64
            || !PlayerFinderSignals.outsideOwnSimulation(distance(pos, mc.player.chunkPosition()), sim)) return false;
        for (Player player : world.players()) {
            if (player != mc.player && distance(pos, player.chunkPosition()) <= sim + 1) return false;
        }
        return true;
    }

    private void signal(Minecraft mc, BlockPos block, String source, long now) {
        ChunkPos pos = new ChunkPos(block.getX() >> 4, block.getZ() >> 4);
        if (!candidate(mc, pos, now)) return;
        if (!loadedAt.containsKey(pos) && loadedAt.size() >= PlayerLightTracker.MAX_CHUNKS) return;
        Long loaded = loadedAt.putIfAbsent(pos, now);
        if (loaded == null || now - loaded < 3_000_000_000L) return;
        if (!activity.containsKey(pos) && activity.size() >= PlayerLightTracker.MAX_CHUNKS) return;
        Map<String, Long> chunk = activity.computeIfAbsent(pos, ignored -> new HashMap<>());
        if (chunk.containsKey(source) || chunk.size() < 64) chunk.put(source, now);
    }

    private void sampleEntities(Minecraft mc, long now) {
        Set<Integer> live = new HashSet<>();
        for (var entity : world.entitiesForRendering()) {
            if (entity instanceof Player) continue;
            if (live.size() >= 4096) break;
            live.add(entity.getId());
            Vec3 previous = entityPositions.put(entity.getId(), entity.position());
            if (previous != null && previous.distanceToSqr(entity.position()) >= 0.0001
                && previous.distanceToSqr(entity.position()) < 256) {
                signal(mc, entity.blockPosition(), "entity:" + entity.getId(), now);
            }
        }
        entityPositions.keySet().retainAll(live);
    }

    private void sampleBlock(Minecraft mc, BlockPos block, net.minecraft.world.level.block.state.BlockState state, long now) {
        ChunkPos pos = new ChunkPos(block.getX() >> 4, block.getZ() >> 4);
        if (!candidate(mc, pos, now)) return;
        if (!blockStates.containsKey(pos) && blockStates.size() >= PlayerLightTracker.MAX_CHUNKS) return;
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> known = blockStates.computeIfAbsent(pos, ignored -> new HashMap<>());
        if (!known.containsKey(block) && known.size() >= 64) return;
        var previous = known.put(block.immutable(), state);
        if (PlayerFinderSignals.changed(previous, state) && previous.getFluidState().isEmpty() && state.getFluidState().isEmpty()) {
            signal(mc, block, "block:" + block.asLong(), now);
        }
    }

    private void refresh(Minecraft mc, long now) {
        long lifetime = memory.get() * 1_000_000_000L;
        lightTracker.retain(key -> world.getChunkSource().hasChunk(QuantumRules.x(key), QuantumRules.z(key))
            && distance(new ChunkPos(QuantumRules.x(key), QuantumRules.z(key)), mc.player.chunkPosition()) <= 64);
        loadedAt.keySet().removeIf(pos -> !world.getChunkSource().hasChunk(pos.x(), pos.z()) || distance(pos, mc.player.chunkPosition()) > 64);
        blockStates.keySet().removeIf(pos -> !candidate(mc, pos, now));
        activity.entrySet().removeIf(entry -> {
            entry.getValue().values().removeIf(time -> !PlayerFinderSignals.active(time, now, lifetime));
            return entry.getValue().isEmpty() || !candidate(mc, entry.getKey(), now);
        });
        flagged.clear();
        if (mode.get() == Mode.LIGHT_DATA) {
            for (long key : lightTracker.flagged()) {
                ChunkPos pos = new ChunkPos(QuantumRules.x(key), QuantumRules.z(key));
                if (distance(pos, mc.player.chunkPosition()) <= 16) flagged.add(pos);
            }
        } else {
            activity.forEach((pos, events) -> { if (events.size() >= sources.get()) flagged.add(pos); });
            alerted.retainAll(flagged);
            for (ChunkPos pos : flagged) if (alerted.add(pos)) onSignal(pos);
        }
    }

    private static int distance(ChunkPos first, ChunkPos second) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(Math.abs((long) first.x() - second.x()), Math.abs((long) first.z() - second.z())));
    }
    @Override public String info() { return mode.get() + " / " + flagged.size() + " signals"; }
}