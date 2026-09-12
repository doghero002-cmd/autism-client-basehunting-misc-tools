package com.autism.seedcracker.chatgames;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Routes a chat-game question to an OpenAI-compatible chat-completions API and returns the
 * model's short answer. Works with any endpoint that accepts {model, messages:[{role,content}]}
 * and returns choices[0].message.content (OpenAI, OpenRouter, Ollama /v1/chat/completions, etc).
 */
public final class AiRouter {
    private AiRouter() {}

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8)).build();

    /** Ask the AI for a concise answer to the question. Async; resolves to the answer or null. */
    public static CompletableFuture<String> ask(String endpoint, String apiKey, String model, String question) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String prompt = "Answer this chat-game question with ONLY the final answer, no explanation. "
                    + "If it's a math problem, give just the number. Question: " + question;
                String body = "{"
                    + "\"model\":" + json(model) + ","
                    + "\"messages\":[{\"role\":\"user\",\"content\":" + json(prompt) + "}],"
                    + "\"max_tokens\":32,\"temperature\":0"
                    + "}";
                HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
                if (apiKey != null && !apiKey.isBlank()) {
                    b.header("Authorization", "Bearer " + apiKey.trim());
                }
                HttpResponse<String> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) return null;
                return extractContent(resp.body());
            } catch (Exception e) {
                return null;
            }
        });
    }

    private static String extractContent(String json) {
        // Find "content":" ... " after "message". Minimal tolerant parse (no full JSON lib needed).
        int msgIdx = json.indexOf("\"message\"");
        if (msgIdx < 0) msgIdx = 0;
        int contentIdx = json.indexOf("\"content\"", msgIdx);
        if (contentIdx < 0) return null;
        int colon = json.indexOf(':', contentIdx);
        if (colon < 0) return null;
        int firstQuote = json.indexOf('"', colon);
        if (firstQuote < 0) return null;
        StringBuilder sb = new StringBuilder();
        boolean esc = false;
        for (int i = firstQuote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (esc) { sb.append(c); esc = false; continue; }
            if (c == '\\') { esc = true; continue; }
            if (c == '"') break;
            sb.append(c);
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? null : out;
    }

    private static String json(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
