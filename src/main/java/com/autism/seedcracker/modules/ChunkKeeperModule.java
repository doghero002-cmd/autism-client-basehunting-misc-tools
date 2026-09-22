package com.autism.seedcracker.modules;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.util.FlagLog;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Chunk Keeper (nyx ChunkKeeperModule port, upgraded to a full chunk HOLDER).
 *
 * Anti-xray plugins reveal blocks (deepslate, ores, storage) and then "fix themselves" by
 * re-sending the chunk / mass block updates with everything hidden again. This module keeps the
 * revealed state:
 *
 *  - HOLD RE-SENDS: a full chunk re-send for a chunk we already have is almost always the
 *    anti-xray refresh - cancel it, keep our revealed copy. (Relog Loader's explicit resend is
 *    let through, as is the first re-send after a held unload.)
 *  - BLOCK RE-HIDES: mass section updates that flip a big batch of blocks to filler
 *    (deepslate/stone/netherrack...) are the anti-xray re-hide signature - cancelled.
 *  - HOLD UNLOADS (optional): cancel chunk unloads so the region stays rendered beyond the
 *    server's bubble (stale but visible - the next legit re-send refreshes it).
 *
 * The original detection stays: chunks re-sent with fewer populated sections get flagged
 * (a strip attempt is intel - the server is hiding something there).
 */
public final class ChunkKeeperModule extends Module {

