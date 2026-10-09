package com.autism.seedcracker.modules;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.chatgames.AiRouter;
import com.autism.seedcracker.chatgames.ChatGameSolver;
import com.autism.seedcracker.chatgames.ChatLine;
import com.autism.seedcracker.util.DebugProbe;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

/**
 * Auto Chat Games: answers server chat games.
 *
 * LOCAL (no internet) solves math, word problems, UNSCRAMBLE, retype ("first to type X"),
 * REVERSE and FILL-IN games. Words come from every item/block/mob name in the game plus your own
 * chatgames-words.txt, and trivia answers are learned from the server's "the answer was X" reveals,
 * so it gets better the longer it runs. AI mode asks an OpenAI-compatible API only when the local
 * solver can't answer.
 */
public final class ChatGamesModule extends Module {

    public enum AnswerMode { LOCAL, AI }

    private final StringSetting keyword = add(new StringSetting("keyword", "Trigger keyword", "")
        .description("Only react to lines containing this (e.g. your server's '[ChatGames]' tag). Blank = any game-looking line.")
        .group("General"));
    private final EnumSetting<AnswerMode> mode = add(new EnumSetting<>("mode", "Answer mode", AnswerMode.LOCAL, AnswerMode.values())
        .description("LOCAL = offline solver (math, unscramble, type, reverse, fill-in, learned trivia). "
            + "AI = same, but unknown questions go to your AI endpoint.")
        .group("General"));
    private final BoolSetting games = add(new BoolSetting("word-games", "Word games", true)
        .description("Unscramble / reverse / fill-in / retype. Off = math only.").group("General"));
    private final BoolSetting wordProblems = add(new BoolSetting("word-problems", "Word problems", true)
        .description("Read 'what is five plus three' style math. Can misread odd phrasing.").group("General"));
    private final BoolSetting learnTrivia = add(new BoolSetting("learn", "Learn from reveals", true)
        .description("Remember answers the server announces ('the word was diamond') and answer that question instantly next time.")
        .group("General"));
    private final BoolSetting autoReply = add(new BoolSetting("auto-reply", "Auto reply in chat", true)
        .description("Send the answer. Off = only show it to you (safe practice mode).").group("Reply"));
    private final StringSetting replyPrefix = add(new StringSetting("reply-prefix", "Reply prefix", "")
        .description("Text before the answer (e.g. '/answer '). Blank = plain chat or the command the prompt mentions.")
        .group("Reply"));
    private final BoolSetting showAdvanced = add(new BoolSetting("advanced", "Show advanced", false)
        .description("Reveal command detection, typing speed, delays and skip-chance humanizing.").group("Reply"));
    private final BoolSetting autoDetectCommand = add(new BoolSetting("auto-detect-command", "Use prompt's command", true)
        .description("If the prompt says to use /answer, /quiz etc., reply with that command.").group("Reply").visibleWhen(showAdvanced::get));
    private final IntSetting typeSpeed = add(new IntSetting("type-speed", "Typing speed (chars/s)", 9, 3, 30, 1)
        .description("Simulated read + type time. Instant answers are the #1 chat-game bot tell.").group("Reply").visibleWhen(showAdvanced::get));
    private final IntSetting maxDelay = add(new IntSetting("max-delay", "Max delay (s)", 6, 1, 20, 1)
        .description("Never wait longer than this, or someone else wins.").group("Reply").visibleWhen(showAdvanced::get));
    private final IntSetting skipChance = add(new IntSetting("skip-chance", "Skip chance (%)", 0, 0, 80, 5)
        .description("Randomly sit out some rounds so you don't win every single one.").group("Reply").visibleWhen(showAdvanced::get));
    private final IntSetting cooldownMs = add(new IntSetting("cooldown-ms", "Cooldown (ms)", 3000, 0, 60000, 250)
        .description("Minimum time between answers.").group("Reply").visibleWhen(showAdvanced::get));
    private final StringSetting aiEndpoint = add(new StringSetting("ai-endpoint", "AI endpoint", "https://api.openai.com/v1/chat/completions")
        .description("OpenAI-compatible chat-completions URL (OpenAI, OpenRouter, Ollama...).")
        .group("AI").visibleWhen(() -> mode.get() == AnswerMode.AI));
    private final StringSetting aiModel = add(new StringSetting("ai-model", "AI model", "gpt-4o-mini")
        .group("AI").visibleWhen(() -> mode.get() == AnswerMode.AI));
    private final StringSetting aiKey = add(new StringSetting("ai-key", "AI API key", "")
        .description("Blank for local endpoints like Ollama.").group("AI").visibleWhen(() -> mode.get() == AnswerMode.AI));
    private final BoolSetting debug = add(new BoolSetting("debug", "Debug tracing", false).group("General").visibleWhen(showAdvanced::get));

