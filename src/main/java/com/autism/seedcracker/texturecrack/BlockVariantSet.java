package com.autism.seedcracker.texturecrack;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * The random-variant model list of a block, parsed from its blockstate JSON at runtime (so any
 * block whose default state picks among weighted y-rotated / mirrored models is crackable, and
 * resource-pack or version changes are picked up automatically instead of trusting a hardcoded
 * audit).
 *
 * A variant's visual transform is encoded in one byte: bits 0-1 = CW quarter-turns of the top
 * face, bit 2 = horizontally mirrored ("_mirrored" models, e.g. stone/bedrock). The positional
 * RNG picks the variant index; what the OBSERVER sees is the variant transform composed with the
 * screenshot's view rotation - {@link #observedFor} implements that composition, and
 * {@link #acceptMask} inverts it into "which variant indices could produce this observation".
 */
public final class BlockVariantSet {

    public static final int T_MIRROR = 4;

    private final String block;
    private final byte[] transforms;
    private final int[] weights;
    private final int totalWeight;
    private final boolean uniform;

    private BlockVariantSet(String block, byte[] transforms, int[] weights) {
        this.block = block;
        this.transforms = transforms;
        this.weights = weights;
        int t = 0;
        boolean uni = true;
        for (int w : weights) {
            t += w;
            if (w != weights[0]) uni = false;
        }
        this.totalWeight = t;
        this.uniform = uni;
    }

    public String block() { return block; }
    public int count() { return transforms.length; }
    public int totalWeight() { return totalWeight; }
    public boolean uniformWeights() { return uniform; }
    public byte transform(int variant) { return transforms[variant]; }
    public int weight(int variant) { return weights[variant]; }
    public boolean hasMirrors() {
        for (byte t : transforms) if ((t & T_MIRROR) != 0) return true;
        return false;
    }

    /** True when the variants carry no signal (a single model = nothing to crack). */
    public boolean degenerate() {
        for (byte t : transforms) if (t != transforms[0]) return false;
        return true;
    }

    /**
     * The transform the OBSERVER sees for a variant under grid orientation {@code o}
     * (0-3 quarter-turns). Mirrored textures rotate against the view; {@code plusConvention}
     * selects the composition handedness (both are tried - a wrong pick can't lose a match).
     */
    public static byte observedFor(int o, byte t, boolean plusConvention) {
        int vo = plusConvention ? o : (4 - o) & 3;
        int r = t & 3;
        boolean m = (t & T_MIRROR) != 0;
        int obsR = m ? (r - vo) & 3 : (r + vo) & 3;
        return (byte) (obsR | (m ? T_MIRROR : 0));
    }

    /** Bitmask of variant indices whose observed transform equals {@code observed} under (o, convention). */
    public int acceptMask(int o, byte observed, boolean plusConvention) {
        int mask = 0;
        for (int v = 0; v < transforms.length; v++) {
            if (observedFor(o, transforms[v], plusConvention) == observed) mask |= 1 << v;
        }
        return mask;
    }

    /** All transforms an observer could see across orientations/conventions (bounds reader matching). */
    public Set<Byte> reachableTransforms() {
        Set<Byte> out = new java.util.HashSet<>();
        for (byte t : transforms) {
            for (int o = 0; o < 4; o++) {
                out.add(observedFor(o, t, true));
                out.add(observedFor(o, t, false));
            }
        }
        return out;
    }

    /** Blocks the ROT4 fallback applies to when the blockstate JSON can't be read. */
    private static final Set<String> ROT4_FALLBACK = Set.of(
        "dirt", "coarse_dirt", "rooted_dirt", "netherrack", "sand", "red_sand", "gravel",
        "mud", "soul_sand", "soul_soil", "end_stone");

    private static BlockVariantSet rot4(String block) {
        return new BlockVariantSet(block, new byte[]{0, 1, 2, 3}, new int[]{1, 1, 1, 1});
    }

    /**
     * Loads the variant list of {@code block}'s DEFAULT blockstate entry ("" or the first
     * property-keyed entry, e.g. grass_block's "snowy=false") from the active resources.
     */
    public static BlockVariantSet load(String block) {
        String key = block.toLowerCase(Locale.ROOT);
        if (key.contains(":")) key = key.substring(key.indexOf(':') + 1);
        // The reader's texture aliases (grass -> grass_block_top) share names; normalize a few.
        if (key.equals("grass")) key = "grass_block";

        try {
            Identifier id = Identifier.fromNamespaceAndPath("minecraft", "blockstates/" + key + ".json");
            var res = Minecraft.getInstance().getResourceManager().getResource(id);
            if (res.isEmpty()) throw new IllegalStateException("no blockstate " + id);
            JsonObject root;
            try (InputStream in = res.get().open()) {
                root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            }
            JsonObject variants = root.getAsJsonObject("variants");
            if (variants == null) throw new IllegalStateException(key + " uses multipart (unsupported)");
            // Default entry: "" if present, else prefer snowy=false / axis=y style defaults, else first.
            JsonElement entry = variants.get("");
            if (entry == null) {
                for (String k : variants.keySet()) {
                    if (k.contains("=false") || entry == null) entry = variants.get(k);
                    if (k.contains("=false")) break;
                }
            }
            if (entry == null) throw new IllegalStateException(key + " has no variant entries");
            JsonArray arr = entry.isJsonArray() ? entry.getAsJsonArray() : null;
            if (arr == null) {
                arr = new JsonArray();
                arr.add(entry);
            }
            if (arr.size() > 32) {
                // acceptMask is an int bitmask; >32 variants would overflow silently.
                throw new IllegalStateException(key + " has " + arr.size() + " variants (max 32 supported)");
            }
            byte[] transforms = new byte[arr.size()];
            int[] weights = new int[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                JsonObject v = arr.get(i).getAsJsonObject();
                if (v.has("x") && v.get("x").getAsInt() != 0) {
                    throw new IllegalStateException(key + " uses x-rotated variants (unsupported)");
                }
                int y = v.has("y") ? v.get("y").getAsInt() : 0;
                String model = v.has("model") ? v.get("model").getAsString() : "";
                boolean mirrored = model.endsWith("_mirrored") || model.contains("_mirrored_");
                transforms[i] = (byte) (((y / 90) & 3) | (mirrored ? T_MIRROR : 0));
                weights[i] = v.has("weight") ? Math.max(1, v.get("weight").getAsInt()) : 1;
            }
            return new BlockVariantSet(key, transforms, weights);
        } catch (Throwable t) {
            if (ROT4_FALLBACK.contains(key)) return rot4(key);
            throw new IllegalArgumentException("cannot resolve variants for '" + key + "': "
                + (t.getMessage() == null ? t.toString() : t.getMessage()));
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(block).append(" [");
        for (int i = 0; i < transforms.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(transforms[i] & 3);
            if ((transforms[i] & T_MIRROR) != 0) sb.append('m');
            if (weights[i] != 1) sb.append('w').append(weights[i]);
        }
        return sb.append(']').toString();
    }
}