    private final BoolSetting holdResends = add(new BoolSetting("hold-resends", "Hold chunk re-sends", true)
        .description("Cancel full chunk re-sends for chunks you already have - revealed deepslate/ores/storage stay rendered when the anti-xray re-sends the 'fixed' chunk. Relog Loader's explicit resend still works.")
        .group("Hold"));
    private final BoolSetting blockRehide = add(new BoolSetting("block-rehides", "Block re-hide updates", true)
        .description("Cancel mass block updates that flip a batch of blocks to filler (deepslate/stone/netherrack...) - the anti-xray re-hide signature. Normal block changes are untouched.")
        .group("Hold"));
    private final IntSetting rehideMin = add(new IntSetting("rehide-min", "Re-hide min batch", 16, 4, 256, 4)
        .description("Blocks in one update packet (90%+ filler) before it counts as a re-hide and is cancelled.")
        .group("Hold").visibleWhen(() -> blockRehide.get()));
    private final BoolSetting holdUnloads = add(new BoolSetting("hold-unloads", "Hold unloads", false)
        .description("Cancel chunk unloads: explored terrain stays rendered past the server bubble (stale until the next legit re-send).")
        .group("Hold"));
    private final IntSetting maxTracked = add(new IntSetting("max-tracked", "Max tracked chunks", 4096, 256, 16384, 256)
        .description("Chunk fingerprints kept in memory (oldest evicted).").group("General"));
    private final BoolSetting notify = add(new BoolSetting("notification", "Notification", true)
        .description("Chat ping when a stripped chunk re-send is detected.").group("General"));
    private final ColorSetting color = add(new ColorSetting("color", "Stripped colour", 0xA0FF00FF)
        .description("Marker colour for stripped chunks.").group("Render"));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Tracer", true)
        .description("Tracer to stripped chunk markers.").group("Render"));

    /** chunk key -> last seen populated-section count. */
    private final Map<Long, Integer> sections = new ConcurrentHashMap<>();
    private final Set<ChunkPos> stripped = ConcurrentHashMap.newKeySet();
    /** Chunks whose unload we cancelled: the NEXT re-send is legit (server thinks we forgot). */
    private final Set<Long> pendingRefresh = ConcurrentHashMap.newKeySet();
    private int feedTicks = 0;
    private long heldResends = 0, heldRehides = 0, heldUnloads = 0;

    public ChunkKeeperModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":chunk-keeper", "Chunk Keeper", category,
            "Holds revealed chunks: blocks anti-xray re-sends/re-hides so deepslate & ores stay rendered. Also flags strip attempts.");
    }

    @Override
    public void onDisable() {
        ChunkFlagRenderer.clear(id());
    }

    @Override
    public void onGameLeft() {
        sections.clear();
        stripped.clear();
        pendingRefresh.clear();
        heldResends = heldRehides = heldUnloads = 0;
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public boolean onPacketReceive(Packet<?> packet) {
        Minecraft mc = Minecraft.getInstance();
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunkPacket) {
            final int x = chunkPacket.getX();
            final int z = chunkPacket.getZ();
            long key = (((long) x) << 32) | (z & 0xffffffffL);

            // HOLD: a re-send for a chunk we already have is the anti-xray "fixing itself" -
            // cancel it and keep the revealed copy. Exceptions: the first re-send after a held
            // unload (server legitimately thinks we forgot it) and Relog Loader's explicit resend.
            if (holdResends.get() && mc.level != null
                && !com.autism.seedcracker.rtp.RelogLoaderModule.ACTIVE
                && mc.level.getChunkSource().getChunk(x, z, false) != null) {
                if (pendingRefresh.remove(key)) {
                    // let this one through - it refreshes a held (stale) chunk
                } else {
                    heldResends++;
                    return true;
                }
            }

            // Compare against the previous fingerprint AFTER the world applies it: defer one tick.
            mc.execute(() -> inspect(x, z));
        } else if (packet instanceof ClientboundForgetLevelChunkPacket forget) {
            if (holdUnloads.get() && mc.level != null) {
                heldUnloads++;
                pendingRefresh.add(forget.pos().pack());
                return true; // keep the chunk rendered
            }
            // Chunk unloads keep their fingerprint (that's the point: compare on re-send).
            stripped.remove(forget.pos());
        } else if (blockRehide.get()
            && packet instanceof net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket sup) {
            // Anti-xray re-hide = one packet flipping a big batch of blocks to filler.
            int[] total = {0}, filler = {0};
            sup.runUpdates((pos, state) -> {
                total[0]++;
                if (isFiller(state.getBlock())) filler[0]++;
            });
            if (total[0] >= rehideMin.get() && filler[0] * 10 >= total[0] * 9) {
                heldRehides++;
                return true;
            }
        }
        return false;
    }

    /** Anti-xray mask blocks (what revealed ores get flipped back into). */
    private static boolean isFiller(net.minecraft.world.level.block.Block b) {
        return b == net.minecraft.world.level.block.Blocks.DEEPSLATE
            || b == net.minecraft.world.level.block.Blocks.STONE
            || b == net.minecraft.world.level.block.Blocks.NETHERRACK
            || b == net.minecraft.world.level.block.Blocks.TUFF
            || b == net.minecraft.world.level.block.Blocks.ANDESITE
            || b == net.minecraft.world.level.block.Blocks.DIORITE
            || b == net.minecraft.world.level.block.Blocks.GRANITE
            || b == net.minecraft.world.level.block.Blocks.END_STONE
            || b == net.minecraft.world.level.block.Blocks.BLACKSTONE
            || b == net.minecraft.world.level.block.Blocks.BASALT;
    }

    private void inspect(int x, int z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        LevelChunk chunk = mc.level.getChunkSource().getChunk(x, z, false);
        if (chunk == null) return;

        int populated = 0;
        var chunkSections = chunk.getSections();
        for (var section : chunkSections) {
            if (section != null && !section.hasOnlyAir()) populated++;
        }

        long key = (((long) x) << 32) | (z & 0xffffffffL);
        Integer prev = sections.put(key, populated);
        if (prev != null && populated < prev - 1) { // allow 1-section jitter (lighting edge)
            ChunkPos cp = new ChunkPos(x, z);
            if (stripped.add(cp)) {
                FlagLog.warn("CHUNKKEEP", "ChunkKeeper", "chunk " + x + "," + z
                    + " re-sent with " + populated + " sections (was " + prev + ") - stripped!");
                if (notify.get()) {
                    AutismClientMessaging.sendPrefixed("§d[ChunkKeeper] §fChunk " + (x << 4) + ", " + (z << 4)
                        + " was re-sent STRIPPED (" + prev + " -> " + populated + " sections).");
                }
            }
        }

        // Evict oldest fingerprints over the cap (cheap random eviction is fine here).
        if (sections.size() > maxTracked.get()) {
            var it = sections.keySet().iterator();
            for (int i = 0; i < 64 && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
    }

    @Override
    public void tick() {
        if (++feedTicks < 10) return;
        feedTicks = 0;
        ChunkFlagRenderer.feed(id(), stripped, color.get(), tracer.get());
    }

    @Override
    public String info() {
        long held = heldResends + heldRehides + heldUnloads;
        return held > 0 ? held + " held, " + stripped.size() + " stripped" : stripped.size() + " stripped";
    }
}
