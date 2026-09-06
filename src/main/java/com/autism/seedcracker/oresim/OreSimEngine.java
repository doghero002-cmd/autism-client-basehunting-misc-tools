package com.autism.seedcracker.oresim;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.phys.Vec3;

/**
 * OreSim engine: computes where ores *should* generate in each chunk from the world seed + the
 * biome's world-gen ore placement config, using the same decoration/feature seed math vanilla
 * uses. This is a world-gen simulation (legitimate seed math), not a packet/render bypass.
 *
 * Results are cached per chunk as ore-id -> set of block positions, for the renderer to draw.
 *
 * Port of the meteor-rejects OreSim chunk math (anticope.rejects.modules.OreSim) to Mojang 26.2.
 */
public final class OreSimEngine {

    /** chunkKey -> (oreId -> set of block positions). */
    private static final Map<Long, Map<Ore, Set<BlockPos>>> chunkOres = new ConcurrentHashMap<>();

    private OreSimEngine() {}

    public static Map<Long, Map<Ore, Set<BlockPos>>> chunkOres() {
        return chunkOres;
    }

    public static void clear() {
        chunkOres.clear();
    }

    /** Remove a position (called when the block there becomes air / is mined). */
    public static void removeAt(BlockPos pos) {
        long key = ((long) (pos.getX() >> 4) << 32) | ((pos.getZ() >> 4) & 0xffffffffL);
        Map<Ore, Set<BlockPos>> chunk = chunkOres.get(key);
        if (chunk == null) return;
        for (Set<BlockPos> set : chunk.values()) set.remove(pos);
    }

    /** Compute the ore positions for a freshly loaded chunk. */
    public static void processChunk(ChunkAccess chunk, Ore.Dim dimension, Map<ResourceKey<Biome>, List<Ore>> oreConfig, long worldSeed) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel world = mc.level;
        if (world == null || oreConfig == null) return;

        ChunkPos chunkPos = chunk.getPos();
        long chunkKey = ((long) chunkPos.x() << 32) | (chunkPos.z() & 0xffffffffL);
        if (chunkOres.containsKey(chunkKey)) return;

        // Collect the biome keys in this chunk + its 1-chunk border (ores depend on surrounding biomes).
        Set<ResourceKey<Biome>> biomes = new HashSet<>();
        ChunkPos.rangeClosed(chunkPos, 1).forEach(cp -> {
            ChunkAccess neighbor = world.getChunk(cp.x(), cp.z(), ChunkStatus.BIOMES, false);
            if (neighbor == null) return;
            for (LevelChunkSection section : neighbor.getSections()) {
                section.getBiomes().getAll(entry -> entry.unwrapKey().ifPresent(biomes::add));
            }
        });
        Set<Ore> oreSet = biomes.stream()
            .flatMap(b -> getOresForBiome(oreConfig, b).stream())
            .collect(Collectors.toSet());

        int chunkX = chunkPos.x() << 4;
        int chunkZ = chunkPos.z() << 4;
        WorldgenRandom random = new WorldgenRandom(WorldgenRandom.Algorithm.XOROSHIRO.newInstance(0));

        long populationSeed = random.setDecorationSeed(worldSeed, chunkX, chunkZ);
        Map<Ore, Set<BlockPos>> result = new HashMap<>();

