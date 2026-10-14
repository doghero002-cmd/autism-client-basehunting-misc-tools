package com.autism.seedcracker.finder;

import com.autism.seedcracker.util.pure.PrimeLight;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

public final class GeodeGlowSnapshot {
    private GeodeGlowSnapshot() {}

    public static Integer scan(ClientLevel world, LevelChunk chunk) {
        int chunkX = chunk.getPos().x(), chunkZ = chunk.getPos().z();
        byte[][] layers = new byte[72][];
        var listener = world.getChunkSource().getLightEngine().getLayerListener(LightLayer.BLOCK);
        boolean any = false;
        for (int offsetX = -1; offsetX <= 1; offsetX++) for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
            if (!world.hasChunk(chunkX + offsetX, chunkZ + offsetZ)) return null;
            for (int section = 0; section < 8; section++) {
                var light = listener.getDataLayerData(SectionPos.of(chunkX + offsetX, -5 + section, chunkZ + offsetZ));
                if (light != null && !light.isEmpty()) { layers[slot(offsetX, offsetZ, section)] = light.getData().clone(); any = true; }
            }
        }
        if (!any) return 0;
        int count = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int sectionY = -4; sectionY <= 1; sectionY++) {
            int index = chunk.getSectionIndex(sectionY << 4);
            if (index < 0 || index >= chunk.getSectionsCount() || !nearLight(layers, sectionY)) continue;
            var section = chunk.getSection(index);
            if (section == null || !section.maybeHas(GeodeGlowSnapshot::open)) continue;
            for (int y = Math.max(-58, sectionY << 4); y <= Math.min(30, (sectionY << 4) + 15); y++) {
                for (int localZ = 0; localZ < 16; localZ++) for (int localX = 0; localX < 16; localX++) {
                    if (!open(section.getBlockState(localX, y & 15, localZ))) continue;
                    int x = (chunkX << 4) + localX, z = (chunkZ << 4) + localZ;
                    if (lightAt(layers, chunkX, chunkZ, x, y, z) != 0) continue;
                    boolean opening = false, tooBright = false;
                    for (Direction side : Direction.values()) {
                        int nx = x + side.getStepX(), ny = y + side.getStepY(), nz = z + side.getStepZ();
                        int value = lightAt(layers, chunkX, chunkZ, nx, ny, nz);
                        if (value > 4) { tooBright = true; break; }
                        if (value == 4 && !opening && open(world.getBlockState(cursor.set(nx, ny, nz)))) opening = true;
                    }
                    if (opening && !tooBright) count++;
                }
            }
        }
        return count;
    }

    static int slot(int offsetX, int offsetZ, int section) { return ((offsetX + 1) * 3 + offsetZ + 1) * 8 + section; }

    static int lightAt(byte[][] layers, int chunkX, int chunkZ, int x, int y, int z) {
        int section = (y >> 4) + 5, offsetX = (x >> 4) - chunkX, offsetZ = (z >> 4) - chunkZ;
        if (section < 0 || section >= 8 || offsetX < -1 || offsetX > 1 || offsetZ < -1 || offsetZ > 1) return 0;
        byte[] data = layers[slot(offsetX, offsetZ, section)];
        return data == null ? 0 : PrimeLight.value(data, ((y & 15) << 8) | ((z & 15) << 4) | (x & 15));
    }

    private static boolean nearLight(byte[][] layers, int sectionY) {
        for (int offsetX = -1; offsetX <= 1; offsetX++) for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
            for (int section = sectionY - 1; section <= sectionY + 1; section++) if (layers[slot(offsetX, offsetZ, section + 5)] != null) return true;
        }
        return false;
    }

    private static boolean open(BlockState state) { return state.isAir() || state.is(Blocks.AMETHYST_CLUSTER); }
}