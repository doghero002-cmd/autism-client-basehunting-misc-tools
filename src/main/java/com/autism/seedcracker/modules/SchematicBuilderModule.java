package com.autism.seedcracker.modules;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.render.BlockEspRenderer;
import com.autism.seedcracker.util.tunnel.LegitMovement;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Schematic Builder.
 *
 * Loads a schematic file (.litematic / .schem / legacy .schematic), renders a layer-by-layer
 * ghost preview in the world, and auto-builds it. The auto-placer is deliberately "legit":
 * it walks to within vanilla reach, eases its view onto the placement face with the
 * {@link LegitMovement} human-mouse engine (no instant head-snap), and places through the real
 * {@code useItemOn} interaction so the packets match a real player right-clicking blocks.
 *
 * Ghost colours: green = placed, cyan = current layer, red = missed in a past layer,
 * grey (distance-faded) = future layers.
 *
 * Port of the Water Client "SchematicBuilder" file-loading + render logic to the AUTISM API
 * (Mojang 26.2), with the placement rewritten to use legit rotation + vanilla interactions.
 */
public final class SchematicBuilderModule extends Module {

    private static final double MAX_REACH = 4.5;
    private static final double WALK_NEAR = 6.0;

    public SchematicBuilderModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":schematic-builder", "Schematic Builder", category,
            "Loads a schematic and auto-builds it layer by layer with legit (human-like) placement.");
    }

    // ---- settings ----
    private final StringSetting schematicPath = add(new StringSetting("schematic-path", "Schematic file", "")
        .description("Path to a .litematic / .schem / .schematic file (relative to the autism folder or absolute).")
        .group("File"));
    private final BoolSetting load = add(new BoolSetting("load", "Load schematic", false)
        .description("Toggle on to (re)load the file above.").group("File"));
    private final BoolSetting setOrigin = add(new BoolSetting("set-origin", "Set origin", false)
        .description("Toggle on to set the build origin to the block you're looking at (or your feet).").group("File"));
    private final BoolSetting build = add(new BoolSetting("build", "Auto-build", false)
        .description("Start / stop auto-building.").group("Build"));
    public enum BuildOrder { ADAPTIVE, LAYERED }
    private final EnumSetting<BuildOrder> buildOrder = add(new EnumSetting<>("build-order", "Build order", BuildOrder.ADAPTIVE, BuildOrder.values())
        .description("ADAPTIVE = advance at 95% of a layer (skips unreachable spots, faster). LAYERED = strict printer: a layer must be 100% placed before the next starts (keeps retrying misses).")
        .group("Build"));
    private final IntSetting placeDelay = add(new IntSetting("place-delay", "Place delay (ticks)", 2, 0, 20, 1)
        .description("Ticks to wait between block placements.").group("Build"));
    private final IntSetting rotationSteps = add(new IntSetting("rotation", "Rotate schematic", 0, 0, 3, 1)
        .description("Rotate the whole schematic 0/90/180/270 degrees.").group("Build"));
    private final BoolSetting silentPlace = add(new BoolSetting("silent-place", "Silent rotation", false)
        .description("Face placement via movement packets (camera stays). OFF eases the real camera (recommended - the crosshair must be on the block to place).")
        .group("Build"));
    public enum AimStyle { LEGIT, WIND_GRAVITY }
    private final EnumSetting<AimStyle> aimStyle = add(new EnumSetting<>("aim-style", "Aim style", AimStyle.WIND_GRAVITY, AimStyle.values())
        .description("Placement aim easing. LEGIT = time-eased human mouse. WIND_GRAVITY = wocky AdvancedRotationModel (velocity + gust + gravity, most human).")
        .group("Build"));
    private final BoolSetting renderGhost = add(new BoolSetting("render-ghost", "Ghost preview", true)
        .description("Render the layer-by-layer ghost boxes.").group("Render"));
    private final ColorSetting placedColor = add(new ColorSetting("placed-color", "Placed colour", 0x6400FF00).group("Render"));
    private final ColorSetting currentColor = add(new ColorSetting("current-color", "Current layer colour", 0x9600FFFF).group("Render"));
    private final ColorSetting missedColor = add(new ColorSetting("missed-color", "Missed colour", 0x78FF0000).group("Render"));
    private final ColorSetting futureColor = add(new ColorSetting("future-color", "Future colour", 0x40808080).group("Render"));
    private final BoolSetting pathfind = add(new BoolSetting("pathfind", "A* pathfinding", true)
        .description("Walk around obstacles to reach blocks (straight-line walk when off).").group("Build"));
    private final BoolSetting legitWalk = add(new BoolSetting("legit-walk", "Legit walk (key-press)", true)
        .description("Walk by pressing real movement keys (nyx HumanMotionSim) with ledge/fall-hazard stop - most legit, no velocity/packet edits.")
        .group("Build"));
    private final BoolSetting scaffold = add(new BoolSetting("scaffold", "Scaffold bridging", true)
        .description("Place temporary blocks to bridge gaps / reach high spots while building.").group("Build"));
    private final BoolSetting restock = add(new BoolSetting("restock", "Chest restock", false)
        .description("OPTIONAL: when a needed block is missing, open a nearby chest/barrel/shulker/ender-chest and take it, then resume.")
        .group("Restock"));
    private final BoolSetting clearBlocks = add(new BoolSetting("clear-blocks", "Mine out wrong blocks", true)
        .description("Mine out blocks occupying schematic spots (where it wants air or a different block) before placing.")
        .group("Build"));
    private final BoolSetting autoTool = add(new BoolSetting("auto-tool", "Auto tool swap", true)
        .description("Swap to the best hotbar tool when mining out a block (faster clearing).")
        .group("Build"));
    private final IntSetting chestRange = add(new IntSetting("chest-range", "Chest range", 12, 4, 32, 1)
        .description("How far (blocks) to search for a chest with the needed block.")
        .group("Restock").visibleWhen(() -> restock.get()));
    private final IntSetting chestDelay = add(new IntSetting("chest-delay", "Chest action delay", 4, 0, 20, 1)
        .description("Ticks between opening / taking from a chest.")
        .group("Restock").visibleWhen(() -> restock.get()));

    // ---- state ----
    private final Map<BlockPos, BlockState> schematic = new HashMap<>();
    private BlockPos origin = null;
    private int minY, maxY;
    private boolean loaded = false;
    private int currentLayer = 0;
    private int layerRetries = 0;
    private final List<BlockPlaceTask> layerTasks = new ArrayList<>();
    private int layerIndex = 0;
    private int placeCooldown = 0;
    private int totalBlocks = 0;
    private final Set<BlockPos> placed = new HashSet<>();
    private final LegitMovement look = new LegitMovement();
    private int totalBlocksCount = 0;
    private boolean pendingPlace = false;
    private int pendingSlot = -1;
    private BlockHitResult pendingHit = null;
    private boolean pendingSneak = false;
    private boolean faceScanFailureLogged = false;

    // pathfinding + scaffold
    private List<BlockPos> currentPath = new ArrayList<>();
    private int pathIndex = 0;
    private final Set<BlockPos> tempBlocks = new HashSet<>();
    private BlockPos scaffoldTarget = null;
    private int scaffoldAttempts = 0;
    // restock
    private enum RestockState { NONE, SEARCHING, OPENING, TAKING }
    private RestockState restockState = RestockState.NONE;
    private BlockPos restockChest = null;
    private Item neededItem = null;
    private int restockDelay = 0;
    private final Set<BlockPos> searchedChests = new HashSet<>();

    private record BlockPlaceTask(BlockPos worldPos, BlockState state) {}

    private final com.autism.seedcracker.util.StuckDetector stuck =
        new com.autism.seedcracker.util.StuckDetector("SchematicBuilderModule",
            com.autism.seedcracker.util.Tuning.STUCK_TICKS_BUILDER, com.autism.seedcracker.util.Tuning.STUCK_EPSILON);

    @Override
    public void onEnable() {
        look.reset();
        placeCooldown = 0;
        stuck.reset();
    }

    @Override
    public void onDisable() {
        BlockEspRenderer.clearBox(id() + ":p");
        BlockEspRenderer.clearBox(id() + ":c");
        BlockEspRenderer.clearBox(id() + ":m");
        BlockEspRenderer.clearBox(id() + ":f");
        com.autism.seedcracker.util.tunnel.SilentRotation.clear();
        currentPath.clear();
        pathIndex = 0;
        tempBlocks.clear();
        scaffoldTarget = null;
        scaffoldAttempts = 0;
        resetRestock();
        searchedChests.clear();
        pendingPlace = false;
        pendingSlot = -1;
        pendingHit = null;
        pendingSneak = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null) {
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
            mc.options.keyAttack.setDown(false);
            mc.options.keyShift.setDown(false); // pendingSneak path may have latched it
            mc.options.keyUse.setDown(false);
        }
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        if (load.get()) { load.set(false); loadSchematic(mc); }
        if (setOrigin.get()) { setOrigin.set(false); setOriginHere(mc); }

        if (renderGhost.get() && loaded && origin != null) renderGhost(mc);

        if (!build.get() || !loaded || origin == null) return;

        // Restock runs even while a container GUI is open (it needs the chest screen). When the
        // restock machine is active it owns the tick; the normal build is paused.
        if (restock.get() && restockState != RestockState.NONE) {
            tickRestock(mc);
            return;
        }
        if (mc.gui.screen() != null) return; // don't build while a GUI is open
        // No place/dig packets within the container grace window.
        if (com.autism.seedcracker.util.ContainerMutex.containerBusy(mc)) {
            com.autism.seedcracker.util.tunnel.HumanMotionSim.releaseAll();
            return;
        }
        tickBuild(mc);

        // Stuck detector: only meaningful while walking to a target (building in place is fine).
        if (!currentPath.isEmpty() && pathIndex < currentPath.size()) {
            stuck.setAction("layer=" + currentLayer + " task=" + layerIndex + "/" + layerTasks.size()
                + " path=" + pathIndex + "/" + currentPath.size() + " restock=" + restockState);
            stuck.tick(mc);
        } else {
            stuck.reset();
        }
    }

    // ---- origin ----
    private void setOriginHere(Minecraft mc) {
        if (mc.hitResult instanceof BlockHitResult bhr
            && mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            origin = bhr.getBlockPos().above();
        } else {
            origin = mc.player.blockPosition();
        }
        placed.clear();
        currentLayer = minY;
        layerRetries = 0;
        currentPath.clear();
        pathIndex = 0;
        tempBlocks.clear();
        resetRestock();
        searchedChests.clear();
        rebuildLayerTasks();
        msg(mc, "Origin set to " + origin.getX() + " " + origin.getY() + " " + origin.getZ());
    }

    // ---- build state machine ----
    private void tickBuild(Minecraft mc) {
        if (currentLayer > maxY) {
            msg(mc, "Build complete! " + placed.size() + "/" + totalBlocks + " blocks.");
            build.set(false);
            return;
        }
        if (layerIndex >= layerTasks.size()) {
            // verify + advance
            int placedInLayer = 0;
            for (BlockPlaceTask t : layerTasks) {
                if (mc.level.getBlockState(t.worldPos).equals(t.state)) placedInLayer++;
            }
            boolean advance;
            if (buildOrder.get() == BuildOrder.LAYERED) {
                // Strict printer: the layer must be COMPLETE before the next starts. Retry passes
                // are capped so an unplaceable spot (bedrock hole, mob cage) can't loop forever.
                advance = placedInLayer == layerTasks.size() || ++layerRetries > 8;
                if (advance && placedInLayer < layerTasks.size()) {
                    msg(mc, "Layer " + (currentLayer - minY + 1) + ": " + (layerTasks.size() - placedInLayer)
                        + " spot(s) unplaceable after 8 passes - moving on.");
                }
            } else {
                advance = placedInLayer >= (int) (layerTasks.size() * 0.95) || placedInLayer == layerTasks.size();
            }
            if (advance) {
                currentLayer++;
                layerRetries = 0;
                rebuildLayerTasks();
            } else {
                rebuildLayerTasks(); // retry the missed ones
            }
            return;
        }

        if (placeCooldown > 0) { placeCooldown--; return; }

        BlockPlaceTask task = layerTasks.get(layerIndex);
        BlockState cur = mc.level.getBlockState(task.worldPos);
        if (cur.equals(task.state)) {
            placed.add(task.worldPos);
            layerIndex++;
            return;
        }
        // The spot is occupied by a wrong / non-replaceable block. Mine it out first (legit dig),
        // then come back and place. Bedrock is never dug.
        if (!cur.isAir() && !cur.canBeReplaced()) {
            if (clearBlocks.get() && cur.getBlock() != Blocks.BEDROCK) {
                if (digOut(mc, task.worldPos, cur)) return; // still digging
                // digOut returns false when it can't reach / finished this tick: fall through to re-check next tick
                return;
            }
            layerIndex++;
            return;
        }
        // Entity-free guard (Wocky canPlaceBlockClient): don't place into a cell occupied by a
        // mob/armor stand/etc (the server rejects it) - skip it this pass, retry next.
        if (entityBlocking(mc, task.worldPos)) return;

        // Find the item for this block in the hotbar.
        Item item = task.state.getBlock().asItem();
        int slot = findHotbarItem(mc, item);
        if (slot < 0) {
            // Missing the block. If restock is on, go fetch it from a chest; otherwise skip.
            if (restock.get()) {
                neededItem = item;
                restockState = RestockState.SEARCHING;
                searchedChests.clear();
                return; // don't advance - resume after restock
            }
            layerIndex++;
            return;
        }

        // Placement solve: prefer the base client's AutismFaceScan (per-block face rects, raycast
        // visibility, self-occlusion, sneak-unlock detection - the engine its own Scaffold uses).
        // Falls back to our simpler face-centre scan when FaceScan has no candidate.
        BlockHitResult hit = null;
        boolean scanSneak = false;
        Vec3 eye = mc.player.getEyePosition();
        try {
            var placement = autismclient.util.AutismFaceScan.blockItem(
                mc.player.getInventory().getItem(slot), mc.player, InteractionHand.MAIN_HAND);
            if (placement != null) {
                var req = new autismclient.util.AutismFaceScan.Request(
                    task.worldPos, eye, reachFor(task.worldPos), placement)
                    .sneakAllowed(true)
                    .quantize(true);
                var cand = autismclient.util.AutismFaceScan.best(req);
                if (cand != null && cand.hit() != null) {
                    hit = cand.hit();
                    scanSneak = cand.option() != null && cand.option().requiresSneak();
                }
            }
        } catch (Throwable t) {
            if (!faceScanFailureLogged) { // one line, not silence - fallback path still works
                faceScanFailureLogged = true;
                com.autism.seedcracker.util.FlagLog.flag("INFO", "SchematicBuilder",
                    "AutismFaceScan unavailable, using fallback placement: " + t);
            }
        }
        if (hit == null) hit = findPlacementHit(mc, task.worldPos);
        if (hit == null) { layerIndex++; return; }
        double dist = eye.distanceTo(hit.getLocation());
        double reach = reachFor(task.worldPos);
        if (dist > reach) {
            if (pathfind.get()) walkPath(mc, task.worldPos);
            else walkToward(mc, task.worldPos);
            return;
        }
        stopWalk(mc);
        currentPath.clear(); pathIndex = 0;
        if (!mc.player.onGround()) return; // never place airborne (Grim)

        // Swap first, then place on the NEXT tick (Water #2) so the server registers the held item
        // before the interact - reads as a real click instead of an instant swap-place.
        if (pendingSlot != slot || !pendingPlace) {
            com.autism.seedcracker.util.InvSync.select(mc, slot);
            pendingSlot = slot;
            pendingPlace = true;
            pendingHit = hit;
            pendingSneak = scanSneak;
            return;
        }

        // Face the placement point with the legit human-mouse engine, then place.
        Vec3 target = pendingHit.getLocation();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        float[] rot = aimStyle.get() == AimStyle.WIND_GRAVITY
            ? look.updateWindGravity(yaw, pitch)
            : look.update(yaw, pitch);
        if (silentPlace.get()) {
            com.autism.seedcracker.util.tunnel.SilentRotation.apply(rot[0], rot[1]);
        } else {
            com.autism.seedcracker.util.tunnel.SilentRotation.clear();
            mc.player.setYRot(rot[0]);
            mc.player.setXRot(net.minecraft.util.Mth.clamp(rot[1], -90f, 90f));
        }
        if (mc.gameMode != null) {
            // Sneak when the support is a container (nyx ROTATE_SNEAK) or FaceScan says the click
            // would otherwise be eaten by the block's use action (levers, doors, etc).
            boolean sneakForContainer = pendingSneak || isContainer(mc, pendingHit.getBlockPos());
            if (sneakForContainer) mc.options.keyShift.setDown(true);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, pendingHit);
            mc.player.swing(InteractionHand.MAIN_HAND);
            if (sneakForContainer) mc.options.keyShift.setDown(false);
        }
        placed.add(task.worldPos);
        layerIndex++;
        pendingPlace = false;
        pendingSlot = -1;
        pendingHit = null;
        pendingSneak = false;
        placeCooldown = placeDelay.get();
    }

    /** Vanilla-ish reach: a bit more for targets above the head (you can reach up further). */
    private double reachFor(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && pos.getY() > mc.player.getBlockY() + 1) return MAX_REACH + 1.5;
        return MAX_REACH;
    }

    private void rebuildLayerTasks() {
        layerTasks.clear();
        layerIndex = 0;
        pendingPlace = false;
        pendingSlot = -1;
        pendingHit = null;
        pendingSneak = false;
        int rot = rotationSteps.get();
        for (Map.Entry<BlockPos, BlockState> e : schematic.entrySet()) {
            BlockPos rel = e.getKey();
            if (rel.getY() != currentLayer) continue;
            BlockPos r = rotatePos(rel, rot);
            layerTasks.add(new BlockPlaceTask(origin.offset(r), e.getValue()));
        }
        BlockPos pp = Minecraft.getInstance().player != null
            ? Minecraft.getInstance().player.blockPosition() : BlockPos.ZERO;
        layerTasks.sort((a, b) -> Double.compare(pp.distSqr(a.worldPos), pp.distSqr(b.worldPos)));
    }

    private BlockHitResult findPlacementHit(Minecraft mc, BlockPos pos) {
        // For blocks above the player, prefer the DOWN face (place on top of the block beneath) -
        // more natural. Otherwise prefer placing on top of the block below.
        BlockPos below = pos.below();
        if (isSolid(mc, below)) return faceCenterHit(below, Direction.UP);

        // Prioritize horizontal sides for eye-level blocks, DOWN for above-head blocks.
        boolean aboveHead = mc.player != null && pos.getY() > mc.player.getBlockY();
        Direction[] dirs = aboveHead
            ? new Direction[] { Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP }
            : new Direction[] { Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN };
        for (Direction d : dirs) {
            BlockPos n = pos.relative(d);
            if (isSolid(mc, n)) return faceCenterHit(n, d.getOpposite());
        }

        // Fallback (Water #3): an AIR neighbour with a solid block BEHIND it lets us bridge
        // outward/upward when there's no directly-adjacent solid face.
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            BlockState ns = mc.level.getBlockState(n);
            if (ns.isAir() || ns.canBeReplaced()) {
                BlockPos behind = n.relative(d);
                if (isSolid(mc, behind)) {
                    return faceCenterHit(n, d.getOpposite());
                }
            }
        }
        return null;
    }

    /**
     * Build a BlockHitResult on the support block's face. Tries a raycast-validated VISIBLE point
     * first (THack Interact.Legit - survives Grim's PositionPlace ray check even when the face
     * centre is occluded); falls back to the face centre (nyx class243Of) if none is visible.
     */
    private static BlockHitResult faceCenterHit(BlockPos support, Direction face) {
        Vec3 legit = com.autism.seedcracker.util.tunnel.LegitHitPoint.find(
            Minecraft.getInstance(), support, face, 4.5);
        if (legit != null) return new BlockHitResult(legit, face, support, false);
        Vec3 point = new Vec3(
            support.getX() + 0.5 + face.getStepX() * 0.5,
            support.getY() + 0.5 + face.getStepY() * 0.5,
            support.getZ() + 0.5 + face.getStepZ() * 0.5);
        return new BlockHitResult(point, face, support, false);
    }

    private static boolean isSolid(Minecraft mc, BlockPos pos) {
        BlockState s = mc.level.getBlockState(pos);
        return !s.isAir() && !s.canBeReplaced();
    }

    /** True if the block has a container GUI (placing against it needs sneak to not open it). */
    private static boolean isContainer(Minecraft mc, BlockPos pos) {
        return mc.level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
    }

    /** True if a living/non-item entity intersects the target cell (server rejects the place). */
    private static boolean entityBlocking(Minecraft mc, BlockPos pos) {
        if (mc.level == null) return false;
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(pos);
        for (net.minecraft.world.entity.Entity e : mc.level.getEntitiesOfClass(
                net.minecraft.world.entity.Entity.class, box,
                x -> !(x instanceof net.minecraft.world.entity.item.ItemEntity) && x.isPickable())) {
            return true;
        }
        return false;
    }

    private void walkToward(Minecraft mc, BlockPos target) {
        if (legitWalk.get()) {
            // nyx HumanMotionSim: drive the REAL movement keys toward the target with a turn-gate,
            // ledge/fall-hazard stop and mine-through detection - zero velocity/packet manipulation.
            Vec3 t = Vec3.atCenterOf(target);
            var result = com.autism.seedcracker.util.tunnel.HumanMotionSim.step(t, MAX_REACH, 30.0, 4, 1);
            if (result == com.autism.seedcracker.util.tunnel.HumanMotionSim.StepResult.MINE_THROUGH) {
                BlockPos mine = com.autism.seedcracker.util.tunnel.HumanMotionSim.getMineTarget();
                if (mine != null) digOut(mc, mine, mc.level.getBlockState(mine));
            }
            return;
        }
        double dx = (target.getX() + 0.5) - mc.player.getX();
        double dz = (target.getZ() + 0.5) - mc.player.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float[] rot = look.update(yaw, 0f);
        mc.player.setYRot(rot[0]);
        mc.options.keyUp.setDown(true);
        if (mc.player.horizontalCollision && mc.player.onGround()) mc.options.keyJump.setDown(true);
        else mc.options.keyJump.setDown(false);
    }

    private static void stopWalk(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        com.autism.seedcracker.util.tunnel.HumanMotionSim.releaseAll();
    }

    /**
     * Legit-mine the block occupying a schematic spot. Walks to within vanilla reach, eases the
     * view onto the block with the legit engine, swaps to the best tool, then holds attack on the
     * crosshair block (real reported face). Returns true while a dig is actively in progress.
     */
    private boolean digOut(Minecraft mc, BlockPos pos, BlockState state) {
        Vec3 eye = mc.player.getEyePosition();
        double dist = eye.distanceTo(Vec3.atCenterOf(pos));
        if (dist > MAX_REACH) {
            if (pathfind.get()) walkPath(mc, pos);
            else walkToward(mc, pos);
            return true;
        }
        stopWalk(mc);
        if (!mc.player.onGround()) return true; // never dig airborne (Grim)
        if (autoTool.get()) selectBestTool(mc, state);
        // Ease onto the block with the human-mouse engine so the crosshair lands on it. Cooldown-
        // scaled (Wocky SpookyTime): the aim slows as attack-strength charges, like a real player
        // timing their swings, instead of a constant-speed head-track.
        double dx = pos.getX() + 0.5 - eye.x, dy = pos.getY() + 0.5 - eye.y, dz = pos.getZ() + 0.5 - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        float cd = mc.player.getAttackStrengthScale(0.5f); // 0..1 crit charge
        float scale = 0.4f + 0.6f * cd; // aim faster when charged, slower while charging
        float[] rot = look.update(
            mc.player.getYRot() + (yaw - mc.player.getYRot()) * scale,
            mc.player.getXRot() + (pitch - mc.player.getXRot()) * scale);
        com.autism.seedcracker.util.tunnel.SilentRotation.clear();
        mc.player.setYRot(rot[0]);
        mc.player.setXRot(net.minecraft.util.Mth.clamp(rot[1], -90f, 90f));
        // Break whatever solid block the crosshair ray actually reports (real face).
        if (mc.hitResult instanceof BlockHitResult bhr
            && mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            BlockPos hitPos = bhr.getBlockPos();
            if (!mc.level.getBlockState(hitPos).isAir() && mc.gameMode != null) {
                mc.options.keyAttack.setDown(true);
                mc.gameMode.continueDestroyBlock(hitPos, bhr.getDirection());
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
        }
        return true;
    }

    /** Swap to the best hotbar tool for a block (used when clearing). */
    private static void selectBestTool(Minecraft mc, BlockState state) {
        int bestSlot = -1;
        float bestSpeed = -1.0f;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) { bestSpeed = speed; bestSlot = slot; }
        }
        if (bestSlot == -1) return;
        float current = mc.player.getMainHandItem().getDestroySpeed(state);
        if (bestSpeed > current) com.autism.seedcracker.util.InvSync.select(mc, bestSlot);
    }

    // ---- A* pathfinding + scaffold bridging ----

    /** Follow an A* path toward the target, bridging gaps with scaffold blocks when needed. */
    private void walkPath(Minecraft mc, BlockPos target) {
        BlockPos start = mc.player.blockPosition();
        if (currentPath.isEmpty() || pathIndex >= currentPath.size()
            || !currentPath.get(currentPath.size() - 1).equals(target)) {
            List<BlockPos> path = findPath(mc, start, target);
            if (path != null && !path.isEmpty()) { currentPath = path; pathIndex = 0; }
            else { walkToward(mc, target); return; } // no path: fall back to straight walk
        }
        // Scaffold-bridge: if the next step has no floor, place a temporary block to stand on.
        if (scaffold.get() && tryScaffoldStep(mc)) return;

        Vec3 pp = mc.player.position();
        while (pathIndex < currentPath.size()) {
            BlockPos next = currentPath.get(pathIndex);
            double d = pp.distanceTo(Vec3.atCenterOf(next));
            if (d < 0.6) { pathIndex++; continue; }
            walkToward(mc, next);
            return;
        }
        walkToward(mc, target);
    }

    /** Place a temporary scaffold block under the next step if there's a gap. */
    private boolean tryScaffoldStep(Minecraft mc) {
        BlockPos feet = mc.player.blockPosition();
        BlockPos ahead = feet.relative(mc.player.getDirection());
        BlockPos floor = ahead.below();
        if (isSolid(mc, floor)) return false; // already a floor
        if (schematic.containsKey(toRel(floor))) return false; // don't overwrite schematic spots
        int slot = findScaffoldSlot(mc);
        if (slot < 0) return false;
        // place on the side of an adjacent solid block
        BlockHitResult hit = findPlacementHit(mc, floor);
        if (hit == null) return false;
        com.autism.seedcracker.util.InvSync.select(mc, slot);
        float[] rot = look.update(
            (float) Math.toDegrees(Math.atan2(-(hit.getLocation().x - mc.player.getX()), hit.getLocation().z - mc.player.getZ())),
            40f);
        mc.player.setYRot(rot[0]);
        mc.player.setXRot(net.minecraft.util.Mth.clamp(rot[1], -90f, 90f));
        if (mc.gameMode != null) {
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
        tempBlocks.add(floor);
        return true;
    }

    private BlockPos toRel(BlockPos worldPos) {
        return origin == null ? worldPos : worldPos.subtract(origin);
    }

    private static int findScaffoldSlot(Minecraft mc) {
        Item[] pref = { Blocks.DIRT.asItem(), Blocks.COBBLESTONE.asItem(), Blocks.STONE.asItem(), Blocks.OAK_PLANKS.asItem() };
        for (Item it : pref) { int s = findHotbarItem(mc, it); if (s >= 0) return s; }
        for (int i = 0; i < 9; i++) {
            ItemStack st = mc.player.getInventory().getItem(i);
            if (!st.isEmpty() && st.getItem() instanceof net.minecraft.world.item.BlockItem) return i;
        }
        return -1;
    }

    /** Simple A* over walkable positions (3-D, with jump/build costs). */
    private List<BlockPos> findPath(Minecraft mc, BlockPos start, BlockPos goal) {
        record Node(BlockPos pos, double f) {}
        java.util.PriorityQueue<Node> open = new java.util.PriorityQueue<>(java.util.Comparator.comparingDouble(Node::f));
        Map<BlockPos, BlockPos> came = new HashMap<>();
        Map<BlockPos, Double> g = new HashMap<>();
        g.put(start, 0.0);
        open.add(new Node(start, heuristic(start, goal)));
        int iter = 0;
        while (!open.isEmpty() && iter++ < 1500) {
            BlockPos cur = open.poll().pos();
            if (cur.equals(goal) || cur.distSqr(goal) <= 4) {
                List<BlockPos> path = new ArrayList<>();
                for (BlockPos n = cur; n != null; n = came.get(n)) path.add(0, n);
                return path;
            }
            if (cur.distSqr(start) > 60.0 * 60.0) break;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dz == 0 && dy == 0) continue;
                BlockPos nb = cur.offset(dx, dy, dz);
                if (!isWalkable(mc, nb)) continue;
                double cost = 1.0;
                if (dx != 0 && dz != 0) cost = 1.414;
                if (dy > 0) cost += 0.8;
                if (!isSolid(mc, nb.below())) cost += 2.0; // needs a bridge
                double tg = g.getOrDefault(cur, Double.MAX_VALUE) + cost;
                if (tg < g.getOrDefault(nb, Double.MAX_VALUE)) {
                    came.put(nb, cur);
                    g.put(nb, tg);
                    open.add(new Node(nb, tg + heuristic(nb, goal)));
                }
            }
        }
        return null;
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dy = a.getY() - b.getY(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private boolean isWalkable(Minecraft mc, BlockPos pos) {
        BlockState feet = mc.level.getBlockState(pos);
        BlockState head = mc.level.getBlockState(pos.above());
        boolean passable = (feet.isAir() || feet.canBeReplaced()) && (head.isAir() || head.canBeReplaced());
        if (!passable) return false;
        if (isSolid(mc, pos.below())) return true;
        // can bridge a gap if scaffolding is on and we have a scaffold block
        return scaffold.get() && findScaffoldSlot(mc) >= 0;
    }

    // ---- chest restock (optional) ----

    private void tickRestock(Minecraft mc) {
        // Release any held movement keys before touching the chest (no movement during a container).
        com.autism.seedcracker.util.tunnel.HumanMotionSim.releaseAll();
        stopWalk(mc);
        if (restockDelay > 0) { restockDelay--; return; }
        switch (restockState) {
            case SEARCHING -> {
                if (findHotbarItem(mc, neededItem) >= 0) { resetRestock(); return; }
                restockChest = findNearestChest(mc, neededItem);
                if (restockChest == null) {
                    msg(mc, "No chest with " + new ItemStack(neededItem).getHoverName().getString() + " nearby - skipping it.");
                    resetRestock();
                    layerIndex++; // skip the block we couldn't get
                    return;
                }
                restockState = RestockState.OPENING;
                restockDelay = chestDelay.get();
            }
            case OPENING -> {
                if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) {
                    restockState = RestockState.TAKING;
                    restockDelay = chestDelay.get();
                    return;
                }
                // face + open the chest
                float[] rot = look.update(
                    (float) Math.toDegrees(Math.atan2(-(restockChest.getX() + 0.5 - mc.player.getX()), restockChest.getZ() + 0.5 - mc.player.getZ())), 10f);
                mc.player.setYRot(rot[0]);
                mc.player.setXRot(net.minecraft.util.Mth.clamp(rot[1], -90f, 90f));
                if (mc.gameMode != null) {
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(restockChest), Direction.UP, restockChest, false));
                }
                restockDelay = chestDelay.get();
            }
            case TAKING -> {
                if (!(mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)) {
                    resetRestock();
                    return;
                }
                var handler = mc.player.containerMenu;
                int containerSlots = handler.slots.size() - 36;
                boolean took = false;
                for (int i = 0; i < containerSlots; i++) {
                    var slot = handler.slots.get(i);
                    if (slot.hasItem() && slot.getItem().is(neededItem)) {
                        com.autism.seedcracker.util.ContainerMutex.notifyContainerAction(); mc.gameMode.handleContainerInput(handler.containerId, i, 0,
                            net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, mc.player);
                        took = true;
                        break;
                    }
                }
                restockDelay = chestDelay.get();
                if (findHotbarItem(mc, neededItem) >= 0) {
                    closeScreen(mc);
                    resetRestock();
                } else if (!took) {
                    // this chest is empty of the item: mark + look for another
                    searchedChests.add(restockChest);
                    closeScreen(mc);
                    restockState = RestockState.SEARCHING;
                    restockDelay = chestDelay.get();
                }
            }
            default -> {}
        }
    }

    private void resetRestock() {
        restockState = RestockState.NONE;
        restockChest = null;
        neededItem = null;
        restockDelay = 0;
    }

    private static void closeScreen(Minecraft mc) {
        if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) {
            mc.player.closeContainer();
        }
    }

    private BlockPos findNearestChest(Minecraft mc, Item item) {
        BlockPos pp = mc.player.blockPosition();
        int r = chestRange.get();
        BlockPos nearest = null;
        double nd = Double.MAX_VALUE;
        for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
            BlockPos p = pp.offset(x, y, z);
            if (searchedChests.contains(p)) continue;
            if (!(mc.level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity)) continue;
            double d = pp.distSqr(p);
            if (d < nd) { nd = d; nearest = p; }
        }
        return nearest;
    }

    private static int findHotbarItem(Minecraft mc, Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (!s.isEmpty() && s.is(item)) return i;
        }
        return -1;
    }

    private static BlockPos rotatePos(BlockPos p, int rot) {
        return switch (rot) {
            case 1 -> new BlockPos(-p.getZ(), p.getY(), p.getX());
            case 2 -> new BlockPos(-p.getX(), p.getY(), -p.getZ());
            case 3 -> new BlockPos(p.getZ(), p.getY(), -p.getX());
            default -> p;
        };
    }

    // ---- ghost render ----
    private void renderGhost(Minecraft mc) {
        List<AABB> placedBoxes = new ArrayList<>();
        List<AABB> currentBoxes = new ArrayList<>();
        List<AABB> missedBoxes = new ArrayList<>();
        List<AABB> futureBoxes = new ArrayList<>();
        int rot = rotationSteps.get();
        for (Map.Entry<BlockPos, BlockState> e : schematic.entrySet()) {
            BlockPos worldPos = origin.offset(rotatePos(e.getKey(), rot));
            if (mc.player.distanceToSqr(Vec3.atCenterOf(worldPos)) > 200.0 * 200.0) continue;
            AABB box = new AABB(worldPos);
            int y = e.getKey().getY();
            boolean isPlaced = mc.level.getBlockState(worldPos).equals(e.getValue());
            if (isPlaced) placedBoxes.add(box);
            else if (y == currentLayer) currentBoxes.add(box);
            else if (y < currentLayer) missedBoxes.add(box);
            else futureBoxes.add(box);
        }
        feed(id() + ":p", placedBoxes, placedColor.get());
        feed(id() + ":c", currentBoxes, currentColor.get());
        feed(id() + ":m", missedBoxes, missedColor.get());
        feed(id() + ":f", futureBoxes, futureColor.get());
    }

    private static void feed(String id, List<AABB> boxes, int argb) {
        if (boxes.isEmpty()) BlockEspRenderer.clearBox(id);
        else BlockEspRenderer.feedBoxes(id, boxes, argb);
    }

    // ---- file loading ----
    private void loadSchematic(Minecraft mc) {
        String path = schematicPath.get() == null ? "" : schematicPath.get().trim();
        if (path.isEmpty()) { msg(mc, "Set a schematic file path first."); return; }
        File file = new File(path);
        if (!file.isAbsolute()) file = autismclient.AutismClientAddon.FOLDER.toPath().resolve(path).toFile();
        if (!file.exists()) { msg(mc, "Schematic not found: " + file.getPath()); return; }

        try {
            CompoundTag nbt = readNbt(file);
            if (nbt == null) { msg(mc, "Failed to read NBT."); return; }
            if (nbt.getCompound("Schematic").isPresent()) nbt = nbt.getCompoundOrEmpty("Schematic");

            schematic.clear();
            if (nbt.getCompound("Regions").isPresent()) loadLitematica(nbt);
            else if (nbt.contains("palette") && nbt.contains("blocks")) loadStructure(nbt);
            else if (nbt.contains("Blocks")) loadLegacy(nbt);
            else { msg(mc, "Unknown schematic format."); return; }

            if (schematic.isEmpty()) { msg(mc, "Schematic loaded but has no blocks."); return; }
            normalize();
            totalBlocks = schematic.size();
            totalBlocksCount = totalBlocks;
            loaded = true;
            placed.clear();
            currentLayer = minY;
            rebuildLayerTasks();
            msg(mc, "Loaded " + totalBlocks + " blocks, layers " + minY + ".." + maxY
                + ". Set origin, then enable Auto-build.");
        } catch (Throwable t) {
            msg(mc, "Load failed: " + t.getMessage());
        }
    }

    private static CompoundTag readNbt(File file) {
        try { return NbtIo.readCompressed(file.toPath(), NbtAccounter.unlimitedHeap()); }
        catch (Throwable ignored) {}
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(file))) {
            Tag t = NbtIo.read(in, NbtAccounter.unlimitedHeap());
            return t instanceof CompoundTag c ? c : null;
        } catch (Throwable t) { return null; }
    }

    private void loadStructure(CompoundTag nbt) {
        ListTag palette = nbt.getListOrEmpty("palette");
        BlockState[] states = new BlockState[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            CompoundTag b = palette.getCompoundOrEmpty(i);
            Block blk = BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse(b.getStringOr("Name", "minecraft:air")));
            states[i] = blk != null ? blk.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }
        ListTag blocks = nbt.getListOrEmpty("blocks");
        trackMinMaxReset();
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag b = blocks.getCompoundOrEmpty(i);
            int[] pos = b.getIntArray("pos").orElse(new int[0]);
            int si = b.getIntOr("state", -1);
            if (pos.length < 3 || si < 0 || si >= states.length || states[si].isAir()) continue;
            put(new BlockPos(pos[0], pos[1], pos[2]), states[si]);
        }
    }

    private void loadLitematica(CompoundTag nbt) {
        trackMinMaxReset();
        CompoundTag regions = nbt.getCompoundOrEmpty("Regions");
        for (String name : regions.keySet()) {
            CompoundTag region = regions.getCompoundOrEmpty(name);
            java.util.Optional<long[]> bsOpt = region.getLongArray("BlockStates");
            if (bsOpt.isEmpty() || region.getList("BlockStatePalette").isEmpty()) continue;
            int[] size = region.getIntArray("Size").orElse(new int[0]);
            int[] pos = region.getIntArray("Position").orElse(new int[0]);
            if (size.length < 3) continue;
            int w = Math.abs(size[0]), h = Math.abs(size[1]), l = Math.abs(size[2]);
            int ox = pos.length >= 1 ? pos[0] : 0, oy = pos.length >= 2 ? pos[1] : 0, oz = pos.length >= 3 ? pos[2] : 0;

            ListTag palette = region.getListOrEmpty("BlockStatePalette");
            BlockState[] states = new BlockState[palette.size()];
            for (int i = 0; i < palette.size(); i++) {
                CompoundTag b = palette.getCompoundOrEmpty(i);
                Block blk = BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse(b.getStringOr("Name", "minecraft:air")));
                states[i] = blk != null ? blk.defaultBlockState() : Blocks.AIR.defaultBlockState();
            }
            long[] data = bsOpt.get();
            int bitsPer = ceilLog2(Math.max(2, palette.size()));
            long mask = (1L << bitsPer) - 1;
            int perLong = 64 / bitsPer;
            for (int y = 0; y < h; y++) for (int z = 0; z < l; z++) for (int x = 0; x < w; x++) {
                int idx = (y * l + z) * w + x;
                int li = idx / perLong;
                if (li >= data.length) continue;
                int off = (idx % perLong) * bitsPer;
                int val = (int) ((data[li] >>> off) & mask);
                if (val < 0 || val >= states.length || states[val].isAir()) continue;
                put(new BlockPos(x + ox, y + oy, z + oz), states[val]);
            }
        }
    }

    private void loadLegacy(CompoundTag nbt) {
        trackMinMaxReset();
        int w = nbt.getShortOr("Width", (short) 0) & 0xFFFF, h = nbt.getShortOr("Height", (short) 0) & 0xFFFF, l = nbt.getShortOr("Length", (short) 0) & 0xFFFF;
        byte[] blocks = nbt.getByteArray("Blocks").orElse(new byte[0]);
        byte[] data = nbt.getByteArray("Data").orElse(new byte[0]);
        for (int y = 0; y < h; y++) for (int z = 0; z < l; z++) for (int x = 0; x < w; x++) {
            int idx = (y * l + z) * w + x;
            if (idx >= blocks.length) continue;
            int id = blocks[idx] & 0xFF;
            int meta = idx < data.length ? data[idx] & 0xF : 0;
            Block b = legacyBlock(id, meta);
            if (b != null && b != Blocks.AIR) put(new BlockPos(x, y, z), b.defaultBlockState());
        }
    }

    private static Block legacyBlock(int id, int meta) {
        return switch (id) {
            case 1 -> Blocks.STONE; case 2 -> Blocks.GRASS_BLOCK; case 3 -> Blocks.DIRT;
            case 4 -> Blocks.COBBLESTONE; case 5 -> Blocks.OAK_PLANKS; case 7 -> Blocks.BEDROCK;
            case 12 -> Blocks.SAND; case 13 -> Blocks.GRAVEL; case 14 -> Blocks.GOLD_ORE;
            case 15 -> Blocks.IRON_ORE; case 16 -> Blocks.COAL_ORE; case 20 -> Blocks.GLASS;
            case 35 -> Blocks.WOOL.pick(net.minecraft.world.item.DyeColor.byId(meta & 0xF)); case 41 -> Blocks.GOLD_BLOCK; case 42 -> Blocks.IRON_BLOCK;
            case 45 -> Blocks.BRICKS; case 46 -> Blocks.TNT; case 47 -> Blocks.BOOKSHELF;
            case 48 -> Blocks.MOSSY_COBBLESTONE; case 49 -> Blocks.OBSIDIAN; case 54 -> Blocks.CHEST;
            case 56 -> Blocks.DIAMOND_ORE; case 57 -> Blocks.DIAMOND_BLOCK; case 80 -> Blocks.SNOW_BLOCK;
            case 86 -> Blocks.PUMPKIN; case 87 -> Blocks.NETHERRACK; case 89 -> Blocks.GLOWSTONE;
            case 98 -> Blocks.STONE_BRICKS; case 112 -> Blocks.NETHER_BRICKS; case 121 -> Blocks.END_STONE;
            case 133 -> Blocks.EMERALD_BLOCK; case 155 -> Blocks.QUARTZ_BLOCK;
            default -> null;
        };
    }

    private void trackMinMaxReset() {
        minY = Integer.MAX_VALUE; maxY = Integer.MIN_VALUE;
    }

    private void put(BlockPos pos, BlockState state) {
        schematic.put(pos, state);
        minY = Math.min(minY, pos.getY());
        maxY = Math.max(maxY, pos.getY());
    }

    private void normalize() {
        if (schematic.isEmpty()) return;
        int offX = Integer.MAX_VALUE, offZ = Integer.MAX_VALUE, offY = minY;
        for (BlockPos p : schematic.keySet()) {
            offX = Math.min(offX, p.getX());
            offZ = Math.min(offZ, p.getZ());
        }
        Map<BlockPos, BlockState> moved = new HashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : schematic.entrySet()) {
            BlockPos p = e.getKey();
            moved.put(new BlockPos(p.getX() - offX, p.getY() - offY, p.getZ() - offZ), e.getValue());
        }
        schematic.clear();
        schematic.putAll(moved);
        maxY -= offY;
        minY = 0;
    }

    private static int ceilLog2(int n) {
        int bits = 0, v = n - 1;
        while (v > 0) { bits++; v >>= 1; }
        return Math.max(1, bits);
    }

    private static void msg(Minecraft mc, String s) {
        autismclient.util.AutismClientMessaging.sendPrefixed("§d[Schematic] §f" + s);
    }

    @Override public String info() {
        if (!loaded) return "no schematic";
        return placed.size() + "/" + totalBlocks + " (layer " + (currentLayer - minY + 1) + "/" + (maxY - minY + 1) + ")";
    }
}
