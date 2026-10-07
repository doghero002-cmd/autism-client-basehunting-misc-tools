package com.autism.seedcracker.chatbot;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides what the chat bot is allowed to do (pure, unit tested). Every reply passes through here:
 * who asked (trust tier), what they asked for (only built-in actions, never free-form commands),
 * rate limits, and an outgoing filter that blocks anything that would leak or run something.
 */
public final class ChatBotPolicy {

    /** What a message is allowed to trigger. Anything not listed here cannot happen. */
    public enum Action { REPLY, COORDS, HEALTH, PING, TIME, GOTO_ME, STOP }

    public enum Tier { STRANGER, TRUSTED, OWNER }

    /** Sensitive actions need a trusted sender; reveal-anything actions need explicit opt-in too. */
    public static Tier required(Action a) {
        return switch (a) {
            case REPLY, PING, TIME -> Tier.STRANGER;
            case HEALTH -> Tier.TRUSTED;
            case COORDS, GOTO_ME, STOP -> Tier.OWNER;
        };
    }

    public record Rule(String keyword, String reply, Action action) {}

    public record Decision(Action action, String reply, String why) {
        static Decision deny(String why) {
            return new Decision(null, null, why);
        }

        public boolean allowed() {
            return action != null;
        }
    }

    // Commands that could hurt the player if a crafted reply ever got sent: pay, drop items, run code, etc.
    private static final Pattern DANGEROUS_OUT = Pattern.compile(
        "(?i)^\\s*[/!.#]|\\b(pay|give|trade|sell|tpa(ccept|here|deny)?|warp|sethome|msg|tell|whisper|kill|suicide|op|deop|"
            + "pass(word)?|login|register|token|api[_ -]?key|seed)\\b|discord\\.gg|https?://|www\\.");
    private static final Pattern COLOR_OR_CONTROL = Pattern.compile("\\u00A7.?|\\p{Cntrl}");

    private final Set<String> owners;
    private final Set<String> trusted;
    private final Set<Action> enabledActions;
    private final List<Rule> rules;
    private final long globalCooldownMs;
    private final long perSenderCooldownMs;
    private final int maxPerMinute;
    private final Map<String, Long> lastBySender = new HashMap<>();
    private final Deque<Long> recent = new ArrayDeque<>();
    private long lastAny = Long.MIN_VALUE / 2;

    public ChatBotPolicy(Set<String> owners, Set<String> trusted, Set<Action> enabledActions, List<Rule> rules,
                         long globalCooldownMs, long perSenderCooldownMs, int maxPerMinute) {
        this.owners = lower(owners);
        this.trusted = lower(trusted);
        this.enabledActions = Set.copyOf(enabledActions);
        this.rules = List.copyOf(rules);
        this.globalCooldownMs = globalCooldownMs;
        this.perSenderCooldownMs = perSenderCooldownMs;
        this.maxPerMinute = Math.max(1, maxPerMinute);
    }

    private static Set<String> lower(Set<String> names) {
        java.util.HashSet<String> out = new java.util.HashSet<>();
        for (String n : names) if (n != null && !n.isBlank()) out.add(n.trim().toLowerCase(Locale.ROOT));
        return out;
    }

    public Tier tier(String sender, boolean verified) {
        String s = sender == null ? "" : sender.toLowerCase(Locale.ROOT);
        // Names parsed out of plain system text can be faked by anyone typing "<Owner> ..."; only signed/verified senders get trust.
        if (!verified) return Tier.STRANGER;
        if (owners.contains(s)) return Tier.OWNER;
        if (trusted.contains(s)) return Tier.TRUSTED;
        return Tier.STRANGER;
    }

