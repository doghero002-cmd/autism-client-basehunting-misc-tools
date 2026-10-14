package com.autism.seedcracker.modules;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.util.pure.PlayerFinderSignals;
import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
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
    private final IntSetting memory = add(new IntSetting("memory", "Memory seconds", 45, 5, 180, 5).group("Detection"));
    private final IntSetting sources = add(new IntSetting("min-sources", "Independent activity sources", 4, 2, 16, 1)
        .group("Detection").visibleWhen(() -> mode.get() == Mode.SIMULATION_ACTIVITY));
    private final BoolSetting notify = add(new BoolSetting("notification", "Notification", true).group("Alerts"));
    private final ColorSetting color = add(new ColorSetting("color", "Chunk colour", 0x9600FFFF).group("Render"));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Tracer", false).group("Render"));
    private record Pending(ClientLevel world, Packet<?> packet) {}
    private record Light(byte[] sections, long updated) {}
    private final ArrayBlockingQueue<Pending> pending = new ArrayBlockingQueue<>(256);
    private final Map<ChunkPos, Light> lights = new HashMap<>();
    private final Map<ChunkPos, Long> loadedAt = new HashMap<>();
    private final Map<ChunkPos, Map<String, Long>> activity = new HashMap<>();
    private final Map<ChunkPos, Map<BlockPos, net.minecraft.world.level.block.state.BlockState>> blockStates = new HashMap<>();
    private final Map<Integer, Vec3> entityPositions = new HashMap<>();
    private final Set<ChunkPos> flagged = new HashSet<>();
    private final Set<ChunkPos> alerted = new HashSet<>();
    private ClientLevel world;
    private Vec3 lastPosition;
    private long quietUntil;
    private int ticks;

    public PlayerChunksModule() {
        super(SeedcrackerAddon.ID + ":player-chunks", "Player Finder",
            "Light-data anomalies or simulation activity outside your own range. Signals, not player proof.");
    }

    private void clear() {
        pending.clear();
        lights.clear();
        loadedAt.clear();
        activity.clear();
        blockStates.clear();
        entityPositions.clear();
        flagged.clear();
        alerted.clear();
        lastPosition = null;
        ticks = 0;
        quietUntil = System.nanoTime() + 3_000_000_000L;
        ChunkFlagRenderer.clear(id());
    }
    @Override public void onEnable() { clear(); world = Minecraft.getInstance().level; }
    @Override public void onDisable() { clear(); world = null; }
    @Override public void onGameLeft() {
        clear();
        world = null;
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }
    @Override protected void onOptionValueChanged(String settingId) { clear(); }

    @Override public boolean onPacketReceive(Packet<?> packet) {
        if (packet instanceof ClientboundLevelChunkWithLightPacket || packet instanceof ClientboundLightUpdatePacket
            || packet instanceof ClientboundBlockUpdatePacket || packet instanceof ClientboundSectionBlocksUpdatePacket) {
            pending.offer(new Pending(Minecraft.getInstance().level, packet));
        }
        return false;
    }

    @Override public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (world != mc.level) { clear(); world = mc.level; }
        if (!world.dimensionType().hasSkyLight()) { clear(); return; }
        long now = System.nanoTime();
        Vec3 position = mc.player.position();
        if (lastPosition != null && lastPosition.distanceToSqr(position) > 64) {
            activity.clear();
            blockStates.clear();
            entityPositions.clear();
            quietUntil = now + 3_000_000_000L;
        }
        lastPosition = position;
        for (int count = 0; count < 32; count++) {
            Pending event = pending.poll();
            if (event == null) break;
            if (event.world != world) continue;
            Packet<?> packet = event.packet;
            if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
                ChunkPos pos = new ChunkPos(chunk.getX(), chunk.getZ());
                loadedAt.put(pos, now);
                lights.remove(pos);
                activity.remove(pos);
                blockStates.remove(pos);
                if (mode.get() == Mode.LIGHT_DATA) ingest(pos, chunk.getLightData(), now);
            } else if (mode.get() == Mode.LIGHT_DATA && packet instanceof ClientboundLightUpdatePacket light) {
                ingest(new ChunkPos(light.getX(), light.getZ()), light.getLightData(), now);
            } else if (mode.get() == Mode.SIMULATION_ACTIVITY && packet instanceof ClientboundBlockUpdatePacket block) {
                sampleBlock(mc, block.getPos(), block.getBlockState(), now);
            } else if (mode.get() == Mode.SIMULATION_ACTIVITY && packet instanceof ClientboundSectionBlocksUpdatePacket section) {
                section.runUpdates((pos, state) -> {
                    sampleBlock(mc, pos, state, now);
                });
            }
        }
        if (++ticks % 10 == 0) {
            if (mode.get() == Mode.SIMULATION_ACTIVITY) sampleEntities(mc, now);
            refresh(mc, now);
        }
        ChunkFlagRenderer.feed(id(), flagged, color.get(), tracer.get());
    }

    private void ingest(ChunkPos pos, ClientboundLightUpdatePacketData data, long now) {
        if (!world.hasChunk(pos.x(), pos.z())) return;
        Light previous = lights.get(pos);
        byte[] sections = previous == null ? new byte[world.getSectionsCount() + 2] : previous.sections;
        PlayerFinderSignals.apply(sections, data.getSkyYMask(), data.getEmptySkyYMask(), data.getSkyUpdates());
        lights.put(pos, new Light(sections, now));
    }

    private boolean candidate(Minecraft mc, ChunkPos pos, long now) {
        int sim = world.getServerSimulationDistance();
        if (now < quietUntil || mc.player.getY() < 0 || !world.hasChunk(pos.x(), pos.z())
            || !PlayerFinderSignals.outsideOwnSimulation(distance(pos, mc.player.chunkPosition()), sim)) return false;
        for (Player player : world.players()) {
            if (player != mc.player && distance(pos, player.chunkPosition()) <= sim + 1) return false;
        }
        return true;
    }

    private void signal(Minecraft mc, BlockPos block, String source, long now) {
        ChunkPos pos = new ChunkPos(block.getX() >> 4, block.getZ() >> 4);
        if (!candidate(mc, pos, now)) return;
        Long loaded = loadedAt.putIfAbsent(pos, now);
        if (loaded == null || now - loaded < 3_000_000_000L) return;
        Map<String, Long> chunk = activity.computeIfAbsent(pos, ignored -> new HashMap<>());
        if (chunk.containsKey(source) || chunk.size() < 64) chunk.put(source, now);
    }

    private void sampleEntities(Minecraft mc, long now) {
        Set<Integer> live = new HashSet<>();
        for (var entity : world.entitiesForRendering()) {
            if (entity instanceof Player) continue;
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
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> known = blockStates.computeIfAbsent(pos, ignored -> new HashMap<>());
        if (!known.containsKey(block) && known.size() >= 64) return;
        var previous = known.put(block.immutable(), state);
        if (PlayerFinderSignals.changed(previous, state) && previous.getFluidState().isEmpty() && state.getFluidState().isEmpty()) {
            signal(mc, block, "block:" + block.asLong(), now);
        }
    }

    private void refresh(Minecraft mc, long now) {
        long lifetime = memory.get() * 1_000_000_000L;
        lights.entrySet().removeIf(entry -> !world.hasChunk(entry.getKey().x(), entry.getKey().z())
            || distance(entry.getKey(), mc.player.chunkPosition()) > 64
            || !PlayerFinderSignals.active(entry.getValue().updated, now, lifetime));
        loadedAt.keySet().removeIf(pos -> !world.hasChunk(pos.x(), pos.z()) || distance(pos, mc.player.chunkPosition()) > 64);
        blockStates.keySet().removeIf(pos -> !candidate(mc, pos, now));
        activity.entrySet().removeIf(entry -> {
            entry.getValue().values().removeIf(time -> !PlayerFinderSignals.active(time, now, lifetime));
            return entry.getValue().isEmpty() || !candidate(mc, entry.getKey(), now);
        });
        flagged.clear();
        if (mode.get() == Mode.LIGHT_DATA) {
            if (mc.player.getY() >= 0) lights.forEach((pos, light) -> {
                if (darkCavity(pos, light.sections)) flagged.add(pos);
            });
        } else activity.forEach((pos, events) -> { if (events.size() >= sources.get()) flagged.add(pos); });
        alerted.retainAll(flagged);
        for (ChunkPos pos : flagged) if (alerted.add(pos) && notify.get()) {
            AutismClientMessaging.sendPrefixed("[Player Finder] " + mode.get() + " signal at X:"
                + pos.getMinBlockX() + " Z:" + pos.getMinBlockZ());
        }
    }

    private static int distance(ChunkPos first, ChunkPos second) {
        return Math.max(Math.abs(first.x() - second.x()), Math.abs(first.z() - second.z()));
    }

    private boolean darkCavity(ChunkPos pos, byte[] sections) {
        int bottom = world.getMinY() >> 4;
        for (int index = 1; index < sections.length - 1; index++) {
            if (sections[index] != 1 || (bottom + index - 1) * 16 + 15 >= 62) continue;
            int sectionY = bottom + index - 1;
            for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
                int chunkX = pos.x() + direction.getStepX();
                int chunkZ = pos.z() + direction.getStepZ();
                if (!world.hasChunk(chunkX, chunkZ)) continue;
                var chunk = world.getChunk(chunkX, chunkZ);
                int nearby = chunk.getSectionIndex((sectionY + direction.getStepY()) << 4);
                if (nearby >= 0 && nearby < chunk.getSectionsCount()
                    && chunk.getSection(nearby).maybeHas(net.minecraft.world.level.block.state.BlockState::isAir)) return true;
            }
            if (world.getChunk(pos.x(), pos.z()).getSection(index - 1)
                .maybeHas(net.minecraft.world.level.block.state.BlockState::isAir)) return true;
        }
        return false;
    }
    @Override public String info() { return mode.get() + " / " + flagged.size(); }
}