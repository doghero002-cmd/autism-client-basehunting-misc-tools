package com.autism.seedcracker.oresim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import kaptainwutax.seedcrackerX.mixin.accessor.CountPlacementAccessor;
import kaptainwutax.seedcrackerX.mixin.accessor.HeightRangePlacementAccessor;
import kaptainwutax.seedcrackerX.mixin.accessor.RarityFilterAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.data.worldgen.placement.OrePlacements;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.feature.ScatteredOreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * One simulated ore type: the world-gen placement parameters (count, height, rarity, vein size,
 * discard-on-air chance, scattered) for a configured ore feature, plus which module toggle
 * enables it and the render colour.
 *
 * Port of the meteor-rejects Ore (anticope.rejects.utils.Ore) to Mojang 26.2 mappings, using the
 * project's placement accessor mixins and plain boolean toggles instead of Meteor settings.
 */
public final class Ore {

    /** Ore ids used as toggle keys, in display order. */
    public static final String[] ORE_IDS = {
        "coal", "iron", "gold", "redstone", "diamond", "lapis", "copper", "emerald", "quartz", "debris"
    };

    /** Dimension the ore set is for. */
    public enum Dim { OVERWORLD, NETHER, END }

    public int step;
    public int index;
    public String id;
    public IntProvider count = ConstantInt.of(1);
    public HeightProvider heightProvider;
    public WorldGenerationContext heightContext;
    public float rarity = 1;
    public float discardOnAirChance;
    public int size;
    public int color;
    public boolean scattered;

    private Ore(PlacedFeature feature, int step, int index, String id, int color) {
        this.step = step;
        this.index = index;
        this.id = id;
        this.color = color;
        int bottom = Minecraft.getInstance().level.getMinY();
        int height = Minecraft.getInstance().level.dimensionType().logicalHeight();
        this.heightContext = new WorldGenerationContext(null, LevelHeightAccessor.create(bottom, height));

        for (PlacementModifier modifier : feature.placement()) {
            if (modifier instanceof CountPlacement) {
                this.count = ((CountPlacementAccessor) modifier).getCount();
            } else if (modifier instanceof HeightRangePlacement) {
                this.heightProvider = ((HeightRangePlacementAccessor) modifier).getHeight();
            } else if (modifier instanceof RarityFilter) {
                this.rarity = ((RarityFilterAccessor) modifier).getChance();
            }
        }

        FeatureConfiguration featureConfig = feature.feature().value().config();
        if (featureConfig instanceof OreConfiguration oreFeatureConfig) {
            this.discardOnAirChance = oreFeatureConfig.discardChanceOnAirExposure;
            this.size = oreFeatureConfig.size;
        } else {
            throw new IllegalStateException("config for " + feature + " is not OreConfiguration");
        }
        if (feature.feature().value().feature() instanceof ScatteredOreFeature) {
            this.scattered = true;
        }
    }

