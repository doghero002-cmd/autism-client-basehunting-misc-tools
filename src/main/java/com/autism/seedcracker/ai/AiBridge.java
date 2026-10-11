package com.autism.seedcracker.ai;

import autismclient.api.AutismAddons;
import autismclient.api.ai.AiCapabilityDescriptor;
import autismclient.api.ai.AiCapabilityProvider;
import autismclient.api.ai.AiObservation;
import autismclient.api.ai.AiOperation;
import com.autism.seedcracker.finder.BaseTracker;
import com.autism.seedcracker.market.PriceTracker;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Bridges the addon into the AUTISM Client AI subsystem ({@code AutismAddons.ai()}).
 *
 * Exposes a small set of READ-ONLY capabilities the host AI can invoke: a module-state snapshot,
 * market (AH) price stats, and base-finding results. Read-only is deliberate: the host calls
 * {@link #start}/{@link AiOperation#tick} off the client thread, so any capability that mutated
 * module state or spent money would need purchase-safety gating. The market/base reads use
 * already-synchronized snapshot APIs ({@link PriceTracker}, {@link BaseTracker}); the module-state
 * read is marshalled onto the client thread because live module objects are client-thread state.
 *
 * Compatibility: every AI type used here lives in the stable {@code autismclient.api.**} surface
 * (the same guarantee as {@code ModuleLookup}/{@code CategoryAssigner} rely on), NOT the
 * obfuscated {@code autismclient.ai.**} package - which is why {@code Result} is built only via
 * its 4-arg constructor, never the failure-enum overload. {@link #register()} loads this class
 * reflectively so a host without the AI API never triggers a linkage error and the one jar still
 * loads warning-free on 5.0 / V4.
 */
public final class AiBridge implements AiCapabilityProvider {
    private static final Minecraft mc = Minecraft.getInstance();

    private AiBridge() {}

    /** Reflective entry point so this class is only linked on hosts that actually have the AI API. */
    @SuppressWarnings("unchecked")
    public static void registerIfPresent() {
        try {
            Class.forName("autismclient.api.ai.AiCapabilityProvider"); // linkage probe; throws on old hosts
            Object provider = Class.forName("com.autism.seedcracker.ai.AiBridge")
                .getDeclaredConstructor().newInstance();
            boolean ok = AutismAddons.ai().register((AiCapabilityProvider) provider);
            if (!ok) System.out.println("[autism-seedcracker] AI bridge registration returned false");
        } catch (Throwable t) {
            System.out.println("[autism-seedcracker] AI bridge unavailable on this client (" + t.getClass().getSimpleName() + "); skipping.");
        }
    }

    @Override
    public List<AiCapabilityDescriptor> capabilities() {
        return List.of(
            desc("module-state", "state", "Snapshot of every addon module's id, name and enabled flag.",
                "{\"type\":\"object\",\"properties\":{}}"),
            desc("market-stats", "market", "AH price-history stats for one item id.",
                "{\"type\":\"object\",\"required\":[\"item\"],\"properties\":{\"item\":{\"type\":\"string\"}}}"),
            desc("base-report", "find", "Nearest recorded base findings to the player (coords included).",
                "{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\"}}}"));
    }

    private static AiCapabilityDescriptor desc(String id, String family, String purpose, String inputSchema) {
        return new AiCapabilityDescriptor(
            id, "autism-seedcracker", 1, family, purpose,
            List.of(), inputSchema, "", "", List.of(),
            "", "", "", AiCapabilityDescriptor.Classification.OBSERVE);
    }

    @Override
    public AiOperation start(String id, String input, AiObservation observation) {
        final String data;
        try {
            data = switch (id) {
                case "module-state" -> moduleState();
                case "market-stats" -> marketStats(parseItem(input));
                case "base-report" -> baseReport(parseLimit(input));
                default -> null;
            };
        } catch (Throwable t) {
            return finished(AiOperation.Status.FAILED, "{\"error\":\"" + esc(t.getClass().getSimpleName()) + "\"}");
        }
        if (data == null) {
            return finished(AiOperation.Status.FAILED, "{\"error\":\"unknown capability " + esc(id) + "\"}");
        }
        return finished(AiOperation.Status.SUCCEEDED, data);
    }

    // ---- capability bodies ----

    private static String moduleState() {
        JsonObject out = new JsonObject();
        JsonArray arr = new JsonArray();
        if (mc.isSameThread()) {
            collectModules(arr);
        } else {
            CountDownLatch latch = new CountDownLatch(1);
            mc.execute(() -> { collectModules(arr); latch.countDown(); });
            try { latch.await(1, TimeUnit.SECONDS); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        out.add("modules", arr);
        return out.toString();
    }

    private static void collectModules(JsonArray arr) {
        for (autismclient.modules.Module m : com.autism.seedcracker.compat.ModuleLookup.all()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", String.valueOf(m.id()));
            o.addProperty("name", String.valueOf(m.name()));
            o.addProperty("enabled", m.isEnabled());
            arr.add(o);
        }
    }

    private static String marketStats(String item) {
        JsonObject out = new JsonObject();
        out.addProperty("item", item);
        PriceTracker.Stats s = PriceTracker.stats(item);
        if (s == null) { out.addProperty("known", false); return out.toString(); }
        out.addProperty("known", true);
        out.addProperty("samples", s.samples());
        out.addProperty("min", s.min());
        out.addProperty("median", s.median());
        out.addProperty("mean", s.mean());
        out.addProperty("max", s.max());
        out.addProperty("recentMedian", s.recentMedian());
        return out.toString();
    }

    private static String baseReport(int limit) {
        JsonObject out = new JsonObject();
        JsonArray arr = new JsonArray();
        double px = mc.player != null ? mc.player.getX() : 0;
        double pz = mc.player != null ? mc.player.getZ() : 0;
        for (BaseTracker.Fused f : BaseTracker.fusedNearest(px, pz, Math.max(1, limit))) {
            JsonObject o = new JsonObject();
            o.addProperty("x", f.blockX());
            o.addProperty("z", f.blockZ());
            o.addProperty("confidence", f.confidence());
            o.addProperty("sources", f.sources());
            arr.add(o);
        }
        out.add("bases", arr);
        out.addProperty("count", arr.size());
        return out.toString();
    }

    // ---- helpers ----

    private static String parseItem(String input) {
        try {
            JsonObject o = JsonParser.parseString(input == null || input.isBlank() ? "{}" : input).getAsJsonObject();
            return o.has("item") ? o.get("item").getAsString() : "minecraft:netherite_ingot";
        } catch (Throwable t) { return "minecraft:netherite_ingot"; }
    }

    private static int parseLimit(String input) {
        try {
            JsonObject o = JsonParser.parseString(input == null || input.isBlank() ? "{}" : input).getAsJsonObject();
            return o.has("limit") ? Math.max(1, Math.min(64, o.get("limit").getAsInt())) : 8;
        } catch (Throwable t) { return 8; }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** A one-shot operation whose first tick() returns the already-computed terminal result. */
    private static AiOperation finished(AiOperation.Status status, String dataJson) {
        // 4-arg constructor only: the 5-arg overload takes the obfuscated autismclient.ai.n enum,
        // which would link against a renamed/absent class on other client builds.
        AiOperation.Result result = new AiOperation.Result(status, "", dataJson, List.of());
        return new AiOperation() {
            private boolean done;
            @Override public AiOperation.Result tick() { done = true; return result; }
            @Override public void cancel() { done = true; }
            @Override public boolean canInterruptNow() { return true; }
        };
    }
}
