// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Spectre QuantumModule, d5a7dd3 (Cody / cody-raves).
package com.autism.seedcracker.spectre.activity;

import java.util.LinkedHashSet;
import java.util.Set;

public final class QuantumRules {
    public static final int SCAN_RADIUS = 8;
    public static final int FLAG_RADIUS = 9;
    private static final Set<String> IGNORED_NEW = Set.of(
        "minecraft:fire", "minecraft:frosted_ice", "minecraft:coal_ore", "minecraft:deepslate_coal_ore",
        "minecraft:iron_ore", "minecraft:deepslate_iron_ore", "minecraft:copper_ore", "minecraft:deepslate_copper_ore",
        "minecraft:gold_ore", "minecraft:deepslate_gold_ore", "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore",
        "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore", "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore",
        "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore", "minecraft:oak_leaves", "minecraft:acacia_leaves",
        "minecraft:birch_leaves", "minecraft:jungle_leaves", "minecraft:dark_oak_leaves", "minecraft:pale_oak_leaves",
        "minecraft:cherry_leaves", "minecraft:azalea_leaves", "minecraft:flowering_azalea_leaves",
        "minecraft:mossy_cobblestone", "minecraft:raw_copper_block", "minecraft:stone", "minecraft:observer",
        "minecraft:repeater", "minecraft:comparator", "minecraft:redstone_torch", "minecraft:redstone_lamp",
        "minecraft:redstone_wire", "minecraft:dropper"
    );
    private static final Set<String> IGNORED_OLD = Set.of("minecraft:amethyst_block", "minecraft:stone", "minecraft:frosted_ice");

    private QuantumRules() {}

    public static boolean ignoredChange(String oldBlock, String newBlock) {
        return IGNORED_OLD.contains(oldBlock) || IGNORED_NEW.contains(newBlock);
    }

    public static long key(int x, int z) { return (x & 0xffffffffL) | ((long) z << 32); }
    public static int x(long key) { return (int) key; }
    public static int z(long key) { return (int) (key >>> 32); }

    public static boolean inScanRange(int x, int z, int centerX, int centerZ) {
        long dz = Math.abs((long) z - centerZ);
        return dz <= SCAN_RADIUS && Math.abs((long) x - centerX) <= SCAN_RADIUS - Math.max(0, dz - 5);
    }

    public static Set<Long> scanRange(int centerX, int centerZ) {
        Set<Long> chunks = new LinkedHashSet<>();
        for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
            int halfWidth = SCAN_RADIUS - Math.max(0, Math.abs(dz) - 5);
            for (int dx = -halfWidth; dx <= halfWidth; dx++) chunks.add(key(centerX + dx, centerZ + dz));
        }
        return chunks;
    }

    public static Set<Long> narrow(Set<Long> previous, int sourceX, int sourceZ) {
        Set<Long> area = new LinkedHashSet<>();
        for (int dx = -FLAG_RADIUS; dx <= FLAG_RADIUS; dx++) {
            for (int dz = -FLAG_RADIUS; dz <= FLAG_RADIUS; dz++) area.add(key(sourceX + dx, sourceZ + dz));
        }
        if (previous.isEmpty()) return area;
        Set<Long> overlap = new LinkedHashSet<>(area);
        overlap.retainAll(previous);
        return overlap.isEmpty() ? area : overlap;
    }
}
