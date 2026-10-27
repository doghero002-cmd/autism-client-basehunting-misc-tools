package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.spectre.mechanics.HomeMetaSequence;
import com.autism.seedcracker.util.ActionPacer;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Existing Home Setter ID, SET mode, command templates and silent-chat API are retained.
 * META command order adapted from Spectre HomeMetaModule (GPL-3.0-only), revision
 * d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da, by cody-raves and contributors.
 * Intentional changes: client-tick scheduling, paced commands, bounded confirmation/teleport waits,
 * and cancellation on connection/world/player changes. No external logging is performed.
 */
public final class HomeSetterModule extends Module {
    public enum Mode { SET, META }

    private static final System.Logger LOGGER = System.getLogger(HomeSetterModule.class.getName());
    private final EnumSetting<Mode> mode = add(new EnumSetting<>("mode", "Mode", Mode.SET, Mode.values())
        .description("SET = delete/delay/set. META = delete, set and confirm, RTP, then return home.")
        .group("General"));
    private final IntSetting slot = add(new IntSetting("slot", "Home slot", 1, 1, 10, 1)
        .description("Which home slot number to set.").group("General"));
    private final BoolSetting deleteFirst = add(new BoolSetting("delete-first", "Delete old home first", true)
        .description("SET: run the delete command before setting, to overwrite an existing home.")
        .group("General").visibleWhen(() -> mode.get() == Mode.SET));
    private final StringSetting setCommand = add(new StringSetting("set-command", "Set command", "sethome %slot%")
        .description("Command used to set the home. %slot% is replaced with the slot number.")
        .group("General"));
    private final StringSetting deleteCommand = add(new StringSetting("delete-command", "Delete command", "delhome %slot%")
        .description("Command used to delete the home. %slot% is replaced with the slot number.")
        .group("General"));
    private final IntSetting delayMs = add(new IntSetting("delay-ms", "Delay (ms)", 750, 100, 5000, 50)
        .description("SET: milliseconds to wait between the delete and set commands.")
        .group("General").visibleWhen(() -> mode.get() == Mode.SET));
    private final IntSetting metaDelayTicks = add(new IntSetting("meta-delay", "Step delay (ticks)", 20, 5, 100, 1)
        .description("Minimum delay between META steps (20 ticks = 1s).")
        .group("Meta").visibleWhen(() -> mode.get() == Mode.META));
    private final StringSetting metaRtpCommand = add(new StringSetting("meta-rtp", "RTP command", "rtp")
        .description("Command used to teleport away after the home-set confirmation.")
        .group("Meta").visibleWhen(() -> mode.get() == Mode.META));
    private final IntSetting metaRtpTimeout = add(new IntSetting("meta-rtp-timeout", "RTP timeout (s)", 15, 5, 60, 1)
        .description("Maximum wait for each RTP/return-home teleport. Timeout aborts the sequence.")
        .group("Meta").visibleWhen(() -> mode.get() == Mode.META));
    private final BoolSetting silent = add(new BoolSetting("silent", "Silent (hide confirmations)", false)
        .description("Hide matching system-chat lines briefly around this module's commands.")
        .group("Silent"));
    private final StringSetting silentPatterns = add(new StringSetting("silent-patterns", "Suppress patterns", "home,sethome,delhome")
        .description("Comma-separated case-insensitive substrings hidden during the bounded window.")
        .group("Silent").visibleWhen(() -> silent.get()));
    // Retain the serialized legacy key/value without exposing a nonfunctional network control.
    private final StringSetting webhook = add(new StringSetting("webhook", "Legacy webhook (not sent)", "")
        .description("Retained for profile compatibility only. No external transmission is performed.")
        .group("Silent").visibleWhen(() -> false));

    private static volatile HomeSetterModule active;
    private static volatile HomeSetterModule suppressor;
    private volatile long suppressUntilMs;
    private volatile String[] suppressPatterns = new String[0];
    private volatile ClientPacketListener suppressionConnection;

