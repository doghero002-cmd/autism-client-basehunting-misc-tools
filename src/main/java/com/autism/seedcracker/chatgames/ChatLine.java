package com.autism.seedcracker.chatgames;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Classifies chat lines around a chat game (pure, unit tested): answer reveals, round ends, player chatter. */
public final class ChatLine {
    private ChatLine() {}

    private static final Pattern REVEAL = Pattern.compile(
        "(?i)\\b(?:correct )?(?:answer|word|solution)\\s+(?:was|is)\\s*(?::|-(?!\\d))?\\s*[\"'`]?([^\"'`\\r\\n]{1,64})[\"'`]?");
    private static final Pattern ROUND_OVER = Pattern.compile(
        "(?i)\\b(?:won|answered (?:it )?correctly|guessed (?:it|correctly|the word)|got it right|was (?:the )?first|nobody|no one|time'?s up)\\b");
    private static final Pattern GENERIC_HEADER = Pattern.compile("(?i)^[^\\p{L}\\p{N}]*(?:(?:chat\\s*games?[^\\p{L}\\p{N}]*)?first\\s+to\\s+answer\\s+wins[.!]?\\s*|chat\\s*games?[^\\p{L}\\p{N}]*)$");

    public static boolean isGenericHeader(String line) {
        return line != null && GENERIC_HEADER.matcher(line.trim()).find();
    }
    private static final Pattern COUNTDOWN = Pattern.compile(
        "(?i)^(?:you have|time left:?|time remaining:?)\\s*\\d+\\s*(?:seconds?|secs?|s)[.!]?$");
    private static final Pattern TIMED_HEADER = Pattern.compile(
        "(?i)^you have\\s+\\d+\\s+seconds?\\s+to\\s+"
            + "(unscramble this word|fill in the blanks)[.!]?$");
    private static final Pattern WORD_PAYLOAD_PREFIX = Pattern.compile("^>\\s+");
    private static final Pattern PRIZE_PROMPT = Pattern.compile(
        "(?i)^(solve|unscramble)\\s+(.{1,512}?)\\s+to win a prize!$");
    private static final Pattern FIRST_PROMPT = Pattern.compile(
        "(?i)^(?:the\\s+)?first\\s+to\\s+(?:type|write|say|copy|retype|fill\\s+in|unreverse|reverse|unscramble)\\b");
    private static final Pattern FIRST_END = Pattern.compile("(?is)\\bwins[.!]?(?:\\s*\\([^)]*\\))?\\s*$");
    private static final Pattern FIRST_FILL = Pattern.compile("(?i)^(?:the\\s+)?first\\s+to\\s+fill\\s+in\\b");
    private static final Pattern MISSING_ONLY = Pattern.compile("(?i)\\bonly\\s+type\\s+(?:the\\s+)?missing\\s+letters\\b");
    private static final Pattern REVEAL_HEADER = Pattern.compile("(?i)\\b(?:answer|word|solution)\\s+(?:was|is)\\s*[:!-]?\\s*$");
    private static final Pattern QUOTED_REVEAL = Pattern.compile("^[\"'\u201C\u2018`]([^\"'\u201D\u2019`\\r\\n]{1,64})[\"'\u201D\u2019`][.!]?$");

    public static boolean missingLettersOnly(String prompt) {
        return prompt != null && MISSING_ONLY.matcher(prompt).find();
    }

