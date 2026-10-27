package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.rtp.RelogLoaderModule;
import com.autism.seedcracker.spectre.mechanics.AutoRelogConnectionControl;
import com.autism.seedcracker.spectre.mechanics.AutoRelogCycle;
import com.autism.seedcracker.spectre.mechanics.AutoRelogWaitingScreen;
import com.autism.seedcracker.util.ActionPacer;
import com.autism.seedcracker.util.RelogPersistence;

import autismclient.api.module.DoubleSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;

/**
 * Adapted from Spectre AutoRelogModule and LevelUtil (GPL-3.0-only), revision
 * d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da, by cody-raves and contributors.
 * The separate RelogLoaderModule's one-shot resend/HUD/ACTIVE contract is intentionally untouched.
 * Deviations: paced actions, identity-owned cancellation, finite teardown/connect deadlines and
 * cancellation of the native connection worker on disable. No timer thread or scheduled reconnect.
 */
public final class AutoRelogModule extends Module {
    private static final System.Logger LOGGER = System.getLogger(AutoRelogModule.class.getName());
    private final IntSetting yLevel = add(new IntSetting("y-level", "Y level", 0, -60, 16, 1)
        .description("After 40 ticks at least four blocks above this height, relog once below it.")
        .group("Relog"));
    private final DoubleSetting delay = add(new DoubleSetting("delay", "Reconnect delay (s)", 3.0, 1.0, 30.0, 0.5)
        .description("Wait after teardown before one reconnect attempt. Leave the server list to cancel; 3s or more is recommended.")
        .group("Relog"));

    private final AutoRelogCycle cycle = new AutoRelogCycle();
    private ClientLevel seenLevel;
    private LocalPlayer seenPlayer;
    private ClientLevel originLevel;
    private ClientPacketListener originConnection;
    private ServerData rejoinServer;
    private ServerAddress rejoinAddress;
    private AutoRelogWaitingScreen waitingScreen;
    private ConnectScreen connectingScreen;
    private AutoRelogConnectionControl connectionControl;
    private long generation;
    private boolean expectedLeave;
    private boolean startingConnection;

    public AutoRelogModule() {
        super(SeedcrackerAddon.ID + ":auto-relog", "Auto Relog",
            "Opt-in: relog once on a downward height crossing; cancel by leaving the waiting screen.");
    }

    @Override
    public void onEnable() {
        clearPending(true);
        // Fail closed if the parent has not registered the narrowly scoped cancellation mixin.
        if (!AutoRelogConnectionControl.class.isAssignableFrom(ConnectScreen.class)) {
            AutismClientMessaging.sendPrefixed("§c[Auto Relog] Required connection-cancellation hook is missing.");
            setEnabledSilently(false);
        }
    }

    @Override
    public void onDisable() {
        clearPending(true);
    }

