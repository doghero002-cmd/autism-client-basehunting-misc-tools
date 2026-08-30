package com.example.donutflipscanner.market.item;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.text.Normalizer;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Pattern;

final class CanonicalTextNormalizer {
    private static final Gson GSON = new Gson();
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final int MAXIMUM_TEXT_COMPONENT_DEPTH = 32;

    String canonicalize(String input) {
        String normalized = normalizeLineEndings(input);
        String stripped = normalized.stripLeading();
        if (stripped.startsWith("{") || stripped.startsWith("[") || stripped.startsWith("\"")) {
            try {
                JsonElement parsed = JsonParser.parseString(normalized);
                return GSON.toJson(sortJson(parsed, 0));
            } catch (RuntimeException ignored) {
                // Preserve malformed or plain server text instead of discarding it.
            }
        }
        return normalizeLegacyFormatting(normalized);
    }

    String comparisonText(String canonical) {
        if (canonical.startsWith("{") || canonical.startsWith("[") || canonical.startsWith("\"")) {
            return canonical;
        }
        StringBuilder plain = new StringBuilder(canonical.length());
        for (int index = 0; index < canonical.length(); index++) {
            char character = canonical.charAt(index);
            if (character == '\u00a7' && index + 1 < canonical.length()) {
                index++;
                continue;
            }
            plain.append(character);
        }
        return WHITESPACE.matcher(plain.toString().trim()).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    private String normalizeLineEndings(String value) {
        return Normalizer.normalize(value.replace("\r\n", "\n").replace('\r', '\n'), Normalizer.Form.NFC);
    }

    private String normalizeLegacyFormatting(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            result.append(character);
            if (character == '\u00a7' && index + 1 < value.length()) {
                result.append(Character.toLowerCase(value.charAt(++index)));
            }
        }
        return result.toString();
    }

    private JsonElement sortJson(JsonElement element, int depth) {
        if (depth > MAXIMUM_TEXT_COMPONENT_DEPTH) {
            throw new IllegalArgumentException("Text component is nested too deeply");
        }
        if (element.isJsonObject()) {
            JsonObject sorted = new JsonObject();
            JsonObject object = element.getAsJsonObject();
            for (String key : new TreeSet<>(object.keySet())) {
                sorted.add(key, sortJson(object.get(key), depth + 1));
            }
            return sorted;
        }
        if (element.isJsonArray()) {
            JsonArray sortedValues = new JsonArray();
            for (JsonElement value : element.getAsJsonArray()) {
                sortedValues.add(sortJson(value, depth + 1));
            }
            return sortedValues;
        }
        return element.deepCopy();
    }
}