    public static boolean isRevealHeader(String line) {
        return line != null && REVEAL_HEADER.matcher(line).find();
    }
    private static final String GAME_PREFIX = "^[^\\p{L}\\p{N}\\[]*(?:\\[[^\\]]{1,32}\\]\\s*)*"
        + "(?:chat\\s*games?\\s*[!:>-]?\\s*)?(?:the\\s+(?=first\\s+to\\s+))?";
    private static final Pattern GAME_PROMPT = Pattern.compile("(?i)" + GAME_PREFIX
        + "(?:unscramble|scrambled|unjumble|anagram|reverse|backwards?|fill\\s+in|trivia|quiz|question|"
        + "solve|calculate|compute|math|first\\s+to|type|copy|retype|who|what|which|where|when|how|why)\\b");
    private static final Pattern TYPED_HEADER = Pattern.compile("(?i)" + GAME_PREFIX
        + "((?:unscramble|scrambled|unjumble|anagram)(?:\\s+(?:the\\s+)?(?:word|phrase|command))?"
        + "|(?:math|solve|calculate)(?:\\s+(?:it|challenge|problem|expression))?"
        + "|(?:reverse|backwards?)(?:\\s+(?:it|(?:the\\s+)?(?:word|phrase)))?"
        + "|fill\\s+in(?:\\s+(?:the\\s+)?(?:missing\\s+)?(?:letters|word))?"
        + "|(?:first\\s+to\\s+)?(?:type|copy|retype)(?:\\s+(?:it|(?:the\\s+)?(?:word|phrase)))?"
        + "|(?:trivia|quiz|question)(?:\\s+question)?|who\\s+am\\s+i\\?)[:!.\\s]*$");

    public static boolean isGamePrompt(String text) {
        return text != null && (GAME_PROMPT.matcher(text.trim()).find()
            || TIMED_HEADER.matcher(text.trim()).matches());
    }

    private static final Pattern QUESTION_PREFIX = Pattern.compile(
        "(?i)^[^\\p{L}\\p{N}\\[]*(?:\\[(?:chat\\s*games?|trivia|quiz|question)\\]\\s*[:!>\\-]*\\s*"
            + "|(?:chat\\s*games?|trivia|quiz|question)\\s*[:!>\\-]\\s*)");
    private static final Pattern QUESTION = Pattern.compile("(?i)^(?:what|which|who|where|when|how|why|name|identify)\\b");

    public static String questionText(String text) {
        if (text == null) return "";
        String question = text.trim();
        for (int prefix = 0; prefix < 4; prefix++) {
            Matcher match = QUESTION_PREFIX.matcher(question);
            if (!match.find()) break;
            question = question.substring(match.end()).trim();
        }
        return question;
    }

    public static boolean isQuestion(String text) {
        return QUESTION.matcher(questionText(text)).find();
    }

    private static String headerInstruction(String line) {
        if (isGenericHeader(line)) return "";
        Matcher match = TYPED_HEADER.matcher(line);
        if (!match.find()) {
            match = TIMED_HEADER.matcher(line);
            if (!match.matches()) return null;
        }
        String cue = match.group(1).toLowerCase(java.util.Locale.ROOT);
        int space = cue.indexOf(' ');
        String kind = space < 0 ? cue : cue.substring(0, space);
        return switch (kind) {
            case "unscramble", "scrambled", "unjumble", "anagram" -> "Unscramble: ";
            case "math", "solve", "calculate" -> "Solve: ";
            case "reverse", "backward", "backwards" -> "Reverse: ";
            case "fill" -> "Fill in: ";
            case "first", "type", "copy", "retype" -> "Type: ";
            default -> "Trivia: ";
        };
    }

    public static final class RevealWindow {
        public record Closed(String question, boolean canLearn, long at) {}
        public record Revealed(Closed round, String answer) {}
        private Closed pending;
        private boolean quotedAnswer;

        public void remember(String question, boolean canLearn, long now) {
            remember(question, canLearn, now, false);
        }

        public void remember(String question, boolean canLearn, long now, boolean quotedAnswer) {
            pending = question == null || question.isBlank() ? null : new Closed(question, canLearn, now);
            this.quotedAnswer = quotedAnswer;
        }

        public Revealed takeQuoted(String line, long now) {
            if (!quotedAnswer || pending == null) return null;
            Matcher match = QUOTED_REVEAL.matcher(line.trim());
            if (!match.matches()) {
                clear();
                return null;
            }
            Closed closed = take(now);
            return closed == null ? null : new Revealed(closed, match.group(1).trim());
        }

        public Closed take(long now) {
            Closed closed = pending;
            clear();
            return closed != null && now >= closed.at() && now - closed.at() <= 10_000L ? closed : null;
        }

