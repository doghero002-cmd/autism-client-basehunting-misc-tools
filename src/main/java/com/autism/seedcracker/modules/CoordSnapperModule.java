package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Coord Snapper.
 *
 * Snaps your position to the center of the block you are standing in. Bind it to a key; while the
 * key is held you are pinned to the center of your current block (horizontal centering, optional
 * vertical snap to the block floor). Release the key to move freely again. Useful for lining up
 * precisely on a block before building or triggering something position-sensitive.
 *
 * Clean-room port of the obfuscated Zelith "CoordSnapper" module, reworked as a local position
 * snapper (the original sent your coords to a webhook; this keeps the position logic client-side).
 */
public final class CoordSnapperModule extends Module {

    private final BoolSetting snapY = add(new BoolSetting("snap-y", "Snap to floor", false)
        .description("Also snap you down to the top of the block below you.")
        .group("General"));
    private final BoolSetting stopMotion = add(new BoolSetting("stop-motion", "Stop motion", true)
        .description("Zero your velocity while snapping so you stay centered.")
        .group("General"));

    public CoordSnapperModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-coord-snapper", "Coord Snapper", category,
            "Snaps your position to the center of the block you are standing in.");
    }

    @Override
    public boolean holdToActivate() {
        return true;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        BlockPos block = mc.player.blockPosition();
        double x = block.getX() + 0.5;
        double z = block.getZ() + 0.5;
        double y = snapY.get() ? block.getY() : mc.player.getY();

        mc.player.setPos(x, y, z);
        if (stopMotion.get()) {
            Vec3 motion = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(0.0, snapY.get() ? Math.min(0.0, motion.y) : motion.y, 0.0);
        }
    }

    @Override
    public String info() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return "";
        BlockPos block = mc.player.blockPosition();
        return block.getX() + " " + block.getY() + " " + block.getZ();
    }
}
