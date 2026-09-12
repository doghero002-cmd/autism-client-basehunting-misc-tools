package com.autism.seedcracker.modules;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.autism.seedcracker.SeedcrackerAddon;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.AutismRenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import autismclient.util.AutismWorldGeometry;

/**
 * Hole ESP.
 *
 * Detects deep vertical holes (1x1 shafts and 3x1 shafts, surrounded on all sides by solid
 * blocks) and draws a coloured box at each. Green = safe 1x1, yellow = wider/unsafe, orange =
 * deep (>= 10). Holes in plants / mineshaft structures / underwater are filtered out so it only
 * shows real dug/bedrock holes.
 *
 * Port of the Xenon "HoleESP" module to the AUTISM API (Mojang 26.2), using the project's
 * level-render collector for the boxes.
 */
public final class HoleEspModule extends Module {

    private static final int SAFE = 0xFF00C800;      // green  (1x1)
    private static final int UNSAFE = 0xFFC8C800;    // yellow (3x1)
    private static final int DEEP = 0xFFC86400;      // orange (deep)

    private final IntSetting range = add(new IntSetting("range", "Range (chunks)", 4, 1, 8, 1)
        .description("Chunk radius around you scanned for holes.").group("General"));
    private final IntSetting minDepth = add(new IntSetting("min-depth", "Min depth", 16, 10, 50, 1)
        .description("Minimum hole depth (blocks) to show.").group("General"));
    private final BoolSetting outline = add(new BoolSetting("outline", "Outline", true)
        .description("Draw the box outline.").group("Render"));

    private static volatile boolean initialised = false;
    private static volatile boolean active = false;
    private static volatile int drawRange = 4;
    private static volatile boolean drawOutline = true;

    /** chunkKey -> set of holes (box + depth + is1x1). */
    private final Map<Long, Set<Hole>> chunkHoles = new ConcurrentHashMap<>();
    private int tickCounter = 0;

    private record Hole(AABB box, int depth, boolean is1x1) {}

