package com.autism.seedcracker.modules;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.compat.ClientNotify;
import com.autism.seedcracker.compat.ModuleLookup;
import com.autism.seedcracker.flip.ApiFlipSource;
import com.autism.seedcracker.flip.FlipEngine;
import com.autism.seedcracker.flip.GuiListingReader;
import com.autism.seedcracker.flip.core.FlipModel.Basis;
import com.autism.seedcracker.flip.core.FlipModel.Listing;
import com.autism.seedcracker.flip.core.FlipModel.Opportunity;
import com.autism.seedcracker.flip.core.OwnSaleParser;
import com.autism.seedcracker.market.AhGui;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;

/**
 * AH Flipper - DonutSMP flip finder built on the GoNuts valuation core (MIT, cody-raves/Gonuts).
 *
 * Values items from COMPLETED sales (recency-weighted percentiles, log-space outlier filter,
 * a conservative 37.5th-percentile quick-sale target, manipulation checks), caps the resale at
 * the next cheaper ask, and ranks flips by profit x fill odds x confidence / hold time.
 *
 * Data sources:
 *  - NO KEY (default): reads /ah pages you open and auto-pages them. Two complete passes over the
 *    same search a few minutes apart reveal which cheap listings vanished: those become inferred
 *    sales. Until enough exist, flips are valued against the live asks only (alert-only, capped
 *    confidence).
 *  - API KEY (optional): real completed sales + recently-listed book from api.donutsmp.net.
 *
 * Paper positions settle only on a LATER real/inferred sale at the target; losing markets can
 * be benched. Optional capped auto-buy uses the sniper's verified purchase flow. Ask-only
 * opportunities remain alerts, never purchases. Open .flip to see the list.
 */
public final class AHFlipperModule extends Module {

    public enum Source { KEYLESS, API, BOTH }

    private final EnumSetting<Source> source = add(new EnumSetting<>("source", "Data source", Source.KEYLESS, Source.values())
        .description("KEYLESS = learn from /ah pages you open (no key needed). API = DonutSMP API sales feed. "
            + "BOTH = API sales plus your own page scans.")
        .group("General"));
    private final StringSetting apiKey = add(new StringSetting("api-key", "DonutSMP API key", "")
        .description("Paste a key from /api in-game. It's moved into donut-ah/api-key.txt (owner-only) and this field is cleared.")
        .group("General").visibleWhen(() -> source.get() != Source.KEYLESS));
    private final BoolSetting autoPage = add(new BoolSetting("auto-page", "Auto-page /ah", true)
        .description("Click through every page of the /ah screen you open so each pass is complete (needed for keyless sale detection). "
            + "Stops when you move or close the screen.")
        .group("Keyless"));
    private final IntSetting pageDelay = add(new IntSetting("page-delay", "Page delay (ticks)", 30, 15, 200, 5)
        .description("Base delay between page turns (jittered).")
        .group("Keyless").visibleWhen(autoPage::get));
    private final IntSetting maxPages = add(new IntSetting("max-pages", "Max pages per pass", 15, 1, 60, 1)
        .description("A pass that hits this cap is incomplete and won't be used for sale detection.")
        .group("Keyless").visibleWhen(autoPage::get));
    private final StringSetting watchlist = add(new StringSetting("watchlist", "Re-scan watchlist", "")
        .description("Comma list of items (e.g. elytra, ender_pearl). While AFK with no screen open, opens /ah <item> "
            + "for each in turn. Auto-opened passes close when finished so later scans can run; manually opened screens stay open. "
            + "Blank = never opens /ah itself.")
        .group("Keyless"));
    private final IntSetting rescanMinutes = add(new IntSetting("rescan-minutes", "Re-scan every (min)", 6, 2, 60, 1)
        .description("Keep this short: sale detection only trusts passes under 20 minutes apart.")
        .group("Keyless").visibleWhen(() -> !watchlist.get().isBlank()));
    private final IntSetting minProfit = add(new IntSetting("min-profit", "Min profit", 5000, 0, 10_000_000, 500)
        .description("Net profit (after tax) a flip must clear.").group("Filters"));
    private final IntSetting minRoi = add(new IntSetting("min-roi", "Min ROI (%)", 12, 1, 500, 1).group("Filters"));
    private final StringSetting maxBuy = add(new StringSetting("max-buy", "Max buy price", "")
        .description("Ignore listings above this (k/m/b ok). Blank = no per-listing cap; invalid values reject all deals.").group("Filters"));
    private final BoolSetting autoBuy = add(new BoolSetting("auto-buy", "Auto-buy flips", false)
        .description("Buy sales-backed flips through AH Sniper. Requires a budget; ask-only deals stay alerts.").group("Buying"));
    private final StringSetting buyBudget = add(new StringSetting("buy-budget", "Buy budget", "")
        .description("Total coins reserved per enable (k/m/b ok). Set a positive amount to allow buys. Failed attempts stay reserved.")
        .group("Buying").visibleWhen(autoBuy::get));
    private final IntSetting maxBuys = add(new IntSetting("max-buys", "Max buy attempts", 1, 1, 128, 1)
        .description("Stop auto-buy after this many attempts, including unconfirmed buys. Re-enable the module to reset.")
        .group("Buying").visibleWhen(autoBuy::get));
    private final BoolSetting alerts = add(new BoolSetting("alerts", "Chat alerts", true).group("Alerts"));

