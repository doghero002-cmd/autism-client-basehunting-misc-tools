package com.autism.seedcracker.modules;

import java.util.HashSet;
import java.util.Set;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.render.BlockEspRenderer;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Sign Finder.
 *
 * Highlights placed signs and reads their text into chat. Every sign is player-placed (none
 * generate naturally outside structures), so any sign in the wild is base evidence - and the
 * TEXT often names the owner, points to a shop, or marks a highway. An optional keyword filter
 * pings only signs mentioning things you care about.
 */
public final class SignFinderModule extends Module {

    private final BoolSetting readText = add(new BoolSetting("read-text", "Read text in chat", true)
        .description("Print each new sign's text to chat.").group("General"));
    private final StringSetting keywords = add(new StringSetting("keywords", "Keyword filter", "")
        .description("Comma-separated keywords; blank = report every sign, else only matching ones.")
        .group("General"));
    private final BoolSetting tracers = add(new BoolSetting("tracer", "Tracer", false)
        .description("Tracer to each found sign.").group("Render"));
    private final ColorSetting color = add(new ColorSetting("color", "Colour", 0xC04DE1FF)
        .description("Highlight colour.").group("Render"));

    private final Set<BlockPos> found = new HashSet<>();
    /** Signs already read to chat (by position) - cleared per world. */
    private final Set<BlockPos> reported = new HashSet<>();

    public SignFinderModule() {
        super(SeedcrackerAddon.ID + ":sign-finder", "Sign Finder",
            "Highlights signs and reads their text (signs never generate in the wild - all player-placed).");
    }

    @Override
    public void onDisable() {
        BlockEspRenderer.clear(id());
        found.clear();
    }

    @Override
    public void onGameLeft() {
        found.clear();
        reported.clear();
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    private int scanTicks = 0;

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // Signs don't move: rescan twice a second, but FEED every tick (the renderer's feed
        // TTL is 300ms, so a sparser feed makes the markers flicker).
        if (++scanTicks >= 10) {
            scanTicks = 0;
            found.clear();
            int r = 6; // chunk radius; block entities are cheap to enumerate (already in memory)
            var centre = mc.player.chunkPosition();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (!mc.level.hasChunk(centre.x() + dx, centre.z() + dz)) continue;
                    LevelChunk chunk = mc.level.getChunk(centre.x() + dx, centre.z() + dz);
                    for (BlockEntity be : chunk.getBlockEntities().values()) {
                        if (!(be instanceof SignBlockEntity sign)) continue;
                        BlockPos pos = be.getBlockPos();
                        String text = signText(sign);
                        if (!matchesKeywords(text)) continue;
                        found.add(pos);
                        if (readText.get() && !text.isBlank() && reported.add(pos)) {
                            AutismClientMessaging.sendPrefixed("§b[Sign] §f\"" + text + "\" at "
                                + pos.getX() + " " + pos.getY() + " " + pos.getZ());
                        }
                    }
                }
            }
        }

        BlockEspRenderer.feed(id(), found, color.get(), tracers.get(), true);
    }

    /** All four front lines joined (back text is rarely used; front is the message). */
    private static String signText(SignBlockEntity sign) {
        StringBuilder sb = new StringBuilder();
        var front = sign.getFrontText();
        for (int i = 0; i < 4; i++) {
            String line = front.getMessage(i, false).getString().trim();
            if (line.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" / ");
            sb.append(line);
        }
        return sb.toString();
    }

    private String kwCacheKey;
    private java.util.List<String> kwCache = java.util.List.of();

    private boolean matchesKeywords(String text) {
        String raw = keywords.get();
        if (raw == null || raw.isBlank()) return true;
        if (!raw.equals(kwCacheKey)) {
            kwCacheKey = raw;
            java.util.List<String> next = new java.util.ArrayList<>();
            for (String kw : raw.split(",")) {
                String k = kw.trim().toLowerCase(java.util.Locale.ROOT);
                if (!k.isEmpty()) next.add(k);
            }
            kwCache = next;
        }
        if (kwCache.isEmpty()) return true;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (String k : kwCache) {
            if (lower.contains(k)) return true;
        }
        return false;
    }

    @Override
    public String info() {
        return found.isEmpty() ? "" : found.size() + " signs";
    }
}
