package com.autism.seedcracker.util.pure;

import java.util.Set;

public final class InventoryPolicy {
    private static final Set<String> RISKY_FOOD = Set.of("minecraft:rotten_flesh", "minecraft:spider_eye",
        "minecraft:pufferfish", "minecraft:poisonous_potato", "minecraft:chicken", "minecraft:chorus_fruit",
        "minecraft:suspicious_stew", "minecraft:enchanted_golden_apple");
    private InventoryPolicy() {}

    public static boolean safeFood(String itemId) {
        return itemId != null && !RISKY_FOOD.contains(itemId);
    }

    public static boolean shouldRestoreSlot(int original, int owned, int selected) {
        return original >= 0 && original < 9 && owned >= 0 && owned < 9 && selected == owned && original != selected;
    }

    public static boolean validReplenishSwap(int inventorySlot, int hotbarSlot) {
        return inventorySlot >= 9 && inventorySlot < 36 && hotbarSlot >= 0 && hotbarSlot < 9;
    }
}