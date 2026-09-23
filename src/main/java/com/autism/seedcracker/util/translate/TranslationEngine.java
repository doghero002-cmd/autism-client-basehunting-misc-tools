package com.autism.seedcracker.util.translate;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Async translation engine with an LRU cache.
 *
 * The packet and render threads must never block on the network, so translation is always
 * prefetched: callers ask for the CACHED translation (instant) and, on a miss, queue the fetch
 * and get nothing this time - the translation appears the next time the same text shows up
 * (chat re-print, tooltip re-open). Google Translate's free client endpoint is used; results
 * are cached per (target language + source text) so repeated lines cost zero network.
 */
public final class TranslationEngine {

    private static final Duration TIMEOUT = Duration.ofSeconds(8L);
    private static final int CACHE_CAP = 1000;

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "Translate");
        t.setDaemon(true);
        return t;
    });

    /** (lang + \0 + source) -> translation. Synchronized LRU, bounded. */
    private static final Map<String, String> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHE_CAP;
        }
    };

    /** Keys with a fetch already in flight (dedupes identical lookups while one is running). */
    private static final Map<String, Boolean> IN_FLIGHT = new ConcurrentHashMap<>();

    /** Throttle identical-text fetches so a spammed line doesn't hammer the endpoint. */
    private static final Map<String, Long> LAST_ATTEMPT = new ConcurrentHashMap<>();
    private static final long RETRY_MS = 15_000L;

    private static volatile boolean enabled = true;

    private TranslationEngine() {}

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    private static String key(String lang, String text) {
        return lang + '\u0000' + text;
    }

    /**
     * Returns the cached translation of {@code text} into {@code lang}, or null on a cache miss.
     * On a miss the fetch is queued in the background; a later call for the same text returns
     * the completed translation. Never blocks. Returns null immediately when the text is blank,
     * already looks like the target language can't help it, or translation is disabled.
     *
     * @param onDone optional callback fired (off-thread) when a fetch completes; used by the
     *               chat re-print. May be null.
     */
    public static String lookup(String text, String lang, Consumer<String> onDone) {
        if (!enabled || text == null) return null;
        String src = stripFormatting(text).trim();
        if (src.isEmpty() || !needsTranslation(src)) return null;
        String k = key(lang, src);
        synchronized (CACHE) {
            String hit = CACHE.get(k);
            if (hit != null) return hit;
        }
        prefetch(src, lang, onDone);
        return null;
    }

    /** Queue a fetch if not already cached / in flight / recently attempted. */
    public static void prefetch(String src, String lang, Consumer<String> onDone) {
        String k = key(lang, src);
        long now = System.currentTimeMillis();
        Long last = LAST_ATTEMPT.get(k);
        if (last != null && now - last < RETRY_MS) return;
        if (IN_FLIGHT.putIfAbsent(k, Boolean.TRUE) != null) return;
        LAST_ATTEMPT.put(k, now);
        POOL.submit(() -> {
            try {
                String translated = fetch(src, lang);
                if (translated != null && !translated.isBlank()
                    && !translated.equalsIgnoreCase(src)) {
                    synchronized (CACHE) {
                        CACHE.put(k, translated);
                    }
                    if (onDone != null) onDone.accept(translated);
                }
            } catch (Throwable ignored) {
                // Network/parse failure: leave uncached; RETRY_MS backs off repeats.
            } finally {
                IN_FLIGHT.remove(k);
            }
        });
    }

    /** True when the text has letters worth translating (skips pure numbers/symbols/coords). */
    private static boolean needsTranslation(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) return true;
        }
        return false;
    }

    /** Strip Minecraft section-sign formatting codes so they don't pollute the translation. */
    private static String stripFormatting(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u00A7' && i + 1 < s.length()) { i++; continue; }
            out.append(c);
        }
        return out.toString();
    }

    /** Google Translate free endpoint; returns the translated string or null on failure. */
    private static String fetch(String text, String tl) throws Exception {
        String q = URLEncoder.encode(text, StandardCharsets.UTF_8);
        URI uri = URI.create("https://translate.googleapis.com/translate_a/single?client=gtx"
            + "&sl=auto&tl=" + tl + "&dt=t&q=" + q);
        HttpRequest req = HttpRequest.newBuilder(uri)
            .timeout(TIMEOUT)
            .header("User-Agent", "Mozilla/5.0")
            .GET()
            .build();
        String body = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
        return parse(body);
    }

    /**
     * Parses the Google gtx response: [[["translated","original",...],...],null,"auto"].
     * Minimal hand-rolled JSON walk (no dependency) - collects the FIRST string of each
     * innermost segment array (depth 3, index 0), which is the translated text. Bracket depth
     * is tracked so strings inside the source/metadata aren't picked up.
     */
    private static String parse(String json) {
        if (json == null || json.length() < 5) return null;
        StringBuilder out = new StringBuilder();
        int n = json.length();
        int depth = 0;
        int segIdx = -1;        // element index within the current innermost array
        int i = 0;
        while (i < n) {
            char c = json.charAt(i);
            if (c == '[') {
                depth++;
                if (depth == 3) segIdx = 0; // start of a segment array
                i++;
            } else if (c == ']') {
                depth--;
                i++;
            } else if (c == ',') {
                if (depth == 3) segIdx++;
                i++;
            } else if (c == '"') {
                StringBuilder sb = new StringBuilder();
                i++; // skip opening quote
                boolean closed = false;
                while (i < n) {
                    char ch = json.charAt(i);
                    if (ch == '\\' && i + 1 < n) {
                        char e = json.charAt(i + 1);
                        switch (e) {
                            case 'n' -> sb.append('\n');
                            case 't' -> sb.append('\t');
                            case 'r' -> sb.append('\r');
                            case '"' -> sb.append('"');
                            case '\\' -> sb.append('\\');
                            case '/' -> sb.append('/');
                            case 'u' -> {
                                if (i + 5 < n) {
                                    try {
                                        sb.append((char) Integer.parseInt(json.substring(i + 2, i + 6), 16));
                                    } catch (NumberFormatException ex) { /* keep going */ }
                                    i += 4;
                                }
                            }
                            default -> sb.append(e);
                        }
                        i += 2;
                        continue;
                    }
                    if (ch == '"') { closed = true; i++; break; }
                    sb.append(ch);
                    i++;
                }
                if (!closed) break;
                // First string of a depth-3 segment array is a translated chunk.
                if (depth == 3 && segIdx == 0) out.append(sb);
            } else {
                i++;
            }
        }
        String result = out.toString().trim();
        return result.isEmpty() ? null : result;
    }

    public static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
        }
        IN_FLIGHT.clear();
        LAST_ATTEMPT.clear();
    }
}
