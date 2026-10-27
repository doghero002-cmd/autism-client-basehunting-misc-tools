package com.autism.seedcracker.chatgames;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;

public final class ChatGameVocabulary {
    private static final int MAX_EXTRA_WORD_CHARS = 64;
    private static final List<String> DEFAULT_EXTRA_WORDS = loadDefaultExtraWords();

    private ChatGameVocabulary() {}

    /** Curated built-ins shown in the client setting; the 370k English fallback stays automatic. */
    public static List<String> defaultExtraWords() {
        return DEFAULT_EXTRA_WORDS;
    }

    /** Adds upgraded built-ins to a saved client list without discarding user-added entries. */
    public static List<String> withDefaults(List<String> configured) {
        List<String> merged = new ArrayList<>(DEFAULT_EXTRA_WORDS);
        Set<String> seen = new HashSet<>();
        DEFAULT_EXTRA_WORDS.forEach(word -> seen.add(word.toLowerCase(Locale.ROOT)));
        if (configured != null) {
            for (String raw : configured) {
                if (raw == null || raw.isBlank()) continue;
                String word = raw.trim();
                if (seen.add(word.toLowerCase(Locale.ROOT))) merged.add(word);
            }
        }
        return List.copyOf(merged);
    }

    private static List<String> loadDefaultExtraWords() {
        Set<String> words = new LinkedHashSet<>(List.of(
            "overworld", "nether", "the nether", "end", "the end",
            "nether portal", "end portal", "stronghold", "ancient city", "trial chambers",
            "end city", "nether fortress", "bastion remnant", "ocean monument", "woodland mansion",
            "pillager outpost", "buried treasure", "mineshaft", "desert pyramid", "jungle temple",
            "igloo", "shipwreck", "ruined portal", "village", "experience"));
        words.addAll(List.of(
            "enchantment table", "crafting bench", "workbench", "wooden planks", "wooden door",
            "wooden pressure plate", "wooden button", "wooden slab", "wooden stairs", "wooden fence",
            "wooden trapdoor", "mob spawner", "monster spawner", "monster egg", "spawn egg",
            "notch apple", "enchanted apple", "god apple", "gapple", "totem", "shulker",
            "quartz ore", "nether brick block", "red nether brick", "stone brick", "mossy stone brick",
            "cracked stone brick", "chiseled stone brick", "smooth brick", "stone monster egg",
            "silverfish block", "grass block", "long grass", "water lily", "ink sack", "speckled melon",
            "raw pork", "raw porkchop", "grilled pork", "raw beef", "raw chicken", "raw mutton",
            "raw rabbit", "raw fish", "raw salmon", "cooked fish", "clownfish", "sugar canes",
            "gold record", "green record", "zombie pigman", "pig zombie", "mushroom cow", "snowman",
            "iron golem", "ender crystal", "ender dragon", "snowy tundra", "ice plains", "ice mountains",
            "ice plains spikes", "extreme hills", "extreme hills plus", "roofed forest", "roofed forest hills",
            "mega taiga", "mega spruce taiga", "giant tree taiga", "cold taiga", "stone beach", "cold beach",
            "mushroom island", "mushroom island shore", "mesa", "mesa plateau", "mesa plateau forest",
            "swampland", "jungle edge", "modified jungle edge", "tall birch forest"));
        ChatGameAnswerKey.defaults().values().stream().sorted().forEach(words::add);
        for (String resource : List.of("/chatgames/public-plugin-words.txt", "/chatgames/public-country-words.txt")) {
            try (var input = ChatGameVocabulary.class.getResourceAsStream(resource)) {
                if (input == null) throw new java.io.IOException("Bundled phrase list missing: " + resource);
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))) {
                    reader.lines().limit(2000).filter(word -> !word.startsWith("#")).forEach(words::add);
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Could not load built-in chat-game phrases: " + resource, e);
            }
        }
        List<String> ordered = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : words) {
            String word = raw.trim();
            String key = word.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (isSolverWord(word) && seen.add(key)) ordered.add(word);
        }
        return List.copyOf(ordered);
    }

    private static boolean isSolverWord(String word) {
        String normalized = word.toLowerCase(Locale.ROOT);
        return normalized.length() <= MAX_EXTRA_WORD_CHARS && normalized.replaceAll("[^a-z]", "").length() >= 3
            && !word.contains("|") && word.codePoints().noneMatch(Character::isISOControl);
    }

    public static Set<String> builtInWords() {
        Set<String> words = new LinkedHashSet<>();
        for (var item : BuiltInRegistries.ITEM) words.add(Component.translatable(item.getDescriptionId()).getString());
        for (var block : BuiltInRegistries.BLOCK) words.add(block.getName().getString());
        for (var type : BuiltInRegistries.ENTITY_TYPE) words.add(type.getDescription().getString());
        for (var effect : BuiltInRegistries.MOB_EFFECT) words.add(effect.getDisplayName().getString());
        for (var registry : List.of(BuiltInRegistries.ITEM, BuiltInRegistries.BLOCK,
            BuiltInRegistries.ENTITY_TYPE, BuiltInRegistries.MOB_EFFECT, BuiltInRegistries.POTION,
            BuiltInRegistries.VILLAGER_PROFESSION)) {
            for (var key : registry.keySet()) words.add(key.getPath().replace('_', ' '));
        }
        for (var potion : BuiltInRegistries.POTION) {
            words.add(potion.name().replace('_', ' '));
            for (String item : List.of("potion", "splash_potion", "lingering_potion")) {
                words.add(Component.translatable("item.minecraft." + item + ".effect." + potion.name()).getString());
            }
        }
        for (Class<?> keys : List.of(BuiltinStructures.class, net.minecraft.world.level.biome.Biomes.class,
            net.minecraft.world.item.enchantment.Enchantments.class, net.minecraft.world.level.dimension.BuiltinDimensionTypes.class)) {
            for (var field : keys.getFields()) {
                try {
                    if (field.get(null) instanceof ResourceKey<?> key) words.add(key.identifier().getPath().replace('_', ' '));
                } catch (IllegalAccessException inaccessible) {
                    throw new IllegalStateException("Could not read vanilla registry names", inaccessible);
                }
            }
        }
        return words;
    }

    public static void addWorldWords(ChatGameSolver target, HolderLookup.Provider registries) {
        for (var registryKey : List.of(Registries.BIOME, Registries.DIMENSION_TYPE,
            Registries.ENCHANTMENT, Registries.STRUCTURE, Registries.TRIM_PATTERN,
            Registries.TRIM_MATERIAL, Registries.BANNER_PATTERN, Registries.PAINTING_VARIANT,
            Registries.INSTRUMENT, Registries.WOLF_VARIANT, Registries.CAT_VARIANT,
            Registries.FROG_VARIANT, Registries.PIG_VARIANT, Registries.COW_VARIANT,
            Registries.CHICKEN_VARIANT, Registries.ZOMBIE_NAUTILUS_VARIANT)) {
            registries.lookup(registryKey).ifPresent(registry -> registry.listElementIds().forEach(key -> {
                String name = key.identifier().getPath().replace('_', ' ');
                target.addWord(name);
                if (registryKey.equals(Registries.TRIM_PATTERN)) target.addWord(name + " armor trim");
                if (registryKey.equals(Registries.WOLF_VARIANT)) target.addWord(name + " wolf");
                if (registryKey.equals(Registries.CAT_VARIANT)) target.addWord(name + " cat");
                if (registryKey.equals(Registries.FROG_VARIANT)) target.addWord(name + " frog");
                if (registryKey.equals(Registries.PIG_VARIANT)) target.addWord(name + " pig");
                if (registryKey.equals(Registries.COW_VARIANT)) target.addWord(name + " cow");
                if (registryKey.equals(Registries.CHICKEN_VARIANT)) target.addWord(name + " chicken");
                if (registryKey.equals(Registries.ZOMBIE_NAUTILUS_VARIANT)) target.addWord(name + " zombie nautilus");
            }));
        }
        registries.lookup(Registries.ENCHANTMENT).ifPresent(registry -> registry.listElements()
            .forEach(holder -> target.addWord(holder.value().description().getString())));
        registries.lookup(Registries.TRIM_PATTERN).ifPresent(registry -> registry.listElements()
            .forEach(holder -> target.addWord(holder.value().description().getString())));
        registries.lookup(Registries.TRIM_MATERIAL).ifPresent(registry -> registry.listElements()
            .forEach(holder -> target.addWord(holder.value().description().getString())));
        registries.lookup(Registries.INSTRUMENT).ifPresent(registry -> registry.listElements()
            .forEach(holder -> target.addWord(holder.value().description().getString())));
        registries.lookup(Registries.PAINTING_VARIANT).ifPresent(registry -> registry.listElements()
            .forEach(holder -> holder.value().title().ifPresent(title -> target.addWord(title.getString()))));
    }
}