    private ChatGameSolver solver;
    private long lastAnswerMs;
    private String lastPrompt = "";
    private String detectedCommand = "";
    private String pendingQuestion;
    private long pendingQuestionAt;
    private int roundToken;
    private volatile boolean aiInFlight;

    public ChatGamesModule() {
        super(SeedcrackerAddon.ID + ":chat-games", "Auto Chat Games",
            "Answers chat games offline: math, unscramble, type, reverse, fill-in, learned trivia (AI optional).");
    }

    @Override
    public void onEnable() {
        solver = buildSolver();
        AutismClientMessaging.sendPrefixed("§a[ChatGames] §7Ready: " + solver.dictionarySize() + " words, "
            + solver.triviaSnapshot().size() + " learned answers.");
    }

    @Override
    public void onDisable() {
        saveTrivia();
        roundToken++;
    }

    @Override
    public boolean onPacketReceive(Packet<?> packet) {
        if (!(packet instanceof ClientboundSystemChatPacket chat) || chat.overlay() || chat.content() == null) return false;
        Component content = chat.content();
        String line = content.getString();
        Minecraft.getInstance().execute(() -> handle(line, hoverText(content)));
        return false;
    }

    private void handle(String line, String hover) {
        if (solver == null || line == null || line.isBlank()) return;
        DebugProbe.setEnabled(id(), debug.get());
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Round-end lines first: learn the answer, and cancel any reply we haven't sent yet.
        String revealed = ChatLine.revealedAnswer(line);
        if (revealed != null || ChatLine.isRoundOver(line)) {
            if (revealed != null && learnTrivia.get() && pendingQuestion != null
                && System.currentTimeMillis() - pendingQuestionAt < 5 * 60_000L) {
                solver.learn(pendingQuestion, revealed);
                solver.addWord(revealed);
                DebugProbe.trace(id(), "learned", pendingQuestion + " -> " + revealed);
                saveTrivia();
            }
            pendingQuestion = null;
            roundToken++;
            return;
        }

        if (ChatLine.isPlayerChat(line, onlineNames(mc))) return;
        String kw = keyword.get().trim().toLowerCase(Locale.ROOT);
        String lower = line.toLowerCase(Locale.ROOT);
        if (!kw.isEmpty() && !lower.contains(kw)) return;

        if (autoDetectCommand.get()) {
            String cmd = detectCommand(line);
            if (cmd != null) detectedCommand = cmd;
        }

        // Some servers hide the actual word in the hover text of the prompt.
        String prompt = hover != null && !hover.isBlank() && !lower.contains(hover.toLowerCase(Locale.ROOT))
            ? line + " : " + hover : line;
        ChatGameSolver.Answer answer = solver.solve(prompt, wordProblems.get());
        if (answer != null && !games.get() && answer.kind() != ChatGameSolver.Kind.MATH
            && answer.kind() != ChatGameSolver.Kind.TRIVIA) {
            answer = null;
        }
        if (answer == null && !looksLikeGame(lower)) return;

        pendingQuestion = line;
        pendingQuestionAt = System.currentTimeMillis();
        long now = System.currentTimeMillis();
        if (line.equals(lastPrompt) && now - lastAnswerMs < 15_000) return;
        if (now - lastAnswerMs < cooldownMs.get()) return;

        if (answer != null) {
            DebugProbe.trace(id(), "solved", answer.kind() + ": " + answer.text());
            lastPrompt = line;
            lastAnswerMs = now;
            deliver(line, answer.text(), answer.kind().name().toLowerCase(Locale.ROOT));
        } else if (mode.get() == AnswerMode.AI) {
            lastPrompt = line;
            lastAnswerMs = now;
            askAi(line);
        } else {
            DebugProbe.trace(id(), "unsolved", line);
        }
    }

    private void askAi(String question) {
        if (aiInFlight) return;
        aiInFlight = true;
        int token = roundToken;
        AiRouter.ask(aiEndpoint.get(), aiKey.get(), aiModel.get(), question).whenComplete((raw, err) -> {
            aiInFlight = false;
            String clean = err == null ? AiRouter.sanitize(raw) : null;
            if (clean == null) return;
            Minecraft.getInstance().execute(() -> {
                if (token == roundToken) deliver(question, clean, "ai");
            });
        });
    }