    // Everything below is expert tuning - hidden until "Show advanced" so the default panel is
    // just the data source, the profit/ROI floor and a buy cap.
    private final BoolSetting showAdvanced = add(new BoolSetting("advanced", "Show advanced", false)
        .description("Reveal sample/confidence/hold filters, tax, fallbacks and paper-trading.").group("Filters"));
    private final IntSetting minSamples = add(new IntSetting("min-samples", "Min sales", 12, 3, 200, 1)
        .description("Completed/inferred sales needed before a market is valued from sales instead of asks.").group("Filters").visibleWhen(showAdvanced::get));
    private final IntSetting minConfidence = add(new IntSetting("min-confidence", "Min confidence (%)", 35, 5, 95, 5).group("Filters").visibleWhen(showAdvanced::get));
    private final IntSetting maxHold = add(new IntSetting("max-hold", "Max hold (h)", 3, 1, 48, 1)
        .description("Reject flips expected to take longer than this to resell.").group("Filters").visibleWhen(showAdvanced::get));
    private final IntSetting taxPercent = add(new IntSetting("tax", "Sale tax (%)", 0, 0, 50, 1)
        .description("Auction tax taken from the resale; leave 0 until you've confirmed the server's rate.").group("Filters").visibleWhen(showAdvanced::get));
    private final BoolSetting askFallback = add(new BoolSetting("ask-fallback", "Ask-only fallback", true)
        .description("While a market has too few sales, still flag listings far under the other live asks (alert only).").group("Filters").visibleWhen(showAdvanced::get));
    private final BoolSetting benchLosers = add(new BoolSetting("bench", "Bench losing markets", true)
        .description("Skip markets whose paper flips lost money in the last 24h; they come back when the window rolls.").group("Filters").visibleWhen(showAdvanced::get));
    private final BoolSetting alertSound = add(new BoolSetting("alert-sound", "Alert sound", true)
        .group("Alerts").visibleWhen(alerts::get));
    private final BoolSetting autoPaper = add(new BoolSetting("auto-paper", "Auto paper-trade", true)
        .description("Open a paper position for every sales-backed flip so you can see if the strategy pays before risking coins.")
        .group("Alerts").visibleWhen(showAdvanced::get));

    private FlipEngine engine;
    private ApiFlipSource api;
    private long nextApiPollAt;
    private final Set<String> alerted = new HashSet<>();
    private final Set<String> attempted = new HashSet<>();
    private AHSniperModule purchaseExecutor;
    private Listing pendingBuy;
    private int buyAttempts;
    private long reservedCoins;
    private String buyStatus = "waiting for sales-backed deals";

    private final Paging paging = new Paging();
    private net.minecraft.world.phys.Vec3 scanStartPos;
    private long firstPageAt;
    private long lastPageObservedAt;
    private String scanStatus = "Open /ah or add a watchlist to start learning";
    private final WatchPass watchPass = new WatchPass();
    private volatile String watchCommand;
    private String auctionQuery = "";

