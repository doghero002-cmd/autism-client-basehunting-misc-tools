package com.autism.seedcracker.modules;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.flip.GuiListingReader;
import com.autism.seedcracker.flip.core.FlipModel.Listing;
import com.autism.seedcracker.market.AhGui;
import com.autism.seedcracker.util.ActionPacer;

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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * AH Sniper.
 *
 * Watches the DonutSMP auction house for a specific item at or under a target price and buys it
 * the moment it appears. Two modes:
 *
 *  - MANUAL: no API key needed. Drives the normal /ah GUI - opens /ah <item>, scans
 *    listing pages and clicks a matching cheap listing to buy.
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
    private final IntSetting maxBuys = add(new IntSetting(
            "max-buys", "Max buys (0 = endless)", 1, 0, 128, 1)
        .description("Auto-disable after this many CONFIRMED purchases (runaway-spend failsafe). 1 = snipe one and stop.")
        .group("General"));
    private final BoolSetting notify = add(new BoolSetting(
            "notify", "Notifications", true)
        .description("Chat notifications for finds / errors.")
        .group("General"));

    // API mode only: the key + poll rate are hidden in MANUAL mode so the panel isn't confusing.
    private final StringSetting apiKey = add(new StringSetting(
            "api-key", "API key", "")
        .description("DonutSMP API key (type /api in-game). Only used in API mode.")
        .group("API").visibleWhen(() -> mode.get() == Mode.API));
    private final IntSetting apiRefreshMs = add(new IntSetting(
            "api-refresh-ms", "API refresh (ms)", 500, 150, 5000, 10)
        .description("Base API poll interval (jittered +-30% so the cadence isn't a metronome; sub-150ms hammers the API and gets keys rate-limited).")
        .group("API").visibleWhen(() -> mode.get() == Mode.API));

    // Timing + debug tucked under advanced so the common case is just item/price/mode.
    private final BoolSetting showAdvanced = add(new BoolSetting("advanced", "Show advanced", false)
        .description("Reveal click/refresh timing and debug tracing.").group("General"));
    private final IntSetting refreshDelay = add(new IntSetting(
            "refresh-delay", "Refresh delay (ticks)", 2, 0, 100, 1)
        .description("Delay before re-opening / refreshing the auction page.")
        .group("Timing").visibleWhen(showAdvanced::get));
    private final IntSetting buyDelay = add(new IntSetting(
            "buy-delay", "Buy delay (ticks)", 2, 0, 100, 1)
        .description("Delay before clicking to buy a matched listing.")
        .group("Timing").visibleWhen(showAdvanced::get));
    private final BoolSetting debug = add(new BoolSetting("debug", "Debug tracing", false)
        .description("Trace snipe/verify phases to chat + /flaglog.").group("Timing").visibleWhen(showAdvanced::get));

    private final HttpClient http = com.autism.seedcracker.util.Http.CLIENT;

    private int delayCounter;
    private boolean isProcessing;
    private boolean apiQueryInProgress;
    private boolean isAuctionSniping;
    private int auctionPageCounter = -1;
    private String currentSeller = "";
    private long lastApiCall;
    private long nextApiGap = 500; // jittered per-poll
    private boolean warnedUnparseable; // once per enable, not per scan
    private Listing preparedListing;
    private AbstractContainerMenu preparedMenu;
    private int preparedSlot = -1;
    private Listing pendingListing;
    private int preBuyCount = -1;
    private long verifyDeadline;
    private boolean confirmSent;
    private int confirmedBuys;
    private int apiGeneration;
    private CompletableFuture<java.util.List<com.google.gson.JsonObject>> apiQuery;

    // The flipper borrows this executor without changing the user's sniper settings or toggle.
    private Listing flipTarget;
    private long flipDeadline;
    private boolean flipSearchSent;
    private boolean lastFlipBought;

    public AHSniperModule() {
        super(SeedcrackerAddon.ID + ":ah-sniper", "AH Sniper",
            "Buys a target item the instant it appears at/under your price (manual GUI or API mode).");
    }

    @Override
    public void onEnable() {
        cancelWork();
        if (parsePrice(price.get()) <= 0) {
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
        warnedUnparseable = false;
        confirmedBuys = 0;
    }

    @Override
    public void onDisable() {
        cancelWork();
    }

    private void cancelWork() {
        apiGeneration++;
        if (apiQuery != null) apiQuery.cancel(true);
        apiQuery = null;
        isAuctionSniping = false;
        apiQueryInProgress = false;
        currentSeller = "";
        auctionPageCounter = -1;
        delayCounter = 0;
        cancelFlip();
        resetPurchase();
    }

    @Override
    protected void onOptionValueChanged(String name) {
        if (name.equals("mode") || name.equals("item") || name.equals("price")
            || name.equals("api-key") || name.equals("max-buys")) cancelWork();
    }

    @Override
    protected void onSettingsReset() {
        cancelWork();
    }

    @Override
    public boolean onPacketSend(net.minecraft.network.protocol.Packet<?> packet) {
        if (packet instanceof net.minecraft.network.protocol.game.ServerboundContainerClosePacket) {
            Minecraft.getInstance().execute(() -> {
                if (pendingListing != null || preparedListing != null || flipTarget != null) {
                    failPurchase("Container closed; purchase cancelled.");
                }
            });
        }
        return false;
    }

    @Override
    public void onGameLeft() {
        onDisable(); // A pending purchase must never cross a connection/dimension change.
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public boolean ticksWhenDisabled() {
        return true;
    }

    @Override
    public boolean hasDisabledTickWork() {
        return flipTarget != null;
    }

    public boolean flipBusy() {
        return flipTarget != null;
    }

    public boolean lastFlipBought() {
        return lastFlipBought;
    }

    public boolean tryBuyFlip(Listing target) {
        Minecraft mc = Minecraft.getInstance();
        if (isEnabled() || flipTarget != null || target == null || !target.isValid()
            || mc.player == null || mc.level == null || mc.getConnection() == null
            || target.seller() == null || !target.seller().matches("[A-Za-z0-9_]{3,16}")
            || target.seller().equalsIgnoreCase(mc.player.getGameProfile().name())
            || (mc.gui.screen() != null && !isAuctionGui(mc.player.containerMenu))) return false;
        resetPurchase();
        delayCounter = 0;
        flipTarget = target;
        lastFlipBought = false;
        flipSearchSent = false;
        flipDeadline = System.currentTimeMillis() + 15_000;
        AhGui.setPurchasing(true);
        return true;
    }

    public void cancelFlip() {
        if (flipTarget == null) return;
        flipTarget = null;
        lastFlipBought = false;
        resetPurchase();
    }

    private void finishFlip(boolean bought) {
        flipTarget = null;
        lastFlipBought = bought;
        resetPurchase();
    }

    private void resetPurchase() {
        preparedListing = null;
        preparedMenu = null;
        preparedSlot = -1;
        pendingListing = null;
        preBuyCount = -1;
        verifyDeadline = 0;
        confirmSent = false;
        isProcessing = false;
        AhGui.setPurchasing(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        long now = System.currentTimeMillis();
        com.autism.seedcracker.util.DebugProbe.setEnabled(id(), debug.get());
        com.autism.seedcracker.util.DebugProbe.traceChange(id(), "state", info());
        if (preBuyCount >= 0) {
            verifyPurchase(mc, now);
            return;
        }
        if (flipTarget != null && now >= flipDeadline) {
            finishFlip(false);
            return;
        }
        if (flipTarget == null && maxBuys.get() > 0 && confirmedBuys >= maxBuys.get()) {
            setEnabledSilently(false);
            return;
        }
        if (delayCounter > 0) {
            delayCounter--;
            return;
        }
        if (flipTarget != null) handleFlip(mc);
        else if (mode.get() == Mode.API) handleApiMode(mc);
        else handleManualMode(mc);
    }

    private void verifyPurchase(Minecraft mc, long now) {
        if (AhGui.delivered(preBuyCount, countOf(mc, pendingListing.itemKey()), pendingListing.count())) {
            long paid = pendingListing.totalPrice();
            if (flipTarget != null) {
                finishFlip(true);
            } else {
                confirmedBuys++;
                resetPurchase();
                send("§a[AH Sniper] Purchase confirmed for " + formatPrice(paid) + ".");
                if (maxBuys.get() > 0 && confirmedBuys >= maxBuys.get()) {
                    send("§a[AH Sniper] Hit max buys (" + maxBuys.get() + ") - stopping.");
                    setEnabledSilently(false);
                }
            }
            return;
        }
        if (now >= verifyDeadline) {
            failPurchase("Purchase not confirmed; stopping to avoid buying twice.");
            return;
        }
        clickConfirmIfPresent(mc);
    }

    private void failPurchase(String reason) {
        if (flipTarget != null) finishFlip(false);
        else {
            send("§e[AH Sniper] " + reason);
            resetPurchase();
            setEnabledSilently(false);
        }
    }

    private static int countOf(Minecraft mc, String itemKey) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (!s.isEmpty() && itemKey.equals(GuiListingReader.itemKey(s))) n += s.getCount();
        }
        return n;
    }

    private void handleFlip(Minecraft mc) {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (isAuctionGui(menu) && scanListingSlots(mc, menu)) return;
        if (mc.gui.screen() != null && !isAuctionGui(menu)) {
            finishFlip(false); // Never replace a chest, inventory, chat or another user's screen.
            return;
        }
        if (!flipSearchSent) {
            if (sendCommand(mc, "ah " + flipTarget.seller())) {
                flipSearchSent = true;
                delayCounter = 20;
            }
        } else if (isAuctionGui(menu)) {
            int next = AhGui.findNextPageSlot(menu, AhGui.containerSlots(mc, menu));
            if (next >= 0 && click(mc, menu, next)) AhGui.notePageTurn();
            delayCounter = 20;
        }
    }

    // ---- MANUAL mode (drive the open /ah GUI) ----

    private void handleManualMode(Minecraft mc) {
        if (resolveItem() == null || parsePrice(price.get()) <= 0) {
            failPurchase("Invalid item or max price.");
            return;
        }
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (!isAuctionGui(menu)) {
            if (mc.gui.screen() == null && sendCommand(mc, "ah " + prettyItemName())) delayCounter = 20;
            return;
        }
        if (scanListingSlots(mc, menu)) return;
        int slots = AhGui.containerSlots(mc, menu);
        int control = AhGui.findNextPageSlot(menu, slots);
        if (control < 0) control = AhGui.findRefreshSlot(menu, slots);
        if (control >= 0) {
            if (click(mc, menu, control)) AhGui.notePageTurn();
        } else {
            sendCommand(mc, "ah " + prettyItemName());
        }
        delayCounter = Math.max(20, refreshDelay.get() + 20);
    }

    // ---- API mode (poll DonutSMP, then open seller page) ----

    private void handleApiMode(Minecraft mc) {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (!isAuctionSniping) {
            if (apiQueryInProgress) return;
            long now = System.currentTimeMillis();
            if (now - lastApiCall < nextApiGap) return;
            nextApiGap = com.autism.seedcracker.util.Humanizer.delayMs(apiRefreshMs.get());
            lastApiCall = now;
            String key = apiKey.get().trim();
            if (key.isEmpty()) {
                if (notify.get()) send("§c[AH Sniper] API key not set (use /api in-game).");
                delayCounter = 100;
                return;
            }
            if (mc.getConnection() == null || parsePrice(price.get()) <= 0 || resolveItem() == null) return;
            apiQueryInProgress = true;
            int generation = apiGeneration;
            var connection = mc.getConnection();
            String requestedItem = itemId.get();
            String requestedPrice = price.get();
            apiQuery = queryApi(key, generation);
            apiQuery.whenComplete((list, error) -> mc.execute(() -> {
                if (generation != apiGeneration || !isEnabled() || mode.get() != Mode.API
                    || mc.getConnection() != connection) return;
                apiQueryInProgress = false;
                apiQuery = null;
                if (error == null && itemId.get().equals(requestedItem) && price.get().equals(requestedPrice)) {
                    processApiResponse(mc, list);
                }
            }));
        } else {
            if (auctionPageCounter == -1) {
                if (mc.gui.screen() != null && !isAuctionGui(menu)) return;
                if (sendCommand(mc, "ah " + currentSeller)) {
                    auctionPageCounter = 80;
                    delayCounter = 20;
                }
                return;
            }
            if (!isAuctionGui(menu)) {
                if (--auctionPageCounter <= 0) {
                    isAuctionSniping = false;
                    currentSeller = "";
                }
                return;
            }
            if (scanListingSlots(mc, menu)) return;
            int next = AhGui.findNextPageSlot(menu, AhGui.containerSlots(mc, menu));
            if (next >= 0) {
                if (click(mc, menu, next)) AhGui.notePageTurn();
                delayCounter = 20;
            } else {
                isAuctionSniping = false;
                currentSeller = "";
                closeScreen(mc);
            }
        }
    }

    /** Scan only the auction container, then recheck the exact listing after the buy delay. */
    private boolean scanListingSlots(Minecraft mc, AbstractContainerMenu menu) {
        Item target = flipTarget == null ? resolveItem() : null;
        long maxPrice = flipTarget == null ? parsePrice(price.get()) : flipTarget.totalPrice();
        if ((flipTarget == null && target == null) || maxPrice <= 0) return false;
        int limit = AhGui.containerSlots(mc, menu);
        long now = System.currentTimeMillis();
        for (int i = 0; i < limit; i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (stack.isEmpty() || (target != null && !stack.is(target))) continue;
            Listing listing = GuiListingReader.read(stack, now);
            if (listing == null) {
                if (target != null && !warnedUnparseable) {
                    warnedUnparseable = true;
                    send("§e[AH Sniper] Skipping " + itemName(stack) + " - price not readable from the lore.");
                }
                continue;
            }
            if (listing.totalPrice() > maxPrice
                || listing.seller().equalsIgnoreCase(mc.player.getGameProfile().name())) continue;
            if (flipTarget != null && !AhGui.sameListing(flipTarget, listing)) continue;
            if (flipTarget == null && mode.get() == Mode.API && isAuctionSniping
                && !listing.seller().equalsIgnoreCase(currentSeller)) continue;

            if (isProcessing && preparedMenu == menu && preparedSlot == i
                && AhGui.sameListing(preparedListing, listing)) {
                int before = countOf(mc, listing.itemKey());
                if (!click(mc, menu, i)) return true;
                pendingListing = listing;
                preBuyCount = before;
                verifyDeadline = now + 8_000;
                confirmSent = false;
                preparedListing = null;
                preparedMenu = null;
                isProcessing = false;
                AhGui.setPurchasing(true);
                return true;
            }
            preparedListing = listing;
            preparedMenu = menu;
            preparedSlot = i;
            isProcessing = true;
            AhGui.setPurchasing(true);
            delayCounter = com.autism.seedcracker.util.Humanizer.delay(Math.max(1, buyDelay.get()));
            return true;
        }
        preparedListing = null;
        preparedMenu = null;
        isProcessing = false;
        AhGui.setPurchasing(flipTarget != null);
        return false;
    }

    // ---- DonutSMP REST API ----

    private CompletableFuture<java.util.List<com.google.gson.JsonObject>> queryApi(String key, int generation) {
        return CompletableFuture.supplyAsync(() -> {
            java.util.List<com.google.gson.JsonObject> out = new java.util.ArrayList<>();
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.donutsmp.net/v1/auction/list/1"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"sort\": \"recently_listed\"}"))
                    .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    apiError(generation, "API error: " + resp.statusCode());
                    return out;
                }
                com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
                if (root.has("result") && root.get("result").isJsonArray()) {
                    for (com.google.gson.JsonElement el : root.getAsJsonArray("result")) {
                        if (el.isJsonObject()) out.add(el.getAsJsonObject());
                    }
                }
            } catch (Exception e) {
                apiError(generation, "API query failed (" + e.getClass().getSimpleName() + ").");
            }
            return out;
        });
    }

    private void apiError(int generation, String message) {
        Minecraft.getInstance().execute(() -> {
            if (generation != apiGeneration || !isEnabled()) return;
            delayCounter = 100;
            send("§c[AH Sniper] " + message);
        });
    }

    private void processApiResponse(Minecraft mc, java.util.List<com.google.gson.JsonObject> list) {
        if (mc.player == null) return;
        Identifier wantId = Identifier.tryParse(itemId.get().trim());
        long maxPrice = parsePrice(price.get());
        if (wantId == null || maxPrice <= 0) return;
        for (com.google.gson.JsonObject auction : list) {
            try {
                String id = auction.getAsJsonObject("item").get("id").getAsString().toLowerCase(Locale.ROOT);
                long priceVal = auction.get("price").getAsLong();
                String seller = auction.getAsJsonObject("seller").get("name").getAsString();
                if (wantId.equals(Identifier.tryParse(id)) && priceVal > 0 && priceVal <= maxPrice
                    && seller.matches("[A-Za-z0-9_]{3,16}")
                    && !seller.equalsIgnoreCase(mc.player.getGameProfile().name())) {
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
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && menu != null && menu != mc.player.inventoryMenu
            && AhGui.isAuctionPage(mc, menu);
    }

    private Item resolveItem() {
        // tryParse+getOptional: getValue() returns AIR for unknown ids, so a typo'd item id
        // silently sniped for AIR matches (never buys, no error).
        Identifier id = Identifier.tryParse(itemId.get().trim());
        if (id == null) return null;
        return BuiltInRegistries.ITEM.getOptional(id).orElse(null);
    }

    private String prettyItemName() {
        return AhGui.searchName(itemId.get());
    }

    private String itemName(ItemStack stack) {
        return stack.getHoverName().getString();
    }

    private boolean click(Minecraft mc, AbstractContainerMenu menu, int slot) {
        if (mc.gameMode == null || menu != mc.player.containerMenu || slot < 0
            || slot >= AhGui.containerSlots(mc, menu) || !ActionPacer.tryAction()) return false;
        com.autism.seedcracker.util.ContainerMutex.notifyContainerAction();
        mc.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.PICKUP, mc.player);
        return true;
    }

    /** Count container slots, not container + inventory; a 27-slot dialog has 63 total slots. */
    private void clickConfirmIfPresent(Minecraft mc) {
        if (confirmSent || pendingListing == null) return;
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu == null || menu == mc.player.inventoryMenu) return;
        int slots = AhGui.containerSlots(mc, menu);
        if (!AhGui.isConfirmDialog(slots, AhGui.screenTitle(mc))) return;
        int previewSlot = -1;
        double previewPrice = -1;
        for (int i = 0; i < slots; i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (stack.isEmpty() || stack.getCount() != pendingListing.count()
                || !pendingListing.itemKey().equals(GuiListingReader.itemKey(stack))) continue;
            double shownPrice = com.autism.seedcracker.market.ListingPriceParser.parse(stack);
            if (shownPrice > 0 && Math.round(shownPrice) != pendingListing.totalPrice()) {
                failPurchase("Confirmation price changed; not buying.");
                return;
            }
            previewSlot = i;
            previewPrice = shownPrice;
            break;
        }
        if (previewSlot < 0) return; // No matching stack visible = not our confirmation dialog.
        if (previewPrice < 0) {
            // ponytail: DonutSMP confirm GUIs omit lore from the preview item (price lives on the
            // button). Waiting forever makes every buy time out, so accept only when the dialog
            // title confirms it's the purchase confirm; a wrong chest with our stack + no price
            // would still need a "Confirm Purchase"-titled screen.
            if (!AhGui.isConfirmTitle(AhGui.screenTitle(mc))) return;
        }
        int confirm = AhGui.findConfirmSlot(menu, slots, previewSlot);
        if (confirm < 0) return;
        double shownPrice = com.autism.seedcracker.market.ListingPriceParser.parse(menu.slots.get(confirm).getItem());
        if (shownPrice >= 0 && Math.round(shownPrice) != pendingListing.totalPrice()) {
            failPurchase("Confirmation price changed; not buying.");
            return;
        }
        if (click(mc, menu, confirm)) confirmSent = true; // Reset per purchase, even if the server reuses its menu id.
    }

    private void closeScreen(Minecraft mc) {
        if (mc.player != null) mc.player.closeContainer();
    }

    private boolean sendCommand(Minecraft mc, String command) {
        if (mc.getConnection() == null || !ActionPacer.tryAction()) return false;
        if (command.startsWith("/")) mc.getConnection().sendCommand(command.substring(1));
        else mc.getConnection().sendCommand(command);
        return true;
    }

    /** Parse "1k"/"2.5m"/"1b"/plain numbers into a price, or -1 on error (shared parser). */
    static long parsePrice(String raw) {
        if (raw == null) return -1;
        return com.autism.seedcracker.util.pure.PriceMath.parseAmount(raw.replace("$", ""));
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
        if (preBuyCount >= 0) return confirmSent ? "waiting for item" : "confirming purchase";
        if (isProcessing) return "checking listing";
        if (flipTarget != null) return "buying flip";
        return isAuctionSniping ? "finding seller" : "watching";
    }
}
