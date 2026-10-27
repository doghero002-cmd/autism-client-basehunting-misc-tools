/* SPDX-License-Identifier: GPL-3.0-only */
package com.autism.seedcracker.spectre.mechanics;

import com.autism.seedcracker.util.ActionPacer;
import com.autism.seedcracker.util.ContainerMutex;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Vanilla server clicks without local prediction, plus inbound-only evidence. Verified against
 * MC 26.2 ServerGamePacketListenerImpl.handleContainerClick: absent changedSlots predictions leave
 * remote slot mirrors untouched; broadcastChanges therefore sends the actual changed slots back.
 * No fabricated acknowledgement, intentional state-id mismatch, PICKUP swap chain, THROW or drops.
 * ClientInventory.swapInventorySlots is intentionally not used: its local prediction is not proof.
 */
public final class SpawnerInventoryGuard {
    private final ItemStack[] serverItems = new ItemStack[36];
    private final long[] updatedAt = new long[36];
    private long inventoryVersion;
    private AbstractContainerMenu contentMenu;
    private ProtectionInventoryTransaction<ItemStack> transaction;
    private int confirmedDepositCount;

    public SpawnerInventoryGuard() { Arrays.fill(serverItems, ItemStack.EMPTY); }

    public void bind(Minecraft mc) {
        clear();
        // A baseline is not confirmation. Consumers must also require an inbound update after it.
        for (int i = 0; i < 36; i++) serverItems[i] = mc.player.getInventory().getItem(i).copy();
    }

    public void clear() {
        if (transaction != null) transaction.cancel();
        transaction = null;
        contentMenu = null;
        inventoryVersion = 0;
        confirmedDepositCount = 0;
        Arrays.fill(serverItems, ItemStack.EMPTY);
        Arrays.fill(updatedAt, 0);
    }

    public long version() { return inventoryVersion; }
    public boolean hasTransaction() { return transaction != null; }
    public boolean hasContent(AbstractContainerMenu menu) { return contentMenu == menu; }
    public boolean matchesInventory(Minecraft mc, int slot) {
        return slot >= 0 && slot < 36 && ItemStack.matches(serverItems[slot], mc.player.getInventory().getItem(slot));
    }
    public ItemStack item(int slot) { return slot >= 0 && slot < 36 ? serverItems[slot].copy() : ItemStack.EMPTY; }
    public int count(Predicate<ItemStack> match) {
        int total = 0;
        for (ItemStack item : serverItems) if (!item.isEmpty() && match.test(item)) total += item.getCount();
        return total;
    }
    public int updatedSlot(Predicate<ItemStack> match, long since) {
        for (int i = 0; i < 36; i++) if (updatedAt[i] > since && !serverItems[i].isEmpty() && match.test(serverItems[i])) return i;
        return -1;
    }
    public boolean updated(int slot, long since) { return slot >= 0 && slot < 36 && updatedAt[slot] > since; }

    public void playerSlot(int index, ItemStack item) {
        if (index >= 0 && index < 36) {
            serverItems[index] = item.copy();
            updatedAt[index] = ++inventoryVersion;
        }
    }

    public void content(Minecraft mc, Object session, ClientboundContainerSetContentPacket packet, long tick) {
        AbstractContainerMenu menu = menu(mc, packet.containerId());
        if (menu == null || packet.items().size() != menu.slots.size()) return;
        // An inventory-menu refresh must not invalidate the currently owned chest's content proof.
        if (menu == mc.player.containerMenu) contentMenu = menu;
        for (int i = 0; i < packet.items().size(); i++) {
            observe(mc, session, menu, packet.containerId(), packet.stateId(), i, packet.items().get(i), tick);
        }
        cursor(packet.carriedItem().isEmpty());
    }

    public void slot(Minecraft mc, Object session, ClientboundContainerSetSlotPacket packet, long tick) {
        AbstractContainerMenu menu = menu(mc, packet.getContainerId());
        if (menu == null) return;
        observe(mc, session, menu, packet.getContainerId(), packet.getStateId(), packet.getSlot(), packet.getItem(), tick);
    }

    private void observe(Minecraft mc, Object session, AbstractContainerMenu menu, int id, int state,
            int index, ItemStack stack, long tick) {
        if (index < 0 || index >= menu.slots.size()) return;
        var slot = menu.getSlot(index);
        if (slot.container == mc.player.getInventory()) playerSlot(slot.getContainerSlot(), stack);
        if (transaction != null) transaction.observe(session, menu, id, state, index, value(stack), tick);
    }

    public void cursor(boolean empty) { if (transaction != null) transaction.observeCursor(empty); }

    private static AbstractContainerMenu menu(Minecraft mc, int id) {
        if (mc.player == null) return null;
        if (id == mc.player.inventoryMenu.containerId) return mc.player.inventoryMenu;
        return id == mc.player.containerMenu.containerId ? mc.player.containerMenu : null;
    }

