package com.example.donutflipscanner.automation.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Pure inventory planner; Minecraft applies and verifies the returned swap on its client thread. */
public final class PurchasedStackPlacementPlanner {
    public Plan plan(List<InventorySlotState> inventory, int requiredCount, int selectedHotbarSlot) {
        List<InventorySlotState> slots = List.copyOf(Objects.requireNonNull(inventory, "inventory"));
        if (requiredCount < 1 || selectedHotbarSlot < 0 || selectedHotbarSlot > 8) {
            throw new IllegalArgumentException("inventory planning values are invalid");
        }
        List<InventorySlotState> matches = slots.stream()
                .filter(InventorySlotState::exactItemMatch)
                .filter(value -> value.itemCount() == requiredCount)
                .toList();
        if (matches.size() != 1) {
            return Plan.rejected(matches.isEmpty()
                    ? "The verified purchase is missing from inventory."
                    : "Multiple inventory stacks match the verified purchase.");
        }
        int source = matches.getFirst().inventoryIndex();
        if (source < 9) {
            return Plan.accepted(source, source, false);
        }
        int target = slots.stream()
                .filter(value -> value.inventoryIndex() >= 0 && value.inventoryIndex() < 9)
                .filter(value -> value.itemCount() == 0)
                .mapToInt(InventorySlotState::inventoryIndex)
                .findFirst().orElse(selectedHotbarSlot);
        return Plan.accepted(source, target, true);
    }

    public record InventorySlotState(int inventoryIndex, int itemCount, boolean exactItemMatch) {
        public InventorySlotState {
            if (inventoryIndex < 0 || itemCount < 0) {
                throw new IllegalArgumentException("inventory slot values must not be negative");
            }
        }
    }

    public record Plan(
            boolean accepted,
            Optional<Integer> sourceInventoryIndex,
            Optional<Integer> targetHotbarSlot,
            boolean swapRequired,
            String message
    ) {
        private static Plan accepted(int source, int target, boolean swap) {
            return new Plan(true, Optional.of(source), Optional.of(target), swap,
                    swap ? "Move the exact stack into the selected hotbar target."
                            : "The exact stack is already in the hotbar.");
        }

        private static Plan rejected(String message) {
            return new Plan(false, Optional.empty(), Optional.empty(), false, message);
        }
    }
}
