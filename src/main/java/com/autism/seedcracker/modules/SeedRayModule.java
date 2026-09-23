package com.autism.seedcracker.modules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.render.BlockEspRenderer;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import kaptainwutax.seedcrackerX.SeedCracker;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/**
 * Seed Ray (KiwiClient port).
 *
 * Given the CRACKED world seed (auto-pulled from SeedcrackerX when it finishes), re-runs the
 * vanilla ore-populate math client-side to predict where ore veins generated - then compares the
 * prediction against the real loaded chunk:
 *
 *  - render the predicted veins (x-ray from math, not packets - undetectable), and/or
 *  - MISMATCH MODE: a predicted vein whose blocks are now air/cobble means a PLAYER mined it.
 *    Flag those chunks - "someone was here" evidence that pairs with the sus-chunk finders.
 *
 * The ore-vein placement math is Mojang's OreFeature port (via Kiwi SeedRay.generateVeinPart).
 */
public final class SeedRayModule extends Module {

    private final IntSetting range = add(new IntSetting("range", "Range (chunks)", 4, 1, 8, 1)
        .description("Chunk radius to simulate around the player.").group("General"));
    private final BoolSetting showDiamond = add(new BoolSetting("diamonds", "Diamonds", true)
        .description("Simulate diamond veins.").group("Ores"));
    private final BoolSetting showDebris = add(new BoolSetting("debris", "Ancient debris", true)
        .description("Simulate ancient-debris veins (nether).").group("Ores"));
    private final BoolSetting showGold = add(new BoolSetting("gold", "Gold", false)
        .description("Simulate gold veins.").group("Ores"));
    private final BoolSetting showIron = add(new BoolSetting("iron", "Iron", false)
        .description("Simulate deep iron veins.").group("Ores"));
    private final BoolSetting render = add(new BoolSetting("render", "Render predicted ores", true)
        .description("Draw boxes on predicted (still-unmined) vein blocks.").group("Render"));
    private final ColorSetting oreColor = add(new ColorSetting("ore-color", "Ore colour", 0x8033F4FF)
        .description("Colour for predicted ore boxes.").group("Render"));
    private final BoolSetting mismatch = add(new BoolSetting("mismatch", "Mismatch detector", true)
        .description("Flag chunks where predicted veins are GONE (mined by a player) - base-hunting signal.").group("Detect"));
    private final IntSetting mismatchThreshold = add(new IntSetting("mismatch-threshold", "Mined veins to flag", 2, 1, 10, 1)
        .description("How many mined-out veins in a chunk before flagging it.").group("Detect").visibleWhen(() -> mismatch.get()));
    private final ColorSetting flagColor = add(new ColorSetting("flag-color", "Mismatch chunk colour", 0x64FF00FF)
        .description("Colour of flagged mined-out chunks.").group("Detect").visibleWhen(() -> mismatch.get()));

    // Simulated veins per chunk (chunk key -> predicted ore block positions).
    private final Map<Long, List<Set<BlockPos>>> simulated = new HashMap<>();
    private final Set<ChunkPos> flagged = new HashSet<>();
    private final Set<ChunkPos> notified = new HashSet<>();
    private final Set<BlockPos> renderBlocks = new HashSet<>();
    private long worldSeed = 0;
    private boolean haveSeed = false;
    private int cursor = 0;
    private int compareTicks = 0;
    private boolean haveComparedOnce = false;

