package com.autism.seedcracker.chatgames;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Classifies chat lines around a chat game (pure, unit tested): answer reveals, round ends, player chatter. */
public final class ChatLine {
    private ChatLine() {}

    private static final Pattern REVEAL = Pattern.compile(
        "(?i)\\b(?:correct )?(?:answer|word|solution)\\s+(?:was|is)\\s*[:\\-]?\\s*[\"'`]?([^\"'`.!,]{1,48})[\"'`]?");
    private static final Pattern ROUND_OVER = Pattern.compile(
        "(?i)\\b(?:won|answered (?:it )?correctly|guessed (?:it|correctly|the word)|got it right|was (?:the )?first|nobody|no one|time'?s up)\\b");
    // "<Steve> hi", "[VIP] Steve: hi", "Steve » hi"
    private static final Pattern SPEAKER = Pattern.compile(
        "^\\s*(?:<([A-Za-z0-9_]{3,16})>|(?:\\[[^\\]]{0,24}\\]\\s*)*([A-Za-z0-9_]{3,16})\\s*(?::|\u00BB|>>|->|\\|))");

    /** The answer the server announced ("The word was diamond"), or null. */
    public static String revealedAnswer(String line) {
        if (line == null) return null;
        Matcher m = REVEAL.matcher(line);
        if (!m.find()) return null;
        String a = m.group(1).trim();
        return a.isEmpty() ? null : a;
    }

    /** Someone won, or nobody answered in time. Present-tense "wins" in a prompt doesn't count. */
    public static boolean isRoundOver(String line) {
        return line != null && ROUND_OVER.matcher(line).find();
    }

    /** True when the line is an online player talking, so a player typing "unscramble: x" can't bait a reply. */
    public static boolean isPlayerChat(String line, Collection<String> onlineNames) {
        if (line == null || onlineNames == null || onlineNames.isEmpty()) return false;
        Matcher m = SPEAKER.matcher(line);
        if (!m.find()) return false;
        String speaker = m.group(1) != null ? m.group(1) : m.group(2);
        for (String n : onlineNames) if (n != null && n.equalsIgnoreCase(speaker)) return true;
        return false;
    }
}
