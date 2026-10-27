package com.autism.seedcracker.chatgames;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatGameVocabularyTest {
    @Test
    void upgradedDefaultsKeepPreviouslySavedExtras() {
        var defaults = ChatGameVocabulary.defaultExtraWords();
        var oldSavedList = List.of("the nether", "my server's custom phrase");
        var migrated = ChatGameVocabulary.withDefaults(oldSavedList);
        assertTrue(migrated.size() > defaults.size());
        assertTrue(migrated.contains("trial chambers"));
        assertTrue(migrated.contains("my server's custom phrase"));
        assertEquals(migrated.size(), ChatGameVocabulary.withDefaults(migrated).size());
    }

    @Test
    void curatedDefaultsIncludePhrasesAndAcceptAdditionalWords() {
        var defaults = ChatGameVocabulary.defaultExtraWords();
        assertEquals(610, defaults.size(), "all curated answer, plugin, country, and phrase defaults are editable");
        assertTrue(defaults.contains("the nether"));
        assertTrue(defaults.contains("trial chambers"));
        assertTrue(defaults.contains("woodland mansion"));
        assertTrue(defaults.stream().anyMatch(word -> word.equalsIgnoreCase("World Wide Web")));
        assertTrue(defaults.stream().anyMatch(word -> word.equalsIgnoreCase("Wellington")));
        assertTrue(defaults.stream().anyMatch(word -> word.equalsIgnoreCase("Chicago")));
        assertTrue(defaults.stream().anyMatch(word -> word.equalsIgnoreCase("Redmond Washington")));
        assertTrue(defaults.stream().anyMatch(word -> word.equalsIgnoreCase("creeper")));
        assertTrue(defaults.stream().anyMatch(word -> word.equalsIgnoreCase("SMARTPHONE")));
        assertTrue(defaults.stream().anyMatch(word -> word.equalsIgnoreCase("Afghanistan")));

        var solver = new ChatGameSolver(defaults);
        assertEquals(defaults.size(), solver.dictionarySize());
        solver.addWord("my custom server phrase");
        assertEquals(defaults.size() + 1, solver.dictionarySize());
    }
}
