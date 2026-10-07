package com.autism.seedcracker.seedmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.seedfinding.mcbiome.source.BiomeSource;
import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.state.Dimension;
import com.seedfinding.mccore.util.pos.CPos;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcfeature.structure.BastionRemnant;
import com.seedfinding.mcfeature.structure.BuriedTreasure;
import com.seedfinding.mcfeature.structure.DesertPyramid;
import com.seedfinding.mcfeature.structure.EndCity;
import com.seedfinding.mcfeature.structure.Fortress;
import com.seedfinding.mcfeature.structure.Igloo;
import com.seedfinding.mcfeature.structure.JunglePyramid;
import com.seedfinding.mcfeature.structure.Mansion;
import com.seedfinding.mcfeature.structure.Monument;
import com.seedfinding.mcfeature.structure.OceanRuin;
import com.seedfinding.mcfeature.structure.PillagerOutpost;
import com.seedfinding.mcfeature.structure.RegionStructure;
import com.seedfinding.mcfeature.structure.RuinedPortal;
import com.seedfinding.mcfeature.structure.Shipwreck;
import com.seedfinding.mcfeature.structure.Stronghold;
import com.seedfinding.mcfeature.structure.SwampHut;
import com.seedfinding.mcfeature.structure.Village;

/**
 * Structure positions predicted from a world seed. Region structures are placed per spacing
 * region, so the nearest ones come from scanning the regions around the player and keeping those
 * whose start chunk passes the biome check. Results are cached per (type, region); the biome
 * source is the expensive part and is built once per seed. Strongholds use the ring algorithm.
 *
 * Seed-predicted only: a structure can still be missing if terrain blocked it (e.g. a mansion on a
 * cliff), and servers with custom world gen won't match at all.
 *
 * Not thread-safe (biome sources keep internal caches): use one instance from one worker thread.
 */
public final class SeedStructures {

    public enum Type {
        VILLAGE("Village", 0xFF6FCF4A, Dimension.OVERWORLD),
        STRONGHOLD("Stronghold", 0xFFB45CFF, Dimension.OVERWORLD),
        MANSION("Mansion", 0xFF8B5A2B, Dimension.OVERWORLD),
        MONUMENT("Monument", 0xFF2FB6C9, Dimension.OVERWORLD),
        OUTPOST("Outpost", 0xFF9A9A9A, Dimension.OVERWORLD),
        DESERT_TEMPLE("Desert Temple", 0xFFE8C76A, Dimension.OVERWORLD),
        JUNGLE_TEMPLE("Jungle Temple", 0xFF3E8F3E, Dimension.OVERWORLD),
        SWAMP_HUT("Swamp Hut", 0xFF4F6B3A, Dimension.OVERWORLD),
        IGLOO("Igloo", 0xFFDDEEFF, Dimension.OVERWORLD),
        SHIPWRECK("Shipwreck", 0xFF7A5C3A, Dimension.OVERWORLD),
        OCEAN_RUIN("Ocean Ruin", 0xFF5C8A8A, Dimension.OVERWORLD),
        BURIED_TREASURE("Buried Treasure", 0xFFFFD700, Dimension.OVERWORLD),
        RUINED_PORTAL("Ruined Portal", 0xFFA040FF, Dimension.OVERWORLD),
        FORTRESS("Fortress", 0xFFB03030, Dimension.NETHER),
        BASTION("Bastion", 0xFF404040, Dimension.NETHER),
        NETHER_PORTAL_RUIN("Nether Ruined Portal", 0xFFA040FF, Dimension.NETHER),
        END_CITY("End City", 0xFFE0D0FF, Dimension.END);

        public final String label;
        public final int color;
        public final Dimension dim;

        Type(String label, int color, Dimension dim) {
            this.label = label;
            this.color = color;
            this.dim = dim;
        }
    }

    public record Hit(Type type, int blockX, int blockZ, double dist) {}

    private final long seed;
    private final MCVersion version;
    private final Map<Dimension, BiomeSource> biomes = new EnumMap<>(Dimension.class);
    private final Map<Type, RegionStructure<?, ?>> features = new EnumMap<>(Type.class);
    // (type, regionX, regionZ) -> block pos packed, or Long.MIN_VALUE when that region has none
    private final Map<Type, Map<Long, Long>> regionCache = new ConcurrentHashMap<>();
    private List<CPos> strongholds;

    public SeedStructures(long seed, MCVersion version) {
        this.seed = seed;
        this.version = version;
    }

    public long seed() {
        return seed;
    }

    private BiomeSource biome(Dimension d) {
        return biomes.computeIfAbsent(d, dim -> BiomeSource.of(dim, version, seed));
    }

    private RegionStructure<?, ?> feature(Type t) {
        return features.computeIfAbsent(t, type -> switch (type) {
            case VILLAGE -> new Village(version);
            case MANSION -> new Mansion(version);
            case MONUMENT -> new Monument(version);
            case OUTPOST -> new PillagerOutpost(version);
            case DESERT_TEMPLE -> new DesertPyramid(version);
            case JUNGLE_TEMPLE -> new JunglePyramid(version);
            case SWAMP_HUT -> new SwampHut(version);
            case IGLOO -> new Igloo(version);
            case SHIPWRECK -> new Shipwreck(version);
            case OCEAN_RUIN -> new OceanRuin(version);
            case BURIED_TREASURE -> new BuriedTreasure(version);
            case RUINED_PORTAL -> new RuinedPortal(Dimension.OVERWORLD, version);
            case NETHER_PORTAL_RUIN -> new RuinedPortal(Dimension.NETHER, version);
            case FORTRESS -> new Fortress(version);
            case BASTION -> new BastionRemnant(version);
            case END_CITY -> new EndCity(version);
            case STRONGHOLD -> null;
        });
    }

