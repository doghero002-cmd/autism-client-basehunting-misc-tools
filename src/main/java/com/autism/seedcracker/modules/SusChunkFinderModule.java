package com.autism.seedcracker.modules;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.finder.ChunkScanHelper;
import com.autism.seedcracker.util.pure.SusScore;
import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;

public final class SusChunkFinderModule extends Module {
    public enum Mode {
        CLASSIC, ANBS_PLUS;
        @Override public String toString() { return this == CLASSIC ? "Classic" : "Dog+"; }
        public static String migrate(String token, Enum<?>[] choices) {
            return "ANBS+ Scan".equals(token) && choices.length == 2 && choices[0].getDeclaringClass() == Mode.class ? "ANBS_PLUS" : token;
        }
    }
    private final EnumSetting<Mode> mode = add(new EnumSetting<>("mode", "Detection mode", Mode.CLASSIC,
        Mode.values(), Mode::toString).group("Detection"));
    private final IntSetting threshold = add(new IntSetting("geode-heat", "Heat to flag", 6, 1, 64, 1).group("Detection"));
    private final IntSetting spread = add(new IntSetting("geode-spread", "Spread radius", 5, 0, 16, 1).group("Detection"));
    private final IntSetting radius = add(new IntSetting("scan-radius", "Scan radius", 12, 2, 32, 1).group("Performance"));
    private final IntSetting budget = add(new IntSetting("chunks-per-tick", "Chunks per tick", 2, 1, 128, 1).group("Performance"));
    private final BoolSetting notify = add(new BoolSetting("notify", "Notification", true).group("Alerts"));
    private final BoolSetting detectMined = add(new BoolSetting("detect-mined-clusters", "Detect mined clusters", false).group("Detection"));
    private final ColorSetting minedColor = add(new ColorSetting("mined-color", "Mined colour", 0x60A64AFF).group("Render").visibleWhen(detectMined::get));
    private final com.autism.seedcracker.finder.MinedGeodeTracker mined = new com.autism.seedcracker.finder.MinedGeodeTracker();
    private Set<ChunkPos> minedChunks = Set.of();
    private final ColorSetting color = add(new ColorSetting("color", "Chunk colour", 0x96FF4444).group("Render"));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Tracer", false).group("Render"));
    private record Sample(int heat, boolean veto) {}
    private record Update(ClientLevel world, ChunkPos pos, boolean lightOnly) {}
    private final ArrayBlockingQueue<Update> updates = new ArrayBlockingQueue<>(256);
    private final Map<ChunkPos, Sample> samples = new HashMap<>();
    private final Map<ChunkPos, Boolean> terrain = new HashMap<>();
    private final Set<ChunkPos> flagged = new HashSet<>();
    private final Set<ChunkPos> alerted = new HashSet<>();
    private ClientLevel world;
    private List<LevelChunk> queue = List.of();
    private int cursor;
    private int ticks;
    private long refreshAt;

    public SusChunkFinderModule() {
        super(SeedcrackerAddon.ID + ":z-sus-chunk-finder", "Sus Chunk Finder",
            "Classic amethyst growth or Dog+ block-light edges. Scouting signals, not confirmed bases.");
    }

    private void clear() {
        updates.clear();
        samples.clear();
        terrain.clear();
        mined.clear(); minedChunks = Set.of();
        flagged.clear();
        alerted.clear();
        queue = List.of();
        cursor = 0;
        ticks = 0;
        refreshAt = 0;
        ChunkFlagRenderer.clear(id());
        ChunkFlagRenderer.clear(id() + ":mined");
    }
    @Override public void onEnable() { clear(); world = null; }
    @Override public void onDisable() { clear(); world = null; }
    @Override public void onGameLeft() {
        clear();
        world = null;
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }
    @Override protected void onOptionValueChanged(String settingId) {
        if ("mode".equals(settingId) || "detect-mined-clusters".equals(settingId)) clear();
    }

