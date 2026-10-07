package com.autism.seedcracker.motion;

import com.autism.seedcracker.util.InvSync;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Throwaway blocks for bridging: cheap full cubes from the hotbar, never valuables or gravity blocks. */
final class BridgeBlocks {
    private BridgeBlocks() {}

    /** How many bridge blocks the hotbar holds (the planner's place budget). */
    static int count(Minecraft mc) {
        if (mc.player == null) return 0;
        int n = 0;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack s = mc.player.getInventory().getItem(slot);
            if (isThrowaway(s)) n += s.getCount();
        }
        return n;
    }

    /** Hotbar slot with a throwaway block (prefers the one in hand), or -1. */
    static int slot(Minecraft mc) {
        int sel = mc.player.getInventory().getSelectedSlot();
        if (isThrowaway(mc.player.getInventory().getItem(sel))) return sel;
        for (int slot = 0; slot < 9; slot++) if (isThrowaway(mc.player.getInventory().getItem(slot))) return slot;
        return -1;
    }

    /**
     * Places a block at {@code floor} against a solid neighbour. Returns true once the block is
     * there; false while still working (swap/aim) or when it can't be done this tick.
     */
    static boolean place(Minecraft mc, BlockPos floor, String rotOwner) {
        if (!mc.level.getBlockState(floor).canBeReplaced()) return true;
        int slot = slot(mc);
        if (slot < 0 || mc.gameMode == null) return false;
        if (mc.player.getInventory().getSelectedSlot() != slot) {
            InvSync.select(mc, slot);
            return false;
        }
        for (Direction d : Direction.values()) {
            if (d == Direction.UP) continue;
            BlockPos against = floor.relative(d);
            if (mc.level.getBlockState(against).getCollisionShape(mc.level, against).isEmpty()) continue;
            Direction face = d.getOpposite();
            Vec3 hit = Vec3.atCenterOf(against).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            if (mc.player.getEyePosition().distanceTo(hit) > 4.4) continue;
            // A block between eye and face (our own head, a wall) would make the server reject or redirect the click.
            var los = mc.level.clip(new net.minecraft.world.level.ClipContext(mc.player.getEyePosition(), hit,
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
            if (los.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && !los.getBlockPos().equals(against)) continue;
            // Placing into the cell we're standing in fails; pillars wait until the feet have cleared it.
            if (mc.player.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(floor))) return false;
            if (!RotationEngine.lookAt(rotOwner, RotationEngine.PRIORITY_INTERACT, hit)) return false;
            if (!com.autism.seedcracker.util.ActionPacer.tryAction()) return false;
            InteractionResult r = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, face, against, false));
            if (r.consumesAction()) mc.player.swing(InteractionHand.MAIN_HAND);
            return false;
        }
        return false;
    }

    static boolean isThrowaway(ItemStack s) {
        if (s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) return false;
        Block b = bi.getBlock();
        if (b instanceof FallingBlock) return false;
        return b == Blocks.COBBLESTONE || b == Blocks.COBBLED_DEEPSLATE || b == Blocks.DIRT || b == Blocks.NETHERRACK
            || b == Blocks.STONE || b == Blocks.DEEPSLATE || b == Blocks.ANDESITE || b == Blocks.DIORITE
            || b == Blocks.GRANITE || b == Blocks.TUFF || b == Blocks.BLACKSTONE || b == Blocks.END_STONE
            || b == Blocks.OAK_PLANKS || b == Blocks.SPRUCE_PLANKS || b == Blocks.BIRCH_PLANKS;
    }
}
