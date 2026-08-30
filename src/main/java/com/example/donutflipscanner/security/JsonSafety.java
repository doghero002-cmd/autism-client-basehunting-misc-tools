package com.example.donutflipscanner.security;

import java.util.Objects;

/** Performs allocation-free size/depth checks before a JSON parser builds an object tree. */
public final class JsonSafety {
    private JsonSafety() {
    }

    public static void validateBounds(String json, int maximumCharacters, int maximumDepth) {
        Objects.requireNonNull(json, "json");
        if (maximumCharacters < 1 || maximumDepth < 1) {
            throw new IllegalArgumentException("JSON limits must be positive");
        }
        if (json.length() > maximumCharacters) {
            throw new IllegalArgumentException("JSON document exceeds the configured size limit");
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < json.length(); index++) {
            char value = json.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == '"') {
                    inString = false;
                } else if (value < 0x20) {
                    throw new IllegalArgumentException("JSON string contains an unescaped control character");
                }
                continue;
            }
            if (value == '"') {
                inString = true;
            } else if (value == '{' || value == '[') {
                depth++;
                if (depth > maximumDepth) {
                    throw new IllegalArgumentException("JSON document exceeds the configured nesting limit");
                }
            } else if (value == '}' || value == ']') {
                depth--;
                if (depth < 0) {
                    throw new IllegalArgumentException("JSON document has unbalanced nesting");
                }
            }
        }
        if (inString || depth != 0) {
            throw new IllegalArgumentException("JSON document is incomplete");
        }
    }
}
