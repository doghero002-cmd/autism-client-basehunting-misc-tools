package com.autism.seedcracker.rtp;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.server.level.ClientInformation;

/**
 * Relog Loader.
 *
 * Forces the server to resend all chunks so ESP/base-finder modules can re-read the region
 * (anti-xray re-hides block data over time; a resend restores it). No movement automation -
 * position yourself, toggle it, done. Two modes:
 *
 *  - RELOG: disconnect, wait, auto-rejoin. The classic full resend.
 *  - PACKET_RESEND: shrink the reported client view distance to 2 and restore it a moment
 *    later - the server resends the whole render bubble WITHOUT a disconnect (Water client
 *    SpawnerDetect trick). Faster and no leave/join message, at the cost of two settings packets.
 *
 * Optional wait-for-Y arms the relog until you reach a target depth yourself.
 * One-shot: disables itself when finished.
 */
public final class RelogLoaderModule extends Module {

    public enum Mode { RELOG, PACKET_RESEND }

    private enum Phase { IDLE, WAIT_Y, DISCONNECT_WAIT, RECONNECT_WAIT, RESEND_RESTORE, DONE }

    private Phase phase = Phase.IDLE;
    private int phaseTicks = 0;
    private int teardownTicks = 0;
    private ServerData server;

    private final EnumSetting<Mode> mode = add(new EnumSetting<>("mode", "Mode", Mode.RELOG, Mode.values())
        .description("RELOG = disconnect + auto-rejoin (full chunk resend). PACKET_RESEND = view-distance packet trick: chunks resent with NO disconnect (quieter, no leave/join message).")
        .group("Relog"));
    private final BoolSetting waitForY = add(new BoolSetting("wait-for-y", "Wait for Y level", false)
        .description("Arm and wait until YOU reach the target Y (walk/dig there yourself), then trigger.")
        .group("Relog"));
    private final IntSetting targetY = add(new IntSetting("target-y", "Target Y", -1, -60, 320, 1)
        .description("Y level to wait for before triggering.")
        .group("Relog")
        .visibleWhen(() -> waitForY.get()));
    private final IntSetting disconnectWait = add(new IntSetting("disconnect-wait", "Disconnect wait (s)", 5, 3, 60, 1)
        .description("RELOG: seconds to stay disconnected before rejoining. Under ~3s the server/proxy hasn't deregistered your session yet and the rejoin bounces with 'already online'.")
        .group("Relog")
        .visibleWhen(() -> mode.get() == Mode.RELOG));
    private final IntSetting reconnectWait = add(new IntSetting("reconnect-wait", "Rejoin wait (s)", 5, 1, 120, 1)
        .description("RELOG: seconds to wait after rejoining for chunks to load before finishing.")
        .group("Relog")
        .visibleWhen(() -> mode.get() == Mode.RELOG));
    private final IntSetting resendRestoreTicks = add(new IntSetting("resend-restore-ticks", "Restore after (ticks)", 10, 2, 100, 1)
        .description("PACKET_RESEND: ticks before the real view distance is restored (triggering the resend).")
        .group("Relog")
        .visibleWhen(() -> mode.get() == Mode.PACKET_RESEND));

    public RelogLoaderModule() {
        super(SeedcrackerAddon.ID + ":relog-loader", "Relog Loader",
            "Makes the server resend all chunks (relog or view-distance packet trick) so ESP re-reads the region. One-shot.");
    }

    /** True while the module is enabled (drives the on-screen warning HUD). */
    public static volatile boolean ACTIVE = false;

