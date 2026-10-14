package com.autism.seedcracker.util.pure;

import java.util.Locale;
import java.util.regex.Pattern;

public final class PaymentPolicy {
    private PaymentPolicy() {}

    public static boolean recipient(String target, String self) {
        return target != null && target.matches("\\.?[A-Za-z0-9_]{1,16}") && !target.equalsIgnoreCase(self);
    }

    public static boolean amount(String value) {
        if (value == null) return false;
        if (value.equalsIgnoreCase("all")) return true;
        if (!value.matches("(?i)[0-9]+(?:\\.[0-9]+)?[kmb]?")) return false;
        String digits = value.replaceAll("(?i)[kmb]$", "");
        try {
            double number = Double.parseDouble(digits);
            double scale = value.toLowerCase(Locale.ROOT).endsWith("b") ? 1_000_000_000.0
                : value.toLowerCase(Locale.ROOT).endsWith("m") ? 1_000_000.0
                : value.toLowerCase(Locale.ROOT).endsWith("k") ? 1_000.0 : 1.0;
            return number > 0 && Double.isFinite(number * scale);
        } catch (NumberFormatException invalid) { return false; }
    }

    public static boolean acknowledged(String message, String target) {
        if (message == null || !recipient(target, "")) return false;
        String text = message.replaceAll("(?i)\\u00a7[0-9a-fk-or]", "").trim();
        return Pattern.compile("(?i)^you (?:have )?(?:successfully )?(?:paid|sent)\\s+.+?\\s+to\\s+"
            + Pattern.quote(target) + "(?:[.!]|\\s.*)?$").matcher(text).matches();
    }
}