package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;

/**
 * AH Sell.
 *
 * Automatically sells items from your hotbar on DonutSMP's auction house. Finds the first
 * non-empty hotbar slot, runs {@code /ah sell <price>}, then clicks the confirm button in the
 * sell GUI. Loops with a configurable delay so you can list a whole hotbar.
 *
 * Port of the Water Client "AhSell" module to the AUTISM API (Mojang 26.2).
 */
public final class AhSellModule extends Module {

    private final StringSetting sellPrice = add(new StringSetting("sell-price", "Sell Price", "15000")
        .description("The /ah sell price to list each item at.").group("General"));
    private final IntSetting delay = add(new IntSetting("delay", "Delay (ticks)", 20, 5, 100, 1)
        .description("Ticks between each sell action.").group("General"));
    private final StringSetting itemFilter = add(new StringSetting("items", "Items", "").group("Target"));
    private final autismclient.api.module.BoolSetting allowAll = add(new autismclient.api.module.BoolSetting(
        "allow-all", "Sell all hotbar items", false).group("Target"));
    private final autismclient.api.module.BoolSetting debug = add(new autismclient.api.module.BoolSetting(
            "debug", "Debug tracing", false)
        .description("Trace sell-state transitions to chat + /flaglog.").group("General"));

    private int cooldown = 0;
    private State currentState = State.IDLE;
    private int guiActionDelay = 0;
    private int sellingSlot = -1;   // hotbar slot we ran /ah sell on (verify the GUI consumed it)
    private int confirmRetries = 0;
    private boolean pendingVerify = false; // check the sold slot emptied before listing another
    private int stuckListings = 0; // consecutive gave-up listings (3 = probable listing limit)
    private ItemStack sellingStack = ItemStack.EMPTY;
    private double activePrice;
    private long menuDeadline;
    private long verifyDeadline;

    private enum State { IDLE, SELECTING, WAITING_FOR_GUI, CLICKING_CONFIRM }

    public AhSellModule() {
        super(SeedcrackerAddon.ID + ":ah-sell", "AH Sell",
            "Automatically /ah sell's items from your hotbar at a set price.");
    }

    @Override
    public void onEnable() {
        cooldown = 0;
        currentState = State.IDLE;
        guiActionDelay = 0;
        sellingSlot = -1;
        confirmRetries = 0;
        stuckListings = 0;
        pendingVerify = false;
        sellingStack = ItemStack.EMPTY;
        if (itemFilter.get().isBlank() && !allowAll.get()) {
            autismclient.util.AutismClientMessaging.sendPrefixed("[AH Sell] Configure Items or explicitly enable all-hotbar sales.");
            setEnabledSilently(false);
        }
    }

    @Override
    public void onDisable() {
        currentState = State.IDLE;
        guiActionDelay = 0;
        sellingSlot = -1;
        confirmRetries = 0;
        pendingVerify = false;
        sellingStack = ItemStack.EMPTY;
    }

    @Override protected void onOptionValueChanged(String name) {
        if ((name.equals("items") || name.equals("allow-all") || name.equals("sell-price"))
            && (currentState != State.IDLE || pendingVerify)) {
            onDisable();
            setEnabledSilently(false);
        }
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
        onDisable();
    }

    private void resetToIdle() {
        currentState = State.IDLE;
        guiActionDelay = 0;
        sellingSlot = -1;
        confirmRetries = 0;
        cooldown = com.autism.seedcracker.util.Humanizer.delay(delay.get());
        sellingStack = ItemStack.EMPTY;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.getConnection() == null) return;

        com.autism.seedcracker.util.DebugProbe.setEnabled(id(), debug.get());
        com.autism.seedcracker.util.DebugProbe.traceChange(id(), "state",
            currentState + " slot=" + sellingSlot + " retries=" + confirmRetries + " stuck=" + stuckListings);

        if (cooldown > 0) { cooldown--; return; }

        if (currentState == State.SELECTING) {
            // We selected the slot last tick; the carried-item packet has landed. Send the command.
            guiActionDelay--;
            if (guiActionDelay > 0) return;
            ItemStack current = mc.player.getMainHandItem();
            if (mc.gui.screen() != null || mc.player.getInventory().getSelectedSlot() != sellingSlot
                || !ItemStack.isSameItemSameComponents(current, sellingStack) || current.getCount() != sellingStack.getCount()
                || com.autism.seedcracker.util.pure.PriceMath.parseAmount(sellPrice.get()) != activePrice) {
                onDisable();
                setEnabledSilently(false);
                return;
            }
            mc.getConnection().sendCommand("ah sell " + activePrice);
            currentState = State.WAITING_FOR_GUI;
            menuDeadline = System.currentTimeMillis() + 8000;
            guiActionDelay = com.autism.seedcracker.util.Humanizer.delay(10);
            return;
        }

