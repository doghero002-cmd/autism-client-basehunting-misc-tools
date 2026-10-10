package com.autism.seedcracker.util.pure;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Price-text parsing (pure, no Minecraft types - unit tested).
 *
 * Shared by the auction/pay modules so the server-format assumptions live in ONE place:
 * anchored price lines ($ prefix or price/cost/buy keyword, never "lowest number anywhere")
 * and k/m/b shorthand amounts.
 */
public final class PriceMath {
    private PriceMath() {}

    private static final Pattern DOLLAR = Pattern.compile("\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kKmMbB])?");
    private static final Pattern KEYWORD = Pattern.compile("(?i)(?:price|cost|buy(?: it now)?)\\D{0,8}([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kKmMbB])?");

    /** Price from one tooltip/chat line, or -1 if the line doesn't look like a price line. */
    public static double parseLine(String line) {
        if (line == null || line.isEmpty()) return -1;
        // "Each: $100" / "$100 per" lines are PER-UNIT; every consumer treats our result as the
        // listing total and divides by count, so taking them double-divides (A7). Skip them -
        // the total line ("Price: $6,400") is always present alongside.
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("each") || lower.contains(" per ") || lower.endsWith(" per")) return -1;
        Matcher m = DOLLAR.matcher(line);
        if (!m.find()) {
            m = KEYWORD.matcher(line);
            if (!m.find()) return -1;
        }
        try {
            double v = Double.parseDouble(m.group(1).replace(",", "")) * suffixMultiplier(m.group(2));
            return Double.isFinite(v) ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Amount with optional k/m/b shorthand and commas ("166.3k", "1.8m", "30"); -1 if invalid. */
    public static long parseAmount(String raw) {
        if (raw == null) return -1;
        String s = raw.replace(",", "").trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty()) return -1;
        long mult = 1;
        char last = s.charAt(s.length() - 1);
        if (last == 't') { mult = 1_000_000_000_000L; s = s.substring(0, s.length() - 1); }
        else if (last == 'b') { mult = 1_000_000_000L; s = s.substring(0, s.length() - 1); }
        else if (last == 'm') { mult = 1_000_000L; s = s.substring(0, s.length() - 1); }
        else if (last == 'k') { mult = 1_000L; s = s.substring(0, s.length() - 1); }
        try {
            var amount = new java.math.BigDecimal(s).multiply(java.math.BigDecimal.valueOf(mult));
            if (amount.signum() < 0) return -1;
            return amount.setScale(0, java.math.RoundingMode.DOWN).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return -1;
        }
    }

    private static double suffixMultiplier(String suffix) {
        if (suffix == null || suffix.isEmpty()) return 1.0;
        return switch (Character.toLowerCase(suffix.charAt(0))) {
            case 'k' -> 1_000.0;
            case 'm' -> 1_000_000.0;
            case 'b' -> 1_000_000_000.0;
            default -> 1.0;
        };
    }
}
