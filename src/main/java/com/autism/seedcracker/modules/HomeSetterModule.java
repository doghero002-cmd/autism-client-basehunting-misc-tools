package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.phys.Vec3;

/**
 * Home Setter.
 *
 * One-shot home tool with two modes:
 *  - SET:  delete the configured home slot, wait a beat, set it at your position, auto-disable.
 *          (Clean-room port of the obfuscated Zelith "HomeSetter".)
 *  - META: /sethome (twice), /rtp away, wait for the teleport, /home back, auto-disable - the
 *          Anubis HomeMeta stash/escape sequence, merged in so it isn't a separate menu entry.
 */
public final class HomeSetterModule extends Module {

    public enum Mode { SET, META }

    private final EnumSetting<Mode> mode = add(new EnumSetting<>("mode", "Mode", Mode.SET, Mode.values())
        .description("SET = set the home slot at your position. META = sethome, /rtp away, then /home back (stash/escape sequence).")
        .group("General"));
    private final IntSetting slot = add(new IntSetting("slot", "Home slot", 1, 1, 10, 1)
        .description("Which home slot number to set.")
        .group("General"));
    private final BoolSetting deleteFirst = add(new BoolSetting("delete-first", "Delete old home first", true)
        .description("Run the delete command before setting, to overwrite an existing home.")
        .group("General").visibleWhen(() -> mode.get() == Mode.SET));
    private final StringSetting setCommand = add(new StringSetting("set-command", "Set command", "sethome %slot%")
        .description("Command used to set the home. %slot% is replaced with the slot number.")
        .group("General").visibleWhen(() -> mode.get() == Mode.SET));
    private final StringSetting deleteCommand = add(new StringSetting("delete-command", "Delete command", "delhome %slot%")
        .description("Command used to delete the home. %slot% is replaced with the slot number.")
        .group("General").visibleWhen(() -> mode.get() == Mode.SET));
    private final IntSetting delayMs = add(new IntSetting("delay-ms", "Delay (ms)", 750, 100, 5000, 50)
        .description("Milliseconds to wait between the delete and set commands.")
        .group("General").visibleWhen(() -> mode.get() == Mode.SET));

    // META mode (Anubis HomeMeta): sethome -> /rtp -> wait for the teleport -> /home back.
    private final IntSetting metaDelayTicks = add(new IntSetting("meta-delay", "Step delay (ticks)", 20, 5, 100, 1)
        .description("Ticks between sequence steps (20 = 1s). Raise on laggy servers.")
        .group("Meta").visibleWhen(() -> mode.get() == Mode.META));
    private final StringSetting metaRtpCommand = add(new StringSetting("meta-rtp", "RTP command", "rtp")
        .description("Command used to teleport away.")
        .group("Meta").visibleWhen(() -> mode.get() == Mode.META));
    private final IntSetting metaRtpTimeout = add(new IntSetting("meta-rtp-timeout", "RTP timeout (s)", 15, 5, 60, 1)
        .description("Give up if the RTP teleport hasn't happened after this long.")
        .group("Meta").visibleWhen(() -> mode.get() == Mode.META));

    // SilentHome (Water port): hide the server's "Home set"/"Home deleted" confirmation lines so
    // nothing appears in chat while setting a stash home, optionally logging to a webhook instead.
    private final BoolSetting silent = add(new BoolSetting("silent", "Silent (hide confirmations)", false)
        .description("Suppress the server's home-command confirmation chat lines for a few seconds around the command (Water SilentHome).")
        .group("Silent"));
    private final StringSetting silentPatterns = add(new StringSetting("silent-patterns", "Suppress patterns",
            "home,sethome,delhome")
        .description("Comma-separated case-insensitive substrings; system chat containing any of them is hidden during the window.")
        .group("Silent").visibleWhen(() -> silent.get()));
    private final StringSetting webhook = add(new StringSetting("webhook", "Webhook", "")
        .description("Optional Discord webhook: posts the home slot + coordinates after setting (your private log).")
        .group("Silent").visibleWhen(() -> silent.get()));

    private static final java.time.Duration HTTP_TIMEOUT = java.time.Duration.ofSeconds(8L);
    private static final java.net.http.HttpClient HTTP = com.autism.seedcracker.util.Http.CLIENT;

    /** Suppression window read by HomeSetterChatMixin (static: the mixin has no module ref). */
    private static volatile long suppressUntilMs = 0;
    private static volatile String[] suppressPatterns = new String[0];

    /** True if this system-chat line should be hidden (called from HomeSetterChatMixin). */
    public static boolean shouldSuppress(net.minecraft.network.chat.Component message) {
        if (System.currentTimeMillis() > suppressUntilMs || message == null) return false;
        String text = message.getString().toLowerCase(java.util.Locale.ROOT);
        for (String p : suppressPatterns) {
            if (!p.isEmpty() && text.contains(p)) return true;
        }
        return false;
    }

    private volatile boolean running = false;

    public HomeSetterModule() {
        super(SeedcrackerAddon.ID + ":z-home-setter", "Home Setter",
            "One-shot: sets a home at your current position by running the server home commands.");
    }

    @Override
    public void onEnable() {
        if (running) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) {
            AutismClientMessaging.sendPrefixed("§c[Home Setter] Join a world first.");
            setEnabledSilently(false);
            return;
        }
        if (mode.get() == Mode.META) {
            startMeta(mc);
            return;
        }
        running = true;
        int homeSlot = slot.get();
        int wait = delayMs.get();
        boolean doDelete = deleteFirst.get();
        String setCmd = buildCommand(setCommand.get(), homeSlot);
        String delCmd = buildCommand(deleteCommand.get(), homeSlot);

