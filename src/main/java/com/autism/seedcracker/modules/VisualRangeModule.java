package com.autism.seedcracker.modules;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.compat.ClientNotify;
import com.autism.seedcracker.util.FlagLog;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;

/**
 * Visual Range.
 *
 * Alerts the moment any player ENTERS or LEAVES your render distance, with their coordinates at
 * that moment. On anarchy-style servers this is the single most important situational-awareness
 * tool: an enter alert is your cue to hide, log, or fight before they see you; the leave
 * position tells you which way they went.
 */
public final class VisualRangeModule extends Module {

    private final BoolSetting enterAlert = add(new BoolSetting("enter", "Enter alerts", true)
        .description("Alert when a player appears in render distance.").group("General"));
    private final BoolSetting leaveAlert = add(new BoolSetting("leave", "Leave alerts", true)
        .description("Alert when a player despawns, with their last seen position.").group("General"));
    private final BoolSetting sound = add(new BoolSetting("sound", "Sound ping", true)
        .description("Audible ping on enter (you may be looking away).").group("General"));
    private final StringSetting whitelist = add(new StringSetting("whitelist", "Whitelist", "")
        .description("Comma-separated names that never alert (friends/alts).").group("General"));
    private final BoolSetting logCoords = add(new BoolSetting("log", "Log to flag-log", true)
        .description("Record every enter/leave with coordinates for later review (/flaglog).").group("General"));

    /** Players currently in range: entity id -> last known name + position. */
    private final Map<Integer, Seen> inRange = new HashMap<>();

    private record Seen(String name, int x, int y, int z) {}

    public VisualRangeModule() {
        super(SeedcrackerAddon.ID + ":visual-range", "Visual Range",
            "Alerts when players enter or leave your render distance (with coordinates).");
    }

    @Override
    public void onEnable() {
        inRange.clear();
        // Seed with everyone already visible so enabling mid-crowd doesn't fire a wall of alerts.
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.player != null) {
            for (Player p : mc.level.players()) {
                if (p != mc.player) inRange.put(p.getId(), snapshot(p));
            }
        }
    }

    @Override
    public void onGameLeft() {
        inRange.clear();
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        Map<Integer, Seen> current = new HashMap<>();
        for (Player p : mc.level.players()) {
            if (p == mc.player) continue;
            current.put(p.getId(), snapshot(p));
        }

        // Enters.
        for (Map.Entry<Integer, Seen> e : current.entrySet()) {
            if (inRange.containsKey(e.getKey())) continue;
            Seen s = e.getValue();
            if (isWhitelisted(s.name())) continue;
            if (enterAlert.get()) {
                String msg = s.name() + " entered visual range at " + s.x() + " " + s.y() + " " + s.z();
                AutismClientMessaging.sendPrefixed("§c[VisualRange] §f" + msg);
                ClientNotify.warning(msg);
                if (sound.get()) mc.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 0.6f);
            }
            if (logCoords.get()) FlagLog.info("VISUAL", "VisualRange", "enter " + s.name() + " " + s.x() + "," + s.y() + "," + s.z());
        }

        // Leaves (use the LAST snapshot - the entity is gone, so current has no position).
        for (Map.Entry<Integer, Seen> e : inRange.entrySet()) {
            if (current.containsKey(e.getKey())) continue;
            Seen s = e.getValue();
            if (isWhitelisted(s.name())) continue;
            if (leaveAlert.get()) {
                String msg = s.name() + " left visual range near " + s.x() + " " + s.y() + " " + s.z();
                AutismClientMessaging.sendPrefixed("§e[VisualRange] §f" + msg);
            }
            if (logCoords.get()) FlagLog.info("VISUAL", "VisualRange", "leave " + s.name() + " " + s.x() + "," + s.y() + "," + s.z());
        }

        inRange.clear();
        inRange.putAll(current);
    }

    private static Seen snapshot(Player p) {
        return new Seen(p.getPlainTextName(),
            (int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ()));
    }

    private String wlCacheKey;
    private java.util.Set<String> wlCache = java.util.Set.of();

    private boolean isWhitelisted(String name) {
        if (name == null || name.isEmpty()) return false;
        String raw = whitelist.get();
        if (raw == null || raw.isBlank()) return false;
        if (!raw.equals(wlCacheKey)) {
            wlCacheKey = raw;
            java.util.Set<String> next = new java.util.HashSet<>();
            for (String part : raw.split(",")) {
                String t = part.trim().toLowerCase(Locale.ROOT);
                if (!t.isEmpty()) next.add(t);
            }
            wlCache = next;
        }
        return wlCache.contains(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public String info() {
        return inRange.isEmpty() ? "" : inRange.size() + " visible";
    }
}
