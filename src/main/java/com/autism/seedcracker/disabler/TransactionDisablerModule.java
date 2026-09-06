package com.autism.seedcracker.disabler;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.modules.Module;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundPongPacket;

/**
 * Transaction (Pong) Disabler.
 *
 * Cancels outgoing pong replies (the modern equivalent of the 1.8.9 confirm-transaction packet).
 * The server pings, the client never pongs, so the anti-cheat's transaction-based timing / reach
 * checks lose their reference clock.
 *
 * Port of the Rise "Transaction" disabler mode (1.8.9) to the AUTISM API (Mojang 26.2). The 1.8.9
 * C0FPacketConfirmTransaction maps to ServerboundPongPacket in the modern ping/pong protocol.
 * WARNING: some servers kick for missing pongs.
 */
public final class TransactionDisablerModule extends Module {

    public TransactionDisablerModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":transaction-disabler", "Transaction Disabler [Patched]", category,
            "Cancels outgoing pong (transaction) replies. WARNING: some servers kick for missing pongs.");
    }

    @Override
    public boolean onPacketSend(Packet<?> packet) {
        return packet instanceof ServerboundPongPacket;
    }
}