        public void clear() { pending = null; quotedAnswer = false; }
    }

    public static final class Round {
        private long headerAt;
        private boolean waiting;
        private boolean genericPayload;
        private boolean pipePayload;
        private String payload;
        private int revision;
        private String instruction = "";
        private String fragment = "";

        public String accept(String message, long now) {
            genericPayload = false;
            if (message == null || message.isBlank()) return null;
            if ((waiting || payload != null) && (now < headerAt || now - headerAt > 120_000L)) clear();
            StringBuilder content = new StringBuilder();
            for (String raw : message.split("\\R")) {
                String line = raw.trim();
                String header = headerInstruction(line);
                if (header != null) {
                    clear();
                    headerAt = now;
                    waiting = true;
                    instruction = header;
                    pipePayload = line.startsWith("|");
                } else {
                    if (!waiting) {
                        Matcher prize = PRIZE_PROMPT.matcher(line);
                        if (prize.matches()) {
                            line = prize.group(1).equalsIgnoreCase("unscramble")
                                ? "Unscramble: " + prize.group(2) : prize.group(2);
                        }
                    }
                    line = questionText(line);
                    if (waiting && pipePayload && line.startsWith("| ")) line = line.substring(2).trim();
                    if (waiting && (instruction.equals("Unscramble: ") || instruction.equals("Fill in: "))) {
                        line = WORD_PAYLOAD_PREFIX.matcher(line).replaceFirst("");
                    }
                    if (!line.isBlank() && !COUNTDOWN.matcher(line).matches()
                        && line.codePoints().anyMatch(Character::isLetterOrDigit)) {
                        if (!content.isEmpty()) content.append('\n');
                        content.append(line);
                    }
                }
            }
            if (content.isEmpty()) return null;
            String question = content.toString();
            if (!fragment.isEmpty()) question = fragment + " " + question;
            if (waiting && FIRST_PROMPT.matcher(question).find()) {
                question = question.replaceAll("\\s*\\R\\s*", " ");
                if (question.length() > 512) { clear(); return null; }
                if (!FIRST_END.matcher(question).find()
                    || FIRST_FILL.matcher(question).find() && !missingLettersOnly(question)) {
                    fragment = question;
                    return null;
                }
                fragment = "";
            }
            if (question.equals(payload)) return null;
            if (waiting) {
                waiting = false;
                if (now - headerAt > 5_000L) return null;
                genericPayload = instruction.isEmpty();
                payload = question;
                return instruction + question;
            }
            return question;
        }

        public boolean genericPayload() { return genericPayload; }
        public boolean awaitingPayload(long now) {
            return waiting && now >= headerAt && now - headerAt <= 5_000L;
        }
        public int revision() { return revision; }

        public void clear() {
            waiting = false;
            genericPayload = false;
            pipePayload = false;
            payload = null;
            instruction = "";
            fragment = "";
            revision++;
        }
    }
    public record Reply(String text, boolean command) {}

    private static final Pattern WINNER = Pattern.compile(
        "(?i)(?:^|\\s)([A-Za-z0-9_]{3,16})\\s+(?:got it in\\s+\\d|won(?:\\s+the)?\\s+(?:chat\\s*)?game|answered (?:it )?correctly)");
    private static final Pattern TYPED_WINNER = Pattern.compile(
        "(?i)(?<![A-Za-z0-9_])([A-Za-z0-9_]{3,16})\\s+typed\\s+[\"'\u201C\u2018`]([^\"'\u201D\u2019`\\r\\n]{1,64})[\"'\u201D\u2019`]"
            + "\\s+in\\s+\\d+(?:\\.\\d+)?\\s*(?:seconds?|secs?|s)\\b");

    public static String winner(String message) {
        if (message == null || isGamePrompt(message)) return null;
        Matcher typed = TYPED_WINNER.matcher(message);
        if (typed.find()) return typed.group(1);
        Matcher match = WINNER.matcher(message);
        return match.find() ? match.group(1) : null;
    }

