package com.autism.seedcracker.util.pure;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Evidence fusion for the Sus Chunk Finder (Beta), pure and unit tested. Each detector from the
 * single-mode finder contributes weighted points; a chunk flags only when the total clears the
 * threshold AND at least {@code minSignals} independent signal families agree, so one natural
 * quirk (a geode, a jungle shaft, an ancient city) can never flag on its own.
 */
public final class SusScore {
    public static boolean geodeTiePreferred(int otherLocalHeat, long otherKey, int localHeat, long key) {
        return otherLocalHeat > localHeat || (otherLocalHeat == localHeat && otherKey < key);
    }

    public static boolean geodePeakEligible(int heat, boolean terrainPresent, int configuredThreshold, int strongestNearby) {
        int required = terrainPresent ? Math.max(1, configuredThreshold) : 1;
        return heat >= required && heat >= strongestNearby;
    }

    public static boolean glowEdge(int ownLight, int[] adjacentLight, boolean[] adjacentOpen) {
        if (ownLight != 0 || adjacentLight.length != 6 || adjacentOpen.length != 6) return false;
        boolean opening = false;
        for (int index = 0; index < 6; index++) {
            if (adjacentLight[index] > 4 || adjacentLight[index] < 0) return false;
            if (adjacentLight[index] == 4 && adjacentOpen[index]) opening = true;
        }
        return opening;
    }

    private SusScore() {}

    private enum Family { STORAGE, STONE, VEGETATION, GEOMETRY, REDSTONE, DECORATION, SUPPORT }

    /** Independent families; each is counted once toward the agreement rule. */
    public enum Signal {
        STORAGE("player storage", 45, Family.STORAGE),
        ROTATED_DEEPSLATE("rotated deepslate", 25, Family.STONE),
        DEEP_VINES("underground vines", 25, Family.VEGETATION),
        DEEP_KELP("deep kelp", 25, Family.VEGETATION),
        FLAT_ROOM("flat mined floor", 20, Family.GEOMETRY),
        POWERED_REDSTONE("running redstone", 35, Family.REDSTONE),
        SKULL_CANDLE("skulls/candles", 15, Family.DECORATION),
        GLOW("amethyst glow pocket", 15, Family.SUPPORT),
        BUILT("built structure", 20, Family.SUPPORT);

        public final String label;
        public final int weight;
        private final Family family;

        Signal(String label, int weight, Family family) {
            this.label = label;
            this.weight = weight;
            this.family = family;
        }
    }

    public record Result(int score, int signals, boolean flagged, String why) {}

    /**
     * @param strength 0..1 per signal (1 = detector fully fired); partial strengths scale the weight
     * @param neighbourHeat best score among adjacent chunks, adds up to 15% (bases sprawl)
     */
    public static Result score(Map<Signal, Double> strength, int neighbourHeat, int threshold, int minSignals) {
        boolean construction = false;
        EnumSet<Family> families = EnumSet.noneOf(Family.class);
        EnumMap<Family, Integer> weights = new EnumMap<>(Family.class);
        List<Signal> hits = new ArrayList<>();
        for (Map.Entry<Signal, Double> e : strength.entrySet()) {
            double s = Math.max(0, Math.min(1, e.getValue()));
            if (s <= 0) continue;
            weights.merge(e.getKey().family, (int) Math.round(e.getKey().weight * s), Math::max);
            if (s >= 0.5 && e.getKey().family != Family.SUPPORT) {
                Family family = e.getKey().family;
                families.add(family);
                construction |= family != Family.VEGETATION && family != Family.GEOMETRY;
                hits.add(e.getKey());
            }
        }
        int total = weights.values().stream().mapToInt(Integer::intValue).sum();
        // Spread never creates a flag by itself: it only tops up chunks that already have evidence.
        if (total > 0) total += (int) Math.round(Math.max(0, Math.min(100, neighbourHeat)) * 0.15);
        total = Math.min(100, total);
        hits.sort((a, b) -> a.weight != b.weight ? Integer.compare(b.weight, a.weight) : a.compareTo(b));
        StringBuilder why = new StringBuilder();
        for (int i = 0; i < Math.min(3, hits.size()); i++) {
            if (i > 0) why.append(", ");
            why.append(hits.get(i).label);
        }
        int signals = families.size();
        return new Result(total, signals, construction && total >= threshold
            && signals >= Math.max(1, minSignals), why.toString());
    }

    /** Detector count -> 0..1 strength: 0 below {@code need}, 1 at {@code 2*need}. */
    public static double ramp(int count, int need) {
        if (need <= 0 || count < need) return 0;
        return Math.min(1.0, 0.5 + 0.5 * (count - need) / (double) need);
    }

    public static double storageStrength(int exclusive, int ordinary, boolean naturalStructure) {
        if (exclusive > 0) return ramp(exclusive, 1);
        return naturalStructure ? 0 : ramp(ordinary, 4);
    }

    public record GeodeGrowth(int mature, int detached, int young, int unknownSupport) {
        public boolean complete() { return unknownSupport == 0; }
        public GeodeGrowth {
            if (mature < 0 || detached < 0 || young < 0 || unknownSupport < 0) {
                throw new IllegalArgumentException("Growth counts cannot be negative");
            }
        }

        public boolean veto() {
            return young > 0 || unknownSupport > 0;
        }

        public int heat() {
            return veto() ? 0 : (int) Math.min(Integer.MAX_VALUE, (long) mature + detached);
        }
    }

    public static boolean flatFloorHit(boolean[] cells, int need) {
        if (cells.length != 256) throw new IllegalArgumentException("Expected a 16x16 floor");
        int[] heights = new int[16];
        for (int row = 0; row < 16; row++) {
            for (int column = 0; column < 16; column++) {
                heights[column] = cells[row * 16 + column] ? heights[column] + 1 : 0;
            }
            for (int right = 2; right < 16; right++) {
                int height = heights[right];
                for (int left = right - 1; left >= 0 && height >= 3; left--) {
                    height = Math.min(height, heights[left]);
                    int width = right - left + 1;
                    if (height >= 3 && width >= 3 && height * width >= Math.max(9, need)) return true;
                }
            }
        }
        return false;
    }

    public static int findBaseCluster(int[] counts, boolean[] spawners, int side, int need, boolean requireSpawner) {
        if (side < 1 || counts.length != side * side || spawners.length != counts.length || need < 1) {
            throw new IllegalArgumentException("Invalid chunk grid or threshold");
        }
        for (int row = 0; row < side; row++) {
            for (int column = 0; column < side; column++) {
                int total = 0;
                boolean spawner = false;
                for (int nearbyRow = Math.max(0, row - 1); nearbyRow <= Math.min(side - 1, row + 1); nearbyRow++) {
                    for (int nearbyColumn = Math.max(0, column - 1); nearbyColumn <= Math.min(side - 1, column + 1); nearbyColumn++) {
                        int index = nearbyRow * side + nearbyColumn;
                        total += counts[index];
                        spawner |= spawners[index];
                    }
                }
                if (total >= need && (!requireSpawner || spawner)) return row * side + column;
            }
        }
        return -1;
    }
}
