package com.autism.seedcracker.finder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class StoneStackScanner {
    private final Function<BlockPos, BlockState> states;
    private final int minimum;
    private final int baseX, baseZ;
    private final int[] top = new int[256], height = new int[256], shape = new int[256];
    private final Block[] wall = new Block[256];
    private final Set<BlockPos> stacks = new HashSet<>();
    private boolean incomplete;
    private int cursor;
    private record Area(int shape, Block wall) {}

    public StoneStackScanner(ChunkPos chunk, int minimum, Function<BlockPos, BlockState> states) {
        baseX = chunk.getMinBlockX(); baseZ = chunk.getMinBlockZ(); this.minimum = minimum; this.states = states;
        Arrays.fill(top, Integer.MIN_VALUE);
    }

    public boolean complete() { return !incomplete; }
    public Set<BlockPos> stacks() { return Set.copyOf(stacks); }

    public boolean step(long deadline) {
        while (cursor < 33 * 256 && System.nanoTime() < deadline) {
            int x = cursor & 15, z = (cursor >> 4) & 15, y = cursor >> 8;
            cursor++;
            BlockState state = get(x, y, z);
            if (state == null || !state.is(Blocks.STONE)) continue;
            Area area = area(x, y, z);
            if (area == null) continue;
            int column = (z << 4) | x;
            boolean onTop = top[column] == y - 1 && shape[column] == area.shape && wall[column] == area.wall;
            if (!onTop) keep(column);
            height[column] = onTop ? height[column] + 1 : 1;
            top[column] = y; shape[column] = area.shape; wall[column] = area.wall;
        }
        if (cursor < 33 * 256) return false;
        for (int column = 0; column < 256; column++) keep(column);
        return true;
    }

    private BlockState get(int x, int y, int z) {
        BlockState state = states.apply(new BlockPos(baseX + x, y, baseZ + z));
        if (state == null) incomplete = true;
        return state;
    }

    private Area area(int x0, int y, int z0) {
        BlockState left = get(x0 - 1, y, z0), front = get(x0, y, z0 - 1);
        if (!blob(left) || !blob(front) || left.getBlock() != front.getBlock()) return null;
        Block shell = left.getBlock();
        ArrayList<BlockPos> group = new ArrayList<>();
        group.add(new BlockPos(x0, y, z0));
        int minZ = z0, maxZ = z0, maxX = x0;
        int[] sideX = {1, -1, 0, 0}, sideZ = {0, 0, 1, -1};
        for (int index = 0; index < group.size(); index++) {
            BlockPos member = group.get(index);
            for (int side = 0; side < 4; side++) {
                int x = member.getX() + sideX[side], z = member.getZ() + sideZ[side];
                BlockPos next = new BlockPos(x, y, z);
                if (group.contains(next)) continue;
                BlockState state = get(x, y, z);
                if (blob(state)) { if (state.getBlock() != shell) return null; }
                else {
                    if (state == null || !state.is(Blocks.STONE) || x < x0 || x == x0 && z < z0) return null;
                    maxX = Math.max(maxX, x); minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                    if (maxX - x0 > 1 || maxZ - minZ > 1) return null;
                    group.add(next);
                }
            }
        }
        int bits = 0;
        for (BlockPos member : group) {
            for (int offsetX : new int[]{-1, 1}) for (int offsetZ : new int[]{-1, 1}) {
                BlockPos corner = member.offset(offsetX, 0, offsetZ);
                if (!group.contains(corner)) {
                    BlockState state = get(corner.getX(), y, corner.getZ());
                    if (!blob(state) || state.getBlock() != shell) return null;
                }
            }
            BlockState above = get(member.getX(), y + 1, member.getZ()), below = get(member.getX(), y - 1, member.getZ());
            if (above == null || below == null || above.isAir() || below.isAir()) return null;
            bits |= 1 << ((member.getX() - x0) * 3 + member.getZ() - z0 + 1);
        }
        return new Area(bits, shell);
    }

    private void keep(int column) {
        if (height[column] < minimum || top[column] == Integer.MIN_VALUE) return;
        int x = column & 15, z = column >> 4;
        for (int bit = 0; bit < 6; bit++) {
            if ((shape[column] & (1 << bit)) == 0) continue;
            BlockState above = get(x + bit / 3, top[column] + 1, z + bit % 3 - 1);
            BlockState below = get(x + bit / 3, top[column] - height[column], z + bit % 3 - 1);
            if (!carries(above) || !carries(below)) return;
        }
        stacks.add(new BlockPos(baseX + x, top[column] - height[column] + 1, baseZ + z));
    }

    private static boolean carries(BlockState state) { return state != null && (state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE)); }
    private static boolean blob(BlockState state) { return state != null && (state.is(Blocks.DIORITE) || state.is(Blocks.GRANITE) || state.is(Blocks.ANDESITE) || state.is(Blocks.GRAVEL)); }

    public static Map<ChunkPos, Integer> spreadHeat(Map<ChunkPos, Integer> scanned, ChunkPos center, int radius, int spread) {
        int side = radius * 2 + 1;
        int[][] differences = new int[side + 1][side + 1];
        int minX = center.x() - radius, minZ = center.z() - radius;
        for (int offsetX = -radius - spread; offsetX <= radius + spread; offsetX++) {
            for (int offsetZ = -radius - spread; offsetZ <= radius + spread; offsetZ++) {
                int sourceX = center.x() + offsetX, sourceZ = center.z() + offsetZ;
                int amount = scanned.getOrDefault(new ChunkPos(sourceX, sourceZ), 0);
                if (amount <= 0) continue;
                int left = Math.max(0, sourceX - spread - minX), right = Math.min(side - 1, sourceX + spread - minX);
                int front = Math.max(0, sourceZ - spread - minZ), back = Math.min(side - 1, sourceZ + spread - minZ);
                differences[left][front] += amount; differences[right + 1][front] -= amount;
                differences[left][back + 1] -= amount; differences[right + 1][back + 1] += amount;
            }
        }
        Map<ChunkPos, Integer> heat = new HashMap<>();
        for (int localX = 0; localX < side; localX++) for (int localZ = 0; localZ < side; localZ++) {
            if (localX > 0) differences[localX][localZ] += differences[localX - 1][localZ];
            if (localZ > 0) differences[localX][localZ] += differences[localX][localZ - 1];
            if (localX > 0 && localZ > 0) differences[localX][localZ] -= differences[localX - 1][localZ - 1];
            if (differences[localX][localZ] > 0) heat.put(new ChunkPos(minX + localX, minZ + localZ), differences[localX][localZ]);
        }
        return heat;
    }

    public static Set<ChunkPos> hottest(Map<ChunkPos, Integer> heat) {
        Set<ChunkPos> seen = new HashSet<>(), result = new HashSet<>();
        int[] sideX = {1, -1, 0, 0}, sideZ = {0, 0, 1, -1};
        for (ChunkPos start : heat.keySet()) {
            if (!seen.add(start)) continue;
            ArrayDeque<ChunkPos> open = new ArrayDeque<>(); open.add(start);
            ArrayList<ChunkPos> patch = new ArrayList<>(); int max = 0;
            while (!open.isEmpty()) {
                ChunkPos pos = open.removeFirst(); patch.add(pos); max = Math.max(max, heat.get(pos));
                for (int side = 0; side < 4; side++) {
                    ChunkPos next = new ChunkPos(pos.x() + sideX[side], pos.z() + sideZ[side]);
                    if (heat.containsKey(next) && seen.add(next)) open.add(next);
                }
            }
            for (ChunkPos pos : patch) if (heat.get(pos) == max) result.add(pos);
        }
        return Set.copyOf(result);
    }
}