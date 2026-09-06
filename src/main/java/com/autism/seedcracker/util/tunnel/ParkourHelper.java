package com.autism.seedcracker.util.tunnel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Decides when a jump is safe/needed while tunneling, and performs it.
 *
 * Detects a 1-block obstacle ahead with clearance above (jump onto it), or a safe drop (just
 * walk), and exposes a small jump state machine with a cooldown so jumps aren't spammed.
 *
 * Port of the Krypton AI ParkourHelper (dev.FORE.AI) to Mojang 26.2 mappings.
 */
public final class ParkourHelper {
    private static final int JUMP_COOLDOWN_TICKS = 10;

    private final Minecraft mc = Minecraft.getInstance();
    private boolean isJumping = false;
    private int jumpCooldown = 0;
    private BlockPos jumpTarget = null;
    private Vec3 jumpStartPos = null;

    public static final class JumpCheck {
        public final boolean canProceed;
        public final boolean shouldJump;
        public final BlockPos jumpTarget;
        public final String reason;
        public JumpCheck(boolean canProceed, boolean shouldJump, BlockPos jumpTarget, String reason) {
            this.canProceed = canProceed;
            this.shouldJump = shouldJump;
            this.jumpTarget = jumpTarget;
            this.reason = reason;
        }
    }

    public JumpCheck checkJumpOpportunity(LocalPlayer player, Direction movingDirection) {
        if (jumpCooldown > 0 || !player.onGround() || isJumping) {
            return new JumpCheck(false, false, null, "On cooldown or already jumping");
        }
        Level world = mc.level;
        if (world == null) return new JumpCheck(false, false, null, "No world");

        BlockPos playerPos = player.blockPosition();
        BlockPos frontPos = playerPos.relative(movingDirection);
        BlockPos frontGround = frontPos.below();
        BlockState frontState = world.getBlockState(frontPos);
        BlockState frontGroundState = world.getBlockState(frontGround);

        // Safe 1-block drop: air at foot, solid ground 1 below.
        if (frontState.isAir() && !frontGroundState.isAir() && solid(world, frontGround)) {
            if (world.getBlockState(frontGround.below()).isAir()) {
                return new JumpCheck(true, false, null, "Safe 1-block drop - just walk");
            }
        }

        // Obstacle ahead: solid block at foot level.
        if (!frontState.isAir() && solid(world, frontPos)) {
            BlockPos aboveFront = frontPos.above();
            BlockState aboveFrontState = world.getBlockState(aboveFront);
            BlockState twoAboveFrontState = world.getBlockState(frontPos.above(2));
            if (aboveFrontState.isAir() && twoAboveFrontState.isAir()) {
                BlockPos landingPos = frontPos.relative(movingDirection);
                BlockState landingState = world.getBlockState(landingPos);
                BlockState landingGroundState = world.getBlockState(landingPos.below());
                boolean safeLanding = landingState.isAir() && world.getBlockState(landingPos.above()).isAir();
                boolean hasGround = solid(world, landingPos.below()) || solid(world, landingPos.below(2));
                if (safeLanding && hasGround) {
                    return new JumpCheck(true, true, landingPos, "Safe jump available");
                }
                return aboveFrontState.isAir() && world.getBlockState(aboveFront.above()).isAir()
                    ? new JumpCheck(true, true, aboveFront, "Jump onto block")
                    : new JumpCheck(false, false, null, "No safe landing");
            }
            return new JumpCheck(false, false, null, "No clearance above obstacle");
        }

        return new JumpCheck(true, false, null, "No obstacle");
    }

    public boolean startJump(BlockPos target) {
        if (isJumping || jumpCooldown > 0) return false;
        LocalPlayer player = mc.player;
        if (player == null || !player.onGround()) return false;
        this.jumpTarget = target;
        this.jumpStartPos = player.position();
        this.isJumping = true;
        player.jumpFromGround();
        return true;
    }

    /** Call each tick; returns true when the jump finished (landed). */
    public boolean tickJump() {
        if (jumpCooldown > 0) jumpCooldown--;
        if (!isJumping) return false;
        LocalPlayer player = mc.player;
        if (player == null) { isJumping = false; return false; }
        if (player.onGround() && player.position().distanceTo(jumpStartPos) > 0.5) {
            isJumping = false;
            jumpCooldown = JUMP_COOLDOWN_TICKS;
            return true;
        }
        return false;
    }

    public boolean isJumping() { return isJumping; }

    private static boolean solid(Level world, BlockPos pos) {
        return !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }
}