    public SeedRayModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":seed-ray", "Seed Ray", category,
            "Simulates ore veins from the cracked world seed; mined-out veins reveal player activity (Kiwi).");
    }

    @Override
    public void onEnable() {
        simulated.clear();
        flagged.clear();
        notified.clear();
        renderBlocks.clear();
        haveSeed = false;
        compareTicks = 0;
        haveComparedOnce = false;
        resolveSeed();
        if (!haveSeed) {
            AutismClientMessaging.sendPrefixed("§e[SeedRay] §fNo cracked seed yet - run the seed cracker first (module stays on and picks it up).");
        }
    }

    @Override
    public void onDisable() {
        simulated.clear();
        flagged.clear();
        renderBlocks.clear();
        BlockEspRenderer.clear(id());
        com.autism.seedcracker.finder.ChunkFlagRenderer.clear(id());
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    /** Pull the world seed from SeedcrackerX once it has exactly one candidate. */
    private void resolveSeed() {
        try {
            var seeds = SeedCracker.get().getDataStorage().getTimeMachine().worldSeeds;
            if (seeds != null && seeds.size() == 1) {
                long s = seeds.iterator().next();
                if (!haveSeed || s != worldSeed) {
                    worldSeed = s;
                    haveSeed = true;
                    simulated.clear();
                    flagged.clear();
                    notified.clear();
                    AutismClientMessaging.sendPrefixed("§a[SeedRay] §fUsing cracked seed " + worldSeed);
                }
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!haveSeed) { resolveSeed(); if (!haveSeed) return; }

        ChunkPos center = mc.player.chunkPosition();
        int r = range.get();
        // Simulate 1 chunk per tick (each is a few hundred rng rolls - cheap but not free).
        int side = r * 2 + 1;
        int total = side * side;
        {
            int idx = cursor++ % total;
            ChunkPos pos = new ChunkPos(center.x() + idx % side - r, center.z() + idx / side - r);
            long key = key(pos);
            if (!simulated.containsKey(key) && mc.level.hasChunk(pos.x(), pos.z())) {
                simulated.put(key, simulateChunk(mc, pos));
            }
        }

        // Compare every 10 ticks: the vein-vs-world diff walks every predicted block with a
        // registry string lookup - per tick it was a steady FPS sink. Feeds stay per-tick
        // (renderer TTL) using the cached results.
        if (haveComparedOnce && ++compareTicks < 10) {
            if (render.get()) BlockEspRenderer.feed(id(), renderBlocks, oreColor.get(), false, false);
            com.autism.seedcracker.finder.ChunkFlagRenderer.feed(id(), flagged, flagColor.get(), false);
            return;
        }
        compareTicks = 0;
        haveComparedOnce = true;

        // Prune simulations for chunks far outside the bubble (unbounded growth over a session).
        int keep = r + 8;
        simulated.keySet().removeIf(k -> {
            long kv = k;
            int kx = (int) kv;
            int kz = (int) (kv >> 32);
            return Math.abs(kx - center.x()) > keep || Math.abs(kz - center.z()) > keep;
        });

        renderBlocks.clear();
        flagged.clear();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkPos pos = new ChunkPos(center.x() + dx, center.z() + dz);
                List<Set<BlockPos>> veins = simulated.get(key(pos));
                if (veins == null || !mc.level.hasChunk(pos.x(), pos.z())) continue;
                int minedVeins = 0;
                for (Set<BlockPos> vein : veins) {
                    int present = 0, gone = 0;
                    for (BlockPos bp : vein) {
                        var st = mc.level.getBlockState(bp);
                        boolean isOre = !st.isAir() && st.getFluidState().isEmpty()
                            && isOreBlock(st);
                        if (isOre) present++;
                        else if (st.isAir() || !st.getFluidState().isEmpty()) gone++;
                        // solid non-ore (stone etc) = our prediction missed; ignore, don't count either way
                    }
                    if (present > 0 && render.get()) {
                        for (BlockPos bp : vein) {
                            var st = mc.level.getBlockState(bp);
                            if (!st.isAir() && isOreBlock(st)) renderBlocks.add(bp);
                        }
                    }
                    // Vein counts as "mined" when most of its predicted blocks are now air.
                    if (gone >= 2 && gone > present) minedVeins++;
                }
                if (mismatch.get() && minedVeins >= mismatchThreshold.get()) {
                    flagged.add(pos);
                    if (notified.add(pos)) {
                        AutismClientMessaging.sendPrefixed("§d[SeedRay] §f" + minedVeins
                            + " mined-out veins in chunk X:" + pos.getMinBlockX() + " Z:" + pos.getMinBlockZ()
                            + " - player activity!");
                    }
                }
            }
        }
        if (render.get()) BlockEspRenderer.feed(id(), renderBlocks, oreColor.get(), false, false);
        else BlockEspRenderer.clear(id());
        com.autism.seedcracker.finder.ChunkFlagRenderer.feed(id(), flagged, flagColor.get(), false);
    }

    private static long key(ChunkPos pos) {
        return (long) pos.x() + ((long) pos.z() << 32);
    }

    /** Ore predicate without the registry string round-trip (the old getKey().contains("_ore")). */
    private static boolean isOreBlock(net.minecraft.world.level.block.state.BlockState st) {
        var b = st.getBlock();
        return b == net.minecraft.world.level.block.Blocks.DIAMOND_ORE
            || b == net.minecraft.world.level.block.Blocks.DEEPSLATE_DIAMOND_ORE
            || b == net.minecraft.world.level.block.Blocks.GOLD_ORE
            || b == net.minecraft.world.level.block.Blocks.DEEPSLATE_GOLD_ORE
            || b == net.minecraft.world.level.block.Blocks.NETHER_GOLD_ORE
            || b == net.minecraft.world.level.block.Blocks.IRON_ORE
            || b == net.minecraft.world.level.block.Blocks.DEEPSLATE_IRON_ORE
            || b == net.minecraft.world.level.block.Blocks.ANCIENT_DEBRIS;
    }

    // ---- vanilla ore populate simulation (Kiwi SeedRay doMathOnChunk, Mojang names) ----

    private record OreDef(IntProvider count, float chance, boolean depthAverage,
                          int minY, int maxY, int index, int step, int size, float discardOnAir) {}

    /** 1.18+ overworld/nether ore configs we simulate (subset: the base-hunting-relevant ores). */
    private List<OreDef> activeOres(boolean nether) {
        List<OreDef> list = new ArrayList<>();
        if (!nether) {
            if (showDiamond.get()) {
                list.add(new OreDef(ConstantInt.of(7), 1f, true, -63, 81, 18, 6, 4, 0.5f));
                list.add(new OreDef(ConstantInt.of(1), 1f / 9f, true, -63, 81, 19, 6, 12, 0.7f));
                list.add(new OreDef(ConstantInt.of(4), 1f, true, -63, 81, 20, 6, 8, 1f));
            }
            if (showGold.get()) {
                list.add(new OreDef(ConstantInt.of(4), 1f, true, -15, 49, 14, 6, 9, 0.5f));
                list.add(new OreDef(UniformInt.of(0, 1), 1f, false, -64, -47, 15, 6, 9, 0.5f));
            }
            if (showIron.get()) {
                list.add(new OreDef(ConstantInt.of(10), 1f, true, 17, 41, 12, 6, 9, 0f));
                list.add(new OreDef(ConstantInt.of(10), 1f, false, -64, 73, 13, 6, 4, 0f));
            }
        } else {
            if (showDebris.get()) {
                list.add(new OreDef(ConstantInt.of(1), 1f, true, 17, 9, 21, 7, 3, 1f));   // large debris (no_surface)
                list.add(new OreDef(ConstantInt.of(1), 1f, false, 8, 120, 22, 7, 2, 1f)); // small debris (no_surface)
            }
            if (showGold.get()) {
                list.add(new OreDef(ConstantInt.of(10), 1f, false, 10, 118, 19, 7, 10, 0f));
            }
        }
        return list;
    }

    /** Predicted ore veins for a chunk: each vein is the set of block positions it would occupy. */
    private List<Set<BlockPos>> simulateChunk(Minecraft mc, ChunkPos chunkPos) {
        List<Set<BlockPos>> veins = new ArrayList<>();
        boolean nether = mc.level.dimension() == net.minecraft.world.level.Level.NETHER;
        int chunkX = chunkPos.getMinBlockX();
        int chunkZ = chunkPos.getMinBlockZ();

        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0));
        long populationSeed = random.setDecorationSeed(worldSeed, chunkX, chunkZ);

        for (OreDef ore : activeOres(nether)) {
            random.setFeatureSeed(populationSeed, ore.index(), ore.step());
            int repeat = ore.count().sample(random);
            for (int i = 0; i < repeat; i++) {
                if (ore.chance() != 1f && random.nextFloat() >= ore.chance()) continue;
                int x = random.nextInt(16) + chunkX;
                int z = random.nextInt(16) + chunkZ;
                int y = ore.depthAverage()
                    ? random.nextInt(ore.maxY()) + random.nextInt(ore.maxY()) - ore.maxY()
                    : random.nextInt(ore.maxY() - ore.minY());
                y += ore.minY();
                Set<BlockPos> vein = generateVein(random, new BlockPos(x, y, z), ore.size());
                if (vein.size() >= 2) veins.add(vein);
            }
        }
        return veins;
    }

    /** Mojang OreFeature vein-shape math (Kiwi generateVeinPart, positions only). */
    private Set<BlockPos> generateVein(RandomSource random, BlockPos origin, int veinSize) {
        Set<BlockPos> out = new HashSet<>();
        float f = random.nextFloat() * (float) Math.PI;
        float g = (float) veinSize / 8.0f;
        double d = origin.getX() + Math.sin(f) * g;
        double e = origin.getX() - Math.sin(f) * g;
        double h = origin.getZ() + Math.cos(f) * g;
        double j = origin.getZ() - Math.cos(f) * g;
        double l = origin.getY() + random.nextInt(3) - 2;
        double m = origin.getY() + random.nextInt(3) - 2;

        double[] ds = new double[veinSize * 4];
        for (int n = 0; n < veinSize; ++n) {
            float t = (float) n / (float) veinSize;
            double p = Mth.lerp(t, d, e);
            double q = Mth.lerp(t, l, m);
            double rr = Mth.lerp(t, h, j);
            double s = random.nextDouble() * veinSize / 16.0;
            double radius = ((Mth.sin((float) Math.PI * t) + 1.0f) * s + 1.0) / 2.0;
            ds[n * 4] = p;
            ds[n * 4 + 1] = q;
            ds[n * 4 + 2] = rr;
            ds[n * 4 + 3] = radius;
        }
        for (int n = 0; n < veinSize - 1; ++n) {
            if (ds[n * 4 + 3] <= 0.0) continue;
            for (int o = n + 1; o < veinSize; ++o) {
                if (ds[o * 4 + 3] <= 0.0) continue;
                double p = ds[n * 4] - ds[o * 4];
                double q = ds[n * 4 + 1] - ds[o * 4 + 1];
                double rr = ds[n * 4 + 2] - ds[o * 4 + 2];
                double s = ds[n * 4 + 3] - ds[o * 4 + 3];
                if (s * s > p * p + q * q + rr * rr) {
                    if (s > 0.0) ds[o * 4 + 3] = -1.0; else ds[n * 4 + 3] = -1.0;
                }
            }
        }
        for (int n = 0; n < veinSize; ++n) {
            double u = ds[n * 4 + 3];
            if (u < 0.0) continue;
            double v = ds[n * 4];
            double w = ds[n * 4 + 1];
            double aa = ds[n * 4 + 2];
            int ab = Mth.floor(v - u);
            int ac = Mth.floor(w - u);
            int ad = Mth.floor(aa - u);
            int ae = Math.max(Mth.floor(v + u), ab);
            int af = Math.max(Mth.floor(w + u), ac);
            int ag = Math.max(Mth.floor(aa + u), ad);
            for (int ah = ab; ah <= ae; ++ah) {
                double ai = (ah + 0.5 - v) / u;
                if (ai * ai >= 1.0) continue;
                for (int aj = ac; aj <= af; ++aj) {
                    double ak = (aj + 0.5 - w) / u;
                    if (ai * ai + ak * ak >= 1.0) continue;
                    for (int al = ad; al <= ag; ++al) {
                        double am = (al + 0.5 - aa) / u;
                        if (ai * ai + ak * ak + am * am < 1.0) {
                            out.add(new BlockPos(ah, aj, al));
                        }
                    }
                }
            }
        }
        return out;
    }

    @Override
    public String info() {
        if (!haveSeed) return "waiting for seed";
        return flagged.size() + " flagged";
    }
}
