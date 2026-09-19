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
    private final BoolSetting safeMine = add(new BoolSetting("safe-mine", "Safe mine (lava guard)", true)
        .description("Don't break a block that has lava adjacent to it (MeteorPlus SafeMine) - stops mining into lava and dying.")
        .group("General"));
    private final BoolSetting antiBreak = add(new BoolSetting("anti-break", "Anti-break tool guard", true)
        .description("Stop mining before the held tool shatters (Krypton AutoTool anti-break).")
        .group("General"));
    private final IntSetting antiBreakPercent = add(new IntSetting("anti-break-percent", "Anti-break at %", 5, 1, 50, 1)
        .description("Stop when the tool's remaining durability falls below this % of max.")
        .group("General").visibleWhen(() -> antiBreak.get()));

    public AutoMineModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":auto-mine", "Auto Mine", category,
            "Automatically mines whatever block your crosshair is on.");
    }

    @Override
    public void onEnable() {
        // Seed the view lock from the player's current view so enabling doesn't head-flick to (0,0).
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && lockView.get()) {
            yaw.set((int) Math.round(mc.player.getYRot()));
            pitch.set((int) Math.round(mc.player.getXRot()));
        }
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (mc.gui.screen() != null) return;
        if (com.autism.seedcracker.util.ContainerMutex.containerBusy(mc)) { // no break packets near a container
            if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
            return;
        }

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
            if (safeMine.get() && lavaAdjacent(mc, pos)) {
                if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
                return;
            }
            // Tool durability guard (Krypton AutoTool anti-break): swap off / stop before the
            // pick shatters. Preserves expensive DonutSMP picks.
            if (antiBreak.get() && toolBreakingSoon(mc)) {
                if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
                return;
            }
            // Break-desync re-sync (nyx AutoTunnelUtil): if the server thinks we're breaking a
            // DIFFERENT block than the crosshair target (lag rubber-band), release + re-press to
            // re-sync instead of hammering a stale break.
            if (desyncResync(mc, pos)) return;
            if (!mc.level.getBlockState(pos).isAir() && mc.gameMode != null) {
                Direction side = bhr.getDirection();
                mc.gameMode.continueDestroyBlock(pos, side);
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
        } else if (mc.gameMode != null) {
            mc.gameMode.stopDestroyBlock();
        }
    }

    private int desyncCooldown = 0;

    /**
     * True while handling a break-desync (released the stale break and backed off). Returns false
     * when the server's current-breaking block matches the target (in sync) or nothing is breaking.
     */
    private boolean desyncResync(Minecraft mc, BlockPos target) {
        if (mc.gameMode == null) return false;
        if (desyncCooldown > 0) { desyncCooldown--; return true; }
        try {
            autismclient.mixin.accessor.AutismMultiPlayerGameModeAccessor acc =
                (autismclient.mixin.accessor.AutismMultiPlayerGameModeAccessor) mc.gameMode;
            if (!acc.autism$isDestroying()) return false;
            BlockPos breaking = acc.autism$getDestroyBlockPos();
            if (breaking == null || breaking.equals(target)) return false;
            if (mc.level.getBlockState(breaking).isAir()) return false;
            // Desync: server is mid-break on a different block. Release + back off 1-3 ticks.
            mc.gameMode.stopDestroyBlock();
            mc.options.keyAttack.setDown(false);
            desyncCooldown = 1 + (int) (Math.random() * 3);
            FlagDetectorModule.report("BREAK_DESYNC", "AutoMine",
                "server breaking " + breaking + " but crosshair on " + target);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** True if the held tool is within anti-break % of shattering. */
    private boolean toolBreakingSoon(Minecraft mc) {
        net.minecraft.world.item.ItemStack s = mc.player.getMainHandItem();
        if (s.isEmpty() || s.getMaxDamage() <= 0) return false;
        int remaining = s.getMaxDamage() - s.getDamageValue();
        return remaining < s.getMaxDamage() * antiBreakPercent.get() / 100;
    }

    /** True if any of the 6 blocks adjacent to pos is lava (fluid-state: catches flowing lava too). */
    private static boolean lavaAdjacent(Minecraft mc, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (mc.level.getBlockState(pos.relative(d)).getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) return true;
        }
        return false;
    }

    /** Status line shown next to the module in the menu: the block being mined + its distance. */
    @Override
    public String info() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        HitResult target = mc.hitResult;
        if (target != null && target.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = ((BlockHitResult) target).getBlockPos();
            double dist = Math.sqrt(mc.player.getEyePosition().distanceToSqr(
                net.minecraft.world.phys.Vec3.atCenterOf(pos)));
            String name = mc.level.getBlockState(pos).getBlock().getName().getString();
            return name + " (" + String.format(java.util.Locale.ROOT, "%.1f", dist) + "m)";
        }
        return "no target";
    }
}
