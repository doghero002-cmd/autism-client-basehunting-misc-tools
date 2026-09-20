package com.autism.seedcracker.modules;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.BaseTracker;
import com.autism.seedcracker.util.FlagLog;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;

/**
 * Base Webhook (Krypton RtpBaseFinder webhook port, generalized).
 *
 * Watches the shared {@link BaseTracker} (every finder module reports flagged chunks there) and
 * posts a Discord webhook embed the first time a NEW base location is confirmed. Dedupes by
 * chunk cell so one base = one ping, with an optional self-ping and coordinates.
 *
 * Works with every finder automatically - StashFinder, TunnelBase bots, ChunkFinder, spawner
 * finds - because they all flow through BaseTracker.
 */
public final class BaseWebhookModule extends Module {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(8L);
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(HTTP_TIMEOUT)
        .build();

    private final StringSetting webhook = add(new StringSetting("webhook", "Webhook URL", "")
        .description("Discord webhook URL to post base finds to.").group("Webhook"));
    private final StringSetting selfPing = add(new StringSetting("self-ping", "Self ping ID", "")
        .description("Optional Discord user ID to @mention in the message.").group("Webhook"));
    private final IntSetting minConfidence = add(new IntSetting("min-confidence", "Min confidence", 50, 0, 100, 5)
        .description("Only post finds at or above this confidence (BaseTracker scale 0-100).").group("Filter"));
    private final IntSetting dedupeCell = add(new IntSetting("dedupe-cell", "Dedupe cell (chunks)", 8, 1, 64, 1)
        .description("Finds within the same NxN chunk cell count as one base (one ping).").group("Filter"));
    private final BoolSetting includeCoords = add(new BoolSetting("include-coords", "Include coordinates", true)
        .description("Include block coordinates in the webhook (disable for shared/insecure channels).").group("Webhook"));
    private final BoolSetting chatEcho = add(new BoolSetting("chat-echo", "Chat echo", true)
        .description("Also print a chat line when a webhook is posted.").group("General"));

    /** Cells already posted this session (cell key -> post time). */
    private final java.util.Map<Long, Long> posted = new java.util.concurrent.ConcurrentHashMap<>();
    private int pollTicks = 0;

    public BaseWebhookModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":base-webhook", "Base Webhook", category,
            "Posts a Discord webhook the first time any finder confirms a new base location.");
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (++pollTicks < 20) return; // poll 1/s
        pollTicks = 0;

        String url = webhook.get() == null ? "" : webhook.get().trim();
        if (url.isEmpty() || !url.startsWith("https://")) return;

        for (BaseTracker.Entry e : BaseTracker.nearest(mc.player.getX(), mc.player.getZ(), 32)) {
            if (e.confidence() < minConfidence.get()) continue;
            long cellKey = cellKey(e.blockX(), e.blockZ());
            if (posted.putIfAbsent(cellKey, System.currentTimeMillis()) != null) continue;

            String dim = mc.level.dimension().identifier().toString();
            String coords = includeCoords.get()
                ? "X: " + e.blockX() + ", Z: " + e.blockZ() + " (" + dim + ")"
                : "(coordinates hidden)";
            String desc = "Source: " + e.source() + "\\nConfidence: " + e.confidence() + "%\\n" + coords;
            postWebhook(url, "Base found!", desc);
            if (chatEcho.get()) {
                AutismClientMessaging.sendPrefixed("§a[BaseWebhook] §fPosted find from §e" + e.source()
                    + "§f (" + e.confidence() + "%)");
            }
        }
    }

    private long cellKey(int blockX, int blockZ) {
        int cell = Math.max(1, dedupeCell.get()) * 16;
        long cx = Math.floorDiv(blockX, cell);
        long cz = Math.floorDiv(blockZ, cell);
        return (cx << 32) ^ (cz & 0xffffffffL);
    }

    private void postWebhook(String url, String title, String description) {
        String ping = selfPing.get() == null ? "" : selfPing.get().trim();
        String content = ping.isEmpty() ? "" : "<@" + ping + "> ";
        String json = "{"
            + "\"username\":\"Base Webhook\","
            + (content.isEmpty() ? "" : "\"content\":" + quote(content) + ",")
            + "\"embeds\":[{"
            + "\"title\":" + quote(title) + ","
            + "\"description\":" + quote(description) + ","
            + "\"color\":15105570"
            + "}]}";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .exceptionally(t -> {
                    FlagLog.warn("WEBHOOK", "BaseWebhook", "post failed: " + t);
                    return null;
                });
        } catch (Throwable t) {
            FlagLog.warn("WEBHOOK", "BaseWebhook", "post failed: " + t);
        }
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
