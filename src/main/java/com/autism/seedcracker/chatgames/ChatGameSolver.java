package com.autism.seedcracker.chatgames;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private static final Pattern AFTER_COLON = Pattern.compile(":\\s*((?:/[^\\s:]+:)?[^:\\r\\n]{1,64})$");
    private static final Pattern UNSCRAMBLE = Pattern.compile("(?i)\\b(unscramble|scrambled|unjumble|jumbled|anagram)\\b");
    private static final Pattern TYPE = Pattern.compile("(?i)\\b(type|write|say|copy)\\b");
    private static final Pattern REVERSE = Pattern.compile("(?i)\\b(unreverse|reverse|reversed|backwards?)\\b");
    private static final Pattern FILL = Pattern.compile("(?i)\\b(fill in|fill the|missing letters?|complete the word)\\b");
    private static final Pattern MATH_CUE = Pattern.compile("(?i)\\b(solve|calculate|math|what is|what's|equals?|compute)\\b|=\\s*\\?");
    private static final Pattern DATE_OR_TIME = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b|\\b\\d{1,2}[/:]\\d{1,2}(?:[/:]\\d{2,4})?\\b");
    private static final Pattern MATH_RUN = Pattern.compile("[-+*/%^()\\d.\\s]+");
    private static final Pattern MATH_OPERATION = Pattern.compile("\\d\\s*[-+*/%^]\\s*[-(\\d]");
    private static final Pattern LEADING_PUNCTUATION = Pattern.compile("^\\.+\\s+");
    private static final Pattern MULTIPLICATION = Pattern.compile("(?<=\\d)\\s*[xX\u00D7]\\s*(?=[\\d(])");
    private static final Pattern EXPLICIT_TYPE = Pattern.compile("(?i)\\b(?:type|write|say|copy)\\s*:\\s*([^\\r\\n]{1,64})$");
    private static final Pattern FIRST_TYPE = Pattern.compile("(?is)\\bfirst\\s+to\\s+(?:type|write|say|copy|retype)\\s+(.{1,64}?)\\s+wins\\b.*$");
    private static final Pattern FALLBACK_WORD = Pattern.compile("[a-z]+(?:[ '-][a-z]+)*");

    private final Map<String, List<String>> anagrams = new HashMap<>();
    private final Map<String, List<String>> fallbackAnagrams = new HashMap<>();
    private final Map<String, List<String>> commandAnagrams = new HashMap<>();
    private final Set<String> commands = new HashSet<>();
    private final Map<Integer, List<String>> byLength = new HashMap<>();
    private final Map<Integer, List<String>> fallbackByLength = new HashMap<>();
    private final Map<String, String> trivia = new HashMap<>();
    private final Map<String, String> answerKey = new HashMap<>();
    private final Set<String> learnedWords = new java.util.LinkedHashSet<>();

    public ChatGameSolver(Collection<String> dictionary) {
        for (String w : dictionary) addWord(w);
    }

    /** Adds a word or a multi-word name for unscrambling and fill-ins. */
    public void addWord(String raw) {
        if (raw == null) return;
        String w = raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        String letters = w.replaceAll("[^a-z0-9]", "");
        if (w.replaceAll("[^a-z]", "").length() < 3 || w.length() > 64) return;
        List<String> group = anagrams.computeIfAbsent(sortedLetters(letters), k -> new ArrayList<>());
        if (group.stream().anyMatch(existing -> existing.replaceAll("[^a-z0-9]", "").equals(letters))) return;
        group.add(w);
        byLength.computeIfAbsent(w.length(), k -> new ArrayList<>()).add(w);
    }

    public int dictionarySize() {
        int n = 0;
        for (List<String> l : anagrams.values()) n += l.size();
        for (List<String> l : fallbackAnagrams.values()) n += l.size();
        return n;
    }

    public boolean containsWord(String raw) {
        if (raw == null) return false;
        String identity = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return anagrams.getOrDefault(sortedLetters(identity), List.of()).stream()
            .anyMatch(word -> word.replaceAll("[^a-z0-9]", "").equals(identity));
    }

    public void addLearnedWord(String raw) {
        if (raw == null || learnedWords.size() >= 2000) return;
        String word = raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (word.length() > 64 || !word.matches("[a-z0-9][a-z0-9 '_:-]*") || word.replaceAll("[^a-z]", "").length() < 3) return;
        addWord(word);
        learnedWords.add(word);
    }

    public Set<String> learnedWordsSnapshot() { return Set.copyOf(learnedWords); }

    public void addFallbackWord(String raw) {
        if (raw == null) return;
        String word = raw.trim().toLowerCase(Locale.ROOT);
        if (word.length() > 40 || !FALLBACK_WORD.matcher(word).matches()) return;
        String letters = word.replace(" ", "").replace("'", "").replace("-", "");
        if (letters.length() < 3) return;
        String signature = sortedLetters(letters);
        List<String> primary = anagrams.get(signature);
        if (primary != null && primary.stream().anyMatch(existing -> existing.replaceAll("[^a-z0-9]", "").equals(letters))) return;
        List<String> group = fallbackAnagrams.computeIfAbsent(signature, key -> new ArrayList<>());
        if (group.stream().anyMatch(existing -> existing.replace(" ", "").replace("'", "").replace("-", "").equals(letters))) return;
        group.add(word);
        fallbackByLength.computeIfAbsent(word.length(), key -> new ArrayList<>()).add(word);
    }

    public void setCommands(Collection<String> names) {
        commandAnagrams.clear();
        commands.clear();
        for (String name : names) {
            if (name == null || !name.matches("[A-Za-z0-9_:-]{1,64}")) continue;
            String command = "/" + name;
            commands.add(name.toLowerCase(Locale.ROOT));
            List<String> group = commandAnagrams.computeIfAbsent(commandSignature(command), key -> new ArrayList<>());
            if (!group.contains(command)) group.add(command);
        }
    }

    /** Remember the answer the server announced for a question (trivia memory). */
    public void learn(String question, String answer) {
        if (ChatLine.isGenericHeader(question)) return;
        String q = normalizeQuestion(question);
        if (q.length() < 8 || answer == null || answer.isBlank() || answer.length() > 64) return;
        trivia.put(q, answer.trim());
    }

    public void learnReveal(String question, String answer, boolean remember) {
        String pattern = question == null ? null : blankPattern(question);
        if (pattern != null && FILL.matcher(question).find()) {
            String completed = completeFill(pattern, answer);
            if (completed == null) return;
            addLearnedWord(completed);
            if (remember) learn(question, ChatLine.missingLettersOnly(question) ? missingLetters(pattern, completed) : completed);
        } else {
            if (remember) learn(question, answer);
            if (question == null || !FIRST_TYPE.matcher(question).find()) addLearnedWord(answer);
        }
    }

    public Map<String, String> triviaSnapshot() {
        return Map.copyOf(trivia);
    }

    public void setAnswerKey(Map<String, String> answers) {
        answerKey.clear();
        answers.forEach((question, answer) -> answerKey.put(normalizeQuestion(question), answer));
    }

    public int answerKeySize() {
        return answerKey.size();
    }

    public int defaultAnswerCount() {
        return ChatGameAnswerKey.defaults().size();
    }

    /** Best answer for a prompt, or null when it isn't a format we can solve with confidence. */
    public Answer solve(String prompt, boolean allowWordProblems, boolean genericPayload) {
        Answer answer = solve(prompt, allowWordProblems);
        if (answer == null && genericPayload && prompt != null && prompt.matches("[A-Za-z0-9_/.-]{1,64}")) {
            String letters = prompt.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            List<String> candidates = anagrams.get(sortedLetters(letters));
            if (candidates == null) candidates = fallbackAnagrams.get(sortedLetters(letters));
            if (candidates != null && candidates.stream().noneMatch(word -> word.replaceAll("[^a-z]", "").equals(letters))) {
                return solve("Unscramble: " + prompt, allowWordProblems);
            }
            return new Answer(Kind.TYPE, prompt);
        }
        return answer;
    }

    public Answer solve(String prompt, boolean allowWordProblems) {
        if (prompt == null || prompt.isBlank()) return null;
        String p = ChatLine.questionText(prompt);
        if (ChatLine.isGenericHeader(p)) return null;
        Matcher firstType = FIRST_TYPE.matcher(p);
        if (firstType.find()) {
            String target = firstType.group(1).trim().replaceAll("\\s*\\R\\s*", " ");
            Matcher quoted = QUOTED.matcher(target);
            return new Answer(Kind.TYPE, quoted.matches() ? quoted.group(1).trim() : target);
        }

        if (UNSCRAMBLE.matcher(p).find()) {
            String target = payload(p);
            String word = target == null ? null : unscramble(target);
            String remembered = trivia.get(normalizeQuestion(p));
            if (target != null && remembered != null) {
                if (target.startsWith("/")) {
                    List<String> commands = commandAnagrams.get(commandSignature(target));
                    if (commands != null && commands.contains(remembered)) word = remembered;
                } else if (sortedLetters(target.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""))
                    .equals(sortedLetters(remembered.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "")))) {
                    word = remembered;
                }
            }
            return word == null ? null : new Answer(Kind.UNSCRAMBLE,
                target.startsWith("/") ? word : matchCase(word, target));
        }
        if (REVERSE.matcher(p).find()) {
            String target = payload(p);
            return target == null ? null : new Answer(Kind.REVERSE, new StringBuilder(target).reverse().toString());
        }
        if (FILL.matcher(p).find() || p.indexOf('_') >= 0) {
            String target = blankPattern(p);
            String word = target == null ? null : fill(target);
            if (word == null && target != null && FILL.matcher(p).find()) {
                String remembered = answerKey.get(normalizeQuestion(p));
                if (remembered == null) remembered = trivia.get(normalizeQuestion(p));
                word = completeFill(target, remembered);
                if (word == null) return null;
            }
            if (word != null) {
                String completed = matchCase(word, target);
                return new Answer(Kind.FILL, ChatLine.missingLettersOnly(p) ? missingLetters(target, completed) : completed);
            }
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
            Matcher explicit = EXPLICIT_TYPE.matcher(p);
            if (explicit.find()) return new Answer(Kind.TYPE, explicit.group(1).trim());
            Matcher first = FIRST_TYPE.matcher(p);
            if (first.find()) return new Answer(Kind.TYPE, first.group(1).trim());
        }
        String known = answerKey.get(normalizeQuestion(p));
        if (known == null) known = trivia.get(normalizeQuestion(p));
        if (known == null) known = ChatGameAnswerKey.defaults().get(normalizeQuestion(p));
        if (known != null) return new Answer(Kind.TRIVIA, known);
        return minecraftQuestion(p);
    }

    private Answer minecraftQuestion(String prompt) {
        if (!ChatLine.isQuestion(prompt)) return null;
        String question = normalizeQuestion(prompt);
        if (question.matches(".*\\b(?:not|never|cannot|can t|doesn t|don t|isn t|aren t|except|without|besides)\\b.*")) return null;
        if (question.matches(".*\\b(?:mob|mobs|creature|creatures|monster|monsters|enemy|enemies)\\b.*")
            && question.matches(".*\\b(?:explode|explodes|exploding|blows? up|detonate|detonates)\\b.*")
            && question.matches(".*\\b(?:close|near|nearby|approach|approaches|approaching|proximity|next to)\\b.*")
            && !question.matches(".*\\b(?:shoot|shoots|projectile|fireball|arrow|arrows)\\b.*")) {
            return new Answer(Kind.TRIVIA, "creeper");
        }
        if (question.contains("nether portal") && question.matches(".*\\b(?:block|blocks|material|materials)\\b.*")
            && question.matches(".*\\b(?:build|builds|building|built|make|makes|making|made|construct|constructs|frame|use|uses|used)\\b.*")
            && !question.matches(".*\\b(?:light|ignite|activate|end)\\b.*")) {
            return new Answer(Kind.TRIVIA, "obsidian");
        }
        if (question.contains("ender dragon") && (question.contains("dimension")
            || question.startsWith("where ") && question.matches(".*\\b(?:live|lives|spawn|spawns|found)\\b.*"))) {
            return new Answer(Kind.TRIVIA, "the end");
        }
        if (question.contains("elytra") && question.matches(".*\\b(?:repair|repairs|repairing)\\b.*")
            && question.matches(".*\\b(?:item|items|material|materials)\\b.*")) {
            return new Answer(Kind.TRIVIA, "phantom membrane");
        }
        if (question.contains("command") && question.matches(".*\\b(?:sell|sells|selling)\\b.*")
            && question.contains("all") && question.matches(".*\\b(?:drops|loot|items)\\b.*")
            && commands.contains("sellall")) {
            return new Answer(Kind.TRIVIA, "sellall");
        }
        if (question.contains("command") && question.contains("spawn")
            && question.matches(".*\\b(?:teleport|teleports|return|returns)\\b.*") && commands.contains("spawn")) {
            return new Answer(Kind.TRIVIA, "spawn");
        }
        return null;
    }

    public boolean validatesAiAnswer(String prompt, String answer) {
        if (answer == null || answer.isBlank()) return false;
        Answer known = solve(prompt, true);
        if (known != null) return ChatLine.sameAnswer(ChatLine.answerText(known.text()), ChatLine.answerText(answer));
        String question = ChatLine.questionText(prompt);
        if (UNSCRAMBLE.matcher(question).find()) {
            String target = payload(question);
            if (target == null) return false;
            if (target.startsWith("/")) {
                String command = "/" + ChatLine.answerText(answer);
                return commands.contains(ChatLine.answerText(answer).toLowerCase(Locale.ROOT))
                    && commandSignature(target).equals(commandSignature(command));
            }
            return sortedLetters(target.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""))
                .equals(sortedLetters(answer.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "")));
        }
            if (FILL.matcher(question).find()) {
                String pattern = blankPattern(question);
                String completed = pattern == null ? null : completeFill(pattern, answer);
                return completed != null && ChatLine.sameAnswer(answer,
                ChatLine.missingLettersOnly(question) ? missingLetters(pattern, completed) : completed);
            }
        return true;
    }

    /** Unique alternative anagram; ambiguous matches stay unanswered. */
    public String unscramble(String scrambled) {
        if (scrambled == null || scrambled.isBlank()) return null;
        if (scrambled.trim().startsWith("/")) {
            String signature = commandSignature(scrambled);
            List<String> commands = commandAnagrams.get(signature);
            if (commands == null) return null;
            String found = null;
            for (String command : commands) {
                if (command.equalsIgnoreCase(scrambled.trim())) continue;
                if (found != null) return null;
                found = command;
            }
            return found != null ? found : commands.size() == 1 ? commands.get(0) : null;
        }
        String letters = scrambled.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        List<String> hits = anagrams.get(sortedLetters(letters));
        if (hits == null) hits = fallbackAnagrams.get(sortedLetters(letters));
        if (hits == null) return null;
        // A scramble that happens to spell a real word is never the answer itself.
        String found = null;
        for (String hit : hits) {
            if (hit.replaceAll("[^a-z0-9]", "").equals(letters)) continue;
            if (found != null) return null;
            found = hit;
        }
        return found != null ? found : hits.size() == 1 ? hits.get(0) : null;
    }

    private static String commandSignature(String command) {
        return sortedLetters(command.trim().toLowerCase(Locale.ROOT).replaceFirst("^/", "").replaceAll("\\s+", ""));
    }

    /** Unique dictionary word matching an underscore pattern ("d_am_nd"); null when ambiguous. */
    public String fill(String pattern) {
        String pat = pattern.toLowerCase(Locale.ROOT);
        for (List<String> candidates : List.of(byLength.getOrDefault(pat.length(), List.of()),
            fallbackByLength.getOrDefault(pat.length(), List.of()))) {
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
            if (found != null) return found;
        }
        return null;
    }

    private static String missingLetters(String pattern, String completed) {
        StringBuilder missing = new StringBuilder();
        for (int index = 0; index < pattern.length(); index++) {
            if (pattern.charAt(index) == '_') missing.append(completed.charAt(index));
        }
        return missing.toString();
    }

    private static String completeFill(String pattern, String answer) {
        if (answer == null) return null;
        String candidate = answer.trim();
        if (candidate.length() == pattern.length()) {
            for (int index = 0; index < pattern.length(); index++) {
                if (pattern.charAt(index) == '_' && !Character.isLetterOrDigit(candidate.charAt(index))) return null;
                if (pattern.charAt(index) != '_' && Character.toLowerCase(pattern.charAt(index)) != Character.toLowerCase(candidate.charAt(index))) return null;
            }
            return candidate;
        }
        long missing = pattern.chars().filter(character -> character == '_').count();
        if (candidate.length() != missing || !candidate.matches("[A-Za-z0-9]+")) return null;
        StringBuilder completed = new StringBuilder(pattern);
        int offset = 0;
        for (int index = 0; index < pattern.length(); index++) {
            if (pattern.charAt(index) == '_') completed.setCharAt(index, candidate.charAt(offset++));
        }
        return completed.toString();
    }

    /**
     * The longest run of a real arithmetic expression. Letters break a run (so "max" or "box" are
     * never read as multiplication), "x" counts only between two numbers, and dates/times like
     * 12/4 or 10:30 are skipped.
     */
    public static String extractMath(String text) {
        String normalized = text.replace('\u00A0', ' ').replace('\u202F', ' ').replace('\u2212', '-');
        boolean explicitMath = MATH_CUE.matcher(normalized).find();
        String t = DATE_OR_TIME.matcher(normalized).replaceAll(match ->
            explicitMath && match.group().indexOf('/') >= 0 ? match.group() : " ");
        t = MULTIPLICATION.matcher(t).replaceAll("*").replace('\u00F7', '/');
        Matcher m = MATH_RUN.matcher(t);
        String best = null;
        while (m.find()) {
            String run = LEADING_PUNCTUATION.matcher(m.group().trim()).replaceFirst("").trim();
            if (!MATH_OPERATION.matcher(run).find()) continue;
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
            if (!s.isEmpty()) return s;
        }
        String[] words = prompt.trim().replaceAll("[.!?]+$", "").split("\\s+");
        String last = words[words.length - 1];
        return last.matches("[A-Za-z]{3,40}") ? last : null;
    }

    private static String blankPattern(String prompt) {
        Matcher quoted = QUOTED.matcher(prompt);
        if (quoted.find() && quoted.group(1).contains("_") && quoted.group(1).matches("[A-Za-z0-9_ -]{3,64}")) return quoted.group(1).trim();
        Matcher colon = AFTER_COLON.matcher(prompt.trim());
        if (colon.find() && colon.group(1).contains("_") && colon.group(1).matches("[A-Za-z0-9_ -]{3,64}")) return colon.group(1).trim();
        Matcher m = Pattern.compile("(?<![A-Za-z0-9_])[A-Za-z0-9_]*_[A-Za-z0-9_]*(?![A-Za-z0-9_])").matcher(prompt);
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
        return q == null ? "" : ChatLine.questionText(q).toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static String sortedLetters(String w) {
        char[] c = w.toCharArray();
        Arrays.sort(c);
        return new String(c);
    }
}
