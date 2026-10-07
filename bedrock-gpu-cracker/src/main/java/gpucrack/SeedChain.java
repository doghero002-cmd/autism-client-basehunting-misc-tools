package gpucrack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Standalone re-implementation of Minecraft's bedrock random chain (no MC dependency):
 * worldSeed -> XoroshiroRandomSource -> forkPositional -> fromHashOf("minecraft:bedrock_floor")
 * -> forkPositional, then one xoroshiro128++ draw per position compared to the layer threshold.
 * Mirrors the (runtime-verified) fast path in the mod's BedrockFinderEngine.
 */
public final class SeedChain {

    /** Xoroshiro128++ stepper matching vanilla nextLong. */
    public static final class Xoro {
        public long lo, hi;

        public Xoro(long lo, long hi) {
            if ((lo | hi) == 0L) { // vanilla zero-state guard
                this.lo = -7046029254386353131L;
                this.hi = 7640891576956012809L;
            } else {
                this.lo = lo;
                this.hi = hi;
            }
        }

        public long next() {
            long l = lo, m = hi;
            long n = Long.rotateLeft(l + m, 17) + l;
            m ^= l;
            lo = Long.rotateLeft(l, 49) ^ m ^ (m << 21);
            hi = Long.rotateLeft(m, 28);
            return n;
        }
    }

    private SeedChain() {}

    public static long mixStafford13(long z) {
        z = (z ^ (z >>> 30)) * -4658895280553007687L;
        z = (z ^ (z >>> 27)) * -7723592293110705685L;
        return z ^ (z >>> 31);
    }

    /** Vanilla Mth.getSeed (the x multiply overflows in int on purpose). */
    public static long posSeed(int x, int y, int z) {
        long l = (long) (x * 3129871) ^ (long) z * 116129781L ^ (long) y;
        l = l * l * 42317861L + l * 11L;
        return l >> 16;
    }

    /** Derives the positional factory's {lo, hi} for "minecraft:bedrock_floor" or "bedrock_roof". */
    public static long[] deriveSeeds(long worldSeed, boolean roof) {
        try {
            long sl = worldSeed ^ 0x6A09E667F3BCC909L;            // silver ratio
            long sh = sl + -7046029254386353131L;                  // golden ratio
            Xoro root = new Xoro(mixStafford13(sl), mixStafford13(sh));
            long fLo = root.next(), fHi = root.next();             // forkPositional

            String id = "minecraft:" + (roof ? "bedrock_roof" : "bedrock_floor");
            byte[] md5 = MessageDigest.getInstance("MD5").digest(id.getBytes(StandardCharsets.UTF_8));
            long hLo = beLong(md5, 0), hHi = beLong(md5, 8);
            Xoro hashed = new Xoro(hLo ^ fLo, hHi ^ fHi);          // fromHashOf
            return new long[]{hashed.next(), hashed.next()};       // forkPositional
        } catch (Exception e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    private static long beLong(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b[off + i] & 0xFFL);
        return v;
    }

    /** Bedrock density at a layer: floor Y-63..-60 = 0.8/0.6/0.4/0.2, roof Y123..126 mirrored. */
    public static float layerThreshold(int y, boolean roof) {
        return roof ? 1.0f - (127 - y) / 5.0f : 1.0f - (y + 64) / 5.0f;
    }
}
