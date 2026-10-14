package com.autism.seedcracker.commands;

import com.autism.seedcracker.setup.Loadouts;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

import autismclient.commands.AutismCommandSource;
import autismclient.commands.Command;
import autismclient.util.AutismClientMessaging;

/**
 * {@code .qql} - the "just tell me what to turn on" command. Lists the one-click loadouts and
 * applies them, so new users get a direct answer instead of scrolling 90-odd modules.
 *
 * Usage:
 *   .qql                 -> list every loadout
 *   .qql <name>          -> apply a loadout (turns off other addon modules first)
 *   .qql <name> keep     -> apply a loadout on top of whatever is already enabled
 *   .qql off             -> turn every addon module off
 */
public final class QqlCommand extends Command {
    private java.util.concurrent.CompletableFuture<String> aiTest;
    public QqlCommand() {
        super("qql", "One-click loadouts: .qql [list|off|<name> [keep]]", "loadout", "setup");
    }

    @Override
    public void build(LiteralArgumentBuilder<AutismCommandSource> root) {
        // Bare ".qql" lists the loadouts.
        root.executes(ctx -> { Loadouts.printOverview(); return SUCCESS; });

        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("list")
            .executes(ctx -> { Loadouts.printOverview(); return SUCCESS; }));

        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("off")
            .executes(ctx -> { AutismClientMessaging.sendPrefixed(Loadouts.disableAll()); return SUCCESS; }));

        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("ai-test")
            .executes(ctx -> testAi("Briefly explain how Minecraft comparators work."))
            .then(RequiredArgumentBuilder.<AutismCommandSource, String>argument("question", StringArgumentType.greedyString())
                .executes(ctx -> testAi(StringArgumentType.getString(ctx, "question")))));
        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("ai-test-context")
            .executes(ctx -> testAi("For this conversation, remember that my test block choice is polished andesite. Briefly acknowledge it.", true)));

        // ".qql <name>" and ".qql <name> keep".
        root.then(RequiredArgumentBuilder.<AutismCommandSource, String>argument("name", StringArgumentType.word())
            .executes(ctx -> apply(StringArgumentType.getString(ctx, "name"), true))
            .then(RequiredArgumentBuilder.<AutismCommandSource, String>argument("mode", StringArgumentType.word())
                .executes(ctx -> {
                    String mode = StringArgumentType.getString(ctx, "mode");
                    boolean exclusive = !mode.equalsIgnoreCase("keep") && !mode.equalsIgnoreCase("add");
                    return apply(StringArgumentType.getString(ctx, "name"), exclusive);
                })));
    }

    private int apply(String name, boolean exclusive) {
        Loadouts.Loadout loadout = Loadouts.byKey(name);
        if (loadout == null) {
            AutismClientMessaging.sendPrefixed("§cUnknown loadout '" + name + "'. Use §e.qql§c to list them.");
            return SUCCESS;
        }
        AutismClientMessaging.sendPrefixed(Loadouts.apply(loadout, exclusive));
        return SUCCESS;
    }

    private int testAi(String question) {
        return testAi(question, false);
    }

    private int testAi(String question, boolean contextTest) {
        if (aiTest != null && !aiTest.isDone()) {
            AutismClientMessaging.sendPrefixed("\u00a7e[AI test] A provider test is already running.");
            return SUCCESS;
        }
        if (question.length() > 512 || com.autism.seedcracker.chatbot.ChatBotPolicy.sanitizeConversation(question) == null) {
            AutismClientMessaging.sendPrefixed("\u00a7e[AI test] Use a short, non-sensitive question.");
            return SUCCESS;
        }
        var path = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("autism/ai/settings.json");
        var profile = com.autism.seedcracker.chatbot.V4ChatProvider.read(path);
        if (profile == null || profile.builtIn()) {
            AutismClientMessaging.sendPrefixed("\u00a7e[AI test] Select a language-model provider in V4's AI screen; built-in mode runs local game intents.");
            return SUCCESS;
        }
        AutismClientMessaging.sendPrefixed("\u00a77[AI test] Testing V4's selected provider; output stays local. Credentials available: "
            + com.autism.seedcracker.chatbot.V4ChatProvider.credentialsAvailable(path));
        var history = new com.autism.seedcracker.chatbot.ChatBotConversation();
        var sender = java.util.UUID.randomUUID();
        var first = history.begin(sender, question, 2, System.currentTimeMillis());
        var request = com.autism.seedcracker.chatbot.V4ChatProvider.converse(path, first.turns(), 30);
        aiTest = contextTest ? request.thenCompose(raw -> {
            String clean = com.autism.seedcracker.chatbot.ChatBotPolicy.sanitizeConversation(raw);
            if (clean == null || !history.complete(first, clean, System.currentTimeMillis()))
                return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Unsafe first turn"));
            var next = history.begin(sender, "Which test block did I choose earlier? Reply with only the block name.", 2, System.currentTimeMillis());
            return com.autism.seedcracker.chatbot.V4ChatProvider.converse(path, next.turns(), 30);
        }) : request;
        aiTest.whenComplete((raw, error) -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
            String reply = error == null ? com.autism.seedcracker.chatbot.ChatBotPolicy.sanitizeConversation(raw) : null;
            history.clear();
            if (contextTest && reply != null) {
                AutismClientMessaging.sendPrefixed(reply.toLowerCase(java.util.Locale.ROOT).contains("polished andesite")
                    ? "\u00a7a[AI test] Two-turn context PASS: " + reply
                    : "\u00a7e[AI test] Two-turn context FAIL: selected block was not recalled.");
                return;
            }
            AutismClientMessaging.sendPrefixed(reply != null ? "\u00a7a[AI test] " + reply
                : "\u00a7e[AI test] " + (error != null && error.getMessage() != null && error.getMessage().startsWith("V4 native text request failed:")
                    ? error.getMessage() : "Native provider failed or returned an unsafe reply. Check V4's AI screen."));
        }));
        return SUCCESS;
    }
}
