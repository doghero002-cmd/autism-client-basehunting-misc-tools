package com.autism.seedcracker.util.tunnel;

import java.util.SplittableRandom;
import java.util.UUID;

import net.minecraft.client.Minecraft;

/**
 * Human pacing engine for automated digging/walking.
 *
 * Port of the CodeEngine "AutoTunnelHelper" util (dev.nyx.util.AutoTunnelHelper), translated
 * to Mojang 26.2 mappings. It produces human-looking break pacing: gamma-distributed micro
 * pauses between blocks, lognormal block-break durations, a session-length ramp that slowly
 * lengthens pauses the longer you run, and randomized re-arm intervals. All randomness is
 * seeded from the player UUID + nanoTime so patterns differ per session/player.
 */
public final class HumanPacingEngine {
    private static final HumanPacingEngine INSTANCE = new HumanPacingEngine();

    private volatile boolean enabled = false;
    private SplittableRandom rng;
    private long startMs = 0L;
    private int microPauseInterval = 5;
    private int microPauseCounter = 0;
    private int longPauseInterval = 40;
    private int longPauseCounter = 0;
    private long lastShortPauseMs = 0L;
    private long lastLongPauseMs = 0L;
    private long shortPauseCooldownMs = 300000L;
    private long longPauseCooldownMs = 1200000L;

    private HumanPacingEngine() {}

    public static HumanPacingEngine get() {
        return INSTANCE;
    }

    public void setEnabled(boolean on) {
        if (this.enabled != on) {
            this.enabled = on;
            if (on) {
                ensureRng();
                this.startMs = System.currentTimeMillis();
                this.lastShortPauseMs = this.startMs;
                this.lastLongPauseMs = this.startMs;
                reroll();
            }
        }
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    private void ensureRng() {
        if (this.rng == null) {
            long seed;
            try {
                UUID id = Minecraft.getInstance().player != null
                    ? Minecraft.getInstance().player.getUUID() : new UUID(0L, 0L);
                seed = id.getMostSignificantBits() ^ id.getLeastSignificantBits();
            } catch (Throwable t) {
                seed = 0L;
            }
            this.rng = new SplittableRandom(System.nanoTime() ^ seed);
        }
    }

    private void reroll() {
        ensureRng();
        this.microPauseInterval = 3 + this.rng.nextInt(5);
        this.longPauseInterval = 25 + this.rng.nextInt(31);
        this.shortPauseCooldownMs = 240000L + (long) (this.rng.nextDouble() * 300000.0);
        this.longPauseCooldownMs = 900000L + (long) (this.rng.nextDouble() * 900000.0);
    }

    /** Ticks to wait between individual block breaks (gamma-distributed, small). */
    public int breakGapTicks() {
        if (!this.enabled) return 0;
        ensureRng();
        double g = gammaSample(2.0, 5.0);
        return 1 + (int) Math.floor(g * 3.0);
    }

    /** Block-break hold duration in ticks (lognormal around ~140ms, clamped 2..8). */
    public int breakHoldTicks() {
        if (!this.enabled) return 0;
        ensureRng();
        double ln = lognormalSample(140.0, 50.0);
        int t = (int) Math.round(ln / 50.0);
        if (t < 2) t = 2;
        if (t > 8) t = 8;
        return t;
    }

    /** Returns 1 every few calls (micro pause trigger), else 0. */
    public int microPause() {
        if (!this.enabled) return 0;
        this.microPauseCounter++;
        this.longPauseCounter++;
        if (this.microPauseCounter >= this.microPauseInterval) {
            this.microPauseCounter = 0;
            this.microPauseInterval = 3 + this.rng.nextInt(5);
            return 1;
        }
        return 0;
    }

    /** Longer occasional pause length in ticks, 0 if not due. */
    public int longPause() {
        if (!this.enabled) return 0;
        if (this.longPauseCounter < this.longPauseInterval) return 0;
        this.longPauseCounter = 0;
        this.longPauseInterval = 25 + this.rng.nextInt(31);
        double ramp = sessionRamp();
        int base = 10 + this.rng.nextInt(21);
        return Math.max(10, (int) Math.round(base * ramp));
    }

    /** Occasional short idle (every ~20s), 2-5 ticks, else 0. */
    public int shortIdle(long nowMs) {
        if (!this.enabled) return 0;
        if (nowMs - this.lastShortPauseMs < 20000L) return 0;
        this.lastShortPauseMs = nowMs;
        return 2 + this.rng.nextInt(4);
    }

    /** Occasional long idle (every few minutes), ramped by session length, else 0. */
    public int longIdle(long nowMs) {
        if (!this.enabled) return 0;
        if (nowMs - this.lastLongPauseMs < this.longPauseCooldownMs) return 0;
        this.lastLongPauseMs = nowMs;
        this.longPauseCooldownMs = 240000L + (long) (this.rng.nextDouble() * 300000.0);
        double ramp = sessionRamp();
        int base = 20 + this.rng.nextInt(41);
        return Math.max(20, (int) Math.round(base * ramp));
    }

    /** Session-length ramp: pauses grow ~2%/min up to 1.6x to mimic fatigue. */
    public double sessionRamp() {
        if (!this.enabled) return 1.0;
        double minutes = Math.max(0.0, (System.currentTimeMillis() - this.startMs) / 60000.0);
        return Math.min(1.6, 1.0 + 0.02 * minutes);
    }

    // --- gamma (shape/scale via Marsaglia-Tsang) ---
    private double gammaSample(double shape, double scale) {
        double a = gammaShape(shape);
        double b = gammaShape(scale);
        double sum = a + b;
        return sum <= 0.0 ? 0.5 : a / sum;
    }

    private double gammaShape(double shape) {
        if (shape < 1.0) {
            double u = this.rng.nextDouble();
            return gammaShape(shape + 1.0) * Math.pow(u, 1.0 / shape);
        }
        double d = shape - 0.3333333333333333;
        double c = 1.0 / Math.sqrt(9.0 * d);
        while (true) {
            double z = gaussian();
            double v = 1.0 + c * z;
            if (v > 0.0) {
                v = v * v * v;
                double u = this.rng.nextDouble();
                if (u < 1.0 - 0.0331 * (z * z) * (z * z)) return d * v;
                if (Math.log(u) < 0.5 * z * z + d * (1.0 - v + Math.log(v))) return d * v;
            }
        }
    }

    private double gaussian() {
        double u1 = Math.max(1.0E-12, this.rng.nextDouble());
        double u2 = this.rng.nextDouble();
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos((Math.PI * 2) * u2);
    }

    private double lognormalSample(double median, double sigma) {
        double mu = Math.log(Math.max(1.0, median));
        double s = sigma / Math.max(1.0, median);
        return Math.exp(mu + s * gaussian());
    }
}
