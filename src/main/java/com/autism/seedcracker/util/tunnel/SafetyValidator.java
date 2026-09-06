package com.autism.seedcracker.util.tunnel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Watches the player for "stuck" conditions while auto-tunneling and recommends a recovery action.
 *
 * Tracks horizontal and vertical movement over a rolling window. If the player stops making
 * progress (horizontal &lt; threshold and no longer-term drift), it escalates through recovery
 * steps: jump-forward, recalculate path, find a new spot, or finally request a module reset. Also
 * guards tool durability (stop before the pick breaks) and unsafe situations (falling far, lava
 * below).
 *
 * Port of the Krypton AI SafetyValidator (dev.FORE.AI) to Mojang 26.2 mappings.
 */
public final class SafetyValidator {
    private static final int RECOVERY_GRACE_TICKS = 40;
    private static final int STUCK_THRESHOLD = 60;
    private static final int MINING_DOWN_STUCK_THRESHOLD = 40;
    private static final int JUMP_COOLDOWN_TICKS = 20;
    private static final double MIN_DURABILITY_PERCENT = 0.1;
    private static final double MOVEMENT_THRESHOLD = 1.0;
    private static final double VERTICAL_MOVEMENT_THRESHOLD = 0.5;

    private Vec3 lastPosition;
    private int stuckTicks = 0;
    private int jumpCooldown = 0;
    private int unstuckAttempts = 0;
    private boolean needsModuleReset = false;
    private double lastYPosition = 0.0;
    private int verticalStuckTicks = 0;
    private int miningDownAttempts = 0;
    private Vec3 lastMiningDownPosition;
    private boolean jumpedWhileMoving = false;
    private int jumpFollowUpTicks = 0;
    private int recoveryGracePeriod = 0;
    private double recoveryStartY = 0.0;
    private boolean inRecovery = false;
    private Vec3[] positionHistory = new Vec3[20];
    private int historyIndex = 0;

    public enum MiningMode { NORMAL, MINING_DOWN, MOVING_TO_TARGET }
    public enum StuckRecoveryAction {
        NONE, JUMP_FORWARD, STOP_MOVEMENT, PATH_BLOCKED_RTP,
        RETOGGLE_KEYS, MOVE_AND_RETRY, FIND_NEW_SPOT, RECALCULATE_PATH, NEEDS_RESET
    }

    /** Whether the player can safely continue (tool present + durable, not falling into void/lava). */
    public boolean canContinue(LocalPlayer player, int maxY) {
        if (player == null) return false;
        if (player.getY() > maxY) return false;

        if (!player.onGround()) {
            boolean groundNearby = false;
            int groundDistance = 0;
            for (int i = 1; i <= 4; i++) {
                if (!player.level().getBlockState(player.blockPosition().below(i)).isAir()) {
                    groundNearby = true;
                    groundDistance = i;
                    break;
                }
            }
            if (groundNearby && groundDistance <= 2) {
                // safe small drop
            } else {
                if (!groundNearby && player.getDeltaMovement().y < -0.5) return false;
                if (!player.level().getFluidState(player.blockPosition().below()).isEmpty()) return false;
            }
        }

        ItemStack mainHand = player.getMainHandItem();
        if (isMiningTool(mainHand)) {
            double pct = getToolDurabilityPercent(mainHand);
            return pct > MIN_DURABILITY_PERCENT;
        }
        return false;
    }

    private boolean isMiningTool(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.is(Items.DIAMOND_PICKAXE) || stack.is(Items.NETHERITE_PICKAXE)
            || stack.is(Items.IRON_PICKAXE) || stack.is(Items.STONE_PICKAXE)
            || stack.is(Items.GOLDEN_PICKAXE) || stack.is(Items.WOODEN_PICKAXE)
            || stack.is(Items.DIAMOND_SHOVEL) || stack.is(Items.NETHERITE_SHOVEL)
            || stack.is(Items.IRON_SHOVEL) || stack.is(Items.DIAMOND_AXE) || stack.is(Items.NETHERITE_AXE);
    }

    /** Per-tick stuck check; returns the recovery action to take (usually NONE). */
    public StuckRecoveryAction checkAndHandleStuck(LocalPlayer player, MiningMode mode) {
        if (player == null) return StuckRecoveryAction.NONE;
        if (jumpCooldown > 0) jumpCooldown--;

        if (recoveryGracePeriod > 0) {
            recoveryGracePeriod--;
            if (mode == MiningMode.MINING_DOWN && recoveryGracePeriod == 1) {
                double progress = Math.abs(recoveryStartY - player.getY());
                if (progress < 1.0) inRecovery = false;
                else { miningDownAttempts = 0; inRecovery = false; }
            }
            return StuckRecoveryAction.NONE;
        }

        inRecovery = false;
        return switch (mode) {
            case MINING_DOWN -> checkMiningDownStuck(player);
            case NORMAL -> checkMovementStuck(player, true);
            case MOVING_TO_TARGET -> checkMovementStuck(player, false);
        };
    }