    @Override public boolean onPacketReceive(Packet<?> packet) {
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            updates.offer(new Update(Minecraft.getInstance().level, new ChunkPos(chunk.getX(), chunk.getZ()), false));
        } else if (packet instanceof ClientboundLightUpdatePacket light) {
            updates.offer(new Update(Minecraft.getInstance().level, new ChunkPos(light.getX(), light.getZ()), true));
        }
        return false;
    }

    @Override public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (world != mc.level) { clear(); world = mc.level; }
        if (!world.dimensionType().hasSkyLight()) { clear(); return; }
        long now = System.currentTimeMillis();
        if (cursor >= queue.size() && now >= refreshAt) {
            queue = ChunkScanHelper.loadedChunksAround(mc, radius.get());
            cursor = 0;
            refreshAt = now + 5000;
        }
        long deadline = System.nanoTime() + 3_000_000;
        int remaining = budget.get();
        Set<ChunkPos> scannedPositions = new HashSet<>();
        for (int drained = 0; drained < 128 && remaining > 0 && System.nanoTime() < deadline; drained++) {
            Update update = updates.poll();
            if (update == null) break;
            if (update.world != world || (update.lightOnly && mode.get() == Mode.CLASSIC)
                || distance(mc.player.chunkPosition(), update.pos) > radius.get()
                || samples.containsKey(update.pos)
                || !world.hasChunk(update.pos.x(), update.pos.z()) || !scannedPositions.add(update.pos)) continue;
            if (scan(mc, world.getChunk(update.pos.x(), update.pos.z()))) remaining--;
            if (System.nanoTime() >= deadline) break;
        }
        while (remaining > 0 && cursor < queue.size() && System.nanoTime() < deadline) {
            LevelChunk chunk = queue.get(cursor++);
            if (!samples.containsKey(chunk.getPos()) && distance(mc.player.chunkPosition(), chunk.getPos()) <= radius.get()
                && world.hasChunk(chunk.getPos().x(), chunk.getPos().z()) && scannedPositions.add(chunk.getPos())) {
                if (scan(mc, world.getChunk(chunk.getPos().x(), chunk.getPos().z()))) remaining--;
            }
            if (System.nanoTime() >= deadline) break;
        }
        if (++ticks % 20 == 0) {
            rebuild();
            if (detectMined.get()) minedChunks = mined.shown(world, ticks, mc.player.chunkPosition(), radius.get());
        }
        ChunkFlagRenderer.feed(id(), flagged, color.get(), tracer.get());
        ChunkFlagRenderer.feed(id() + ":mined", minedChunks, minedColor.get(), tracer.get());
    }

    private boolean scan(Minecraft mc, LevelChunk chunk) {
        if (samples.containsKey(chunk.getPos())) return false;
        if (detectMined.get() && world.dimension().equals(net.minecraft.world.level.Level.OVERWORLD) && !mined.collect(world, chunk, ticks)) return false;
        Sample result;
        if (mode.get() == Mode.CLASSIC) result = classic(mc, chunk);
        else {
            Integer edges = com.autism.seedcracker.finder.GeodeGlowSnapshot.scan(mc.level, chunk);
            result = edges == null ? null : new Sample(edges / 12, false);
        }
        if (result == null) return false;
        boolean terrainPresent = terrainInBand(chunk);
        samples.put(chunk.getPos(), result);
        terrain.put(chunk.getPos(), terrainPresent);
        return true;
    }

    private static boolean terrainInBand(LevelChunk chunk) {
        for (int sectionY = -1; sectionY <= 1; sectionY++) {
            int index = chunk.getSectionIndex(sectionY << 4);
            if (index < 0 || index >= chunk.getSectionsCount()) continue;
            var section = chunk.getSection(index);
            if (section.hasOnlyAir()) continue;
            for (int y = Math.max(-16, sectionY << 4); y <= Math.min(30, (sectionY << 4) + 15); y++) {
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    if (!section.getBlockState(x, y & 15, z).isAir()) return true;
                }
            }
        }
        return false;
    }

    private void rebuild() {
        ChunkPos center = Minecraft.getInstance().player.chunkPosition();
        Map<ChunkPos, Sample> activeSamples = new HashMap<>();
        for (int deltaX = -radius.get(); deltaX <= radius.get(); deltaX++) for (int deltaZ = -radius.get(); deltaZ <= radius.get(); deltaZ++) {
            ChunkPos pos = new ChunkPos(center.x() + deltaX, center.z() + deltaZ);
            Sample sample = samples.get(pos);
            if (sample != null && world.hasChunk(pos.x(), pos.z())) activeSamples.put(pos, sample);
        }
        Map<ChunkPos, Integer> heat = new HashMap<>();
        Set<ChunkPos> veto = new HashSet<>();
        for (var entry : activeSamples.entrySet()) {
            ChunkPos source = entry.getKey();
            Sample sample = entry.getValue();
            if (sample.heat == 0 && !sample.veto) continue;
            for (int dx = -spread.get(); dx <= spread.get(); dx++) for (int dz = -spread.get(); dz <= spread.get(); dz++) {
                ChunkPos target = new ChunkPos(source.x() + dx, source.z() + dz);
                if (!world.hasChunk(target.x(), target.z()) || distance(center, target) > radius.get()) continue;
                heat.merge(target, sample.heat, Integer::sum);
                if (sample.veto) veto.add(target);
            }
        }
        heat.entrySet().removeIf(entry -> veto.contains(entry.getKey()) || scannedNeighbours(entry.getKey()) < 3);
        flagged.clear();
        for (var entry : heat.entrySet()) {
            ChunkPos pos = entry.getKey();
            int strongest = 0;
            for (int dx = -spread.get(); dx <= spread.get(); dx++) for (int dz = -spread.get(); dz <= spread.get(); dz++) {
                if (dx == 0 && dz == 0) continue;
                ChunkPos other = new ChunkPos(pos.x() + dx, pos.z() + dz);
                int value = heat.getOrDefault(other, 0);
                strongest = Math.max(strongest, value);
            }
            if (!SusScore.geodePeakEligible(entry.getValue(), terrain.getOrDefault(pos, true), threshold.get(), strongest)) continue;
            flagged.add(pos);
            if (alerted.add(pos) && notify.get()) AutismClientMessaging.sendPrefixed("[Sus Finder] " + mode.get()
                + " signal at X:" + pos.getMinBlockX() + " Z:" + pos.getMinBlockZ());
        }
    }
    private int scannedNeighbours(ChunkPos pos) {
        int count = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if ((dx != 0 || dz != 0) && world.hasChunk(pos.x() + dx, pos.z() + dz)
                && samples.containsKey(new ChunkPos(pos.x() + dx, pos.z() + dz))) count++;
        }
        return count;
    }
    private static int distance(ChunkPos first, ChunkPos second) {
        return Math.max(Math.abs(first.x() - second.x()), Math.abs(first.z() - second.z()));
    }

    private static Sample classic(Minecraft mc, LevelChunk chunk) {
        int mature = 0, detached = 0, young = 0, unknown = 0;
        String brand = mc.getConnection() == null ? null : mc.getConnection().serverBrand();
        boolean paletteOnly = brand != null && brand.contains("DonutFolia");
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        var sections = chunk.getSections();
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            var section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir() || !section.maybeHas(SusChunkFinderModule::growth)) continue;
            if (paletteOnly) {
                if (section.maybeHas(state -> growth(state) && !state.is(Blocks.AMETHYST_CLUSTER))) young = 1;
                if (section.maybeHas(state -> state.is(Blocks.AMETHYST_CLUSTER))) mature = 1;
                continue;
            }
            int baseY = chunk.getMinY() + (sectionIndex << 4);
            for (int localY = 0; localY < 16; localY++) for (int localX = 0; localX < 16; localX++) for (int localZ = 0; localZ < 16; localZ++) {
                BlockState state = section.getBlockState(localX, localY, localZ);
                if (state.is(Blocks.AMETHYST_CLUSTER)) mature++;
                else if (growth(state)) {
                    BlockPos support = cursor.set(chunk.getPos().getMinBlockX() + localX, baseY + localY,
                        chunk.getPos().getMinBlockZ() + localZ).relative(state.getValue(BlockStateProperties.FACING).getOpposite());
                    if (!mc.level.hasChunk(support.getX() >> 4, support.getZ() >> 4)) unknown++;
                    else if (mc.level.getBlockState(support).is(Blocks.BUDDING_AMETHYST)) young++;
                    else detached++;
                }
            }
        }
        SusScore.GeodeGrowth result = new SusScore.GeodeGrowth(mature, detached, young, unknown);
        if (!result.complete()) return null;
        return new Sample(result.heat(), result.veto());
    }
    private static boolean growth(BlockState state) {
        return state.is(Blocks.AMETHYST_CLUSTER) || state.is(Blocks.LARGE_AMETHYST_BUD)
            || state.is(Blocks.MEDIUM_AMETHYST_BUD) || state.is(Blocks.SMALL_AMETHYST_BUD);
    }
    @Override public String info() { return mode.get() + " / " + flagged.size(); }
}