    /**
     * Up to {@code count} nearest structures of each type in {@code types} around block (x, z),
     * scanning at most {@code maxRegions} spacing-regions out in each direction.
     */
    public List<Hit> nearest(List<Type> types, Dimension dim, int x, int z, int count, int maxRegions) {
        List<Hit> out = new ArrayList<>();
        for (Type t : types) {
            if (t.dim != dim) continue;
            try {
                out.addAll(t == Type.STRONGHOLD ? nearestStrongholds(x, z, count) : nearestRegion(t, x, z, count, maxRegions));
            } catch (Throwable ignored) {
                // A version the library can't model for this structure: skip it rather than break the map.
            }
        }
        out.sort(Comparator.comparingDouble(Hit::dist));
        return out;
    }

    private List<Hit> nearestRegion(Type t, int x, int z, int count, int maxRegions) {
        RegionStructure<?, ?> f = feature(t);
        if (f == null) return List.of();
        int spacingBlocks = f.getSpacing() * 16;
        int rx0 = Math.floorDiv(x, spacingBlocks), rz0 = Math.floorDiv(z, spacingBlocks);
        Map<Long, Long> cache = regionCache.computeIfAbsent(t, k -> new ConcurrentHashMap<>());
        ChunkRand rand = new ChunkRand();
        BiomeSource src = biome(t.dim);
        List<Hit> found = new ArrayList<>();
        for (int ring = 0; ring <= maxRegions; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int rx = rx0 + dx, rz = rz0 + dz;
                    long key = (long) rx << 32 | (rz & 0xFFFFFFFFL);
                    Long packed = cache.get(key);
                    if (packed == null) {
                        packed = locate(f, src, rx, rz, rand);
                        cache.put(key, packed);
                    }
                    if (packed == Long.MIN_VALUE) continue;
                    int bx = (int) (packed >> 32), bz = (int) (long) packed;
                    found.add(new Hit(t, bx, bz, Math.hypot(bx - x, bz - z)));
                }
            }
            // Once we have enough, one more ring can't contain anything closer than the ring edge.
            if (found.size() >= count && ring * spacingBlocks > kthDistance(found, count)) break;
        }
        found.sort(Comparator.comparingDouble(Hit::dist));
        return found.size() > count ? new ArrayList<>(found.subList(0, count)) : found;
    }

    private static double kthDistance(List<Hit> hits, int k) {
        List<Double> d = new ArrayList<>();
        for (Hit h : hits) d.add(h.dist());
        d.sort(Double::compare);
        return d.get(Math.min(k, d.size()) - 1);
    }

    private long locate(RegionStructure<?, ?> f, BiomeSource src, int rx, int rz, ChunkRand rand) {
        CPos c = f.getInRegion(seed, rx, rz, rand);
        if (c == null || !f.canSpawn(c.getX(), c.getZ(), src)) return Long.MIN_VALUE;
        int bx = (c.getX() << 4) + 8, bz = (c.getZ() << 4) + 8;
        return (long) bx << 32 | (bz & 0xFFFFFFFFL);
    }

    private List<Hit> nearestStrongholds(int x, int z, int count) {
        if (strongholds == null) {
            Stronghold sh = new Stronghold(version);
            CPos[] starts = sh.getAllStarts(biome(Dimension.OVERWORLD), new com.seedfinding.mcseed.rand.JRand(0));
            strongholds = starts == null ? List.of() : List.of(starts);
        }
        List<Hit> out = new ArrayList<>();
        for (CPos c : strongholds) {
            int bx = (c.getX() << 4) + 4, bz = (c.getZ() << 4) + 4;
            out.add(new Hit(Type.STRONGHOLD, bx, bz, Math.hypot(bx - x, bz - z)));
        }
        out.sort(Comparator.comparingDouble(Hit::dist));
        return out.size() > count ? new ArrayList<>(out.subList(0, count)) : out;
    }

    /** Biome category at a block, for the minimap's background colour. */
    public int biomeColor(Dimension dim, int x, int z) {
        try {
            var b = biome(dim).getBiome(x, 64, z);
            return b == null ? 0xFF202020 : BiomeColors.of(b);
        } catch (Throwable t) {
            return 0xFF202020;
        }
    }

    /** Parse a comma list of type names ("village,stronghold"); unknown names are ignored. */
    public static List<Type> parse(String csv) {
        Map<String, Type> byName = new HashMap<>();
        for (Type t : Type.values()) {
            byName.put(t.name().toLowerCase(java.util.Locale.ROOT), t);
            byName.put(t.label.toLowerCase(java.util.Locale.ROOT).replace(' ', '_'), t);
        }
        List<Type> out = new ArrayList<>();
        for (String s : csv.split("[,|]")) {
            Type t = byName.get(s.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_'));
            if (t != null && !out.contains(t)) out.add(t);
        }
        return out;
    }
}
