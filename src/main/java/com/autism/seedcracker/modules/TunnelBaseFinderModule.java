package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.modules.ModuleRegistry;
import autismclient.util.AutismClientMessaging;

/**
 * TunnelBase Finder.
 *
 * Single, reliable entry point for autonomous DonutSMP base-hunting tunnels: toggling it starts /
 * stops the Water tunnel engine ({@link TunnelBaseWaterModule}) - the one mode that doesn't flag
 * the anti-cheat. The old straight-line XENON and 2x1 WALK engines both rubber-banded on the live
 * server, so they were removed.
 *
 * Every Water engine setting is mirrored here 1:1 (same ids) and delegated through the
 * external-setting hooks, so both settings panels read/write the SAME stored values - change
 * mining style here and Tunnel Base (Water) sees it instantly, and vice versa.
 */
public final class TunnelBaseFinderModule extends Module {

    public TunnelBaseFinderModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":tunnel-base-finder", "TunnelBase Finder", category,
            "Digs a tunnel and alerts on bases using the Water engine (the reliable, non-flagging mode). Settings are shared with Tunnel Base (Water).");

        add(new EnumSetting<>("mining-style", "Mining style",
                TunnelBaseWaterModule.MiningMode.AMETHYST, TunnelBaseWaterModule.MiningMode.values())
            .description("CRAWL = 1x1 swim-crawl. STANDING = 2x1 walk. AMETHYST = wider hazard scan.")
            .group("General"));
        add(new BoolSetting("spawner-critical", "Spawner critical", false)
            .description("Disconnect the moment any spawner is detected (else only on chest/shulker/piston counts).")
            .group("General"));
        add(new BoolSetting("humanize", "Humanize", true)
            .description("Randomise shop/mend action delays.").group("General"));
        add(new IntSetting("delay-randomness", "Delay randomness", 3, 0, 10, 1)
            .group("General").visibleWhen(() -> bool("humanize")));
        add(new BoolSetting("background-run", "Run while tabbed out", true)
            .description("Keep tunneling while the window is unfocused: blocks the pause menu on alt-tab and auto-closes it if it slipped in.")
            .group("General"));

        add(new BoolSetting("require-spawner", "Require spawner", true)
            .description("Only flag a BASE when a spawner is ALSO loaded. Chest/shulker/piston counts alone (kelp farms, shops, lush caves) won't trigger.")
            .group("Base Detection"));
        add(new IntSetting("chest-threshold", "Chest/barrel count", 35, 1, 200, 1)
            .description("Chests/barrels below Y0 in loaded chunks needed to count as a BASE.").group("Base Detection"));
        add(new IntSetting("shulker-threshold", "Shulker count", 35, 1, 200, 1)
            .description("Shulker boxes below Y0 needed to count as a BASE.").group("Base Detection"));
        add(new IntSetting("piston-threshold", "Moving piston count", 10, 1, 100, 1)
            .description("Moving pistons below Y0 needed to count as a BASE.").group("Base Detection"));

        add(new IntSetting("obsidian-slot", "Obsidian slot", 2, 1, 9, 1).group("Slots"));
        add(new IntSetting("pearl-slot", "Pearl slot", 3, 1, 9, 1).group("Slots"));
        add(new IntSetting("bottle-slot", "Bottle slot", 4, 1, 9, 1).group("Slots"));
        add(new IntSetting("carrot-slot", "GoldenCarrot slot", 5, 1, 9, 1).group("Slots"));

        add(new BoolSetting("kick-on-no-totem", "Kick on no totem", true)
            .description("Disconnect when you have no totem (startup + when it pops). OFF = just warn and keep tunneling (risky).")
            .group("Totem"));
        add(new BoolSetting("auto-buy-totem", "Auto buy totem", false)
            .description("When you have no totem, open /shop and buy one automatically instead of stopping.")
            .group("Totem"));
        add(new StringSetting("totem-price", "Totem price", "50000")
            .description("The /shop totem price (for reference - the buy flow clicks the totem slot; the price is informational / for your tracking).")
            .group("Totem").visibleWhen(() -> bool("auto-buy-totem")));

        add(new IntSetting("turn-speed", "Turn speed", 12, 2, 40, 1)
            .description("Degrees per tick the view turns toward a new direction (higher = snappier, lower = slower/smoother).")
            .group("Movement"));
        add(new BoolSetting("turn-while-walking", "Turn while walking", true)
            .description("Keep walking while turning to a new direction (looks less bot-like than stop-turn-go).")
            .group("Movement"));
        add(new BoolSetting("manual-steer", "Steer with mouse", true)
            .description("Turn the tunnel by looking: turn your camera past ~35 degrees and the tunnel adopts that direction instead of forcing itself back straight.")
            .group("Movement"));

        add(new BoolSetting("kick-on-find", "Kick on base find", true)
            .description("Disconnect when a base is found. OFF = play a sound + keep going (you loot it yourself).")
            .group("Find"));
        add(new StringSetting("find-sound", "Found sound", "entity.player.levelup")
            .description("Sound event to play when a base is found (only when kick-on-find is off).")
            .group("Find").visibleWhen(() -> !bool("kick-on-find")));

        add(new BoolSetting("buying", "Enable buying", true)
            .description("Allow the module to /shop-restock XP / pearls / obsidian / carrots / totems. OFF = never opens the shop (you supply everything).")
            .group("Buying"));
    }

    private Module water() {
        return ModuleRegistry.get(SeedcrackerAddon.ID + ":tunnel-base-water");
    }

    // The mirrored settings store nothing locally: reads and writes route to the Water module's
    // state so both settings panels are always the same values.
    @Override
    protected String externalSettingValue(String settingId) {
        Module water = water();
        if (water == null || water == this) return null;
        return water.value(settingId);
    }

    @Override
    protected boolean setExternalSettingValue(String settingId, String value) {
        Module water = water();
        if (water == null || water == this) return false;
        water.setValue(settingId, value);
        return true;
    }

    @Override
    public void onEnable() {
        Module water = water();
        if (water == null) {
            AutismClientMessaging.sendPrefixed("§cTunnelBase Finder: Water engine module is missing.");
            setEnabledSilently(false);
            return;
        }
        if (water.isEnabled()) {
            water.setEnabled(false);
            replaceNextToggleMessage("TunnelBase Finder: stopped.");
        } else {
            water.setEnabled(true);
            replaceNextToggleMessage("TunnelBase Finder: Water engine running.");
        }
        setEnabledSilently(false);
    }

    @Override
    public String info() {
        Module water = water();
        return water != null && water.isEnabled() ? "water engine on" : "water engine off";
    }
}
