package com.autism.seedcracker.modules;

import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkScanHelper;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import autismclient.util.AutismNotifications;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Tunnel Base Finder.
 *
 * Digs a straight tunnel in the best cardinal direction, mining forward while staying on a fixed
 * coordinate line, sidestepping around obstacles (lava, water, gravel/sand falls, caves/drops),
 * and humanizing its movement (crosshair drift, micro-pauses, sneak/sprint/strafe variation, and
 * smoothed non-snapping rotations) so it doesn't look like a bot. If a player comes within range
 * it briefly turns and defends itself. While tunneling it watches loaded chunks for storage
 * blocks / spawners and alerts + logs bases to bases.txt.
 *
 * Faithful port of the Xenon "TunnelBaseFinder" (client.xenon) to the AUTISM API (Mojang 26.2).
 * The original's DonutSMP shop-GUI auto-totem/mend (hardcoded slot clicks) and Discord webhook
 * depend on a live server / external service, so they're omitted; the straight-line digging,
 * obstacle avoidance, humanization, player defense, and base detection are ported.
 *
 * WARNING: automated digging/movement may flag anti-cheats.
 */
public final class TunnelBaseFinderModule extends Module {

    private final IntSetting minimumStorage = add(new IntSetting(
            "minimum-storage", "Minimum storage", 100, 1, 500, 1)
        .description("Storage block entities in loaded chunks needed to call it a base.")
        .group("Detect"));
    private final BoolSetting spawners = add(new BoolSetting(
            "spawners", "Detect spawners", true)
        .description("Alert when monster spawners are found while tunneling.")
        .group("Detect"));
    private final BoolSetting notify = add(new BoolSetting(
            "notify", "Notifications", true)
        .description("Toast + chat + sound when a base is found.")
        .group("Detect"));
    private final BoolSetting pauseOnFind = add(new BoolSetting(
            "pause-on-find", "Pause on find", false)
        .description("Stop tunneling when a base is found (else keep digging).")
        .group("Detect"));
    private final BoolSetting humanize = add(new BoolSetting(
            "humanize", "Humanize movement", true)
        .description("Crosshair drift, micro-pauses, sneak/sprint/strafe variation, smoothed rotations.")
        .group("Tunnel"));
    private final BoolSetting defendPlayers = add(new BoolSetting(
            "defend-players", "Defend vs players", true)
        .description("Turn and hit a player that comes within 16 blocks.")
        .group("Tunnel"));

    private static final Set<Block> STORAGE = buildStorage();

    // === tunnel state ===
    private enum AvoidState { NONE, SIDESTEP, FORWARD, RETURN }
    private Direction tunnelDirection = null;
    private double fixedCoord;
    private boolean fixedCoordIsX;
    private AvoidState avoidState = AvoidState.NONE;
    private int avoidSideDirection = 0;
    private int avoidForwardCount = 0;
    private int stuckTicks = 0;
    private BlockPos lastBlockPos = null;
    private int waitTicks = 0;
    private int avoidCooldown = 0;
    private boolean miningLookDown = false;

    // === player defense ===
    private Player targetPlayer = null;
    private int playerAttackHits = 0;
    private int playerAttackCooldown = 0;
    private boolean playerDetected = false;
    private int playerWaitTicks = 0;
    private float playerLookSpeed = 0.1f;

    // === humanization ===
    private final Random rng = new Random();
    private float currentSmoothedYaw = 0f;
    private float currentSmoothedPitch = 2.0f;
    private boolean yawInitialized = false;
    private float driftYawOffset = 0f, driftPitchOffset = 0f;
    private float driftYawTarget = 0f, driftPitchTarget = 0f;
    private int driftTicksRemaining = 0, driftTicksDuration = 1;
    private int microPauseTicks = 0, microPauseCooldown = 0;
    private int idleBreakTicks = 0, idleBreakCooldown = 0;
    private int sprintTicks = 0, sprintCooldown = 0;
    private int sneakTicksRemaining = 0, sneakCooldown = 0;
    private boolean sneakHoldMode = false, sneakToggle = false;
    private int sneakBurstsLeft = 0;
    private int strafeDriftTicks = 0, strafeDriftCooldown = 0;
    private boolean strafeDriftLeft = false;
    private int walkPauseTicks = 0, walkPauseCooldown = 0;
    private float turnSpeedMultiplier = 0.08f;
    private float turnYawOvershoot = 0f, turnOvershootDecay = 0.03f;
    private int turnReactionTicks = 0;
    private float lastRawTargetYaw = 0f;
    private float basePitchBias = 2.0f;
    private long totalTicks = 0;

    private static final float MAX_YAW_DRIFT = 0.7f;
    private static final float MAX_PITCH_DRIFT = 0.3f;

    private int spawnerCount = 0;
    private final Set<ChunkPos> notified = new HashSet<>();

