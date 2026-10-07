package com.autism.seedcracker.chatgames;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local (offline) chat-game solver, pure and unit tested. Recognises the common server chat-game
 * formats and answers them without any API:
 * <ul>
 *   <li>MATH: "Solve 12 + 7 * 3", "What is 5 x 6?", word problems ("five plus three")</li>
 *   <li>UNSCRAMBLE: "Unscramble: dnmiaod" (anagram lookup against a word dictionary)</li>
 *   <li>TYPE: "First to type 'Pumpkin Pie' wins" (exact retype)</li>
 *   <li>REVERSE: "Reverse: nomaid" / "Type backwards: diamond"</li>
 *   <li>FILL: "Fill in: D_am_nd" (blank-pattern lookup)</li>
 *   <li>TRIVIA: questions seen before whose answer was announced (learned memory)</li>
 * </ul>
 * Unknown formats return null so the module can fall back to AI or stay quiet.
 */
public final class ChatGameSolver {

    public enum Kind { MATH, UNSCRAMBLE, TYPE, REVERSE, FILL, TRIVIA }

    public record Answer(Kind kind, String text) {}

    private static final Pattern QUOTED = Pattern.compile("[\"'\u201C\u2018`]([^\"'\u201D\u2019`]{1,64})[\"'\u201D\u2019`]");
    private static final Pattern AFTER_COLON = Pattern.compile(":\\s*([^:]{1,64})$");
    private static final Pattern UNSCRAMBLE = Pattern.compile("(?i)\\b(unscramble|scrambled|unjumble|jumbled|anagram)\\b");
    private static final Pattern TYPE = Pattern.compile("(?i)\\b(type|write|say|copy)\\b");
    private static final Pattern REVERSE = Pattern.compile("(?i)\\b(reverse|reversed|backwards?)\\b");
    private static final Pattern FILL = Pattern.compile("(?i)\\b(fill in|fill the|missing letters?|complete the word)\\b");
    private static final Pattern MATH_CUE = Pattern.compile("(?i)\\b(solve|calculate|math|what is|what's|equals?|compute)\\b|=\\s*\\?");
    private static final Pattern DATE_OR_TIME = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b|\\b\\d{1,2}[/:]\\d{1,2}(?:[/:]\\d{2,4})?\\b");

    private final Map<String, List<String>> anagrams = new HashMap<>();
    private final Map<Integer, List<String>> byLength = new HashMap<>();
    private final Map<String, String> trivia = new HashMap<>();

    public ChatGameSolver(Collection<String> dictionary) {
        for (String w : dictionary) addWord(w);
    }

    /** Adds a word or a multi-word name ("golden apple"); phrases unscramble but aren't used for fill-ins. */
    public void addWord(String raw) {
        if (raw == null) return;
        String w = raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        String letters = w.replaceAll("[^a-z]", "");
        if (letters.length() < 3 || w.length() > 40) return;
        List<String> group = anagrams.computeIfAbsent(sortedLetters(letters), k -> new ArrayList<>());
        if (group.contains(w)) return;
        group.add(w);
        if (w.equals(letters)) byLength.computeIfAbsent(w.length(), k -> new ArrayList<>()).add(w);
    }

    public int dictionarySize() {
        int n = 0;
        for (List<String> l : byLength.values()) n += l.size();
        return n;
    }

    /** Remember the answer the server announced for a question (trivia memory). */
    public void learn(String question, String answer) {
        String q = normalizeQuestion(question);
        if (q.length() < 8 || answer == null || answer.isBlank() || answer.length() > 64) return;
        trivia.put(q, answer.trim());
    }

    public Map<String, String> triviaSnapshot() {
        return Map.copyOf(trivia);
    }