    private record FeedbackScope(long run, long step) {}
    private record Feedback(FeedbackScope scope, String line, boolean teleport) {}
    private final ArrayBlockingQueue<Feedback> feedback = new ArrayBlockingQueue<>(32);
    private volatile FeedbackScope feedbackScope;
    private volatile FeedbackScope overflowScope;
    private long generation;
    private final HomeMetaSequence sequence = new HomeMetaSequence();
    private ClientPacketListener connection;
    private ClientLevel level;
    private LocalPlayer player;
    private Vec3 homePosition;
    private Vec3 rtpPosition;
    private Mode runMode;
    private int homeSlot;
    private boolean preserveSuppressionOnDisable;

    public HomeSetterModule() {
        super(SeedcrackerAddon.ID + ":z-home-setter", "Home Setter",
            "One-shot: sets a home, or runs the confirmed home/RTP/return sequence.");
    }

    /** Existing HomeSetterChatMixin remains unchanged; feed confirmation before hiding any line. */
    public static boolean shouldSuppress(Component message) {
        onChatMessage(message);
        HomeSetterModule owner = suppressor;
        if (owner == null || message == null) return false;
        owner.expireSuppression(Minecraft.getInstance());
        if (suppressor != owner) return false;
        String text = message.getString().toLowerCase(Locale.ROOT);
        for (String pattern : owner.suppressPatterns) if (text.contains(pattern)) return true;
        return false;
    }

    /** Public chat bridge; only immutable feedback is queued, never game work on a network thread. */
    public static void onChatMessage(Component message) {
        if (message != null) onChatMessage(message.getString());
    }

    public static void onChatMessage(String message) {
        HomeSetterModule owner = active;
        if (owner != null && message != null && message.length() <= 4096) owner.enqueue(message, false);
    }