        for (Ore ore : oreSet) {
            Set<BlockPos> positions = new HashSet<>();
            random.setFeatureSeed(populationSeed, ore.index, ore.step);
            int repeat = ore.count.sample(random);

            for (int i = 0; i < repeat; i++) {
                if (ore.rarity != 1F && random.nextFloat() >= 1.0F / ore.rarity) continue;

                int x = random.nextInt(16) + chunkX;
                int z = random.nextInt(16) + chunkZ;
                int y = ore.heightProvider.sample(random, ore.heightContext);
                BlockPos origin = new BlockPos(x, y, z);

                ResourceKey<Biome> biome = chunk.getNoiseBiome(x, y, z).unwrapKey().orElse(null);
                if (biome == null || !getOresForBiome(oreConfig, biome).contains(ore)) continue;

                if (ore.scattered) {
                    positions.addAll(generateHidden(world, random, origin, ore.size));
                } else {
                    positions.addAll(generateNormal(world, random, origin, ore.size, ore.discardOnAirChance));
                }
            }
            if (!positions.isEmpty()) result.put(ore, positions);
        }
        chunkOres.put(chunkKey, result);
    }

    private static List<Ore> getOresForBiome(Map<ResourceKey<Biome>, List<Ore>> oreConfig, ResourceKey<Biome> key) {
        List<Ore> list = oreConfig.get(key);
        if (list != null) return list;
        return oreConfig.values().stream().findAny().orElse(List.of());
    }

    // ---- vanilla vein-generation math (Mojang's OreFeature) ----

    private static List<BlockPos> generateNormal(ClientLevel world, WorldgenRandom random, BlockPos blockPos, int veinSize, float discardOnAir) {
        float f = random.nextFloat() * (float) Math.PI;
        float g = (float) veinSize / 8.0F;
        int i = Mth.ceil(((float) veinSize / 16.0F * 2.0F + 1.0F) / 2.0F);
        double d = blockPos.getX() + Math.sin(f) * (double) g;
        double e = blockPos.getX() - Math.sin(f) * (double) g;
        double h = blockPos.getZ() + Math.cos(f) * (double) g;
        double j = blockPos.getZ() - Math.cos(f) * (double) g;
        double l = blockPos.getY() + random.nextInt(3) - 2;
        double m = blockPos.getY() + random.nextInt(3) - 2;
        int n = blockPos.getX() - Mth.ceil(g) - i;
        int o = blockPos.getY() - 2 - i;
        int p = blockPos.getZ() - Mth.ceil(g) - i;
        int q = 2 * (Mth.ceil(g) + i);
        int r = 2 * (2 + i);

        for (int s = n; s <= n + q; ++s) {
            for (int t = p; t <= p + q; ++t) {
                if (o <= world.getHeight(Heightmap.Types.MOTION_BLOCKING, s, t)) {
                    return generateVeinPart(world, random, veinSize, d, e, h, j, l, m, n, o, p, q, r, discardOnAir);
                }
            }
        }
        return new ArrayList<>();
    }

    private static List<BlockPos> generateVeinPart(ClientLevel world, WorldgenRandom random, int veinSize,
            double startX, double endX, double startZ, double endZ, double startY, double endY,
            int x, int y, int z, int size, int i, float discardOnAir) {
        BitSet bitSet = new BitSet(size * i * size);
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        double[] ds = new double[veinSize * 4];
        List<BlockPos> poses = new ArrayList<>();

        for (int n = 0; n < veinSize; ++n) {
            float f = (float) n / (float) veinSize;
            double p = Mth.lerp(f, startX, endX);
            double q = Mth.lerp(f, startY, endY);
            double r = Mth.lerp(f, startZ, endZ);
            double s = random.nextDouble() * (double) veinSize / 16.0D;
            double m = ((double) (Mth.sin((float) Math.PI * f) + 1.0F) * s + 1.0D) / 2.0D;
            ds[n * 4] = p;
            ds[n * 4 + 1] = q;
            ds[n * 4 + 2] = r;
            ds[n * 4 + 3] = m;
        }

        for (int n = 0; n < veinSize - 1; ++n) {
            if (!(ds[n * 4 + 3] <= 0.0D)) {
                for (int o = n + 1; o < veinSize; ++o) {
                    if (!(ds[o * 4 + 3] <= 0.0D)) {
                        double p = ds[n * 4] - ds[o * 4];
                        double q = ds[n * 4 + 1] - ds[o * 4 + 1];
                        double r = ds[n * 4 + 2] - ds[o * 4 + 2];
                        double s = ds[n * 4 + 3] - ds[o * 4 + 3];
                        if (s * s > p * p + q * q + r * r) {
                            if (s > 0.0D) ds[o * 4 + 3] = -1.0D;
                            else ds[n * 4 + 3] = -1.0D;
                        }
                    }
                }
            }
        }

        for (int n = 0; n < veinSize; ++n) {
            double u = ds[n * 4 + 3];
            if (!(u < 0.0D)) {
                double v = ds[n * 4];
                double w = ds[n * 4 + 1];
                double aa = ds[n * 4 + 2];
                int ab = Math.max(Mth.floor(v - u), x);
                int ac = Math.max(Mth.floor(w - u), y);
                int ad = Math.max(Mth.floor(aa - u), z);
                int ae = Math.max(Mth.floor(v + u), ab);
                int af = Math.max(Mth.floor(w + u), ac);
                int ag = Math.max(Mth.floor(aa + u), ad);

                for (int ah = ab; ah <= ae; ++ah) {
                    double ai = ((double) ah + 0.5D - v) / u;
                    if (ai * ai < 1.0D) {
                        for (int aj = ac; aj <= af; ++aj) {
                            double ak = ((double) aj + 0.5D - w) / u;
                            if (ai * ai + ak * ak < 1.0D) {
                                for (int al = ad; al <= ag; ++al) {
                                    double am = ((double) al + 0.5D - aa) / u;
                                    if (ai * ai + ak * ak + am * am < 1.0D) {
                                        int an = ah - x + (aj - y) * size + (al - z) * size * i;
                                        if (!bitSet.get(an)) {
                                            bitSet.set(an);
                                            mutable.set(ah, aj, al);
                                            if (aj >= -64 && aj < 320 && world.getBlockState(mutable).canOcclude()) {
                                                if (shouldPlace(world, mutable, discardOnAir, random)) {
                                                    poses.add(new BlockPos(ah, aj, al));
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return poses;
    }

    private static boolean shouldPlace(ClientLevel world, BlockPos orePos, float discardOnAir, WorldgenRandom random) {
        if (discardOnAir == 0F || (discardOnAir != 1F && random.nextFloat() >= discardOnAir)) return true;
        for (Direction direction : Direction.values()) {
            if (!world.getBlockState(orePos.relative(direction)).canOcclude() && discardOnAir != 1F) return false;
        }
        return true;
    }

    private static List<BlockPos> generateHidden(ClientLevel world, WorldgenRandom random, BlockPos blockPos, int size) {
        List<BlockPos> poses = new ArrayList<>();
        int i = random.nextInt(size + 1);
        for (int j = 0; j < i; ++j) {
            int s = Math.min(j, 7);
            int x = randomCoord(random, s) + blockPos.getX();
            int y = randomCoord(random, s) + blockPos.getY();
            int z = randomCoord(random, s) + blockPos.getZ();
            BlockPos pos = new BlockPos(x, y, z);
            if (world.getBlockState(pos).canOcclude() && shouldPlace(world, pos, 1F, random)) {
                poses.add(pos);
            }
        }
        return poses;
    }

    private static int randomCoord(WorldgenRandom random, int size) {
        return Math.round((random.nextFloat() - random.nextFloat()) * (float) size);
    }
}
