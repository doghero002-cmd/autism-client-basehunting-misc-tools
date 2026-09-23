package kaptainwutax.seedcrackerX.cracker;

import com.seedfinding.mcbiome.biome.Biome;
import com.seedfinding.mcbiome.source.BiomeSource;
import com.seedfinding.mccore.version.MCVersion;
import kaptainwutax.seedcrackerX.config.Config;

public class BiomeData {

    public final Biome biome;
    public final int x;
    public final int z;
    /** Nether entries are verified against a NetherBiomeSource (y matters for 3D noise). */
    public final boolean nether;
    public final int y;

    public BiomeData(Biome biome, int x, int z) {
        this(biome, x, 0, z, false);
    }

    public BiomeData(Biome biome, int x, int y, int z, boolean nether) {
        this.biome = biome;
        this.x = x;
        this.y = y;
        this.z = z;
        this.nether = nether;
    }

    public boolean test(BiomeSource source) {
        if (this.nether) {
            return source.getBiomeForNoiseGen(this.x, this.y, this.z) == this.biome;
        }
        if (Config.get().getVersion().isNewerOrEqualTo(MCVersion.v1_15)) {
            return source.getBiomeForNoiseGen(this.x, 0, this.z) == this.biome;
        } else {
            return source.getBiome(this.x, 0, this.z) == this.biome;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BiomeData)) return false;
        BiomeData data = (BiomeData) o;
        // Position must participate: the set holds one sample PER location, and same-biome
        // samples at different coords are independent evidence (the biome search needs many).
        return this.biome == data.biome && this.x == data.x && this.y == data.y && this.z == data.z
            && this.nether == data.nether;
    }

    @Override
    public int hashCode() {
        return this.biome.getName().hashCode() * 31 ^ this.x * 7 ^ this.y * 3 ^ this.z
            ^ (this.nether ? 1 << 16 : 0);
    }
}