    public TunnelBaseFinderModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":tunnel-base-finder", "Tunnel Base Finder", category,
            "Digs a straight tunnel (Xenon logic) with obstacle avoidance + humanization, and alerts on bases. WARNING: may flag anti-cheats.");
    }

    private static Set<Block> buildStorage() {
        Set<Block> s = new HashSet<>();
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
        notified.clear();
        spawnerCount = 0;
        totalTicks = 0;
        yawInitialized = false;
        avoidState = AvoidState.NONE;
        avoidSideDirection = 0;
        avoidForwardCount = 0;
        stuckTicks = 0;
        lastBlockPos = null;
        waitTicks = 0;
        avoidCooldown = 0;
        targetPlayer = null;
        playerDetected = false;
        sneakTicksRemaining = 0;
        sneakCooldown = 0;
        microPauseTicks = 0;
        microPauseCooldown = 0;
        idleBreakTicks = 0;
        idleBreakCooldown = randomRange(600, 1800);
        sprintTicks = 0;
        sprintCooldown = 0;
        strafeDriftTicks = 0;
        strafeDriftCooldown = 0;
        walkPauseTicks = 0;
        walkPauseCooldown = randomRange(60, 200);
        pickNewDriftTarget();
        initTunnelDirection(mc);
        if (tunnelDirection != null) {
            AutismClientMessaging.sendPrefixed("§aTunnel Base Finder: tunneling " + tunnelDirection.getName() + " (Xenon).");
        }
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null) {
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
            mc.options.keyUp.setDown(false);
            mc.options.keyShift.setDown(false);
            mc.options.keySprint.setDown(false);
            mc.options.keyAttack.setDown(false);
            mc.options.keyJump.setDown(false);
        }
        tunnelDirection = null;
        notified.clear();
    }

    @Override
    public void onGameLeft() {
        setEnabledSilently(false);
    }

    private void initTunnelDirection(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            tunnelDirection = null;
            return;
        }
        tunnelDirection = pickBestDirection(mc);
        if (tunnelDirection == null) tunnelDirection = mc.player.getDirection();
        if (tunnelDirection == Direction.NORTH || tunnelDirection == Direction.SOUTH) {
            fixedCoordIsX = true;
            fixedCoord = Math.floor(mc.player.getX()) + 0.5;
        } else {
            fixedCoordIsX = false;
            fixedCoord = Math.floor(mc.player.getZ()) + 0.5;
        }
    }

    private Direction pickBestDirection(Minecraft mc) {
        if (mc.player == null || mc.level == null) return null;
        BlockPos playerPos = mc.player.blockPosition();
        Direction[] dirs = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        Direction bestDir = mc.player.getDirection();
        int bestScore = -1;
        for (Direction dir : dirs) {
            int score = 0;
            for (int i = 1; i <= 8; i++) {
                BlockPos p = playerPos.relative(dir, i);
                if (!mc.level.getBlockState(p).isAir() && !isLiquid(mc, p) && !isHazardous(mc, p)) score++;
                if (!mc.level.getBlockState(p.above()).isAir() && !isLiquid(mc, p.above()) && !isHazardous(mc, p.above())) score++;
                if (!mc.level.getBlockState(p.below()).isAir() && !isHazardous(mc, p.below())) score++;
            }
            for (int i = 1; i <= 4; i++) {
                BlockPos p = playerPos.relative(dir, i);
                if (isHazardous(mc, p) || isHazardous(mc, p.above())) score -= 5;
                if (isLiquid(mc, p) || isLiquid(mc, p.above())) score -= 10;
            }
            if (score > bestScore) { bestScore = score; bestDir = dir; }
        }
        return bestDir;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (tunnelDirection == null) {
            initTunnelDirection(mc);
            if (tunnelDirection == null) return;
        }

        totalTicks++;
        if (humanize.get()) tickHumanization(mc);

        if (idleBreakTicks > 0) { idleBreakTicks--; mc.options.keyUp.setDown(false); }
        if (microPauseTicks > 0) { microPauseTicks--; mc.options.keyUp.setDown(false); }
        if (waitTicks > 0) { waitTicks--; mc.options.keyUp.setDown(false); return; }

        // Player defense
        if (playerDetected) { tickPlayerAttack(mc); return; }
        if (defendPlayers.get()) {
            Player nearest = findNearestPlayer(mc, 16.0);
            if (nearest != null) {
                targetPlayer = nearest;
                playerDetected = true;
                playerAttackHits = 0;
                playerAttackCooldown = 0;
                playerWaitTicks = 60;
                playerLookSpeed = 0.04f + rng.nextFloat() * 0.12f;
                mc.options.keyUp.setDown(false);
                return;
            }
        }

        // Compute target yaw (+ avoidance turns)
        float targetYaw = getDirectionYaw(tunnelDirection);
        boolean isAvoiding = false;
        if (avoidState == AvoidState.SIDESTEP) {
            targetYaw = getDirectionYaw(getSideDirection(tunnelDirection, avoidSideDirection));
            isAvoiding = true;
        } else if (avoidState == AvoidState.RETURN) {
            targetYaw = getDirectionYaw(getSideDirection(tunnelDirection, -avoidSideDirection));
            isAvoiding = true;
        }

        tickCrosshairDrift();
        float driftedYaw = targetYaw + driftYawOffset;
        float pitchTarget = miningLookDown ? 35.0f : basePitchBias;
        float driftedPitch = pitchTarget + driftPitchOffset;

        if (!yawInitialized) {
            currentSmoothedYaw = mc.player.getYRot();
            currentSmoothedPitch = mc.player.getXRot();
            yawInitialized = true;
        }

        if (avoidCooldown > 0) avoidCooldown--;

        float yawChange = Math.abs(angleDiff(lastRawTargetYaw, targetYaw));
        if (yawChange > 5f) {
            turnSpeedMultiplier = yawChange > 80f
                ? 0.06f + rng.nextFloat() * 0.08f
                : 0.04f + rng.nextFloat() * 0.06f;
            turnYawOvershoot = gaussian(0f, 1.0f);
            turnOvershootDecay = 0.025f + rng.nextFloat() * 0.035f;
            turnReactionTicks = randomRange(1, 3);
            lastRawTargetYaw = targetYaw;
        }

        if (turnReactionTicks > 0) {
            turnReactionTicks--;
            mc.player.setYRot(currentSmoothedYaw + gaussian(0f, 0.02f));
            mc.player.setXRot(clampPitch(currentSmoothedPitch + gaussian(0f, 0.01f)));
        } else {
            turnYawOvershoot = lerp(turnYawOvershoot, 0f, turnOvershootDecay);
            float rotSpeed = turnSpeedMultiplier;
            if (isAvoiding) rotSpeed = 0.08f + rng.nextFloat() * 0.10f;
            float finalYaw = driftedYaw + turnYawOvershoot;
            currentSmoothedYaw = lerpAngle(currentSmoothedYaw, finalYaw, rotSpeed);
            currentSmoothedPitch = lerp(currentSmoothedPitch, driftedPitch, rotSpeed);
            mc.player.setYRot(currentSmoothedYaw + gaussian(0f, 0.015f));
            mc.player.setXRot(clampPitch(currentSmoothedPitch + gaussian(0f, 0.01f)));
        }

        correctPosition(mc);

        // Base detection + tunneling.
        notifyFound(mc);
        tickTunneling(mc);
    }

    // ---- main tunneling state machine ----

    private void tickTunneling(Minecraft mc) {
        BlockPos currentPos = mc.player.blockPosition();

        // Emergency: mine gravel/sand we're standing in.
        if (isHazardous(mc, currentPos) && !isLiquid(mc, currentPos)) { mineDirect(mc, currentPos); mc.options.keyUp.setDown(false); return; }
        BlockPos headPos = currentPos.above();
        if (isHazardous(mc, headPos) && !isLiquid(mc, headPos)) { mineDirect(mc, headPos); mc.options.keyUp.setDown(false); return; }

        if (lastBlockPos != null && lastBlockPos.equals(currentPos)) stuckTicks++; else stuckTicks = 0;
        lastBlockPos = currentPos;

        switch (avoidState) {
            case NONE -> tickNormalTunneling(mc);
            case SIDESTEP -> tickSidestep(mc);
            case FORWARD -> tickAvoidForward(mc);
            case RETURN -> tickReturn(mc);
        }

        if (stuckTicks > 40 && avoidState == AvoidState.NONE) {
            mc.options.keyUp.setDown(false);
            startObstacleAvoidance(mc);
            stuckTicks = 0;
        } else if (stuckTicks > 80) {
            avoidSideDirection = -avoidSideDirection;
            avoidState = AvoidState.SIDESTEP;
            avoidForwardCount = 0;
            stuckTicks = 0;
        }
    }

    private void tickNormalTunneling(Minecraft mc) {
        BlockPos playerPos = mc.player.blockPosition();
        BlockPos nextPos = playerPos.relative(tunnelDirection);

        if (!isGroundSafe(mc, tunnelDirection, 2)) {
            mc.options.keyUp.setDown(false);
            if (avoidCooldown <= 0) { startObstacleAvoidance(mc); avoidCooldown = 20; }
            return;
        }

        // Cave detection: scan ahead for mostly-air / hazards / deep drops.
        int solid = 0, air = 0, hazard = 0, caveDepth = 0;
        Direction sideDir = tunnelDirection.getClockWise();
        for (int fwd = 1; fwd <= 6; fwd++) {
            for (int side = -3; side <= 3; side++) {
                BlockPos scan = playerPos.relative(tunnelDirection, fwd).relative(sideDir, side);
                if (!mc.level.getBlockState(scan).isAir()) { if (isHazardous(mc, scan) || isLiquid(mc, scan)) hazard++; else solid++; } else air++;
                if (!mc.level.getBlockState(scan.above()).isAir()) { if (isHazardous(mc, scan.above()) || isLiquid(mc, scan.above())) hazard++; else solid++; } else air++;
                BlockPos ground = scan.below();
                if (mc.level.getBlockState(ground).isAir()) {
                    air += 2;
                    int drop = 0;
                    for (int y = 1; y <= 8 && mc.level.getBlockState(ground.below(y)).isAir(); y++) drop++;
                    if (drop >= 2) caveDepth += drop;
                }
            }
        }
        if (air > solid * 2 || hazard > 4 || caveDepth > 10) {
            mc.options.keyUp.setDown(false);
            if (avoidCooldown <= 0) { startObstacleAvoidance(mc); avoidCooldown = 20; }
            return;
        }

        // Gap: jump small gaps, avoid big ones.
        if (hasGapAhead(mc, tunnelDirection, 1)) {
            BlockPos twoAhead = nextPos.relative(tunnelDirection);
            boolean landing = !mc.level.getBlockState(twoAhead.below()).isAir() && !isHazardous(mc, twoAhead.below());
            if (landing) { mc.options.keyJump.setDown(true); mc.options.keyUp.setDown(true); return; }
            mc.options.keyUp.setDown(false);
            startObstacleAvoidance(mc);
            return;
        }
        mc.options.keyJump.setDown(false);
        if (mc.level.getBlockState(playerPos.below()).isAir()) { mc.options.keyJump.setDown(true); mc.options.keyUp.setDown(true); return; }

        boolean feetBlocked = !mc.level.getBlockState(nextPos).isAir();
        boolean headBlocked = !mc.level.getBlockState(nextPos.above()).isAir();
        boolean feetHazard = isHazardous(mc, nextPos);
        boolean headHazard = isHazardous(mc, nextPos.above());
        boolean feetLiquid = isLiquid(mc, nextPos);
        boolean headLiquid = isLiquid(mc, nextPos.above());

        if ((feetBlocked && feetLiquid) || (headBlocked && headLiquid)) { mc.options.keyUp.setDown(false); startObstacleAvoidance(mc); return; }

        BlockPos behindFeet = nextPos.relative(tunnelDirection);
        BlockPos behindHead = nextPos.above().relative(tunnelDirection);

        if (feetBlocked && feetHazard && !feetLiquid) {
            if (isHazardous(mc, behindFeet) || hasHazardAbove(mc, nextPos, 5)) { mc.options.keyUp.setDown(false); startObstacleAvoidance(mc); return; }
        }
        if (headBlocked && headHazard && !headLiquid) {
            if (isHazardous(mc, behindHead) || hasHazardAbove(mc, nextPos.above(), 5)) { mc.options.keyUp.setDown(false); startObstacleAvoidance(mc); return; }
        }
        if (!mc.level.getBlockState(nextPos.above()).isAir() && !isHazardous(mc, nextPos.above())) {
            // solid barrier above
        } else if (hasHazardAbove(mc, nextPos.above(), 5)) { mc.options.keyUp.setDown(false); startObstacleAvoidance(mc); return; }

        if (feetBlocked && !feetHazard && isHazardous(mc, behindFeet)) { mc.options.keyUp.setDown(false); startObstacleAvoidance(mc); return; }
        if (headBlocked && !headHazard && isHazardous(mc, behindHead)) { mc.options.keyUp.setDown(false); startObstacleAvoidance(mc); return; }
        if (feetBlocked && !feetHazard && hasHazardAbove(mc, behindHead, 5)) { mc.options.keyUp.setDown(false); startObstacleAvoidance(mc); return; }

        // Mine the block we're looking at (crosshair), like holding the mouse button.
        boolean didMine = false;
        if ((feetBlocked && !feetHazard && !feetLiquid) || (headBlocked && !headHazard && !headLiquid)) {
            HitResult hit = mc.hitResult;
            if (hit instanceof BlockHitResult bhr) {
                BlockPos bp = bhr.getBlockPos();
                if (!mc.level.getBlockState(bp).isAir() && !isHazardous(mc, bp)) {
                    handleBlockBreaking(mc, true, bhr);
                    didMine = true;
                }
            }
        }
        if (!didMine) handleBlockBreaking(mc, false, null);

        // Periodic walk pauses.
        if (walkPauseTicks > 0) { walkPauseTicks--; mc.options.keyUp.setDown(false); return; }
        if (walkPauseCooldown > 0) walkPauseCooldown--;
        else if (rng.nextFloat() < 0.03f) {
            walkPauseTicks = randomRange(10, 40);
            walkPauseCooldown = randomRange(60, 200);
            mc.options.keyUp.setDown(false);
            return;
        }

        mc.options.keyUp.setDown(true);
        stuckTicks = 0;
    }

    private void startObstacleAvoidance(Minecraft mc) {
        Direction rightDir = tunnelDirection.getClockWise();
        Direction leftDir = tunnelDirection.getCounterClockWise();
        boolean rightSafe = isSideSafe(mc, rightDir);
        boolean leftSafe = isSideSafe(mc, leftDir);
        boolean rightClear = isPathClearAndSafe(mc, rightDir, 2);
        boolean leftClear = isPathClearAndSafe(mc, leftDir, 2);

        if (rightClear && rightSafe) avoidSideDirection = 1;
        else if (leftClear && leftSafe) avoidSideDirection = -1;
        else if (rightSafe) avoidSideDirection = 1;
        else if (leftSafe) avoidSideDirection = -1;
        else avoidSideDirection = rng.nextBoolean() ? 1 : -1;

        avoidState = AvoidState.SIDESTEP;
        avoidForwardCount = 0;
        stuckTicks = 0;
    }

    private void tickSidestep(Minecraft mc) {
        double sideCoord = fixedCoordIsX ? mc.player.getX() : mc.player.getZ();
        double target = fixedCoord + avoidSideDirection;
        double diff = Math.abs(sideCoord - target);

        if (diff < 0.3) {
            avoidState = AvoidState.FORWARD;
            avoidForwardCount = 0;
            mc.options.keyUp.setDown(false);
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
            waitTicks = randomRange(3, 8);
        } else {
            Direction sideDir = getSideDirection(tunnelDirection, avoidSideDirection);
            BlockPos sidePos = mc.player.blockPosition().relative(sideDir);
            boolean feetBlocked = !mc.level.getBlockState(sidePos).isAir();
            boolean headBlocked = !mc.level.getBlockState(sidePos.above()).isAir();
            if (headBlocked && !isHazardous(mc, sidePos.above())) mineDirect(mc, sidePos.above());
            else if (feetBlocked && !isHazardous(mc, sidePos)) mineDirect(mc, sidePos);

            float yawDiff = Math.abs(angleDiff(mc.player.getYRot(), getDirectionYaw(sideDir)));
            mc.options.keyUp.setDown(yawDiff < 15.0f);
        }
    }

    private void tickAvoidForward(Minecraft mc) {
        if (!isGroundSafe(mc, tunnelDirection, 2)) {
            mc.options.keyUp.setDown(false);
            avoidForwardCount++;
            if (avoidForwardCount > 60) {
                fixedCoord += avoidSideDirection;
                avoidState = AvoidState.SIDESTEP;
                avoidForwardCount = 0;
                waitTicks = 5;
            }
            return;
        }
        BlockPos ahead = mc.player.blockPosition().relative(tunnelDirection);
        boolean feetBlocked = !mc.level.getBlockState(ahead).isAir();
        boolean headBlocked = !mc.level.getBlockState(ahead.above()).isAir();
        if (headBlocked && !isHazardous(mc, ahead.above())) mineDirect(mc, ahead.above());
        else if (feetBlocked && !isHazardous(mc, ahead)) mineDirect(mc, ahead);

        mc.options.keyUp.setDown(true);
        avoidForwardCount++;

        if (avoidForwardCount > 6) {
            Direction returnDir = getSideDirection(tunnelDirection, -avoidSideDirection);
            if (isPathClearAndSafe(mc, returnDir, 1) && isOriginalLineSafe(mc, 4)) {
                avoidState = AvoidState.RETURN;
                mc.options.keyUp.setDown(false);
                waitTicks = randomRange(3, 8);
            }
        }
    }

    private void tickReturn(Minecraft mc) {
        double current = fixedCoordIsX ? mc.player.getX() : mc.player.getZ();
        double diff = Math.abs(current - fixedCoord);

        if (diff < 0.3) {
            if (!isGroundSafe(mc, tunnelDirection, 2)) {
                avoidState = AvoidState.FORWARD;
                avoidForwardCount = 0;
                mc.options.keyUp.setDown(false);
                waitTicks = 5;
                return;
            }
            avoidState = AvoidState.NONE;
            mc.options.keyUp.setDown(false);
            stuckTicks = 0;
            avoidCooldown = 30;
            waitTicks = randomRange(3, 8);
        } else {
            Direction returnDir = getSideDirection(tunnelDirection, -avoidSideDirection);
            BlockPos returnPos = mc.player.blockPosition().relative(returnDir);
            boolean feetBlocked = !mc.level.getBlockState(returnPos).isAir();
            boolean headBlocked = !mc.level.getBlockState(returnPos.above()).isAir();
            if (headBlocked && !isHazardous(mc, returnPos.above())) mineDirect(mc, returnPos.above());
            else if (feetBlocked && !isHazardous(mc, returnPos)) mineDirect(mc, returnPos);

            float yawDiff = Math.abs(angleDiff(mc.player.getYRot(), getDirectionYaw(returnDir)));
            mc.options.keyUp.setDown(yawDiff < 15.0f);
        }
    }

    // ---- position correction (stay on the line) ----

    private void correctPosition(Minecraft mc) {
        if (avoidState != AvoidState.NONE) {
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
            return;
        }
        if (strafeDriftTicks > 0) {
            mc.options.keyLeft.setDown(strafeDriftLeft);
            mc.options.keyRight.setDown(!strafeDriftLeft);
            return;
        }
        double current = fixedCoordIsX ? mc.player.getX() : mc.player.getZ();
        double diff = fixedCoord - current;
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        if (Math.abs(diff) < 0.1) return;

        boolean pressLeft = false, pressRight = false;
        switch (tunnelDirection) {
            case SOUTH -> { if (diff > 0.1) pressLeft = true; else if (diff < -0.1) pressRight = true; }
            case NORTH -> { if (diff > 0.1) pressRight = true; else if (diff < -0.1) pressLeft = true; }
            case EAST -> { if (diff > 0.1) pressRight = true; else if (diff < -0.1) pressLeft = true; }
            case WEST -> { if (diff > 0.1) pressLeft = true; else if (diff < -0.1) pressRight = true; }
            default -> {}
        }
        mc.options.keyLeft.setDown(pressLeft);
        mc.options.keyRight.setDown(pressRight);
    }

    // ---- player defense ----

    private void tickPlayerAttack(Minecraft mc) {
        if (targetPlayer == null || !targetPlayer.isAlive()
            || mc.player.distanceToSqr(targetPlayer) > 256.0) {
            playerDetected = false;
            targetPlayer = null;
            playerAttackHits = 0;
            playerWaitTicks = 0;
            return;
        }
        if (playerWaitTicks > 0) {
            playerWaitTicks--;
            tickTunneling(mc);
            return;
        }
        mc.options.keyUp.setDown(false);
        double dx = targetPlayer.getX() - mc.player.getX();
        double dy = targetPlayer.getEyeY() - mc.player.getEyeY();
        double dz = targetPlayer.getZ() - mc.player.getZ();
        double dist = Math.hypot(dx, dz);
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, dist));

        float jitter = gaussian(0f, 0.02f);
        float speed = Math.max(0.03f, playerLookSpeed + jitter);
        currentSmoothedYaw = lerpAngle(currentSmoothedYaw, targetYaw, speed);
        currentSmoothedPitch = lerp(currentSmoothedPitch, targetPitch, speed);
        mc.player.setYRot(currentSmoothedYaw + gaussian(0f, 0.04f));
        mc.player.setXRot(clampPitch(currentSmoothedPitch + gaussian(0f, 0.04f) * 0.5f));

        if (Math.abs(angleDiff(currentSmoothedYaw, targetYaw)) > 15f) return;
        if (playerAttackCooldown > 0) { playerAttackCooldown--; return; }
        if (mc.player.distanceToSqr(targetPlayer) <= 16.0) {
            mc.gameMode.attack(mc.player, targetPlayer);
            mc.player.swing(InteractionHand.MAIN_HAND);
            playerAttackHits++;
            playerAttackCooldown = randomRange(6, 18);
        }
        if (playerAttackHits >= 2) {
            playerDetected = false;
            targetPlayer = null;
            playerAttackHits = 0;
            waitTicks = randomRange(15, 40);
        }
    }

    private Player findNearestPlayer(Minecraft mc, double range) {
        Player nearest = null;
        double nearestDist = range * range;
        for (Player p : mc.level.players()) {
            if (p == mc.player || p.isSpectator()) continue;
            double d = mc.player.distanceToSqr(p);
            if (d < nearestDist) { nearestDist = d; nearest = p; }
        }
        return nearest;
    }

    // ---- block breaking ----

    private void mineDirect(Minecraft mc, BlockPos pos) {
        if (mc.gameMode != null) {
            mc.gameMode.startDestroyBlock(pos, Direction.UP);
            mc.gameMode.continueDestroyBlock(pos, Direction.UP);
        }
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private void handleBlockBreaking(Minecraft mc, boolean breaking, BlockHitResult hit) {
        if (mc.player.isUsingItem()) return;
        if (breaking && hit != null && mc.gameMode != null) {
            BlockPos bp = hit.getBlockPos();
            mc.gameMode.startDestroyBlock(bp, hit.getDirection());
            mc.gameMode.continueDestroyBlock(bp, hit.getDirection());
            mc.player.swing(InteractionHand.MAIN_HAND);
        } else if (mc.gameMode != null) {
            mc.gameMode.stopDestroyBlock();
        }
    }

    // ---- base detection ----

    private void notifyFound(Minecraft mc) {
        int storage = 0;
        int spawner = 0;
        BlockPos spawnerPos = null;
        java.util.List<LevelChunk> chunks = ChunkScanHelper.loadedChunksAround(mc, 4);
        for (LevelChunk chunk : chunks) {
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                if (spawners.get() && be instanceof SpawnerBlockEntity) {
                    spawner++;
                    spawnerPos = be.getBlockPos();
                } else if (STORAGE.contains(be.getBlockState().getBlock())) {
                    storage++;
                }
            }
        }
        if (spawner > 0) spawnerCount++; else spawnerCount = 0;
        if (spawnerCount > 10 && spawnerPos != null) {
            onBaseFound(mc, "spawner", spawnerPos.getX(), spawnerPos.getY(), spawnerPos.getZ());
            spawnerCount = 0;
        }
        if (storage > minimumStorage.get()) {
            onBaseFound(mc, "base", (int) mc.player.getX(), (int) mc.player.getY(), (int) mc.player.getZ());
        }
    }

    private void onBaseFound(Minecraft mc, String kind, int x, int y, int z) {
        if (!notify.get()) return;
        String msg = "Found " + kind + " at X:" + x + " Y:" + y + " Z:" + z;
        AutismNotifications.warning("Tunnel base: " + kind + " at " + x + " " + z);
        AutismClientMessaging.sendPrefixed("§d[TunnelBaseFinder] §f" + msg);
        logToBaseFile(mc, x, y, z, kind);
        if (mc.player != null) mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        if (pauseOnFind.get()) setEnabled(false);
    }

    private void logToBaseFile(Minecraft mc, int x, int y, int z, String kind) {
        try {
            java.nio.file.Path f = autismclient.AutismClientAddon.FOLDER.toPath().resolve("bases.txt");
            java.nio.file.Files.createDirectories(f.getParent());
            String dim = mc.level != null ? mc.level.dimension().identifier().toString() : "unknown";
            String block = kind.equals("spawner") ? "minecraft:spawner" : "minecraft:chest";
            String line = String.format(Locale.ROOT, "%d %d %d  %s  %s  %s%n",
                x, y, z, dim, block, new java.sql.Timestamp(System.currentTimeMillis()));
            java.nio.file.Files.writeString(f, line, java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    // ---- humanization ----

    private void tickHumanization(Minecraft mc) {
        // Sneak
        if (sneakTicksRemaining > 0) {
            sneakTicksRemaining--;
            if (sneakHoldMode) mc.options.keyShift.setDown(true);
            else {
                if (totalTicks % (2 + rng.nextInt(3)) == 0) sneakToggle = !sneakToggle;
                mc.options.keyShift.setDown(sneakToggle);
            }
            if (sneakTicksRemaining == 0) {
                mc.options.keyShift.setDown(false);
                sneakHoldMode = false;
                if (sneakBurstsLeft > 0) { sneakBurstsLeft--; sneakCooldown = randomRange(8, 20); }
                else sneakCooldown = randomRange(150, 500);
            }
        } else {
            mc.options.keyShift.setDown(false);
            if (sneakCooldown > 0) sneakCooldown--;
            else if (rng.nextFloat() < 0.018f) {
                float roll = rng.nextFloat();
                if (roll < 0.30f) { sneakTicksRemaining = randomRange(30, 50); sneakHoldMode = true; sneakBurstsLeft = 0; }
                else if (roll < 0.60f) { sneakTicksRemaining = randomRange(10, 16); sneakHoldMode = true; sneakBurstsLeft = 1; }
                else { sneakTicksRemaining = randomRange(10, 16); sneakHoldMode = true; sneakBurstsLeft = 0; }
            }
        }
        // Micro-pause
        if (microPauseTicks <= 0 && microPauseCooldown <= 0) {
            if (rng.nextFloat() < 0.005f) { microPauseTicks = randomRange(5, 20); microPauseCooldown = randomRange(200, 500); }
        } else if (microPauseCooldown > 0) microPauseCooldown--;
        // Idle break
        if (idleBreakTicks <= 0) {
            if (idleBreakCooldown > 0) idleBreakCooldown--;
            else if (rng.nextFloat() < 0.003f) { idleBreakTicks = randomRange(20, 60); idleBreakCooldown = randomRange(600, 2400); }
        }
        // Sprint variation
        if (sprintTicks > 0) {
            sprintTicks--;
            mc.options.keySprint.setDown(true);
            if (sprintTicks == 0) { mc.options.keySprint.setDown(false); sprintCooldown = randomRange(100, 400); }
        } else {
            mc.options.keySprint.setDown(false);
            if (sprintCooldown > 0) sprintCooldown--;
            else if (rng.nextFloat() < 0.02f) sprintTicks = randomRange(10, 40);
        }
        // Strafe drift
        if (strafeDriftTicks > 0) {
            strafeDriftTicks--;
            if (strafeDriftTicks % randomRange(3, 6) == 0) strafeDriftLeft = !strafeDriftLeft;
        } else {
            if (strafeDriftCooldown > 0) strafeDriftCooldown--;
            else if (rng.nextFloat() < 0.03f) {
                strafeDriftTicks = randomRange(20, 40);
                strafeDriftLeft = rng.nextBoolean();
                strafeDriftCooldown = randomRange(200, 500);
            }
        }
    }

    private void pickNewDriftTarget() {
        int pattern = rng.nextInt(8);
        float yawTo, pitchTo;
        switch (pattern) {
            case 0 -> { yawTo = randomFloatRange(0.2f, MAX_YAW_DRIFT); pitchTo = gaussian(0f, 0.03f); }
            case 1 -> { yawTo = randomFloatRange(-MAX_YAW_DRIFT, -0.2f); pitchTo = gaussian(0f, 0.03f); }
            case 2 -> { yawTo = gaussian(0f, 0.03f); pitchTo = randomFloatRange(0.1f, MAX_PITCH_DRIFT); }
            case 3 -> { yawTo = gaussian(0f, 0.03f); pitchTo = randomFloatRange(-MAX_PITCH_DRIFT, -0.1f); }
            case 4 -> { yawTo = randomFloatRange(0.2f, MAX_YAW_DRIFT); pitchTo = randomFloatRange(0.1f, MAX_PITCH_DRIFT); }
            case 5 -> { yawTo = randomFloatRange(-MAX_YAW_DRIFT, -0.2f); pitchTo = randomFloatRange(0.1f, MAX_PITCH_DRIFT); }
            case 6 -> { yawTo = randomFloatRange(0.2f, MAX_YAW_DRIFT); pitchTo = randomFloatRange(-MAX_PITCH_DRIFT, -0.1f); }
            default -> { yawTo = randomFloatRange(-MAX_YAW_DRIFT, -0.2f); pitchTo = randomFloatRange(-MAX_PITCH_DRIFT, -0.1f); }
        }
        if (rng.nextFloat() < 0.25f) { yawTo = gaussian(0f, 0.05f); pitchTo = gaussian(0f, 0.04f); }
        driftYawTarget = clamp(yawTo, -MAX_YAW_DRIFT, MAX_YAW_DRIFT);
        driftPitchTarget = clamp(pitchTo, -MAX_PITCH_DRIFT, MAX_PITCH_DRIFT);
        driftTicksDuration = randomRange(30, 80);
        driftTicksRemaining = driftTicksDuration;
    }

    private void tickCrosshairDrift() {
        if (driftTicksRemaining <= 0) pickNewDriftTarget();
        driftTicksRemaining--;
        float progress = 1.0f - ((float) driftTicksRemaining / (float) driftTicksDuration);
        float eased = (1.0f - (float) Math.cos(progress * Math.PI)) * 0.5f;
        float lerpSpeed = 0.03f + eased * 0.05f + rng.nextFloat() * 0.01f;
        driftYawOffset = lerp(driftYawOffset, driftYawTarget, lerpSpeed);
        driftPitchOffset = lerp(driftPitchOffset, driftPitchTarget, lerpSpeed);
        driftYawOffset = clamp(driftYawOffset, -MAX_YAW_DRIFT, MAX_YAW_DRIFT);
        driftPitchOffset = clamp(driftPitchOffset, -MAX_PITCH_DRIFT, MAX_PITCH_DRIFT);
    }

    // ---- hazard helpers ----

    private boolean isHazardous(Minecraft mc, BlockPos pos) {
        Block b = mc.level.getBlockState(pos).getBlock();
        return b == Blocks.LAVA || b == Blocks.WATER || b == Blocks.GRAVEL || b == Blocks.SAND || b == Blocks.RED_SAND;
    }

    private boolean isLiquid(Minecraft mc, BlockPos pos) {
        Block b = mc.level.getBlockState(pos).getBlock();
        return b == Blocks.LAVA || b == Blocks.WATER;
    }

    private boolean hasHazardAbove(Minecraft mc, BlockPos headPos, int maxHeight) {
        for (int y = 1; y <= maxHeight; y++) {
            BlockPos above = headPos.above(y);
            Block b = mc.level.getBlockState(above).getBlock();
            if (b == Blocks.GRAVEL || b == Blocks.SAND || b == Blocks.RED_SAND || b == Blocks.LAVA || b == Blocks.WATER) return true;
            if (!mc.level.getBlockState(above).isAir() && !isHazardous(mc, above)) break;
        }
        return false;
    }

    private boolean isSideSafe(Minecraft mc, Direction sideDir) {
        BlockPos p = mc.player.blockPosition().relative(sideDir);
        Block feet = mc.level.getBlockState(p).getBlock();
        Block head = mc.level.getBlockState(p.above()).getBlock();
        Block ground = mc.level.getBlockState(p.below()).getBlock();
        return feet != Blocks.LAVA && feet != Blocks.WATER && head != Blocks.LAVA && head != Blocks.WATER
            && ground != Blocks.LAVA && ground != Blocks.WATER;
    }

    private boolean isPathClearAndSafe(Minecraft mc, Direction dir, int blocks) {
        BlockPos pos = mc.player.blockPosition();
        for (int i = 1; i <= blocks; i++) {
            BlockPos ahead = pos.relative(dir, i);
            if (!mc.level.getBlockState(ahead).isAir() && isHazardous(mc, ahead)) return false;
            if (!mc.level.getBlockState(ahead.above()).isAir() && isHazardous(mc, ahead.above())) return false;
            BlockPos ground = ahead.below();
            Block groundBlock = mc.level.getBlockState(ground).getBlock();
            if (mc.level.getBlockState(ground).isAir() && mc.level.getBlockState(ground.below()).isAir()) return false;
            if (groundBlock == Blocks.LAVA || groundBlock == Blocks.WATER) return false;
        }
        return true;
    }

    private boolean hasGapAhead(Minecraft mc, Direction dir, int blocks) {
        BlockPos pos = mc.player.blockPosition();
        for (int i = 1; i <= blocks; i++) {
            BlockPos ahead = pos.relative(dir, i);
            BlockPos ground = ahead.below();
            if (mc.level.getBlockState(ahead).isAir() && mc.level.getBlockState(ground).isAir()) return true;
        }
        return false;
    }

    private boolean isOriginalLineSafe(Minecraft mc, int blocksAhead) {
        BlockPos playerPos = mc.player.blockPosition();
        int origX = fixedCoordIsX ? (int) Math.floor(fixedCoord) : playerPos.getX();
        int origZ = fixedCoordIsX ? playerPos.getZ() : (int) Math.floor(fixedCoord);
        BlockPos orig = new BlockPos(origX, playerPos.getY(), origZ);
        for (int i = 0; i <= blocksAhead; i++) {
            BlockPos check = orig.relative(tunnelDirection, i);
            if (isHazardous(mc, check) || isHazardous(mc, check.above())) return false;
            BlockPos ground = check.below();
            Block g = mc.level.getBlockState(ground).getBlock();
            if (g == Blocks.LAVA || g == Blocks.WATER || g == Blocks.GRAVEL || g == Blocks.SAND || g == Blocks.RED_SAND) return false;
            if (mc.level.getBlockState(ground).isAir() && mc.level.getBlockState(ground.below()).isAir()) return false;
            if (hasHazardAbove(mc, check.above(), 5)) return false;
            BlockPos behind = check.relative(tunnelDirection);
            if (!mc.level.getBlockState(check).isAir() && isHazardous(mc, behind)) return false;
        }
        return true;
    }

    private boolean isGroundSafe(Minecraft mc, Direction dir, int blocks) {
        BlockPos pos = mc.player.blockPosition();
        for (int y = 2; y <= 5; y++) {
            BlockPos above = pos.above(y);
            Block b = mc.level.getBlockState(above).getBlock();
            if (b == Blocks.GRAVEL || b == Blocks.SAND || b == Blocks.RED_SAND) return false;
            if (!mc.level.getBlockState(above).isAir() && !isHazardous(mc, above)) break;
        }
        for (int i = 1; i <= blocks; i++) {
            BlockPos ahead = pos.relative(dir, i);
            BlockPos ground = ahead.below();
            Block g = mc.level.getBlockState(ground).getBlock();
            if (g == Blocks.LAVA || g == Blocks.WATER || g == Blocks.GRAVEL || g == Blocks.SAND || g == Blocks.RED_SAND) return false;
            Block feet = mc.level.getBlockState(ahead).getBlock();
            Block head = mc.level.getBlockState(ahead.above()).getBlock();
            if (feet == Blocks.LAVA || feet == Blocks.WATER || head == Blocks.LAVA || head == Blocks.WATER) return false;
            if (feet == Blocks.GRAVEL || feet == Blocks.SAND || feet == Blocks.RED_SAND) return false;
            if (head == Blocks.GRAVEL || head == Blocks.SAND || head == Blocks.RED_SAND) return false;
            for (int y = 2; y <= 5; y++) {
                BlockPos above = ahead.above(y);
                Block b = mc.level.getBlockState(above).getBlock();
                if (b == Blocks.GRAVEL || b == Blocks.SAND || b == Blocks.RED_SAND) return false;
                if (!mc.level.getBlockState(above).isAir() && !isHazardous(mc, above)) break;
            }
            if (mc.level.getBlockState(ground).isAir()) {
                int drop = 0;
                for (int y = 0; y <= 10 && mc.level.getBlockState(ground.below(y)).isAir(); y++) drop++;
                if (drop >= 2) return false;
                for (int y = 0; y <= 10; y++) {
                    BlockPos cp = ground.below(y);
                    if (!mc.level.getBlockState(cp).isAir()) {
                        Block bb = mc.level.getBlockState(cp).getBlock();
                        if (bb == Blocks.LAVA || bb == Blocks.WATER) return false;
                        break;
                    }
                }
            }
            for (int y = 1; y <= 3; y++) {
                Block below = mc.level.getBlockState(ground.below(y)).getBlock();
                if (below == Blocks.LAVA) return false;
                if (below != Blocks.WATER && !mc.level.getBlockState(ground.below(y)).isAir()) break;
            }
        }
        return true;
    }

    // ---- math helpers ----

    private float getDirectionYaw(Direction dir) {
        return switch (dir) {
            case NORTH -> 180f;
            case SOUTH -> 0f;
            case EAST -> -90f;
            case WEST -> 90f;
            default -> 0f;
        };
    }

    private Direction getSideDirection(Direction forward, int side) {
        return side > 0 ? forward.getClockWise() : forward.getCounterClockWise();
    }

    private float gaussian(float mean, float stddev) { return mean + (float) (rng.nextGaussian() * stddev); }
    private float lerp(float from, float to, float t) { return from + (to - from) * t; }
    private float lerpAngle(float from, float to, float t) {
        float diff = to - from;
        while (diff > 180f) diff -= 360f;
        while (diff < -180f) diff += 360f;
        return from + diff * t;
    }
    private float angleDiff(float a, float b) {
        float diff = b - a;
        while (diff > 180f) diff -= 360f;
        while (diff < -180f) diff += 360f;
        return diff;
    }
    private float clampPitch(float p) { return Math.max(-90f, Math.min(90f, p)); }
    private float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }
    private int randomRange(int min, int max) { return max <= min ? min : min + rng.nextInt(max - min + 1); }
    private float randomFloatRange(float min, float max) { return min + rng.nextFloat() * (max - min); }

    @Override
    public String info() {
        return avoidState == AvoidState.NONE ? "tunneling" : avoidState.name().toLowerCase(Locale.ROOT);
    }
}
