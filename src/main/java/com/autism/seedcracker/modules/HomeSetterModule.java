package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

/**
 * Home Setter.
 *
 * A one-shot module: enabling it deletes the configured home slot, waits a beat, then sets it to
 * your current position by running the server's home commands, then auto-disables. Works with
 * both /delhome+sethome style plugins and /home+sethome style via a custom command template.
 *
 * Clean-room port of the obfuscated Zelith "HomeSetter" module.
 */
public final class HomeSetterModule extends Module {

    private final IntSetting slot = add(new IntSetting("slot", "Home slot", 1, 1, 10, 1)
        .description("Which home slot number to set.")
        .group("General"));
    private final BoolSetting deleteFirst = add(new BoolSetting("delete-first", "Delete old home first", true)
        .description("Run the delete command before setting, to overwrite an existing home.")
        .group("General"));
    private final StringSetting setCommand = add(new StringSetting("set-command", "Set command", "sethome %slot%")
        .description("Command used to set the home. %slot% is replaced with the slot number.")
        .group("General"));
    private final StringSetting deleteCommand = add(new StringSetting("delete-command", "Delete command", "delhome %slot%")
        .description("Command used to delete the home. %slot% is replaced with the slot number.")
        .group("General"));
    private final IntSetting delayMs = add(new IntSetting("delay-ms", "Delay (ms)", 750, 100, 5000, 50)
        .description("Milliseconds to wait between the delete and set commands.")
        .group("General"));

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