    public HoleEspModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":hole-esp", "Hole ESP", category,
            "Highlights deep vertical holes (1x1 and 3x1) around you.");
    }

    @Override
    public void onEnable() {
        initRenderer();
        chunkHoles.clear();
        tickCounter = 0;
    }

    @Override
    public void onDisable() {
        active = false;
        chunkHoles.clear();
    }

    @Override
    public void onGameLeft() {
        setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) { active = false; return; }

        // Scan a couple of chunks per tick (cheap, spread out).
        tickCounter++;
        if (tickCounter % 4 == 0) {
            scanAround(mc);
        }

        active = true;
        drawRange = Math.max(1, range.get());
        drawOutline = outline.get();
    }

    private void scanAround(Minecraft mc) {
        int r = Math.max(1, range.get());
        int pcx = mc.player.chunkPosition().x();
        int pcz = mc.player.chunkPosition().z();

        // Mark all current keys stale, then refresh the bubble.
        chunkHoles.keySet().removeIf(key -> {
            int kx = (int) (key >> 32);
            int kz = (int) (key & 0xffffffffL);
            return Math.abs(kx - pcx) > r + 1 || Math.abs(kz - pcz) > r + 1;
        });

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int cx = pcx + dx, cz = pcz + dz;
                if (!mc.level.hasChunk(cx, cz)) continue;
                long key = ((long) cx << 32) | (cz & 0xffffffffL);
                if (chunkHoles.containsKey(key)) continue;
                chunkHoles.put(key, scanChunk(mc, cx, cz));
            }
        }
    }

    private Set<Hole> scanChunk(Minecraft mc, int cx, int cz) {
        Set<Hole> out = ConcurrentHashMap.newKeySet();
        int minY = mc.level.getMinY();
        int maxY = mc.level.getMaxY();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseX = cx << 4, baseZ = cz << 4;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY + 1; y < maxY - 1; y++) {
                    pos.set(baseX + x, y, baseZ + z);
                    checkHole(mc, pos, out, true);   // 1x1
                    checkHole(mc, pos, out, false);  // 3x1
                }
            }
        }
        return out;
    }

    private void checkHole(Minecraft mc, BlockPos.MutableBlockPos pos, Set<Hole> out, boolean is1x1) {
        if (is1x1) {
            if (!isValidHoleSection(mc, pos) || isValidHoleSection(mc, pos.above())) return;
            int depth = 0;
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos().set(pos);
            while (isValidHoleSection(mc, cursor)) { cursor.move(0, -1, 0); depth++; }
            depth--;
            if (depth >= minDepth.get()) {
                AABB box = new AABB(pos.getX(), cursor.getY() + 1, pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
                if (!intersects(out, box)) out.add(new Hole(box, depth, true));
            }
        } else {
            // 3x1 in X
            if (isValid3x1X(mc, pos) && !isValid3x1X(mc, pos.above())) {
                BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos().set(pos);
                int depth = 0;
                while (isValid3x1X(mc, cursor)) { cursor.move(0, -1, 0); depth++; }
                depth--;
                if (depth >= minDepth.get()) {
                    AABB box = new AABB(pos.getX(), cursor.getY() + 1, pos.getZ(), pos.getX() + 3, pos.getY() + 1, pos.getZ() + 1);
                    if (!intersects(out, box)) out.add(new Hole(box, depth, false));
                }
            }
            // 3x1 in Z
            if (isValid3x1Z(mc, pos) && !isValid3x1Z(mc, pos.above())) {
                BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos().set(pos);
                int depth = 0;
                while (isValid3x1Z(mc, cursor)) { cursor.move(0, -1, 0); depth++; }
                depth--;
                if (depth >= minDepth.get()) {
                    AABB box = new AABB(pos.getX(), cursor.getY() + 1, pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 3);
                    if (!intersects(out, box)) out.add(new Hole(box, depth, false));
                }
            }
        }
    }

    private boolean intersects(Set<Hole> set, AABB box) {
        for (Hole h : set) if (h.box.intersects(box)) return true;
        return false;
    }

    private boolean isValidHoleSection(Minecraft mc, BlockPos pos) {
        return isPassable(mc, pos)
            && isSolidWall(mc, pos.north()) && isSolidWall(mc, pos.south())
            && isSolidWall(mc, pos.east()) && isSolidWall(mc, pos.west());
    }

    private boolean isValid3x1X(Minecraft mc, BlockPos pos) {
        return isPassable(mc, pos) && isPassable(mc, pos.east()) && isPassable(mc, pos.east(2))
            && isSolidWall(mc, pos.north()) && isSolidWall(mc, pos.south())
            && isSolidWall(mc, pos.west()) && isSolidWall(mc, pos.east(3));
    }

    private boolean isValid3x1Z(Minecraft mc, BlockPos pos) {
        return isPassable(mc, pos) && isPassable(mc, pos.south()) && isPassable(mc, pos.south(2))
            && isSolidWall(mc, pos.east()) && isSolidWall(mc, pos.west())
            && isSolidWall(mc, pos.north()) && isSolidWall(mc, pos.south(3));
    }

    private boolean isPassable(Minecraft mc, BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (!state.isAir()) return false;
        // Filter out natural plant/mineshaft gaps.
        if (isPlant(mc.level.getBlockState(pos.below())) || isPlant(mc.level.getBlockState(pos.above()))) return false;
        return !isMineshaft(mc.level.getBlockState(pos.below())) && !isMineshaft(mc.level.getBlockState(pos.above()));
    }

    private boolean isSolidWall(Minecraft mc, BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        return !state.isAir() && !isTransparent(state);
    }

    private boolean isTransparent(BlockState state) {
        Block b = state.getBlock();
        return b == Blocks.OAK_LEAVES || b == Blocks.SPRUCE_LEAVES || b == Blocks.BIRCH_LEAVES
            || b == Blocks.JUNGLE_LEAVES || b == Blocks.ACACIA_LEAVES || b == Blocks.DARK_OAK_LEAVES
            || b == Blocks.CHERRY_LEAVES || b == Blocks.MANGROVE_LEAVES || b == Blocks.AZALEA_LEAVES
            || b == Blocks.FLOWERING_AZALEA_LEAVES || b == Blocks.GLASS || b == Blocks.GLASS_PANE
            || b == Blocks.VINE || b == Blocks.CAVE_VINES || b == Blocks.CAVE_VINES_PLANT
            || b == Blocks.WEEPING_VINES || b == Blocks.WEEPING_VINES_PLANT || b == Blocks.TWISTING_VINES
            || b == Blocks.TWISTING_VINES_PLANT || b == Blocks.GLOW_LICHEN || b == Blocks.HANGING_ROOTS
            || b == Blocks.SPORE_BLOSSOM || b == Blocks.BAMBOO || b == Blocks.BAMBOO_SAPLING
            || b == Blocks.KELP || b == Blocks.KELP_PLANT || b == Blocks.SEAGRASS || b == Blocks.TALL_SEAGRASS
            || b == Blocks.SHORT_GRASS || b == Blocks.TALL_GRASS || b == Blocks.FERN || b == Blocks.LARGE_FERN
            || b == Blocks.SUGAR_CANE || b == Blocks.DEAD_BUSH || b == Blocks.SWEET_BERRY_BUSH;
    }

    private boolean isPlant(BlockState state) {
        Block b = state.getBlock();
        return b == Blocks.KELP || b == Blocks.KELP_PLANT || b == Blocks.SEAGRASS || b == Blocks.TALL_SEAGRASS
            || b == Blocks.VINE || b == Blocks.CAVE_VINES || b == Blocks.CAVE_VINES_PLANT
            || b == Blocks.WEEPING_VINES || b == Blocks.WEEPING_VINES_PLANT || b == Blocks.TWISTING_VINES
            || b == Blocks.TWISTING_VINES_PLANT || b == Blocks.GLOW_LICHEN || b == Blocks.HANGING_ROOTS
            || b == Blocks.SPORE_BLOSSOM;
    }

    private boolean isMineshaft(BlockState state) {
        Block b = state.getBlock();
        return b == Blocks.RAIL || b == Blocks.POWERED_RAIL || b == Blocks.DETECTOR_RAIL
            || b == Blocks.ACTIVATOR_RAIL || b == Blocks.OAK_FENCE || b == Blocks.DARK_OAK_FENCE
            || b == Blocks.SPRUCE_FENCE || b == Blocks.COBWEB;
    }

    // ---- rendering ----

    private void initRenderer() {
        if (initialised) return;
        initialised = true;
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            if (!active) return;
            HoleEspModule self = INSTANCE;
            if (self == null) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null || mc.player == null) return;

            CameraRenderState cameraState = context.levelState().cameraRenderState;
            Vec3 origin = cameraState.pos;
            PoseStack poseStack = context.poseStack();
            int pcx = mc.player.chunkPosition().x();
            int pcz = mc.player.chunkPosition().z();

            for (Map.Entry<Long, Set<Hole>> e : self.chunkHoles.entrySet()) {
                long key = e.getKey();
                int kx = (int) (key >> 32);
                int kz = (int) (key & 0xffffffffL);
                if (Math.abs(kx - pcx) > drawRange || Math.abs(kz - pcz) > drawRange) continue;
                for (Hole hole : e.getValue()) {
                    int color = hole.depth >= 10 ? DEEP : (hole.is1x1 ? SAFE : UNSAFE);
                    AABB b = hole.box;
                    AABB rel = new AABB(
                        b.minX - origin.x, b.minY - origin.y, b.minZ - origin.z,
                        b.maxX - origin.x, b.maxY - origin.y, b.maxZ - origin.z);
                    if (drawOutline) {
                        context.submitNodeCollector().submitCustomGeometry(poseStack,
                            AutismRenderTypes.storageEspLinesSeeThrough(),
                            (pose, buffer) -> outlineBox(pose, buffer, rel, color));
                    }
                }
            }
        });
    }

    private static HoleEspModule INSTANCE;

    {
        INSTANCE = this;
    }

    private static void outlineBox(PoseStack.Pose pose, VertexConsumer buffer, AABB box, int color) {
        double x1 = box.minX, y1 = box.minY, z1 = box.minZ;
        double x2 = box.maxX, y2 = box.maxY, z2 = box.maxZ;
        line(pose, buffer, x1, y1, z1, x2, y1, z1, color);
        line(pose, buffer, x2, y1, z1, x2, y1, z2, color);
        line(pose, buffer, x2, y1, z2, x1, y1, z2, color);
        line(pose, buffer, x1, y1, z2, x1, y1, z1, color);
        line(pose, buffer, x1, y2, z1, x2, y2, z1, color);
        line(pose, buffer, x2, y2, z1, x2, y2, z2, color);
        line(pose, buffer, x2, y2, z2, x1, y2, z2, color);
        line(pose, buffer, x1, y2, z2, x1, y2, z1, color);
        line(pose, buffer, x1, y1, z1, x1, y2, z1, color);
        line(pose, buffer, x2, y1, z1, x2, y2, z1, color);
        line(pose, buffer, x2, y1, z2, x2, y2, z2, color);
        line(pose, buffer, x1, y1, z2, x1, y2, z2, color);
    }

    private static void line(PoseStack.Pose pose, VertexConsumer buffer, double x1, double y1, double z1,
                             double x2, double y2, double z2, int color) {
        AutismWorldGeometry.line(pose, buffer, x1, y1, z1, x2, y2, z2, color, 2.0f);
    }
}
