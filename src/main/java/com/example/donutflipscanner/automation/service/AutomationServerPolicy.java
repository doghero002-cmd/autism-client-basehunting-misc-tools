package com.example.donutflipscanner.automation.service;

import java.util.Locale;
import java.util.Objects;

/** Explicitly prevents gameplay automation on public servers whose published rules prohibit it. */
public final class AutomationServerPolicy {
    private static final String DONUT_SMP_DOMAIN = "donutsmp.net";

    private AutomationServerPolicy() {
    }

    public static boolean permitsGameplayAutomation(String serverAddress) {
        String host = host(serverAddress);
        return !host.equals(DONUT_SMP_DOMAIN) && !host.endsWith("." + DONUT_SMP_DOMAIN);
    }

    private static String host(String serverAddress) {
        String value = Objects.requireNonNullElse(serverAddress, "").strip()
                .toLowerCase(Locale.ROOT);
        if (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        int portSeparator = value.lastIndexOf(':');
        if (portSeparator > 0 && value.indexOf(':') == portSeparator) {
            value = value.substring(0, portSeparator);
        }
        return value;
    }
}
