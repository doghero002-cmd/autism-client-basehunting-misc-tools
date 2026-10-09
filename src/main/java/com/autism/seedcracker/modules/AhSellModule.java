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
    private static final int MAX_CONFIRM_RETRIES = 3;

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
    }

    @Override
    public void onDisable() {
        currentState = State.IDLE;
        guiActionDelay = 0;
        sellingSlot = -1;
        confirmRetries = 0;
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    private void resetToIdle() {
        currentState = State.IDLE;
        guiActionDelay = 0;
        sellingSlot = -1;
        confirmRetries = 0;
        cooldown = com.autism.seedcracker.util.Humanizer.delay(delay.get());
    }

    /** The lime stained-glass pane confirm slot in the sell GUI (dynamic, not hardcoded). */
    private static int findConfirmSlot(net.minecraft.world.inventory.AbstractContainerMenu handler) {
        for (int i = 0; i < handler.slots.size(); i++) {
            net.minecraft.world.item.ItemStack s = handler.getSlot(i).getItem();
            if (s.isEmpty()) continue;
            net.minecraft.resources.Identifier id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem());
            if (id != null && id.toString().equals("minecraft:lime_stained_glass_pane")) return i;
        }
        return -1;
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
            mc.getConnection().sendCommand("ah sell "
                + com.autism.seedcracker.util.pure.PriceMath.parseAmount(sellPrice.get()));
            currentState = State.WAITING_FOR_GUI;
            guiActionDelay = com.autism.seedcracker.util.Humanizer.delay(10);
            return;
        }

        if (currentState == State.WAITING_FOR_GUI) {
            guiActionDelay--;
            if (mc.gui.screen() instanceof AbstractContainerScreen) {
                currentState = State.CLICKING_CONFIRM;
                guiActionDelay = com.autism.seedcracker.util.Humanizer.delay(2);
            } else if (guiActionDelay <= 0) {
                resetToIdle();
            }
            return;
        }

        if (currentState == State.CLICKING_CONFIRM) {
            guiActionDelay--;
            if (guiActionDelay > 0) return;
            if (mc.gui.screen() instanceof AbstractContainerScreen) {
                int confirm = findConfirmSlot(mc.player.containerMenu);
                if (confirm >= 0) {
                    if (!com.autism.seedcracker.util.ActionPacer.tryAction()) { guiActionDelay = 2; return; }
                    com.autism.seedcracker.util.ContainerMutex.notifyContainerAction(); mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId,
                        confirm, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
                    confirmRetries++;
                    // Verify on the next IDLE pass that the held slot emptied (item was listed).
                    currentState = State.IDLE;
                    pendingVerify = true;
                    return;
                }
                // no confirm button found: bail without listing again
                mc.player.closeContainer();
            }
            resetToIdle();
            return;
        }

        // Verify the previous listing consumed the item before selling another (no double-list).
        if (pendingVerify && sellingSlot >= 0) {
            pendingVerify = false;
            ItemStack held = mc.player.getInventory().getItem(sellingSlot);
            if (!held.isEmpty() && confirmRetries < MAX_CONFIRM_RETRIES) {
                // The item is still in hand - the confirm didn't go through. Re-open the sell GUI
                // for THIS item rather than blindly listing a fresh one.
                com.autism.seedcracker.util.InvSync.select(mc, sellingSlot);
                currentState = State.SELECTING; // command next tick (carried-item lands first)
                guiActionDelay = 1;
                return;
            }
            int s = sellingSlot;
            resetToIdle();
            if (!mc.player.getInventory().getItem(s).isEmpty()) {
                // gave up on this one after retries: move on so we don't loop on it forever.
                // Repeated confirm failures usually mean the server listing limit is hit.
                stuckListings++;
                if (stuckListings >= 3) {
                    autismclient.util.AutismClientMessaging.sendPrefixed(
                        "§e[AH Sell] 3 listings failed in a row - listing limit reached? Disabling.");
                    setEnabledSilently(false);
                    return;
                }
                cooldown = com.autism.seedcracker.util.Humanizer.delay(delay.get());
            } else {
                stuckListings = 0;
            }
            return;
        }

        // IDLE: find the first non-empty hotbar slot and sell it.
        int sellableSlot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty()) { sellableSlot = i; break; }
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