    private StuckRecoveryAction checkMiningDownStuck(LocalPlayer player) {
        double currentY = player.getY();
        if (lastMiningDownPosition == null) {
            lastMiningDownPosition = player.position();
            lastYPosition = currentY;
            return StuckRecoveryAction.NONE;
        }
        double vertical = Math.abs(currentY - lastYPosition);
        double horizontal = Math.hypot(player.getX() - lastMiningDownPosition.x, player.getZ() - lastMiningDownPosition.z);
        boolean stuck = vertical < VERTICAL_MOVEMENT_THRESHOLD || horizontal > 2.0;
        if (stuck) {
            verticalStuckTicks++;
            if (verticalStuckTicks >= MINING_DOWN_STUCK_THRESHOLD) {
                miningDownAttempts++;
                verticalStuckTicks = 0;
                recoveryStartY = currentY;
                inRecovery = true;
                recoveryGracePeriod = RECOVERY_GRACE_TICKS;
                lastYPosition = currentY;
                lastMiningDownPosition = player.position();
                return switch (miningDownAttempts) {
                    case 1 -> StuckRecoveryAction.RETOGGLE_KEYS;
                    case 2 -> StuckRecoveryAction.MOVE_AND_RETRY;
                    case 3 -> StuckRecoveryAction.FIND_NEW_SPOT;
                    default -> { needsModuleReset = true; yield StuckRecoveryAction.NEEDS_RESET; }
                };
            }
        } else {
            verticalStuckTicks = 0;
            if (vertical > 1.0) miningDownAttempts = 0;
            lastYPosition = currentY;
            lastMiningDownPosition = player.position();
        }
        return StuckRecoveryAction.NONE;
    }

    private StuckRecoveryAction checkMovementStuck(LocalPlayer player, boolean allowJumping) {
        Vec3 currentPos = player.position();
        positionHistory[historyIndex] = currentPos;
        historyIndex = (historyIndex + 1) % positionHistory.length;
        if (lastPosition == null) {
            lastPosition = currentPos;
            return StuckRecoveryAction.NONE;
        }
        double horizontal = Math.hypot(currentPos.x - lastPosition.x, currentPos.z - lastPosition.z);
        Vec3 oldPos = positionHistory[(historyIndex + 1) % positionHistory.length];
        double longerTerm = oldPos == null ? 0.0 : Math.hypot(currentPos.x - oldPos.x, currentPos.z - oldPos.z);

        if (jumpedWhileMoving && jumpFollowUpTicks > 0) {
            jumpFollowUpTicks--;
            if (horizontal > MOVEMENT_THRESHOLD) {
                jumpedWhileMoving = false;
                jumpFollowUpTicks = 0;
                return StuckRecoveryAction.STOP_MOVEMENT;
            }
            if (jumpFollowUpTicks == 0) jumpedWhileMoving = false;
        }

        boolean stuck = horizontal < MOVEMENT_THRESHOLD && (oldPos == null || longerTerm < 0.5);
        if (stuck) {
            stuckTicks++;
            if (stuckTicks >= STUCK_THRESHOLD && jumpCooldown <= 0) {
                unstuckAttempts++;
                stuckTicks = 0;
                lastPosition = currentPos;
                switch (unstuckAttempts) {
                    case 1 -> {
                        if (allowJumping && player.onGround()) {
                            jumpedWhileMoving = true;
                            jumpFollowUpTicks = 20;
                            jumpCooldown = JUMP_COOLDOWN_TICKS;
                            return StuckRecoveryAction.JUMP_FORWARD;
                        }
                        return StuckRecoveryAction.RECALCULATE_PATH;
                    }
                    case 2 -> {
                        return allowJumping ? StuckRecoveryAction.RECALCULATE_PATH : StuckRecoveryAction.FIND_NEW_SPOT;
                    }
                    case 3 -> { return StuckRecoveryAction.PATH_BLOCKED_RTP; }
                    default -> {
                        needsModuleReset = true;
                        jumpCooldown = RECOVERY_GRACE_TICKS;
                        return StuckRecoveryAction.NEEDS_RESET;
                    }
                }
            }
        } else {
            stuckTicks = 0;
            unstuckAttempts = 0;
            needsModuleReset = false;
            jumpedWhileMoving = false;
            jumpFollowUpTicks = 0;
            lastPosition = currentPos;
        }
        return StuckRecoveryAction.NONE;
    }

    public void resetMiningDown() {
        verticalStuckTicks = 0;
        miningDownAttempts = 0;
        lastMiningDownPosition = null;
        lastYPosition = 0.0;
        recoveryGracePeriod = 0;
        inRecovery = false;
        recoveryStartY = 0.0;
    }

    public boolean needsModuleReset() { return needsModuleReset; }

    public void acknowledgeReset() {
        needsModuleReset = false;
        unstuckAttempts = 0;
        stuckTicks = 0;
        miningDownAttempts = 0;
        verticalStuckTicks = 0;
        recoveryGracePeriod = 0;
        inRecovery = false;
    }

    public void reset() {
        stuckTicks = 0;
        jumpCooldown = 0;
        unstuckAttempts = 0;
        needsModuleReset = false;
        lastPosition = null;
        positionHistory = new Vec3[20];
        historyIndex = 0;
        resetMiningDown();
    }

    public boolean isStuck() {
        return stuckTicks >= STUCK_THRESHOLD || verticalStuckTicks >= MINING_DOWN_STUCK_THRESHOLD;
    }

    public int getUnstuckAttempts() {
        return Math.max(unstuckAttempts, miningDownAttempts);
    }

    public boolean isInRecovery() {
        return inRecovery || recoveryGracePeriod > 0;
    }

    public static double getToolDurabilityPercent(ItemStack tool) {
        if (tool.isEmpty() || tool.getMaxDamage() == 0) return 1.0;
        int remaining = tool.getMaxDamage() - tool.getDamageValue();
        return (double) remaining / (double) tool.getMaxDamage();
    }
}