    /**
     * {@code message} = the chat text without the sender prefix. {@code self} = our own name (we
     * never answer ourselves). {@code verified} = the sender came from a signed player-chat packet.
     */
    public Decision decide(String sender, boolean verified, String self, String message, long nowMs) {
        if (message == null || message.isBlank()) return Decision.deny("empty");
        if (sender != null && self != null && sender.equalsIgnoreCase(self)) return Decision.deny("self");
        String lower = message.toLowerCase(Locale.ROOT);
        Rule hit = null;
        for (Rule r : rules) {
            String kw = r.keyword() == null ? "" : r.keyword().trim().toLowerCase(Locale.ROOT);
            if (!kw.isEmpty() && containsWord(lower, kw)) {
                hit = r;
                break;
            }
        }
        if (hit == null) return Decision.deny("no keyword");
        Action a = hit.action() == null ? Action.REPLY : hit.action();
        if (!enabledActions.contains(a)) return Decision.deny("action off: " + a);
        Tier t = tier(sender, verified);
        if (t.ordinal() < required(a).ordinal()) return Decision.deny("not allowed for " + t);
        if (!rateOk(sender, nowMs, t == Tier.OWNER)) return Decision.deny("rate limited");
        return new Decision(a, hit.reply(), "ok");
    }

    /** Commit a sent reply against the rate limits. */
    public void record(String sender, long nowMs) {
        lastAny = nowMs;
        if (sender != null) lastBySender.put(sender.toLowerCase(Locale.ROOT), nowMs);
        recent.addLast(nowMs);
    }

    private boolean rateOk(String sender, long now, boolean owner) {
        while (!recent.isEmpty() && now - recent.peekFirst() > 60_000) recent.removeFirst();
        if (recent.size() >= maxPerMinute) return false;
        if (owner) return true;
        if (now - lastAny < globalCooldownMs) return false;
        Long last = sender == null ? null : lastBySender.get(sender.toLowerCase(Locale.ROOT));
        return last == null || now - last >= perSenderCooldownMs;
    }

    /** Keyword must stand alone ("hi" matches "hi there", not "this"). */
    static boolean containsWord(String text, String kw) {
        int i = text.indexOf(kw);
        while (i >= 0) {
            boolean before = i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1));
            int end = i + kw.length();
            boolean after = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (before && after) return true;
            i = text.indexOf(kw, i + 1);
        }
        return false;
    }

    /**
     * Last gate before anything is typed into chat: strips colour/control codes, refuses anything
     * that starts a command or mentions payment/teleport/credentials/links, and caps the length.
     * Returns null when the reply must not be sent.
     */
    public static String sanitizeOutgoing(String reply) {
        if (reply == null) return null;
        String s = COLOR_OR_CONTROL.matcher(reply).replaceAll("").trim();
        if (s.isEmpty()) return null;
        if (DANGEROUS_OUT.matcher(s).find()) return null;
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    /** Placeholders that reveal where you are or how you're doing: only filled when the sender may use that action. */
    private static final Map<String, Action> SENSITIVE = Map.of(
        "x", Action.COORDS, "y", Action.COORDS, "z", Action.COORDS, "dim", Action.COORDS, "health", Action.HEALTH);

    /**
     * Values this sender may see. A plain REPLY rule written as "hi => I'm at {x} {z}" must not leak
     * coords to strangers just because the rule itself isn't tagged as a coords action.
     */
    public Map<String, String> visible(Map<String, String> all, String sender, boolean verified) {
        Tier t = tier(sender, verified);
        Map<String, String> out = new HashMap<>();
        for (var e : all.entrySet()) {
            Action needs = SENSITIVE.get(e.getKey());
            boolean ok = needs == null || enabledActions.contains(needs) && t.ordinal() >= required(needs).ordinal();
            out.put(e.getKey(), ok ? e.getValue() : "?");
        }
        return out;
    }

    /** {name} {x} {y} {z} {dim} {health} {ping} {time} {sender} placeholders; unknown ones are left as-is. */
    public static String fill(String template, Map<String, String> values) {
        if (template == null) return "";
        String out = template;
        for (var e : values.entrySet()) {
            // Values are never re-scanned, so a player name like "{x}" can't pull in coords.
            out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue().replace("{", "(").replace("}", ")"));
        }
        return out;
    }
}
