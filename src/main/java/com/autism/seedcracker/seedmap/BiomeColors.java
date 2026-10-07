package com.autism.seedcracker.seedmap;

import com.seedfinding.mcbiome.biome.Biome;

/** Map colours per biome category (Amidst-like, muted so structure icons stand out). */
final class BiomeColors {
    private BiomeColors() {}

    static int of(Biome b) {
        String name = b.getName() == null ? "" : b.getName();
        if (name.contains("deep") && name.contains("ocean")) return 0xFF1A2A6C;
        if (name.contains("frozen") && name.contains("ocean")) return 0xFF6E7FB0;
        if (name.contains("warm_ocean")) return 0xFF2A5AA8;
        if (name.contains("cherry")) return 0xFFE6A6C8;
        if (name.contains("mangrove")) return 0xFF4E6B3A;
        if (name.contains("dark_forest")) return 0xFF2F4A20;
        if (name.contains("snowy") || name.contains("ice")) return 0xFFE8F0F4;
        if (name.contains("meadow")) return 0xFF7FB66A;
        if (name.contains("peaks") || name.contains("slopes") || name.contains("grove")) return 0xFFB8C4C8;
        if (name.contains("deep_dark") || name.contains("lush") || name.contains("dripstone")) return 0xFF3A3A3A;
        Biome.Category c = b.getCategory();
        if (c == null) return 0xFF505050;
        return switch (c.name()) {
            case "OCEAN" -> 0xFF2440A0;
            case "RIVER" -> 0xFF3A5FD0;
            case "BEACH" -> 0xFFE6DCA0;
            case "DESERT" -> 0xFFE3C878;
            case "MESA", "BADLANDS_PLATEAU" -> 0xFFC2653A;
            case "SAVANNA" -> 0xFFB4A84A;
            case "PLAINS" -> 0xFF8DB360;
            case "FOREST" -> 0xFF4E8A3A;
            case "TAIGA" -> 0xFF3E6A5A;
            case "JUNGLE" -> 0xFF4A9A20;
            case "SWAMP" -> 0xFF4C6A4A;
            case "EXTREME_HILLS" -> 0xFF7A8A7A;
            case "ICY" -> 0xFFE0EEF4;
            case "MUSHROOM" -> 0xFFA070A8;
            case "NETHER" -> 0xFF7A2A20;
            case "THE_END" -> 0xFFD8D8A0;
            default -> 0xFF505050;
        };
    }
}
