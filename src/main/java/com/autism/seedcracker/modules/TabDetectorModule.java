package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import autismclient.util.AutismNotifications;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Tab Detector.
 *
 * Reads the server tab list / player list each tick and notifies you when specific players
 * (a comma-separated watch list, or any player) join or leave. Optionally also reports players
 * going offline.
 *
 * Clean-room port of the obfuscated Zelith "TabDetector" module.
 */
public final class TabDetectorModule extends Module {

    private final BoolSetting detectAny = add(new BoolSetting("detect-any", "Detect any player", false)
        .description("Notify for every player, ignoring the watch list.")
        .group("General"));
    private final StringSetting targets = add(new StringSetting("targets", "Watch list", "")
        .description("Comma-separated player names to detect (used when 'Detect any player' is off).")
        .group("General"));
    private final BoolSetting logOffline = add(new BoolSetting("log-offline", "Notify when leaving", true)
        .description("Also notify when a watched player leaves the tab list.")
        .group("General"));
    private final BoolSetting toast = add(new BoolSetting("toast", "Toast notification", true)
        .description("Show an on-screen toast in addition to the chat message.")
        .group("Notifications"));
    private final BoolSetting chat = add(new BoolSetting("chat", "Chat message", true)
        .description("Print a chat message in addition to the toast.")
        .group("Notifications"));

    private final Set<String> online = new HashSet<>();

    public TabDetectorModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-tab-detector", "Tab Detector", category,
            "Detects specific players in the tab list and notifies when they join or leave.");
    }

    @Override
    public void onEnable() {
        online.clear();
        snapshot();
    }

    @Override
    public void onDisable() {
        online.clear();
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;

        boolean any = detectAny.get();
        Set<String> watch = watchList();
        if (!any && watch.isEmpty()) {
            online.clear();
            return;
        }

        Set<String> current = new HashSet<>();
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            String name = nameOf(info);
            if (name.isEmpty()) continue;
            if (mc.player != null && name.equalsIgnoreCase(mc.player.getName().getString())) continue;
            if (any || watch.contains(name.toLowerCase(Locale.ROOT))) {
                current.add(name);
            }
        }

        // Joined.
        Set<String> joined = new HashSet<>(current);
        joined.removeAll(online);
        if (!joined.isEmpty()) {
            notifyPlayers(joined, true);
        }

        // Left.
        if (logOffline.get()) {
            Set<String> left = new HashSet<>(online);
            left.removeAll(current);
            if (!left.isEmpty()) {
                notifyPlayers(left, false);
            }
        }

        online.clear();
        online.addAll(current);
    }

    private void snapshot() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;
        boolean any = detectAny.get();
        Set<String> watch = watchList();
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            String name = nameOf(info);
            if (name.isEmpty()) continue;
            if (mc.player != null && name.equalsIgnoreCase(mc.player.getName().getString())) continue;
            if (any || watch.contains(name.toLowerCase(Locale.ROOT))) {
                online.add(name);
            }
        }
    }

    private void notifyPlayers(Set<String> names, boolean joined) {
        String list = String.join(", ", names);
        String verb = joined ? "joined" : "left";
        String message = (names.size() == 1 ? "Player " : "Players ") + verb + ": " + list;
        if (chat.get()) {
            AutismClientMessaging.sendPrefixed((joined ? "§a[Tab Detector] " : "§e[Tab Detector] ") + message);
        }
        if (toast.get()) {
            AutismNotifications.warning("Tab Detector: " + message);
        }
    }

    private static String nameOf(PlayerInfo info) {
        try {
            if (info == null || info.getProfile() == null || info.getProfile().name() == null) return "";
            return info.getProfile().name();
        } catch (Throwable t) {
            return "";
        }
    }

    private Set<String> watchList() {
        String raw = targets.get();
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.replace('\n', ',').replace('\r', ',').split(",")) {
            String trimmed = part == null ? "" : part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed.toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
