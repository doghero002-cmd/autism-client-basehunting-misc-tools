package com.autism.seedcracker.modules;

import java.util.HashSet;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.function.Predicate;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkScanHelper;
import com.autism.seedcracker.util.RotationController;
import com.autism.seedcracker.util.tunnel.DirectionalPathfinder;
import com.autism.seedcracker.util.tunnel.ParkourHelper;
import com.autism.seedcracker.util.tunnel.PathScanner;
import com.autism.seedcracker.util.tunnel.SafetyValidator;
import com.autism.seedcracker.util.tunnel.SimpleSneakCentering;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import autismclient.util.AutismNotifications;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Tunnel Base Finder (AI).
 *
 * Digs a tunnel in the direction you face using the ported Krypton tunneling AI: it scans the
 * path ahead for hazards (lava, water, falling blocks, drops, dangerous blocks, suffocation
 * "sandwich traps"), digs through solid blocks with human-like smooth rotations, detours around
 * hazards it can't mine through, parkour-jumps small gaps, and recovers when it gets stuck.
 * While tunneling it watches the surrounding chunks for player-base signs (storage blocks,
 * monster spawners) and alerts/logs them.
 *
 * Port of the Krypton "TunnelBaseFinder" + its dev.FORE.AI pathing package to the AUTISM API
 * (Mojang 26.2). The original's DonutSMP shop-GUI totem-buying and mixin-based spawner counter
 * depend on a live server / a mixin we can't add from an addon, so those parts are omitted; the
 * tunneling + base-detection core is faithful.
 *
 * WARNING: automated digging/movement may flag anti-cheats.
 */
public final class TunnelBaseFinderModule extends Module {

    private enum State { CENTERING, MINING, DETOUR, ROTATING, STOPPED }

    private final IntSetting scanAhead = add(new IntSetting(
            "scan-ahead", "Scan ahead (blocks)", 20, 5, 40, 1)
        .description("How far ahead the path is scanned for hazards.")
        .group("Tunnel"));
    private final BoolSetting strictGround = add(new BoolSetting(
            "strict-ground", "Strict ground check", false)
        .description("Require solid ground (no small drops) while mining forward.")
        .group("Tunnel"));
    private final BoolSetting allowParkour = add(new BoolSetting(
            "parkour", "Parkour over gaps", true)
        .description("Jump across small gaps instead of treating them as hazards.")
        .group("Tunnel"));
    private final BoolSetting centerFirst = add(new BoolSetting(
            "center-first", "Center on block first", true)
        .description("Sneak-center on the block before starting / changing direction.")
        .group("Tunnel"));
    private final IntSetting scanRadius = add(new IntSetting(
            "scan-radius", "Scan radius (chunks)", 2, 1, 8, 1)
        .description("Chunk bubble scanned for bases while tunneling.")
        .group("Detect"));
    private final IntSetting storageThreshold = add(new IntSetting(
            "storage-threshold", "Storage threshold", 5, 1, 100, 1)
        .description("Storage blocks in a chunk needed to flag it as a base.")
        .group("Detect"));
    private final BoolSetting detectSpawners = add(new BoolSetting(
            "spawners", "Detect spawners", true)
        .description("Alert when a monster spawner is found while tunneling.")
        .group("Detect"));
    private final BoolSetting notify = add(new BoolSetting(
            "notify", "Notifications", true)
        .description("Toast + chat + sound when a base is found.")
        .group("Detect"));
    private final BoolSetting pauseOnFind = add(new BoolSetting(
            "pause-on-find", "Pause on find", false)
        .description("Stop tunneling when a base is found (else keep digging).")
        .group("Detect"));

    private static final Set<net.minecraft.world.level.block.Block> STORAGE = buildStorage();

    // AI components.
    private final PathScanner scanner = new PathScanner();
    private final SafetyValidator safety = new SafetyValidator();
    private final DirectionalPathfinder pathfinder = new DirectionalPathfinder(scanner);
    private final ParkourHelper parkour = new ParkourHelper();
    private final SimpleSneakCentering centering = new SimpleSneakCentering();
    private final RotationController rotation = new RotationController();

