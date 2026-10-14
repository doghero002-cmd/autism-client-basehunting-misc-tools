package com.autism.seedcracker.finder;

import com.autism.seedcracker.util.pure.PrimeLight;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class PrimePacketTracker {
    private static final byte[] ZERO = new byte[2048];
    private static final Direction[] SIDES = Direction.values();
    private static final Set<Block> REDSTONE = Set.of(Blocks.REDSTONE_WIRE, Blocks.REPEATER, Blocks.COMPARATOR,
        Blocks.OBSERVER, Blocks.REDSTONE_TORCH, Blocks.REDSTONE_WALL_TORCH, Blocks.REDSTONE_LAMP,
        Blocks.PISTON, Blocks.STICKY_PISTON, Blocks.PISTON_HEAD, Blocks.LEVER, Blocks.HOPPER,
        Blocks.DISPENSER, Blocks.DROPPER, Blocks.DAYLIGHT_DETECTOR, Blocks.TARGET, Blocks.SCULK_SENSOR,
        Blocks.CALIBRATED_SCULK_SENSOR, Blocks.TRIPWIRE, Blocks.TRIPWIRE_HOOK);
    private record Update(ClientLevel level, ChunkPos pos, ClientboundLightUpdatePacketData data, boolean baseline) {}
    private record Source(BlockPos pos, int emission, boolean amethyst) {}
    private record GoneLamp(int emission, long expires) {}
    private static final class Difference {
        final ChunkPos pos;
        final int sectionY;
        final byte[] previous;
        final byte[] incoming;
        final Map<SectionPos, List<Source>> sources = new HashMap<>();
        int cursor;
        Difference(ChunkPos pos, int sectionY, byte[] previous, byte[] incoming) {
            this.pos = pos; this.sectionY = sectionY; this.previous = previous; this.incoming = incoming;
        }
    }
    private final String owner;
    private final Consumer<BlockPos> notify;
    private final ArrayBlockingQueue<Update> updates = new ArrayBlockingQueue<>(256);
    private final LinkedHashMap<ChunkPos, Map<Integer, byte[]>> history = new LinkedHashMap<>(256, 0.75f, true);
    private final Set<ChunkPos> passed = new HashSet<>();
    private final LinkedHashSet<ChunkPos> flagged = new LinkedHashSet<>();
    private final Map<BlockPos, GoneLamp> goneLamps = new LinkedHashMap<>();
    private final Map<ChunkPos, Long> notified = new HashMap<>();
    private final ArrayDeque<Difference> differences = new ArrayDeque<>();
    private final ArrayDeque<ChunkPos> initial = new ArrayDeque<>();
    private ClientLevel level;

    public PrimePacketTracker(String owner, Consumer<BlockPos> notify) {
        this.owner = owner;
        this.notify = notify;
    }

    public void reset() {
        updates.clear(); history.clear(); passed.clear(); flagged.clear(); goneLamps.clear();
        notified.clear(); differences.clear(); initial.clear(); level = null;
        ChunkFlagRenderer.clear(owner);
    }

    public void offer(Packet<?> packet, ClientLevel world) {
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            updates.offer(new Update(world, new ChunkPos(chunk.getX(), chunk.getZ()), chunk.getLightData(), true));
        } else if (packet instanceof ClientboundLightUpdatePacket light) {
            updates.offer(new Update(world, new ChunkPos(light.getX(), light.getZ()), light.getLightData(), false));
        }
    }

    public void tick(Minecraft mc, int radius, int color, boolean tracer) {
        if (mc.level != level) {
            reset(); level = mc.level;
            if (level != null && mc.player != null) for (var chunk : ChunkScanHelper.loadedChunksAround(mc, radius)) initial.add(chunk.getPos());
        }
        if (level == null || mc.player == null) return;
        if (mc.player.getY() < 0) passed.add(mc.player.chunkPosition());
        goneLamps.values().removeIf(lamp -> lamp.expires() < System.currentTimeMillis());
        long deadline = System.nanoTime() + 3_000_000;
        for (int count = 0; count < 8 && System.nanoTime() < deadline; count++) {
            Update update = updates.poll();
            if (update == null) break;
            if (update.level() == level && level.hasChunk(update.pos().x(), update.pos().z())) ingest(update, mc);
        }
        for (int count = 0; count < 2 && !initial.isEmpty() && differences.isEmpty() && System.nanoTime() < deadline; count++) {
            ChunkPos pos = initial.removeFirst();
            if (!history.containsKey(pos) && level.hasChunk(pos.x(), pos.z())) snapshot(pos);
        }
        while (!differences.isEmpty() && System.nanoTime() < deadline) {
            Difference difference = differences.peekFirst();
            if (own(difference.pos, mc) || !level.hasChunk(difference.pos.x(), difference.pos.z())) { differences.removeFirst(); continue; }
            int index = difference.cursor++;
            int previous = PrimeLight.value(difference.previous, index);
            int incoming = PrimeLight.value(difference.incoming, index);
            if (previous != incoming) {
                BlockPos pos = new BlockPos(difference.pos.getMinBlockX() + (index & 15),
                    (difference.sectionY << 4) + (index >>> 8), difference.pos.getMinBlockZ() + ((index >>> 4) & 15));
                if (!explained(difference, pos, Math.max(previous, incoming))) flag(difference.pos, pos);
            }
            if (difference.cursor >= 4096) differences.removeFirst();
        }
        var shown = new HashSet<ChunkPos>();
        for (ChunkPos pos : flagged) if (level.hasChunk(pos.x(), pos.z())) shown.add(pos);
        ChunkFlagRenderer.feed(owner, shown, color, tracer);
    }

    private boolean own(ChunkPos pos, Minecraft mc) { return passed.contains(pos) || mc.player.chunkPosition().equals(pos); }

    private void snapshot(ChunkPos pos) {
        var sections = new HashMap<Integer, byte[]>();
        var listener = level.getChunkSource().getLightEngine().getLayerListener(LightLayer.BLOCK);
        for (int sectionY = level.getMinSectionY(); sectionY < 0; sectionY++) {
            var data = listener.getDataLayerData(SectionPos.of(pos.x(), sectionY, pos.z()));
            sections.put(sectionY, data == null ? ZERO : data.getData().clone());
        }
        history.put(pos, sections);
        trim();
    }

    private void ingest(Update update, Minecraft mc) {
        Map<Integer, byte[]> sections = history.get(update.pos());
        boolean compare = !update.baseline() && sections != null && !own(update.pos(), mc);
        if (sections == null || update.baseline()) sections = new HashMap<>();
        var data = update.data();
        int next = 0;
        int bottom = level.getChunkSource().getLightEngine().getMinLightSection();
        var present = data.getBlockYMask();
        for (int bit = present.nextSetBit(0); bit >= 0; bit = present.nextSetBit(bit + 1)) {
            byte[] incoming = next < data.getBlockUpdates().size() ? data.getBlockUpdates().get(next++) : null;
            int sectionY = bottom + bit;
            if (sectionY >= level.getMinSectionY() && sectionY < 0 && incoming != null && incoming.length == 2048)
                updateSection(update.pos(), sections, sectionY, incoming.clone(), compare);
        }
        var empty = data.getEmptyBlockYMask();
        for (int bit = empty.nextSetBit(0); bit >= 0; bit = empty.nextSetBit(bit + 1)) {
            int sectionY = bottom + bit;
            if (sectionY >= level.getMinSectionY() && sectionY < 0 && !present.get(bit)) updateSection(update.pos(), sections, sectionY, ZERO, compare);
        }
        history.put(update.pos(), sections);
        trim();
    }

    private void updateSection(ChunkPos pos, Map<Integer, byte[]> sections, int sectionY, byte[] incoming, boolean compare) {
        byte[] previous = sections.put(sectionY, incoming);
        if (compare && previous != null && !Arrays.equals(previous, incoming)) {
            if (differences.size() >= 256) differences.removeFirst();
            differences.addLast(new Difference(pos, sectionY, previous, incoming));
        }
    }

    private void trim() { while (history.size() > 5000) history.remove(history.keySet().iterator().next()); }

    public void blockChange(ClientLevel world, BlockPos pos, BlockState previous, BlockState incoming) {
        Minecraft mc = Minecraft.getInstance();
        if (world != level || mc.player == null || previous.equals(incoming) || pos.getY() >= 0) return;
        if (lamp(previous) && (!lamp(incoming) || incoming.getLightEmission() < previous.getLightEmission())) {
            if (goneLamps.size() >= 2048) goneLamps.remove(goneLamps.keySet().iterator().next());
            goneLamps.put(pos.immutable(), new GoneLamp(previous.getLightEmission(), System.currentTimeMillis() + 5000));
        }
        ChunkPos chunk = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
        if (!own(chunk, mc) && (redstone(previous) || redstone(incoming))) flag(chunk, pos);
    }

    public static boolean redstone(BlockState state) {
        return REDSTONE.contains(state.getBlock()) || state.is(BlockTags.BUTTONS) || state.is(BlockTags.PRESSURE_PLATES)
            || state.is(BlockTags.RAILS) || state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS);
    }

    public static boolean lamp(BlockState state) {
        return state.getLightEmission() > 0 && (state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH)
            || state.is(Blocks.SOUL_TORCH) || state.is(Blocks.SOUL_WALL_TORCH)
            || state.getBlock() instanceof CandleBlock || state.getBlock() instanceof CandleCakeBlock);
    }

    private static boolean amethyst(BlockState state) {
        return state.is(Blocks.AMETHYST_BLOCK) || state.is(Blocks.BUDDING_AMETHYST) || state.is(Blocks.AMETHYST_CLUSTER)
            || state.is(Blocks.SMALL_AMETHYST_BUD) || state.is(Blocks.MEDIUM_AMETHYST_BUD) || state.is(Blocks.LARGE_AMETHYST_BUD);
    }

    private boolean explained(Difference difference, BlockPos pos, int value) {
        int reach = Math.max(5, 14 - value);
        for (int sectionX = (pos.getX() - reach) >> 4; sectionX <= (pos.getX() + reach) >> 4; sectionX++)
            for (int sectionY = (pos.getY() - reach) >> 4; sectionY <= (pos.getY() + reach) >> 4; sectionY++)
                for (int sectionZ = (pos.getZ() - reach) >> 4; sectionZ <= (pos.getZ() + reach) >> 4; sectionZ++) {
                    if (!level.hasChunk(sectionX, sectionZ)) return true;
                    for (Source source : difference.sources.computeIfAbsent(SectionPos.of(sectionX, sectionY, sectionZ), this::sources)) {
                        int distance = Math.abs(source.pos().getX() - pos.getX()) + Math.abs(source.pos().getY() - pos.getY()) + Math.abs(source.pos().getZ() - pos.getZ());
                        if (source.amethyst() ? PrimeLight.amethystExplains(value, distance) : PrimeLight.sourceExplains(source.emission(), value, distance)) return true;
                    }
                }
        for (var entry : goneLamps.entrySet()) {
            BlockPos source = entry.getKey();
            int distance = Math.abs(source.getX() - pos.getX()) + Math.abs(source.getY() - pos.getY()) + Math.abs(source.getZ() - pos.getZ());
            if (PrimeLight.sourceExplains(entry.getValue().emission(), value, distance)) return true;
        }
        return false;
    }

    private List<Source> sources(SectionPos sectionPos) {
        var chunk = level.getChunk(sectionPos.x(), sectionPos.z());
        int index = chunk.getSectionIndex(sectionPos.y() << 4);
        if (index < 0 || index >= chunk.getSectionsCount()) return List.of();
        var section = chunk.getSection(index);
        if (section.hasOnlyAir() || !section.maybeHas(state -> amethyst(state) || lamp(state) || state.is(Blocks.REDSTONE_ORE) || state.is(Blocks.DEEPSLATE_REDSTONE_ORE))) return List.of();
        var result = new ArrayList<Source>();
        for (int localX = 0; localX < 16; localX++) for (int localY = 0; localY < 16; localY++) for (int localZ = 0; localZ < 16; localZ++) {
            BlockState state = section.getBlockState(localX, localY, localZ);
            boolean ore = state.is(Blocks.REDSTONE_ORE) || state.is(Blocks.DEEPSLATE_REDSTONE_ORE);
            if (!amethyst(state) && !lamp(state) && !ore) continue;
            var pos = new BlockPos((sectionPos.x() << 4) + localX, (sectionPos.y() << 4) + localY, (sectionPos.z() << 4) + localZ);
            if (ore) {
                boolean exposed = false;
                for (Direction side : SIDES) {
                    BlockPos neighbour = pos.relative(side);
                    if (!level.hasChunk(neighbour.getX() >> 4, neighbour.getZ() >> 4)) continue;
                    if (!level.getBlockState(neighbour).isSolidRender()) { exposed = true; break; }
                }
                if (!exposed) continue;
            }
            result.add(new Source(pos, ore ? 9 : state.getLightEmission(), amethyst(state)));
        }
        return result;
    }

    private void flag(ChunkPos chunk, BlockPos pos) {
        flagged.remove(chunk); flagged.add(chunk);
        while (flagged.size() > 256) { ChunkPos oldest = flagged.iterator().next(); flagged.remove(oldest); notified.remove(oldest); }
        long now = System.currentTimeMillis();
        if (now - notified.getOrDefault(chunk, 0L) >= 15000) { notified.put(chunk, now); notify.accept(pos); }
    }

    public int size() { return flagged.size(); }
}