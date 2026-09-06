package kaptainwutax.seedcrackerX.finder;

import kaptainwutax.seedcrackerX.finder.decorator.EndPillarsFinder;
import kaptainwutax.seedcrackerX.finder.decorator.ore.EmeraldOreFinder;
import kaptainwutax.seedcrackerX.finder.structure.*;
import kaptainwutax.seedcrackerX.util.HeightContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

public class ReloadFinders {
    public Minecraft client = Minecraft.getInstance();

    public static void reloadHeight(int minY, int maxY) {
        Finder.CHUNK_POSITIONS.clear();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    Finder.CHUNK_POSITIONS.add(new BlockPos(x, y, z));
                }
            }
        }
        Finder.heightContext = new HeightContext(minY, maxY);

        EmeraldOreFinder.reloadSearchPositions();
        EndPillarsFinder.BedrockMarkerFinder.reloadSearchPositions();
        AbstractTempleFinder.reloadSearchPositions();
        BuriedTreasureFinder.reloadSearchPositions();
        EndCityFinder.reloadSearchPositions();
        MonumentFinder.reloadSearchPositions();
        OutpostFinder.reloadSearchPositions();
        IglooFinder.reloadSearchPositions();
        TrialChambersFinder.reloadSearchPositions();
    }

    public void reload() {
        int renderdistance = client.options.renderDistance().get();

        int playerChunkX = (int) (Math.round(client.player.getX()) >> 4);
        int playerChunkZ = (int) (Math.round(client.player.getZ()) >> 4);

        // Queue the render-distance bubble on a background thread in small batches so joining a
        // world / changing dimension doesn't flood the finder pool and stall chunk loading.
        java.util.List<ChunkPos> positions = new java.util.ArrayList<>();
        for (int i = playerChunkX - renderdistance; i < playerChunkX + renderdistance; i++) {
            for (int j = playerChunkZ - renderdistance; j < playerChunkZ + renderdistance; j++) {
                positions.add(new ChunkPos(i, j));
            }
        }
        net.minecraft.world.level.Level level = client.level;
        new Thread(() -> {
            for (ChunkPos pos : positions) {
                FinderQueue.get().onChunkData(level, pos);
                try {
                    Thread.sleep(5); // ~200 chunks/sec, spreads the load instead of one burst
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "SeedCracker-ReloadFinders").start();
    }
}
