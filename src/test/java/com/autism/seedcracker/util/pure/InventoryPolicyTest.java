package com.autism.seedcracker.util.pure;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryPolicyTest {
    @Test void replenishmentOnlySwapsMainInventoryIntoAHotbarSlot() {
        assertTrue(InventoryPolicy.validReplenishSwap(9, 0));
        assertTrue(InventoryPolicy.validReplenishSwap(35, 8));
        assertFalse(InventoryPolicy.validReplenishSwap(8, 0));
        assertFalse(InventoryPolicy.validReplenishSwap(36, 0));
        assertFalse(InventoryPolicy.validReplenishSwap(9, 9));
        assertFalse(InventoryPolicy.validReplenishSwap(9, -1));
    }

    @Test void riskyOrValuableFoodsAreNotDefaultSnacks() {
        for (String id : new String[]{"rotten_flesh", "spider_eye", "pufferfish", "poisonous_potato", "chicken",
            "chorus_fruit", "suspicious_stew", "enchanted_golden_apple"}) {
            assertFalse(InventoryPolicy.safeFood("minecraft:" + id), id);
        }
        assertTrue(InventoryPolicy.safeFood("minecraft:cooked_beef"));
        assertTrue(InventoryPolicy.safeFood("minecraft:golden_apple"));
        assertFalse(InventoryPolicy.safeFood(null));
    }

    @Test void restorationOnlyAppliesWhileTheAutomationStillOwnsTheSelection() {
        assertTrue(InventoryPolicy.shouldRestoreSlot(0, 3, 3));
        assertFalse(InventoryPolicy.shouldRestoreSlot(0, 3, 6));
        assertFalse(InventoryPolicy.shouldRestoreSlot(-1, 3, 3));
        assertFalse(InventoryPolicy.shouldRestoreSlot(9, 3, 3));
        assertFalse(InventoryPolicy.shouldRestoreSlot(0, -1, 0));
        assertFalse(InventoryPolicy.shouldRestoreSlot(3, 3, 3));
    }
}