    public ProtectionInventoryTransaction.Result poll(Minecraft mc, Object session, long tick) {
        if (transaction == null) return ProtectionInventoryTransaction.Result.CONFIRMED;
        var menu = mc.player.containerMenu;
        var result = transaction.poll(session, menu, menu.containerId, tick);
        if (result == ProtectionInventoryTransaction.Result.CONFIRMED) {
            confirmedDepositCount = transaction.isSwap() ? 0 : transaction.confirmedCount();
            transaction = null;
        }
        return result;
    }

    public int takeConfirmedDepositCount() {
        int count = confirmedDepositCount;
        confirmedDepositCount = 0;
        return count;
    }

    public boolean swapIntoEmptyHotbar(Minecraft mc, Object session, int source, int hotbar, long tick,
            Consumer<Packet<?>> sender) {
        var menu = mc.player.inventoryMenu;
        if (transaction != null || mc.player.containerMenu != menu || source < 9 || source > 35 || hotbar < 0 || hotbar > 8
                || !menu.getCarried().isEmpty() || !mc.player.getInventory().getItem(hotbar).isEmpty()
                || !matchesInventory(mc, source) || !matchesInventory(mc, hotbar)
                || ContainerMutex.containerBusy(mc) || !ActionPacer.tryAction()) return false;
        transaction = new ProtectionInventoryTransaction<>(session, menu, menu.containerId, menu.getStateId(),
            source, Set.of(36 + hotbar), snapshot(menu), ItemStack::isSameItemSameComponents, true, tick, 100);
        ContainerMutex.notifyContainerAction();
        sender.accept(new ServerboundContainerClickPacket(menu.containerId, menu.getStateId(), (short) source, (byte) hotbar,
            ContainerInput.SWAP, Int2ObjectMaps.emptyMap(), HashedStack.EMPTY));
        return true;
    }

    public boolean deposit(Minecraft mc, Object session, AbstractContainerMenu ownedMenu, int source, long tick,
            Predicate<ItemStack> allowed, Consumer<Packet<?>> sender) {
        if (transaction != null || mc.player.containerMenu != ownedMenu || !hasContent(ownedMenu)
                || !(ownedMenu instanceof ChestMenu || ownedMenu instanceof ShulkerBoxMenu)
                || !ownedMenu.getCarried().isEmpty() || source < 0 || source >= ownedMenu.slots.size()) return false;
        var from = ownedMenu.getSlot(source);
        if (from.container != mc.player.getInventory() || from.getItem().isEmpty() || !allowed.test(from.getItem())
                || !matchesInventory(mc, from.getContainerSlot()) || !hasSpace(mc, ownedMenu, from.getItem())
                || !ActionPacer.tryAction()) return false;
        Set<Integer> destinations = new HashSet<>();
        for (int i = 0; i < ownedMenu.slots.size(); i++) {
            if (ownedMenu.getSlot(i).container != mc.player.getInventory()) destinations.add(i);
        }
        transaction = new ProtectionInventoryTransaction<>(session, ownedMenu, ownedMenu.containerId, ownedMenu.getStateId(),
            source, destinations, snapshot(ownedMenu), ItemStack::isSameItemSameComponents, false, tick, 100);
        ContainerMutex.notifyContainerAction();
        sender.accept(new ServerboundContainerClickPacket(ownedMenu.containerId, ownedMenu.getStateId(), (short) source, (byte) 0,
            ContainerInput.QUICK_MOVE, Int2ObjectMaps.emptyMap(), HashedStack.EMPTY));
        return true;
    }

    public static boolean hasSpace(Minecraft mc, AbstractContainerMenu menu, ItemStack item) {
        for (var slot : menu.slots) {
            if (slot.container == mc.player.getInventory() || !slot.mayPlace(item)) continue;
            ItemStack stored = slot.getItem();
            if ((stored.isEmpty() || ItemStack.isSameItemSameComponents(stored, item))
                    && stored.getCount() < Math.min(slot.getMaxStackSize(item), item.getMaxStackSize())) return true;
        }
        return false;
    }

    private static Map<Integer, ProtectionInventoryTransaction.Stack<ItemStack>> snapshot(AbstractContainerMenu menu) {
        if (menu.slots.size() > 128) throw new IllegalArgumentException("Unexpected protection container size");
        Map<Integer, ProtectionInventoryTransaction.Stack<ItemStack>> result = new HashMap<>();
        for (int i = 0; i < menu.slots.size(); i++) result.put(i, value(menu.getSlot(i).getItem()));
        return result;
    }
    private static ProtectionInventoryTransaction.Stack<ItemStack> value(ItemStack stack) {
        return stack.isEmpty() ? ProtectionInventoryTransaction.Stack.empty()
            : new ProtectionInventoryTransaction.Stack<>(stack.copyWithCount(1), stack.getCount());
    }
}
