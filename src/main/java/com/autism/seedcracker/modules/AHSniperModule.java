package com.autism.seedcracker.modules;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * AH Sniper.
 *
 * Watches the DonutSMP auction house for a specific item at or under a target price and buys it
 * the moment it appears. Two modes:
 *
 *  - MANUAL: no API key needed. Drives the normal /ah GUI - opens /ah <item>, switches to the
 *    "Recently Listed" sort, scans the listing slots and clicks a matching cheap listing to buy.
 *    Requires the auction GUI to stay open.
 *  - API: uses the DonutSMP REST API (paste your /api key) to poll recently-listed auctions, then
 *    opens /ah <seller> and buys the match. Works without you staring at the GUI.
 *
 * Price accepts suffixes: "1k" = 1,000, "2.5m" = 2,500,000, plain numbers also work.
 *
 * Port of the Krypton "AuctionSniper" module (DonutSMP) to the AUTISM API (Mojang 26.2).
 * The container interaction is rewritten against the real container menu API (no obfuscated
 * class references). Use at your own risk - automated buying may be against server rules.
 */
public final class AHSniperModule extends Module {

    public enum Mode { MANUAL, API }

    private final StringSetting itemId = add(new StringSetting(
            "item", "Sniping item", "minecraft:netherite_ingot")
        .description("Item id to snipe (e.g. minecraft:netherite_ingot).")
        .group("General"));
    private final StringSetting price = add(new StringSetting(
            "price", "Max price", "1k")
        .description("Max buy price. Supports k/m suffixes (1k, 2.5m).")
        .group("General"));
    private final EnumSetting<Mode> mode = add(new EnumSetting<>(
            "mode", "Mode", Mode.MANUAL, Mode.values())
        .description("MANUAL = drive the /ah GUI (no key). API = poll the DonutSMP API (needs key).")
        .group("General"));
    private final StringSetting apiKey = add(new StringSetting(
            "api-key", "API key", "")
        .description("DonutSMP API key (type /api in-game). Only used in API mode.")
        .group("API"));
    private final IntSetting refreshDelay = add(new IntSetting(
            "refresh-delay", "Refresh delay (ticks)", 2, 0, 100, 1)
        .description("Delay before re-opening / refreshing the auction page.")
        .group("General"));
    private final IntSetting buyDelay = add(new IntSetting(
            "buy-delay", "Buy delay (ticks)", 2, 0, 100, 1)
        .description("Delay before clicking to buy a matched listing.")
        .group("General"));
    private final IntSetting apiRefreshMs = add(new IntSetting(
            "api-refresh-ms", "API refresh (ms)", 250, 10, 5000, 10)
        .description("How often to poll the DonutSMP API in API mode.")
        .group("API"));
    private final BoolSetting notify = add(new BoolSetting(
            "notify", "Notifications", true)
        .description("Chat notifications for finds / errors.")
        .group("General"));

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private int delayCounter;
    private boolean isProcessing;
    private boolean apiQueryInProgress;
    private boolean isAuctionSniping;
    private int auctionPageCounter = -1;
    private String currentSeller = "";
    private long lastApiCall;

