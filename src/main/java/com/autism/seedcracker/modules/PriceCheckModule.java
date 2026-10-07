package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.market.ListingPriceParser;
import com.autism.seedcracker.market.PriceTracker;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Price Check - your own market knowledge instead of trusting any one flipper.
 *
 * While you browse /ah normally, every listing with a readable price line is recorded into a
 * rolling per-item history (price-history.json, unit prices). Over time that builds a personal
 * market database; listings far under the recent median get highlighted in chat as deals.
 * The .price command charts any item's history and stats.
 *
 * Passive by design: no clicks, no commands, no packets - it just reads what the server already
 * sent you, so there is nothing for an anticheat to flag.
 */
public final class PriceCheckModule extends Module {

    private final BoolSetting dealAlerts = add(new BoolSetting("deal-alerts", "Deal alerts", true)
        .description("Chat ping when a listing is this far under the recent median.").group("Alerts"));
    private final IntSetting dealPercent = add(new IntSetting("deal-percent", "Deal threshold (%)", 30, 5, 90, 5)
        .description("How far below the 24h median a listing must be to count as a deal.")
        .group("Alerts").visibleWhen(() -> dealAlerts.get()));
    private final IntSetting minSamples = add(new IntSetting("min-samples", "Min samples", 8, 3, 100, 1)
        .description("Don't alert until this many price samples exist for the item (avoids noise on fresh data).")
        .group("Alerts").visibleWhen(() -> dealAlerts.get()));

    public enum ScanMode { OFF, TRACK_ITEM, LOWEST_PRICE }

    private final autismclient.api.module.EnumSetting<ScanMode> scanMode = add(new autismclient.api.module.EnumSetting<>(
            "scan-mode", "Auto scan", ScanMode.OFF, ScanMode.values())
        .description("OFF = record only what you browse. TRACK_ITEM = open /ah <item> yourself, then it pages through for you. "
            + "LOWEST_PRICE = open /ah sorted by lowest price, then it pages through. It never opens /ah by itself and stops "
            + "if you move or close the screen.")
        .group("Auto Scan"));
    private final autismclient.api.module.StringSetting trackItem = add(new autismclient.api.module.StringSetting(
            "track-item", "Tracked item", "")
        .description("TRACK_ITEM: only record this item id (e.g. minecraft:elytra); blank = record everything seen.")
        .group("Auto Scan").visibleWhen(() -> scanMode.get() == ScanMode.TRACK_ITEM));
    private final IntSetting pageDelay = add(new IntSetting("page-delay", "Page delay (ticks)", 40, 20, 200, 5)
        .description("Base delay between page turns (jittered, AC-scaled). Lower = faster, more noticeable.")
        .group("Auto Scan").visibleWhen(() -> scanMode.get() != ScanMode.OFF));
    private final IntSetting maxPages = add(new IntSetting("max-pages", "Max pages per scan", 10, 1, 50, 1)
        .description("Stop after this many page turns (then reopen /ah to scan again).")
        .group("Auto Scan").visibleWhen(() -> scanMode.get() != ScanMode.OFF));
    private final IntSetting reopenMinutes = add(new IntSetting("reopen-minutes", "Re-scan every (min)", 10, 0, 120, 1)
        .description("TRACK_ITEM: re-open /ah <tracked item> on this interval (jittered) so the chart keeps building "
            + "while you're AFK. Only fires after you've stood still 10s with no screen open. 0 = never open /ah itself.")
        .group("Auto Scan").visibleWhen(() -> scanMode.get() == ScanMode.TRACK_ITEM));

    private int scanCooldown = 0;
    private long lastAlertAt = 0;
    /** containerId+slot keys already recorded this screen (don't resample every tick). */
    private final java.util.Set<Long> seenThisScreen = new java.util.HashSet<>();
    private int lastContainerId = -1;

    public PriceCheckModule() {
        super(SeedcrackerAddon.ID + ":price-check", "Price Check",
            "Builds a personal AH price database while you browse, and flags underpriced listings.");
    }

    @Override
    public void onEnable() {
        PriceTracker.load();
        seenThisScreen.clear();
        lastContainerId = -1;
    }

    @Override
    public void onDisable() {
        PriceTracker.save(true);
    }

