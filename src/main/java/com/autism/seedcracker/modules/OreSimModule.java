package com.autism.seedcracker.modules;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.oresim.Ore;
import com.autism.seedcracker.oresim.OreSimEngine;
import com.autism.seedcracker.oresim.OreSimRenderer;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * OreSim.
 *
 * Simulates where ores generate around you from the world seed and each biome's ore world-gen
 * config, using vanilla's own decoration/vein math, and draws an outline box at each simulated
 * ore block. Works for every overworld ore plus nether gold/quartz and ancient debris.
 *
 * This is a world-gen *simulation* (it computes where ore should be from the seed), not a
 * packet/render bypass - it doesn't touch or depend on server anti-xray. On servers that
 * re-randomise ore seeds (anti-xray), the simulated positions won't match the real ore.
 *
 * Port of the meteor-rejects "OreSim" module to the AUTISM API (Mojang 26.2). The seed comes from
 * the SeedCracker engine when cracked, or you can type it manually.
 */
public final class OreSimModule extends Module {

    private final StringSetting manualSeed = add(new StringSetting(
            "seed", "Seed (blank = auto)", "")
        .description("World seed. Leave blank to use the SeedCracker-cracked seed.")
        .group("General"));
    private final IntSetting range = add(new IntSetting(
            "range", "Render range (chunks)", 5, 1, 10, 1)
        .description("Chunk radius around you that ore boxes are drawn in.")
        .group("Render"));
    private final IntSetting processPerTick = add(new IntSetting(
            "process-per-tick", "Chunks per tick", 4, 1, 32, 1)
        .description("How many chunks are simulated per tick (higher = faster coverage, more CPU).")
        .group("General"));

    // Per-ore toggles.
    private final BoolSetting coal = ore("coal", "Coal", false);
    private final BoolSetting iron = ore("iron", "Iron", false);
    private final BoolSetting gold = ore("gold", "Gold", true);
    private final BoolSetting redstone = ore("redstone", "Redstone", false);
    private final BoolSetting diamond = ore("diamond", "Diamond", true);
    private final BoolSetting lapis = ore("lapis", "Lapis", false);
    private final BoolSetting copper = ore("copper", "Copper", false);
    private final BoolSetting emerald = ore("emerald", "Emerald", false);
    private final BoolSetting quartz = ore("quartz", "Nether Quartz", false);
    private final BoolSetting debris = ore("debris", "Ancient Debris", true);

    private BoolSetting ore(String id, String title, boolean def) {
        return add(new BoolSetting("ore-" + id, title, def).group("Ores"));
    }

    private Map<ResourceKey<Biome>, java.util.List<Ore>> oreConfig;
    private Ore.Dim cachedDim = null;
    private Long cachedSeed = null;

    public OreSimModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":oresim", "OreSim", category,
            "Simulates ore positions from the world seed and outlines them (seed math, not an anti-xray bypass).");
    }

    @Override
    public void onEnable() {
        OreSimRenderer.init();
        OreSimEngine.clear();
        oreConfig = null;
        cachedDim = null;
        cachedSeed = null;
        if (resolveSeed() == null) {
            AutismClientMessaging.sendPrefixed("§eOreSim: no seed yet. Crack it with SeedCracker or type it in the Seed setting.");
        }
    }

    @Override
    public void onDisable() {
        OreSimRenderer.stop();
        OreSimEngine.clear();
        oreConfig = null;
        cachedDim = null;
        cachedSeed = null;
    }

    @Override
    public void onGameLeft() {
        setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        Long seed = resolveSeed();
        if (seed == null) {
            OreSimRenderer.feed(false, Set.of(), range.get());
            return;
        }

        // (Re)build the ore config when the dimension or seed changes.
        Ore.Dim dim = currentDim(mc);
        if (oreConfig == null || dim != cachedDim || !seed.equals(cachedSeed)) {
            try {
                oreConfig = Ore.getRegistry(dim);
                cachedDim = dim;
                cachedSeed = seed;
                OreSimEngine.clear();
            } catch (Throwable t) {
                AutismClientMessaging.sendPrefixed("§cOreSim: couldn't build ore config: " + t.getMessage());
                setEnabled(false);
                return;
            }
        }

        // Simulate a few un-processed loaded chunks per tick.
        int budget = Math.max(1, processPerTick.get());
        int cx = mc.player.chunkPosition().x();
        int cz = mc.player.chunkPosition().z();
        int r = Math.max(1, range.get()) + 1;
        final long seedVal = seed;
        for (int dx = -r; dx <= r && budget > 0; dx++) {
            for (int dz = -r; dz <= r && budget > 0; dz++) {
                int x = cx + dx, z = cz + dz;
                if (!mc.level.hasChunk(x, z)) continue;
                ChunkAccess chunk = mc.level.getChunk(x, z);
                long key = ((long) x << 32) | (z & 0xffffffffL);
                if (OreSimEngine.chunkOres().containsKey(key)) continue;
                try {
                    OreSimEngine.processChunk(chunk, cachedDim, oreConfig, seedVal);
                } catch (Throwable ignored) {}
                budget--;
            }
        }

        // Prune chunks outside range so the map doesn't grow unbounded.
        int pr = r + 2;
        OreSimEngine.chunkOres().keySet().removeIf(key -> {
            int kx = (int) (key >> 32);
            int kz = (int) (key & 0xffffffffL);
            return Math.abs(kx - cx) > pr || Math.abs(kz - cz) > pr;
        });

        OreSimRenderer.feed(true, enabledOres(), range.get());
    }

    private Set<String> enabledOres() {
        Set<String> set = new HashSet<>();
        if (coal.get()) set.add("coal");
        if (iron.get()) set.add("iron");
        if (gold.get()) set.add("gold");
        if (redstone.get()) set.add("redstone");
        if (diamond.get()) set.add("diamond");
        if (lapis.get()) set.add("lapis");
        if (copper.get()) set.add("copper");
        if (emerald.get()) set.add("emerald");
        if (quartz.get()) set.add("quartz");
        if (debris.get()) set.add("debris");
        return set;
    }

    private Ore.Dim currentDim(Minecraft mc) {
        ResourceKey<Level> dim = mc.level.dimension();
        if (dim == Level.NETHER) return Ore.Dim.NETHER;
        if (dim == Level.END) return Ore.Dim.END;
        return Ore.Dim.OVERWORLD;
    }

    /** Seed from the manual setting, else the SeedCracker-cracked seed. */
    private Long resolveSeed() {
        String s = manualSeed.get().trim();
        if (!s.isEmpty()) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {}
        }
        return com.autism.seedcracker.bedrock.SeedSeedProvider.crackedSeedPublic();
    }
}