    static final class Paging {
        int containerId = -1;
        int cooldown;
        int turns;
        boolean read;
        boolean awaitingPage;
        boolean fromFirstPage;
        long requestedAt;
        String signature = "";
        String searchKey = "";

        boolean openMenu(int id, String title) {
            String key = AhGui.scanKey(title);
            if (id == containerId && key.equals(searchKey)) return false;
            if (awaitingPage && key.equals(searchKey)) {
                containerId = id;
                return false;
            }
            reset();
            containerId = id;
            searchKey = key;
            fromFirstPage = AhGui.pageNumber(title) <= 1;
            return true;
        }

        boolean timedOut(long now) {
            return awaitingPage && (now < requestedAt || now - requestedAt >= 8_000);
        }

        boolean waitingForMenu(long now, boolean anotherScreenOpen) {
            return awaitingPage && !anotherScreenOpen && !timedOut(now);
        }

        boolean readPage(String incoming, int delay) {
            if (incoming.isEmpty()) {
                read = false;
                return false;
            }
            if (incoming.equals(signature)) {
                if (!awaitingPage) read = true;
                return false;
            }
            signature = incoming;
            read = true;
            awaitingPage = false;
            cooldown = Math.max(cooldown, delay);
            return true;
        }

        void turn(int delay, long now) {
            turns++;
            read = false;
            awaitingPage = true;
            requestedAt = now;
            cooldown = delay;
        }

        void reset() {
            containerId = -1;
            cooldown = 0;
            turns = 0;
            read = false;
            awaitingPage = false;
            fromFirstPage = false;
            requestedAt = 0;
            signature = "";
            searchKey = "";
        }
    }

    static final class WatchPass {
        private Object menu;
        private net.minecraft.world.phys.Vec3 origin;
        private long requestedAt;
        boolean finished;

        void request(net.minecraft.world.phys.Vec3 position, long now) {
            clear();
            origin = position;
            requestedAt = now;
        }

        void observe(Object current, boolean newPass, net.minecraft.world.phys.Vec3 position, long now) {
            if (origin == null) return;
            if (origin.distanceToSqr(position) > 0.25 || now < requestedAt
                || menu == null && now - requestedAt >= 8_000 || menu != null && newPass) clear();
            else menu = current;
        }

        boolean expired(long now) {
            return origin != null && menu == null && (now < requestedAt || now - requestedAt >= 8_000);
        }

        boolean canClose(Object current, net.minecraft.world.phys.Vec3 position) {
            return finished && menu != null && menu == current && origin != null && origin.distanceToSqr(position) <= 0.25;
        }

        void clear() {
            menu = null;
            origin = null;
            requestedAt = 0;
            finished = false;
        }
    }

    // watchlist re-scan state
    private int watchIndex;
    private long nextRescanAt;
    private long idleSince = -1;
    private net.minecraft.world.phys.Vec3 idlePos;

    public AHFlipperModule() {
        super(SeedcrackerAddon.ID + ":ah-flipper", "AH Flipper",
            "Finds DonutSMP AH flips from real or inferred sales (no API key needed). Open .flip for the list.");
    }

    public static AHFlipperModule INSTANCE;

    public FlipEngine engine() {
        return engine;
    }

    public boolean apiActive() {
        return api != null && source.get() != Source.KEYLESS;
    }

    public String apiError() {
        return api == null ? "" : api.lastError();
    }

