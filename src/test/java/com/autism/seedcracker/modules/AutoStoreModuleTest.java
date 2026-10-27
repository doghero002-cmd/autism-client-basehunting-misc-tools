package com.autism.seedcracker.modules;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AutoStoreModuleTest {
    @BeforeAll static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Items.STONE.builtInRegistryHolder().bindComponents(net.minecraft.core.component.DataComponents.COMMON_ITEM_COMPONENTS);
        Items.DIRT.builtInRegistryHolder().bindComponents(net.minecraft.core.component.DataComponents.COMMON_ITEM_COMPONENTS);
    }

    @Test void emptyOutputOnlySlotsAreNotDepositCapacity() throws Exception {
        Slot output = new Slot(new SimpleContainer(1), 0, 0, 0) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
        };
        assertFalse(hasRoom(new Menu(output), new ItemStack(Items.STONE)));
    }

    @Test void stackCapacityUsesTheSlotsLimitNotOnlyTheItemsLimit() throws Exception {
        SimpleContainer storage = new SimpleContainer(1);
        storage.setItem(0, new ItemStack(Items.STONE, 16));
        Slot slot = new Slot(storage, 0, 0, 0) {
            @Override public int getMaxStackSize() { return 16; }
        };
        assertFalse(hasRoom(new Menu(slot), new ItemStack(Items.STONE)));
        storage.setItem(0, new ItemStack(Items.STONE, 15));
        assertTrue(hasRoom(new Menu(slot), new ItemStack(Items.STONE)));
    }

    @Test void existingDifferentItemsAreNotMergeCapacity() throws Exception {
        SimpleContainer storage = new SimpleContainer(1);
        storage.setItem(0, new ItemStack(Items.DIRT, 1));
        assertFalse(hasRoom(new Menu(new Slot(storage, 0, 0, 0)), new ItemStack(Items.STONE)));
    }

    @Test void storageInterleavedWithPlayerSlotsDoesNotDefeatHotbarAndEquipmentGuards() {
        SimpleContainer player = new SimpleContainer(41);
        SimpleContainer storage = new SimpleContainer(2);
        Slot hotbar = new Slot(player, 0, 0, 0);
        Slot main = new Slot(player, 9, 0, 0);
        Slot armor = new Slot(player, 36, 0, 0);
        Slot offhand = new Slot(player, 40, 0, 0);
        Slot foreign = new Slot(storage, 0, 0, 0);
        Menu menu = new Menu(hotbar, foreign, main, armor, offhand);
        assertFalse(AutoStoreModule.isDepositSource(menu.slots.get(0), player, true));
        assertTrue(AutoStoreModule.isDepositSource(menu.slots.get(0), player, false));
        assertTrue(AutoStoreModule.isDepositSource(menu.slots.get(2), player, true));
        assertFalse(AutoStoreModule.isDepositSource(foreign, player, false));
        assertFalse(AutoStoreModule.isDepositSource(armor, player, false));
        assertFalse(AutoStoreModule.isDepositSource(offhand, player, false));
    }

    @Test void playerInventoryRoomCannotMasqueradeAsStorageRoom() {
        SimpleContainer player = new SimpleContainer(36);
        SimpleContainer storage = new SimpleContainer(1);
        storage.setItem(0, new ItemStack(Items.DIRT, 64));
        Menu menu = new Menu(new Slot(player, 9, 0, 0), new Slot(storage, 0, 0, 0));
        assertFalse(AutoStoreModule.containerHasRoomFor(menu, player, new ItemStack(Items.STONE)));
        storage.setItem(0, ItemStack.EMPTY);
        assertTrue(AutoStoreModule.containerHasRoomFor(menu, player, new ItemStack(Items.STONE)));
    }

    @Test void anEmptyZeroCapacitySlotStillCannotAcceptAnItem() {
        Slot sealed = new Slot(new SimpleContainer(1), 0, 0, 0) {
            @Override public int getMaxStackSize() { return 0; }
        };
        assertFalse(hasRoom(new Menu(sealed), new ItemStack(Items.STONE)));
    }

    private static boolean hasRoom(AbstractContainerMenu menu, ItemStack stack) {
        return AutoStoreModule.containerHasRoomFor(menu, new SimpleContainer(36), stack);
    }

    private static final class Menu extends AbstractContainerMenu {
        Menu(Slot... slots) {
            super(null, 1);
            for (Slot slot : slots) addSlot(slot);
        }
        @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player player) { return true; }
    }
}
