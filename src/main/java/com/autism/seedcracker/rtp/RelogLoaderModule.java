package com.autism.seedcracker.rtp;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import autismclient.util.AutismCompatManager;
import com.autism.seedcracker.SeedcrackerAddon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.item.Items;

/**
 * Relog Loader.
 *
 * Forces the server to resend chunks (the "relog loading" trick): digs down to a configurable
 * Y below the bedrock floor, disconnects and rejoins so chunks are resent, then boosts up and
 * flies around so ESP/base-finder modules can read the loaded region. This sees the bedrock /
 * deepslate chunk signatures that normal chunk loading hides, though not everywhere.
 *
 * Requires an elytra or a riptide trident for the fly phase, and Baritone for the dig phase.
 * One-shot: enable it to run a single load cycle; it disables itself when finished.
 */
public final class RelogLoaderModule extends Module {

    private enum Phase { IDLE, DIG, DISCONNECT_WAIT, RECONNECT_WAIT, FLY, DONE }

    private Phase phase = Phase.IDLE;
    private int phaseTicks = 0;
    private ServerData server;

    private final IntSetting digY = add(new IntSetting("dig-y", "Dig down to Y", -1, -60, 320, 1)
        .description("Y level to dig down to before relogging.")
        .group("Relog"));
    private final IntSetting disconnectWait = add(new IntSetting("disconnect-wait", "Disconnect wait (s)", 2, 0, 60, 1)
        .description("Seconds to stay disconnected before rejoining.")
        .group("Relog"));
    private final IntSetting reconnectWait = add(new IntSetting("reconnect-wait", "Rejoin wait (s)", 5, 1, 120, 1)
        .description("Seconds to wait after rejoining for chunks to load.")
        .group("Relog"));
    private final IntSetting flyDuration = add(new IntSetting("fly-duration", "Fly duration (s)", 20, 1, 600, 1)
        .description("How long to fly around loading chunks after rejoining.")
        .group("Relog"));
    private final BoolSetting requireTool = add(new BoolSetting("require-tool", "Require elytra/trident", true)
        .description("Abort the fly phase if you have no elytra or riptide trident.")
        .group("Relog"));

    public RelogLoaderModule() {
        super(SeedcrackerAddon.ID + ":relog-loader", "Relog Loader",
            "Digs down, relogs to resend chunks, then flies so ESP can read the region. One-shot.");
    }

    @Override
    public void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        server = mc.getCurrentServer();
        if (server == null || mc.getConnection() == null) {
            AutismClientMessaging.sendPrefixed("§cRelog Loader: you must be on a server.");
            setEnabledSilently(false);
            return;
        }
        if (requireTool.get() && !hasFlightTool(mc)) {
            AutismClientMessaging.sendPrefixed("§eRelog Loader: no elytra or riptide trident - the fly phase will be skipped.");
        }
        phase = Phase.DIG;
        phaseTicks = 0;
        AutismClientMessaging.sendPrefixed("§aRelog Loader: digging to Y=" + digY.get() + "...");
    }

    @Override
    public void onDisable() {
        stopBaritone();
        phase = Phase.IDLE;
        server = null;
    }

    private void stopBaritone() {
        try {
            if (AutismCompatManager.isBaritoneAvailable()) AutismCompatManager.stopBaritone(Minecraft.getInstance());
        } catch (Throwable ignored) {}
    }

    private static boolean hasFlightTool(Minecraft mc) {
        if (mc.player == null) return false;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var stack = inv.getItem(i);
            if (stack.is(Items.ELYTRA) || stack.is(Items.TRIDENT)) return true;
        }
        return false;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();

        switch (phase) {
            case IDLE, DONE -> {
            }
            case DIG -> {
                if (mc.player == null) return;
                if ((int) mc.player.getY() <= digY.get()) {
                    stopBaritone();
                    AutismClientMessaging.sendPrefixed("§7Relog Loader: at depth, relogging...");
                    if (RelogHelper.disconnect()) {
                        phase = Phase.DISCONNECT_WAIT;
                        phaseTicks = disconnectWait.get() * 20;
                    } else {
                        setEnabled(false);
                    }
                    return;
                }
                if (!AutismCompatManager.isBaritoneBusy()) {
                    if (!AutismCompatManager.isBaritoneAvailable()) {
                        AutismClientMessaging.sendPrefixed("§cRelog Loader: Baritone required to dig. Teleport falling instead is unsafe; aborting.");
                        setEnabled(false);
                        return;
                    }
                    AutismCompatManager.startBaritoneGoTo(mc, (int) mc.player.getX(), digY.get(), (int) mc.player.getZ());
                }
            }
            case DISCONNECT_WAIT -> {
                if (phaseTicks > 0) { phaseTicks--; return; }
                RelogHelper.reconnect(server);
                phase = Phase.RECONNECT_WAIT;
                phaseTicks = reconnectWait.get() * 20;
            }
            case RECONNECT_WAIT -> {
                // Wait while rejoining + chunks resend. Only count down once back in a world.
                if (mc.player == null || mc.level == null) return;
                if (phaseTicks > 0) { phaseTicks--; return; }
                if (requireTool.get() && !hasFlightTool(mc)) {
                    AutismClientMessaging.sendPrefixed("§eRelog Loader: chunks loaded. No flight tool, skipping fly phase.");
                    phase = Phase.DONE;
                    setEnabled(false);
                    return;
                }
                phase = Phase.FLY;
                phaseTicks = flyDuration.get() * 20;
                AutismClientMessaging.sendPrefixed("§7Relog Loader: flying to load chunks for ESP...");
            }
            case FLY -> {
                if (mc.player == null) return;
                // Boost straight up first, then drift so new chunks stream in.
                if (!AutismCompatManager.isBaritoneBusy()) {
                    int targetY = Math.min(mc.level.getMaxY() - 2, (int) mc.player.getY() + 40);
                    AutismCompatManager.startBaritoneGoTo(mc,
                        (int) mc.player.getX() + 32, targetY, (int) mc.player.getZ() + 32);
                }
                if (phaseTicks > 0) { phaseTicks--; return; }
                phase = Phase.DONE;
                AutismClientMessaging.sendPrefixed("§aRelog Loader: done.");
                setEnabled(false);
            }
        }
    }

    @Override
    public String info() {
        return switch (phase) {
            case IDLE -> "idle";
            case DIG -> "digging";
            case DISCONNECT_WAIT -> "leaving";
            case RECONNECT_WAIT -> "rejoining";
            case FLY -> "flying";
            case DONE -> "done";
        };
    }
}