    @Override
    public void onGameLeft() {
        seenLevel = null;
        seenPlayer = null;
        boolean ourLeave = startingConnection || (expectedLeave
            && (cycle.phase() == AutoRelogCycle.Phase.DISCONNECTING || cycle.phase() == AutoRelogCycle.Phase.WAITING));
        expectedLeave = false;
        if (!ourLeave) clearPending(true);
        if (RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void onGameJoin() {
        // Join may be announced before Minecraft.level is installed. The native connection,
        // not the old level pointer, determines whether this is our successful handoff.
        Minecraft mc = Minecraft.getInstance();
        boolean completedAttempt = connectionControl != null && mc.getConnection() != null
            && connectionControl.autoRelog$isConnection(mc.getConnection().getConnection());
        clearPending(!completedAttempt);
    }

    @Override
    public void tick() {
        if (!isEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (cycle.pending()) {
            tickPending(mc);
            return;
        }
        if (seenLevel != mc.level || seenPlayer != mc.player) {
            cycle.resetArming();
            seenLevel = mc.level;
            seenPlayer = mc.player;
        }
        ServerData server = mc.getCurrentServer();
        boolean eligible = mc.player != null && mc.level != null && mc.getConnection() != null
            && !mc.player.isDeadOrDying() && !mc.isLocalServer() && server != null && !server.isRealm()
            && !(mc.gui.screen() instanceof LevelLoadingScreen) && !RelogLoaderModule.ACTIVE
            && (mc.level.dimension() == Level.OVERWORLD
                || mc.level.dimensionTypeRegistration().is(BuiltinDimensionTypes.OVERWORLD));
        if (cycle.observe(eligible, mc.player == null ? Double.NaN : mc.player.getY(), yLevel.get(), AutoRelogModule::permitAction)) {
            beginRelog(mc, server);
        }
    }

    private void beginRelog(Minecraft mc, ServerData server) {
        if (server.ip == null || !ServerAddress.isValidAddress(server.ip)) {
            AutismClientMessaging.sendPrefixed("§c[Auto Relog] Invalid server address; no disconnect was sent.");
            return;
        }
        // Copy metadata before leaving; editing a server-list entry cannot redirect this attempt.
        rejoinServer = new ServerData(server.name, server.ip, server.type());
        rejoinServer.copyFrom(server);
        rejoinAddress = ServerAddress.parseString(server.ip);
        originConnection = mc.getConnection();
        originLevel = mc.level;
        long attempt = ++generation;
        waitingScreen = new AutoRelogWaitingScreen(new TitleScreen(), () -> {
            if (generation == attempt) clearPending(true);
        });
        cycle.begin(nowMs(), delay.get());
        expectedLeave = true;
        AutismClientMessaging.sendPrefixed("§7[Auto Relog] Relogging once. Cancel or leave the server list to stop.");
        try {
            mc.disconnect(waitingScreen, false);
        } catch (RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "Auto Relog disconnect failed", failure);
            clearPending(true);
        }
    }

    private void tickPending(Minecraft mc) {
        long now = nowMs();
        switch (cycle.phase()) {
            case DISCONNECTING -> {
                boolean sameOrigin = (mc.level == null || mc.level == originLevel)
                    && (mc.getConnection() == null || mc.getConnection() == originConnection);
                boolean disconnected = mc.level == null && mc.player == null && mc.getConnection() == null;
                cycle.observeTeardown(now, sameOrigin, disconnected, mc.gui.screen() == waitingScreen);
                if (cycle.phase() == AutoRelogCycle.Phase.WAITING) {
                    originConnection = null;
                    originLevel = null;
                    seenLevel = null;
                    seenPlayer = null;
                }
            }
            case WAITING -> {
                boolean ownsEmptyContext = mc.gui.screen() == waitingScreen && mc.level == null
                    && mc.player == null && mc.getConnection() == null && rejoinServer != null;
                if (cycle.reconnectDue(now, ownsEmptyContext, AutoRelogModule::permitAction)) startConnection(mc);
            }
            case CONNECTING -> {
                boolean ownsConnection = connectionControl != null && mc.getConnection() != null
                    && connectionControl.autoRelog$isConnection(mc.getConnection().getConnection());
                if (mc.level != null) {
                    clearPending(true);
                    return;
                }
                boolean ownsScreen = mc.gui.screen() == connectingScreen
                    || (mc.gui.screen() instanceof LevelLoadingScreen && ownsConnection);
                cycle.observeConnecting(now, connectionControl != null && !connectionControl.autoRelog$isAborted()
                    && ownsScreen && (mc.getConnection() == null || ownsConnection));
            }
            default -> { }
        }
        if (!cycle.pending()) {
            String reason = cycle.stopReason();
            if (!reason.isEmpty()) LOGGER.log(System.Logger.Level.INFO, "Auto Relog stopped: " + reason);
            clearPending(true);
        }
    }

    private void startConnection(Minecraft mc) {
        long attempt = generation;
        AutoRelogWaitingScreen parent = waitingScreen;
        parent.release();
        startingConnection = true;
        try {
            // Synchronous main-thread handoff: no runnable can fire after cancellation.
            ConnectScreen.startConnecting(parent, mc, rejoinAddress, rejoinServer, false, null);
            Screen screen = mc.gui.screen();
            if (!(screen instanceof ConnectScreen current) || !(screen instanceof AutoRelogConnectionControl control)) {
                LOGGER.log(System.Logger.Level.WARNING, "Auto Relog connection handoff did not produce an owned ConnectScreen");
                clearPending(true);
                return;
            }
            if (generation != attempt || !isEnabled()) {
                control.autoRelog$cancelOwnedConnection();
                if (mc.gui.screen() == screen && mc.level == null) mc.gui.setScreen(parent);
                return;
            }
            connectingScreen = current;
            connectionControl = control;
        } catch (RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "Auto Relog connection failed", failure);
            clearPending(true);
        } finally {
            startingConnection = false;
            expectedLeave = false;
        }
    }

    private void clearPending(boolean cancelConnection) {
        Minecraft mc = Minecraft.getInstance();
        ConnectScreen oldScreen = connectingScreen;
        AutoRelogConnectionControl oldControl = connectionControl;
        AutoRelogWaitingScreen oldWaiting = waitingScreen;
        generation++;
        if (oldWaiting != null) oldWaiting.release();
        connectingScreen = null;
        connectionControl = null;
        waitingScreen = null;
        rejoinServer = null;
        rejoinAddress = null;
        originConnection = null;
        originLevel = null;
        seenLevel = null;
        seenPlayer = null;
        expectedLeave = false;
        cycle.cancel();
        if (cancelConnection && oldControl != null) {
            boolean alreadyJoined = mc.level != null && mc.getConnection() != null
                && oldControl.autoRelog$isConnection(mc.getConnection().getConnection());
            if (!alreadyJoined) {
                oldControl.autoRelog$cancelOwnedConnection();
                // Never replace a screen/world now owned by the user or another connection.
                if (mc.gui.screen() == oldScreen && mc.level == null) {
                    mc.gui.setScreen(new JoinMultiplayerScreen(new TitleScreen()));
                }
            }
        }
    }

    private static boolean permitAction() { return !ActionPacer.holding() && ActionPacer.tryAction(); }
    private static long nowMs() { return System.nanoTime() / 1_000_000L; }

    @Override
    public String info() {
        return cycle.phase() == AutoRelogCycle.Phase.WATCHING
            ? "armed " + cycle.aboveTicks() + "/40" : cycle.phase().name().toLowerCase(java.util.Locale.ROOT);
    }
}
