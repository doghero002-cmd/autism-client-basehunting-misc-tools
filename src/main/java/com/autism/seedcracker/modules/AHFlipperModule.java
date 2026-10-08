package com.autism.seedcracker.modules;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.compat.ClientNotify;
import com.autism.seedcracker.flip.ApiFlipSource;
import com.autism.seedcracker.flip.FlipEngine;
import com.autism.seedcracker.flip.GuiListingReader;
import com.autism.seedcracker.flip.core.FlipModel.Basis;
import com.autism.seedcracker.flip.core.FlipModel.Listing;
import com.autism.seedcracker.flip.core.FlipModel.Opportunity;
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
 * Trading is paper-only: positions settle only on a LATER real/inferred sale at the target, and
 * markets whose paper flips lose get benched. It never buys for you; open .flip to see the list.
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
            + "for each in turn on the interval below so the sale history keeps building. Blank = never opens /ah itself.")
        .group("Keyless"));
    private final IntSetting rescanMinutes = add(new IntSetting("rescan-minutes", "Re-scan every (min)", 6, 2, 60, 1)
        .description("Keep this short: sale detection only trusts passes under 20 minutes apart.")
        .group("Keyless").visibleWhen(() -> !watchlist.get().isBlank()));
    private final IntSetting minProfit = add(new IntSetting("min-profit", "Min profit", 5000, 0, 10_000_000, 500)
        .description("Net profit (after tax) a flip must clear.").group("Filters"));
    private final IntSetting minRoi = add(new IntSetting("min-roi", "Min ROI (%)", 12, 1, 500, 1).group("Filters"));
    private final StringSetting maxBuy = add(new StringSetting("max-buy", "Max buy price", "")
        .description("Ignore listings above this (k/m/b ok). Blank = no cap.").group("Filters"));
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

    // auto-paging state
    private int lastContainerId = -1;
    private int pageCooldown;
    private int pagesTurned;
    private boolean pageRead;
    private String lastPageSignature = "";
    private net.minecraft.world.phys.Vec3 scanStartPos;

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
        resetPaging();
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
        if (engine != null) engine.save();
        if (api != null) api.close();
        api = null;
        engine = null;
        if (INSTANCE == this) INSTANCE = null;
    }

    @Override
    public void onGameLeft() {
        if (engine != null) engine.save();
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
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

        if (source.get() != Source.KEYLESS && api != null && api.hasKey() && now >= nextApiPollAt) {
            nextApiPollAt = now + com.autism.seedcracker.util.Humanizer.delayMs(30_000);
            api.poll(sales -> mc.execute(() -> { if (engine != null) engine.ingestApiSales(sales); }),
                listings -> mc.execute(() -> { if (engine != null) engine.ingestApiListings(listings); }));
        }

        boolean readPages = source.get() != Source.API || api == null || !api.hasKey();
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu == null || menu instanceof InventoryMenu) {
            if (engine.scanning()) engine.abortScan();
            resetPaging();
            if (readPages) tickWatchlist(mc, now);
        } else {
            idleSince = -1;
            if (readPages) tickPages(mc, menu, now);
        }

        for (Opportunity o : engine.rank(now)) maybeAlert(o, now);
    }

    private void syncTuning() {
        FlipEngine.Tuning t = engine.tuning;
        t.minProfit = minProfit.get();
        t.minRoi = minRoi.get();
        t.minSamples = minSamples.get();
        t.minConfidence = minConfidence.get() / 100.0;
        t.maxHoldHours = maxHold.get();
        long cap = com.autism.seedcracker.util.pure.PriceMath.parseAmount(maxBuy.get());
        t.maxBuy = cap > 0 ? cap : Long.MAX_VALUE;
        t.salesTaxPercent = taxPercent.get();
        t.askFallback = askFallback.get();
        t.benchLosers = benchLosers.get();
    }

    // ---- keyless: read + auto-page the open /ah screen ----

    private void tickPages(Minecraft mc, AbstractContainerMenu menu, long now) {
        int slots = AhGui.containerSlots(mc, menu);
        if (!AhGui.isListingPage(slots)) return;
        if (menu.containerId != lastContainerId) {
            lastContainerId = menu.containerId;
            resetPaging();
            String title = mc.gui.screen() == null ? "" : mc.gui.screen().getTitle().getString();
            engine.beginScan(title.toLowerCase(Locale.ROOT).trim());
            scanStartPos = mc.player.position();
        }
        if (pageCooldown > 0) pageCooldown--;

        List<Listing> page = new ArrayList<>();
        StringBuilder sig = new StringBuilder();
        for (int i = 0; i < slots; i++) {
            Listing l = GuiListingReader.read(menu.slots.get(i).getItem(), now);
            if (l == null) continue;
            page.add(l);
            sig.append(l.listingKey()).append(';');
        }
        // In-place paginating plugins keep the containerId; a changed signature = a new page landed.
        String signature = sig.toString();
        if (!page.isEmpty() && !signature.equals(lastPageSignature)) {
            lastPageSignature = signature;
            engine.addPage(page);
            pageRead = true;
        }
        if (!autoPage.get() || !engine.scanning() || mc.gameMode == null) return;

        if (scanStartPos != null && mc.player.position().distanceToSqr(scanStartPos) > 0.25) {
            engine.abortScan();
            return;
        }
        if (!pageRead || pageCooldown > 0 || AhGui.pagedWithin(1000)) return;
        int next = AhGui.findNextPageSlot(menu, slots);
        if (next < 0) {
            int inferred = engine.completeScan(now);
            if (inferred > 0 && alerts.get()) {
                AutismClientMessaging.sendPrefixed("§7[AH Flipper] Pass complete: §f" + inferred + "§7 sales inferred.");
            }
            return;
        }
        if (pagesTurned >= maxPages.get()) {
            engine.abortScan();
            return;
        }
        if (!com.autism.seedcracker.util.ActionPacer.tryAction()) return;
        com.autism.seedcracker.util.ContainerMutex.notifyContainerAction();
        mc.gameMode.handleContainerInput(menu.containerId, next, 0,
            net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
        AhGui.notePageTurn();
        pagesTurned++;
        pageRead = false;
        pageCooldown = com.autism.seedcracker.util.Humanizer.delay(pageDelay.get());
    }

    private void resetPaging() {
        lastContainerId = -1;
        pageCooldown = 0;
        pagesTurned = 0;
        pageRead = false;
        lastPageSignature = "";
        scanStartPos = null;
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
        String item = list.get(watchIndex++ % list.size());
        mc.getConnection().sendCommand("ah " + AhGui.searchName(item));
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

    @Override
    public String info() {
        if (engine == null) return "idle";
        if (engine.scanning()) return "scanning p" + (pagesTurned + 1) + " (" + engine.scanBufferSize() + ")";
        int flips = engine.rank(System.currentTimeMillis()).size();
        return flips + " flips, " + engine.knownSales() + " sales";
    }
}
