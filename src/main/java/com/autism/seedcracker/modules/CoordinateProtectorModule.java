package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;

/**
 * Coordinate Protector (MeteorPlus CoordinateProtector port, mixin-free variant).
 *
 * Stops coordinate leaks on stream/screenshots:
 *  - Forces the vanilla "reducedDebugInfo" option while enabled, which strips the XYZ / block /
 *    chunk / facing lines out of the F3 overlay entirely (restored on disable).
 *  - Optionally blocks chat messages you SEND that contain what looks like your own coordinates
 *    (accidental paste into public chat), with a chat warning instead.
 */
public final class CoordinateProtectorModule extends Module {

    private final BoolSetting hideF3 = add(new BoolSetting("hide-f3", "Hide F3 coordinates", true)
        .description("Force reduced debug info (strips XYZ from the F3 screen).").group("General"));
    private final BoolSetting blockChatLeak = add(new BoolSetting("block-chat-leak", "Block chat coord leaks", true)
        .description("Cancel outgoing chat messages containing your current coordinates.").group("General"));

    private Boolean savedReduced = null;

    public CoordinateProtectorModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":coord-protector", "Coord Protector", category,
            "Hides F3 coordinates and blocks accidental coordinate pastes into chat.");
    }

    @Override
    public void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        if (hideF3.get()) {
            savedReduced = mc.options.reducedDebugInfo().get();
            mc.options.reducedDebugInfo().set(true);
        }
    }

    @Override
    public void onDisable() {
        if (savedReduced != null) {
            Minecraft.getInstance().options.reducedDebugInfo().set(savedReduced);
            savedReduced = null;
        }
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public boolean onPacketSend(net.minecraft.network.protocol.Packet<?> packet) {
        if (!blockChatLeak.get()) return false;
        String text = null;
        if (packet instanceof net.minecraft.network.protocol.game.ServerboundChatPacket chat) {
            text = chat.message();
        }
        if (text == null) return false;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;

        // Leak check: message contains 2+ of our coordinates (as rounded integers).
        int x = (int) Math.floor(mc.player.getX());
        int y = (int) Math.floor(mc.player.getY());
        int z = (int) Math.floor(mc.player.getZ());
        int hits = 0;
        if (containsNumber(text, x)) hits++;
        if (containsNumber(text, y)) hits++;
        if (containsNumber(text, z)) hits++;
        if (hits < 2) return false;

        AutismClientMessaging.sendPrefixed("§c[CoordProtector] §fBlocked a chat message containing your coordinates!");
        return true; // cancel the send
    }

    private static boolean containsNumber(String text, int n) {
        // Word-boundary match so "100" doesn't match in "1000".
        return java.util.regex.Pattern.compile("(?<!\\d)" + java.util.regex.Pattern.quote(String.valueOf(n)) + "(?!\\d)")
            .matcher(text).find();
    }
}
