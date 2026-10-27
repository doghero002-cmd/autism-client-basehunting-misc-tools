// SPDX-License-Identifier: GPL-3.0-only
package com.autism.seedcracker.spectre.palette;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueOutput;

/** Spectre BlockEntityLeakTracker: raw packet tags survive even when vanilla rejects a hidden entity. */
public final class BlockEntityCapture {
    private static final BlockEntityLeaks CACHE = new BlockEntityLeaks();
    private static boolean initialized;
    private static long snapshotRevision = -1;
    private static List<BlockEntityLeaks.Entry> snapshot = List.of();

    private BlockEntityCapture() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            scope(mc.level);
            if (mc.level != null) CACHE.retainChunks(p -> mc.level.getChunkSource().hasChunk(p.x(), p.z()));
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            if (level != Minecraft.getInstance().level) return;
            scope(level);
            CACHE.removeChunk(new BlockEntityLeaks.Chunk(chunk.getPos().x(), chunk.getPos().z()));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> clear());
    }

    public static void clear() {
        CACHE.clear();
        snapshot = List.of();
        snapshotRevision = -1;
    }

    private static void scope(ClientLevel level) {
        CACHE.scope(level, level == null ? null : level.dimension().toString());
    }

    /** Invoke at ClientPacketListener's applied-packet RETURN, never from Module.onPacketReceive. */
    public static void onPacketApplied(ClientLevel level, Packet<?> packet) {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Block entity capture requires the client thread");
        init();
        scope(level);
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundLoginPacket
            || packet instanceof net.minecraft.network.protocol.game.ClientboundRespawnPacket) {
            clear();
            scope(level);
            return;
        }
        if (level == null) return;
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            if (!PaletteScan.validChunk(chunk.getX(), chunk.getZ())) return;
            List<BlockEntityLeaks.Entry> entries = new ArrayList<>();
            chunk.getChunkData().getBlockEntitiesTagsConsumer(chunk.getX(), chunk.getZ()).accept((pos, type, tag) -> {
                if (entries.size() >= BlockEntityLeaks.MAX_PER_CHUNK || (pos.getX() >> 4) != chunk.getX()
                    || (pos.getZ() >> 4) != chunk.getZ()) return;
                var entry = entry(level, pos, type, tag);
                if (entry != null) entries.add(entry);
            });
            CACHE.replaceChunk(new BlockEntityLeaks.Chunk(chunk.getX(), chunk.getZ()), entries);
        } else if (packet instanceof ClientboundBlockEntityDataPacket data) {
            var entry = entry(level, data.getPos(), data.getType(), data.getTag());
            if (entry != null) CACHE.put(entry);
        } else if (packet instanceof ClientboundBlockUpdatePacket block) {
            observe(block.getPos(), block.getBlockState());
        } else if (packet instanceof ClientboundSectionBlocksUpdatePacket section) {
            section.runUpdates(BlockEntityCapture::observe);
        } else if (packet instanceof ClientboundForgetLevelChunkPacket forgotten) {
            CACHE.removeChunk(new BlockEntityLeaks.Chunk(forgotten.pos().x(), forgotten.pos().z()));
        }
    }

    private static BlockEntityLeaks.Entry entry(ClientLevel level, BlockPos pos, BlockEntityType<?> type, CompoundTag tag) {
        if (type == null || pos.getY() < level.getMinY() || pos.getY() > level.getMaxY()) return null;
        var key = position(pos);
        if (!key.valid()) return null;
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return null;
        Identifier typeKey = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
        if (typeKey == null) return null;
        String typeId = typeKey.toString();
        BlockState state = chunk.getBlockState(pos);
        boolean visible = type.isValid(state);
        String blockId = visible ? BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString() : BlockEntityLeaks.blockFromType(typeId);
        BlockEntityLeaks.Spawner details = null;
        if (typeId.equals("minecraft:mob_spawner")) {
            var previous = CACHE.get(key);
            details = readSpawner(tag, previous == null || previous.spawner() == null
                ? BlockEntityLeaks.Spawner.unknown() : previous.spawner());
        }
        return new BlockEntityLeaks.Entry(key, typeId, blockId, details, visible);
    }

    private static void observe(BlockPos pos, BlockState state) {
        CACHE.observe(position(pos), BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }

    public static List<BlockEntityLeaks.Entry> snapshot(ClientLevel level) {
        scope(level);
        if (snapshotRevision != CACHE.revision()) {
            snapshotRevision = CACHE.revision();
            snapshot = CACHE.snapshot();
        }
        return snapshot;
    }

    public static BlockEntityLeaks.Entry get(ClientLevel level, BlockPos pos) {
        scope(level);
        return CACHE.get(position(pos));
    }

    public static BlockEntityLeaks.Position position(BlockPos pos) {
        return new BlockEntityLeaks.Position(pos.getX(), pos.getY(), pos.getZ());
    }

    public static BlockPos blockPos(BlockEntityLeaks.Entry entry) {
        var pos = entry.pos();
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }

    public static BlockEntityLeaks.Spawner readSpawner(CompoundTag tag, BlockEntityLeaks.Spawner fallback) {
        if (tag == null) return fallback;
        String mob = tag.getCompound("SpawnData").map(data -> data.getCompoundOrEmpty("entity").getStringOr("id", ""))
            .orElse(fallback.entityId());
        var activation = tag.getInt("RequiredPlayerRange");
        return new BlockEntityLeaks.Spawner(mob, activation.orElse(fallback.activationRange()),
            tag.getIntOr("Delay", fallback.delay()), activation.isPresent() || fallback.activationKnown());
    }

    public static BlockEntityLeaks.Spawner spawnerData(ClientLevel level, BlockPos pos) {
        var raw = get(level, pos);
        var fallback = raw != null && raw.spawner() != null ? raw.spawner() : BlockEntityLeaks.Spawner.unknown();
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk != null && chunk.getBlockEntity(pos) instanceof SpawnerBlockEntity spawner) {
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
            spawner.getSpawner().save(output);
            return fallback.withLiveFallback(readSpawner(output.buildResult(), fallback));
        }
        return fallback;
    }

    public static String mobName(BlockEntityLeaks.Spawner data) {
        Identifier id = Identifier.tryParse(data.entityId());
        return id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id)
            .map(type -> type.getDescription().getString()).orElse(null);
    }
}
