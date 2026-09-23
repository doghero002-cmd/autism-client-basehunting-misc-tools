package kaptainwutax.seedcrackerX.finder;

import com.seedfinding.mcbiome.biome.Biomes;
import kaptainwutax.seedcrackerX.SeedCracker;
import kaptainwutax.seedcrackerX.config.Config;
import kaptainwutax.seedcrackerX.cracker.BiomeData;
import kaptainwutax.seedcrackerX.cracker.DataAddedEvent;
import kaptainwutax.seedcrackerX.render.Cuboid;
import kaptainwutax.seedcrackerX.util.BiomeFixer;
import kaptainwutax.seedcrackerX.util.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.ArrayList;
import java.util.List;

/**
 * Nether biome sampler: collects 3D multi-noise biome samples that the TimeMachine verifies
 * against a NetherBiomeSource, letting the world seed be finished from structure seeds without
 * ever visiting the overworld. Nether biomes are 3D noise (1.16+), so samples are taken at the
 * player-relevant Y band rather than sea level.
 */
public class NetherBiomeFinder extends Finder {

    public NetherBiomeFinder(Level world, ChunkPos chunkPos) {
        super(world, chunkPos);
    }

    public static List<Finder> create(Level world, ChunkPos chunkPos) {
        List<Finder> finders = new ArrayList<>();
        finders.add(new NetherBiomeFinder(world, chunkPos));
        return finders;
    }

    @Override
    public List<BlockPos> findInChunk() {
        List<BlockPos> result = new ArrayList<>();

        for (int x = 0; x < 16; x += 8) {
            for (int z = 0; z < 16; z += 8) {
                BlockPos blockPos = this.chunkPos.getWorldPosition().offset(x, 64, z);
                // Quart coords (>> 2) like the overworld path; Y=16 quarts = Y64 blocks, the
                // middle of the nether's playable band where the client has real biome data.
                Biome biome = this.world.getNoiseBiome(blockPos.getX() >> 2, 16, blockPos.getZ() >> 2).value();
                com.seedfinding.mcbiome.biome.Biome otherBiome = BiomeFixer.swap(biome);
                if (otherBiome == Biomes.THE_VOID) continue;
                // Only trust proper nether biomes (dimension guard for weird server worlds).
                if (otherBiome != Biomes.NETHER_WASTES && otherBiome != Biomes.SOUL_SAND_VALLEY
                    && otherBiome != Biomes.CRIMSON_FOREST && otherBiome != Biomes.WARPED_FOREST
                    && otherBiome != Biomes.BASALT_DELTAS) {
                    continue;
                }

                BiomeData data = new BiomeData(otherBiome, blockPos.getX() >> 2, 16, blockPos.getZ() >> 2, true);
                if (SeedCracker.get().getDataStorage().addBiomeData(data, DataAddedEvent.POKE_BIOMES)) {
                    if (Config.get().debug) Log.warn(blockPos.toShortString() + ", " + otherBiome.getName());
                    result.add(blockPos);
                }
            }
        }
        result.forEach(pos -> this.cuboids.add(new Cuboid(pos, ARGB.color(204, 51, 51))));

        return result;
    }

    @Override
    public boolean isValidDimension(DimensionType dimension) {
        return this.isNether(dimension);
    }
}
