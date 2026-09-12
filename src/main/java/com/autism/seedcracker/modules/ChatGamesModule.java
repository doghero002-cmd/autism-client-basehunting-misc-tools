package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.chatgames.AiRouter;
import com.autism.seedcracker.chatgames.MathSolver;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

import java.util.Locale;

/**
 * Auto Chat Games.
 *
 * Watches chat for a configurable keyword/message (e.g. the server's chat-game prompt). When a
 * message contains the keyword AND a math expression, it solves the math and replies in chat.
 *
 * Two answer modes:
 *  - LOCAL: solves the math itself with a built-in arithmetic evaluator (no internet needed).
 *  - AI: routes the question to an OpenAI-compatible chat API you configure (endpoint + model +
 *    optional key) and replies with the model's answer. Falls back to LOCAL if the AI fails.
 *
 * Replies are sent as normal chat (or a configurable command prefix). Rate-limited and deduped so
 * it can't spam. Only solves when a keyword matches (or any message if keyword is blank).
 */
public final class ChatGamesModule extends Module {

    public enum AnswerMode { LOCAL, RISKY, AI }

    private final StringSetting keyword = add(new StringSetting(
            "keyword", "Trigger keyword", "solve")
        .description("Only react to chat messages containing this text (blank = react to math in any message).")
        .group("General"));
    private final EnumSetting<AnswerMode> mode = add(new EnumSetting<>(
            "mode", "Answer mode", AnswerMode.LOCAL, AnswerMode.values())
        .description("LOCAL = solve raw math in-client. RISKY = also solve word problems + auto answer command (can misread). AI = route to an AI API.")
        .group("General"));
    private final BoolSetting autoReply = add(new BoolSetting(
            "auto-reply", "Auto reply in chat", true)
        .description("Send the answer in chat. Off = just show it to you locally.")
        .group("General"));
    private final StringSetting replyPrefix = add(new StringSetting(
            "reply-prefix", "Reply prefix", "")
        .description("Optional prefix before the answer (e.g. a command like '/answer '). Blank = plain chat. RISKY can auto-detect this from the prompt.")
        .group("General"));
    private final BoolSetting autoDetectCommand = add(new BoolSetting(
            "auto-detect-command", "Auto-detect answer command", true)
        .description("RISKY: read the prompt for a '/answer'/'/quiz' style command and reply using it.")
        .group("General"));
    private final IntSetting cooldownMs = add(new IntSetting(
            "cooldown-ms", "Cooldown (ms)", 1500, 0, 30000, 100)
        .description("Minimum time between answers (anti-spam).")
        .group("General"));

    // AI config.
    private final StringSetting aiEndpoint = add(new StringSetting(
            "ai-endpoint", "AI endpoint", "https://api.openai.com/v1/chat/completions")
        .description("OpenAI-compatible chat-completions URL (OpenAI, OpenRouter, Ollama, etc).")
        .group("AI"));
    private final StringSetting aiModel = add(new StringSetting(
            "ai-model", "AI model", "gpt-4o-mini")
        .description("Model name to request.")
        .group("AI"));
    private final StringSetting aiKey = add(new StringSetting(
            "ai-key", "AI API key", "")
        .description("API key (leave blank for local endpoints like Ollama).")
        .group("AI"));

    private long lastAnswerMs = 0;
    private String lastQuestion = "";
    private String lastDetectedCommand = "";
    private volatile boolean aiInFlight = false;

