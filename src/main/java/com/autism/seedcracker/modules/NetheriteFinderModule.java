package com.autism.seedcracker.modules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.netherite.HiddenDebrisScanner;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Netherite Finder (port of the Anubis Client logic).
 *
 * Finds ancient debris the server tries to HIDE from you (anti-xray). Instead of trusting
 * mc.level.getBlockState (already server-filtered), it reads the raw chunk-packet buffer and scans
 * each section's block palette: a section whose palette still contains the ancient-debris state id
 * while ZERO packed blocks resolve to it had debris edited out of the block data - so it's there,
 * just hidden. Those sections get flagged.
 *
 * Rendering follows the Anubis style: a wireframe edge outline + corner brackets around each
 * flagged section, a translucent shell fill, and a tracer to the nearest one. Sections pulse with
 * a fade-in so a fresh detection reads as a reveal, not a static box.
 */
public final class NetheriteFinderModule extends Module {

    private static final long NOTIFY_COOLDOWN_MS = 8000;

    private final IntSetting range = add(new IntSetting("range", "Range (chunks)", 16, 1, 64, 1)
        .description("Only render flagged sections within this chunk distance (16 chunks = 256 blocks = your full render distance by default).")
        .group("General"));
    private final IntSetting opacity = add(new IntSetting("opacity", "Fill opacity (%)", 18, 0, 100, 2)
        .description("How solid the translucent section-box fill is (0 = outline only, 100 = fully solid).")
        .group("Render"));
    private final ColorSetting color = add(new ColorSetting("color", "Section colour", 0xFFE04030).group("Render"));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Tracer", true)
        .description("Draw a tracer line to the nearest flagged section.").group("Render"));
    private final BoolSetting shellFill = add(new BoolSetting("shell-fill", "Shell fill", true)
        .description("Translucent fill inside flagged sections.").group("Render"));
    private final BoolSetting notify = add(new BoolSetting("notify", "Notify on reveal", true)
        .description("Chat ping when a hidden-debris section is revealed.").group("General"));
    private final BoolSetting probe = add(new BoolSetting("probe", "Auto-probe (confirm)", false)
        .description("Anubis AdvancedFinder: when you're near a flagged section, mine toward the hidden debris to force the server to reveal it (confirms real debris vs a palette artifact). Mines with your held tool.")
        .group("Probe"));
    private final IntSetting probeRange = add(new IntSetting("probe-range", "Probe range (blocks)", 6, 2, 12, 1)
        .description("Only probe flagged sections within this reach of you.")
        .group("Probe").visibleWhen(() -> probe.get()));

    /** Anubis NetheriteChunkTracker.MAXIMUM_CANDIDATES: cap flagged sections, evict oldest first. */
    private static final int MAX_FLAGGED = 512;

    /** sectionKey -> reveal timestamp. key packs chunkX, chunkZ, sectionY. Access-ordered LinkedHashMap
     * that self-evicts the eldest entry past MAX_FLAGGED (the Anubis FIFO candidate cap). Wrapped
     * synchronized: the netty chunk thread writes while the client tick thread reads/prunes. */
    private final Map<Long, Long> flagged = java.util.Collections.synchronizedMap(
        new java.util.LinkedHashMap<Long, Long>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
                return size() > MAX_FLAGGED;
            }
        });
    /** Exact debris blocks revealed by block-update packets (the Anubis AdvancedFinder reveal). Once
     * a block actually becomes ancient_debris in the client's view, the server sends a block update
     * carrying its real position - we render that EXACT block through walls, not just the section. */
    private final Set<BlockPos> revealed = ConcurrentHashMap.newKeySet();
    private long lastNotifyAt = 0;
    private int probeCooldown = 0;
    /** Last dimension we saw, so a nether<->overworld swap (still connected) clears stale flags. */
    private net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> lastDimension = null;

    public NetheriteFinderModule() {
        super(SeedcrackerAddon.ID + ":netherite-finder", "Netherite Finder",
            "Reveals ancient debris the server hides (anti-xray) by scanning chunk-section palettes.");
    }

    @Override
    public void onEnable() {
        // BlockEspRenderer must be init()'d before it renders anything (registers the level render
        // event). Wrapped so a render-event failure can never kill the module's enable/scan.
        try {
            com.autism.seedcracker.render.BlockEspRenderer.init();
            com.autism.seedcracker.compat.ClientNotify.success("[NetheriteFinder] enabled");
        } catch (Throwable t) {
            com.autism.seedcracker.compat.ClientNotify.error("[NetheriteFinder] render init failed: " + t);
        }
    }

    @Override
    public void onDisable() {
        flagged.clear();
        revealed.clear();
        com.autism.seedcracker.render.BlockEspRenderer.clearBox(SeedcrackerAddon.ID + ":netherite-finder");
        com.autism.seedcracker.render.BlockEspRenderer.clearBox(SeedcrackerAddon.ID + ":netherite-finder-exact");
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
        flagged.clear();
        revealed.clear();
        com.autism.seedcracker.render.BlockEspRenderer.clearBox(SeedcrackerAddon.ID + ":netherite-finder");
        com.autism.seedcracker.render.BlockEspRenderer.clearBox(SeedcrackerAddon.ID + ":netherite-finder-exact");
    }

    /** Pack (chunkX, chunkZ, sectionY) into one long. Section Y is biased by +512 so negative
     * sections (nether band / below Y0) pack as non-negative and round-trip correctly. */
    private static long key(int chunkX, int chunkZ, int sectionY) {
        return ((long) (chunkX & 0x1FFFFF) << 43) | ((long) (chunkZ & 0x1FFFFF) << 22) | ((sectionY + 512) & 0x3FFFFF);
    }

    private static int unpackChunkX(long k) { return signExtend((int) (k >>> 43), 21); }
    private static int unpackChunkZ(long k) { return signExtend((int) ((k >>> 22) & 0x1FFFFF), 21); }
    private static int unpackSectionY(long k) { return (int) (k & 0x3FFFFFL) - 512; }
    private static int signExtend(int v, int bits) { int m = 1 << (bits - 1); return (v ^ m) - m; }

    /** Called by the chunk-packet hook with a FRESH buffer wrapper around the packet's chunk data
     * (getReadBuffer() returns a new Unpooled wrapper each call - the packet owns the array, so we
     * don't release it; the wrapper is GC'd). Safe to early-return without touching the buffer. */
    public void onChunkData(net.minecraft.network.FriendlyByteBuf buffer, int chunkX, int chunkZ,
                            int sectionCount, int minSectionY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (!isNether(mc)) return; // ancient debris only matters in the nether

        List<Integer> hidden = HiddenDebrisScanner.findHiddenSections(
            buffer, sectionCount, minSectionY, HiddenDebrisScanner.ancientStateId());
        if (hidden.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (int sectionY : hidden) {
            long k = key(chunkX, chunkZ, sectionY);
            boolean fresh;
            // Persistent flags: a flagged section stays marked until it's genuinely far behind you
            // (out of render distance) or you mine it out, NOT on a short TTL. Short TTLs were the
            // "de-renders when you get close / only detects when close" cause - flags expired before
            // you reached them. The access-ordered map self-evicts the eldest past MAX_FLAGGED.
            synchronized (flagged) {
                fresh = !flagged.containsKey(k);
                flagged.put(k, now); // access-order moves it to the tail; eldest auto-evicts at the cap
            }
            if (fresh && notify.get() && now - lastNotifyAt >= NOTIFY_COOLDOWN_MS) {
                lastNotifyAt = now;
                com.autism.seedcracker.compat.ClientNotify.warning(
                    "Hidden ancient debris at chunk " + chunkX + "," + chunkZ + " section " + sectionY);
            }
        }
    }

    private static boolean isNether(Minecraft mc) {
        return mc.level != null && mc.level.dimension().equals(net.minecraft.world.level.Level.NETHER);
    }

    /** Called by the block-update packet hooks. When a block actually becomes ancient debris in the
     * client's block view (you mined next to it, an explosion exposed it, lava flowed off it), the
     * server sends a block update with the REAL position. Record that exact block so we render it
     * precisely through walls, and stop flagging its section (the debris is found, not hidden). */
    public void onBlockUpdate(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (!isNether(mc)) return;
        // Match the same state id the scanner uses (ancient debris in any orientation/state).
        if (net.minecraft.world.level.block.Block.getId(state) != HiddenDebrisScanner.ancientStateId()) return;
        BlockPos immutable = pos.immutable();
        boolean fresh = revealed.add(immutable);
        // The exact debris is now known - drop the section flag so the big box stops and only the
        // precise block marker remains.
        flagged.remove(key(pos.getX() >> 4, pos.getZ() >> 4, pos.getY() >> 4));
        if (fresh && notify.get()) {
            com.autism.seedcracker.compat.ClientNotify.success(
                "Ancient debris at " + immutable.getX() + " " + immutable.getY() + " " + immutable.getZ());
        }
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (probeCooldown > 0) probeCooldown--;
        if (probe.get()) tickProbe(mc);

        // Anubis trackLevel: a dimension swap (nether<->overworld) while still connected leaves stale
        // flags from the old dimension. Clear everything on the swap so we don't render phantom boxes.
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim = mc.level.dimension();
        if (lastDimension != null && !lastDimension.equals(dim)) {
            flagged.clear();
            revealed.clear();
            com.autism.seedcracker.render.BlockEspRenderer.clearBox(SeedcrackerAddon.ID + ":netherite-finder");
            com.autism.seedcracker.render.BlockEspRenderer.clearBox(SeedcrackerAddon.ID + ":netherite-finder-exact");
        }
        lastDimension = dim;

        // Prune flags: (a) now well beyond render distance, or (b) the debris is now EXPOSED in the
        // block view (you mined/uncovered it) - a revealed section must STOP rendering its box. The
        // "keeps rendering after the debris is found" bug was persistent flags never un-flagging.
        if (!flagged.isEmpty()) {
            ChunkPos centre = mc.player.chunkPosition();
            int pr = range.get() + 4;
            synchronized (flagged) {
                flagged.keySet().removeIf(k -> {
                    int cx = unpackChunkX(k), cz = unpackChunkZ(k);
                    if (Math.max(Math.abs(cx - centre.x()), Math.abs(cz - centre.z())) > pr) return true;
                    return sectionExposed(mc, cx, cz, unpackSectionY(k));
                });
            }
        }

        // Prune revealed exact blocks: drop ones that are no longer ancient debris (you mined it out)
        // or that are now beyond range. Cheap check against the live block view.
        if (!revealed.isEmpty()) {
            ChunkPos centre = mc.player.chunkPosition();
            int pr = range.get() + 4;
            revealed.removeIf(p -> {
                if (Math.max(Math.abs((p.getX() >> 4) - centre.x()), Math.abs((p.getZ() >> 4) - centre.z())) > pr) return true;
                return !mc.level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.ANCIENT_DEBRIS);
            });
        }
        feedExact(mc);

        if (flagged.isEmpty()) {
            com.autism.seedcracker.render.BlockEspRenderer.clearBox(SeedcrackerAddon.ID + ":netherite-finder");
            return;
        }
        feedRenderer(mc);
    }

    /** Feed the exact revealed debris blocks to the renderer as 1x1x1 boxes (brighter + more solid
     * than the section boxes, so the confirmed block stands out through walls). This is the Anubis
     * "precise debris marker" - the block the server confirmed via a block update. */
    private void feedExact(Minecraft mc) {
        String id = SeedcrackerAddon.ID + ":netherite-finder-exact";
        if (revealed.isEmpty()) {
            com.autism.seedcracker.render.BlockEspRenderer.clearBox(id);
            return;
        }
        ChunkPos centre = mc.player.chunkPosition();
        int r = range.get();
        java.util.List<AABB> boxes = new java.util.ArrayList<>();
        for (BlockPos p : revealed) {
            if (Math.max(Math.abs((p.getX() >> 4) - centre.x()), Math.abs((p.getZ() >> 4) - centre.z())) > r) continue;
            boxes.add(new AABB(p.getX(), p.getY(), p.getZ(), p.getX() + 1.0, p.getY() + 1.0, p.getZ() + 1.0));
        }
        if (boxes.isEmpty()) {
            com.autism.seedcracker.render.BlockEspRenderer.clearBox(id);
            return;
        }
        // Bright, near-solid exact marker (independent of the section opacity setting).
        com.autism.seedcracker.render.BlockEspRenderer.feedBoxes(id, boxes, 0xFF50FF50, 120);
    }

    /** True if ancient debris is now VISIBLE in the section's block view (you've mined/uncovered
     * it), so the section should stop rendering as "hidden". Scans the section's exposed faces for
     * ancient debris the client can now actually see. Cheap: only checks blocks near the surface. */
    private static boolean sectionExposed(Minecraft mc, int chunkX, int chunkZ, int secY) {
        if (mc.level == null) return false;
        int minY = secY << 4;
        int baseX = chunkX << 4, baseZ = chunkZ << 4;
        for (int y = minY; y < minY + 16; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    net.minecraft.core.BlockPos p = new net.minecraft.core.BlockPos(baseX + x, y, baseZ + z);
                    if (mc.level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.ANCIENT_DEBRIS)) return true;
                }
            }
        }
        return false;
    }

    // ---- AdvancedFinder probe-confirm (Anubis port) ----

    /**
     * Probe the nearest flagged section within reach: aim at the hidden debris's likely cell and
     * start mining toward it. The section's palette said "debris is here but hidden" - the debris
     * sits somewhere in the 16x16x16 section, so we mine into the section from our side. When the
     * server sees us break toward it, it reveals the real block (the block update confirms it).
     * One probe per few ticks so it reads as deliberate digging, not a bot burst.
     */
    private void tickProbe(Minecraft mc) {
        if (probeCooldown > 0 || mc.gameMode == null) return;
        if (flagged.isEmpty()) return;
        ChunkPos centre = mc.player.chunkPosition();
        double reach = probeRange.get();

        // Nearest flagged section within reach.
        long bestKey = -1;
        double bestDist = Double.MAX_VALUE;
        int bestX = 0, bestZ = 0, bestSecY = 0;
        synchronized (flagged) {
            for (Long k : flagged.keySet()) {
                int chunkX = unpackChunkX(k);
                int chunkZ = unpackChunkZ(k);
                int secY = unpackSectionY(k);
                // Section centre in world coords.
                double cx = (chunkX << 4) + 8.0;
                double cz = (chunkZ << 4) + 8.0;
                double cy = (secY << 4) + 8.0;
                double dx = cx - mc.player.getX();
                double dy = cy - mc.player.getEyeY();
                double dz = cz - mc.player.getZ();
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dist < bestDist) { bestDist = dist; bestKey = k; bestX = chunkX; bestZ = chunkZ; bestSecY = secY; }
            }
        }
        if (bestKey < 0 || bestDist > reach) return;

        // Aim at the section centre (where the hidden debris likely is) and mine toward it.
        double tx = (bestX << 4) + 8.0, ty = (bestSecY << 4) + 8.0, tz = (bestZ << 4) + 8.0;
        double dx = tx - mc.player.getX();
        double dz = tz - mc.player.getZ();
        double dy = ty - mc.player.getEyeY();
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, dist));
        mc.player.setYRot(yaw);
        mc.player.setXRot(net.minecraft.util.Mth.clamp(pitch, -90f, 90f));

        // Mine the block we're now looking at (toward the hidden debris).
        if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
            && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            mc.gameMode.startDestroyBlock(hit.getBlockPos(), hit.getDirection());
            mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            probeCooldown = 4; // deliberate pace (not a bot burst)
        }
    }

    /** Feed every in-range flagged section to the through-wall renderer as its own 16x16x16 box
     * (Anubis NetheriteVisuals renders each section box individually - no merging - so a big flagged
     * field shows every marked section). Each box is a wireframe + translucent shell fill, through
     * walls and lava. */
    private void feedRenderer(Minecraft mc) {
        ChunkPos centre = mc.player.chunkPosition();
        int r = range.get();
        // Collect in-range flagged sections as (x,y,z) section coords.
        java.util.List<int[]> sections = new java.util.ArrayList<>();
        synchronized (flagged) {
            for (Long k : flagged.keySet()) {
                int chunkX = unpackChunkX(k);
                int chunkZ = unpackChunkZ(k);
                int secY = unpackSectionY(k);
                int dx = chunkX - centre.x(), dz = chunkZ - centre.z();
                if (Math.max(Math.abs(dx), Math.abs(dz)) > r) continue;
                sections.add(new int[]{chunkX, secY, chunkZ});
            }
        }
        // One 16x16x16 box per flagged section (Anubis renders each section box individually - no
        // merging - so a big flagged field shows every marked section, not one giant merged blob).
        java.util.List<AABB> boxes = new java.util.ArrayList<>();
        for (int[] s : sections) {
            boxes.add(new AABB(s[0] * 16.0, s[1] * 16.0, s[2] * 16.0,
                s[0] * 16.0 + 16.0, s[1] * 16.0 + 16.0, s[2] * 16.0 + 16.0));
        }
        // Opacity setting -> fill alpha (0-100% -> 0-255).
        int fillAlpha = shellFill.get() ? (int) Math.round(opacity.get() * 2.55) : 0;
        com.autism.seedcracker.render.BlockEspRenderer.feedBoxes(
            SeedcrackerAddon.ID + ":netherite-finder", boxes, color.get(), fillAlpha);
    }

    @Override
    public String info() {
        return flagged.isEmpty() ? "watching" : flagged.size() + " sections";
    }
}
