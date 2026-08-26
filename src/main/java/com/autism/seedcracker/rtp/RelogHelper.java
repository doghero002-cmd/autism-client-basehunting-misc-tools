package com.autism.seedcracker.rtp;

import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
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

    /** Saves the current server and disconnects. Returns false if not on a server. */
    public static boolean disconnect() {
        if (MC.getConnection() == null) return false;
        try {
            MC.getConnection().getConnection().disconnect(Component.literal("Relog"));
            return true;
        } catch (Throwable t) {
            AutismClientMessaging.sendPrefixed("§cRelog disconnect failed: " + t.getMessage());
            return false;
        }
    }

    /** Reconnects to the given server. */
    public static void reconnect(ServerData server) {
        if (server == null || server.ip == null || server.ip.isBlank()) return;
        try {
            ServerAddress address = ServerAddress.parseString(server.ip);
            Screen parent = MC.gui.screen() == null ? new TitleScreen() : MC.gui.screen();
            ConnectScreen.startConnecting(parent, MC, address, server, false, null);
        } catch (Throwable t) {
            AutismClientMessaging.sendPrefixed("§cRelog reconnect failed: " + t.getMessage());
        }
    }
}