    public ChatGamesModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":chat-games", "Auto Chat Games", category,
            "Answers chat-game math questions (local solver, or routed to an AI API).");
    }

    @Override
    public boolean onPacketReceive(Packet<?> packet) {
        if (!(packet instanceof ClientboundSystemChatPacket chat)) return false;
        Component content = chat.content();
        if (content == null) return false;
        handleMessage(content.getString());
        return false; // never block the message
    }

    private void handleMessage(String raw) {
        if (raw == null) return;
        // RISKY: remember any '/answer'/'/quiz' style command the prompt told us to use.
        if (autoDetectCommand.get()) {
            String cmd = detectCommand(raw);
            if (cmd != null) lastDetectedCommand = cmd;
        }
        String kw = keyword.get().trim().toLowerCase(Locale.ROOT);
        if (!kw.isEmpty() && !raw.toLowerCase(Locale.ROOT).contains(kw)) return;

        String expr = extractMath(raw);
        // RISKY mode also handles word problems when there's no raw expression.
        if (expr == null && mode.get() == AnswerMode.RISKY) {
            Double wordResult = MathSolver.solveWordProblem(raw);
            if (wordResult != null) {
                long nowW = System.currentTimeMillis();
                if (nowW - lastAnswerMs >= cooldownMs.get()) {
                    lastAnswerMs = nowW;
                    lastQuestion = raw;
                    deliver(raw, MathSolver.format(wordResult));
                }
            }
            return;
        }
        if (expr == null) return;

        // Dedupe identical question + cooldown.
        long now = System.currentTimeMillis();
        if (expr.equals(lastQuestion) && now - lastAnswerMs < 10000) return;
        if (now - lastAnswerMs < cooldownMs.get()) return;
        lastQuestion = expr;
        lastAnswerMs = now;

        if (mode.get() == AnswerMode.AI) {
            answerWithAi(raw, expr);
        } else {
            answerLocal(expr);
        }
    }

    private void answerLocal(String expr) {
        Double result = MathSolver.solve(expr);
        if (result == null) return;
        String answer = MathSolver.format(result);
        deliver(expr, answer);
    }

    private void answerWithAi(String fullMessage, String expr) {
        if (aiInFlight) return;
        aiInFlight = true;
        String question = fullMessage.trim();
        AiRouter.ask(aiEndpoint.get(), aiKey.get(), aiModel.get(), question)
            .thenAccept(answer -> {
                aiInFlight = false;
                Minecraft.getInstance().execute(() -> {
                    String clean = sanitizeAnswer(answer);
                    if (clean != null) {
                        deliver(expr, clean);
                    } else {
                        // Fallback to local solver if the AI gave nothing usable.
                        answerLocal(expr);
                    }
                });
            })
            .exceptionally(e -> {
                aiInFlight = false;
                Minecraft.getInstance().execute(() -> answerLocal(expr));
                return null;
            });
    }

    private void deliver(String expr, String answer) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (autoReply.get()) {
            if (mc.getConnection() == null) return;
            String prefix = replyPrefix.get().trim();
            // RISKY: prefer the command the prompt told us to use.
            if (autoDetectCommand.get() && !lastDetectedCommand.isEmpty()) {
                prefix = lastDetectedCommand;
            }
            String out = (prefix + answer).trim();
            if (out.isEmpty()) return;
            if (out.startsWith("/")) mc.getConnection().sendCommand(out.substring(1));
            else mc.getConnection().sendChat(out);
        } else {
            AutismClientMessaging.sendPrefixed("§a[ChatGames] §f" + expr + " = §e" + answer);
        }
    }

    /** Detect a '/answer', '/quiz', '/math', '/solve' etc. command in the prompt. */
    private static String detectCommand(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("/(answer|quiz|math|solve|response|reply|guess)\\b", java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(text);
        if (m.find()) {
            return "/" + m.group(1).toLowerCase(java.util.Locale.ROOT) + " ";
        }
        return null;
    }

    /**
     * Strip AI verbosity down to a clean Minecraft-chat answer. If there's a number anywhere in
     * the response, return just that number (so "The answer is 42." -> "42"). Otherwise return the
     * first short token (for non-numeric game answers). Returns null if nothing usable.
     */
    private static String sanitizeAnswer(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        // Take only the first line (AI often adds a second sentence).
        int nl = s.indexOf('\n');
        if (nl >= 0) s = s.substring(0, nl).trim();
        // Prefer a standalone number (int or decimal, optional sign) anywhere in the text.
        java.util.regex.Matcher num = java.util.regex.Pattern
            .compile("-?\\d+(?:[.,]\\d+)?")
            .matcher(s);
        String best = null;
        while (num.find()) {
            best = num.group(); // last number tends to be the final answer
        }
        if (best != null) {
            return best.replace(",", "");
        }
        // No number: return the first short word/answer (drop filler like "The answer is").
        String cleaned = s.replaceAll("(?i)^(the answer is|answer:|it's|it is|that is|result:?|approximately|about|=)\\s*", "").trim();
        // Keep it short (Minecraft chat games expect a single token).
        String[] parts = cleaned.split("\\s+");
        if (parts.length > 0 && parts[0].length() <= 24 && !parts[0].isEmpty()) {
            return parts[0].replaceAll("[^\\w.-]", "");
        }
        return null;
    }

    /**
     * Extract the math expression from a chat line: the longest run of digits, operators,
     * parentheses, dots and spaces that contains at least one digit and one operator.
     */
    private static String extractMath(String text) {
        StringBuilder best = new StringBuilder();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean ok = Character.isDigit(c) || c == '.' || c == '+' || c == '-' || c == '*'
                || c == '/' || c == '%' || c == '^' || c == '(' || c == ')' || c == ' '
                || c == '×' || c == '÷' || c == 'x' || c == 'X';
            if (ok) {
                cur.append(c);
            } else {
                if (isExpression(cur)) best = new StringBuilder(cur);
                cur.setLength(0);
            }
        }
        if (isExpression(cur)) best = new StringBuilder(cur);
        if (best.length() == 0) return null;
        return best.toString().trim();
    }

    private static boolean isExpression(StringBuilder sb) {
        String s = sb.toString();
        boolean digit = false, op = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) digit = true;
            if (c == '+' || c == '*' || c == '/' || c == '%' || c == '^' || c == '×' || c == '÷') op = true;
        }
        return digit && op;
    }
}