        if (currentState == State.WAITING_FOR_GUI) {
            guiActionDelay--;
            if (mc.gui.screen() instanceof AbstractContainerScreen) {
                if (!com.autism.seedcracker.market.AhGui.saleConfirmation(
                    com.autism.seedcracker.market.AhGui.containerSlots(mc, mc.player.containerMenu),
                    com.autism.seedcracker.market.AhGui.screenTitle(mc))) {
                    onDisable();
                    setEnabledSilently(false);
                    return;
                }
                currentState = State.CLICKING_CONFIRM;
                guiActionDelay = com.autism.seedcracker.util.Humanizer.delay(2);
            } else if (System.currentTimeMillis() >= menuDeadline) {
                onDisable();
                setEnabledSilently(false);
            }
            return;
        }

        if (currentState == State.CLICKING_CONFIRM) {
            guiActionDelay--;
            if (guiActionDelay > 0) return;
            if (mc.gui.screen() instanceof AbstractContainerScreen) {
                var menu = mc.player.containerMenu;
                int slots = com.autism.seedcracker.market.AhGui.containerSlots(mc, menu);
                if (!com.autism.seedcracker.market.AhGui.saleConfirmation(slots, com.autism.seedcracker.market.AhGui.screenTitle(mc))) {
                    onDisable(); setEnabledSilently(false); return;
                }
                String expected = com.autism.seedcracker.flip.GuiListingReader.itemKey(sellingStack);
                int preview = -1;
                for (int slot = 0; slot < slots; slot++) {
                    ItemStack candidate = menu.slots.get(slot).getItem();
                    if (!candidate.isEmpty() && candidate.getCount() == sellingStack.getCount()
                        && expected.equals(com.autism.seedcracker.flip.GuiListingReader.itemKey(candidate))) { preview = slot; break; }
                }
                int confirm = preview < 0 ? -1 : com.autism.seedcracker.market.AhGui.findConfirmSlot(menu, slots, preview);
                if (confirm >= 0) {
                    double shown = com.autism.seedcracker.market.ListingPriceParser.parse(menu.slots.get(confirm).getItem());
                    if (shown > 0 && Math.round(shown) != Math.round(activePrice)) {
                        onDisable(); setEnabledSilently(false); return;
                    }
                    if (!com.autism.seedcracker.util.ActionPacer.tryAction()) { guiActionDelay = 2; return; }
                    com.autism.seedcracker.util.ContainerMutex.notifyContainerAction(); mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId,
                        confirm, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
                    confirmRetries++;
                    // Verify on the next IDLE pass that the held slot emptied (item was listed).
                    currentState = State.IDLE;
                    pendingVerify = true;
                    verifyDeadline = System.currentTimeMillis() + 8000;
                    return;
                }
                // no confirm button found: bail without listing again
                onDisable();
                setEnabledSilently(false);
                return;
            }
            resetToIdle();
            return;
        }

        // Verify the previous listing consumed the item before selling another (no double-list).
        if (pendingVerify && sellingSlot >= 0) {
            ItemStack held = mc.player.getInventory().getItem(sellingSlot);
            if (!held.isEmpty()) {
                if (System.currentTimeMillis() < verifyDeadline) return;
                autismclient.util.AutismClientMessaging.sendPrefixed("[AH Sell] Listing not confirmed; stopped without retrying.");
                onDisable();
                setEnabledSilently(false);
                return;
            }
            pendingVerify = false;
            resetToIdle();
            stuckListings = 0;
            return;
        }

        if (mc.gui.screen() != null || !mc.player.inventoryMenu.getCarried().isEmpty()) return;

        // IDLE: find the first non-empty hotbar slot and sell it.
        int sellableSlot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && com.autism.seedcracker.market.AhGui.saleItemAllowed(
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), itemFilter.get(), allowAll.get())) {
                sellableSlot = i; break;
            }
        }

        if (sellableSlot != -1) {
            // PriceMath accepts "15000", "15,000" and "1.5m" alike; -1/0 = not a price.
            if (com.autism.seedcracker.util.pure.PriceMath.parseAmount(sellPrice.get()) <= 0) {
                autismclient.util.AutismClientMessaging.sendPrefixed("§c[AH Sell] Invalid sell price. Disabling.");
                setEnabledSilently(false);
                return;
            }
            com.autism.seedcracker.util.InvSync.select(mc, sellableSlot);
            sellingSlot = sellableSlot;
            sellingStack = mc.player.getInventory().getItem(sellingSlot).copy();
            activePrice = com.autism.seedcracker.util.pure.PriceMath.parseAmount(sellPrice.get());
            confirmRetries = 0;
            currentState = State.SELECTING; // send the command next tick (carried-item lands first)
            guiActionDelay = com.autism.seedcracker.util.Humanizer.delay(1);
        } else {
            // Nothing left to sell.
            autismclient.util.AutismClientMessaging.sendPrefixed("§a[AH Sell] No more items to sell.");
            setEnabledSilently(false);
        }
    }
}