        boolean quiet = silent.get();
        if (quiet) {
            // Window covers delete + delay + set + server response lag.
            suppressPatterns = splitPatterns(silentPatterns.get());
            suppressUntilMs = System.currentTimeMillis() + wait + 5000L;
        } else {
            AutismClientMessaging.sendPrefixed("§7[Home Setter] Setting home §f" + homeSlot + " §7at your position...");
        }

        // Run the delete on the client thread, then set after a delay off-thread.
        if (doDelete) {
            sendCommand(mc, delCmd);
        }
        Thread delayThread = new Thread(() -> {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            mc.execute(() -> {
                // User toggled off during the delay: don't fire the command seconds later.
                if (!running || mc.getConnection() == null) {
                    running = false;
                    return;
                }
                sendCommand(mc, setCmd);
                if (!quiet) {
                    AutismClientMessaging.sendPrefixed("§a[Home Setter] Home §f" + homeSlot + " §aset.");
                }
                postWebhook(mc, homeSlot);
                running = false;
                setEnabledSilently(false);
            });
        }, "HomeSetter-Delay");
        delayThread.setDaemon(true);
        delayThread.start();
    }

    @Override
    public void onDisable() {
        running = false;
        metaStep = MetaStep.IDLE;
    }

    // ---- META mode: sethome -> rtp -> wait for teleport -> home back ----

    private enum MetaStep { IDLE, SETHOME_1, SETHOME_2, RTP, WAIT_TELEPORT, HOME_BACK }

    private MetaStep metaStep = MetaStep.IDLE;
    private int metaTicks;
    private Vec3 metaStartPos;
    private long metaRtpSentAt;

    private void startMeta(Minecraft mc) {
        running = true;
        metaStep = MetaStep.SETHOME_1;
        metaTicks = 0;
        metaStartPos = mc.player.position();
        AutismClientMessaging.sendPrefixed("§7[Home Meta] Starting: sethome -> rtp -> home back.");
    }

    @Override
    public void tick() {
        if (metaStep == MetaStep.IDLE) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) { abortMeta("left the world"); return; }

        if (metaStep == MetaStep.WAIT_TELEPORT) {
            // The RTP lands when we've moved far from the start point.
            if (mc.player.position().distanceToSqr(metaStartPos) > 64 * 64) {
                metaStep = MetaStep.HOME_BACK;
                metaTicks = 0;
            } else if (System.currentTimeMillis() - metaRtpSentAt > metaRtpTimeout.get() * 1000L) {
                abortMeta("rtp timed out");
            }
            return;
        }

        if (++metaTicks < metaDelayTicks.get()) return;
        metaTicks = 0;
        String setCmd = buildCommand(setCommand.get(), slot.get());
        switch (metaStep) {
            case SETHOME_1 -> { sendCommand(mc, setCmd); metaStep = MetaStep.SETHOME_2; }
            // ponytail: double-sethome mirrors Anubis HomeMeta (some servers eat the first under lag)
            case SETHOME_2 -> { sendCommand(mc, setCmd); metaStep = MetaStep.RTP; }
            case RTP -> {
                sendCommand(mc, buildCommand(metaRtpCommand.get(), slot.get()));
                metaRtpSentAt = System.currentTimeMillis();
                metaStep = MetaStep.WAIT_TELEPORT;
            }
            case HOME_BACK -> {
                sendCommand(mc, "home " + slot.get());
                AutismClientMessaging.sendPrefixed("§a[Home Meta] Done - heading home.");
                finishMeta();
            }
            default -> { }
        }
    }

    private void abortMeta(String why) {
        AutismClientMessaging.sendPrefixed("§c[Home Meta] Aborted: " + why + ".");
        finishMeta();
    }

    private void finishMeta() {
        metaStep = MetaStep.IDLE;
        running = false;
        setEnabledSilently(false);
    }

    private static String buildCommand(String template, int slotNumber) {
        String cmd = template == null || template.isBlank() ? "sethome %slot%" : template.trim();
        cmd = cmd.replace("%slot%", Integer.toString(slotNumber));
        return cmd.startsWith("/") ? cmd.substring(1) : cmd;
    }

    private static String[] splitPatterns(String raw) {
        if (raw == null || raw.isBlank()) return new String[0];
        String[] parts = raw.toLowerCase(java.util.Locale.ROOT).split(",");
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out.toArray(new String[0]);
    }

    /** Fire-and-forget webhook log of the new home position. */
    private void postWebhook(Minecraft mc, int homeSlot) {
        String url = webhook.get();
        if (url == null || url.isBlank() || mc.player == null) return;
        String content = "Home " + homeSlot + " set at "
            + mc.player.getBlockX() + " " + mc.player.getBlockY() + " " + mc.player.getBlockZ();
        String json = "{\"content\":" + com.autism.seedcracker.util.pure.Json.quote(content) + "}";
        try {
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url.trim()))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                .build();
            HTTP.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.discarding());
        } catch (Throwable ignored) {}
    }

    private static void sendCommand(Minecraft mc, String command) {
        if (command == null || command.isBlank()) return;
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) return;
        try {
            connection.sendCommand(command);
        } catch (Throwable t) {
            try {
                connection.sendChat("/" + command);
            } catch (Throwable ignored) {
            }
        }
    }
}