    @Override
    public void onEnable() {
        stopRun(true);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null || mc.player.isDeadOrDying()) {
            AutismClientMessaging.sendPrefixed("§c[Home Setter] Join a world first.");
            setEnabledSilently(false);
            return;
        }
        connection = mc.getConnection();
        level = mc.level;
        player = mc.player;
        runMode = mode.get();
        homeSlot = slot.get();
        try {
            long now = nowMs();
            if (runMode == Mode.META) {
                sequence.startMeta(homeSlot, metaDelayTicks.get() * 50L, metaRtpTimeout.get() * 1000L,
                    setCommand.get(), deleteCommand.get(), metaRtpCommand.get(), now);
            } else {
                sequence.startSet(homeSlot, deleteFirst.get(), delayMs.get(), setCommand.get(), deleteCommand.get(), now);
            }
        } catch (IllegalArgumentException failure) {
            AutismClientMessaging.sendPrefixed("§c[Home Setter] " + failure.getMessage());
            setEnabledSilently(false);
            return;
        }
        active = this;
        syncFeedbackScope();
        if (!silent.get()) AutismClientMessaging.sendPrefixed("§7[Home Setter] Starting " + runMode + " for home " + homeSlot + ".");
    }

    @Override
    public void onDisable() {
        stopRun(!preserveSuppressionOnDisable);
        preserveSuppressionOnDisable = false;
    }

    @Override
    public void onGameLeft() {
        // This is a one-shot transaction, not resumable work: persistence must not restart it.
        stopRun(true);
        if (isEnabled()) setEnabledSilently(false);
    }

    @Override
    public void onGameJoin() {
        if (sequence.running()) finish(false, "session changed");
        clearSuppression();
    }

    @Override
    public boolean ticksWhenDisabled() { return true; }

    @Override
    public boolean hasDisabledTickWork() { return suppressor == this; }

    @Override
    public boolean onPacketReceive(Packet<?> packet) {
        if (packet instanceof ClientboundSystemChatPacket chat && !chat.overlay()) onChatMessage(chat.content());
        else if (packet instanceof ClientboundPlayerPositionPacket) enqueue(null, true);
        return false;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        expireSuppression(mc);
        if (!isEnabled() || !sequence.running()) return;
        if (mc.getConnection() != connection || mc.level != level || mc.player != player
            || mc.player == null || mc.player.isDeadOrDying()) {
            finish(false, "world, player or connection changed");
            return;
        }
        long now = nowMs();
        if (overflowScope != null && overflowScope == feedbackScope) {
            finish(false, "too much pending server feedback");
            return;
        }
        for (int i = 0; i < 32; i++) {
            Feedback event = feedback.poll();
            if (event == null) break;
            if (event.scope() != feedbackScope) continue;
            if (event.teleport()) sequence.onServerTeleport();
            else sequence.onChatMessage(event.line(), now);
            syncFeedbackScope();
        }

        Vec3 position = mc.player.position();
        String command = sequence.nextCommand(now,
            rtpPosition == null ? Double.NaN : position.distanceToSqr(rtpPosition),
            homePosition == null ? Double.NaN : position.distanceToSqr(homePosition));
        syncFeedbackScope();
        if (sequence.step() == HomeMetaSequence.Step.FAILED) {
            finish(false, sequence.failure());
            return;
        }
        if (sequence.step() == HomeMetaSequence.Step.COMPLETE) {
            finish(true, "return-home teleport confirmed");
            return;
        }
        if (command == null || ActionPacer.holding() || !ActionPacer.tryAction()) return;

        HomeMetaSequence.Step sentStep = sequence.step();
        if (sentStep == HomeMetaSequence.Step.SET) homePosition = position;
        if (sentStep == HomeMetaSequence.Step.RTP) rtpPosition = position;
        sequence.commandSent(now);
        syncFeedbackScope();
        if (silent.get()) armSuppression(connection);
        try {
            connection.sendCommand(command);
        } catch (RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "Home Setter command failed at " + sentStep, failure);
            finish(false, "command send failed (not retried)");
            return;
        }
        if (sequence.step() == HomeMetaSequence.Step.COMPLETE) finish(true, "home " + homeSlot + " set command sent");
    }

    private void enqueue(String line, boolean teleport) {
        FeedbackScope scope = feedbackScope;
        if (scope != null && !feedback.offer(new Feedback(scope, line, teleport))) overflowScope = scope;
    }

    private void syncFeedbackScope() {
        FeedbackScope current = feedbackScope;
        if (current == null || current.step() != sequence.version()) {
            feedbackScope = new FeedbackScope(generation, sequence.version());
        }
    }

    private void finish(boolean success, String detail) {
        if (!success || !silent.get()) {
            AutismClientMessaging.sendPrefixed((success ? "§a" : "§c") + "[Home Setter] " + detail + ".");
        }
        preserveSuppressionOnDisable = success && silent.get();
        setEnabledSilently(false);
    }

    private void stopRun(boolean clearSilentWindow) {
        generation++;
        feedbackScope = null;
        overflowScope = null;
        feedback.clear();
        if (active == this) active = null;
        sequence.cancel();
        connection = null;
        level = null;
        player = null;
        homePosition = rtpPosition = null;
        if (clearSilentWindow) clearSuppression();
    }

    private void armSuppression(ClientPacketListener currentConnection) {
        String raw = silentPatterns.get();
        suppressPatterns = raw == null ? new String[0] : java.util.Arrays.stream(raw.split(",", 33))
            .limit(32).map(s -> s.trim().toLowerCase(Locale.ROOT))
            .filter(s -> !s.isEmpty() && s.length() <= 128).toArray(String[]::new);
        suppressionConnection = currentConnection;
        suppressUntilMs = System.currentTimeMillis() + 5_000;
        suppressor = this;
    }

    private void expireSuppression(Minecraft mc) {
        if (suppressor == this && (System.currentTimeMillis() >= suppressUntilMs
            || mc.getConnection() != suppressionConnection)) clearSuppression();
    }

    private void clearSuppression() {
        if (suppressor == this) suppressor = null;
        suppressUntilMs = 0;
        suppressPatterns = new String[0];
        suppressionConnection = null;
    }

    private static long nowMs() { return System.nanoTime() / 1_000_000L; }

    @Override
    public String info() { return sequence.step().name().toLowerCase(Locale.ROOT); }
}
