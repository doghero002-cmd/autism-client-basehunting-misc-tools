package com.autism.seedcracker.modules;

import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkScanHelper;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.EnumSetting;
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
import com.autism.seedcracker.util.tunnel.LookRotation;

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
        .description("Crosshair drift, micro-pauses, sneak/sprint/strafe variation, smoothed rotations. DISABLE if being flagged.")
        .group("Tunnel"));
    private final BoolSetting quietMovement = add(new BoolSetting(
            "quiet-movement", "Quiet movement (anti-flag)", true)
        .description("Go perfectly straight with no drift/sneak/sprint/strafe spam - only turn/stop for obstacles or a found base. Least flaggable.")
        .group("Tunnel"));
    private final BoolSetting defendPlayers = add(new BoolSetting(
            "defend-players", "Defend vs players", true)
        .description("Turn and hit a player that comes within 16 blocks.")
        .group("Tunnel"));

    public enum TunnelMode { WATER, XENON, WALK_2X1 }
    private final EnumSetting<TunnelMode> tunnelMode = add(new EnumSetting<>(
            "mode", "Tunnel mode", TunnelMode.WATER, TunnelMode.values())
        .description("WATER (default) = hand off to the Tunnel Base (Water) engine - the reliable one. XENON = straight-line smart avoidance. WALK_2X1 = mine a plain 2x1 walkable tunnel like a normal pickaxe, with automatic human-like camera.")
        .group("Tunnel"));

    /**
     * Movement / facing engine.
     *  AUTISM       - the base client's own KillAura-smooth human rotation (AutismHumanRotation).
     *  MOUSE        - wocky mouse-delta rotation: turns through the REAL mouse handler (most legit).
     *  VANILLA      - the module's built-in smooth rotation (current behaviour).
     *  LEGIT        - wocky/Water legit rotation: time-eased, jittered, human-mouse look.
     *  SILENT       - facing applied to movement packets only (camera never turns).
     *  HAZARD_ONLY  - hold the camera dead-straight ahead; only turn when a hazard/obstacle
     *                 (lava/water/avoidance) forces a lane change or 90 turn. Least movement.
     */
    public enum MovementStyle { AUTISM, MOUSE, VANILLA, LEGIT, SILENT, HAZARD_ONLY }
    private final EnumSetting<MovementStyle> movementStyle = add(new EnumSetting<>(
            "movement-style", "Movement style", MovementStyle.AUTISM, MovementStyle.values())
        .description("How the bot turns its view. AUTISM = the base client's KillAura-smooth human rotation. MOUSE = through the real mouse handler (wocky). LEGIT = human-mouse eased rotation. SILENT = packets only, camera stays. HAZARD_ONLY = dead-straight unless a hazard forces a turn. VANILLA = the built-in smoother.")
        .group("Tunnel"));
    private final BoolSetting silentStrafe = add(new BoolSetting(
            "silent-strafe", "Silent strafe", true)
        .description("When silent rotation is on, walk using movement keys resolved against the SILENT yaw so you walk down the tunnel while the server sees you looking at the dig face (wocky strafe-relative-to-silent-yaw).")
        .group("Tunnel"));
    private final BoolSetting smartDirection = add(new BoolSetting(
            "smart-direction", "Smart direction", true)
        .description("Tunnel toward the nearest flagged base (from the Base Tracker) when one is known; otherwise pick the clearest direction.")
        .group("Tunnel"));
    private final BoolSetting autoMend = add(new BoolSetting(
            "auto-mend", "Auto mend pickaxe", true)
        .description("When your pickaxe is low on durability, pause and throw XP bottles to mend it before continuing.")
        .group("Tunnel"));
    private final BoolSetting silentRotation = add(new BoolSetting(
            "silent-rotation", "Silent rotation", true)
        .description("Apply facing via movement packets instead of snapping the client camera (server sees the rotation, screen stays put - less flaggy).")
        .group("Tunnel"));
    private final IntSetting mendThreshold = add(new IntSetting(
            "mend-threshold", "Mend at durability", 60, 5, 500, 5)
        .description("Mend the pickaxe when its remaining durability falls to this value.")
        .group("Tunnel")
        .visibleWhen(() -> autoMend.get()));
    private final BoolSetting autoTool = add(new BoolSetting(
            "auto-tool", "Auto tool swap", true)
        .description("Swap to the best hotbar tool for the block being dug (faster digging).")
        .group("Tunnel"));
    private final BoolSetting pauseToEat = add(new BoolSetting(
            "pause-to-eat", "Pause to eat", true)
        .description("Pause digging to eat when hungry (resumes after).")
        .group("Tunnel"));
    private final IntSetting eatHunger = add(new IntSetting(
            "eat-hunger", "Eat at hunger", 14, 1, 20, 1)
        .description("Pause to eat when food level falls to this (out of 20).")
        .group("Tunnel")
        .visibleWhen(() -> pauseToEat.get()));
    private final BoolSetting sellOnFull = add(new BoolSetting(
            "sell-on-full", "Sell items on full inventory", false)
        .description("When your inventory is full, /ah sell the listed items instead of dropping them on the ground.")
        .group("Tunnel"));
    private final IntSetting digTimeout = add(new IntSetting(
            "dig-timeout", "Dig timeout (ticks)", 120, 20, 1200, 20)
        .description("If a block hasn't broken after this many ticks of digging, treat it as unbreakable (too-hard / wrong tool) and avoid it instead of stalling forever.")
        .group("Tunnel"));
    private final BoolSetting escapeHoles = add(new BoolSetting(
            "escape-holes", "Escape bedrock holes", true)
        .description("If you fall into a hole below the target Y, pillar/jump back up to the target Y before continuing.")
        .group("Tunnel"));
    private final IntSetting targetY = add(new IntSetting(
            "target-y", "Target Y level", -59, -64, 320, 1)
        .description("The Y level to climb back to when escaping a bedrock hole (and the tunnel's preferred depth).")
        .group("Tunnel")
        .visibleWhen(() -> escapeHoles.get()));
    private final autismclient.api.module.StringSetting sellItems = add(new autismclient.api.module.StringSetting(
            "sell-items", "Items to sell", "cobblestone,dirt,gravel,netherrack,deepslate")
        .description("Comma-separated item ids to /ah sell when the inventory is full (e.g. cobblestone,dirt).")
        .group("Tunnel")
        .visibleWhen(() -> sellOnFull.get()));
    private final autismclient.api.module.StringSetting sellPrice = add(new autismclient.api.module.StringSetting(
            "sell-price", "Sell price", "100")
        .description("The /ah sell price to list full-inventory items at.")
        .group("Tunnel")
        .visibleWhen(() -> sellOnFull.get()));
    private final BoolSetting sellViaOrders = add(new BoolSetting(
            "sell-via-orders", "Deliver to orders (not /ah sell)", false)
        .description("When ON, full-inventory items are delivered to matching /orders instead of listed on the AH (avoids listing the wrong item).")
        .group("Tunnel")
        .visibleWhen(() -> sellOnFull.get()));

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

    private static final float MAX_YAW_DRIFT = 0.22f;
    private static final float MAX_PITCH_DRIFT = 0.10f;

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
        if (tunnelMode.get() == TunnelMode.WATER) {
            // Default mode: hand off to the Water engine and step aside.
            autismclient.modules.Module water =
                autismclient.modules.ModuleRegistry.get(SeedcrackerAddon.ID + ":tunnel-base-water");
            if (water != null) {
                if (!water.isEnabled()) water.setEnabled(true);
                AutismClientMessaging.sendPrefixed("§aTunnel Base Finder: Water engine running (Tunnel Base (Water)).");
                setEnabledSilently(false);
                return;
            }
            AutismClientMessaging.sendPrefixed("§eWater engine module missing - falling back to XENON.");
        }
        Minecraft mc = Minecraft.getInstance();
        notified.clear();
        spawnerCount = 0;
        totalTicks = 0;
        autismAim.reset();
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
        digGapTicks = 0;
        lastDigPos = null;
        digTicks = 0;
        caveAborts = 0;
        lastCaveAbortTick = 0;
        escapeBestY = Integer.MIN_VALUE;
        escapeStallTicks = 0;
        goAroundLane = null;
        goAroundTicks = 0;
        goAroundFails = 0;
        stuck.reset();
        legitMovement.reset();
        com.autism.seedcracker.util.tunnel.HumanPacingEngine.get().setEnabled(true);
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
            releaseMovementKeys(mc);
            mc.options.keyAttack.setDown(false);
        }
        tunnelDirection = null;
        notified.clear();
        com.autism.seedcracker.util.tunnel.HumanPacingEngine.get().setEnabled(false);
        com.autism.seedcracker.util.tunnel.SilentRotation.clear();
        com.autism.seedcracker.util.tunnel.MouseRotation.get().stop();
        autismAim.clear();
    }

    /** Rewrite outgoing movement-packet rotation to the silent values while silent rotation is on. */
    @Override
    public boolean onPacketSend(net.minecraft.network.protocol.Packet<?> packet) {
        com.autism.seedcracker.util.tunnel.SilentRotation.processPacket(packet);
        return false;
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    /** Release every movement/action key so the player stands still (used when a GUI opens). */
    private static void releaseMovementKeys(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyShift.setDown(false);
        mc.options.keyUse.setDown(false);
    }

    /** wocky/Water legit rotation engine (time-eased, jittered, human-mouse look). */
    private final com.autism.seedcracker.util.tunnel.LegitMovement legitMovement =
        new com.autism.seedcracker.util.tunnel.LegitMovement();

    /** AUTISM client's own KillAura-smooth human rotation (AutismHumanRotation). */
    private final com.autism.seedcracker.util.tunnel.AutismAim autismAim =
        new com.autism.seedcracker.util.tunnel.AutismAim();

    /**
     * Apply a facing for the LEGIT / SILENT / HAZARD_ONLY styles. LEGIT + HAZARD_ONLY ease the
     * real camera (human-mouse); SILENT routes the facing to movement packets only and leaves the
     * camera alone. VANILLA applies its own rotation and never reaches this helper.
     */
    private void applyFacing(Minecraft mc, float yaw, float pitch, MovementStyle style) {
        if (style == MovementStyle.SILENT || (silentRotation.get() && style == MovementStyle.HAZARD_ONLY)) {
            com.autism.seedcracker.util.tunnel.SilentRotation.apply(yaw, clampPitch(pitch));
        } else {
            com.autism.seedcracker.util.tunnel.SilentRotation.clear();
            mc.player.setYRot(yaw);
            mc.player.setXRot(clampPitch(pitch));
        }
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
        // Smart direction: head toward the nearest flagged base if the Base Tracker knows one.
        if (smartDirection.get()) {
            Direction toward = directionTowardNearestBase(mc);
            if (toward != null) return toward;
        }
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

    /** Cardinal direction that best points toward the nearest Base Tracker base, or null if none. */
    private Direction directionTowardNearestBase(Minecraft mc) {
        java.util.List<com.autism.seedcracker.finder.BaseTracker.Entry> bases =
            com.autism.seedcracker.finder.BaseTracker.nearest(mc.player.getX(), mc.player.getZ(), 1);
        if (bases.isEmpty()) return null;
        com.autism.seedcracker.finder.BaseTracker.Entry b = bases.get(0);
        double dx = b.blockX() - mc.player.getX();
        double dz = b.blockZ() - mc.player.getZ();
        if (Math.abs(dx) < 8 && Math.abs(dz) < 8) return null; // already on top of it
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // Mode switched to WATER mid-run: hand off to the Water engine and step aside.
        if (tunnelMode.get() == TunnelMode.WATER) {
            autismclient.modules.Module water =
                autismclient.modules.ModuleRegistry.get(SeedcrackerAddon.ID + ":tunnel-base-water");
            if (water != null) {
                if (!water.isEnabled()) water.setEnabled(true);
                setEnabledSilently(false);
                return;
            }
        }

        // If a GUI is open (inventory, chest, /ah confirm, etc.) the bot must not keep walking/
        // digging behind it. Auto-close anything that isn't the sell-on-full confirm screen and
        // pause for the tick; this is why it previously only seemed to work while in a menu.
        if (mc.gui.screen() != null) {
            if (!(mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)) {
                mc.gui.setScreen(null);
            }
            releaseMovementKeys(mc);
            if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
            mc.options.keyAttack.setDown(false);
            return;
        }
        // No movement/dig packets within the container grace window (right after a container click).
        if (com.autism.seedcracker.util.ContainerMutex.containerBusy(mc)) {
            releaseMovementKeys(mc);
            if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
            mc.options.keyAttack.setDown(false);
            return;
        }

        if (tunnelDirection == null) {
            initTunnelDirection(mc);
            if (tunnelDirection == null) return;
        }

        totalTicks++;
        if (humanize.get() && !quietMovement.get()) tickHumanization(mc);

        // Auto-mend: if the pickaxe is low, pause digging and throw XP bottles to repair it.
        // (Release movement keys on these pause paths - a latched strafe key from a lane shift
        // would keep us walking sideways through the whole mend/eat/wait.)
        if (autoMend.get() && tickMend(mc)) { releaseMovementKeys(mc); return; }

        // Pause to eat: stop digging and eat when hungry.
        if (pauseToEat.get() && tickEat(mc)) { releaseMovementKeys(mc); return; }

        // Sell on full: /ah sell the listed items when the inventory is full (instead of dropping).
        if (sellOnFull.get()) tickSellOnFull(mc);

        if (idleBreakTicks > 0) { idleBreakTicks--; mc.options.keyUp.setDown(false); }
        if (microPauseTicks > 0) { microPauseTicks--; mc.options.keyUp.setDown(false); }
        if (waitTicks > 0) { waitTicks--; releaseMovementKeys(mc); return; }

        // Grim SpeedA predictor says we're near a speed flag: pause movement a few ticks so the
        // buffer drains instead of tripping the real check (server-side setback).
        if (FlagDetectorModule.speedFlagImminent()) {
            releaseMovementKeys(mc);
            waitTicks = randomRange(4, 10);
            return;
        }

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

        MovementStyle style = movementStyle.get();
        boolean hazardTurn = isAvoiding || miningLookDown;

        // While WALK_2X1 is actively digging, aimAtBlock owns the camera. Running the forward
        // rotation here too made two attractors fight every tick (forward vs dig block): the
        // crosshair oscillated across neighbouring blocks, churning START/ABORT dig packets and
        // desyncing server break progress -> rubber-band.
        boolean digAiming = tunnelMode.get() == TunnelMode.WALK_2X1 && walkMiningTarget != null;

        if (digAiming) {
            // aimAtBlock drives rotation this tick.
        } else if (style == MovementStyle.HAZARD_ONLY) {
            // Hold the camera dead-straight ahead at level pitch. Only ease toward a new facing
            // when a hazard/obstacle actually forces a lane change or a 90-degree turn.
            if (!yawInitialized) {
                currentSmoothedYaw = getDirectionYaw(tunnelDirection);
                currentSmoothedPitch = basePitchBias;
                yawInitialized = true;
            }
            if (hazardTurn) {
                currentSmoothedYaw = LookRotation.approachAngle(currentSmoothedYaw, targetYaw, 20.0f);
            } else {
                currentSmoothedYaw = getDirectionYaw(tunnelDirection);
            }
            currentSmoothedPitch = LookRotation.approach(currentSmoothedPitch, basePitchBias, 12.0f);
            applyFacing(mc, currentSmoothedYaw, currentSmoothedPitch, style);
        } else if (style == MovementStyle.AUTISM) {
            // The base client's own KillAura-smooth human rotation (AutismHumanRotation). Pitch
            // biases down while mining. This is the smoothest client-side rotation available.
            float pitchTgt = miningLookDown ? 35.0f : basePitchBias;
            com.autism.seedcracker.util.tunnel.SilentRotation.clear();
            float[] rot = autismAim.face(mc, targetYaw, pitchTgt);
            currentSmoothedYaw = rot[0];
            currentSmoothedPitch = rot[1];
        } else if (style == MovementStyle.MOUSE) {
            // wocky mouse-delta rotation: set the target and let the MouseHandler mixin move the
            // camera through the real mouse path (most legit). Pitch biases down while mining.
            float pitchTgt = miningLookDown ? 35.0f : basePitchBias;
            com.autism.seedcracker.util.tunnel.MouseRotation.get().rotateTo(targetYaw, pitchTgt);
            com.autism.seedcracker.util.tunnel.SilentRotation.clear();
        } else if (style == MovementStyle.LEGIT || style == MovementStyle.SILENT) {
            // wocky/Water legit engine: time-eased, jittered human-mouse rotation. SILENT applies
            // it to packets only; LEGIT eases the real camera. We suppress the humanize drift here
            // because the engine adds its own small jitter.
            float pitchTgt = miningLookDown ? 35.0f : basePitchBias;
            float[] rot = legitMovement.update(targetYaw, pitchTgt);
            currentSmoothedYaw = rot[0];
            currentSmoothedPitch = rot[1];
            applyFacing(mc, rot[0], rot[1], style);
        } else {
            // VANILLA: the module's built-in smoothed rotation (quiet vs humanized).
            if (!quietMovement.get()) tickCrosshairDrift();
            float driftedYaw = targetYaw + (quietMovement.get() ? 0f : driftYawOffset);
            float pitchTarget = miningLookDown ? 35.0f : basePitchBias;
            float driftedPitch = pitchTarget + (quietMovement.get() ? 0f : driftPitchOffset);
            if (quietMovement.get()) {
                if (!yawInitialized) {
                    currentSmoothedYaw = mc.player.getYRot();
                    currentSmoothedPitch = basePitchBias;
                    yawInitialized = true;
                }
                float rotSpeed = isAvoiding ? 0.10f : 0.08f;
                currentSmoothedYaw = lerpAngle(currentSmoothedYaw, targetYaw, rotSpeed);
                currentSmoothedPitch = lerp(currentSmoothedPitch, basePitchBias, rotSpeed);
                LookRotation.apply(currentSmoothedYaw, clampPitch(currentSmoothedPitch));
            } else {
                if (!yawInitialized) {
                    currentSmoothedYaw = mc.player.getYRot();
                    currentSmoothedPitch = mc.player.getXRot();
                    yawInitialized = true;
                }
                float yawChange = Math.abs(angleDiff(lastRawTargetYaw, targetYaw));
                if (yawChange > 5f) {
                    turnSpeedMultiplier = yawChange > 80f
                        ? 0.06f + rng.nextFloat() * 0.08f
                        : 0.04f + rng.nextFloat() * 0.06f;
                    turnYawOvershoot = gaussian(0f, 0.3f);
                    turnOvershootDecay = 0.025f + rng.nextFloat() * 0.035f;
                    turnReactionTicks = randomRange(1, 3);
                    lastRawTargetYaw = targetYaw;
                }
                if (turnReactionTicks > 0) {
                    turnReactionTicks--;
                    mc.player.setYRot(currentSmoothedYaw);
                    mc.player.setXRot(clampPitch(currentSmoothedPitch));
                } else {
                    turnYawOvershoot = lerp(turnYawOvershoot, 0.0f, turnOvershootDecay);
                    float rotSpeed = turnSpeedMultiplier;
                    if (isAvoiding) rotSpeed = 0.08f + rng.nextFloat() * 0.10f;
                    float finalYaw = driftedYaw + turnYawOvershoot;
                    currentSmoothedYaw = lerpAngle(currentSmoothedYaw, finalYaw, rotSpeed);
                    currentSmoothedPitch = lerp(currentSmoothedPitch, driftedPitch, rotSpeed);
                    mc.player.setYRot(currentSmoothedYaw);
                    mc.player.setXRot(clampPitch(currentSmoothedPitch));
                }
            }
        }

        // Don't strafe-correct while actively digging: we're standing still, and the strafe taps
        // wobble the crosshair off the dig block (restarting the dig = packet churn).
        if (!digAiming) correctPosition(mc);
        else { mc.options.keyLeft.setDown(false); mc.options.keyRight.setDown(false); }

        // Base detection + tunneling.
        notifyFound(mc);
        if (tunnelMode.get() == TunnelMode.WALK_2X1) {
            tickWalkTunnel(mc);
        } else {
            tickTunneling(mc);
        }

        // Stuck detector: log the exact sub-state if we stop making progress.
        stuck.setAction("mode=" + tunnelMode.get() + " dir=" + tunnelDirection
            + " avoid=" + avoidState
            + (walkMiningTarget != null ? " digging=" + walkMiningTarget : "")
            + (goAroundLane != null ? " goAround=" + goAroundLane + "/" + goAroundTicks : "")
            + (waitTicks > 0 ? " wait=" + waitTicks : ""));
        stuck.tick(mc);
    }

    // ---- WALK_2X1 mode: mine a plain 2-high x 1-wide walkable tunnel like a normal pickaxe ----

    /**
     * Mines a 2x1 tunnel the player can walk through, using normal pickaxe mechanics and an
     * automatic human-like camera. Each tick it looks at the next block to break (feet, then head)
     * with a smooth, non-snapping rotation, holds attack until it breaks, and walks forward when
     * the 2x1 space ahead is clear. Stops at lava/water (turns 90° instead of digging through).
     */
    private BlockPos walkMiningTarget = null;
    private int digGapTicks = 0;
    private int digTicks = 0;
    private int digTicksTotal = 0;

    private int escapeBestY = Integer.MIN_VALUE;
    private int escapeStallTicks = 0;

    /**
     * Escape a bedrock hole with a LEGIT tower: look down (eased), hold jump on the ground and
     * hold use while rising - vanilla clicks the exposed top face beneath you, exactly the input
     * pattern of a real player pillaring up. The old version useItemOn'd an AIR block (invalid
     * click, server ignores it), so the pillar never actually placed and the escape spun forever.
     */
    private void tickEscapeHole(Minecraft mc) {
        BlockPos pos = mc.player.blockPosition();
        if (pos.getY() >= targetY.get()) { // done - release the tower inputs
            mc.options.keyJump.setDown(false);
            mc.options.keyUse.setDown(false);
            escapeBestY = Integer.MIN_VALUE;
            escapeStallTicks = 0;
            return;
        }
        // Stall watchdog: no upward progress for 10s (no blocks, unreachable lip) -> stop
        // towering and route around instead of cycling jump/place forever.
        if (pos.getY() > escapeBestY) { escapeBestY = pos.getY(); escapeStallTicks = 0; }
        else if (++escapeStallTicks > 200) {
            escapeBestY = Integer.MIN_VALUE;
            escapeStallTicks = 0;
            mc.options.keyJump.setDown(false);
            mc.options.keyUse.setDown(false);
            goAround(mc, "escape stalled");
            return;
        }
        mc.options.keyUp.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyAttack.setDown(false);
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        // Eased look-down (yaw AND pitch through the legit engine - no raw pitch snap).
        float[] rot = legitMovement.update(mc.player.getYRot(), 80f);
        mc.player.setYRot(rot[0]);
        mc.player.setXRot(clampPitch(rot[1]));
        BlockPos below = pos.below();
        boolean belowAir = mc.level.getBlockState(below).isAir() || mc.level.getBlockState(below).canBeReplaced();
        if (belowAir) {
            int slot = findScaffoldSlotForPillar(mc);
            if (slot >= 0) {
                com.autism.seedcracker.util.InvSync.select(mc, slot);
                if (mc.player.onGround()) {
                    mc.options.keyJump.setDown(true);   // launch
                    mc.options.keyUse.setDown(false);
                } else {
                    mc.options.keyJump.setDown(false);
                    mc.options.keyUse.setDown(true);    // vanilla places on the face below at the apex
                }
            } else {
                // No scaffold block: just jump and hope to grab a ledge.
                mc.options.keyUse.setDown(false);
                mc.options.keyJump.setDown(mc.player.onGround());
            }
        } else {
            // Solid below: keep jumping to climb out (or dig the block above if enclosed).
            mc.options.keyUse.setDown(false);
            mc.options.keyJump.setDown(mc.player.onGround());
            BlockPos head = pos.above(2);
            if (!mc.level.getBlockState(head).isAir() && mc.level.getBlockState(head).getBlock() != Blocks.BEDROCK) {
                mineDirect(mc, head);
            }
        }
    }

    /** A hotbar slot holding a cheap block to pillar with (dirt/cobble/netherrack, etc). */
    private int findScaffoldSlotForPillar(Minecraft mc) {
        for (int i = 0; i < 9; i++) {
            net.minecraft.world.item.ItemStack s = mc.player.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
            if (id.endsWith("dirt") || id.endsWith("cobblestone") || id.endsWith("netherrack")
                || id.endsWith("cobbled_deepslate") || id.endsWith("stone") || id.endsWith("_planks")) return i;
        }
        for (int i = 0; i < 9; i++) {
            net.minecraft.world.item.ItemStack s = mc.player.getInventory().getItem(i);
            if (!s.isEmpty() && s.getItem() instanceof net.minecraft.world.item.BlockItem) return i;
        }
        return -1;
    }

    /** Adapter: the live world as the planner's boolean view (planner itself is unit-tested). */
    private com.autism.seedcracker.util.pure.TunnelPlanner.World plannerWorld(Minecraft mc) {
        return new com.autism.seedcracker.util.pure.TunnelPlanner.World() {
            @Override public boolean air(BlockPos pos) { return mc.level.getBlockState(pos).isAir(); }
            @Override public boolean liquid(BlockPos pos) { return isLiquid(mc, pos); }
            @Override public boolean bedrock(BlockPos pos) { return mc.level.getBlockState(pos).getBlock() == Blocks.BEDROCK; }
            @Override public boolean falling(BlockPos pos) {
                return com.autism.seedcracker.util.tunnel.Hazards.isFalling(mc.level.getBlockState(pos));
            }
            @Override public boolean hazard(BlockPos pos) { return isHazardous(mc, pos); }
        };
    }

    private void tickWalkTunnel(Minecraft mc) {
        BlockPos playerPos = mc.player.blockPosition();

        // A committed lane shift keeps running until it expires, even though the trigger hazard
        // is no longer straight ahead (we're mid-strafe; re-deciding every tick causes jitter).
        if (goAroundLane != null && goAroundTicks > 0) {
            goAround(mc, "committed");
            return;
        }

        // Decision core is the pure, unit-tested planner; this method just executes its plan.
        var world = plannerWorld(mc);
        var plan = com.autism.seedcracker.util.pure.TunnelPlanner.plan(
            world, playerPos, tunnelDirection, escapeHoles.get(), targetY.get());

        switch (plan.action()) {
            case GO_AROUND -> { goAround(mc, plan.reason()); return; }
            case ESCAPE_HOLE -> { tickEscapeHole(mc); return; }
            default -> {}
        }
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);

        // Hazards handled and no commit active: clear go-around state.
        goAroundLane = null;
        goAroundTicks = 0;
        goAroundFails = 0;

        BlockPos target = plan.digTarget();
        if (target != null) {
            walkMiningTarget = target;
            // Grim legitimacy: never sprint or be airborne while digging (top Grim flags).
            mc.options.keySprint.setDown(false);
            mc.options.keyUp.setDown(false); // stand still while mining
            if (!mc.player.onGround()) {
                mc.options.keyAttack.setDown(false);
                if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
                return;
            }
            // Human pacing: wait the engine's break-gap between blocks instead of digging every tick.
            if (digGapTicks > 0) {
                digGapTicks--;
                mc.options.keyAttack.setDown(false);
                if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
                return;
            }
            // Keep breaking the same block until it's gone (digCrosshair drives the break via
            // continueDestroyBlock on the converged crosshair; we don't hold the raw attack key).
            if (lastDigPos != null && lastDigPos.equals(target) && !mc.level.getBlockState(target).isAir()) {
                // Unbreakable-block detection: if we've been on the same block past the dig timeout,
                // it's too hard / wrong tool for it - stop and avoid it instead of stalling forever.
                if (++digTicks > digTimeout.get()) {
                    digTicks = 0;
                    digTicksTotal = 0;
                    lastDigPos = null;
                    digActive = false;
                    walkMiningTarget = null;
                    mc.options.keyAttack.setDown(false);
                    if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
                    com.autism.seedcracker.modules.FlagDetectorModule.report("UNBREAKABLE", "TunnelBaseFinder",
                        "block not breaking after " + digTimeout.get() + "t at " + target + " state=" + mc.level.getBlockState(target).getBlock());
                    startObstacleAvoidance(mc);
                    return;
                }
                aimAtBlock(mc, target);
                digCrosshair(mc);
                return;
            }
            // New target: reset the unbreakable timer, auto-tool, then dig.
            digTicks = 0;
            if (autoTool.get()) selectBestTool(mc, mc.level.getBlockState(target));
            aimAtBlock(mc, target);
            digCrosshair(mc);
            lastDigPos = target;
            digActive = true;
            digGapTicks = com.autism.seedcracker.util.tunnel.HumanPacingEngine.get().breakGapTicks();
        } else {
            // 2x1 space ahead is clear: stop mining and walk forward.
            walkMiningTarget = null;
            lastDigPos = null;
            digActive = false;
            mc.options.keyAttack.setDown(false);
            if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
            // Face forward (level) and walk.
            faceForwardHuman(mc);
            walkForward(mc, mc.player.blockPosition().relative(tunnelDirection));
        }
    }

    // ---- go-around (shared hazard/bedrock escape) ----
    private Direction goAroundLane = null;
    private int goAroundTicks = 0;
    private int goAroundFails = 0;

    /**
     * Escape an obstacle (lava, hazard pocket, bedrock wall) by committing to a sideways lane
     * shift for several ticks. Scores both lanes 3 blocks deep (liquids + bedrock walls) instead
     * of only 1, digs the lane open when it's blocked-but-safe, and only turns 90 degrees after
     * repeated failures - so digging along bedrock jogs around the bumps instead of stalling.
     */
    private void goAround(Minecraft mc, String why) {
        walkMiningTarget = null;
        lastDigPos = null;
        digTicks = 0;
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        mc.options.keyAttack.setDown(false);
        mc.options.keyUp.setDown(false);

        // Committed shift in progress: keep strafing until the lane change lands.
        if (goAroundLane != null && goAroundTicks > 0) {
            goAroundTicks--;
            strafeOrDigLane(mc, goAroundLane);
            return;
        }

        Direction lane = com.autism.seedcracker.util.pure.TunnelPlanner.chooseLane(
            plannerWorld(mc), mc.player.blockPosition(), tunnelDirection);
        if (lane != null) {
            if (fixedCoordIsX) fixedCoord += lane.getStepX();
            else fixedCoord += lane.getStepZ();
            goAroundLane = lane;
            goAroundTicks = com.autism.seedcracker.util.Tuning.GO_AROUND_COMMIT_TICKS;
            goAroundFails = 0;
            strafeOrDigLane(mc, lane);
        } else if (++goAroundFails >= com.autism.seedcracker.util.Tuning.GO_AROUND_MAX_FAILS) {
            // Boxed in on both sides repeatedly: 90-degree turn as the last resort. The strafe
            // line MUST re-anchor to the new axis or correctPosition keeps steering to the old
            // coordinate and grinds the player into the wall it just turned away from.
            tunnelDirection = tunnelDirection.getClockWise();
            retargetLine(mc);
            goAroundFails = 0;
            goAroundLane = null;
            FlagDetectorModule.report("GO_AROUND", "TunnelBaseFinder", "boxed in (" + why + "), turned " + tunnelDirection);
        }
    }

    /** Strafe into the lane; if a safe solid block is in the way, dig it open first. */
    private void strafeOrDigLane(Minecraft mc, Direction lane) {
        BlockPos p = mc.player.blockPosition().relative(lane);
        BlockState feet = mc.level.getBlockState(p);
        BlockState head = mc.level.getBlockState(p.above());
        boolean feetBlocked = !feet.isAir() && feet.getBlock() != Blocks.BEDROCK && !isHazardous(mc, p);
        boolean headBlocked = !head.isAir() && head.getBlock() != Blocks.BEDROCK && !isHazardous(mc, p.above());
        if (headBlocked || feetBlocked) {
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
            mineDirect(mc, headBlocked ? p.above() : p);
            return;
        }
        miningLookDown = false;
        boolean isRight = lane == tunnelDirection.getClockWise();
        mc.options.keyRight.setDown(isRight);
        mc.options.keyLeft.setDown(!isRight);
    }

    /** Smoothly turn back to facing the tunnel direction at level pitch (no snap). */
    private void faceForwardHuman(Minecraft mc) {
        miningLookDown = false;
        float targetYaw = getDirectionYaw(tunnelDirection);
        MovementStyle style = movementStyle.get();
        if (style == MovementStyle.AUTISM) {
            com.autism.seedcracker.util.tunnel.SilentRotation.clear();
            float[] rot = autismAim.face(mc, targetYaw, basePitchBias);
            currentSmoothedYaw = rot[0];
            currentSmoothedPitch = rot[1];
            return;
        }
        if (style == MovementStyle.MOUSE) {
            com.autism.seedcracker.util.tunnel.MouseRotation.get().rotateTo(targetYaw, basePitchBias);
            com.autism.seedcracker.util.tunnel.SilentRotation.clear();
            return;
        }
        if (style == MovementStyle.LEGIT || style == MovementStyle.SILENT || style == MovementStyle.HAZARD_ONLY) {
            float[] rot = legitMovement.update(targetYaw, basePitchBias);
            currentSmoothedYaw = rot[0];
            currentSmoothedPitch = rot[1];
            applyFacing(mc, rot[0], rot[1], style);
            return;
        }
        currentSmoothedYaw = LookRotation.approachAngle(currentSmoothedYaw, targetYaw, 16.0f);
        currentSmoothedPitch = LookRotation.approach(currentSmoothedPitch, basePitchBias, 10.0f);
        if (silentRotation.get()) {
            // Silent rotation: the server sees us facing forward via movement packets, but the
            // client camera never snaps (less flaggy). The crosshair dig still uses the camera.
            com.autism.seedcracker.util.tunnel.SilentRotation.apply(currentSmoothedYaw, clampPitch(currentSmoothedPitch));
        } else {
            com.autism.seedcracker.util.tunnel.SilentRotation.clear();
            LookRotation.apply(currentSmoothedYaw, clampPitch(currentSmoothedPitch));
        }
    }

    /**
     * Walk forward toward a target block. When silent rotation is active and silent-strafe is on,
     * resolve the movement keys against the SILENT yaw (wocky strafe-relative-to-silent-yaw) so we
     * keep walking down the tunnel while the server sees us looking at the dig face. Otherwise a
     * plain forward press.
     */
    private void walkForward(Minecraft mc, BlockPos target) {
        if (silentStrafe.get() && com.autism.seedcracker.util.tunnel.SilentRotation.isActive()) {
            com.autism.seedcracker.util.tunnel.MovementInput.apply(
                target.getX() + 0.5, target.getZ() + 0.5, false, false);
        } else {
            mc.options.keyUp.setDown(true);
        }
    }

    // ---- main tunneling state machine ----

    private void tickTunneling(Minecraft mc) {
        BlockPos currentPos = mc.player.blockPosition();

        // Cooldown must tick here: it previously only decremented inside ONE rotation style
        // (VANILLA humanized), so in every other style the first avoidance froze the cave/ground
        // safety scans FOREVER (avoidCooldown stuck at 60 -> walked straight into the next lake).
        if (avoidCooldown > 0) avoidCooldown--;

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

        // Livelier stuck ladder (was 100/200 ticks = 5/10s of standing still before reacting).
        if (stuckTicks > 40 && avoidState == AvoidState.NONE) {
            mc.options.keyUp.setDown(false);
            startObstacleAvoidance(mc);
            stuckTicks = 0;
        } else if (stuckTicks > 90) {
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
            caveAbort(mc, 60);
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
            caveAbort(mc, 20);
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

        // Dig with a CONVERGED aim (same believable packet path as WALK_2X1): pick the blocked
        // block (head first - tunnels clear top-down), aim onto it, and let digCrosshair break it
        // once the crosshair is really there. The old code relied on the crosshair HAPPENING to
        // rest on the block; at level pitch it never covered the feet block, so the module walked
        // into a half-cleared wall, tripped the stuck counter, and churned sidestep avoidance
        // forever - the "bugs out / lags back" loop.
        BlockPos digPos = null;
        if (headBlocked && !headHazard && !headLiquid) digPos = nextPos.above();
        else if (feetBlocked && !feetHazard && !feetLiquid) digPos = nextPos;
        if (digPos != null) {
            if (!digPos.equals(lastDigPos)) {
                lastDigPos = digPos;
                digTicks = 0;
                if (autoTool.get()) selectBestTool(mc, mc.level.getBlockState(digPos));
            } else if (++digTicks > digTimeout.get()) {
                // Unbreakable / wrong tool: don't stall forever - route around it.
                digTicks = 0;
                lastDigPos = null;
                handleBlockBreaking(mc, false, null);
                FlagDetectorModule.report("UNBREAKABLE", "TunnelBaseFinder",
                    "xenon dig timeout at " + digPos + " state=" + mc.level.getBlockState(digPos).getBlock());
                caveAbort(mc, 20);
                return;
            }
            mc.options.keyUp.setDown(false);      // stand still while digging (Grim legitimacy)
            mc.options.keySprint.setDown(false);
            aimAtBlock(mc, digPos);
            digCrosshair(mc);
            stuckTicks = 0; // breaking IS progress - don't trip the stuck ladder mid-deepslate
            return;
        }
        lastDigPos = null;
        digTicks = 0;
        handleBlockBreaking(mc, false, null);

        // Periodic walk pauses.
        if (walkPauseTicks > 0) { walkPauseTicks--; com.autism.seedcracker.util.tunnel.HumanMotionSim.releaseAll(); return; }
        if (walkPauseCooldown > 0) walkPauseCooldown--;
        else if (rng.nextFloat() < 0.03f) {
            walkPauseTicks = randomRange(10, 40);
            walkPauseCooldown = randomRange(60, 200);
            com.autism.seedcracker.util.tunnel.HumanMotionSim.releaseAll();
            return;
        }

        // Drive forward with the HumanMotionSim engine (ported from CodeEngine): it handles
        // forward/sprint/jump and stops at edges / fall hazards instead of a raw key press.
        net.minecraft.world.phys.Vec3 stepTarget = com.autism.seedcracker.util.tunnel.HumanMotionSim.pointAhead(1.0);
        if (stepTarget != null) {
            com.autism.seedcracker.util.tunnel.HumanMotionSim.step(stepTarget, 0.1, 180.0, 1, 1);
        } else {
            mc.options.keyUp.setDown(true);
        }
        stuckTicks = 0;
    }

    private int caveAborts = 0;
    private long lastCaveAbortTick = 0;

    /**
     * Cave/ground abort with escalation: repeated aborts in the same area mean the sidestep dance
     * is circling a megacave/ravine - after 4 of them inside 30s, turn 90 degrees and commit to a
     * fresh line instead of sidestepping forever.
     */
    private void caveAbort(Minecraft mc, int cooldown) {
        mc.options.keyUp.setDown(false);
        if (avoidCooldown > 0) return;
        if (totalTicks - lastCaveAbortTick > 600) caveAborts = 0;
        lastCaveAbortTick = totalTicks;
        if (++caveAborts >= 4) {
            caveAborts = 0;
            tunnelDirection = tunnelDirection.getClockWise();
            retargetLine(mc);
            avoidState = AvoidState.NONE;
            avoidCooldown = 40;
            FlagDetectorModule.report("CAVE_ESCALATE", "TunnelBaseFinder",
                "4 cave aborts - turned " + tunnelDirection);
            return;
        }
        startObstacleAvoidance(mc);
        avoidCooldown = cooldown;
    }

    /** Re-anchor the strafe-correction line after any 90-degree heading change. */
    private void retargetLine(Minecraft mc) {
        if (tunnelDirection == Direction.NORTH || tunnelDirection == Direction.SOUTH) {
            fixedCoordIsX = true;
            fixedCoord = Math.floor(mc.player.getX()) + 0.5;
        } else {
            fixedCoordIsX = false;
            fixedCoord = Math.floor(mc.player.getZ()) + 0.5;
        }
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
                Direction sideDir = getSideDirection(tunnelDirection, avoidSideDirection);
                if (fixedCoordIsX) fixedCoord += sideDir.getStepX();
                else fixedCoord += sideDir.getStepZ();
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
            avoidCooldown = 60;
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

        // Convergence gate: only swing once BOTH axes are close AND the crosshair ray actually
        // intersects the target (hitting a player you're not looking at is a Grim HitBox flag).
        if (Math.abs(angleDiff(currentSmoothedYaw, targetYaw)) > com.autism.seedcracker.util.Tuning.AIM_CONVERGENCE_DEG
            || Math.abs(currentSmoothedPitch - targetPitch) > com.autism.seedcracker.util.Tuning.ATTACK_PITCH_TOLERANCE_DEG) return;
        if (playerAttackCooldown > 0) { playerAttackCooldown--; return; }
        // WexSide HitCooldown: only swing once the vanilla attack bar has recharged -
        // spam-clicking mid-cooldown does no damage and reads as autoclicker.
        if (mc.player.getAttackStrengthScale(0.5f) < com.autism.seedcracker.util.Tuning.ATTACK_STRENGTH_GATE) return;
        boolean rayOnTarget = mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult ehr
            && ehr.getEntity() == targetPlayer;
        if (rayOnTarget && mc.player.distanceToSqr(targetPlayer) <= 9.0) { // vanilla 3-block reach
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
            if (autismclient.modules.TeamsModule.isFriendOrTeam(p)) continue; // never defend-attack a friend
            double d = mc.player.distanceToSqr(p);
            if (d < nearestDist) { nearestDist = d; nearest = p; }
        }
        return nearest;
    }

    // ---- block breaking ----

    private void mineDirect(Minecraft mc, BlockPos pos) {
        miningLookDown = true;
        aimAtBlock(mc, pos);
        digCrosshair(mc);
    }

    /**
     * Xenon-style dig: only break the block the client's own crosshair ray-trace is on, using
     * the real reported face (mirrors Xenon AutoMine.processMiningAction). This is what keeps
     * it from flagging - the dig packets are identical to a real player holding left-click on
     * the block they are looking at.
     */
    private void digCrosshair(Minecraft mc) {
        if (mc.player.isUsingItem()) return;
        if (!mc.player.onGround()) return; // never dig while airborne (Grim flag)
        // AUTISM mode uses a slow human rotation - don't dig until the crosshair has actually
        // converged on the intended block, else we dig the wrong block mid-turn and lag back.
        if (movementStyle.get() == MovementStyle.AUTISM && digTarget != null
            && !autismAim.isDone(getTargetYaw(digTarget), getTargetPitch(digTarget))) {
            return; // still turning onto the block
        }
        net.minecraft.world.phys.HitResult target = mc.hitResult;
        if (target != null && target.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            net.minecraft.world.phys.BlockHitResult bhr = (net.minecraft.world.phys.BlockHitResult) target;
            BlockPos pos = bhr.getBlockPos();
            if (!withinReach(mc, pos)) return;
            // Anti-lagback: vanilla continueDestroyBlock RESTARTS the dig (start+abort packets)
            // every time the position changes. Digging whatever the ray touches mid-turn churned
            // START/ABORT across neighbouring blocks and desynced server break progress. Only dig
            // when the ray is on the intended block, or the aim has converged (<8 deg off) so a
            // neighbouring face of the same cluster is a genuine near-miss, not turn spray.
            if (digTarget != null && !pos.equals(digTarget)) {
                float conv = com.autism.seedcracker.util.Tuning.AIM_CONVERGENCE_DEG;
                float yawErr = Math.abs(angleDiff(mc.player.getYRot(), getTargetYaw(digTarget)));
                float pitchErr = Math.abs(mc.player.getXRot() - getTargetPitch(digTarget));
                if (yawErr > conv || pitchErr > conv) return; // still turning; don't touch this block
            }
            // Break-desync re-sync (nyx AutoTunnelUtil, same as AutoMine): if the game-mode is
            // mid-break on a DIFFERENT block than the ray target, release before re-pressing -
            // continueDestroyBlock on a new pos silently aborts+restarts, churning packets.
            if (digDesyncCooldown > 0) { digDesyncCooldown--; return; }
            try {
                var acc = (autismclient.mixin.accessor.AutismMultiPlayerGameModeAccessor) mc.gameMode;
                if (acc.autism$isDestroying()) {
                    BlockPos breaking = acc.autism$getDestroyBlockPos();
                    if (breaking != null && !breaking.equals(pos)
                        && !mc.level.getBlockState(breaking).isAir()) {
                        mc.gameMode.stopDestroyBlock();
                        digDesyncCooldown = com.autism.seedcracker.util.Tuning.DESYNC_BACKOFF_MIN
                            + rng.nextInt(com.autism.seedcracker.util.Tuning.DESYNC_BACKOFF_MAX);
                        return;
                    }
                }
            } catch (Throwable ignored) {}
            if (!mc.level.getBlockState(pos).isAir() && mc.gameMode != null) {
                mc.gameMode.continueDestroyBlock(pos, bhr.getDirection());
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
        }
    }

    private int digDesyncCooldown = 0;

    private static float getTargetYaw(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        double dx = pos.getX() + 0.5 - mc.player.getX();
        double dz = pos.getZ() + 0.5 - mc.player.getZ();
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    private static float getTargetPitch(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        double dx = pos.getX() + 0.5 - mc.player.getX();
        double dy = pos.getY() + 0.5 - mc.player.getEyeY();
        double dz = pos.getZ() + 0.5 - mc.player.getZ();
        return (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
    }

    /** Vanilla-ish reach check (a little over survival reach to be safe, not exploit-far). */
    private static boolean withinReach(Minecraft mc, BlockPos pos) {
        double dx = pos.getX() + 0.5 - mc.player.getX();
        double dy = pos.getY() + 0.5 - mc.player.getEyeY();
        double dz = pos.getZ() + 0.5 - mc.player.getZ();
        return dx * dx + dy * dy + dz * dz <= 5.2 * 5.2;
    }

    /** The block we're currently trying to dig (so digCrosshair only fires on it). */
    private BlockPos digTarget = null;
    private BlockPos lastDigPos = null;
    private boolean digActive = false;

    private final com.autism.seedcracker.util.StuckDetector stuck =
        new com.autism.seedcracker.util.StuckDetector("TunnelBaseFinderModule",
            com.autism.seedcracker.util.Tuning.STUCK_TICKS_TUNNEL, com.autism.seedcracker.util.Tuning.STUCK_EPSILON);

    // ---- auto-mend ----
    private int mendCooldown = 0;
    private int mendingTicks = 0;
    private boolean wasMending = false;
    private int mendPhase = 0; // 0=select xp, 1=throw, 2=swap back
    private int mendPickSlot = -1;

    /**
     * When the held pickaxe is below the mend threshold, pause digging and throw XP bottles to
     * repair it (Mending). Returns true while a mend cycle is active (so digging pauses).
     */
    private boolean tickMend(Minecraft mc) {
        if (mendCooldown > 0) { mendCooldown--; }
        net.minecraft.world.item.ItemStack held = mc.player.getMainHandItem();
        boolean isPick = !held.isEmpty() && net.minecraft.core.registries.BuiltInRegistries.ITEM
            .getKey(held.getItem()).toString().endsWith("_pickaxe");
        boolean lowDurability = isPick && held.getMaxDamage() > 0
            && (held.getMaxDamage() - held.getDamageValue()) <= mendThreshold.get();

        if (!lowDurability) {
            if (wasMending) { wasMending = false; mendingTicks = 0; }
            return false;
        }

        // Find XP bottles in the hotbar; if none, we can't mend (resume digging to avoid stalling).
        int xpSlot = findItemSlot(mc, net.minecraft.world.item.Items.EXPERIENCE_BOTTLE);
        if (xpSlot == -1) return false;

        // Pause digging during the mend.
        mc.options.keyAttack.setDown(false);
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        wasMending = true;
        mendingTicks++;

        // Look down (throw bottles at your feet so the XP orbs hit you) and throw on a cooldown.
        // Spread select -> use -> swap-back over separate ticks: a same-tick carried-item change
        // plus use-item plus another carried-item change is a classic bot packet burst.
        LookRotation.apply(mc.player.getYRot(), 85f);
        if (mc.gameMode != null) {
            switch (mendPhase) {
                case 0 -> {
                    if (mendCooldown > 0) return true;
                    mendPickSlot = mc.player.getInventory().getSelectedSlot();
                    com.autism.seedcracker.util.InvSync.select(mc, xpSlot);
                    mendPhase = 1;
                }
                case 1 -> {
                    // Verify we actually hold a bottle before throwing (select may not have landed).
                    if (!mc.player.getMainHandItem().is(net.minecraft.world.item.Items.EXPERIENCE_BOTTLE)) {
                        mendPhase = 0; return true;
                    }
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    mendPhase = 2;
                }
                case 2 -> {
                    // Swap back to the pickaxe next tick so Mending repairs it when orbs land.
                    if (mendPickSlot >= 0 && mendPickSlot <= 8) {
                        com.autism.seedcracker.util.InvSync.select(mc, mendPickSlot);
                    }
                    mendCooldown = 12;
                    mendPhase = 0;
                }
            }
        }
        // Stay in mend mode while low (digging stays paused); orbs land and repair the pickaxe.
        return true;
    }

    // ---- pause-to-eat ----
    private boolean eating = false;
    private int eatPrevSlot = -1;

    /** Pause digging and eat when hungry; resumes after. Returns true while eating. */
    private boolean tickEat(Minecraft mc) {
        if (mc.gameMode == null) return false;
        var food = mc.player.getFoodData();
        boolean hungry = food.getFoodLevel() <= eatHunger.get() && food.getFoodLevel() < 20;

        if (!eating) {
            if (!hungry) return false;
            int foodSlot = findFoodSlot(mc);
            if (foodSlot == -1) return false; // no food: keep digging
            eating = true;
            eatPrevSlot = mc.player.getInventory().getSelectedSlot();
            com.autism.seedcracker.util.InvSync.select(mc, foodSlot);
        }

        // Eating: stop digging, hold use until full (or food runs out).
        mc.options.keyAttack.setDown(false);
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        var heldFood = mc.player.getInventory().getItem(mc.player.getInventory().getSelectedSlot());
        if (!isFood(heldFood)) {
            int next = findFoodSlot(mc);
            if (next == -1) { stopEating(mc); return false; }
            com.autism.seedcracker.util.InvSync.select(mc, next);
        }
        mc.options.keyUse.setDown(true);
        if (!mc.player.isUsingItem()) mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);

        if (food.getFoodLevel() >= 20 || !hungry) { stopEating(mc); return false; }
        return true;
    }

    private void stopEating(Minecraft mc) {
        mc.options.keyUse.setDown(false);
        if (eatPrevSlot >= 0 && eatPrevSlot <= 8) com.autism.seedcracker.util.InvSync.select(mc, eatPrevSlot);
        eatPrevSlot = -1;
        eating = false;
    }

    private boolean isFood(net.minecraft.world.item.ItemStack s) {
        return !s.isEmpty() && s.has(net.minecraft.core.component.DataComponents.FOOD);
    }

    private int findFoodSlot(Minecraft mc) {
        for (int i = 0; i < 9; i++) {
            if (isFood(mc.player.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    // ---- sell-on-full ----
    private int sellCooldown = 0;

    /** When the inventory is full, /ah sell (or /orders deliver) the listed items instead of dropping them. */
    private void tickSellOnFull(Minecraft mc) {
        if (sellCooldown > 0) { sellCooldown--; return; }
        if (mc.getConnection() == null) return;
        // Full = no empty slot across hotbar + main inventory.
        boolean full = true;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) { full = false; break; }
        }
        if (!full) return;

        // Build the wanted-id set.
        java.util.Set<String> wanted = new java.util.HashSet<>();
        for (String s : sellItems.get().split(",")) wanted.add(s.trim().toLowerCase(java.util.Locale.ROOT));

        // Find a HOTBAR slot holding a listed item (we can only /ah sell the held item, so it must
        // be in the hotbar and we must select it first - never sell whatever happens to be held).
        for (int i = 0; i < 9; i++) {
            net.minecraft.world.item.ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
                .toString().replace("minecraft:", "");
            if (!wanted.contains(id)) continue;
            // Select the slot, then VERIFY the held item is the one we intend to sell before selling.
            com.autism.seedcracker.util.InvSync.select(mc, i);
            net.minecraft.world.item.ItemStack held = mc.player.getMainHandItem();
            String heldId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held.getItem())
                .toString().replace("minecraft:", "");
            if (!heldId.equals(id)) { sellCooldown = 5; return; } // swap hasn't registered yet; retry
            if (sellViaOrders.get()) {
                mc.getConnection().sendCommand("orders");
            } else {
                mc.getConnection().sendCommand("ah sell " + sellPrice.get().trim().replace(",", ""));
            }
            sellCooldown = 20;
            return;
        }
        // The wanted item isn't in the hotbar. Move a matching main-inventory stack into the hotbar
        // first (so we never sell the pickaxe/held item by mistake), then sell next tick.
        for (int i = 9; i < 36; i++) {
            net.minecraft.world.item.ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
                .toString().replace("minecraft:", "");
            if (!wanted.contains(id)) continue;
            // Find a hotbar slot that is NOT a wanted-sell item and NOT the tool (prefer junk).
            int swapTarget = findHotbarSwapTarget(mc, wanted);
            if (swapTarget >= 0) {
                swapInventoryToHotbar(mc, i, swapTarget);
                sellCooldown = 5;
                return;
            }
        }
        // Nothing listed to sell: drop nothing, just wait (don't loop-sell).
        sellCooldown = 60;
    }

    /** A hotbar slot we can safely overwrite (empty, or itself a wanted sell item, not a tool). */
    private int findHotbarSwapTarget(Minecraft mc, java.util.Set<String> wanted) {
        for (int i = 0; i < 9; i++) {
            net.minecraft.world.item.ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) return i;
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
                .toString().replace("minecraft:", "");
            // Safe to consolidate onto another stack of a sellable junk item (not a tool).
            if (wanted.contains(id) && !id.endsWith("_pickaxe") && !id.endsWith("_axe")
                && !id.endsWith("_shovel") && !id.endsWith("_sword")) return i;
        }
        return -1;
    }

    /** Move a main-inventory stack into a hotbar slot via the player inventory menu 3-click swap. */
    private void swapInventoryToHotbar(Minecraft mc, int from, int hotbarSlot) {
        var h = mc.player.inventoryMenu;
        com.autism.seedcracker.util.ContainerMutex.notifyContainerAction(); mc.gameMode.handleContainerInput(h.containerId, from, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
        com.autism.seedcracker.util.ContainerMutex.notifyContainerAction(); mc.gameMode.handleContainerInput(h.containerId, 36 + hotbarSlot, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
        com.autism.seedcracker.util.ContainerMutex.notifyContainerAction(); mc.gameMode.handleContainerInput(h.containerId, from, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
    }

    private int findItemSlot(Minecraft mc, net.minecraft.world.item.Item item) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(item)) return i;
        }
        return -1;
    }

    /** Swap to the hotbar slot with the fastest destroy speed for the given block (auto-tool). */
    private void selectBestTool(Minecraft mc, net.minecraft.world.level.block.state.BlockState state) {
        int bestSlot = -1;
        float bestSpeed = -1.0f;
        for (int slot = 0; slot < 9; slot++) {
            net.minecraft.world.item.ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            float speed = stack.getDestroySpeed(state);
            // Skip non-tools (speed 1.0 = hand) unless nothing better exists.
            if (speed > bestSpeed) { bestSpeed = speed; bestSlot = slot; }
        }
        if (bestSlot == -1) return;
        float current = mc.player.getMainHandItem().getDestroySpeed(state);
        if (bestSpeed > current) {
            com.autism.seedcracker.util.InvSync.select(mc, bestSlot);
        }
    }

    /**
     * Smoothly turn the camera toward a block (real-player mouse speed), not an instant snap.
     * Records the block as the dig target so digCrosshair only fires once we're actually on it.
     */
    private void aimAtBlock(Minecraft mc, BlockPos pos) {
        digTarget = pos;
        double dx = pos.getX() + 0.5 - mc.player.getX();
        double dy = pos.getY() + 0.5 - mc.player.getEyeY();
        double dz = pos.getZ() + 0.5 - mc.player.getZ();
        double dist = Math.hypot(dx, dz);
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, dist));
        MovementStyle style = movementStyle.get();
        if (style == MovementStyle.AUTISM) {
            com.autism.seedcracker.util.tunnel.SilentRotation.clear();
            float[] rot = autismAim.faceBlock(mc, pos);
            currentSmoothedYaw = rot[0];
            currentSmoothedPitch = rot[1];
            return;
        }
        if (style == MovementStyle.MOUSE) {
            // Mouse-delta rotation drives the crosshair onto the block through the real mouse path.
            com.autism.seedcracker.util.tunnel.MouseRotation.get().rotateTo(targetYaw, targetPitch);
            com.autism.seedcracker.util.tunnel.SilentRotation.clear();
            return;
        }
        if (style == MovementStyle.LEGIT || style == MovementStyle.SILENT || style == MovementStyle.HAZARD_ONLY) {
            // Legit engine eases onto the block like a human mouse. A dig genuinely needs the
            // crosshair on the block, so even SILENT mode eases the real camera here (silent
            // packet-rotation is reserved for walking, where we don't need the crosshair).
            float[] rot = legitMovement.update(targetYaw, targetPitch);
            currentSmoothedYaw = rot[0];
            currentSmoothedPitch = rot[1];
            com.autism.seedcracker.util.tunnel.SilentRotation.clear();
            mc.player.setYRot(rot[0]);
            mc.player.setXRot(clampPitch(rot[1]));
            return;
        }
        // VANILLA path: ease onto the block with the human-turn curve (fast flick, slow settle,
        // no overshoot) so it looks like a real mouse instead of a constant-speed snap.
        float yawStep = 26.0f, pitchStep = 20.0f;
        currentSmoothedYaw = LookRotation.humanTurn(currentSmoothedYaw, targetYaw, yawStep);
        currentSmoothedPitch = LookRotation.humanTurnPitch(currentSmoothedPitch, targetPitch, pitchStep);
        LookRotation.apply(currentSmoothedYaw, clampPitch(currentSmoothedPitch));
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
                    // Skip natural-structure spawners (dungeons / trial chambers) - they aren't
                    // player bases. A dungeon/trial-chamber spawner sits among mossy cobblestone,
                    // tuff/copper/grate blocks, etc.
                    if (isNaturalStructureSpawner(mc, be.getBlockPos())) continue;
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

    /**
     * True if a spawner is part of a natural structure (dungeon or trial chamber) rather than a
     * player base. Checks the blocks around the spawner for structure-specific blocks: mossy
     * cobblestone (dungeons) or tuff / copper / trial-chamber blocks (trial chambers).
     */
    private boolean isNaturalStructureSpawner(Minecraft mc, BlockPos spawnerPos) {
        int structureBlocks = 0;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    Block b = mc.level.getBlockState(spawnerPos.offset(dx, dy, dz)).getBlock();
                    if (b == Blocks.MOSSY_COBBLESTONE
                        || b == Blocks.TUFF || b == Blocks.POLISHED_TUFF || b == Blocks.TUFF_BRICKS
                        || b == Blocks.CHISELED_TUFF || b == Blocks.CHISELED_TUFF_BRICKS
                        || b == Blocks.TRIAL_SPAWNER || b == Blocks.VAULT) {
                        structureBlocks++;
                    }
                }
            }
        }
        // If several structure blocks surround the spawner, it's a natural structure, not a base.
        return structureBlocks >= 3;
    }

    private void onBaseFound(Minecraft mc, String kind, int x, int y, int z) {
        if (!notify.get()) return;
        // Dedup: only notify once per base (keyed by kind + rough location, with a cooldown), so
        // the sound/toast doesn't spam every tick while a base stays in range.
        long now = System.currentTimeMillis();
        String key = kind + ":" + (x >> 4) + ":" + (z >> 4);
        Long last = foundNotifiedAt.get(key);
        if (last != null && now - last < FOUND_COOLDOWN_MS) return;
        foundNotifiedAt.put(key, now);

        String msg = "Found " + kind + " at X:" + x + " Y:" + y + " Z:" + z;
        AutismNotifications.warning("Tunnel base: " + kind + " at " + x + " " + z);
        AutismClientMessaging.sendPrefixed("§d[TunnelBaseFinder] §f" + msg);
        logToBaseFile(mc, x, y, z, kind);
        if (mc.player != null) mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        if (pauseOnFind.get()) setEnabled(false);
    }

    /** kind:chunkX:chunkZ -> last-notified time (dedupes the base-found sound/toast). */
    private final java.util.Map<String, Long> foundNotifiedAt = new java.util.HashMap<>();
    private static final long FOUND_COOLDOWN_MS = 60_000L;

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

    // Hazard classification lives in the shared Hazards util (single source of truth for both
    // tunnel bots); these thin wrappers keep the module's call sites unchanged.
    private boolean isHazardous(Minecraft mc, BlockPos pos) {
        return com.autism.seedcracker.util.tunnel.Hazards.isHazardous(mc, pos);
    }

    private boolean isLiquid(Minecraft mc, BlockPos pos) {
        return com.autism.seedcracker.util.tunnel.Hazards.isLiquid(mc, pos);
    }

    private boolean isLava(Minecraft mc, BlockPos pos) {
        return com.autism.seedcracker.util.tunnel.Hazards.isLava(mc, pos);
    }

    private boolean hasHazardAbove(Minecraft mc, BlockPos headPos, int maxHeight) {
        return com.autism.seedcracker.util.tunnel.Hazards.hasHazardAbove(mc, headPos, maxHeight);
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
