package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkScanHelper;

import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entity Scanner.
 *
 * Scores each chunk around the player by the entities inside it (weighted by entity type:
 * players and vehicles score highest). Chunks passing the threshold are considered "active" /
 * possibly inhabited, useful for finding other players' bases. Notifies on newly hot chunks.
 *
 * Clean-room port of the obfuscated Zelith "EntityScanner" module.
 */
public final class EntityScannerModule extends Module {

    private final Map<ChunkPos, Double> scores = new ConcurrentHashMap<>();
    private final Set<ChunkPos> notified = ConcurrentHashMap.newKeySet();
    private int tickCounter = 0;

    private final IntSetting threshold = add(new IntSetting("threshold", "Threshold", 10, 1, 500, 1)
        .description("Entity score a chunk needs to be flagged as active.")
        .group("General"));
    private final IntSetting scanRadius = add(new IntSetting("scan-radius", "Scan radius (chunks)", 4, 1, 12, 1)
        .description("Chunk bubble around the player scanned for entities.")
        .group("General"));
    private final IntSetting maxNotify = add(new IntSetting("max-notify", "Max notify", 3, 1, 10, 1)
        .description("Max notifications per scan pass.")
        .group("General"));

    public EntityScannerModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-entity-scanner", "Entity Scanner", category,
            "Flags chunks with lots of entity activity (possible bases).");
    }

    @Override
    public void onEnable() {
        scores.clear();
        notified.clear();
        tickCounter = 0;
    }

    @Override
    public void onDisable() {
        scores.clear();
        notified.clear();
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        tickCounter++;

        int notifiedThisPass = 0;
        for (LevelChunk chunk : ChunkScanHelper.loadedChunksAround(mc, scanRadius.get())) {
            ChunkPos cpos = chunk.getPos();
            double score = scoreChunk(mc, cpos);
            scores.put(cpos, score);
            if (score >= threshold.get() && notified.add(cpos) && notifiedThisPass < maxNotify.get()) {
                notifiedThisPass++;
                autismclient.util.AutismNotifications.warning(
                    "Active chunk X:" + cpos.getMiddleBlockX() + " Z:" + cpos.getMiddleBlockZ() + " (score " + (int) score + ")");
            }
        }

        // Drop chunks that moved out of range.
        ChunkPos playerChunk = mc.player.chunkPosition();
        int r = scanRadius.get();
        scores.keySet().removeIf(c -> Math.abs(c.x() - playerChunk.x()) > r || Math.abs(c.z() - playerChunk.z()) > r);
    }

    /** Weighted entity score for one chunk. */
    private double scoreChunk(Minecraft mc, ChunkPos cpos) {
        double score = 0.0;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == null || !e.isAlive() || e == mc.player) continue;
            if (!e.chunkPosition().equals(cpos)) continue;
            score += weight(e);
        }
        return Math.min(score, 100.0);
    }

    private double weight(Entity e) {
        if (e instanceof Player) return 20.0;
        if (e instanceof AbstractMinecart) return 15.0;
        if (e instanceof Monster) return 12.0;
        if (e instanceof AbstractVillager) return 10.0;
        if (e instanceof Animal) return 6.0;
        return 4.0;
    }

    @Override
    public String info() {
        long hot = scores.values().stream().filter(s -> s >= threshold.get()).count();
        return hot + " hot";
    }
}
