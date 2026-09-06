package com.autism.seedcracker.disabler;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.modules.Module;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;

/**
 * Cancel KeepAlive Disabler.
 *
 * Cancels every outgoing keep-alive reply. Some anti-cheats treat the resulting missing keep-alives
 * as "vanilla lag" and stop timing the player out, which can suppress certain checks.
 *
 * Port of the Rise "CancelKeepAlive" disabler mode (1.8.9) to the AUTISM API (Mojang 26.2). The
 * 1.8.9 keep-alive packet maps to ServerboundKeepAlivePacket (common protocol).
 * WARNING: many servers will just kick you for not replying.
 */
public final class CancelKeepAliveDisablerModule extends Module {

    public CancelKeepAliveDisablerModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":cancel-keepalive-disabler", "Cancel KeepAlive [Patched]", category,
            "Cancels outgoing keep-alive packets. WARNING: many servers kick for missing keep-alives.");
    }

    @Override
    public boolean onPacketSend(Packet<?> packet) {
        return packet instanceof ServerboundKeepAlivePacket;
    }
}
