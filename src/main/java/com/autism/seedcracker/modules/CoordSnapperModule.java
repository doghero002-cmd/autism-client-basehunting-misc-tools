package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.EnumSetting;
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
    public enum Mode { CENTER, COPY_OWN, COPY_TARGET }
    private final EnumSetting<Mode> mode = add(new EnumSetting<>("mode", "Mode", Mode.CENTER, Mode.values()).group("General"));
    private final BoolSetting includeDimension = add(new BoolSetting("include-dimension", "Include dimension", true)
        .group("Clipboard").visibleWhen(() -> mode.get() != Mode.CENTER));

    private final BoolSetting snapY = add(new BoolSetting("snap-y", "Snap to floor", false)
        .description("Also snap you down to the top of the block below you.")
        .group("General"));
    private final BoolSetting stopMotion = add(new BoolSetting("stop-motion", "Stop motion", true)
        .description("Zero your velocity while snapping so you stay centered.")
        .group("General"));

    public CoordSnapperModule() {
        super(SeedcrackerAddon.ID + ":z-coord-snapper", "Coord Snapper",
            "Snaps your position to the center of the block you are standing in.");
    }

    @Override
    public boolean holdToActivate() {
        return mode.get() == Mode.CENTER;
    }

    @Override public void onEnable() {
        if (mode.get() == Mode.CENTER) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.level != null && mc.gui.screen() == null) {
            BlockPos pos = mode.get() == Mode.COPY_OWN ? mc.player.blockPosition()
                : mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                    && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK ? hit.getBlockPos() : null;
            if (pos != null && mc.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                String coordinates = pos.getX() + " " + pos.getY() + " " + pos.getZ();
                if (includeDimension.get()) coordinates += " (" + mc.level.dimension().identifier() + ")";
                mc.keyboardHandler.setClipboard(coordinates);
            }
        }
        setEnabledSilently(false);
    }

    @Override protected void onOptionValueChanged(String name) {
        if (name.equals("mode")) {
            centering.stopCentering();
            if (isEnabled()) setEnabled(false);
        }
    }

    private final com.autism.seedcracker.util.tunnel.SimpleSneakCentering centering =
        new com.autism.seedcracker.util.tunnel.SimpleSneakCentering();

    @Override
    public void tick() {
        if (mode.get() != Mode.CENTER) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // Legit centering: drive the player toward the block centre using movement keys (like a
        // real player nudging into place), never setPos/setDeltaMovement (those are teleports the
        // anti-cheat flags instantly).
        if (!centering.isActive()) centering.startCentering();
        centering.tick();
    }

    @Override
    public void onDisable() {
        centering.stopCentering();
    }

    @Override
    public String info() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return "";
        BlockPos block = mc.player.blockPosition();
        return block.getX() + " " + block.getY() + " " + block.getZ();
    }
}
