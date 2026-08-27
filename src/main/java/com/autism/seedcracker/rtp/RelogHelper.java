package com.autism.seedcracker.rtp;

import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/**
 * Disconnect + reconnect helper used by the Relog Loader. Remembers the current server,
 * disconnects, waits, then reconnects so the server resends chunks.
 */
public final class RelogHelper {
    private static final Minecraft MC = Minecraft.getInstance();

    private RelogHelper() {}

    /** Disconnects from the server and drops to the multiplayer screen. Returns false if not on a server. */
    public static boolean disconnect() {
        if (MC.getConnection() == null) return false;
        try {
            // Leave the world cleanly and land on the multiplayer screen so reconnect has a stable parent.
            MC.disconnect(new JoinMultiplayerScreen(new TitleScreen()), false);
            return true;
        } catch (Throwable t) {
            // Fallback: raw connection disconnect.
            try {
                MC.getConnection().getConnection().disconnect(Component.literal("Relog"));
                return true;
            } catch (Throwable t2) {
                AutismClientMessaging.sendPrefixed("§cRelog disconnect failed: " + t2.getMessage());
                return false;
            }
        }
    }

    /** Reconnects to the given server from the multiplayer screen. */
    public static void reconnect(ServerData server) {
        if (server == null || server.ip == null || server.ip.isBlank()) return;
        try {
            ServerAddress address = ServerAddress.parseString(server.ip);
            // Always parent to a fresh multiplayer screen: the previous screen may be gone after the world closed.
            ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), MC, address, server, false, null);
        } catch (Throwable t) {
            AutismClientMessaging.sendPrefixed("§cRelog reconnect failed: " + t.getMessage());
        }
    }
}
