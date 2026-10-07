package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.EnumSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Shop Buyer.
 *
 * Automatically buys a chosen item from DonutSMP's /shop PvP category. Opens /shop, navigates to
 * the PvP category, clicks the item, then confirms the purchase (the lime confirm pane), and can
 * drop the bought stack. Loops while enabled.
 *
 * Port of the Water Client "ShopBuyer" module to the AUTISM API (Mojang 26.2).
 */
public final class ShopBuyerModule extends Module {

    public enum ItemType {
        OBSIDIAN, END_CRYSTAL, RESPAWN_ANCHOR, GLOWSTONE, TOTEM, ENDER_PEARL, GOLDEN_APPLE, XP_BOTTLE
    }

    private final EnumSetting<ItemType> itemToBuy = add(new EnumSetting<>(
            "item", "Item", ItemType.OBSIDIAN, ItemType.values())
        .description("The PvP-shop item to buy.").group("General"));
    private final BoolSetting autoDrop = add(new BoolSetting("auto-drop", "Auto drop", true)
        .description("Drop the bought stack after purchasing.").group("General"));
    private final autismclient.api.module.IntSetting clickDelay = add(new autismclient.api.module.IntSetting(
            "click-delay", "Click delay (ticks)", 4, 1, 20, 1)
        .description("Base ticks between GUI clicks - jittered and TPS-scaled so the cadence reads human, not a 1-tick macro burst.")
        .group("Safety"));
    private final autismclient.api.module.IntSetting maxPurchases = add(new autismclient.api.module.IntSetting(
            "max-purchases", "Max purchases (0 = endless)", 0, 0, 512, 1)
        .description("Auto-disable after this many confirmed buys (runaway-loop failsafe).")
        .group("Safety"));
    private final BoolSetting debug = add(new BoolSetting("debug", "Debug tracing", false)
        .description("Trace shop-screen phases to chat + /flaglog.").group("Safety"));

    private int delayCounter = 0;
    private boolean inPvpCategory = false;
    private boolean inBuyingScreen = false;
    private int shopCommandWait = 0; // ticks since we sent /shop without it opening
    private int purchases = 0;
    /** Hotbar snapshot taken before the confirm click so the bought slot is identified by DIFF. */
    private int[] preBuyCounts = null;
    private int dropWait = 0;
    private static final int SHOP_RESEND_TICKS = 40; // only re-send /shop after 2s of no open

    public ShopBuyerModule() {
        super(SeedcrackerAddon.ID + ":shop-buyer", "Shop Buyer",
            "Automatically buys a chosen item from the /shop PvP category.");
    }

    @Override
    public void onEnable() {
        delayCounter = 0;
        inPvpCategory = false;
        inBuyingScreen = false;
        shopCommandWait = 0;
        purchases = 0;
        preBuyCounts = null;
        dropWait = 0;
    }

    @Override
    public void onDisable() {
        delayCounter = 0;
        inPvpCategory = false;
        inBuyingScreen = false;
        shopCommandWait = 0;
        preBuyCounts = null;
        dropWait = 0;
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null || mc.getConnection() == null) return;

        com.autism.seedcracker.util.DebugProbe.setEnabled(id(), debug.get());
        com.autism.seedcracker.util.DebugProbe.traceChange(id(), "state",
            (inBuyingScreen ? "BUYING" : inPvpCategory ? "PVP_CAT" : "MAIN")
                + " buys=" + purchases + (preBuyCounts != null ? " dropPending" : ""));

        if (delayCounter > 0) { delayCounter--; return; }

        AbstractContainerMenu handler = mc.player.containerMenu;
        // Not in a container: open /shop. Latch so we don't spam the command every tick - only
        // re-send if the shop hasn't opened after SHOP_RESEND_TICKS.
        if (handler == null || handler == mc.player.inventoryMenu) {
            if (shopCommandWait <= 0) {
                mc.getConnection().sendCommand("shop");
                shopCommandWait = SHOP_RESEND_TICKS;
            } else {
                shopCommandWait--;
            }
            delayCounter = com.autism.seedcracker.util.Humanizer.delay(clickDelay.get());
            resetState();
            return;
        }
        shopCommandWait = 0; // a container opened: reset the latch