    @Override
    public void onEnable() {
        INSTANCE = this;
        Path dir = autismclient.AutismClientAddon.FOLDER.toPath().resolve("donut-ah");
        try {
            java.nio.file.Files.createDirectories(dir);
        } catch (Exception e) {
            AutismClientMessaging.sendPrefixed("§cAH Flipper: can't create " + dir + ": " + e.getMessage());
            setEnabledSilently(false);
            return;
        }
        engine = new FlipEngine(dir);
        api = new ApiFlipSource(dir.resolve("api-key.txt"));
        consumeKeySetting();
        alerted.clear();
        attempted.clear();
        buyAttempts = 0;
        reservedCoins = 0;
        pendingBuy = null;
        purchaseExecutor = null;
        buyStatus = "waiting for sales-backed deals";
        resetPaging();
        clearWatchPass();
        scanStatus = "Open /ah or add a watchlist to start learning";
        auctionQuery = "";
        watchIndex = 0;
        idleSince = -1;
        idlePos = null;
        nextRescanAt = 0;
        nextApiPollAt = 0;
        String mode = source.get() == Source.KEYLESS || !api.hasKey()
            ? "keyless - open /ah (or set a watchlist) to start learning"
            : "API + " + (source.get() == Source.BOTH ? "page scans" : "sales feed");
        if (source.get() != Source.KEYLESS && !api.hasKey()) {
            ClientNotify.warning("[AH Flipper] No API key saved - running keyless. Paste one into the setting or use /api.");
        }
        AutismClientMessaging.sendPrefixed("§aAH Flipper: " + mode + ". §7" + engine.knownSales()
            + " sales across " + engine.knownMarkets() + " markets remembered.");
    }

    @Override
    public void onDisable() {
        cancelPurchase();
        clearWatchPass();
        resetPaging();
        if (engine != null) engine.save();
        if (api != null) api.close();
        api = null;
        engine = null;
        if (INSTANCE == this) INSTANCE = null;
    }

