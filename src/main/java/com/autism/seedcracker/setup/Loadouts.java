package com.autism.seedcracker.setup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.compat.ModuleLookup;

import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;

/**
 * One-click "loadouts" (presets) that answer the single most common new-user question: "I want to
 * do X, which of these 90-odd modules do I turn on?"
 *
 * Each loadout is a short, curated list of module ids plus a few setting overrides that make them
 * work well together out of the box. Applying a loadout enables exactly those modules (and,
 * optionally, disables every other addon module first so the user lands on a clean, known-good
 * setup instead of their modules layered on top of whatever was already running).
 *
 * This is pure data + a tiny apply loop so it can be driven from both the QQL Setup module's
 * buttons and the {@code .qql} command.
 */
public final class Loadouts {
    private Loadouts() {}

    /** A named preset: the modules it switches on and the settings it tunes for them. */
    public static final class Loadout {
        public final String key;        // short token used by the command (e.g. "stash")
        public final String name;       // display name
        public final String summary;    // one-line "what this is for"
        public final List<String> moduleIds;                 // ids to enable (without the addon prefix)
        public final List<SettingOverride> overrides;        // settings to apply after enabling

        Loadout(String key, String name, String summary, List<String> moduleIds, List<SettingOverride> overrides) {
            this.key = key;
            this.name = name;
            this.summary = summary;
            this.moduleIds = moduleIds;
            this.overrides = overrides;
        }
    }

    /** A single "set this module's setting to this value" instruction, applied on loadout apply. */
    public static final class SettingOverride {
        public final String moduleId;
        public final String settingId;
        public final String value;

        SettingOverride(String moduleId, String settingId, String value) {
            this.moduleId = moduleId;
            this.settingId = settingId;
            this.value = value;
        }
    }

    private static final Map<String, Loadout> LOADOUTS = new LinkedHashMap<>();

    private static void define(String key, String name, String summary,
                               List<String> moduleIds, List<SettingOverride> overrides) {
        LOADOUTS.put(key, new Loadout(key, name, summary, moduleIds, overrides));
    }

    private static SettingOverride set(String moduleId, String settingId, String value) {
        return new SettingOverride(moduleId, settingId, value);
    }

    static {
        define("stash", "Stash Hunting",
            "Find and clear buried/overworld stashes around RTP spots.",
            List.of(":z-stash-finder", ":relog-loader", ":prime-chunk-finder", ":z-auto-render",
                ":elytra-warner", ":auto-firework", ":spectator-detector", ":amethyst-esp",
                ":storage-recorder", ":chunk-keeper"),
            List.of());

        define("tunnel", "Tunnel Base",
            "Hunt 2-wide tunnel bases with the water-safe scanner.",
            List.of(":tunnel-base-finder", ":storage-recorder", ":prime-chunk-finder",
                ":finder-overlay", ":amethyst-esp"),
            List.of());

        define("trading", "Trading / AH",
            "Auction-house flipping, sniping and shop buying.",
            List.of(":ah-flipper", ":ah-sniper", ":price-check"),
            List.of(
                // Safe starting point for the sniper: snipe exactly one item then stop, so a
                // misconfigured price can't drain a balance.
                set(":ah-sniper", "max-buys", "1")));

        define("pvp", "PvP / Utility",
            "Everyday survival QoL plus anti-death safety nets.",
            List.of(":z-sprint", ":z-fast-place", ":z-auto-eat", ":z-auto-log",
                ":player-panic", ":chest-stealer", ":mace-pvp"),
            List.of());

        define("safety", "Stay Safe",
            "Staff/anti-cheat detection and panic exits, nothing that moves you.",
            List.of(":z-auto-log", ":player-panic", ":flag-detector", ":spectator-detector",
                ":z-tab-detector", ":session-guard", ":macro-protector"),
            List.of());
    }

    /** All loadouts in display order. */
    public static List<Loadout> all() {
        return new ArrayList<>(LOADOUTS.values());
    }

    /** Look up a loadout by its short key (case-insensitive), or null. */
    public static Loadout byKey(String key) {
        if (key == null) return null;
        return LOADOUTS.get(key.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Enable every module in the loadout. When {@code exclusive} is true, all other addon modules
     * are switched off first so the result is exactly the loadout. Returns a short chat-ready
     * summary of what happened.
     */
    public static String apply(Loadout loadout, boolean exclusive) {
        if (loadout == null) return "§cUnknown loadout.";

        int enabled = 0;
        int disabled = 0;
        List<String> missing = new ArrayList<>();

        if (exclusive) {
            for (Module m : ModuleLookup.all()) {
                if (m == null || m.id() == null) continue;
                // Never fight the setup module itself.
                if (m.id().endsWith(":qql-setup")) continue;
                if (m.isEnabled()) {
                    try { m.setEnabled(false); disabled++; } catch (Throwable ignored) {}
                }
            }
        }

        for (String shortId : loadout.moduleIds) {
            Module m = ModuleLookup.get(SeedcrackerAddon.ID + shortId);
            if (m == null) { missing.add(shortId); continue; }
            try {
                if (!m.isEnabled()) m.setEnabled(true);
                enabled++;
            } catch (Throwable t) {
                missing.add(shortId);
            }
        }

        for (SettingOverride o : loadout.overrides) {
            Module m = ModuleLookup.get(SeedcrackerAddon.ID + o.moduleId);
            if (m == null || m.setting(o.settingId) == null) continue;
            try { m.setValue(o.settingId, o.value); } catch (Throwable ignored) {}
        }

        StringBuilder sb = new StringBuilder();
        sb.append("§a[QQL] §fLoadout §b").append(loadout.name).append("§f applied: §a")
          .append(enabled).append("§f module(s) on");
        if (exclusive && disabled > 0) sb.append("§7, ").append(disabled).append(" turned off");
        sb.append('.');
        if (!missing.isEmpty()) {
            sb.append(" §eMissing: ").append(String.join(", ", missing));
        }
        return sb.toString();
    }

    /** Turn off every addon module (the "panic / clean slate" button). Returns a summary line. */
    public static String disableAll() {
        int disabled = 0;
        for (Module m : ModuleLookup.all()) {
            if (m == null || m.id() == null) continue;
            if (m.id().endsWith(":qql-setup")) continue;
            if (m.isEnabled()) {
                try { m.setEnabled(false); disabled++; } catch (Throwable ignored) {}
            }
        }
        return "§a[QQL] §fDisabled §e" + disabled + "§f addon module(s).";
    }

    /** Send a chat overview of every loadout and the modules it enables (for the command). */
    public static void printOverview() {
        AutismClientMessaging.sendPrefixed("§b[QQL] §fOne-click loadouts — use §e.qql <name>§f to apply:");
        for (Loadout l : all()) {
            AutismClientMessaging.send("§e" + l.key + " §8(" + l.name + ")§7 - " + l.summary);
        }
        AutismClientMessaging.send("§7Add §fkeep§7 to layer on top without disabling others (e.g. §f.qql stash keep§7).");
        AutismClientMessaging.send("§7Use §f.qql off§7 to turn every addon module off.");
    }
}
