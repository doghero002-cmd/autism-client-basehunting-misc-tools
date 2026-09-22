package com.autism.seedcracker.modules;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;

/**
 * Balance Tags (Water client Baltagger port, HUD-panel form).
 *
 * Looks up every visible player's DonutSMP balance through the official stats API (same key the
 * AH Sniper uses) and lists them in a HUD panel with health and distance - is that player worth
 * following home? Optional chat alert when someone over the wealth threshold appears. Balances
 * cache per session; one lookup per player, throttled to one request per second.
 */
public final class BalanceTagsModule extends Module {

    private final StringSetting apiKey = add(new StringSetting("api-key", "API key", "")
        .description("DonutSMP API key (same as AH Sniper). Empty = also tries the AH modules' saved key.")
        .group("API"));
    private final IntSetting alertOver = add(new IntSetting("alert-over-m", "Alert over ($M)", 0, 0, 1000, 1)
        .description("Chat ping when a player worth more than this many MILLION appears (0 = off).")
        .group("General"));
    private final IntSetting maxRows = add(new IntSetting("max-rows", "Max rows", 8, 1, 20, 1)
        .description("Nearest players listed on the panel.")
        .group("HUD"));
    private final IntSetting hudX = add(new IntSetting("hud-x", "HUD X", 4, 0, 4000, 1).group("HUD"));
    private final IntSetting hudY = add(new IntSetting("hud-y", "HUD Y", 120, 0, 4000, 1).group("HUD"));
    private final IntSetting hudWidth = add(new IntSetting("hud-width", "HUD width", 160, 110, 260, 2).group("HUD"));
    private final ColorSetting accent = add(new ColorSetting("hud-accent", "HUD accent", 0xFF54D66A).group("HUD"));
    private final BoolSetting showHp = add(new BoolSetting("show-hp", "Show HP", true).group("HUD"));

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8)).build();

    /** name -> balance; null-entry semantics via FAILED set. */
    private final Map<String, Long> balances = new ConcurrentHashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Set<String> failed = ConcurrentHashMap.newKeySet();
    private final Set<String> alerted = ConcurrentHashMap.newKeySet();
    private long lastRequestMs = 0;

    public BalanceTagsModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":balance-tags", "Balance Tags", category,
            "Lists nearby players' DonutSMP balances (API) with HP + distance on a HUD panel.");
    }

    @Override
    public void onEnable() {
        // Keep the balance cache across toggles (it's session intel); reset alerts + failures.
        failed.clear();
        alerted.clear();
    }

    @Override
    public void onGameLeft() {
        balances.clear();
        pending.clear();
        failed.clear();
        alerted.clear();
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        String key = resolveApiKey();
        if (key.isEmpty()) return;

        // One lookup per unseen player, max 1 request/second.
        long now = System.currentTimeMillis();
        if (now - lastRequestMs < 1000) return;
        for (Player p : mc.level.players()) {
            if (p == mc.player) continue;
            String name = p.getName().getString();
            if (name == null || name.isBlank()) continue;
            if (balances.containsKey(name) || pending.contains(name) || failed.contains(name)) {
                maybeAlert(name);
                continue;
            }
            pending.add(name);
            lastRequestMs = now;
            fetchBalance(name, key);
            break; // one per interval
        }
    }

    private void fetchBalance(String name, String key) {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("https://api.donutsmp.net/v1/stats/" + name))
            .header("accept", "application/json")
            .header("Authorization", key.startsWith("Bearer") ? key : "Bearer " + key)
            .timeout(Duration.ofSeconds(10))
            .GET().build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .thenAccept(resp -> {
                pending.remove(name);
                if (resp.statusCode() != 200) { failed.add(name); return; }
                try {
                    var root = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
                    var result = root.getAsJsonObject("result");
                    long money = (long) Double.parseDouble(result.get("money").getAsString());
                    balances.put(name, money);
                    maybeAlert(name);
                } catch (Throwable t) {
                    failed.add(name);
                }
            })
            .exceptionally(e -> { pending.remove(name); failed.add(name); return null; });
    }

    private void maybeAlert(String name) {
        int overM = alertOver.get();
        if (overM <= 0) return;
        Long bal = balances.get(name);
        if (bal == null || bal < overM * 1_000_000L) return;
        if (!alerted.add(name)) return;
        Minecraft.getInstance().execute(() ->
            AutismClientMessaging.sendPrefixed("§6[BalanceTags] §f" + name + " is worth §a" + formatMoney(bal) + "§f!"));
    }

    private String resolveApiKey() {
        String own = text("api-key").trim();
        if (!own.isEmpty()) return own;
        // Fall back to the key the AH modules save to disk.
        try {
            java.nio.file.Path f = autismclient.AutismClientAddon.FOLDER.toPath()
                .resolve("donut-ah").resolve("api-key.txt");
            if (java.nio.file.Files.exists(f)) return java.nio.file.Files.readString(f).trim();
        } catch (Throwable ignored) {}
        return "";
    }

    static String formatMoney(long money) {
        if (money >= 1_000_000_000L) return String.format(Locale.ROOT, "$%.1fB", money / 1_000_000_000.0);
        if (money >= 1_000_000L) return String.format(Locale.ROOT, "$%.1fM", money / 1_000_000.0);
        if (money >= 1_000L) return String.format(Locale.ROOT, "$%.1fK", money / 1_000.0);
        return "$" + money;
    }

    // ---- HUD panel (called from HudFlagTelemetryMixin) ----

    public static void renderHud(GuiGraphicsExtractor context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.font == null || mc.player == null || mc.level == null) return;
        var mod = autismclient.modules.ModuleRegistry.get(SeedcrackerAddon.ID + ":balance-tags");
        if (!(mod instanceof BalanceTagsModule bt) || !bt.isEnabled()) return;
        if (mc.gui != null && mc.gui.hud.isHidden()) return;

        // Nearest players first.
        List<Player> players = new ArrayList<>();
        for (Player p : mc.level.players()) if (p != mc.player) players.add(p);
        if (players.isEmpty()) return;
        players.sort(java.util.Comparator.comparingDouble(p -> p.distanceToSqr(mc.player)));

        Font font = mc.font;
        int width = bt.hudWidth.get();
        int contentWidth = width - 12;
        List<autismclient.gui.vanillaui.direct.DirectHudPanelRenderer.Row> rows = new ArrayList<>();
        int max = Math.min(players.size(), bt.maxRows.get());
        for (int i = 0; i < max; i++) {
            Player p = players.get(i);
            String name = p.getName().getString();
            Long bal = bt.balances.get(name);
            String balText = bal != null ? formatMoney(bal)
                : bt.failed.contains(name) ? "n/a" : "...";
            StringBuilder line = new StringBuilder(name).append("  ").append(balText);
            if (bt.showHp.get()) {
                line.append("  ").append((int) Math.ceil(p.getHealth() + p.getAbsorptionAmount())).append("hp");
            }
            line.append("  ").append((int) p.distanceTo(mc.player)).append("m");
            int color = bal != null && bal >= 10_000_000L ? 0xFFFFD54F : 0xFFE8E8E8;
            rows.add(autismclient.gui.vanillaui.direct.DirectHudPanelRenderer.Row.body(
                trim(font, line.toString(), contentWidth), color));
        }

        autismclient.gui.vanillaui.direct.DirectHudPanelRenderer.render(
            context, font, bt.hudX.get(), bt.hudY.get(), width,
            trim(font, "BALANCES", contentWidth), rows, bt.accent.get());
    }

    private static String trim(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        while (!text.isEmpty() && font.width(text + "..") > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "..";
    }

    @Override
    public String info() {
        return balances.size() + " cached";
    }
}