    @Override
    public void onGameLeft() {
        cancelPurchase();
        resetPaging();
        clearWatchPass();
        scanStatus = "Join a world, then open /ah to start learning";
        auctionQuery = "";
        idleSince = -1;
        idlePos = null;
        if (engine != null) {
            engine.save();
            engine.resetBook();
        }
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    protected void onOptionValueChanged(String name) {
        if (engine != null) engine.invalidateRank();
        if (name.equals("source") || name.equals("watchlist") || name.equals("auto-page")) {
            clearWatchPass();
            resetPaging();
            if (engine != null) engine.abortScan();
        }
        if (name.equals("auto-buy") || name.equals("buy-budget") || name.equals("max-buys")
            || name.equals("max-buy") || name.equals("min-profit") || name.equals("min-roi")
            || name.equals("min-confidence") || name.equals("min-samples") || name.equals("tax")
            || name.equals("source")) cancelPurchase();
    }

    @Override
    protected void onSettingsReset() {
        cancelPurchase();
        clearWatchPass();
        resetPaging();
        if (engine != null) {
            engine.abortScan();
            engine.invalidateRank();
        }
    }

    @Override
    public boolean onPacketSend(net.minecraft.network.protocol.Packet<?> packet) {
        Minecraft mc = Minecraft.getInstance();
        FlipEngine current = engine;
        if (packet instanceof net.minecraft.network.protocol.game.ServerboundContainerClosePacket) {
            mc.execute(() -> {
                if (engine != current) return;
                cancelPurchase();
                clearWatchPass();
                resetPaging();
                if (engine != null) engine.abortScan();
            });
        } else if (packet instanceof net.minecraft.network.protocol.game.ServerboundChatCommandPacket command) {
            String query = command.command().trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (query.equals("ah") || query.startsWith("ah ")) {
                boolean automatic = query.equals(watchCommand);
                if (automatic) watchCommand = null;
                mc.execute(() -> {
                    if (engine != current) return;
                    if (!automatic) clearWatchPass();
                    auctionQuery = query;
                    resetPaging();
                    if (engine != null) engine.abortScan();
                });
            }
        }
        return false;
    }

    private void cancelPurchase() {
        if (pendingBuy != null && purchaseExecutor != null) purchaseExecutor.cancelFlip();
        pendingBuy = null;
        purchaseExecutor = null;
    }

    private void clearWatchPass() {
        watchCommand = null;
        watchPass.clear();
    }

    /**
     * Keyless ground truth: "<buyer> bought your <item> for $X" chat lines are confirmed sales of
     * our own listings - no API key, no inference. Runs on the network thread, so the store write
     * is marshalled onto the client thread.
     */
    @Override
    public boolean onPacketReceive(net.minecraft.network.protocol.Packet<?> packet) {
        if (engine == null) return false;
        if (!(packet instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket chat)) return false;
        String line = chat.content() == null ? null : chat.content().getString();
        OwnSaleParser.OwnSale sale = OwnSaleParser.parse(line);
        if (sale == null) return false;
        Minecraft mc = Minecraft.getInstance();
        FlipEngine activeEngine = engine;
        var connection = mc.getConnection();
        mc.execute(() -> {
            if (engine != activeEngine || mc.player == null || mc.getConnection() != connection) return;
            String me = mc.player.getGameProfile().name();
            boolean added = activeEngine.ingestOwnSale(me, OwnSaleParser.itemKeyFromDisplayName(sale.itemDisplayName()),
                sale.count(), sale.totalPrice(), System.currentTimeMillis());
            if (added && alerts.get()) {
                AutismClientMessaging.sendPrefixed("§aAH Flipper: recorded your sale of §f"
                    + sale.count() + "x " + sale.itemDisplayName() + "§a for §6$" + String.format("%,d", sale.totalPrice()));
            }
        });
        return false; // never cancel the chat line
    }

    /** Moves a pasted key into the restricted key file and blanks the setting so configs never hold it. */
    private void consumeKeySetting() {
        String key = apiKey.get().trim();
        if (key.isEmpty() || api == null) return;
        try {
            api.saveKey(key);
            ClientNotify.success("[AH Flipper] API key saved.");
        } catch (RuntimeException e) {
            ClientNotify.error("[AH Flipper] Couldn't save API key: " + e.getMessage());
        }
        apiKey.set("");
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (engine == null || mc.player == null || mc.level == null) return;
        consumeKeySetting();
        syncTuning();
        long now = System.currentTimeMillis();

        if (source.get() != Source.KEYLESS && api != null && api.hasKey() && mc.getConnection() != null
            && now >= nextApiPollAt) {
            nextApiPollAt = now + com.autism.seedcracker.util.Humanizer.delayMs(30_000);
            FlipEngine activeEngine = engine;
            ApiFlipSource activeApi = api;
            var connection = mc.getConnection();
            api.poll(sales -> mc.execute(() -> {
                if (engine == activeEngine && api == activeApi && mc.getConnection() == connection) activeEngine.ingestApiSales(sales);
            }), listings -> mc.execute(() -> {
                if (engine == activeEngine && api == activeApi && mc.getConnection() == connection) activeEngine.ingestApiListings(listings);
            }));
        }

        boolean readPages = source.get() != Source.API || api == null || !api.hasKey();
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (watchPass.expired(now)) {
            scanStatus = "Watchlist /ah did not open; waiting for the next interval";
            clearWatchPass();
        }
        if (AhGui.purchasing()) {
            if (engine.scanning()) engine.abortScan();
            resetPaging();
            clearWatchPass();
        } else if (menu == null || menu instanceof InventoryMenu) {
            if (paging.waitingForMenu(now, mc.gui.screen() != null)) {
                scanStatus = "Waiting for the next auction menu";
            } else {
                if (engine.scanning()) {
                    engine.abortScan();
                    scanStatus = "Pass interrupted before the last page; open /ah to rescan";
                }
                resetPaging();
                if (mc.gui.screen() != null || watchPass.menu != null) clearWatchPass();
                if (readPages) tickWatchlist(mc, now);
            }
        } else {
            idleSince = -1;
            if (readPages) tickPages(mc, menu, now);
        }

        List<Opportunity> opportunities = engine.rank(now);
        for (Opportunity o : opportunities) {
            if (!o.listing().seller().equalsIgnoreCase(mc.player.getGameProfile().name())) maybeAlert(o, now);
        }
        tickAutoBuy(mc, opportunities, now);
        if (watchPass.finished) {
            boolean close = !AhGui.purchasing() && watchPass.canClose(mc.player.containerMenu, mc.player.position())
                && mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen
                && screen.getMenu() == mc.player.containerMenu && AhGui.isAuctionPage(mc, mc.player.containerMenu);
            clearWatchPass();
            if (close) mc.player.closeContainer();
        }
    }

    private void syncTuning() {
        FlipEngine.Tuning t = engine.tuning;
        t.minProfit = minProfit.get();
        t.minRoi = minRoi.get();
        t.minSamples = minSamples.get();
        t.minConfidence = minConfidence.get() / 100.0;
        t.maxHoldHours = maxHold.get();
        long cap = com.autism.seedcracker.util.pure.PriceMath.parseAmount(maxBuy.get());
        t.maxBuy = maxBuy.get().isBlank() ? Long.MAX_VALUE : Math.max(0, cap);
        t.salesTaxPercent = taxPercent.get();
        t.askFallback = askFallback.get();
        t.benchLosers = benchLosers.get();
    }

    // ---- keyless: read + auto-page the open /ah screen ----

    private void tickPages(Minecraft mc, AbstractContainerMenu menu, long now) {
        int slots = AhGui.containerSlots(mc, menu);
        if (!AhGui.isAuctionPage(mc, menu)) {
            if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) {
                scanStatus = "Not recognized as /ah: " + AhGui.screenTitle(mc).replace('\n', ' ').replace('\r', ' ');
            } else if (engine.scanning()) scanStatus = "Pass interrupted before the last page; reopen /ah to rescan";
            engine.abortScan();
            resetPaging();
            clearWatchPass();
            return;
        }
        String title = (auctionQuery.isEmpty() ? "" : auctionQuery + " | ") + AhGui.screenTitle(mc);
        boolean newPass = paging.openMenu(menu.containerId, title);
        watchPass.observe(menu, newPass, mc.player.position(), now);
        if (newPass) {
            engine.beginScan(paging.searchKey);
            scanStartPos = mc.player.position();
            firstPageAt = now;
            lastPageObservedAt = 0;
            scanStatus = "Waiting for priced listings on /ah";
        }
        if (paging.cooldown > 0) paging.cooldown--;

        List<Listing> page = new ArrayList<>();
        StringBuilder sig = new StringBuilder();
        for (int i = 0; i < slots; i++) {
            Listing listing = GuiListingReader.read(menu.slots.get(i).getItem(), now);
            if (listing == null) continue;
            page.add(listing);
            sig.append(listing.listingKey()).append(';');
        }
        String signature = sig.toString();
        boolean changed = paging.readPage(signature, com.autism.seedcracker.util.Humanizer.delay(pageDelay.get()));
        if (!page.isEmpty() && (changed || now - lastPageObservedAt >= 1000)) {
            engine.addPage(page);
            lastPageObservedAt = now;
        }
        if (!engine.scanning()) return;
        if (scanStartPos != null && mc.player.position().distanceToSqr(scanStartPos) > 0.25) {
            abortPass("Paused because you moved; reopen /ah to start another pass");
            clearWatchPass();
            return;
        }
        if (paging.timedOut(now)) {
            abortPass("Next page did not arrive; pass incomplete (no sales inferred)");
            return;
        }
        if (page.isEmpty() && !paging.awaitingPage
            && now - Math.max(firstPageAt, lastPageObservedAt) >= 8_000) {
            abortPass("No readable priced listings on this page; check the search or listing format");
            return;
        }
        scanStatus = !autoPage.get() ? "Auto-page is off: reading visible asks only"
            : "Scanning page " + (paging.turns + 1) + ": " + engine.scanBufferSize() + " listings read";
        if (!autoPage.get() || mc.gameMode == null || page.isEmpty() || !paging.read || paging.cooldown > 0 || AhGui.pagedWithin(1000)) return;
        int next = AhGui.findNextPageSlot(menu, slots);
        if (next < 0) {
            if (!paging.fromFirstPage) {
                abortPass("Pass started after page 1; reopen /ah from the first page (no sales inferred)");
                return;
            }
            int count = engine.scanBufferSize();
            int inferred = engine.completeScan(now, mc.player.getGameProfile().name());
            scanStatus = "Pass complete: " + count + " listings, " + inferred + " sales inferred";
            watchPass.finished = true;
            if (alerts.get()) AutismClientMessaging.sendPrefixed("§7[AH Flipper] " + scanStatus + ".");
            return;
        }
        if (paging.turns + 1 >= maxPages.get()) {
            abortPass("Page limit reached: incomplete pass; use a narrower /ah search or raise Max pages");
            return;
        }
        if (!com.autism.seedcracker.util.ActionPacer.tryAction()) return;
        com.autism.seedcracker.util.ContainerMutex.notifyContainerAction();
        mc.gameMode.handleContainerInput(menu.containerId, next, 0,
            net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
        AhGui.notePageTurn();
        paging.turn(com.autism.seedcracker.util.Humanizer.delay(pageDelay.get()), now);
    }

    private void abortPass(String reason) {
        engine.abortScan();
        scanStatus = reason;
        watchPass.finished = true;
    }

    private void resetPaging() {
        paging.reset();
        scanStartPos = null;
        firstPageAt = 0;
        lastPageObservedAt = 0;
    }

    /** AFK-only: re-open /ah for the next watchlist item so passes keep coming without input. */
    private void tickWatchlist(Minecraft mc, long now) {
        String[] items = watchlist.get().split(",");
        List<String> list = new ArrayList<>();
        for (String s : items) if (!s.isBlank()) list.add(s.trim());
        if (list.isEmpty() || mc.gui.screen() != null || mc.getConnection() == null) { idleSince = -1; return; }
        net.minecraft.world.phys.Vec3 pos = mc.player.position();
        if (idlePos == null || pos.distanceToSqr(idlePos) > 0.01) {
            idlePos = pos;
            idleSince = now;
            return;
        }
        if (idleSince < 0) idleSince = now;
        if (now - idleSince < 10_000 || now < nextRescanAt) return;
        if (!com.autism.seedcracker.util.ActionPacer.tryAction()) return;
        // Split the interval across the list so each item is revisited about every rescanMinutes.
        nextRescanAt = now + com.autism.seedcracker.util.Humanizer.delayMs(rescanMinutes.get() * 60_000L / list.size());
        String item = list.get(Math.floorMod(watchIndex++, list.size()));
        watchPass.request(pos, now);
        scanStatus = "Opening watchlist search: " + AhGui.searchName(item);
        String command = "ah " + AhGui.searchName(item);
        watchCommand = command.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        auctionQuery = watchCommand;
        mc.getConnection().sendCommand(command);
    }

    private void tickAutoBuy(Minecraft mc, List<Opportunity> opportunities, long now) {
        if (pendingBuy != null) {
            if (purchaseExecutor != null && purchaseExecutor.flipBusy()) {
                buyStatus = "buying " + pendingBuy.itemKey();
                return;
            }
            if (purchaseExecutor != null && purchaseExecutor.lastFlipBought()) {
                engine.forgetListing(pendingBuy);
                ClientNotify.success("[AH Flipper] Bought " + pendingBuy.count() + "x " + pendingBuy.itemKey()
                    + " for " + PriceCheckModule.compact(pendingBuy.totalPrice()) + ".");
            } else {
                ClientNotify.warning("[AH Flipper] Buy not confirmed; the attempt stays reserved and will not be retried.");
            }
            pendingBuy = null;
            purchaseExecutor = null;
        }
        if (!autoBuy.get()) return;
        if (buyAttempts >= maxBuys.get()) {
            buyStatus = "buy limit reached";
            autoBuy.set(false);
            ClientNotify.success("[AH Flipper] Buy limit reached; scanning continues. Re-enable the module to reset.");
            return;
        }
        long budget = com.autism.seedcracker.util.pure.PriceMath.parseAmount(buyBudget.get());
        if (budget <= 0) {
            if (!buyStatus.equals("set a positive Buy budget")) ClientNotify.warning("[AH Flipper] Set a positive Buy budget before auto-buying.");
            buyStatus = "set a positive Buy budget";
            return;
        }
        long remaining = budget - reservedCoins;
        if (remaining <= 0) {
            buyStatus = "buy budget used";
            autoBuy.set(false);
            ClientNotify.warning("[AH Flipper] Buy budget used; scanning continues.");
            return;
        }
        var module = ModuleLookup.get(SeedcrackerAddon.ID + ":ah-sniper");
        if (!(module instanceof AHSniperModule sniper) || sniper.isEnabled() || sniper.flipBusy() || AhGui.purchasing()) {
            buyStatus = "waiting for AH Sniper";
            return;
        }
        buyStatus = "waiting for sales-backed deals";
        for (Opportunity opportunity : opportunities) {
            Listing listing = opportunity.listing();
            if (attempted.contains(listing.listingKey())
                || opportunity.expectedProfit() < minProfit.get() || opportunity.roiPercent() < minRoi.get()
                || opportunity.confidence() < minConfidence.get() / 100.0
                || listing.totalPrice() > engine.tuning.maxBuy
                || !AhGui.canAutoBuy(opportunity, mc.player.getGameProfile().name(), now, remaining)) continue;
            if (!sniper.tryBuyFlip(listing)) {
                buyStatus = "open /ah or close the other screen";
                return;
            }
            attempted.add(listing.listingKey());
            buyAttempts++;
            reservedCoins += listing.totalPrice();
            pendingBuy = listing;
            purchaseExecutor = sniper;
            buyStatus = "buying " + listing.itemKey();
            engine.abortScan();
            resetPaging();
            return;
        }
    }

    // ---- alerts / paper ----

    private void maybeAlert(Opportunity o, long now) {
        String key = o.listing().listingKey();
        if (!alerted.add(key)) return;
        if (alerted.size() > 2000) alerted.clear();
        if (autoPaper.get() && o.basis() != Basis.ASKS) engine.paper(o, now);
        if (!alerts.get()) return;
        String basis = switch (o.basis()) {
            case SALES -> "§a[sales]";
            case INFERRED -> "§e[inferred]";
            case ASKS -> "§7[asks only]";
        };
        AutismClientMessaging.sendPrefixed(String.format(Locale.ROOT,
            "§6[Flip] %s §f%s x%d §7buy §f%s §7-> sell §f%s §7(+%s, %.0f%% ROI, %.0f%% conf)",
            basis, o.listing().itemKey(), o.listing().count(), PriceCheckModule.compact(o.buyPrice()),
            PriceCheckModule.compact(o.sellPrice()), PriceCheckModule.compact(o.expectedProfit()),
            o.roiPercent(), o.confidence() * 100));
        if (alertSound.get() && Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.playSound(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.4f);
        }
    }

    /** Read-only diagnostics shared by the panel and the flip status command. */
    public List<String> diagnostics() {
        if (engine == null || !isEnabled()) return List.of("Enable AH Flipper in the Trading tab first.");
        long now = System.currentTimeMillis();
        int count = engine.rank(now).size();
        List<String> lines = new ArrayList<>();
        lines.add("Source: " + source.get() + ". " + scanStatus);
        lines.add(engine.liveListings() + " current listings, " + engine.knownSales() + " remembered sales, " + count + " qualifying flips.");
        if (!apiError().isBlank() && source.get() != Source.KEYLESS) lines.add("API feed: " + apiError());
        if (engine.liveListings() == 0) lines.add("No market data yet: open /ah for an item, or set Re-scan watchlist. Enabling alone does not open a blank watchlist.");
        if (count == 0 && engine.liveListings() > 0) lines.addAll(engine.rejections());
        lines.add(autoBuy.get() || pendingBuy != null ? "Auto-buy: " + buyStatus
            : "Auto-buy is OFF: alerts and paper trading only. Nothing is bought automatically.");
        lines.add("Ask-only deals never auto-buy. Sales-backed buying needs a positive budget. Automatic selling is not implemented.");
        return List.copyOf(lines);
    }

    @Override
    public String info() {
        if (engine == null) return "idle";
        if (autoBuy.get() || pendingBuy != null) return buyStatus;
        if (engine.scanning()) return "scanning p" + (paging.turns + 1) + " (" + engine.scanBufferSize() + ")";
        int flips = engine.rank(System.currentTimeMillis()).size();
        if (flips == 0) return engine.liveListings() == 0 ? scanStatus : engine.liveListings() + " listings, none meet filters";
        return flips + " flips, " + engine.knownSales() + " sales";
    }
}
