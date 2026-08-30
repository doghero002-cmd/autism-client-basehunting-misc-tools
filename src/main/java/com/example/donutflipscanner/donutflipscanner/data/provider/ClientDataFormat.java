package com.example.donutflipscanner.data.provider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

final class ClientDataFormat {
    private ClientDataFormat() {
    }

    static long saturatedLong(BigDecimal value) {
        try {
            return value.setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException exception) {
            return value.signum() < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    static String itemName(String itemId) {
        int separator = itemId.indexOf(':');
        String path = separator >= 0 ? itemId.substring(separator + 1) : itemId;
        StringBuilder result = new StringBuilder(path.length());
        boolean uppercase = true;
        for (char character : path.toCharArray()) {
            if (character == '_' || character == '-') {
                result.append(' ');
                uppercase = true;
            } else {
                result.append(uppercase ? Character.toUpperCase(character) : character);
                uppercase = false;
            }
        }
        return result.isEmpty() ? "Unknown Item" : result.toString();
    }

    static String age(Instant instant, Clock clock) {
        long seconds = Math.max(0L, Duration.between(instant, clock.instant()).toSeconds());
        if (seconds < 60) {
            return seconds + "s ago";
        }
        if (seconds < 3_600) {
            return (seconds / 60) + "m ago";
        }
        if (seconds < 86_400) {
            return (seconds / 3_600) + "h ago";
        }
        return (seconds / 86_400) + "d ago";
    }

    static String titleCaseState(String value) {
        return itemName(value.toLowerCase(Locale.ROOT).replace(':', '_'));
    }
}
