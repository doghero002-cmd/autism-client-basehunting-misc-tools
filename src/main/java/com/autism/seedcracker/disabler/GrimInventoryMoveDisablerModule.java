package com.autism.seedcracker.disabler;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;

/**
 * Grim Inventory Move Disabler.
 *
 * Lets you move while in an inventory without Grim flagging "inventory move". It tracks the
 * sprint state the server believes you're in; when you click a container while sprinting it
 * briefly stops sprint, forwards the click, then re-starts sprint - so the click never lines up
 * with a sprinting state.
 *
 * Port of the Rise "GrimInventoryMove" disabler mode (1.8.9) to the AUTISM API (Mojang 26.2). The
 * 1.8.9 C0BPacketEntityAction maps to ServerboundPlayerCommandPacket and C0EPacketClickWindow to
 * ServerboundContainerClickPacket.
 */
public final class GrimInventoryMoveDisablerModule extends Module {

    private boolean resending = false;
    private boolean sprinting = false;
    private boolean sprintStateKnown = false;

    public GrimInventoryMoveDisablerModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":grim-inventory-move-disabler", "Grim Inventory Move [Patched]", category,
            "Move in inventories without Grim flags by spoofing sprint state around clicks.");
    }

    @Override
    public void onDisable() {
        resending = false;
        sprinting = false;
        sprintStateKnown = false;
    }

    @Override
    public boolean onPacketSend(Packet<?> packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return false;

        if (packet instanceof ServerboundPlayerCommandPacket cmd) {
            if (cmd.getAction() == ServerboundPlayerCommandPacket.Action.START_SPRINTING) {
                if (sprintStateKnown && sprinting) return true; // duplicate start - cancel
                sprinting = true;
                sprintStateKnown = true;
            } else if (cmd.getAction() == ServerboundPlayerCommandPacket.Action.STOP_SPRINTING) {
                if (sprintStateKnown && !sprinting) return true; // duplicate stop - cancel
                sprinting = false;
                sprintStateKnown = true;
            }
            return false;
        }

        if (resending) return false;

        if (packet instanceof ServerboundContainerClickPacket) {
            if (!sprintStateKnown || !sprinting) return false;

            // Forward the click, but first drop sprint so it doesn't coincide with sprinting.
            mc.getConnection().send(new ServerboundPlayerCommandPacket(
                mc.player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
            sprinting = false;
            sprintStateKnown = true;

            resending = true;
            mc.getConnection().send(packet);
            resending = false;

            if (mc.player.isSprinting() && !sprinting) {
                mc.getConnection().send(new ServerboundPlayerCommandPacket(
                    mc.player, ServerboundPlayerCommandPacket.Action.START_SPRINTING));
                sprinting = true;
                sprintStateKnown = true;
            }
            return true; // cancel the original click (we re-sent it ourselves)
        }
        return false;
    }
}
