package com.autism.seedcracker.modules;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.render.BlockEspRenderer;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Amethyst ESP.
 *
 * Scans the chunks around you for amethyst geodes - clusters of amethyst blocks / budding
 * amethyst - and highlights the amethyst blocks, marks the chunk, draws a tracer, and pings chat
 * when a new geode is found.
 *
 * Clean-room port of the obfuscated Zelith "AmethystESP" module against the AUTISM module API.
 * Detection looks for amethyst-cluster blocks with geode blocks nearby, matching the original's
 * cluster heuristic, but uses the shared chunk scanner + block ESP renderer instead of the
 * original's custom GL renderer.
 */
public final class AmethystEspModule extends Module {

    private static final int MAX_Y = 50;

    private final IntSetting simDistance = add(new IntSetting(
            "sim-distance", "Sim distance (chunks)", 8, 1, 32, 1)
        .description("Chunk bubble scanned for amethyst geodes.")
        .group("General"));
    private final IntSetting minCluster = add(new IntSetting(
            "min-cluster", "Min cluster size", 3, 1, 20, 1)
        .description("Amethyst blocks needed in a chunk to flag it as a geode.")
        .group("General"));
    private final BoolSetting blockEsp = add(new BoolSetting("block-esp", "Block ESP", true)
        .description("Highlight each amethyst block.").group("Render"));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Show tracers", true)
        .description("Draw a tracer line to the nearest amethyst block.").group("Render"));
    private final BoolSetting fill = add(new BoolSetting("fill", "Fill boxes", false)
        .description("Translucent fill on the amethyst blocks.").group("Render"));
    private final BoolSetting chatAlert = add(new BoolSetting("chat-alert", "Chat alert", true)
        .description("Chat message when a new geode is found.").group("General"));
    private final ColorSetting color = add(new ColorSetting("color", "ESP colour", 0xFFB464FF)
        .description("Colour of the amethyst markers.").group("Render"));

    /** chunkKey -> set of amethyst block positions in that chunk. */
    private final Map<Long, Set<BlockPos>> flagged = new ConcurrentHashMap<>();
    private final Set<Long> notified = ConcurrentHashMap.newKeySet();
    private int cursor = 0;

    public AmethystEspModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":amethyst-esp", "Amethyst ESP", category,
            "Highlights amethyst geodes (cluster blocks) around you.");
    }

    @Override
    public void onEnable() {
        BlockEspRenderer.init();
        flagged.clear();
        notified.clear();
        cursor = 0;
    }

    @Override
    public void onDisable() {
        flagged.clear();
        notified.clear();
        BlockEspRenderer.clear(SeedcrackerAddon.ID + ":amethyst-esp");
    }

    @Override
    public void onGameLeft() {
        setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // Spread the chunk scan across ticks (a few chunks per tick, round-robin over the bubble).
        int range = simDistance.get();
        ChunkPos centre = mc.player.chunkPosition();
        int side = range * 2 + 1;
        int total = side * side;
        for (int i = 0; i < 8; i++) {
            int idx = cursor % total;
            cursor = (cursor + 1) % total;
            int dx = idx % side - range;
            int dz = idx / side - range;
            int cx = centre.x() + dx, cz = centre.z() + dz;
            if (!mc.level.hasChunk(cx, cz)) continue;
            scanChunk(mc.level.getChunk(cx, cz));
        }

        // Prune out-of-range chunks.
        int pr = range + 2;
        flagged.keySet().removeIf(key -> {
            int kx = (int) (key >> 32);
            int kz = (int) (key & 0xffffffffL);
            return Math.abs(kx - centre.x()) > pr || Math.abs(kz - centre.z()) > pr;
        });

        // Feed the renderer with the union of all flagged blocks.
        if (blockEsp.get()) {
            Set<BlockPos> all = new HashSet<>();
            for (Set<BlockPos> s : flagged.values()) all.addAll(s);
            BlockEspRenderer.feed(SeedcrackerAddon.ID + ":amethyst-esp", all, color.get(), tracer.get(), fill.get());
        }
    }

    private void scanChunk(LevelChunk chunk) {
        if (chunk == null) return;
        ChunkPos pos = chunk.getPos();
        long key = ((long) pos.x() << 32) | (pos.z() & 0xffffffffL);
        Set<BlockPos> found = new HashSet<>();
        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = -64; y <= MAX_Y; y++) {
                    BlockPos p = new BlockPos(baseX + x, y, baseZ + z);
                    BlockState st = chunk.getBlockState(p);
                    if (isAmethystCluster(st) && hasGeodeNearby(chunk, p)) {
                        found.add(p.immutable());
                    }
                }
            }
        }
        if (found.size() >= minCluster.get()) {
            flagged.put(key, found);
            if (chatAlert.get() && notified.add(key)) {
                AutismClientMessaging.sendPrefixed("§d[AmethystESP] §fAmethyst cluster at X:" + pos.getMinBlockX()
                    + " Z:" + pos.getMinBlockZ() + " §7(" + found.size() + " blocks)");
            }
        } else {
            flagged.remove(key);
        }
    }

    private static boolean isAmethystCluster(BlockState st) {
        return st.is(Blocks.AMETHYST_CLUSTER)
            || st.is(Blocks.LARGE_AMETHYST_BUD)
            || st.is(Blocks.MEDIUM_AMETHYST_BUD)
            || st.is(Blocks.SMALL_AMETHYST_BUD);
    }

    /** True if any geode block (amethyst block / budding amethyst / calcite / smooth basalt) is within 1 of pos. */
    private static boolean hasGeodeNearby(LevelChunk chunk, BlockPos pos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockState st = chunk.getBlockState(pos.offset(dx, dy, dz));
                    if (st.is(Blocks.AMETHYST_BLOCK) || st.is(Blocks.BUDDING_AMETHYST)
                        || st.is(Blocks.CALCITE) || st.is(Blocks.SMOOTH_BASALT)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