    @Override
    public void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        server = mc.getCurrentServer();
        if (server == null || mc.getConnection() == null) {
            AutismClientMessaging.sendPrefixed("§cRelog Loader: you must be on a server.");
            setEnabledSilently(false);
            return;
        }
        phaseTicks = 0;
        ACTIVE = true;
        if (waitForY.get() && mc.player != null && (int) mc.player.getY() > targetY.get()) {
            phase = Phase.WAIT_Y;
            AutismClientMessaging.sendPrefixed("§aRelog Loader: armed - get to Y=" + targetY.get() + " and it triggers.");
        } else {
            trigger(mc);
        }
    }

    @Override
    public void onDisable() {
        // If we shrank the view distance and got toggled off before restoring, restore now.
        if (phase == Phase.RESEND_RESTORE) sendViewDistance(Minecraft.getInstance(), realViewDistance());
        phase = Phase.IDLE;
        server = null;
        ACTIVE = false;
    }

    private void trigger(Minecraft mc) {
        if (mode.get() == Mode.PACKET_RESEND) {
            // Shrink the reported view distance; restoring it makes the server resend the bubble.
            sendViewDistance(mc, 2);
            phase = Phase.RESEND_RESTORE;
            phaseTicks = resendRestoreTicks.get();
            AutismClientMessaging.sendPrefixed("§7Relog Loader: requesting chunk resend (no disconnect)...");
        } else {
            AutismClientMessaging.sendPrefixed("§7Relog Loader: relogging...");
            if (RelogHelper.disconnect()) {
                phase = Phase.DISCONNECT_WAIT;
                // Clamp in code too: saved configs from older versions may carry 0-2s.
                phaseTicks = Math.max(disconnectWait.get(), 3) * 20;
                teardownTicks = 0;
            } else {
                setEnabledSilently(false);
            }
        }
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();

        switch (phase) {
            case IDLE, DONE -> {
            }
            case WAIT_Y -> {
                if (mc.player == null) return;
                if ((int) mc.player.getY() <= targetY.get()) trigger(mc);
            }
            case DISCONNECT_WAIT -> {
                // The wait only starts once the connection is actually torn down: counting from
                // the disconnect CALL meant rejoining while the server/proxy still had the old
                // session live - it bounced us with "you are already connected".
                if (mc.getConnection() != null || mc.level != null) {
                    if (++teardownTicks > 200) { // 10s: disconnect never completed, bail out
                        AutismClientMessaging.sendPrefixed("§cRelog Loader: disconnect never completed - aborting.");
                        phase = Phase.DONE;
                        setEnabledSilently(false);
                    }
                    return;
                }
                if (phaseTicks > 0) { phaseTicks--; return; }
                RelogHelper.reconnect(server);
                phase = Phase.RECONNECT_WAIT;
                phaseTicks = reconnectWait.get() * 20;
            }
            case RECONNECT_WAIT -> {
                // Wait while rejoining + chunks resend. Only count down once back in a world.
                if (mc.player == null || mc.level == null) return;
                if (phaseTicks > 0) { phaseTicks--; return; }
                AutismClientMessaging.sendPrefixed("§aRelog Loader: relogged, chunks resent. Done.");
                phase = Phase.DONE;
                setEnabled(false);
            }
            case RESEND_RESTORE -> {
                if (mc.player == null || mc.getConnection() == null) { setEnabledSilently(false); return; }
                if (phaseTicks > 0) { phaseTicks--; return; }
                sendViewDistance(mc, realViewDistance());
                AutismClientMessaging.sendPrefixed("§aRelog Loader: chunk resend requested. Done.");
                phase = Phase.DONE;
                setEnabled(false);
            }
        }
    }

    private static int realViewDistance() {
        Minecraft mc = Minecraft.getInstance();
        return mc.options != null ? mc.options.renderDistance().get() : 12;
    }

    /** Send a ClientInformation packet with only the view distance changed. */
    private static void sendViewDistance(Minecraft mc, int distance) {
        if (mc.getConnection() == null || mc.options == null || mc.player == null) return;
        try {
            ClientInformation info = new ClientInformation(
                mc.options.languageCode,
                distance,
                mc.options.chatVisibility().get(),
                mc.options.chatColors().get(),
                127, // all model parts shown
                mc.options.mainHand().get(),
                false,
                mc.options.allowServerListing().get(),
                mc.options.particles().get());
            mc.getConnection().send(new ServerboundClientInformationPacket(info));
        } catch (Throwable t) {
            AutismClientMessaging.sendPrefixed("§cRelog Loader: view-distance packet failed (" + t.getClass().getSimpleName() + ").");
        }
    }

    @Override
    public String info() {
        return switch (phase) {
            case IDLE -> "idle";
            case WAIT_Y -> "waiting Y<=" + targetY.get();
            case DISCONNECT_WAIT -> "leaving";
            case RECONNECT_WAIT -> "rejoining";
            case RESEND_RESTORE -> "resend in " + phaseTicks + "t";
            case DONE -> "done";
        };
    }
}
