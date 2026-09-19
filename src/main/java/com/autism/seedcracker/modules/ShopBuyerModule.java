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

    private int delayCounter = 0;
    private boolean inPvpCategory = false;
    private boolean inBuyingScreen = false;
    private int shopCommandWait = 0; // ticks since we sent /shop without it opening
    private static final int DELAY = 1;
    private static final int SHOP_RESEND_TICKS = 40; // only re-send /shop after 2s of no open

    public ShopBuyerModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":shop-buyer", "Shop Buyer", category,
            "Automatically buys a chosen item from the /shop PvP category.");
    }

    @Override
    public void onEnable() {
        delayCounter = 0;
        inPvpCategory = false;
        inBuyingScreen = false;
        shopCommandWait = 0;
    }

    @Override
    public void onDisable() {
        delayCounter = 0;
        inPvpCategory = false;
        inBuyingScreen = false;
        shopCommandWait = 0;
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null || mc.getConnection() == null) return;

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
            delayCounter = DELAY;
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
        com.autism.seedcracker.util.ContainerMutex.notifyContainerAction(); mc.gameMode.handleContainerInput(handler.containerId, slot, 0, ContainerInput.PICKUP, mc.player);
        delayCounter = DELAY;
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
        // Prefer a full 64-count confirm pane; else any lime confirm pane.
        for (int i = 0; i < handler.slots.size(); i++) {
            if (isLimePane(handler.getSlot(i).getItem())
                && handler.getSlot(i).getItem().getCount() == 64) {
                click(mc, handler, i);
                return;
            }
        }
        for (int i = 0; i < handler.slots.size(); i++) {
            if (isLimePane(handler.getSlot(i).getItem())) {
                click(mc, handler, i);
                if (autoDrop.get()) {
                    // Drop the just-bought stack from its slot (THROW = slot-based drop), not the
                    // unrelated currently-held stack.
                    int bought = findBoughtSlot(mc, handler);
                    if (bought >= 0) {
                        com.autism.seedcracker.util.ContainerMutex.notifyContainerAction(); mc.gameMode.handleContainerInput(handler.containerId, bought, 0,
                            net.minecraft.world.inventory.ContainerInput.THROW, mc.player);
                    }
                }
                return;
            }
        }
    }

    /** The hotbar/inventory slot the bought item just landed in (first non-empty hotbar slot). */
    private int findBoughtSlot(Minecraft mc, AbstractContainerMenu handler) {
        for (int i = 0; i < handler.slots.size(); i++) {
            if (!handler.getSlot(i).getItem().isEmpty()) return i;
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
