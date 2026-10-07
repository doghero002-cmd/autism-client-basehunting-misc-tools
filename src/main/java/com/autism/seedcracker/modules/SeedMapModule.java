package com.autism.seedcracker.modules;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.bedrock.SeedSeedProvider;
import com.autism.seedcracker.seedmap.SeedStructures;
import com.autism.seedcracker.seedmap.SeedStructures.Hit;
import com.autism.seedcracker.seedmap.SeedStructures.Type;
import com.seedfinding.mccore.state.Dimension;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

/**
 * Seed Map: once the world seed is cracked (or typed in), shows a biome minimap around you with
 * the predicted structures of the types you pick, and marks the nearest N of each in the world.
 * Everything is computed off-thread from the seed, so it works for chunks you've never loaded.
 */
public final class SeedMapModule extends Module {

    private static final Identifier LAYER_ID = Identifier.fromNamespaceAndPath(SeedcrackerAddon.ID, "seed_map");
    private static final String ESP_ID = "seed-map";

    private final StringSetting structures = add(new StringSetting("structures", "Structures",
            "village,stronghold,mansion,monument,outpost,fortress,bastion,end_city")
        .description("Comma list: village, stronghold, mansion, monument, outpost, desert_temple, jungle_temple, swamp_hut, "
            + "igloo, shipwreck, ocean_ruin, buried_treasure, ruined_portal, fortress, bastion, nether_ruined_portal, end_city.")
        .group("Structures"));
    private final IntSetting nearestCount = add(new IntSetting("nearest", "Nearest per type", 1, 1, 10, 1)
        .description("How many of each type to list and mark.").group("Structures"));
    private final IntSetting searchRegions = add(new IntSetting("search", "Search radius (regions)", 6, 1, 30, 1)
        .description("How far to look, in each structure's spacing regions (~6 = several thousand blocks for villages).")
        .group("Structures"));
    private final StringSetting manualSeed = add(new StringSetting("seed", "Seed override", "")
        .description("Leave blank to use the cracked seed. Type a seed here to use it instead.").group("Structures"));
    private final BoolSetting waypoints = add(new BoolSetting("waypoints", "Mark in world", true)
        .description("Outline each nearest structure's centre column in the world, with a tracer.").group("Waypoints"));
    private final BoolSetting tracers = add(new BoolSetting("tracers", "Tracers", true).group("Waypoints")
        .visibleWhen(waypoints::get));
    private final BoolSetting chatList = add(new BoolSetting("chat-list", "List in chat on update", false)
        .description("Print the nearest structures to chat whenever the list changes.").group("Waypoints"));

    private final BoolSetting minimap = add(new BoolSetting("minimap", "Minimap", true).group("Minimap"));
    private final IntSetting mapX = add(new IntSetting("x", "X", 6, 0, 4000, 1).group("Minimap").visibleWhen(minimap::get));
    private final IntSetting mapY = add(new IntSetting("y", "Y", 6, 0, 4000, 1).group("Minimap").visibleWhen(minimap::get));
    private final IntSetting mapSize = add(new IntSetting("size", "Size", 128, 64, 400, 8).group("Minimap").visibleWhen(minimap::get));
    private final IntSetting zoom = add(new IntSetting("zoom", "Blocks per pixel", 16, 1, 256, 1)
        .description("Map scale. Biome sampling is per 4x4 pixels, so big zooms stay cheap.").group("Minimap").visibleWhen(minimap::get));
    private final BoolSetting biomesOn = add(new BoolSetting("biomes", "Biome colours", true).group("Minimap").visibleWhen(minimap::get));

