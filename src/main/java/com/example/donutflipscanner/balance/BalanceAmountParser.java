package com.example.donutflipscanner.balance;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict parser for the formatted money string documented by the player stats API. */
public final class BalanceAmountParser {
    private static final Pattern AMOUNT = Pattern.compile(
            "^\\s*[$£€]?\\s*([0-9]+(?:\\.[0-9]{1,6})?|[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,6})?)\\s*([KMBT]?)\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    private BalanceAmountParser() {
    }

    public static BigDecimal parse(String text) {
        String value = Objects.requireNonNull(text, "text");
        if (value.length() > 128) {
            throw new IllegalArgumentException("balance text is too long");
        }
        Matcher matcher = AMOUNT.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("balance text is not a supported money amount");
        }
        BigDecimal amount = new BigDecimal(matcher.group(1).replace(",", ""));
        BigDecimal multiplier = switch (matcher.group(2).toUpperCase(Locale.ROOT)) {
            case "K" -> BigDecimal.valueOf(1_000L);
            case "M" -> BigDecimal.valueOf(1_000_000L);
            case "B" -> BigDecimal.valueOf(1_000_000_000L);
            case "T" -> BigDecimal.valueOf(1_000_000_000_000L);
            default -> BigDecimal.ONE;
        };
        BigDecimal parsed = amount.multiply(multiplier).stripTrailingZeros();
        if (parsed.scale() < 0) {
            parsed = parsed.setScale(0);
        }
        if (parsed.signum() < 0 || parsed.precision() > 40) {
            throw new IllegalArgumentException("balance amount is outside supported bounds");
        }
        return parsed;
    }
}