    private void deliver(String question, String answer, String how) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!autoReply.get()) {
            AutismClientMessaging.sendPrefixed("§a[ChatGames] §7(" + how + ") §f" + answer);
            return;
        }
        if (ThreadLocalRandom.current().nextInt(100) < skipChance.get()) {
            DebugProbe.trace(id(), "skipped", question);
            return;
        }
        String prefix = replyPrefix.get().trim();
        if (prefix.isEmpty() && autoDetectCommand.get()) prefix = detectedCommand;
        String out = (prefix.isEmpty() ? answer : prefix + " " + answer).trim();
        // Reading the prompt (~250ms/word) + typing the answer at the configured speed.
        int words = question.split("\\s+").length;
        long delay = 400 + words * 120L + out.length() * 1000L / Math.max(3, typeSpeed.get())
            + ThreadLocalRandom.current().nextLong(500);
        delay = Math.min(delay, maxDelay.get() * 1000L);
        int token = roundToken;
        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(() -> mc.execute(() -> {
            // Someone else already won (or the round ended) while we were "typing".
            if (!isEnabled() || token != roundToken || mc.getConnection() == null) return;
            if (out.startsWith("/")) mc.getConnection().sendCommand(out.substring(1));
            else mc.getConnection().sendChat(out);
        }));
    }

    private static boolean looksLikeGame(String lower) {
        return lower.contains("unscramble") || lower.contains("first to") || lower.contains("trivia")
            || lower.contains("question") || lower.contains("guess") || lower.contains("quiz") || lower.contains("chat game");
    }

    private static String detectCommand(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("(/(?:answer|quiz|math|solve|response|reply|guess|cg|chatgame))\\b", java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(text);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static List<String> onlineNames(Minecraft mc) {
        List<String> out = new ArrayList<>();
        if (mc.getConnection() == null) return out;
        for (PlayerInfo pi : mc.getConnection().getOnlinePlayers()) {
            if (pi.getProfile() != null && pi.getProfile().name() != null) out.add(pi.getProfile().name());
        }
        return out;
    }

    /** Text from any hover tooltip in the message (some servers hide the word there). */
    private static String hoverText(Component c) {
        StringBuilder sb = new StringBuilder();
        collectHover(c, sb, 0);
        return sb.toString().trim();
    }

    private static void collectHover(Component c, StringBuilder sb, int depth) {
        if (c == null || depth > 6) return;
        HoverEvent h = c.getStyle().getHoverEvent();
        if (h instanceof HoverEvent.ShowText st && st.value() != null) sb.append(' ').append(st.value().getString());
        for (Component s : c.getSiblings()) collectHover(s, sb, depth + 1);
    }

    // ---- word list + trivia persistence ----

    private Path dir() {
        return autismclient.AutismClientAddon.FOLDER.toPath();
    }

    private ChatGameSolver buildSolver() {
        Set<String> words = new LinkedHashSet<>();
        for (var item : BuiltInRegistries.ITEM) words.add(new net.minecraft.world.item.ItemStack(item).getHoverName().getString());
        for (var block : BuiltInRegistries.BLOCK) words.add(block.getName().getString());
        for (var type : BuiltInRegistries.ENTITY_TYPE) words.add(type.getDescription().getString());
        for (var key : BuiltInRegistries.ITEM.keySet()) words.add(key.getPath().replace('_', ' '));
        Path custom = dir().resolve("chatgames-words.txt");
        try {
            if (!Files.exists(custom)) {
                Files.createDirectories(custom.getParent());
                Files.writeString(custom, "# One word or phrase per line. Used for unscramble / fill-in games.\n", StandardCharsets.UTF_8);
            }
            for (String l : Files.readAllLines(custom, StandardCharsets.UTF_8)) if (!l.startsWith("#")) words.add(l);
        } catch (Exception ignored) {}
        ChatGameSolver s = new ChatGameSolver(words);
        try {
            Path f = dir().resolve("chatgames-learned.json");
            if (Files.exists(f)) {
                Map<String, String> learned = new Gson().fromJson(Files.readString(f, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, String>>() {}.getType());
                if (learned != null) learned.forEach((q, a) -> { s.learn(q, a); s.addWord(a); });
            }
        } catch (Exception ignored) {}
        return s;
    }

    private void saveTrivia() {
        if (solver == null) return;
        try {
            Path f = dir().resolve("chatgames-learned.json");
            Files.createDirectories(f.getParent());
            Files.writeString(f, new Gson().toJson(solver.triviaSnapshot()), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    @Override
    public String info() {
        return solver == null ? "" : solver.triviaSnapshot().size() + " learned";
    }
}
