package com.autism.seedcracker.modules;

import com.autism.seedcracker.compat.ClientNotify;

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

    /** What counts as base evidence (ports of distinct strategies from the client scans). */
    public enum Mode {
        /** Weighted all-entity score (Zelith original). */
        WEIGHTED,
        /** Only container/utility entities: minecarts w/ chests-hoppers, item frames, armor stands (MeteorPlus ItemFrameEsp idea). */
        STORAGE,
        /** Only tamed/named/leashed animals + villagers - unmistakably player-owned (nyx). */
        OWNED,
        /** Item entities: big drop clusters = mined-out area, death spot, or an active farm. */
        DROPS,
        /** Water BaseESP: farm-tell entities (bees, stands, furnace/hopper carts), farm-output
         *  item drops (bones, kelp, fungus...), and farm block entities (beehive, piston, smoker,
         *  crafter, hopper, banner) - the signals block-only finders miss. */
        BASE_SIGNALS
    }

    private final Map<ChunkPos, Double> scores = new ConcurrentHashMap<>();
    private final Set<ChunkPos> notified = ConcurrentHashMap.newKeySet();
    private int tickCounter = 0;

    private final autismclient.api.module.EnumSetting<Mode> mode = add(
        new autismclient.api.module.EnumSetting<>("mode", "Mode", Mode.WEIGHTED, Mode.values())
        .description("WEIGHTED = all entities scored. STORAGE = container/frame/stand entities. OWNED = tamed/named/leashed (player-owned). DROPS = item clusters. BASE_SIGNALS = Water BaseESP farm tells: bees/stands/powered carts + farm-output drops + farm block entities (beehive, piston, smoker, crafter, banner).")
        .group("General"));
    private final autismclient.api.module.EnumSetting<com.autism.seedcracker.finder.FinderSensitivity> sensitivity = add(
        new autismclient.api.module.EnumSetting<>("sensitivity", "Sensitivity",
            com.autism.seedcracker.finder.FinderSensitivity.MEDIUM, com.autism.seedcracker.finder.FinderSensitivity.values())
        .description("HIGH = flag on half the threshold (more finds, more noise). LOW = double (quiet, high confidence).")
        .group("General"));
    private final IntSetting threshold = add(new IntSetting("threshold", "Threshold", 10, 1, 500, 1)
        .description("Entity score a chunk needs to be flagged as active.")
        .group("General"));
    private final IntSetting scanRadius = add(new IntSetting("scan-radius", "Scan radius (chunks)", 4, 1, 12, 1)
        .description("Chunk bubble around the player scanned for entities.")
        .group("General"));
    private final IntSetting maxNotify = add(new IntSetting("max-notify", "Max notify", 3, 1, 10, 1)
        .description("Max notifications per scan pass.")
        .group("General"));
    private final IntSetting chunksPerTick = add(new IntSetting("chunks-per-tick", "Chunks per tick", 1, 1, 32, 1)
        .description("How many chunks to scan per tick. 1 = smoothest FPS, higher = faster full sweep.").group("Performance"));
    private final com.autism.seedcracker.finder.ScanCursor scanCursor = new com.autism.seedcracker.finder.ScanCursor();

    public EntityScannerModule() {
        super(SeedcrackerAddon.ID + ":z-entity-scanner", "Entity Scanner",
            "Flags chunks with lots of entity activity (possible bases).");
    }

    @Override
    public void onEnable() {
        scores.clear();
        notified.clear();
        tickCounter = 0;
    }

    @Override
    protected void onOptionValueChanged(String settingId) {
        // Mode swap: old-mode scores are meaningless under the new weights.
        if ("mode".equals(settingId) || "sensitivity".equals(settingId)) {
            scores.clear();
            notified.clear();
        }
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
        for (LevelChunk chunk : scanCursor.nextBatch(mc, scanRadius.get(), 400, chunksPerTick.get())) {
            ChunkPos cpos = chunk.getPos();
            double score = scoreChunk(mc, cpos);
            scores.put(cpos, score);
            // Cap check BEFORE notified.add: adding first marked over-cap chunks as notified
            // without ever alerting, permanently swallowing those alerts.
            if (score >= sensitivity.get().scale(threshold.get()) && notifiedThisPass < maxNotify.get() && notified.add(cpos)) {
                notifiedThisPass++;
                ClientNotify.warning(
                    "Active chunk X:" + cpos.getMiddleBlockX() + " Z:" + cpos.getMiddleBlockZ() + " (score " + (int) score + ")");
            }
        }

        // Drop chunks that moved out of range.
        ChunkPos playerChunk = mc.player.chunkPosition();
        int r = scanRadius.get();
        scores.keySet().removeIf(c -> Math.abs(c.x() - playerChunk.x()) > r || Math.abs(c.z() - playerChunk.z()) > r);
    }

    /** Entity score for one chunk under the active mode. */
    private double scoreChunk(Minecraft mc, ChunkPos cpos) {
        double score = 0.0;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == null || !e.isAlive() || e == mc.player) continue;
            if (!e.chunkPosition().equals(cpos)) continue;
            score += switch (mode.get()) {
                case WEIGHTED -> weight(e);
                case STORAGE -> storageWeight(e);
                case OWNED -> ownedWeight(e);
                case DROPS -> e instanceof net.minecraft.world.entity.item.ItemEntity ? 5.0 : 0.0;
                case BASE_SIGNALS -> baseSignalWeight(e);
            };
        }
        if (mode.get() == Mode.BASE_SIGNALS) score += baseSignalBlockScore(mc, cpos);
        return Math.min(score, 100.0);
    }

    /** Water BaseESP entity tells: bees (farm), armor stands (sorters/decor), powered carts. */
    private double baseSignalWeight(Entity e) {
        String id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
        if (id.equals("bee")) return 8.0;
        if (id.equals("armor_stand")) return 10.0;
        if (id.equals("furnace_minecart") || id.equals("hopper_minecart")) return 15.0;
        if (e instanceof net.minecraft.world.entity.item.ItemEntity item && isFarmOutputItem(item)) return 6.0;
        return 0.0;
    }

    /** Dropped farm-output items floating around = an overflowing farm nearby (Water BaseESP). */
    private static boolean isFarmOutputItem(net.minecraft.world.entity.item.ItemEntity item) {
        net.minecraft.world.item.ItemStack s = item.getItem();
        return s.is(net.minecraft.world.item.Items.BONE) || s.is(net.minecraft.world.item.Items.BONE_MEAL)
            || s.is(net.minecraft.world.item.Items.BONE_BLOCK)
            || s.is(net.minecraft.world.item.Items.KELP) || s.is(net.minecraft.world.item.Items.BAMBOO)
            || s.is(net.minecraft.world.item.Items.CACTUS) || s.is(net.minecraft.world.item.Items.SEA_PICKLE)
            || s.is(net.minecraft.world.item.Items.CRIMSON_FUNGUS) || s.is(net.minecraft.world.item.Items.WARPED_FUNGUS)
            || s.is(net.minecraft.world.item.Items.PINK_PETALS);
    }

    /** Farm block entities in the chunk (beehive, piston, smoker, crafter, hopper, banner). */
    private double baseSignalBlockScore(Minecraft mc, ChunkPos cpos) {
        LevelChunk chunk = mc.level.getChunkSource().getChunk(cpos.x(), cpos.z(), false);
        if (chunk == null) return 0.0;
        double score = 0.0;
        for (net.minecraft.world.level.block.entity.BlockEntity be : chunk.getBlockEntities().values()) {
            if (be instanceof net.minecraft.world.level.block.entity.BeehiveBlockEntity) score += 8.0;
            else if (be instanceof net.minecraft.world.level.block.entity.HopperBlockEntity) score += 5.0;
            else if (be instanceof net.minecraft.world.level.block.entity.SmokerBlockEntity) score += 6.0;
            else if (be instanceof net.minecraft.world.level.block.entity.CrafterBlockEntity) score += 8.0;
            else if (be instanceof net.minecraft.world.level.block.entity.BannerBlockEntity) score += 4.0;
            // mid-extension piston BE only exists while actually moving = machinery running NOW
            else if (be instanceof net.minecraft.world.level.block.piston.PistonMovingBlockEntity) score += 10.0;
        }
        return score;
    }

    private double weight(Entity e) {
        if (e instanceof Player) return 20.0;
        if (e instanceof AbstractMinecart) return 15.0;
        if (e instanceof Monster) return 12.0;
        if (e instanceof AbstractVillager) return 10.0;
        if (e instanceof Animal) return 6.0;
        return 4.0;
    }

    /** Container / utility entities - always player-placed. */
    private double storageWeight(Entity e) {
        String id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
        if (id.equals("chest_minecart") || id.equals("hopper_minecart") || id.equals("chest_boat")) return 20.0;
        if (id.equals("item_frame") || id.equals("glow_item_frame")) return 12.0;
        if (id.equals("armor_stand")) return 10.0;
        if (id.equals("leash_knot")) return 10.0;
        if (e instanceof AbstractMinecart) return 8.0;
        return 0.0;
    }

    /** Tamed / named / leashed = unmistakably player-owned. */
    private double ownedWeight(Entity e) {
        if (e.hasCustomName()) return 25.0; // name tags are expensive - strong signal
        if (e instanceof net.minecraft.world.entity.TamableAnimal tam && tam.isTame()) return 20.0;
        if (e instanceof net.minecraft.world.entity.Mob mob && mob.isLeashed()) return 15.0;
        if (e instanceof AbstractVillager) return 8.0; // villagers near beds/workstations = moved-in
        return 0.0;
    }

    @Override
    public String info() {
        long hot = scores.values().stream().filter(s -> s >= sensitivity.get().scale(threshold.get())).count();
        return hot + " hot";
    }
}
