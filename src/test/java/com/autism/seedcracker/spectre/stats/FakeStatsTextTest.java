package com.autism.seedcracker.spectre.stats;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FakeStatsTextTest {
    @Test
    void projectionNeverReplacesTheLiveScoreboardSidebar() throws Exception {
        try (var input = getClass().getResourceAsStream("/com/autism/seedcracker/modules/FakeStatsModule.class")) {
            assertNotNull(input);
            var model = java.lang.classfile.ClassFile.of().parse(input.readAllBytes());
            for (var method : model.methods()) {
                if (method.code().isEmpty()) continue;
                for (var code : method.code().get()) {
                    if (code instanceof java.lang.classfile.instruction.InvokeInstruction invoke) {
                        assertFalse(invoke.owner().name().equalsString("net/minecraft/world/scores/Scoreboard")
                            && invoke.name().equalsString("setDisplayObjective"),
                            "Cosmetic stats must be projected into a private render board, not the live sidebar");
                    }
                }
            }
        }
        try (var input = getClass().getResourceAsStream("/seedcracker.mixins.json")) {
            assertNotNull(input);
            String config = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(config.contains("SpectreFakeStatsHudMixin"));
            assertFalse(config.contains("SpectreFakeStatsScoreboardMixin"));
        }
    }

    @Test
    void matchesWholeWordsAndSmallCapsWithoutMatchingUsernames() {
        assertEquals(0, FakeStatsText.markerAt("ᴍᴏɴᴇʏ: $123", "money"));
        assertEquals(3, FakeStatsText.markerAt("⚔  Kills: 9", "kills"));
        assertEquals(-1, FakeStatsText.markerAt("MoneyMaker_123", "money"));
        assertEquals(-1, FakeStatsText.markerAt("", "money"));
        assertEquals(-1, FakeStatsText.markerAt("anything", ""));
    }

    @Test
    void replacementKeepsExactlyOneCurrencyPrefix() {
        assertEquals("$12M", FakeStatsText.fit("$1,234", "$12M"));
        assertEquals("12M", FakeStatsText.fit("1,234", "$12M"));
    }

    @Test
    void scramblingIsStableDifferentAndPreservesNumberShape() {
        for (long seed : new long[] {0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE}) {
            for (String raw : new String[] {"0", "9", "10", "99", "100", "001", "1,234.50", "999999999999999", "12345678901234567890"}) {
                String masked = FakeStatsText.fakeNumber(seed, 1, 0, raw);
                assertNotEquals(raw, masked, raw);
                assertEquals(masked, FakeStatsText.fakeNumber(seed, 1, 0, raw));
                assertEquals(raw.length(), masked.length());
                for (int i = 0; i < raw.length(); i++) {
                    if (Character.isDigit(raw.charAt(i))) assertTrue(Character.isDigit(masked.charAt(i)));
                    else assertEquals(raw.charAt(i), masked.charAt(i));
                }
                if (raw.charAt(0) != '0' && raw.length() > 1) assertNotEquals('0', masked.charAt(0));
            }
        }
    }

    @Test
    void maskingPreservesWordsUnicodeAndSeparators() {
        String text = "🪙 Money: $1,234.50 / 21d 5h";
        String masked = FakeStatsText.maskText(42, text, 2, new int[1]);
        assertNotEquals(text, masked);
        assertEquals(text.replaceAll("[0-9]", "#"), masked.replaceAll("[0-9]", "#"));
        assertEquals("Team: builders", FakeStatsText.maskText(42, "Team: builders", 2, new int[1]));
    }
}
