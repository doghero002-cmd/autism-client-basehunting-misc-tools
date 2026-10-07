package com.autism.seedcracker.util.pure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PriceMathTest {

    // ---- parseLine ----

    @Test
    void dollarPrefixLines() {
        assertEquals(1500.0, PriceMath.parseLine("Price: $1,500"));
        assertEquals(1500.0, PriceMath.parseLine("$1500"));
        assertEquals(2_500_000.0, PriceMath.parseLine("$2.5m"));
        assertEquals(166_300.0, PriceMath.parseLine("$ 166.3k"));
        assertEquals(1_000_000_000.0, PriceMath.parseLine("$1b"));
    }

    @Test
    void keywordLines() {
        assertEquals(300.0, PriceMath.parseLine("Cost: 300"));
        assertEquals(42_000.0, PriceMath.parseLine("Buy it now: 42k"));
        assertEquals(5.0, PriceMath.parseLine("price - 5"));
    }

    @Test
    void nonPriceLinesRejected() {
        // Enchant levels and stack counts must NOT be read as prices.
        assertEquals(-1.0, PriceMath.parseLine("Sharpness 5"));
        assertEquals(-1.0, PriceMath.parseLine("x64 Cobblestone"));
        assertEquals(-1.0, PriceMath.parseLine("Unbreaking III"));
        assertEquals(-1.0, PriceMath.parseLine(""));
        assertEquals(-1.0, PriceMath.parseLine(null));
    }

    // ---- parseAmount ----

    @Test
    void shorthandAmounts() {
        assertEquals(30L, PriceMath.parseAmount("30"));
        assertEquals(166_300L, PriceMath.parseAmount("166.3k"));
        assertEquals(1_800_000L, PriceMath.parseAmount("1.8m"));
        assertEquals(2_000_000_000L, PriceMath.parseAmount("2b"));
        assertEquals(1_000_000_000_000L, PriceMath.parseAmount("1t"));
        assertEquals(1_500L, PriceMath.parseAmount("1,500"));
        assertEquals(1_000L, PriceMath.parseAmount("1K")); // case-insensitive
    }

    @Test
    void invalidAmounts() {
        assertEquals(-1L, PriceMath.parseAmount("abc"));
        assertEquals(-1L, PriceMath.parseAmount(""));
        assertEquals(-1L, PriceMath.parseAmount(null));
        assertEquals(-1L, PriceMath.parseAmount("k"));
    }
}