    public AHSniperModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":ah-sniper", "AH Sniper", category,
            "Buys a target item the instant it appears at/under your price (manual GUI or API mode).");
    }

    @Override
    public void onEnable() {
        if (parsePrice(price.get()) < 0) {
            send("§c[AH Sniper] Invalid price: " + price.get());
            setEnabledSilently(false);
            return;
        }
        if (resolveItem() == null) {
            send("§c[AH Sniper] Unknown item id: " + itemId.get());
            setEnabledSilently(false);
            return;
        }
        delayCounter = 0;
        isProcessing = false;
        apiQueryInProgress = false;
        isAuctionSniping = false;
        auctionPageCounter = -1;
        currentSeller = "";
        lastApiCall = 0;
    }

    @Override
    public void onDisable() {
        isAuctionSniping = false;
        apiQueryInProgress = false;
    }

    @Override
    public void onGameLeft() {
        setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (delayCounter > 0) {
            delayCounter--;
            return;
        }
        if (mode.get() == Mode.API) handleApiMode(mc);
        else handleManualMode(mc);
    }

    // ---- MANUAL mode (drive the open /ah GUI) ----

    private void handleManualMode(Minecraft mc) {
        AbstractContainerMenu menu = mc.player.containerMenu;
        // If no container is open (player inventory has same menu reference but no extra slots), open /ah.
        if (!isAuctionGui(menu)) {
            String name = prettyItemName();
            sendCommand(mc, "ah " + name);
            delayCounter = 20;
            return;
        }
        scanListingSlots(mc, menu);
    }

    // ---- API mode (poll DonutSMP, then open seller page) ----

    private void handleApiMode(Minecraft mc) {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (!isAuctionSniping) {
            if (apiQueryInProgress) return;
            long now = System.currentTimeMillis();
            if (now - lastApiCall < apiRefreshMs.get()) return;
            lastApiCall = now;
            String key = apiKey.get().trim();
            if (key.isEmpty()) {
                if (notify.get()) send("§c[AH Sniper] API key not set (use /api in-game).");
                delayCounter = 100;
                return;
            }
            apiQueryInProgress = true;
            queryApi(key).thenAccept(list -> {
                apiQueryInProgress = false;
                processApiResponse(mc, list);
            });
        } else {
            // We found a seller via the API: open their page and buy.
            if (!isAuctionGui(menu)) {
                if (auctionPageCounter == -1) {
                    sendCommand(mc, "ah " + currentSeller);
                    auctionPageCounter = 0;
                } else if (auctionPageCounter <= 40) {
                    auctionPageCounter++;
                } else {
                    isAuctionSniping = false;
                    currentSeller = "";
                }
            } else {
                auctionPageCounter = -1;
                scanListingSlots(mc, menu);
            }
        }
    }

    /** Scan the listing area for the target item at/under price and click to buy. */
    private void scanListingSlots(Minecraft mc, AbstractContainerMenu menu) {
        Item target = resolveItem();
        double maxPrice = parsePrice(price.get());
        if (target == null || maxPrice < 0) return;

        int limit = Math.min(menu.slots.size(), 45);
        for (int i = 0; i < limit; i++) {
            Slot slot = menu.slots.get(i);
            if (slot == null) continue;
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) continue;
            if (!stack.is(target)) continue;
            if (!isValidAuctionItem(stack)) continue;

            double listingPrice = readListingPrice(stack);
            if (listingPrice >= 0 && listingPrice > maxPrice) continue;

            if (isProcessing) {
                click(mc, menu, i);
                isProcessing = false;
                delayCounter = Math.max(1, refreshDelay.get());
                if (notify.get()) send("§a[AH Sniper] Bought " + itemName(stack) +
                    (listingPrice >= 0 ? " for " + formatPrice(listingPrice) : "") + ".");
                return;
            }
            isProcessing = true;
            delayCounter = Math.max(1, buyDelay.get());
            return;
        }

        // Nothing matched: if we were sniping a specific seller, close + reset; else refresh.
        if (isAuctionSniping) {
            isAuctionSniping = false;
            currentSeller = "";
            closeScreen(mc);
        } else {
            // Click the "next page" / refresh area if present, else just wait.
            delayCounter = Math.max(2, refreshDelay.get() + 20);
        }
    }

    // ---- DonutSMP REST API ----

    private CompletableFuture<java.util.List<com.google.gson.JsonObject>> queryApi(String key) {
        return CompletableFuture.supplyAsync(() -> {
            java.util.List<com.google.gson.JsonObject> out = new java.util.ArrayList<>();
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.donutsmp.net/v1/auction/list/1"))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"sort\": \"recently_listed\"}"))
                    .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    if (notify.get()) send("§c[AH Sniper] API error: " + resp.statusCode());
                    return out;
                }
                com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
                if (root.has("result") && root.get("result").isJsonArray()) {
                    for (com.google.gson.JsonElement el : root.getAsJsonArray("result")) {
                        if (el.isJsonObject()) out.add(el.getAsJsonObject());
                    }
                }
            } catch (Exception e) {
                if (notify.get()) send("§c[AH Sniper] API query failed: " + e.getMessage());
            }
            return out;
        });
    }

    private void processApiResponse(Minecraft mc, java.util.List<com.google.gson.JsonObject> list) {
        String wantId = itemId.get().trim().toLowerCase(Locale.ROOT);
        double maxPrice = parsePrice(price.get());
        for (com.google.gson.JsonObject auction : list) {
            try {
                String id = auction.getAsJsonObject("item").get("id").getAsString().toLowerCase(Locale.ROOT);
                long priceVal = auction.get("price").getAsLong();
                String seller = auction.getAsJsonObject("seller").get("name").getAsString();
                // Match on the bare item name (minecraft:netherite_ingot -> netherite_ingot).
                String bare = wantId.contains(":") ? wantId.substring(wantId.indexOf(':') + 1) : wantId;
                if (id.contains(bare) && (double) priceVal <= maxPrice) {
                    if (notify.get()) send("§a[AH Sniper] Found " + id + " for " + formatPrice(priceVal)
                        + " §r(<= " + formatPrice(maxPrice) + ") from §e" + seller);
                    isAuctionSniping = true;
                    currentSeller = seller;
                    auctionPageCounter = -1;
                    return;
                }
            } catch (Exception ignored) {}
        }
    }

    // ---- helpers ----

    private boolean isAuctionGui(AbstractContainerMenu menu) {
        if (menu == null) return false;
        // The auction GUI exposes many container slots beyond the player's own inventory menu.
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && menu != mc.player.inventoryMenu && menu.slots.size() > 45;
    }

    private boolean isValidAuctionItem(ItemStack stack) {
        return !stack.isEmpty();
    }

    /** Best-effort listing price read from the item's hover name / lore. -1 if unknown. */
    private double readListingPrice(ItemStack stack) {
        try {
            String name = stack.getHoverName().getString();
            java.util.List<net.minecraft.network.chat.Component> tooltip = stack.getTooltipLines(
                net.minecraft.world.item.Item.TooltipContext.EMPTY, Minecraft.getInstance().player,
                net.minecraft.world.item.TooltipFlag.Default.NORMAL);
            StringBuilder sb = new StringBuilder(name);
            for (net.minecraft.network.chat.Component c : tooltip) sb.append(' ').append(c.getString());
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\$?([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kKmM])?")
                .matcher(sb.toString());
            double best = -1;
            while (m.find()) {
                double v = Double.parseDouble(m.group(1).replace(",", ""));
                String suffix = m.group(2);
                if (suffix != null) {
                    if (suffix.equalsIgnoreCase("k")) v *= 1_000;
                    else if (suffix.equalsIgnoreCase("m")) v *= 1_000_000;
                }
                if (best < 0 || v < best) best = v;
            }
            return best;
        } catch (Exception e) {
            return -1;
        }
    }

    private Item resolveItem() {
        try {
            Identifier id = Identifier.parse(itemId.get().trim());
            return BuiltInRegistries.ITEM.getValue(id);
        } catch (Exception e) {
            return null;
        }
    }

    private String prettyItemName() {
        Item item = resolveItem();
        if (item == null) return itemId.get();
        return new ItemStack(item).getHoverName().getString();
    }

    private String itemName(ItemStack stack) {
        return stack.getHoverName().getString();
    }

    private void click(Minecraft mc, AbstractContainerMenu menu, int slot) {
        if (mc.gameMode == null) return;
        mc.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.PICKUP, mc.player);
    }

    private void closeScreen(Minecraft mc) {
        if (mc.player != null) mc.player.closeContainer();
    }

    private void sendCommand(Minecraft mc, String command) {
        if (mc.getConnection() == null) return;
        if (command.startsWith("/")) mc.getConnection().sendCommand(command.substring(1));
        else mc.getConnection().sendCommand(command);
    }

    /** Parse "1k"/"2.5m"/plain numbers into a price, or -1 on error. */
    static double parsePrice(String raw) {
        if (raw == null) return -1;
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("$", "");
        if (s.isEmpty()) return -1;
        double mult = 1;
        if (s.endsWith("k")) { mult = 1_000; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("m")) { mult = 1_000_000; s = s.substring(0, s.length() - 1); }
        try {
            double v = Double.parseDouble(s) * mult;
            return v < 0 ? -1 : v;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String formatPrice(double v) {
        if (v >= 1_000_000) return String.format(Locale.ROOT, "$%.2fm", v / 1_000_000);
        if (v >= 1_000) return String.format(Locale.ROOT, "$%.1fk", v / 1_000);
        return String.format(Locale.ROOT, "$%.0f", v);
    }

    private void send(String msg) {
        if (!notify.get()) return;
        AutismClientMessaging.sendPrefixed(msg);
    }

    @Override
    public String info() {
        return isAuctionSniping ? "buying" : "watching";
    }
}
