package com.autism.seedcracker.modules;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.finder.FinderNotify;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Portal Finder.
 *
 * Flags chunks containing lit nether portals. Away from spawn, a portal is almost always player
 * infrastructure: a base entrance, a highway exit, or a farm's mob portal. In the Nether the
 * same scan finds the overworld-side bases' nether hubs. Scans incrementally (budgeted chunks
 * per tick) with section-level fast-skip so it costs nothing on empty terrain.
 */
public final class PortalFinderModule extends Module {

    private final IntSetting scanRadius = add(new IntSetting("radius", "Scan radius (chunks)", 8, 2, 16, 1)
        .description("Chunk radius scanned around you.").group("General"));
    private final IntSetting minDistance = add(new IntSetting("min-distance", "Min distance (blocks)", 500, 0, 10000, 100)
        .description("Ignore portals closer than this to 0,0 (spawn-area portals are noise).").group("General"));
    private final BoolSetting notify = add(new BoolSetting("notification", "Notification", true)
        .description("Toast + chat when a portal chunk is found.").group("General"));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Tracer", true)
        .description("Tracer to flagged chunks.").group("Render"));
    private final ColorSetting color = add(new ColorSetting("color", "Colour", 0xFF9B30FF)
        .description("Flag colour.").group("Render"));

    private final Set<ChunkPos> flagged = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> scanned = ConcurrentHashMap.newKeySet();
    private final com.autism.seedcracker.finder.FinderReport reporter =
        new com.autism.seedcracker.finder.FinderReport("Portal", 45);
    private int cursor = 0;

    public PortalFinderModule() {
        super(SeedcrackerAddon.ID + ":portal-finder", "Portal Finder",
            "Flags chunks with lit nether portals (player infrastructure away from spawn).");
    }

    @Override
    public void onEnable() {
        flagged.clear();
        scanned.clear();
        cursor = 0;
    }

    @Override
    public void onDisable() {
        ChunkFlagRenderer.clear(id());
        flagged.clear();
        scanned.clear();
    }

    @Override
    public void onGameLeft() {
        flagged.clear();
        scanned.clear();
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        int r = scanRadius.get();
        ChunkPos centre = mc.player.chunkPosition();

        // Round-robin scan, 2 chunks per tick.
        int side = r * 2 + 1;
        int total = side * side;
        int budget = 2;
        for (int i = 0; i < total && budget > 0; i++) {
            int idx = (cursor + i) % total;
            int cx = centre.x() + idx % side - r;
            int cz = centre.z() + idx / side - r;
            ChunkPos pos = new ChunkPos(cx, cz);
            if (scanned.contains(pos) || !mc.level.hasChunk(cx, cz)) continue;
            scanned.add(pos);
            budget--;
            if (containsPortal(mc, mc.level.getChunk(cx, cz))) {
                // Spawn-distance filter in OVERWORLD coords (nether = x8).
                boolean nether = mc.level.dimension() == net.minecraft.world.level.Level.NETHER;
                long bx = (long) pos.getMinBlockX() * (nether ? 8 : 1);
                long bz = (long) pos.getMinBlockZ() * (nether ? 8 : 1);
                if (Math.max(Math.abs(bx), Math.abs(bz)) >= minDistance.get()) {
                    if (flagged.add(pos) && notify.get()) {
                        FinderNotify.flag("§5[PortalFinder]",
                            "Portal at X:" + pos.getMinBlockX() + " Z:" + pos.getMinBlockZ(), true);
                    }
                }
            }
        }
        cursor = (cursor + 1) % total;

        // Prune far scanned/flagged state as the player moves.
        int keep = r + 8;
        scanned.removeIf(p -> Math.abs(p.x() - centre.x()) > keep || Math.abs(p.z() - centre.z()) > keep);
        flagged.removeIf(p -> Math.abs(p.x() - centre.x()) > keep * 4 || Math.abs(p.z() - centre.z()) > keep * 4);

        ChunkFlagRenderer.feed(id(), flagged, color.get(), tracer.get());
        reporter.tick(mc, flagged);
    }

    private static boolean containsPortal(Minecraft mc, LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        for (LevelChunkSection sec : sections) {
            if (sec == null || sec.hasOnlyAir()) continue;
            if (!sec.maybeHas(s -> s.is(Blocks.NETHER_PORTAL))) continue;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        if (sec.getBlockState(x, y, z).is(Blocks.NETHER_PORTAL)) return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public String info() {
        return flagged.isEmpty() ? "" : flagged.size() + " flagged";
    }
}