    /** Best answer for a prompt, or null when it isn't a format we can solve with confidence. */
    public Answer solve(String prompt, boolean allowWordProblems) {
        if (prompt == null || prompt.isBlank()) return null;
        String p = prompt.trim();

        String known = trivia.get(normalizeQuestion(p));
        if (known != null) return new Answer(Kind.TRIVIA, known);

        if (UNSCRAMBLE.matcher(p).find()) {
            String target = payload(p);
            String word = target == null ? null : unscramble(target);
            return word == null ? null : new Answer(Kind.UNSCRAMBLE, matchCase(word, target));
        }
        if (REVERSE.matcher(p).find()) {
            String target = payload(p);
            return target == null ? null : new Answer(Kind.REVERSE, new StringBuilder(target).reverse().toString());
        }
        if (FILL.matcher(p).find() || p.indexOf('_') >= 0) {
            String target = blankPattern(p);
            String word = target == null ? null : fill(target);
            if (word != null) return new Answer(Kind.FILL, matchCase(word, target));
        }

        String expr = extractMath(p);
        if (expr != null) {
            Double v = MathSolver.solve(expr);
            if (v != null) return new Answer(Kind.MATH, MathSolver.format(v));
        }
        if (allowWordProblems && MATH_CUE.matcher(p).find()) {
            Double v = MathSolver.solveWordProblem(p);
            if (v != null) return new Answer(Kind.MATH, MathSolver.format(v));
        }

        // Retype games quote the exact text; checked last so "Type the answer to 5+5" is still math.
        if (TYPE.matcher(p).find()) {
            Matcher q = QUOTED.matcher(p);
            if (q.find()) return new Answer(Kind.TYPE, q.group(1).trim());
        }
        return null;
    }

    /** All dictionary words that are anagrams of {@code scrambled}; the first is returned. */
    public String unscramble(String scrambled) {
        String letters = scrambled.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        List<String> hits = anagrams.get(sortedLetters(letters));
        if (hits == null) return null;
        // A scramble that happens to spell a real word is never the answer itself.
        for (String h : hits) if (!h.replaceAll("[^a-z]", "").equals(letters)) return h;
        return hits.get(0);
    }

    /** Unique dictionary word matching an underscore pattern ("d_am_nd"); null when ambiguous. */
    public String fill(String pattern) {
        String pat = pattern.toLowerCase(Locale.ROOT);
        List<String> candidates = byLength.get(pat.length());
        if (candidates == null) return null;
        String found = null;
        outer:
        for (String w : candidates) {
            for (int i = 0; i < pat.length(); i++) {
                char c = pat.charAt(i);
                if (c != '_' && c != w.charAt(i)) continue outer;
            }
            if (found != null) return null;
            found = w;
        }
        return found;
    }

    /**
     * The longest run of a real arithmetic expression. Letters break a run (so "max" or "box" are
     * never read as multiplication), "x" counts only between two numbers, and dates/times like
     * 12/4 or 10:30 are skipped.
     */
    public static String extractMath(String text) {
        String t = DATE_OR_TIME.matcher(text).replaceAll(" ");
        t = t.replaceAll("(?<=\\d)\\s*[xX\u00D7]\\s*(?=[\\d(])", "*").replace('\u00F7', '/');
        Matcher m = Pattern.compile("[-+*/%^()\\d.\\s]+").matcher(t);
        String best = null;
        while (m.find()) {
            String run = m.group().trim();
            if (!run.matches(".*\\d.*") || !run.matches(".*\\d\\s*[-+*/%^]\\s*[-(\\d].*")) continue;
            if (best == null || run.length() > best.length()) best = run;
        }
        return best;
    }

    /** The word the game wants worked on: quoted text, else the text after the last colon, else the last word. */
    static String payload(String prompt) {
        Matcher q = QUOTED.matcher(prompt);
        if (q.find()) return q.group(1).trim();
        Matcher c = AFTER_COLON.matcher(prompt.trim());
        if (c.find()) {
            String s = c.group(1).trim().replaceAll("[.!?]+$", "");
            if (!s.isEmpty() && !s.contains(" ")) return s;
        }
        String[] words = prompt.trim().replaceAll("[.!?]+$", "").split("\\s+");
        String last = words[words.length - 1];
        return last.matches("[A-Za-z]{3,40}") ? last : null;
    }

    private static String blankPattern(String prompt) {
        Matcher m = Pattern.compile("\\b[A-Za-z]*_[A-Za-z_]*\\b").matcher(prompt);
        String best = null;
        while (m.find()) if (best == null || m.group().length() > best.length()) best = m.group();
        return best != null && best.length() >= 3 ? best : null;
    }

    /** Keep the prompt's capitalisation style (ALL CAPS / Title / lower). */
    static String matchCase(String word, String like) {
        if (like == null || like.isEmpty()) return word;
        String letters = like.replaceAll("[^A-Za-z]", "");
        if (letters.length() > 1 && letters.equals(letters.toUpperCase(Locale.ROOT))) return word.toUpperCase(Locale.ROOT);
        if (Character.isUpperCase(like.charAt(0))) return Character.toUpperCase(word.charAt(0)) + word.substring(1);
        return word;
    }

    static String normalizeQuestion(String q) {
        return q == null ? "" : q.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static String sortedLetters(String w) {
        char[] c = w.toCharArray();
        Arrays.sort(c);
        return new String(c);
    }
}
