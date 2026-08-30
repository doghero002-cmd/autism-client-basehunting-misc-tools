package com.example.donutflipscanner.security;

import java.util.Objects;
import java.util.regex.Pattern;

/** Redacts credentials and personal absolute paths before text reaches diagnostics or logs. */
public final class SensitiveDataSanitizer {
    private static final String REDACTED = "[REDACTED]";
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[^\\s,;]+" );
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(authorization|api[-_ ]?key|session[-_ ]?token|access[-_ ]?token|token)"
                    + "(\\s*[:=]\\s*)([^\\s,;]+)"
    );
    private static final Pattern WINDOWS_PATH = Pattern.compile(
            "(?i)(?:[a-z]:\\\\(?:Users|Documents and Settings)\\\\)[^\\r\\n,;]+"
    );
    private static final Pattern UNIX_HOME_PATH = Pattern.compile(
            "(?:/Users/|/home/)[^\\r\\n,;]+"
    );

    private SensitiveDataSanitizer() {
    }

    public static String sanitize(String value) {
        Objects.requireNonNull(value, "value");
        String sanitized = BEARER.matcher(value).replaceAll("Bearer " + REDACTED);
        sanitized = SECRET_ASSIGNMENT.matcher(sanitized).replaceAll("$1$2" + REDACTED);
        sanitized = WINDOWS_PATH.matcher(sanitized).replaceAll("<personal-path>");
        sanitized = UNIX_HOME_PATH.matcher(sanitized).replaceAll("<personal-path>");
        return sanitized;
    }

    public static String throwableSummary(Throwable error) {
        Objects.requireNonNull(error, "error");
        String type = error.getClass().getSimpleName();
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return type;
        }
        return sanitize(type + ": " + message);
    }
}
