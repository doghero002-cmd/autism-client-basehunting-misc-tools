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
import com.autism.seedcracker.chatgames.ChatGameJournal;
import com.autism.seedcracker.chatgames.ChatLine;
import com.autism.seedcracker.util.DebugProbe;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringListSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

/**
 * Auto Chat Games: answers server chat games.
 *
 * LOCAL (no internet) solves math, word problems, UNSCRAMBLE, retype ("first to type X"),
 * REVERSE and FILL-IN games. Words come from game registries, the editable client-default
 * "Default + extra words" list and the additive chatgames-words.txt file; trivia answers are
 * learned from the server's "the answer was X" reveals,
 * so it gets better the longer it runs. AI mode asks an OpenAI-compatible API only when the local
 * solver can't answer.
 */
public final class ChatGamesModule extends Module {

    public enum AnswerMode { LOCAL, AI, LOCAL_AI }

    private final StringSetting keyword = add(new StringSetting("keyword", "Trigger keyword", "")
        .description("Only react to lines containing this (e.g. your server's '[ChatGames]' tag). Blank = any game-looking line.")
        .group("General"));
    private final EnumSetting<AnswerMode> mode = add(new EnumSetting<>("mode", "AI fallback", AnswerMode.LOCAL, AnswerMode.values())
        .description("LOCAL = fallback off. AI = cloud/OpenAI-compatible fallback. LOCAL_AI = loopback fallback. "
            + "Always try the offline solver first; only unsolved questions are sent to AI. Configure the selected endpoint/model.")
        .group("General"));
    private final BoolSetting games = add(new BoolSetting("word-games", "Word games", true)
        .description("Unscramble / reverse / fill-in / retype. Checks Minecraft items, blocks, entities, biomes, dimensions, "
            + "enchantments and effects before AI. Off = math only.").group("General"));
    private final StringListSetting extraWords = add(new StringListSetting("extra-words", "Default + extra words",
            com.autism.seedcracker.chatgames.ChatGameVocabulary.defaultExtraWords())
        .description("Hundreds of curated defaults from built-in answers and word packs; add your own here. New entries apply now; removals apply after restart. Registry names and the large English fallback remain automatic.")
        .group("Word games").visibleWhen(games::get));
    private final IntSetting wordDefaultsVersion = add(new IntSetting("word-defaults-version", "Word defaults version", 0, 0, 2, 1)
        .visibleWhen(() -> false));
    private final BoolSetting reactionClicks = add(new BoolSetting("reaction-clicks", "Reaction buttons", false)
        .description("Opt-in: only /chatgames answer UUID buttons. Ambiguous choices and unrelated click actions are ignored.").group("General"));
    private final BoolSetting wordProblems = add(new BoolSetting("word-problems", "Word problems", true)
        .description("Read 'what is five plus three' style math. Can misread odd phrasing.").group("General"));
    private final BoolSetting learnTrivia = add(new BoolSetting("learn", "Learn from reveals", true)
        .description("Remember answers the server announces ('the word was diamond') and answer that question instantly next time.")
        .group("General"));
    private final BoolSetting logGames = add(new BoolSetting("log-games", "Log game evidence", true)
        .description("Bounded local JSONL logs of questions, answers, reaction times and verified outcomes. No API keys or unrelated chat.")
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
    private final BoolSetting humanized = add(new BoolSetting("humanized", "Simulate typing delay", false)
        .description("Off sends solved answers immediately. On uses the configured typing speed and delay.").group("Reply"));
    private final IntSetting typeSpeed = add(new IntSetting("type-speed", "Typing speed (chars/s)", 9, 3, 30, 1)
        .description("Simulated read + type time. Instant answers are the #1 chat-game bot tell.").group("Reply").visibleWhen(showAdvanced::get));
    private final IntSetting maxDelay = add(new IntSetting("max-delay", "Max delay (s)", 6, 1, 20, 1)
        .description("Never wait longer than this, or someone else wins.").group("Reply").visibleWhen(showAdvanced::get));
    private final IntSetting skipChance = add(new IntSetting("skip-chance", "Skip chance (%)", 0, 0, 80, 5)
        .description("Randomly sit out some rounds so you don't win every single one.").group("Reply").visibleWhen(showAdvanced::get));
    private final IntSetting cooldownMs = add(new IntSetting("cooldown-ms", "Cooldown (ms)", 0, 0, 60000, 250)
        .description("Minimum time between answers.").group("Reply").visibleWhen(showAdvanced::get));
    private final StringSetting aiEndpoint = add(new StringSetting("ai-endpoint", "AI endpoint", "https://api.openai.com/v1/chat/completions")
        .description("OpenAI-compatible chat-completions URL (OpenAI, OpenRouter, Ollama...).")
        .group("AI").visibleWhen(() -> mode.get() == AnswerMode.AI));
    private final StringSetting aiModel = add(new StringSetting("ai-model", "AI model", "gpt-4o-mini")
        .group("AI").visibleWhen(() -> mode.get() == AnswerMode.AI));
    private final StringSetting aiKey = add(new StringSetting("ai-key", "AI API key", "")
        .description("Blank for local endpoints like Ollama.").group("AI").visibleWhen(() -> mode.get() == AnswerMode.AI));
    private final IntSetting aiTimeout = add(new IntSetting("ai-timeout", "AI deadline (s)", 5, 1, 20, 1)
        .description("Discard slow responses after this deadline.").group("AI").visibleWhen(() -> mode.get() != AnswerMode.LOCAL));
    private final StringSetting localAiEndpoint = add(new StringSetting("local-ai-endpoint", "Local AI endpoint", "http://127.0.0.1:11434/v1/chat/completions")
        .description("Loopback only. Ollama uses port 11434; LM Studio commonly uses 1234.")
        .group("Local AI").visibleWhen(() -> mode.get() == AnswerMode.LOCAL_AI));
    private final StringSetting localAiModel = add(new StringSetting("local-ai-model", "Installed local model", "")
        .description("Enter an already installed model name. No automatic downloads and no cloud API key forwarding.")
        .group("Local AI").visibleWhen(() -> mode.get() == AnswerMode.LOCAL_AI));
    private final BoolSetting debug = add(new BoolSetting("debug", "Debug tracing", false).group("General").visibleWhen(showAdvanced::get));

    private ChatGameSolver solver;
    private long lastAnswerMs;
    private String lastPrompt = "";
    private String detectedCommand = "";
    private String pendingQuestion;
    private long pendingQuestionAt;
    private int roundToken;
    private CompletableFuture<String> aiRequest;
    private final ChatLine.Round round = new ChatLine.Round();
    private final ChatLine.RevealWindow closedRound = new ChatLine.RevealWindow();
    private boolean pendingCanLearn;
    private net.minecraft.client.multiplayer.ClientPacketListener gameConnection;
    private net.minecraft.client.multiplayer.ClientLevel gameWorld;
    private Object commandTree;
    private String submittedAnswer;
    private int submittedToken = -1;
    private ChatGameJournal journal;
    private boolean journalWarned;
    private java.util.UUID roundLogId;
    private String roundKind = "UNKNOWN";

    public ChatGamesModule() {
        super(SeedcrackerAddon.ID + ":chat-games", "Auto Chat Games",
            "Answers chat games offline: math, unscramble, type, reverse, fill-in, learned trivia (AI optional).");
    }

    @Override
    protected void onOptionValueChanged(String name) {
        if (name.equals("extra-words") && solver != null) extraWords.get().forEach(solver::addWord);
    }

    @Override
    public void onEnable() {
        if (wordDefaultsVersion.get() < 2) {
            extraWords.set(com.autism.seedcracker.chatgames.ChatGameVocabulary.withDefaults(extraWords.get()));
            wordDefaultsVersion.set(2);
        }
        closeJournal();
        journalWarned = false;
        roundLogId = null;
        round.clear();
        closedRound.clear();
        pendingQuestion = null;
        detectedCommand = "";
        lastPrompt = "";
        lastAnswerMs = 0;
        commandTree = null;
        roundToken++;
        cancelAi();
        solver = buildSolver();
        AutismClientMessaging.sendPrefixed("§a[ChatGames] §7Ready: " + solver.dictionarySize() + " words, "
            + solver.defaultAnswerCount() + " default answers, " + solver.triviaSnapshot().size()
            + " learned answers, " + solver.answerKeySize() + " custom answers.");
    }

    @Override
    public void onDisable() {
        finishEvidence("disabled");
        closeJournal();
        saveTrivia();
        round.clear();
        closedRound.clear();
        pendingQuestion = null;
        roundToken++;
        cancelAi();
    }

    @Override
    public boolean onPacketReceive(Packet<?> packet) {
        if (!(packet instanceof ClientboundSystemChatPacket chat) || chat.overlay() || chat.content() == null) return false;
        long receivedNanos = System.nanoTime();
        Component content = chat.content();
        String line = content.getString();
        Minecraft.getInstance().execute(() -> {
            long dispatchedNanos = System.nanoTime();
            handle(line, hoverText(content), content, receivedNanos, dispatchedNanos);
        });
        return false;
    }

    @Override
    public void tick() {
        if (!isEnabled()) return;
        if (solver == null) onEnable();
        refreshContext(Minecraft.getInstance());
    }

    private void refreshContext(Minecraft mc) {
        if (gameConnection != mc.getConnection() || gameWorld != mc.level) {
            finishEvidence("context-change");
            gameConnection = mc.getConnection();
            gameWorld = mc.level;
            commandTree = null;
            pendingQuestion = null;
            detectedCommand = "";
            lastPrompt = "";
            lastAnswerMs = 0;
            round.clear();
            closedRound.clear();
            roundToken++;
            cancelAi();
            solver.setCommands(List.of());
            addWorldWords(solver, mc);
        }
        if (gameConnection != null && commandTree != gameConnection.getCommands()) {
            commandTree = gameConnection.getCommands();
            solver.setCommands(gameConnection.getCommands().getRoot().getChildren().stream()
                .map(node -> node.getName()).toList());
        }
    }

    private void handle(String line, String hover, Component content, long receivedNanos, long handledNanos) {
        if (!isEnabled() || line == null || line.isBlank()) return;
        if (solver == null) onEnable();
        DebugProbe.setEnabled(id(), debug.get());
        Minecraft mc = Minecraft.getInstance();
        refreshContext(mc);
        if (mc.player == null) return;
        if (ChatLine.isPlayerChat(line, onlineNames(mc))) return;

        // Round-end lines first: learn the answer, and cancel any reply we haven't sent yet.
        var splitReveal = closedRound.takeQuoted(line, System.currentTimeMillis());
        String revealed = splitReveal == null ? ChatLine.revealedAnswer(line) : splitReveal.answer();
        if (revealed != null || ChatLine.isRoundOver(line)) {
            if (pendingQuestion == null && revealed != null) {
                var closed = splitReveal == null ? closedRound.take(System.currentTimeMillis()) : splitReveal.round();
                if (closed != null && learnTrivia.get()) {
                    solver.learnReveal(closed.question(), revealed, closed.canLearn());
                    DebugProbe.trace(id(), "late-reveal", closed.question() + " -> " + revealed);
                    saveTrivia();
                }
            } else if (pendingQuestion != null && revealed == null) {
                closedRound.remember(pendingQuestion, pendingCanLearn, System.currentTimeMillis(), ChatLine.isRevealHeader(line));
            } else if (revealed != null) {
                closedRound.clear();
            }
            if (pendingQuestion != null) {
                JsonObject outcome = evidence("outcome");
                boolean sent = submittedToken == roundToken;
                outcome.addProperty("sent", sent);
                if (sent) outcome.addProperty("answer", submittedAnswer);
                if (revealed != null) {
                    outcome.addProperty("revealed", revealed);
                    if (sent) {
                        outcome.addProperty("exactMatch", revealed.equals(submittedAnswer));
                        outcome.addProperty("normalizedMatch", ChatLine.sameAnswer(submittedAnswer, revealed));
                        if (revealed.equals(submittedAnswer)) outcome.addProperty("correct", true);
                        else if (!ChatLine.sameAnswer(submittedAnswer, revealed)) outcome.addProperty("correct", false);
                    }
                }
                String winner = ChatLine.winner(line);
                if (winner != null) {
                    outcome.addProperty("winner", winner);
                    if (sent) {
                        boolean won = winner.equalsIgnoreCase(mc.player.getGameProfile().name());
                        outcome.addProperty("won", won);
                        if (won) outcome.addProperty("correct", true);
                    }
                }
                outcome.addProperty("outcome", sent && revealed != null ? "revealed" : "unknown");
                recordEvidence(outcome);
            }
            if (revealed != null && submittedToken == roundToken) {
                DebugProbe.trace(id(), "answer-match", "exact=" + revealed.equals(submittedAnswer)
                    + " normalized=" + ChatLine.sameAnswer(submittedAnswer, revealed)
                    + " submitted=" + submittedAnswer + " revealed=" + revealed);
            }
            if (revealed != null && learnTrivia.get() && pendingQuestion != null
                && System.currentTimeMillis() - pendingQuestionAt < 5 * 60_000L) {
                solver.learnReveal(pendingQuestion, revealed, pendingCanLearn);
                DebugProbe.trace(id(), pendingCanLearn ? "learned" : "revealed", pendingQuestion + " -> " + revealed);
                saveTrivia();
            }
            pendingQuestion = null;
            roundLogId = null;
            round.clear();
            detectedCommand = "";
            roundToken++;
            cancelAi();
            return;
        }

        String kw = keyword.get().trim().toLowerCase(Locale.ROOT);
        String lower = line.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        if (!kw.isEmpty() && !lower.contains(kw) && !round.awaitingPayload(now)) return;
        if (com.autism.seedcracker.chatgames.ChatGameReaction.prompt(line)) {
            String command = reactionClicks.get() ? com.autism.seedcracker.chatgames.ChatGameReaction.select(content) : null;
            finishEvidence("reaction-prompt");
            roundToken++;
            cancelAi();
            round.clear();
            closedRound.clear();
            pendingQuestion = null;
            if (command == null || command.equals(lastPrompt) && now - lastAnswerMs < 15_000
                || now - lastAnswerMs < cooldownMs.get()) return;
            pendingQuestion = line;
            pendingCanLearn = false;
            pendingQuestionAt = now;
            roundKind = "REACTION";
            roundLogId = java.util.UUID.randomUUID();
            lastPrompt = command;
            lastAnswerMs = now;
            deliver(line, command.substring("/chatgames answer ".length()), "reaction", receivedNanos);
            return;
        }
        int revision = round.revision();
        String question = round.accept(line, now);
        if (question == null && revision != round.revision() && round.awaitingPayload(now)
            && hover != null && !hover.isBlank()) question = round.accept(hover, now);
        if (revision != round.revision()) {
            finishEvidence("new-header");
            closedRound.clear();
            pendingQuestion = null;
            detectedCommand = "";
            lastPrompt = "";
            roundToken++;
            cancelAi();
        }
        if (question == null) return;
        lower = question.toLowerCase(Locale.ROOT);
        // Some servers hide the actual word in the hover text of the prompt.
        String prompt = hover != null && !hover.isBlank() && !lower.contains(hover.toLowerCase(Locale.ROOT))
            ? question + " : " + hover : question;
        long solveStartedNanos = System.nanoTime();
        ChatGameSolver.Answer answer = solver.solve(prompt, wordProblems.get(), round.genericPayload());
        long solvedNanos = System.nanoTime();
        if (answer != null && !games.get() && answer.kind() != ChatGameSolver.Kind.MATH
            && answer.kind() != ChatGameSolver.Kind.TRIVIA) {
            answer = null;
        }
        if (answer == null && !looksLikeGame(lower) && !round.genericPayload() && !ChatLine.isQuestion(question)) return;
        boolean newQuestion = pendingQuestion == null || !pendingQuestion.equals(prompt);
        if (newQuestion) {
            finishEvidence("replaced");
            roundToken++;
            cancelAi();
            detectedCommand = "";
            roundLogId = java.util.UUID.randomUUID();
        }
        if (autoDetectCommand.get()) {
            String cmd = detectCommand(question);
            if (cmd != null) detectedCommand = cmd;
        }
        DebugProbe.trace(id(), "timing-local", "kind=" + (answer == null ? "UNKNOWN" : answer.kind())
            + " queueUs=" + ChatLine.elapsedMicros(receivedNanos, handledNanos)
            + " detectUs=" + ChatLine.elapsedMicros(handledNanos, solveStartedNanos)
            + " solveUs=" + ChatLine.elapsedMicros(solveStartedNanos, solvedNanos));

        pendingQuestion = prompt;
        roundKind = answer == null ? "UNKNOWN" : answer.kind().name();
        pendingCanLearn = answer == null || answer.kind() == ChatGameSolver.Kind.TRIVIA;
        pendingQuestionAt = now;
        if (newQuestion) {
            JsonObject detected = evidence("question");
            detected.addProperty("queueUs", ChatLine.elapsedMicros(receivedNanos, handledNanos));
            detected.addProperty("detectUs", ChatLine.elapsedMicros(handledNanos, solveStartedNanos));
            detected.addProperty("solveUs", ChatLine.elapsedMicros(solveStartedNanos, solvedNanos));
            recordEvidence(detected);
        }
        if (prompt.equals(lastPrompt) && now - lastAnswerMs < 15_000) return;
        if (now - lastAnswerMs < cooldownMs.get()) return;

        if (answer != null) {
            DebugProbe.trace(id(), "solved", answer.kind() + ": " + answer.text());
            lastPrompt = prompt;
            lastAnswerMs = now;
            deliver(prompt, answer.text(), answer.kind().name().toLowerCase(Locale.ROOT), receivedNanos);
        } else if (mode.get() != AnswerMode.LOCAL) {
            lastPrompt = prompt;
            lastAnswerMs = now;
            askAi(prompt, receivedNanos);
        } else {
            DebugProbe.trace(id(), "unsolved", line);
        }
    }

    private void askAi(String question, long receivedNanos) {
        if (aiRequest != null && !aiRequest.isDone()) return;
        int token = roundToken;
        Minecraft mc = Minecraft.getInstance();
        var connection = mc.getConnection();
        var world = mc.level;
        long requestStartedNanos = System.nanoTime();
        CompletableFuture<String> request = mode.get() == AnswerMode.LOCAL_AI
            ? AiRouter.askLocal(localAiEndpoint.get(), localAiModel.get(), question, aiTimeout.get())
            : AiRouter.ask(aiEndpoint.get(), aiKey.get(), aiModel.get(), question, aiTimeout.get());
        aiRequest = request;
        request.whenComplete((raw, err) -> mc.execute(() -> {
            if (aiRequest != request) return;
            aiRequest = null;
            String clean = err == null ? AiRouter.sanitize(raw) : null;
            if (clean != null && !solver.validatesAiAnswer(question, clean)) clean = null;
            JsonObject response = evidence("ai-result");
            response.addProperty("aiWaitUs", ChatLine.elapsedMicros(requestStartedNanos, System.nanoTime()));
            if (clean != null) response.addProperty("answer", ChatLine.answerText(clean));
            response.addProperty("outcome", clean == null ? "rejected-or-unavailable" : "candidate");
            recordEvidence(response);
            DebugProbe.trace(id(), "timing-ai", "waitUs=" + ChatLine.elapsedMicros(requestStartedNanos, System.nanoTime())
                + " usable=" + (clean != null) + " failed=" + (err != null));
            if (clean != null && isEnabled() && token == roundToken
                && mc.getConnection() == connection && mc.level == world) deliver(question, clean, "ai", receivedNanos);
        }));
    }

    private void cancelAi() {
        CompletableFuture<String> request = aiRequest;
        aiRequest = null;
        if (request != null) {
            JsonObject cancelled = evidence("ai-cancelled");
            cancelled.addProperty("reason", "round-owner-ended");
            recordEvidence(cancelled);
            request.cancel(true);
        }
    }

    private void deliver(String question, String answer, String how, long receivedNanos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        String textAnswer = ChatLine.answerText(answer);
        if (textAnswer.isEmpty()) return;
        JsonObject selected = evidence("answer");
        selected.addProperty("answer", textAnswer);
        selected.addProperty("rawAnswer", answer);
        selected.addProperty("sent", false);
        recordEvidence(selected);
        if (!autoReply.get()) {
            AutismClientMessaging.sendPrefixed("§a[ChatGames] §7(" + how + ") §f" + textAnswer);
            return;
        }
        if (ThreadLocalRandom.current().nextInt(100) < skipChance.get()) {
            DebugProbe.trace(id(), "skipped", question);
            return;
        }
        String prefix = replyPrefix.get().trim();
        if (prefix.isEmpty() && autoDetectCommand.get()) prefix = detectedCommand;
        ChatLine.Reply reply = ChatLine.reply("reaction".equals(how) ? "/chatgames answer " : prefix, answer);
        String out = reply.text();
        // Reading the prompt (~250ms/word) + typing the answer at the configured speed.
        long delay = ChatLine.replyDelay(humanized.get(), question, out, typeSpeed.get(), maxDelay.get(),
            ThreadLocalRandom.current().nextLong(500));
        int token = roundToken;
        var connection = mc.getConnection();
        var world = mc.level;
        Runnable send = () -> {
            // Someone else already won (or the round ended) while we were "typing".
            if (!isEnabled() || token != roundToken || connection == null
                || mc.getConnection() != connection || mc.level != world
                || "reaction".equals(how) && !reactionClicks.get()) return;
            long sendStartedNanos = System.nanoTime();
            if (reply.command()) connection.sendCommand(out.substring(1));
            else connection.sendChat(out);
            long sentNanos = System.nanoTime();
            submittedAnswer = textAnswer;
            submittedToken = token;
            JsonObject submitted = evidence("sent");
            submitted.addProperty("answer", textAnswer);
            submitted.addProperty("sent", true);
            submitted.addProperty("reactionUs", ChatLine.elapsedMicros(receivedNanos, sentNanos));
            submitted.addProperty("enqueueUs", ChatLine.elapsedMicros(sendStartedNanos, sentNanos));
            submitted.addProperty("configuredDelayMs", delay);
            submitted.addProperty("command", reply.command());
            recordEvidence(submitted);
            DebugProbe.trace(id(), "timing-send", "kind=" + how
                + " reactionUs=" + ChatLine.elapsedMicros(receivedNanos, sentNanos)
                + " enqueueUs=" + ChatLine.elapsedMicros(sendStartedNanos, sentNanos)
                + " configuredDelayMs=" + delay + " command=" + reply.command());
        };
        if (delay == 0) send.run();
        else CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(() -> mc.execute(send));
    }

    private static boolean looksLikeGame(String lower) {
        return lower.contains("unscramble") || lower.contains("first to") || lower.contains("trivia")
            || lower.contains("question") || lower.contains("guess") || lower.contains("quiz") || lower.contains("chat game");
    }

    private JsonObject evidence(String type) {
        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        if (roundLogId != null) event.addProperty("roundId", roundLogId.toString());
        if (pendingQuestion != null) event.addProperty("question", pendingQuestion);
        event.addProperty("kind", roundKind);
        event.addProperty("mode", mode.get().name());
        Minecraft mc = Minecraft.getInstance();
        event.addProperty("localWorld", mc.getSingleplayerServer() != null);
        if (mc.getCurrentServer() != null) event.addProperty("server", mc.getCurrentServer().ip);
        return event;
    }

    private void recordEvidence(JsonObject event) {
        if (!logGames.get() || journalWarned) return;
        try {
            if (journal == null) {
                journal = new ChatGameJournal(Minecraft.getInstance().gameDirectory.toPath().resolve("run/chatgames-evidence"));
            }
            if (journal.append(event)) return;
        } catch (java.io.IOException unavailable) {
            journalWarned = true;
            AutismClientMessaging.sendPrefixed("§e[ChatGames] Evidence logging unavailable: " + unavailable.getClass().getSimpleName());
            return;
        }
        journalWarned = true;
        AutismClientMessaging.sendPrefixed("§e[ChatGames] Evidence logging paused: " + journal.failure());
    }

    private void finishEvidence(String reason) {
        if (pendingQuestion != null && roundLogId != null) {
            JsonObject ended = evidence("end");
            ended.addProperty("reason", reason);
            ended.addProperty("outcome", "unknown");
            recordEvidence(ended);
        }
        roundLogId = null;
    }

    private void closeJournal() {
        if (journal != null) journal.close();
        journal = null;
    }

    private static String detectCommand(String text) {
        return ChatLine.replyCommand(text);
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
        Set<String> words = com.autism.seedcracker.chatgames.ChatGameVocabulary.builtInWords();
        words.addAll(extraWords.get());
        Path custom = dir().resolve("chatgames-words.txt");
        try {
            if (!Files.exists(custom)) {
                Files.createDirectories(custom.getParent());
                Files.writeString(custom, "# One word or phrase per line. Used for unscramble / fill-in games.\n", StandardCharsets.UTF_8);
            }
            for (String l : Files.readAllLines(custom, StandardCharsets.UTF_8)) if (!l.startsWith("#")) words.add(l);
        } catch (Exception ignored) {}
        ChatGameSolver s = new ChatGameSolver(words);
        com.autism.seedcracker.chatgames.ChatGameAnswerKey.defaults().values().forEach(s::addWord);
        try (var input = ChatGamesModule.class.getResourceAsStream("/chatgames/public-plugin-words.txt")) {
            if (input == null) throw new java.io.IOException("Bundled word list missing");
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(input, StandardCharsets.UTF_8))) {
                reader.lines().limit(2000).filter(word -> !word.startsWith("#")).forEach(s::addWord);
            }
        } catch (java.io.IOException invalid) {
            AutismClientMessaging.sendPrefixed("§e[ChatGames] §7Could not load bundled plugin words.");
        }
        for (String resource : List.of("/chatgames/english-fallback-words.txt", "/chatgames/public-country-words.txt")) {
            try (var input = ChatGamesModule.class.getResourceAsStream(resource)) {
                if (input == null) throw new java.io.IOException("Bundled fallback list missing");
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(input, StandardCharsets.UTF_8))) {
                    reader.lines().limit(400000).forEach(s::addFallbackWord);
                }
            } catch (java.io.IOException invalid) {
                AutismClientMessaging.sendPrefixed("§e[ChatGames] §7Could not load fallback vocabulary: " + resource);
            }
        }
        addWorldWords(s, Minecraft.getInstance());
        try {
            Path learnedWords = dir().resolve("chatgames-learned-words.txt");
            if (Files.isRegularFile(learnedWords)) {
                if (Files.size(learnedWords) > 256 * 1024) throw new java.io.IOException("Learned vocabulary exceeds 256 KB");
                try (var entries = Files.lines(learnedWords, StandardCharsets.UTF_8)) {
                    entries.limit(2000).forEach(s::addLearnedWord);
                }
            }
        } catch (java.io.IOException invalid) {
            AutismClientMessaging.sendPrefixed("\u00a7e[ChatGames] Could not load learned vocabulary: " + invalid.getMessage());
        }
        try {
            Path f = dir().resolve("chatgames-learned.json");
            if (Files.exists(f)) {
                Map<String, String> learned = new Gson().fromJson(Files.readString(f, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, String>>() {}.getType());
                if (learned != null) learned.forEach((q, a) -> { s.learn(q, a); s.addWord(a); });
            }
        } catch (Exception ignored) {}
        Map<String, String> answers = new java.util.LinkedHashMap<>();
        for (String filename : List.of("chatgames-answers.json", "chatgames-answers.txt")) {
            try {
                Path key = dir().resolve(filename);
                boolean text = filename.endsWith(".txt");
                if (!Files.exists(key)) {
                    Files.createDirectories(key.getParent());
                    String example = text
                        ? "# Paste a question on one line and its answer on the next. Blank lines are optional.\n"
                        : "{\n  \"Which mob explodes when it gets close to you?\": \"creeper\"\n}\n";
                    Files.writeString(key, example, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
                }
                answers.putAll(text ? com.autism.seedcracker.chatgames.ChatGameAnswerKey.loadText(key)
                    : com.autism.seedcracker.chatgames.ChatGameAnswerKey.load(key));
            } catch (java.io.IOException invalid) {
                AutismClientMessaging.sendPrefixed("§e[ChatGames] §7Could not load " + filename + ": "
                    + invalid.getMessage() + ". Fix the file and toggle this module off/on.");
            }
        }
        s.setAnswerKey(answers);
        answers.values().forEach(s::addWord);
        return s;
    }

    private static void addWorldWords(ChatGameSolver target, Minecraft mc) {
        if (mc.level == null) return;
        com.autism.seedcracker.chatgames.ChatGameVocabulary.addWorldWords(target, mc.level.registryAccess());
        target.addWord(mc.level.dimension().identifier().getPath().replace('_', ' '));
    }

    private void saveTrivia() {
        if (solver == null) return;
        try {
            Path f = dir().resolve("chatgames-learned.json");
            Files.createDirectories(f.getParent());
            saveLearnedFile(f, new Gson().toJson(solver.triviaSnapshot()));
            saveLearnedFile(dir().resolve("chatgames-learned-words.txt"),
                String.join("\n", solver.learnedWordsSnapshot().stream().sorted().toList()));
        } catch (java.io.IOException failure) {
            AutismClientMessaging.sendPrefixed("\u00a7e[ChatGames] Could not save learning: " + failure.getClass().getSimpleName());
        }
    }

    private static void saveLearnedFile(Path destination, String content) throws java.io.IOException {
        Path temporary = Files.createTempFile(destination.getParent(), "chatgames-learning-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public String info() {
        return solver == null ? "" : solver.triviaSnapshot().size() + " learned, " + solver.answerKeySize() + " custom";
    }
}
