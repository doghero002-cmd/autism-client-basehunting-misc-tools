package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import com.autism.seedcracker.compat.ClientNotify;
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

    // Staff detection (Water StaffDetector port): rank tags live in the tab DISPLAY name / team
    // prefix, not the profile name, so scan those for staff keywords.
    private final BoolSetting staffScan = add(new BoolSetting("staff-scan", "Staff detector", false)
        .description("Scan every tab entry's display name + team prefix/suffix for staff rank tags (admin, mod, helper, owner...). Alerts when staff come online - time to look legit.")
        .group("Staff"));
    private final StringSetting staffRanks = add(new StringSetting("staff-ranks", "Rank keywords",
            "admin,mod,moderator,staff,owner,helper,dev,developer,manager,support,operator,sentinel")
        .description("Comma-separated rank keywords matched case-insensitively inside the decorated tab name.")
        .group("Staff").visibleWhen(() -> staffScan.get()));
    private final BoolSetting staffLeaveAlert = add(new BoolSetting("staff-leave-alert", "Alert when staff leave", true)
        .description("Also notify when detected staff disappear from the tab list.")
        .group("Staff").visibleWhen(() -> staffScan.get()));

    private final Set<String> online = new HashSet<>();
    /** name -> matched rank keyword, for currently-online detected staff. */
    private final java.util.Map<String, String> staffOnline = new java.util.LinkedHashMap<>();
    private int pollTicks = 0;

    public TabDetectorModule() {
        super(SeedcrackerAddon.ID + ":z-tab-detector", "Tab Detector",
            "Detects specific players in the tab list and notifies when they join or leave.");
    }

    @Override
    public void onEnable() {
        online.clear();
        staffOnline.clear();
        pollTicks = 0;
        snapshot();
    }

    @Override
    public void onDisable() {
        online.clear();
        staffOnline.clear();
    }

    @Override
    public void tick() {
        // Poll the tab list twice a second, not every tick: joins/leaves are rare events, and the
        // old per-tick scan rebuilt the watchlist + allocated 3 HashSets 20x/sec for nothing.
        if (++pollTicks < 10) return;
        pollTicks = 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;

        if (staffScan.get()) tickStaff(mc);

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
            ClientNotify.warning("Tab Detector: " + message);
        }
    }

    /** Staff scan: rank tags live in the decorated tab name (display name or team prefix/suffix). */
    private void tickStaff(Minecraft mc) {
        Set<String> ranks = splitList(staffRanks.get());
        if (ranks.isEmpty()) return;
        Set<String> seen = new HashSet<>();
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            String name = nameOf(info);
            if (name.isEmpty()) continue;
            // Strip the profile name before matching: a player NAMED "Moddy"/"Helperman" must not
            // false-flag - only the rank decoration (prefix/suffix) counts.
            String decorated = decoratedName(info, name).replace(name, "").toLowerCase(Locale.ROOT);
            String matched = null;
            for (String rank : ranks) {
                if (decorated.contains(rank)) { matched = rank; break; }
            }
            if (matched == null) continue;
            seen.add(name);
            if (staffOnline.put(name, matched) == null) {
                String msg = "STAFF online: " + name + " (" + matched + ")";
                if (chat.get()) AutismClientMessaging.sendPrefixed("\u00a7c[Tab Detector] \u00a7f" + msg);
                if (toast.get()) ClientNotify.warning(msg);
                if (mc.player != null) mc.player.playSound(
                    net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 0.5f);
            }
        }
        if (staffLeaveAlert.get()) {
            java.util.Iterator<java.util.Map.Entry<String, String>> it = staffOnline.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<String, String> e = it.next();
                if (seen.contains(e.getKey())) continue;
                it.remove();
                String msg = "Staff left: " + e.getKey() + " (" + e.getValue() + ")";
                if (chat.get()) AutismClientMessaging.sendPrefixed("\u00a7a[Tab Detector] \u00a7f" + msg);
                if (toast.get()) ClientNotify.warning(msg);
            }
        } else {
            staffOnline.keySet().retainAll(seen);
        }
    }

    /** Tab display name if set, else team prefix + name + suffix (where rank tags live). */
    private static String decoratedName(PlayerInfo info, String name) {
        try {
            if (info.getTabListDisplayName() != null) return info.getTabListDisplayName().getString();
            if (info.getTeam() != null) {
                return info.getTeam().getPlayerPrefix().getString() + name + info.getTeam().getPlayerSuffix().getString();
            }
        } catch (Throwable ignored) {}
        return name;
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
        return splitList(targets.get());
    }

    private static Set<String> splitList(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.replace('\n', ',').replace('\r', ',').split(",")) {
            String trimmed = part == null ? "" : part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    @Override
    public String info() {
        return staffScan.get() && !staffOnline.isEmpty() ? staffOnline.size() + " staff" : null;
    }
}
