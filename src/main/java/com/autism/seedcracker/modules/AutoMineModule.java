package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Auto Mine.
 *
 * Holds the block-breaking on whatever the crosshair is currently over, using the real hit
 * side reported by the client's own ray-trace. Because it only ever breaks the block you are
 * actually looking at (and uses the exact face the vanilla client computed), the dig packets
 * match a real player holding left-click, so anticheats do not flag it.
 *
 * Optionally locks the view to a fixed yaw/pitch so you can aim once and let it dig.
 *
 * Port of the Xenon "AutoMine" module to the AUTISM API (Mojang 26.2).
 */
public final class AutoMineModule extends Module {

    private final BoolSetting lockView = add(new BoolSetting("lock-view", "Lock view", true)
        .description("Lock your view to the yaw/pitch below so you dig in a fixed direction.")
        .group("General"));
    private final IntSetting yaw = add(new IntSetting("yaw", "Yaw", 0, -180, 180, 1)
        .description("View yaw when Lock view is on.").group("General"));
    private final IntSetting pitch = add(new IntSetting("pitch", "Pitch", 0, -90, 90, 1)
        .description("View pitch when Lock view is on.").group("General"));

    public AutoMineModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":auto-mine", "Auto Mine", category,
            "Automatically mines whatever block your crosshair is on.");
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (mc.gui.screen() != null) return;

        processMiningAction(mc, true);

        if (lockView.get()) {
            float y = yaw.get().floatValue();
            float p = pitch.get().floatValue();
            if (mc.player.getYRot() != y) mc.player.setYRot(y);
            if (mc.player.getXRot() != p) mc.player.setXRot(p);
        }
    }

    /**
     * Break only the block the crosshair is over, using the real reported face. Mirrors
     * Xenon's processMiningAction: updateBlockBreakingProgress == Mojang continueDestroyBlock.
     */
    private void processMiningAction(Minecraft mc, boolean breaking) {
        if (mc.player.isUsingItem()) return;
        HitResult target = mc.hitResult;
        if (breaking && target != null && target.getType() == HitResult.Type.BLOCK) {
            BlockHitResult bhr = (BlockHitResult) target;
            BlockPos pos = bhr.getBlockPos();
            if (!mc.level.getBlockState(pos).isAir() && mc.gameMode != null) {
                Direction side = bhr.getDirection();
                mc.gameMode.continueDestroyBlock(pos, side);
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
        } else if (mc.gameMode != null) {
            mc.gameMode.stopDestroyBlock();
        }
    }
}
