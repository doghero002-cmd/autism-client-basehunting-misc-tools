package kaptainwutax.seedcrackerX.finder.structure;

import com.seedfinding.mcfeature.structure.RegionStructure;
import kaptainwutax.seedcrackerX.Features;
import kaptainwutax.seedcrackerX.SeedCracker;
import kaptainwutax.seedcrackerX.cracker.DataAddedEvent;
import kaptainwutax.seedcrackerX.finder.Finder;
import kaptainwutax.seedcrackerX.render.Cuboid;
import kaptainwutax.seedcrackerX.util.BiomeFixer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.ArrayList;
import java.util.List;

/**
 * Nether fossil finder: fossils are region structures (one attempt per chunk region) built from
 * bone blocks in soul sand valleys, so a found fossil pins region-seed bits exactly like a
 * buried treasure does in the overworld. Bone blocks never generate naturally in the nether
 * outside fossils, which makes detection nearly free: any bone block in a soul-sand-valley chunk
 * above the lava sea is fossil evidence.
 */
public class NetherFossilFinder extends Finder {

    public NetherFossilFinder(Level world, ChunkPos chunkPos) {
        super(world, chunkPos);
    }

    public static List<Finder> create(Level world, ChunkPos chunkPos) {
        List<Finder> finders = new ArrayList<>();
        finders.add(new NetherFossilFinder(world, chunkPos));
        return finders;
    }

    @Override
    public List<BlockPos> findInChunk() {
        if (Features.NETHER_FOSSIL == null) return new ArrayList<>();
        Biome biome = this.world.getNoiseBiome((this.chunkPos.x() << 2) + 2, 8, (this.chunkPos.z() << 2) + 2).value();
        if (!Features.NETHER_FOSSIL.isValidBiome(BiomeFixer.swap(biome))) return new ArrayList<>();

        // Fossils generate on the valley floor between the lava sea (31) and ~Y70; a sparse
        // section scan for bone blocks is cheap because valleys are mostly air/soul sand.
        List<BlockPos> result = new ArrayList<>();
        ChunkAccess chunk = this.world.getChunk(this.chunkPos.getWorldPosition());
        BlockState boneBlock = Blocks.BONE_BLOCK.defaultBlockState();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        outer:
        for (int y = 32; y <= 70; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    m.set(x, y, z);
                    BlockState state = chunk.getBlockState(m);
                    if (state.getBlock() == boneBlock.getBlock()) {
                        result.add(this.chunkPos.getWorldPosition().offset(x, y, z));
                        break outer; // one hit proves the fossil; the region data is per-chunk
                    }
                }
            }
        }
        if (result.isEmpty()) return result;

        RegionStructure.Data<?> data = Features.NETHER_FOSSIL.at(this.chunkPos.x(), this.chunkPos.z());
        if (SeedCracker.get().getDataStorage().addBaseData(data, DataAddedEvent.POKE_STRUCTURES)) {
            for (BlockPos pos : result) {
                this.cuboids.add(new Cuboid(pos, ARGB.color(220, 220, 180)));
            }
        }
        return result;
    }

    @Override
    public boolean isValidDimension(DimensionType dimension) {
        return this.isNether(dimension);
    }
}