    @Override
    public void onGameLeft() {
        PriceTracker.save(true);
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (pageCooldown > 0) pageCooldown--;
        if (scanCooldown > 0) { scanCooldown--; return; }
        scanCooldown = 10; // scan twice a second; listings don't change faster

        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu == null || menu instanceof InventoryMenu) {
            seenThisScreen.clear();
            lastContainerId = -1;
            resetScan();
            tickAutoReopen(mc);
            return;
        }
        idleSince = -1;
        if (menu.containerId != lastContainerId) {
            seenThisScreen.clear();
            lastContainerId = menu.containerId;
            resetScan(); // a freshly opened /ah starts a new scan
        }

        int containerSlots = com.autism.seedcracker.market.AhGui.containerSlots(mc, menu);
        if (!com.autism.seedcracker.market.AhGui.isListingPage(containerSlots)) return;

        String trackOnly = scanMode.get() == ScanMode.TRACK_ITEM && !trackItem.get().isBlank()
            ? PriceTracker.normalize(trackItem.get()) : null;
        int itemsSeen = 0, parsed = 0, recordedThisPass = 0;
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (stack.isEmpty()) continue;
            itemsSeen++;
            double price = ListingPriceParser.parse(stack);
            if (price <= 0) continue;
            parsed++;
            // Key includes stack identity: AH plugins paginate IN-PLACE (same containerId, slots
            // rewritten), so a slot-only key never recorded page 2 onward.
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null) continue;
            String itemId = PriceTracker.normalize(id.toString());
            if (trackOnly != null && !trackOnly.equals(itemId)) continue;
            int identity = java.util.Objects.hash(id, stack.getCount(), (long) price);
            long key = ((long) identity << 16) | (i & 0xFFFF);
            if (!seenThisScreen.add(key)) continue;
            // record() stores price / count, so a 64-stack at 6.4k and a single at 100 compare per unit.
            PriceTracker.record(itemId, price, stack.getCount());
            recordedThisPass++;

            if (dealAlerts.get()) maybeAlert(itemId, stack, price);
        }
        if (recordedThisPass > 0) pageHadData = true;
        tickAutoPage(mc, menu, containerSlots);

        // Fail loud: a full AH page where nothing parses means the server changed its listing
        // format - say so once instead of silently recording no data forever.
        if (itemsSeen >= 20 && parsed == 0) {
            if (++unparsedPages >= 3 && !formatWarned) {
                formatWarned = true;
                com.autism.seedcracker.util.FlagLog.warn("MARKET", "PriceCheck",
                    "no prices parsed on " + unparsedPages + " full AH pages - listing format may have changed");
                com.autism.seedcracker.compat.ClientNotify.warning(
                    "Price Check: couldn't read any listing prices - AH format may have changed.");
            }
        } else if (parsed > 0) {
            unparsedPages = 0;
            formatWarned = false;
        }
    }

    private int unparsedPages = 0;
    private boolean formatWarned = false;

    // ---- auto paging ----
    private int pageCooldown = 0;
    private int pagesTurned = 0;
    private boolean pageHadData = false;
    private net.minecraft.world.phys.Vec3 scanStartPos = null;

    /**
     * Turns to the next AH page once the current page has been recorded. Only opens /ah itself via
     * the TRACK_ITEM re-scan timer; otherwise the user opens it and this just pages.
     * Stops on: max pages, no next button, the player moving, or the screen closing.
     */
    private void tickAutoPage(Minecraft mc, AbstractContainerMenu menu, int containerSlots) {
        if (scanMode.get() == ScanMode.OFF || mc.gameMode == null) return;
        if (scanStartPos == null) {
            scanStartPos = mc.player.position();
            pagesTurned = 0;
        }
        // Moving = the player wants control back.
        if (mc.player.position().distanceToSqr(scanStartPos) > 0.25) {
            stopScan("you moved");
            return;
        }
        if (pagesTurned >= maxPages.get()) {
            stopScan("scanned " + pagesTurned + " pages");
            return;
        }
        if (pageCooldown > 0 || !pageHadData || com.autism.seedcracker.market.AhGui.pagedWithin(1000)) return;

        int next = com.autism.seedcracker.market.AhGui.findNextPageSlot(menu, containerSlots);
        if (next < 0) {
            stopScan("last page reached");
            return;
        }
        if (!com.autism.seedcracker.util.ActionPacer.tryAction()) return;
        com.autism.seedcracker.util.ContainerMutex.notifyContainerAction();
        mc.gameMode.handleContainerInput(menu.containerId, next, 0,
            net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
        com.autism.seedcracker.market.AhGui.notePageTurn();
        pagesTurned++;
        pageHadData = false;
        pageCooldown = com.autism.seedcracker.util.Humanizer.delay(pageDelay.get());
    }

    private void resetScan() {
        scanStartPos = null;
        pagesTurned = 0;
        pageHadData = false;
        pageCooldown = 0;
    }

    // ---- periodic re-open (TRACK_ITEM only) ----
    private long idleSince = -1;
    private long nextReopenAt = 0;
    private net.minecraft.world.phys.Vec3 idlePos = null;

    /** Re-runs "/ah <item>" on a jittered interval while the player is idle with no screen open. */
    private void tickAutoReopen(Minecraft mc) {
        if (scanMode.get() != ScanMode.TRACK_ITEM || reopenMinutes.get() <= 0) return;
        String raw = trackItem.get().trim();
        if (raw.isEmpty() || mc.gui.screen() != null || mc.getConnection() == null) { idleSince = -1; return; }
        long now = System.currentTimeMillis();
        net.minecraft.world.phys.Vec3 pos = mc.player.position();
        if (idlePos == null || pos.distanceToSqr(idlePos) > 0.01) {
            idlePos = pos;
            idleSince = now;
            return;
        }
        if (idleSince < 0) idleSince = now;
        if (now - idleSince < 10_000) return; // only while AFK, so it never steals the screen mid-play
        if (nextReopenAt == 0) nextReopenAt = now; // first idle period: scan right away
        if (now < nextReopenAt) return;
        if (!com.autism.seedcracker.util.ActionPacer.tryAction()) return;
        nextReopenAt = now + com.autism.seedcracker.util.Humanizer.delayMs(reopenMinutes.get() * 60_000L);
        mc.getConnection().sendCommand("ah " + com.autism.seedcracker.market.AhGui.searchName(raw));
    }

    private void stopScan(String why) {
        if (scanStartPos != null && pagesTurned > 0) {
            com.autism.seedcracker.compat.ClientNotify.success("[Price Check] Auto scan stopped: " + why
                + " (" + pagesTurned + " pages). Open .price to see the charts.");
        }
        scanStartPos = null;
        pagesTurned = Integer.MAX_VALUE / 2; // stay stopped until the screen is reopened
    }

    private void maybeAlert(String itemId, ItemStack stack, double totalPrice) {
        PriceTracker.Stats stats = PriceTracker.stats(itemId);
        if (stats == null || stats.samples() < minSamples.get()) return;
        double unit = totalPrice / Math.max(1, stack.getCount());
        double score = PriceTracker.dealScore(itemId, unit);
        if (Double.isNaN(score) || score * 100 < dealPercent.get()) return;
        long now = System.currentTimeMillis();
        if (now - lastAlertAt < 3000) return; // one alert per 3s max
        lastAlertAt = now;
        com.autism.seedcracker.compat.ClientNotify.success(String.format(
            "[Price Check] DEAL: %s at %s/ea (%.0f%% under 24h median %s, %d samples)",
            stack.getHoverName().getString(), compact(unit), score * 100,
            compact(stats.recentMedian()), stats.samples()));
    }

    public static String compact(double v) {
        if (v >= 1_000_000_000) return String.format("%.2fb", v / 1_000_000_000);
        if (v >= 1_000_000) return String.format("%.2fm", v / 1_000_000);
        if (v >= 1_000) return String.format("%.1fk", v / 1_000);
        return String.format("%.0f", v);
    }

    @Override
    public String info() {
        if (scanStartPos != null && pagesTurned > 0 && pagesTurned < Integer.MAX_VALUE / 2) {
            return "scanning p" + (pagesTurned + 1) + "/" + maxPages.get();
        }
        int items = PriceTracker.knownItems().size();
        return items > 0 ? items + " items tracked" : "learning";
    }
}
