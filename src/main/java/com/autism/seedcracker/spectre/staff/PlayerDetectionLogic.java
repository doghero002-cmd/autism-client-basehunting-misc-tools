// SPDX-License-Identifier: GPL-3.0-only
// Adapted from https://github.com/cody-raves/Spectre, commit d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da.
// Upstream: src/main/java/net/spectre/client/module/impl/misc/PlayerDetectionModule.java
package com.autism.seedcracker.spectre.staff;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PlayerDetectionLogic {
    private static final Pattern ACCOUNT = Pattern.compile("[.*]?[A-Za-z0-9_]{1,16}");
    private static final Pattern AMOUNT = Pattern.compile("([0-9]{1,16}(?:\\.[0-9]{1,4})?)[kmbt]?", Pattern.CASE_INSENSITIVE);

    private PlayerDetectionLogic() {}

    public static Set<String> whitelist(String raw) {
        Set<String> names = new HashSet<>();
        names.add("freecamera");
        if (raw != null) {
            for (String name : raw.split(",")) {
                if (!name.isBlank()) names.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(names);
    }

    public static boolean ignored(String name, String self, Set<String> whitelist) {
        return name == null || name.isBlank() || name.equalsIgnoreCase(self)
            || whitelist.contains(name.toLowerCase(Locale.ROOT));
    }

    public static <T> Set<T> entered(Set<T> previous, Set<T> current) {
        Set<T> entered = new LinkedHashSet<>(current);
        entered.removeAll(previous);
        return entered;
    }

    public static boolean accountName(String name) {
        return name != null && ACCOUNT.matcher(name).matches();
    }

    public static String paymentCommand(String target, String amount) {
        String recipient = target == null ? "" : target.trim();
        String quantity = amount == null ? "" : amount.trim().toLowerCase(Locale.ROOT);
        if (!accountName(recipient)) return null;
        Matcher match = AMOUNT.matcher(quantity);
        if (!match.matches() || new BigDecimal(match.group(1)).signum() <= 0) return null;
        return "pay " + recipient + " " + quantity;
    }
}
