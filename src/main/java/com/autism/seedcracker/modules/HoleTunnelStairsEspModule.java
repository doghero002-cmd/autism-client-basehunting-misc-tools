package com.autism.seedcracker.modules;

import java.util.ArrayList;
import java.util.List;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.render.BlockEspRenderer;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Hole / Tunnel / Staircase ESP.
 *
 * Detects and highlights player-dug vertical holes (1x1 and 3x1 shafts), horizontal tunnels, and
 * staircases - all strong signs of a base or a dug path. Per-shape detection with independent
 * colours: 1x1 holes (red), 3x1 holes (orange), tunnels (blue), staircases (magenta).
 *
 * Faithful port of the Water Client "HoleESP / HoleTunnelStairsESP" detection logic
 * (isTunnelSection / isStaircaseSection / hole-section checks) to the AUTISM API (Mojang 26.2),
 * rendered with the shared {@link BlockEspRenderer}.
 */
public final class HoleTunnelStairsEspModule extends Module {

    public enum Mode { ALL, HOLES, TUNNELS, STAIRCASES }

    private static final Direction[] DIRECTIONS = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    private final EnumSetting<Mode> mode = add(new EnumSetting<>("mode", "Mode", Mode.ALL, Mode.values())
        .description("Which shapes to detect.").group("General"));
    private final IntSetting scanRadius = add(new IntSetting("scan-radius", "Scan radius (chunks)", 3, 1, 8, 1)
        .description("Chunk bubble around you scanned.").group("General"));
    private final IntSetting minHoleDepth = add(new IntSetting("min-hole-depth", "Min hole depth", 4, 1, 20, 1)
        .description("Minimum 1x1/3x1 shaft depth.").group("Holes"));
    private final IntSetting minTunnelLength = add(new IntSetting("min-tunnel-length", "Min tunnel length", 3, 1, 20, 1)
        .description("Minimum straight tunnel run length.").group("Tunnels"));
    private final IntSetting minStairLength = add(new IntSetting("min-stair-length", "Min staircase length", 3, 1, 20, 1)
        .description("Minimum staircase run length.").group("Staircases"));
    private final IntSetting maxTunnelHeight = add(new IntSetting("max-tunnel-height", "Max tunnel height", 3, 2, 10, 1)
        .description("Max interior height of a tunnel/staircase section.").group("Tunnels"));
    private final BoolSetting airOnly = add(new BoolSetting("air-only", "Only air blocks", false)
        .description("Only count fully-air blocks as passable (stricter).").group("General"));
    private final ColorSetting holeColor = add(new ColorSetting("hole-color", "Hole colour", 0xFFFF4040).group("Render"));
    private final ColorSetting tunnelColor = add(new ColorSetting("tunnel-color", "Tunnel colour", 0xFF4060FF).group("Render"));
    private final ColorSetting stairColor = add(new ColorSetting("stair-color", "Staircase colour", 0xFFFF40FF).group("Render"));
    private final IntSetting chunksPerTick = add(new IntSetting("chunks-per-tick", "Chunks per tick", 1, 1, 32, 1)
        .description("How many chunks to scan per tick. 1 = smoothest FPS, higher = faster full sweep.").group("Performance"));

    private final List<AABB> holes = new ArrayList<>();
    private final List<AABB> holes3x1 = new ArrayList<>();
    private final List<AABB> tunnels = new ArrayList<>();
    private final List<AABB> staircases = new ArrayList<>();
    private final com.autism.seedcracker.finder.ScanCursor scanCursor = new com.autism.seedcracker.finder.ScanCursor();
    private boolean passStarted = false;
    private int tickCounter = 0;

    public HoleTunnelStairsEspModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":hole-tunnel-stairs-esp", "Hole/Tunnel/Stairs ESP", category,
            "Highlights player-dug holes, tunnels, and staircases (base/dug-path signs).");
    }

    @Override
    public void onEnable() {
        BlockEspRenderer.init();
        clearAll();
        tickCounter = 0;
    }

    @Override
    public void onDisable() {
        clearAll();
        clearRender();
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    private void clearAll() {
        holes.clear();
        holes3x1.clear();
        tunnels.clear();
        staircases.clear();
    }

    private void clearRender() {
        String id = SeedcrackerAddon.ID + ":hole-tunnel-stairs-esp";
        BlockEspRenderer.clear(id);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        scan(mc); // incremental: chunks-per-tick budget controls the rate

        // Feed the renderer (boxes persist via TTL, refreshed here), one colour feed per shape.
        String id = SeedcrackerAddon.ID + ":hole-tunnel-stairs-esp";
        Mode m = mode.get();
        if (m == Mode.ALL || m == Mode.HOLES) {
            List<AABB> h = new ArrayList<>(holes); h.addAll(holes3x1);
            if (!h.isEmpty()) BlockEspRenderer.feedBoxes(id + ":h", h, holeColor.get()); else BlockEspRenderer.clearBox(id + ":h");
        } else BlockEspRenderer.clearBox(id + ":h");
        if (m == Mode.ALL || m == Mode.TUNNELS) {
            if (!tunnels.isEmpty()) BlockEspRenderer.feedBoxes(id + ":t", tunnels, tunnelColor.get()); else BlockEspRenderer.clearBox(id + ":t");
        } else BlockEspRenderer.clearBox(id + ":t");
        if (m == Mode.ALL || m == Mode.STAIRCASES) {
            if (!staircases.isEmpty()) BlockEspRenderer.feedBoxes(id + ":s", staircases, stairColor.get()); else BlockEspRenderer.clearBox(id + ":s");
        } else BlockEspRenderer.clearBox(id + ":s");
    }

    private void scan(Minecraft mc) {
        // Incremental pass: clear results at the start of a pass, then scan a few chunks per tick
        // until the queue is exhausted. Boxes update continuously (renderer has a TTL) so the map
        // fills in progressively instead of spiking one frame with a full-volume scan.
        if (!passStarted) { clearAll(); passStarted = true; }
        var batch = scanCursor.nextBatch(mc, scanRadius.get(), 600, chunksPerTick.get());
        for (LevelChunk chunk : batch) {
            int baseX = chunk.getPos().getMinBlockX();
            int baseZ = chunk.getPos().getMinBlockZ();
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = mc.level.getMinY(); y < mc.player.getBlockY() + 16; y++) {
                        BlockPos pos = new BlockPos(baseX + x, y, baseZ + z);
                        if (mode.get() == Mode.ALL || mode.get() == Mode.HOLES) {
                            checkHole(mc, pos);
                            check3x1Hole(mc, pos);
                        }
                        if (mode.get() == Mode.ALL || mode.get() == Mode.TUNNELS) checkTunnel(mc, pos);
                        if (mode.get() == Mode.ALL || mode.get() == Mode.STAIRCASES) checkStaircase(mc, pos);
                    }
                }
            }
        }
        if (scanCursor.progress() >= 1.0) passStarted = false; // pass complete: next tick starts a fresh pass
    }

    // ---- 1x1 hole: passable column with solid N/S/E/W walls, deep enough. ----
    private void checkHole(Minecraft mc, BlockPos pos) {
        if (!isValidHoleSection(mc, pos)) return;
        int depth = 0;
        BlockPos.MutableBlockPos cur = pos.mutable();
        while (isValidHoleSection(mc, cur) && depth < 60) { depth++; cur.move(Direction.DOWN); }
        if (depth >= minHoleDepth.get()) {
            AABB box = new AABB(cur.getX(), cur.getY(), cur.getZ(), cur.getX() + 1, pos.getY() + 1, cur.getZ() + 1);
            if (noneIntersects(holes, box)) holes.add(box);
        }
    }

    private boolean isValidHoleSection(Minecraft mc, BlockPos pos) {
        return isPassable(mc, pos)
            && !isPassable(mc, pos.north()) && !isPassable(mc, pos.south())
            && !isPassable(mc, pos.east()) && !isPassable(mc, pos.west());
    }

    // ---- 3x1 hole: 3-wide passable column with solid outer walls. ----
    private void check3x1Hole(Minecraft mc, BlockPos pos) {
        // X-oriented
        if (isValid3x1X(mc, pos)) {
            int depth = 0;
            BlockPos.MutableBlockPos cur = pos.mutable();
            while (isValid3x1X(mc, cur) && depth < 60) { depth++; cur.move(Direction.DOWN); }
            if (depth >= minHoleDepth.get()) {
                AABB box = new AABB(cur.getX(), cur.getY(), cur.getZ(), cur.getX() + 3, pos.getY() + 1, cur.getZ() + 1);
                if (noneIntersects(holes3x1, box)) holes3x1.add(box);
            }
            return;
        }
        // Z-oriented
        if (isValid3x1Z(mc, pos)) {
            int depth = 0;
            BlockPos.MutableBlockPos cur = pos.mutable();
            while (isValid3x1Z(mc, cur) && depth < 60) { depth++; cur.move(Direction.DOWN); }
            if (depth >= minHoleDepth.get()) {
                AABB box = new AABB(cur.getX(), cur.getY(), cur.getZ(), cur.getX() + 1, pos.getY() + 1, cur.getZ() + 3);
                if (noneIntersects(holes3x1, box)) holes3x1.add(box);
            }
        }
    }

    private boolean isValid3x1X(Minecraft mc, BlockPos pos) {
        return isPassable(mc, pos) && isPassable(mc, pos.east()) && isPassable(mc, pos.east(2))
            && !isPassable(mc, pos.north()) && !isPassable(mc, pos.south())
            && !isPassable(mc, pos.east(3)) && !isPassable(mc, pos.west());
    }

    private boolean isValid3x1Z(Minecraft mc, BlockPos pos) {
        return isPassable(mc, pos) && isPassable(mc, pos.south()) && isPassable(mc, pos.south(2))
            && !isPassable(mc, pos.east()) && !isPassable(mc, pos.west())
            && !isPassable(mc, pos.south(3)) && !isPassable(mc, pos.north());
    }

    // ---- Tunnel: straight run of enclosed sections with interior height in range. ----
    private void checkTunnel(Minecraft mc, BlockPos pos) {
        for (Direction dir : DIRECTIONS) {
            BlockPos.MutableBlockPos cur = pos.mutable();
            int steps = 0;
            BlockPos start = null, end = null;
            int maxHeight = 0;
            if (isTunnelSection(mc, cur, dir)) start = cur.immutable();
            while (isTunnelSection(mc, cur, dir)) {
                maxHeight = Math.max(maxHeight, getInteriorHeight(mc, cur));
                end = cur.immutable();
                cur.move(dir);
                steps++;
            }
            if (steps >= minTunnelLength.get() && maxHeight >= 2 && maxHeight <= maxTunnelHeight.get()) {
                AABB box = new AABB(
                    Math.min(start.getX(), end.getX()), start.getY(), Math.min(start.getZ(), end.getZ()),
                    Math.max(start.getX(), end.getX()) + 1, start.getY() + maxHeight, Math.max(start.getZ(), end.getZ()) + 1);
                if (noneIntersects(tunnels, box)) tunnels.add(box);
            }
        }
    }

    private boolean isTunnelSection(Minecraft mc, BlockPos pos, Direction dir) {
        int height = getInteriorHeight(mc, pos);
        if (height < 2 || height > maxTunnelHeight.get()) return false;
        if (isPassable(mc, pos.below()) || isPassable(mc, pos.above(height))) return false;
        Direction[] perp = dir.getAxis() == Direction.Axis.X
            ? new Direction[]{Direction.NORTH, Direction.SOUTH}
            : new Direction[]{Direction.EAST, Direction.WEST};
        for (Direction p : perp) {
            for (int i = 0; i < height; i++) {
                if (isPassable(mc, pos.above(i).relative(p))) return false;
            }
        }
        return true;
    }

    // ---- Staircase: ascending run of enclosed sections (forward + up each step). ----
    private void checkStaircase(Minecraft mc, BlockPos pos) {
        for (Direction dir : DIRECTIONS) {
            BlockPos.MutableBlockPos cur = pos.mutable();
            int steps = 0;
            List<AABB> boxes = new ArrayList<>();
            while (isStaircaseSection(mc, cur, dir)) {
                int height = getInteriorHeight(mc, cur);
                boxes.add(new AABB(cur.getX(), cur.getY(), cur.getZ(), cur.getX() + 1, cur.getY() + height, cur.getZ() + 1));
                cur.move(dir);
                cur.move(Direction.UP);
                steps++;
            }
            if (steps >= minStairLength.get()) {
                for (AABB b : boxes) if (noneIntersects(staircases, b)) staircases.add(b);
            }
        }
    }

    private boolean isStaircaseSection(Minecraft mc, BlockPos pos, Direction dir) {
        int height = getInteriorHeight(mc, pos);
        if (height < 2 || height > maxTunnelHeight.get() + 2) return false;
        if (isPassable(mc, pos.below()) || isPassable(mc, pos.above(height))) return false;
        Direction[] perp = dir.getAxis() == Direction.Axis.X
            ? new Direction[]{Direction.NORTH, Direction.SOUTH}
            : new Direction[]{Direction.EAST, Direction.WEST};
        for (Direction p : perp) {
            for (int i = 0; i < height; i++) {
                if (isPassable(mc, pos.above(i).relative(p))) return false;
            }
        }
        return true;
    }

    private int getInteriorHeight(Minecraft mc, BlockPos pos) {
        int height = 0;
        while (isPassable(mc, pos.above(height)) && height < maxTunnelHeight.get() + 2) height++;
        return height;
    }

    private boolean isPassable(Minecraft mc, BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (airOnly.get()) return state.isAir();
        VoxelShape shape = state.getCollisionShape(mc.level, pos, CollisionContext.empty());
        return shape.isEmpty() || !net.minecraft.world.phys.shapes.Shapes.block().equals(shape);
    }

    private static boolean noneIntersects(List<AABB> list, AABB box) {
        for (AABB b : list) if (b.intersects(box) || b.equals(box)) return false;
        return true;
    }
}