    /** Build the biome -> ore list map for the given dimension, from the vanilla world-gen registry. */
    public static Map<ResourceKey<Biome>, List<Ore>> getRegistry(Dim dimension) {
        HolderLookup.Provider registry = VanillaRegistries.createLookup();
        HolderLookup.RegistryLookup<PlacedFeature> features = registry.lookupOrThrow(Registries.PLACED_FEATURE);
        var reg = registry.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value()
            .createWorldDimensions().dimensions();

        var dim = switch (dimension) {
            case OVERWORLD -> reg.get(LevelStem.OVERWORLD);
            case NETHER -> reg.get(LevelStem.NETHER);
            case END -> reg.get(LevelStem.END);
        };

        var biomes = dim.generator().getBiomeSource().possibleBiomes();
        var biomes1 = biomes.stream().toList();

        List<FeatureSorter.StepFeatureData> indexer = FeatureSorter.buildFeaturesPerStep(
            biomes1, biomeEntry -> biomeEntry.value().getGenerationSettings().features(), true);

        Map<PlacedFeature, Ore> featureToOre = new HashMap<>();
        registerOre(featureToOre, indexer, features, OrePlacements.ORE_COAL_LOWER, 6, "coal", 0xFF2F2C36);
        registerOre(featureToOre, indexer, features, OrePlacements.ORE_COAL_UPPER, 6, "coal", 0xFF2F2C36);
        registerOre(featureToOre, indexer, features, OrePlacements.ORE_IRON_MIDDLE, 6, "iron", 0xFFECAD77);
        registerOre(featureToOre, indexer, features, OrePlacements.ORE_IRON_SMALL, 6, "iron", 0xFFECAD77);
        registerOre(featureToOre, indexer, features, OrePlacements.ORE_IRON_UPPER, 6, "iron", 0xFFECAD77);
        registerOre(featureToOre, indexer, features, OrePlacements.ORE_GOLD, 6, "gold", 0xFFF7E51E);
        registerOre(featureToOre, indexer, features, OrePlacements.ORE_GOLD_LOWER, 6, "gold", 0xFFF7E51E);
        registerOre(featureToOre, indexer, features, OrePlacements.ORE_GOLD_EXTRA, 6, "gold", 0xFFF7E51E);
        registerOre(featureToOre, indexer, features, OrePlacements.ORE_GOLD_NETHER, 7, "gold", 0xFFF7E51E);
        registerOre(featureToOre, indexer, features, OrePlacements.ORE_GOLD_DELTAS, 7, "gold", 0xFFF7E51E);
        registerOre(featureToOre, indexer, features, OrePlacements.ORE_REDSTONE, 6, "redstone", 0xFFF50717);
        registerOre(featureToOre, indexer, features, OrePlacements.ORE_REDSTONE_LOWER, 6, "redstone", 0xFFF50717);
        registerOre(featureToOre, indexer, features, OrePlacements.ORE_DIAMOND, 6, "diamond", 0xFF21F4FF);
        registerOre(featureToOre, indexer, features, OrePlacements.ORE_DIAMOND_BURIED, 6, "diamond", 0xFF21F4FF);
        registerOre(featureToOre, indexer, features, OrePlacements.ORE_DIAMOND_LARGE, 6, "diamond", 0xFF21F4FF);
        registerOre(featureToOre, indexer, features, OrePlacements.ORE_DIAMOND_MEDIUM, 6, "diamond", 0xFF21F4FF);
        registerOre(featureToOre, indexer, features, OrePlacements.ORE_LAPIS, 6, "lapis", 0xFF081ABD);
        registerOre(featureToOre, indexer, features, OrePlacements.ORE_LAPIS_BURIED, 6, "lapis", 0xFF081ABD);
        registerOre(featureToOre, indexer, features, OrePlacements.ORE_COPPER, 6, "copper", 0xFFEF9700);
        registerOre(featureToOre, indexer, features, OrePlacements.ORE_COPPER_LARGE, 6, "copper", 0xFFEF9700);
        registerOre(featureToOre, indexer, features, OrePlacements.ORE_EMERALD, 6, "emerald", 0xFF1BD12D);
        registerOre(featureToOre, indexer, features, OrePlacements.ORE_QUARTZ_NETHER, 7, "quartz", 0xFFCDCDCD);
        registerOre(featureToOre, indexer, features, OrePlacements.ORE_QUARTZ_DELTAS, 7, "quartz", 0xFFCDCDCD);
        registerOre(featureToOre, indexer, features, OrePlacements.ORE_ANCIENT_DEBRIS_SMALL, 7, "debris", 0xFFD11BF5);
        registerOre(featureToOre, indexer, features, OrePlacements.ORE_ANCIENT_DEBRIS_LARGE, 7, "debris", 0xFFD11BF5);

        Map<ResourceKey<Biome>, List<Ore>> biomeOreMap = new HashMap<>();
        biomes1.forEach(biome -> {
            biomeOreMap.put(biome.unwrapKey().get(), new ArrayList<>());
            biome.value().getGenerationSettings().features().stream()
                .flatMap(HolderSet::stream)
                .map(Holder::value)
                .filter(featureToOre::containsKey)
                .forEach(feature -> biomeOreMap.get(biome.unwrapKey().get()).add(featureToOre.get(feature)));
        });
        return biomeOreMap;
    }

    private static void registerOre(
            Map<PlacedFeature, Ore> map,
            List<FeatureSorter.StepFeatureData> indexer,
            HolderLookup.RegistryLookup<PlacedFeature> oreRegistry,
            ResourceKey<PlacedFeature> oreKey,
            int genStep,
            String id,
            int color) {
        var orePlacement = oreRegistry.getOrThrow(oreKey).value();
        int index = indexer.get(genStep).indexMapping().applyAsInt(orePlacement);
        map.put(orePlacement, new Ore(orePlacement, genStep, index, id, color));
    }
}