        if (isBuyingScreen(handler)) {
            handleBuyingScreen(mc, handler);
            return;
        } else if (isPvpCategoryScreen(handler)) {
            handlePvpCategory(mc, handler);
            return;
        } else if (isMainShopScreen(handler)) {
            handleMainShop(mc, handler);
            return;
        }
        resetState();
    }

    private void resetState() {
        inPvpCategory = false;
        inBuyingScreen = false;
    }

    private boolean isMainShopScreen(AbstractContainerMenu handler) {
        return slotIs(handler, 13, Items.TOTEM_OF_UNDYING) && !isBuyingScreen(handler);
    }

    private boolean isPvpCategoryScreen(AbstractContainerMenu handler) {
        return slotIs(handler, 9, Items.OBSIDIAN) || slotIs(handler, 10, Items.END_CRYSTAL)
            || slotIs(handler, 11, Items.RESPAWN_ANCHOR) || slotIs(handler, 12, Items.GLOWSTONE);
    }

    private boolean isBuyingScreen(AbstractContainerMenu handler) {
        for (int i = 0; i < handler.slots.size(); i++) {
            if (isLimePane(handler.getSlot(i).getItem())) return true;
        }
        return false;
    }

    /** The lime stained-glass pane confirm button (matched by registry id - the typed dyed
     *  block collections don't expose a plain Items constant in 26.2). */
    public static boolean isLimePane(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        net.minecraft.resources.Identifier id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && id.toString().equals("minecraft:lime_stained_glass_pane");
    }

    private boolean slotIs(AbstractContainerMenu handler, int slot, Item item) {
        if (slot < 0 || slot >= handler.slots.size()) return false;
        return handler.getSlot(slot).getItem().is(item);
    }

    private void click(Minecraft mc, AbstractContainerMenu handler, int slot) {
        if (!com.autism.seedcracker.util.ActionPacer.tryAction()) { delayCounter = 2; return; } // global budget
        com.autism.seedcracker.util.ContainerMutex.notifyContainerAction(); mc.gameMode.handleContainerInput(handler.containerId, slot, 0, ContainerInput.PICKUP, mc.player);
        delayCounter = com.autism.seedcracker.util.Humanizer.delay(clickDelay.get());
    }

    private void handleMainShop(Minecraft mc, AbstractContainerMenu handler) {
        click(mc, handler, 13); // totem slot -> PvP category
        inPvpCategory = true;
    }

    private void handlePvpCategory(Minecraft mc, AbstractContainerMenu handler) {
        int slot = itemSlot(itemToBuy.get());
        if (slot != -1 && correctItem(handler, slot, itemToBuy.get())) {
            click(mc, handler, slot);
        }
    }

    private void handleBuyingScreen(Minecraft mc, AbstractContainerMenu handler) {
        // A pending drop from the last confirm: diff the inventory against the pre-buy snapshot so
        // we throw the stack that actually GREW, never an unrelated first-non-empty slot.
        if (preBuyCounts != null) {
            if (dropWait-- > 0) return;
            int bought = findBoughtSlotByDiff(mc);
            if (bought >= 0 && autoDrop.get()) {
                if (!com.autism.seedcracker.util.ActionPacer.tryAction()) { dropWait = 2; return; } // keep snapshot, retry
                com.autism.seedcracker.util.ContainerMutex.notifyContainerAction();
                mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, bought, 0,
                    net.minecraft.world.inventory.ContainerInput.THROW, mc.player);
                delayCounter = com.autism.seedcracker.util.Humanizer.delay(clickDelay.get());
            }
            preBuyCounts = null;
            if (bought >= 0) countPurchase();
            return;
        }
        // Prefer a full 64-count confirm pane; else any lime confirm pane.
        int confirm = -1;
        for (int i = 0; i < handler.slots.size(); i++) {
            if (isLimePane(handler.getSlot(i).getItem())
                && handler.getSlot(i).getItem().getCount() == 64) { confirm = i; break; }
        }
        if (confirm < 0) {
            for (int i = 0; i < handler.slots.size(); i++) {
                if (isLimePane(handler.getSlot(i).getItem())) { confirm = i; break; }
            }
        }
        if (confirm >= 0) {
            snapshotInventory(mc);
            dropWait = com.autism.seedcracker.util.Humanizer.delay(2); // let the server move the item
            click(mc, handler, confirm);
        }
    }

    private void countPurchase() {
        purchases++;
        int cap = maxPurchases.get();
        if (cap > 0 && purchases >= cap) {
            com.autism.seedcracker.compat.ClientNotify.success("[Shop Buyer] Bought " + purchases + " - done.");
            setEnabledSilently(false);
        }
    }

    @Override
    public String info() {
        int cap = maxPurchases.get();
        return purchases + (cap > 0 ? "/" + cap : "");
    }

    /** Player-inventory container-slot counts (36 slots of inventoryMenu: 9-44). */
    private void snapshotInventory(Minecraft mc) {
        preBuyCounts = new int[36];
        for (int i = 0; i < 36; i++) {
            preBuyCounts[i] = mc.player.inventoryMenu.getSlot(9 + i).getItem().getCount();
        }
    }

    /** inventoryMenu slot index whose count grew since the snapshot, or -1. */
    private int findBoughtSlotByDiff(Minecraft mc) {
        if (preBuyCounts == null) return -1;
        for (int i = 0; i < 36; i++) {
            int now = mc.player.inventoryMenu.getSlot(9 + i).getItem().getCount();
            if (now > preBuyCounts[i]) return 9 + i;
        }
        return -1;
    }

    private int itemSlot(ItemType type) {
        return switch (type) {
            case OBSIDIAN -> 9;
            case END_CRYSTAL -> 10;
            case RESPAWN_ANCHOR -> 11;
            case GLOWSTONE -> 12;
            case TOTEM -> 13;
            case ENDER_PEARL -> 14;
            case GOLDEN_APPLE -> 15;
            case XP_BOTTLE -> 16;
        };
    }

    private boolean correctItem(AbstractContainerMenu handler, int slot, ItemType type) {
        Item item = switch (type) {
            case OBSIDIAN -> Items.OBSIDIAN;
            case END_CRYSTAL -> Items.END_CRYSTAL;
            case RESPAWN_ANCHOR -> Items.RESPAWN_ANCHOR;
            case GLOWSTONE -> Items.GLOWSTONE;
            case TOTEM -> Items.TOTEM_OF_UNDYING;
            case ENDER_PEARL -> Items.ENDER_PEARL;
            case GOLDEN_APPLE -> Items.GOLDEN_APPLE;
            case XP_BOTTLE -> Items.EXPERIENCE_BOTTLE;
        };
        return slotIs(handler, slot, item);
    }
}