    private State state = State.MINING;
    private Direction direction = Direction.NORTH;
    private BlockPos miningTarget = null;
    private final Set<ChunkPos> notified = new HashSet<>();

    public TunnelBaseFinderModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":tunnel-base-finder", "Tunnel Base Finder", category,
            "Digs an AI-driven tunnel (hazard avoidance + smooth turns) and alerts on bases it passes. WARNING: may flag anti-cheats.");
    }

    private static Set<net.minecraft.world.level.block.Block> buildStorage() {
        Set<net.minecraft.world.level.block.Block> s = new HashSet<>();
        s.add(Blocks.CHEST);
        s.add(Blocks.BARREL);
        s.add(Blocks.SHULKER_BOX);
        s.addAll(Blocks.DYED_SHULKER_BOX.asList());
        s.add(Blocks.HOPPER);
        s.add(Blocks.ENDER_CHEST);
        s.add(Blocks.TRAPPED_CHEST);
        return s;
    }

    @Override
    public void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            AutismClientMessaging.sendPrefixed("§cTunnel Base Finder: you must be in a world.");
            setEnabledSilently(false);
            return;
        }
        notified.clear();
        safety.reset();
        miningTarget = null;
        direction = mc.player.getDirection();
        pathfinder.setInitialDirection(direction);
        rotation.settings(true, 4.5, 0.8, true, 0.3);
        rotation.setRandomVariation(1.5);
        state = centerFirst.get() && centering.startCentering() ? State.CENTERING : State.MINING;

        AutismClientMessaging.sendPrefixed("§aTunnel Base Finder: tunneling " + direction.getName() + " (AI).");
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        stopMining(mc);
        centering.stopCentering();
        releaseKeys();
        state = State.STOPPED;
        notified.clear();
    }

    @Override
    public void onGameLeft() {
        setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        scanAround(mc);

        // Advance any in-progress smooth rotation.
        rotation.update();

        // Safety / stuck recovery.
        if (!safety.canContinue(mc.player, 320)) {
            AutismClientMessaging.sendPrefixed("§eTunnel Base Finder: stopping (unsafe / tool broken).");
            setEnabled(false);
            return;
        }
        SafetyValidator.StuckRecoveryAction recovery = safety.checkAndHandleStuck(mc.player, SafetyValidator.MiningMode.NORMAL);
        if (handleRecovery(mc, recovery)) return;
        if (safety.needsModuleReset()) {
            safety.acknowledgeReset();
            pathfinder.setInitialDirection(mc.player.getDirection());
            direction = mc.player.getDirection();
        }

        switch (state) {
            case CENTERING -> {
                if (!centering.tick()) state = State.MINING;
            }
            case MINING -> tickMining(mc);
            case DETOUR -> tickDetour(mc);
            case ROTATING -> {
                if (!rotation.isRotating()) state = State.MINING;
            }
            case STOPPED -> {}
        }
    }

    // ---- primary mining / forward movement ----

    private void tickMining(Minecraft mc) {
        BlockPos playerPos = mc.player.blockPosition();

        PathScanner.ScanResult scan = scanner.scanDirection(playerPos, direction, scanAhead.get(), 3, strictGround.get());

        if (!scan.isSafe()) {
            BlockPos ahead = playerPos.relative(direction);
            BlockPos aheadUp = ahead.above();

            // Diggable wall directly ahead (not a fluid / drop): mine through it.
            if (scan.getHazardType() != PathScanner.HazardType.UNSAFE_GROUND
                && scan.getHazardType() != PathScanner.HazardType.LAVA
                && scan.getHazardType() != PathScanner.HazardType.WATER) {
                if (tryMine(mc, ahead) || tryMine(mc, aheadUp)) return;
            }

            // Parkour over a small gap.
            if (allowParkour.get() && scan.getHazardType() == PathScanner.HazardType.UNSAFE_GROUND) {
                ParkourHelper.JumpCheck jump = parkour.checkJumpOpportunity(mc.player, direction);
                if (jump.canProceed && jump.shouldJump && parkour.startJump(jump.jumpTarget)) {
                    moveForward(mc, true);
                    return;
                }
            }

            // Plan / start a detour.
            DirectionalPathfinder.PathPlan plan = pathfinder.calculateDetour(playerPos, scan);
            if (plan.newPrimaryDirection != null) {
                direction = plan.newPrimaryDirection;
                pathfinder.setInitialDirection(direction);
                faceDirectionSmooth(direction);
                state = State.ROTATING;
                return;
            }
            if (plan.needsDetour && !plan.waypoints.isEmpty()) {
                state = State.DETOUR;
                return;
            }
            AutismClientMessaging.sendPrefixed("§eTunnel Base Finder: path blocked, no detour found. Stopping.");
            setEnabled(false);
            return;
        }

        // Path is safe: face the direction and move forward, digging whatever is directly ahead.
        faceDirectionSmooth(direction);
        parkour.tickJump();
        if (tryMine(mc, playerPos.relative(direction)) || tryMine(mc, playerPos.relative(direction).above())) {
            return;
        }
        moveForward(mc, true);
    }

    // ---- detour following ----

    private void tickDetour(Minecraft mc) {
        Queue<BlockPos> waypoints = pathfinder.currentDetour;
        BlockPos next = waypoints.peek();
        if (next == null) {
            pathfinder.completeDetour();
            state = State.MINING;
            return;
        }
        if (mc.player.blockPosition().distManhattan(next) <= 1) {
            pathfinder.getNextWaypoint();
            return;
        }
        faceTowardSmooth(next);
        if (tryMine(mc, mc.player.blockPosition().relative(direction))
            || tryMine(mc, mc.player.blockPosition().relative(direction).above())) {
            return;
        }
        moveForward(mc, true);
    }

    // ---- mining helper ----

    /** Mine the given block if it's solid and in reach. Returns true while actively mining. */
    private boolean tryMine(Minecraft mc, BlockPos pos) {
        if (pos == null) return false;
        BlockState st = mc.level.getBlockState(pos);
        if (st.isAir()) return false;
        if (st.getBlock() == Blocks.BEDROCK) return false;
        net.minecraft.world.phys.Vec3 center = new net.minecraft.world.phys.Vec3(
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (mc.player.getEyePosition().distanceTo(center) > 5.0) return false;

        faceTowardSmooth(pos);
        miningTarget = pos;
        mc.options.keyAttack.setDown(true);
        if (mc.gameMode != null) {
            mc.gameMode.startDestroyBlock(pos, Direction.UP);
            mc.gameMode.continueDestroyBlock(pos, Direction.UP);
        }
        mc.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private void stopMining(Minecraft mc) {
        if (mc.options != null) mc.options.keyAttack.setDown(false);
        if (miningTarget != null && mc.gameMode != null) {
            mc.gameMode.stopDestroyBlock();
        }
        miningTarget = null;
    }

    // ---- movement / rotation helpers ----

    private void moveForward(Minecraft mc, boolean forward) {
        releaseKeys();
        mc.options.keyUp.setDown(forward);
        mc.options.keySprint.setDown(forward);
    }

    private void releaseKeys() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) return;
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
    }

    private void faceDirectionSmooth(Direction dir) {
        float targetYaw = switch (dir) {
            case SOUTH -> 0.0f;
            case WEST -> 90.0f;
            case NORTH -> 180.0f;
            case EAST -> -90.0f;
            default -> {
                Minecraft mc = Minecraft.getInstance();
                yield mc.player != null ? mc.player.getYRot() : 0.0f;
            }
        };
        if (!rotation.isRotating()) rotation.rotateTo(targetYaw, 2.0f, null);
    }

    private void faceTowardSmooth(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        double dx = pos.getX() + 0.5 - mc.player.getX();
        double dz = pos.getZ() + 0.5 - mc.player.getZ();
        double dy = pos.getY() + 0.5 - mc.player.getEyeY();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        if (!rotation.isRotating()) rotation.rotateTo(yaw, pitch, null);
    }

    // ---- stuck recovery ----

    private boolean handleRecovery(Minecraft mc, SafetyValidator.StuckRecoveryAction action) {
        switch (action) {
            case JUMP_FORWARD -> {
                if (mc.player.onGround()) mc.player.jumpFromGround();
                moveForward(mc, true);
                return true;
            }
            case STOP_MOVEMENT -> {
                releaseKeys();
                return true;
            }
            case RECALCULATE_PATH -> {
                pathfinder.completeDetour();
                pathfinder.setInitialDirection(direction);
                state = State.MINING;
                return true;
            }
            case FIND_NEW_SPOT, PATH_BLOCKED_RTP -> {
                direction = direction.getClockWise();
                pathfinder.setInitialDirection(direction);
                faceDirectionSmooth(direction);
                state = State.ROTATING;
                return true;
            }
            case NEEDS_RESET -> {
                safety.acknowledgeReset();
                onEnable();
                return true;
            }
            default -> { return false; }
        }
    }

    // ---- base detection ----

    private void scanAround(Minecraft mc) {
        java.util.List<LevelChunk> chunks = ChunkScanHelper.loadedChunksAround(mc, scanRadius.get());
        int threshold = storageThreshold.get();
        Predicate<BlockState> isStorage = st -> STORAGE.contains(st.getBlock());

        for (LevelChunk chunk : chunks) {
            ChunkPos pos = chunk.getPos();
            if (notified.contains(pos)) continue;

            int storage = ChunkScanHelper.countBlocksInChunk(chunk, isStorage, threshold);
            boolean foundSpawner = detectSpawners.get() && hasSpawner(chunk);

            if (storage >= threshold || foundSpawner) {
                notified.add(pos);
                onBaseFound(mc, pos, storage, foundSpawner);
                if (pauseOnFind.get()) {
                    setEnabled(false);
                    return;
                }
            }
        }
    }

    private static boolean hasSpawner(LevelChunk chunk) {
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be instanceof SpawnerBlockEntity) return true;
        }
        return false;
    }

    private void onBaseFound(Minecraft mc, ChunkPos pos, int storage, boolean spawner) {
        if (!notify.get()) return;
        int bx = pos.getMinBlockX();
        int bz = pos.getMinBlockZ();
        String what = spawner ? "§5spawner" : ("§6" + storage + " storage");
        String msg = "Base sign (" + what + "§f) at X:" + bx + " Z:" + bz;
        AutismNotifications.warning("Tunnel base found: " + what + " at " + bx + " " + bz);
        AutismClientMessaging.sendPrefixed("§d[TunnelBaseFinder] §f" + msg);
        logToBaseFile(mc, bx, bz, spawner);
        if (mc.player != null) {
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
    }

    private void logToBaseFile(Minecraft mc, int x, int z, boolean spawner) {
        try {
            java.nio.file.Path f = autismclient.AutismClientAddon.FOLDER.toPath().resolve("bases.txt");
            java.nio.file.Files.createDirectories(f.getParent());
            String dim = mc.level != null ? mc.level.dimension().identifier().toString() : "unknown";
            String block = spawner ? "minecraft:spawner" : "minecraft:chest";
            int y = mc.player != null ? (int) mc.player.getY() : 0;
            String line = String.format(Locale.ROOT, "%d %d %d  %s  %s  %s%n",
                x, y, z, dim, block, new java.sql.Timestamp(System.currentTimeMillis()));
            java.nio.file.Files.writeString(f, line, java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    @Override
    public String info() {
        return state == State.DETOUR ? "detour" : (state == State.MINING ? "tunneling" : state.name().toLowerCase(Locale.ROOT));
    }
}