    public static String answerText(String answer) {
        return answer == null ? "" : answer.trim().replaceFirst("^/+", "").trim();
    }

    public static long elapsedMicros(long startNanos, long endNanos) {
        long elapsed = endNanos - startNanos;
        return elapsed < 0 ? -1 : elapsed / 1000L;
    }

    public static boolean sameAnswer(String submitted, String revealed) {
        return submitted != null && revealed != null
            && submitted.trim().replaceAll("\\s+", " ").equalsIgnoreCase(revealed.trim().replaceAll("\\s+", " "));
    }

    private static final Pattern REPLY_COMMAND = Pattern.compile(
        "(?i)\\b(?:use|using|with|via|run|enter|send|type|reply|answer)\\s+(?:the\\s+)?(?:command\\s+)?"
            + "(/(?:answer|quiz|math|solve|response|reply|guess|cg|chatgame))\\b");

    public static String replyCommand(String prompt) {
        Matcher match = REPLY_COMMAND.matcher(prompt);
        return match.find() ? match.group(1).toLowerCase(java.util.Locale.ROOT) : null;
    }

    public static Reply reply(String prefix, String answer) {
        String before = prefix == null ? "" : prefix.trim();
        String text = answerText(answer);
        return new Reply((before.isEmpty() ? text : before + " " + text).trim(), before.startsWith("/"));
    }

    public static long replyDelay(boolean humanized, String question, String output,
        int charsPerSecond, int maxSeconds, long jitter) {
        if (!humanized) return 0;
        long delay = 400 + question.split("\\s+").length * 120L
            + output.length() * 1000L / Math.max(3, charsPerSecond) + Math.max(0, Math.min(499, jitter));
        return Math.min(delay, Math.max(0, maxSeconds) * 1000L);
    }
    // "<Steve> hi", "[VIP] Steve: hi", "Steve » hi"
    private static final Pattern SPEAKER = Pattern.compile(
        "^\\s*(?:<([A-Za-z0-9_]{3,16})>|(?:\\[[^\\]]{0,24}\\]\\s*)*([A-Za-z0-9_]{3,16})\\s*(?::|\u00BB|>>|->|\\|))");
    private static final Pattern NAME_SEPARATORS = Pattern.compile("[^A-Za-z0-9_]");

    /** The answer the server announced ("The word was diamond"), or null. */
    public static String revealedAnswer(String line) {
        if (line == null || isGamePrompt(line)) return null;
        Matcher typed = TYPED_WINNER.matcher(line);
        if (typed.find()) return typed.group(2).trim();
        Matcher m = REVEAL.matcher(line);
        if (!m.find()) return null;
        String a = m.group(1).split("[.!]\\s", 2)[0].trim().replaceAll("[.!]+$", "");
        return a.isEmpty() ? null : a;
    }

    /** Someone won, or nobody answered in time. Present-tense "wins" in a prompt doesn't count. */
    public static boolean isRoundOver(String line) {
        return line != null && !isGamePrompt(line)
            && (ROUND_OVER.matcher(line).find() || TYPED_WINNER.matcher(line).find());
    }

    /** True when the line is an online player talking, so a player typing "unscramble: x" can't bait a reply. */
    public static boolean isPlayerChat(String line, Collection<String> onlineNames) {
        if (line == null || onlineNames == null || onlineNames.isEmpty()) return false;
        Matcher m = SPEAKER.matcher(line);
        if (!m.find()) {
            int separator = line.indexOf('\u00BB');
            if (separator < 0 || separator > 160) return false;
            String prefix = " " + NAME_SEPARATORS.matcher(line.substring(0, separator))
                .replaceAll(" ").toLowerCase(java.util.Locale.ROOT) + " ";
            for (String name : onlineNames) {
                if (name != null && prefix.contains(" " + name.toLowerCase(java.util.Locale.ROOT) + " ")) return true;
            }
            return false;
        }
        String speaker = m.group(1) != null ? m.group(1) : m.group(2);
        for (String n : onlineNames) if (n != null && n.equalsIgnoreCase(speaker)) return true;
        return false;
    }
}