    private static SeedMapModule instance;
    private static boolean layerRegistered;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "seedmap");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private volatile SeedStructures engine;
    private volatile List<Hit> hits = List.of();
    /** Built off-thread and swapped in whole, so the renderer never sees colours from one tile with another's anchor. */
    private record Tile(int[] colors, int centerX, int centerZ, int zoom, int size) {}
    private volatile Tile tile;
    private Dimension lastDim;
    private volatile boolean computing;
    private BlockPos lastQueryAt;
    private String lastQueryKey = "";
    private String lastListed = "";
    private int ticks;

    public SeedMapModule() {
        super(SeedcrackerAddon.ID + ":seed-map", "Seed Map",
            "Biome minimap + nearest structures from the cracked seed (or one you type), with in-world waypoints.");
    }

    @Override
    public void onEnable() {
        instance = this;
        engine = null;
        hits = List.of();
        tile = null;
        lastQueryAt = null;
        if (!layerRegistered) {
            layerRegistered = true;
            HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, LAYER_ID,
                (GuiGraphicsExtractor ctx, DeltaTracker delta) -> {
                    SeedMapModule m = instance;
                    if (m != null && m.isEnabled()) m.render(ctx);
                });
        }
    }

    @Override
    public void onDisable() {
        com.autism.seedcracker.render.BlockEspRenderer.clear(ESP_ID);
        instance = null;
    }

    @Override
    public void onGameLeft() {
        engine = null;
        hits = List.of();
        tile = null;
        com.autism.seedcracker.render.BlockEspRenderer.clear(ESP_ID);
    }

    private Long seed() {
        String s = manualSeed.get().trim();
        if (!s.isEmpty()) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                // Vanilla turns a non-numeric seed into its String hash.
                return (long) s.hashCode();
            }
        }
        return SeedSeedProvider.crackedSeedPublic();
    }

    private static Dimension dim(Minecraft mc) {
        if (mc.level.dimension() == Level.NETHER) return Dimension.NETHER;
        if (mc.level.dimension() == Level.END) return Dimension.END;
        return Dimension.OVERWORLD;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        Long seed = seed();
        if (seed == null) return;
        if (engine == null || engine.seed() != seed) {
            engine = new SeedStructures(seed, kaptainwutax.seedcrackerX.config.Config.get().getVersion());
            hits = List.of();
            tile = null;
            lastQueryAt = null;
        }
        Dimension d = dim(mc);
        if (d != lastDim) {
            // Overworld structures plotted in the Nether (or vice versa) for a second is worse than an empty list.
            lastDim = d;
            hits = List.of();
            tile = null;
            lastQueryAt = null;
        }
        if (++ticks % 10 != 0 || computing) {
            feedWaypoints();
            return;
        }
        BlockPos at = mc.player.blockPosition();
        String key = structures.get() + "|" + nearestCount.get() + "|" + searchRegions.get() + "|" + d;
        boolean moved = lastQueryAt == null || lastQueryAt.distManhattan(at) > 64;
        int zoomNow = zoom.get(), sizeNow = mapSize.get();
        Tile t = tile;
        int drift = zoomNow * sizeNow / 4;
        boolean tileStale = minimap.get() && biomesOn.get()
            && (t == null || t.zoom() != zoomNow || t.size() != sizeNow
                || Math.abs(at.getX() - t.centerX()) > drift || Math.abs(at.getZ() - t.centerZ()) > drift);
        if (!moved && key.equals(lastQueryKey) && !tileStale) {
            feedWaypoints();
            return;
        }
        lastQueryAt = at;
        lastQueryKey = key;
        computing = true;
        SeedStructures eng = engine;
        List<Type> types = SeedStructures.parse(structures.get());
        int count = nearestCount.get(), regions = searchRegions.get();
        int px = at.getX(), pz = at.getZ();
        boolean wantTile = tileStale;
        CompletableFuture.runAsync(() -> {
            try {
                List<Hit> h = eng.nearest(types, d, px, pz, count, regions);
                if (eng == engine) hits = h;
                if (wantTile) buildTile(eng, d, px, pz, zoomNow, sizeNow);
            } finally {
                computing = false;
            }
        }, worker);
        feedWaypoints();
    }

    /** Biome colours for the map, sampled every 4 pixels (biome noise is 4x4 blocks at best anyway). */
    private void buildTile(SeedStructures eng, Dimension d, int cx, int cz, int z, int size) {
        int cells = size / 4;
        int[] colors = new int[cells * cells];
        int span = size * z;
        for (int j = 0; j < cells; j++) for (int i = 0; i < cells; i++) {
            int wx = cx - span / 2 + (i * 4 + 2) * z;
            int wz = cz - span / 2 + (j * 4 + 2) * z;
            colors[j * cells + i] = eng.biomeColor(d, wx, wz);
        }
        if (eng == engine && d == lastDim) tile = new Tile(colors, cx, cz, z, size);
    }

    private void feedWaypoints() {
        List<Hit> h = hits;
        if (!waypoints.get() || h.isEmpty()) {
            com.autism.seedcracker.render.BlockEspRenderer.clear(ESP_ID);
        } else {
            java.util.Set<BlockPos> cols = new java.util.HashSet<>();
            for (Hit hit : h) cols.add(new BlockPos(hit.blockX(), 64, hit.blockZ()));
            com.autism.seedcracker.render.BlockEspRenderer.feed(ESP_ID, cols, 0xFFB45CFF, tracers.get(), false);
        }
        if (chatList.get()) {
            String sig = describe(h);
            if (!sig.isEmpty() && !sig.equals(lastListed)) {
                lastListed = sig;
                AutismClientMessaging.sendPrefixed("\u00a7d[SeedMap] \u00a7f" + sig);
            }
        }
    }

    private static String describe(List<Hit> h) {
        List<String> parts = new ArrayList<>();
        for (Hit hit : h) parts.add(hit.type().label + " " + hit.blockX() + " " + hit.blockZ() + " (" + Math.round(hit.dist()) + "m)");
        return String.join(", ", parts);
    }

    /** For .seedmap and the chat bot: current nearest list (may be empty until the first pass finishes). */
    public static List<Hit> current() {
        SeedMapModule m = instance;
        return m == null ? List.of() : m.hits;
    }

    private void render(GuiGraphicsExtractor g) {
        if (!minimap.get()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.screen() != null && !(mc.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen)) return;
        int x0 = mapX.get(), y0 = mapY.get(), size = mapSize.get(), z = zoom.get();
        g.fill(x0 - 1, y0 - 1, x0 + size + 1, y0 + size + 1, 0xFF000000);
        g.fill(x0, y0, x0 + size, y0 + size, 0xFF151518);
        int px = mc.player.getBlockX(), pz = mc.player.getBlockZ();
        Tile t = tile;
        if (biomesOn.get() && t != null && t.zoom() == z && t.size() == size) {
            int cells = size / 4;
            // The tile was built around an older centre: shift it so it stays world-aligned while the next one computes.
            int ox = Math.floorDiv(t.centerX() - px, z), oz = Math.floorDiv(t.centerZ() - pz, z);
            g.enableScissor(x0, y0, x0 + size, y0 + size);
            for (int j = 0; j < cells; j++) for (int i = 0; i < cells; i++) {
                int sx = x0 + i * 4 + ox, sy = y0 + j * 4 + oz;
                g.fill(sx, sy, sx + 4, sy + 4, t.colors()[j * cells + i]);
            }
            g.disableScissor();
        }
        int half = size / 2;
        var font = mc.font;
        for (Hit h : hits) {
            int sx = x0 + half + Math.floorDiv(h.blockX() - px, z), sy = y0 + half + Math.floorDiv(h.blockZ() - pz, z);
            int cx = Math.max(x0 + 2, Math.min(x0 + size - 3, sx)), cy = Math.max(y0 + 2, Math.min(y0 + size - 3, sy));
            boolean edge = cx != sx || cy != sy;
            g.fill(cx - 2, cy - 2, cx + 3, cy + 3, 0xFF000000);
            g.fill(cx - 1, cy - 1, cx + 2, cy + 2, edge ? (h.type().color & 0x00FFFFFF) | 0x99000000 : h.type().color);
        }
        // Player marker + facing tick.
        int mx = x0 + half, my = y0 + half;
        g.fill(mx - 2, my - 2, mx + 3, my + 3, 0xFFFFFFFF);
        double yaw = Math.toRadians(mc.player.getYRot());
        int fx = mx + (int) Math.round(-Math.sin(yaw) * 6), fz = my + (int) Math.round(Math.cos(yaw) * 6);
        g.fill(fx - 1, fz - 1, fx + 1, fz + 1, 0xFFFF4040);
        // Nearest list under the map.
        int ty = y0 + size + 4;
        if (engine == null) {
            g.text(font, seed() == null ? "No seed yet (crack it or set one)" : "Loading seed...", x0, ty, 0xFFFFC857, true);
            return;
        }
        int shown = 0;
        for (Hit h : hits) {
            if (shown++ >= 6) break;
            String line = String.format(Locale.ROOT, "%s %d %d  %dm", h.type().label, h.blockX(), h.blockZ(), Math.round(h.dist()));
            g.text(font, line, x0, ty, h.type().color, true);
            ty += 10;
        }
        if (computing) g.text(font, "...", x0 + size - 10, y0 + 2, 0xFFFFFFFF, true);
    }
}
