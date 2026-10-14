package com.autism.seedcracker.chatgames;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ChatGameAnswerKey {
    private static final int MAX_BYTES = 256 * 1024;
    private static final Map<String, String> DEFAULTS = readDefaults();

    private ChatGameAnswerKey() {}

    public static Map<String, String> defaults() { return DEFAULTS; }

    private static Map<String, String> readDefaults() {
        try {
            Map<String, String> answers = new LinkedHashMap<>();
            for (String resource : java.util.List.of("minecraft-answers.json", "public-plugin-answers.json")) {
                try (var input = ChatGameAnswerKey.class.getResourceAsStream("/chatgames/" + resource)) {
                    if (input == null) throw new IOException("Bundled answer key missing: " + resource);
                    for (var entry : load(input).entrySet()) {
                        String previous = answers.putIfAbsent(entry.getKey(), entry.getValue());
                        if (previous != null && !previous.equals(entry.getValue())) {
                            throw new IOException("Conflicting bundled question: " + entry.getKey());
                        }
                    }
                }
            }
            return Map.copyOf(answers);
        } catch (IOException invalid) {
            throw new IllegalStateException("Bundled Minecraft answer key invalid", invalid);
        }
    }

    public static Map<String, String> load(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            return load(input);
        }
    }

    public static Map<String, String> loadText(Path file) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(file)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) throw new IOException("Answer key exceeds 256 KB");
        var entries = new com.google.gson.JsonObject();
        String question = null;
        for (String raw : new String(bytes, StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", "").split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (question == null) {
                question = line.replaceFirst("(?i)^(?:question|q)\\s*:\\s*", "");
            } else {
                String answer = line.replaceFirst("(?i)^(?:answer|a)\\s*:\\s*", "");
                if (entries.has(question) && !entries.get(question).getAsString().equals(answer)) {
                    throw new IOException("Conflicting answers for the same question");
                }
                entries.addProperty(question, answer);
                question = null;
                if (entries.size() > 2000) throw new IOException("Answer key exceeds 2000 questions");
            }
        }
        if (question != null) throw new IOException("Missing answer for the last question");
        return load(new java.io.ByteArrayInputStream(entries.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, String> load(java.io.InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new IOException("Answer key exceeds 256 KB");
        try {
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            var root = JsonParser.parseString(text);
            if (!root.isJsonObject() || root.getAsJsonObject().size() > 2000) {
                throw new IOException("Answer key must be an object with at most 2000 questions");
            }
            Map<String, String> answers = new LinkedHashMap<>();
            for (var entry : root.getAsJsonObject().entrySet()) {
                String question = entry.getKey();
                var value = entry.getValue();
                if (question.length() > 512 || question.codePoints().anyMatch(Character::isISOControl)
                    || ChatLine.isGenericHeader(question) || !value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isString()) {
                    throw new IOException("Invalid question or non-string answer");
                }
                String normalized = ChatGameSolver.normalizeQuestion(question);
                String answer = value.getAsString().trim();
                if (normalized.length() < 8 || answer.isEmpty() || answer.length() > 64
                    || answer.codePoints().anyMatch(Character::isISOControl)) {
                    throw new IOException("Invalid question or answer length/control characters");
                }
                String previous = answers.putIfAbsent(normalized, answer);
                if (previous != null && !previous.equals(answer)) {
                    throw new IOException("Conflicting answers for the same normalized question");
                }
            }
            return Map.copyOf(answers);
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid answer-key JSON", invalid);
        }
    }
}