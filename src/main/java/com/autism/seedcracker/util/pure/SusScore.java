package com.autism.seedcracker.util.pure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Evidence fusion for the Sus Chunk Finder (Beta), pure and unit tested. Each detector from the
 * single-mode finder contributes weighted points; a chunk flags only when the total clears the
 * threshold AND at least {@code minSignals} independent signal families agree, so one natural
 * quirk (a geode, a jungle shaft, an ancient city) can never flag on its own.
 */
public final class SusScore {
    private SusScore() {}

    /** Independent families; each is counted once toward the agreement rule. */
    public enum Signal {
        STORAGE("player storage", 45),
        ROTATED_DEEPSLATE("rotated deepslate", 25),
        DEEP_VINES("underground vines", 25),
        DEEP_KELP("deep kelp", 25),
        FLAT_ROOM("flat mined floor", 20),
        POWERED_REDSTONE("running redstone", 35),
        SKULL_CANDLE("skulls/candles", 15),
        GLOW("amethyst glow pocket", 15),
        BUILT("built structure", 20);

        public final String label;
        public final int weight;

        Signal(String label, int weight) {
            this.label = label;
            this.weight = weight;
        }
    }

    public record Result(int score, int signals, boolean flagged, String why) {}

    /**
     * @param strength 0..1 per signal (1 = detector fully fired); partial strengths scale the weight
     * @param neighbourHeat best score among adjacent chunks, adds up to 15% (bases sprawl)
     */
    public static Result score(Map<Signal, Double> strength, int neighbourHeat, int threshold, int minSignals) {
        int total = 0, signals = 0;
        List<Signal> hits = new ArrayList<>();
        for (Map.Entry<Signal, Double> e : strength.entrySet()) {
            double s = Math.max(0, Math.min(1, e.getValue()));
            if (s <= 0) continue;
            total += (int) Math.round(e.getKey().weight * s);
            if (s >= 0.5) {
                signals++;
                hits.add(e.getKey());
            }
        }
        // Spread never creates a flag by itself: it only tops up chunks that already have evidence.
        if (total > 0) total += (int) Math.round(Math.max(0, Math.min(100, neighbourHeat)) * 0.15);
        total = Math.min(100, total);
        hits.sort((a, b) -> a.weight != b.weight ? Integer.compare(b.weight, a.weight) : a.compareTo(b));
        StringBuilder why = new StringBuilder();
        for (int i = 0; i < Math.min(3, hits.size()); i++) {
            if (i > 0) why.append(", ");
            why.append(hits.get(i).label);
        }
        return new Result(total, signals, total >= threshold && signals >= Math.max(1, minSignals), why.toString());
    }

    /** Detector count -> 0..1 strength: 0 below {@code need}, 1 at {@code 2*need}. */
    public static double ramp(int count, int need) {
        if (need <= 0 || count < need) return 0;
        return Math.min(1.0, 0.5 + 0.5 * (count - need) / (double) need);
    }
}
