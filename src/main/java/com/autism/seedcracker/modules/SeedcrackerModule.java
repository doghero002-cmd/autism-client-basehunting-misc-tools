package com.autism.seedcracker.modules;

import java.util.LinkedHashMap;
import java.util.Map;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.EnumSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import kaptainwutax.seedcrackerX.SeedCracker;
import kaptainwutax.seedcrackerX.config.Config;
import kaptainwutax.seedcrackerX.util.FeatureToggle;

/**
 * Toggleable AUTISM module wrapping the SeedCrackerX cracker.
 *
 * Every SeedCrackerX option is mirrored here as a typed AUTISM setting, so the whole cracker is
 * configured from the AUTISM module GUI without typing any command. Changing a setting writes it
 * straight into SeedCrackerX's {@link Config} and saves it; the settings are also re-synced from the
 * config on enable so they never drift if edited through /seedcracker.
 */
public final class SeedcrackerModule extends Module {

    // ---- General ----
    private final BoolSetting resetOnDisable = add(new BoolSetting(
            "resetOnDisable", "Reset data on disable", false)
        .description("Clear all collected seed data and finders when the module is turned off.")
        .group("General"));

    // ---- Cracking ----
    private final BoolSetting debug = add(new BoolSetting("debug", "Debug logging", false)
        .description("Print extra seed-cracking debug info to chat/logs.")
        .group("Cracking"));
    private final BoolSetting antiXrayBypass = add(new BoolSetting("antiXrayBypass", "Anti-xray bypass", true)
        .description("Try to bypass server anti-xray when reading blocks.")
        .group("Cracking"));

    // ---- Rendering ----
    private final EnumSetting<Config.RenderType> render = add(new EnumSetting<>(
            "render", "Render mode", Config.RenderType.XRAY, Config.RenderType.values())
        .description("How found structures are highlighted in the world.")
        .group("Rendering"));

    // ---- Database ----
    private final BoolSetting databaseSubmits = add(new BoolSetting("databaseSubmits", "Submit to database", false)
        .description("Share cracked seeds with the SeedCrackerX community database.")
        .group("Database"));
    private final BoolSetting anonymusSubmits = add(new BoolSetting("anonymusSubmits", "Anonymous submits", false)
        .description("Submit seeds anonymously.")
        .group("Database")
        .visibleWhen(() -> databaseSubmits.get()));

    // Maps each AUTISM setting id to the SeedCrackerX FeatureToggle it controls.
    private final Map<String, FeatureToggleBinding> featureToggles = new LinkedHashMap<>();

    private record FeatureToggleBinding(BoolSetting setting, java.util.function.Function<Config, FeatureToggle> toggle) {}

    public SeedcrackerModule() {
        super(SeedcrackerAddon.ID + ":seedcracker", "SeedCracker",
            "Finds the world seed from structures, biomes and decorators. Configure it here in the module settings.");

        addStructure("buriedTreasure", "Buried treasure", c -> c.buriedTreasure);
        addStructure("desertTemple", "Desert temple", c -> c.desertTemple);
        addStructure("endCity", "End city", c -> c.endCity);
        addStructure("jungleTemple", "Jungle temple", c -> c.jungleTemple);
        addStructure("monument", "Monument", c -> c.monument);
        addStructure("swampHut", "Swamp hut", c -> c.swampHut);
        addStructure("shipwreck", "Shipwreck", c -> c.shipwreck);
        addStructure("outpost", "Pillager outpost", c -> c.outpost);
        addStructure("igloo", "Igloo", c -> c.igloo);
        addStructure("trialChambers", "Trial chambers", c -> c.trialChambers);

        addDecorator("endPillars", "End pillars", c -> c.endPillars);
        addDecorator("endGateway", "End gateway", c -> c.endGateway);
        addDecorator("dungeon", "Dungeon", c -> c.dungeon);
        addDecorator("emeraldOre", "Emerald ore", c -> c.emeraldOre);
        addDecorator("desertWell", "Desert well", c -> c.desertWell);
        addDecorator("warpedFungus", "Warped fungus", c -> c.warpedFungus);
        addDecorator("biome", "Biome data", c -> c.biome);
    }

    private void addStructure(String id, String label, java.util.function.Function<Config, FeatureToggle> toggle) {
        addFeature(id, label, "Structures", toggle);
    }

    private void addDecorator(String id, String label, java.util.function.Function<Config, FeatureToggle> toggle) {
        addFeature(id, label, "Decorators", toggle);
    }

    private void addFeature(String id, String label, String group, java.util.function.Function<Config, FeatureToggle> toggle) {
        boolean def = toggle.apply(Config.get()).get();
        BoolSetting setting = add(new BoolSetting(id, label, def)
            .description("Use " + label.toLowerCase(java.util.Locale.ROOT) + " data when cracking.")
            .group(group));
        featureToggles.put(id, new FeatureToggleBinding(setting, toggle));
    }

    @Override
    public void onEnable() {
        Config.get().active = true;
        Config.save();
        AutismClientMessaging.sendPrefixed("§aSeedCracker active.");
    }

    @Override
    public void onDisable() {
        Config.get().active = false;
        Config.save();
        if (resetOnDisable.get() && SeedCracker.get() != null) {
            SeedCracker.get().reset();
        }
    }

    @Override
    protected void onOptionValueChanged(String settingId) {
        if (settingId == null) return;
        Config config = Config.get();
        boolean handled = true;
        switch (settingId) {
            case "debug" -> config.debug = debug.get();
            case "antiXrayBypass" -> config.antiXrayBypass = antiXrayBypass.get();
            case "render" -> config.render = render.get();
            case "databaseSubmits" -> config.databaseSubmits = databaseSubmits.get();
            case "anonymusSubmits" -> config.anonymusSubmits = anonymusSubmits.get();
            default -> {
                FeatureToggleBinding binding = featureToggles.get(settingId);
                if (binding != null) {
                    binding.toggle().apply(config).set(binding.setting().get());
                } else {
                    handled = false;
                }
            }
        }
        if (handled) {
            Config.save();
        }
    }
}
