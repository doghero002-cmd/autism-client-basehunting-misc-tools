package com.autism.seedcracker.chatgames;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.autism.seedcracker.chatgames.ChatGameSolver.Kind;

import static org.junit.jupiter.api.Assertions.*;

class ChatGameSolverTest {

    @Test
    void reactionButtonsRequireExactAllowlistedCommandsAndUnambiguousVisibleTargets() {
        String first = "/chatgames answer 11111111-1111-4111-8111-111111111111";
        String second = "/chatgames answer 22222222-2222-4222-8222-222222222222";
        var one = net.minecraft.network.chat.Component.literal("ChatGames: First to click ")
            .append(net.minecraft.network.chat.Component.literal("GO").withStyle(style -> style.withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand(first))));
        assertEquals(first, ChatGameReaction.select(one));
        var targeted = net.minecraft.network.chat.Component.literal("Reaction game: click 'GO' ")
            .append(net.minecraft.network.chat.Component.literal("NO").withStyle(style -> style.withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand(second))))
            .append(net.minecraft.network.chat.Component.literal("GO").withStyle(style -> style.withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand(first))));
        assertEquals(first, ChatGameReaction.select(targeted));
        var ambiguous = net.minecraft.network.chat.Component.literal("ChatGames: click a button ")
            .append(one.getSiblings().get(0)).append(targeted.getSiblings().get(0));
        assertNull(ChatGameReaction.select(ambiguous));
        assertNull(ChatGameReaction.select(targeted.copy().append(net.minecraft.network.chat.Component.literal("GO")
            .withStyle(style -> style.withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand(second))))));
        for (String unsafe : List.of("/op player", first + " extra", first + "\n/op player", "/chatgames answer not-a-uuid")) {
            assertNull(ChatGameReaction.select(net.minecraft.network.chat.Component.literal("ChatGames: first to click GO")
                .withStyle(style -> style.withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand(unsafe)))));
        }
        assertNull(ChatGameReaction.select(net.minecraft.network.chat.Component.literal("ChatGames: first to click GO")
            .withStyle(style -> style.withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenUrl(java.net.URI.create("https://example.com"))))));
        assertNull(ChatGameReaction.select(net.minecraft.network.chat.Component.literal("click GO")
            .withStyle(style -> style.withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand(first)))));
    }

    @Test
    void separateLateRevealRemembersOnlyOneRecentClosedRound() {
        var window = new ChatLine.RevealWindow();
        window.remember("Which flower does this server prefer?", true, 1000);
        var target = window.take(2500);
        assertNotNull(target);
        assertEquals("Which flower does this server prefer?", target.question());
        assertTrue(target.canLearn());
        assertNull(window.take(2501));
        window.remember("Old question", true, 1000);
        assertNull(window.take(11001));
        window.remember("Future clock", true, 2000);
        assertNull(window.take(1999));
        window.remember("Math round", false, 3000);
        assertFalse(window.take(3001).canLearn());
        window.remember("Previous server", true, 4000);
        window.clear();
        assertNull(window.take(4001));
    }

    @Test
    void numberedNamesRetainTheirIdentityAndCannotLearnOrValidateAnotherNumber() {
        var solver = new ChatGameSolver(java.util.List.of("music disc 11", "music disc 13", "music disc 5", "music_disc_11"));
        assertEquals(3, solver.dictionarySize());
        assertEquals("music disc 11", solver.unscramble("cdis cimus 11"));
        assertEquals("music disc 13", solver.unscramble("cdis cimus 13"));
        assertNull(solver.unscramble("cdis cimus"));
        assertTrue(solver.validatesAiAnswer("Unscramble: cdis cimus 11", "music disc 11"));
        assertFalse(solver.validatesAiAnswer("Unscramble: cdis cimus 11", "music disc 13"));
        solver.learn("Unscramble: cdis cimus 11", "music disc 13");
        assertEquals("music disc 11", solver.solve("Unscramble: cdis cimus 11", false).text());
    }

    @Test
    void minecraftRegistryVocabularyIncludesPotionAndWorldCategories() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var words = ChatGameVocabulary.builtInWords();
        for (var registry : List.of(net.minecraft.core.registries.BuiltInRegistries.ITEM,
            net.minecraft.core.registries.BuiltInRegistries.BLOCK,
            net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE,
            net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT,
            net.minecraft.core.registries.BuiltInRegistries.POTION,
            net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION)) {
            for (var key : registry.keySet()) assertTrue(words.contains(key.getPath().replace('_', ' ')), key.toString());
        }
        ChatGameSolver expanded = new ChatGameSolver(words);
        for (String structure : List.of("trail ruins", "ocean ruin warm", "village snowy", "shipwreck beached")) {
            assertTrue(words.contains(structure), structure);
            assertNotNull(expanded.solve("Unscramble: " + new StringBuilder(structure).reverse(), false), structure);
        }
        ChatGameVocabulary.addWorldWords(expanded, net.minecraft.data.registries.VanillaRegistries.createLookup());
        for (String name : List.of("sculk shrieker", "armorer", "long swiftness", "sentry armor trim",
            "rusty wolf", "warm cow", "ponder goat horn", "ocean ruin warm", "cherry grove", "wind burst")) {
            String scrambled = new StringBuilder(name).reverse().toString();
            var answer = expanded.solve("Unscramble: " + scrambled, false);
            assertNotNull(answer, name);
            assertEquals(name, answer.text(), name);
        }
        System.out.println("MINECRAFT_VOCABULARY builtInNames=" + words.size() + " indexed=" + expanded.dictionarySize());
    }

    @Test
    void largeEnglishFallbackPreservesMinecraftPriorityAndAbstainsOnAmbiguity() throws Exception {
        ChatGameSolver expanded = new ChatGameSolver(List.of("coal", "diamond"));
        try (var input = ChatGameAnswerKey.class.getResourceAsStream("/chatgames/english-fallback-words.txt")) {
            assertNotNull(input);
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))) {
                reader.lines().limit(400000).forEach(expanded::addFallbackWord);
            }
        }
        assertTrue(expanded.dictionarySize() > 300000);
        assertEquals("coal", expanded.solve("Unscramble: aloc", false).text());
        assertEquals("diamond", expanded.solve("Unscramble: mdnaiod", false).text());
        assertNull(expanded.solve("Unscramble: allerbmu", false));
        assertEquals("computer", expanded.solve("Unscramble: retupmoc", false).text());
        expanded.learn("Unscramble: allerbmu", "umbrella");
        assertEquals("umbrella", expanded.solve("Unscramble: allerbmu", false).text());
        assertEquals("computer", expanded.solve("computer", false, true).text());
        assertNull(expanded.solve("Unscramble: tinsel", false));
        ChatGameSolver small = new ChatGameSolver(List.of("listen", "silent"));
        small.addFallbackWord("enlist");
        assertNull(small.solve("Unscramble: tinsel", false));
        small.addFallbackWord("umbrella");
        assertEquals("umbrella", small.solve("Fill in: um_rella", false).text());
    }

    @Test
    void publicWordVocabularyIncludesOrdinaryAndLegacyNamesWithoutSpacingAmbiguity() throws Exception {
        var vocabulary = new java.util.ArrayList<String>();
        try (var input = ChatGameAnswerKey.class.getResourceAsStream("/chatgames/public-plugin-words.txt")) {
            assertNotNull(input);
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))) {
                reader.lines().filter(word -> !word.startsWith("#")).forEach(vocabulary::add);
            }
        }
        ChatGameSolver expanded = new ChatGameSolver(vocabulary);
        assertEquals("keyboard", expanded.solve("Unscramble: draobyek", false).text());
        assertEquals("computer", expanded.solve("Unscramble: retupmoc", false).text());
        assertEquals("ender eye", expanded.solve("Unscramble: eye redne", false).text());
        expanded.addWord("slimeball");
        assertEquals("slime ball", expanded.solve("Unscramble: llabemils", false).text());
        ChatLine.Round round = new ChatLine.Round();
        assertNull(round.accept("Chat game! First to answer wins.", 1000));
        String prompt = round.accept("  SculkShrieker", 1001);
        expanded.addWord("sculk shrieker");
        assertEquals("SculkShrieker", expanded.solve(prompt, true, round.genericPayload()).text());
    }

    @Test
    void publicCountryTargetsAreFallbackPhrasesAndCannotShadowMinecraftWords() throws Exception {
        ChatGameSolver expanded = new ChatGameSolver(List.of("chain", "diamond"));
        try (var input = ChatGameAnswerKey.class.getResourceAsStream("/chatgames/public-country-words.txt")) {
            assertNotNull(input);
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))) {
                var countries = reader.lines().filter(line -> !line.isBlank()).toList();
                assertEquals(197, countries.size());
                countries.forEach(expanded::addFallbackWord);
            }
        }
        assertEquals("chain", expanded.solve("Unscramble: anihc", false).text());
        assertEquals("new zealand", expanded.solve("Unscramble: dnalaez wen", false).text());
        assertEquals("cote d'ivoire", expanded.solve("Unscramble: eriovid etoc", false).text());
        int size = expanded.dictionarySize();
        expanded.addFallbackWord("newzealand");
        expanded.addFallbackWord("bad\nword");
        expanded.addFallbackWord("a".repeat(41));
        expanded.addFallbackWord("ab");
        assertEquals(size, expanded.dictionarySize());
        assertNotNull(ChatGameAnswerKey.class.getResource("/chatgames/public-country-LICENSE.txt"));
        assertEquals("Phantom", expanded.solve("I strike in the darkness if you don't sleep enough!", true).text());
        assertEquals("Wither", expanded.solve("I have three heads, I destroy hard, summon me and you better run!", true).text());
    }

    @Test
    void plainTextKeysAcceptPastedQuestionAnswerPairsAndOptionalLabels(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var file = directory.resolve("chatgames-answers.txt");
        String pasted = "# My server answers\n\nWhich mob explodes when it gets close to you?\ncreeper\n\n"
            + "Question: Which flower do bees prefer on this server?\nAnswer: Blue Orchid\n"
            + "Q: What command sells all drops?\nA: /sellall\n";
        java.nio.file.Files.writeString(file, pasted);
        solver.setAnswerKey(ChatGameAnswerKey.loadText(file));
        assertEquals(3, solver.answerKeySize());
        assertEquals("creeper", solver.solve("Which mob explodes when it gets close to you?", true).text());
        assertEquals("Blue Orchid", solver.solve("[ChatGames] Which flower do bees prefer on this server?", true).text());
        var reply = ChatLine.reply("", solver.solve("What command sells all drops?", true).text());
        assertEquals("sellall", reply.text());
        assertFalse(reply.command());
        assertEquals(pasted, java.nio.file.Files.readString(file));
    }

    @Test
    void incompleteOrConflictingTextKeysAreRejected(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var file = directory.resolve("chatgames-answers.txt");
        for (String text : List.of("Which flower is preferred?", "Which flower is preferred?\n\n",
            "Which flower is preferred?\npoppy\nWhich flower is preferred?\nrose\n",
            "Which flower is preferred?\npoppy\nWHICH flower is preferred!\nrose\n",
            " ".repeat(256 * 1024 + 1))) {
            java.nio.file.Files.writeString(file, text);
            assertThrows(java.io.IOException.class, () -> ChatGameAnswerKey.loadText(file));
        }
    }

    @Test
    void publicPluginQuestionsPreserveAcceptedAnswersWithoutAddingObsoleteCounts() {
        assertEquals("ocelot", solver.solve("What are Creepers scared of?", true).text());
        assertEquals("Hay bales", solver.solve("What item do you need to breed llamas?", true).text());
        assertEquals("10", solver.solve("How many hit points does a Ghast have?", true).text());
        assertEquals("Combat update", solver.solve("What is the official name of update 1.9?", true).text());
        assertEquals("1.16", solver.solve("What minecraft version overhauled the Nether?", true).text());
        assertEquals("glowstone dust", solver.solve("What item do you use in a brewing stand to create a thick potion?", true).text());
        assertEquals("Markus Persson", solver.solve("What is Notch's real name?", true).text());
        assertEquals("Java", solver.solve("Which programming language is Minecraft written in?", true).text());
        assertNull(solver.solve("How many neutral mobs exist? (1.15)", true));
        assertNull(solver.solve("How many minecraft editions/games are there?", true));
        assertNotNull(ChatGameAnswerKey.class.getResource("/chatgames/public-plugin-NOTICES.txt"));
    }

    @Test
    void bundledMinecraftBankSolvesEveryEntryAndSupportsOverrides() {
        var defaults = ChatGameAnswerKey.defaults();
        assertTrue(defaults.size() >= 200);
        for (var entry : defaults.entrySet()) {
            var answer = solver.solve("[ChatGames] Trivia: " + entry.getKey(), true);
            assertNotNull(answer, entry.getKey());
            assertEquals(entry.getValue(), answer.text(), entry.getKey());
        }
        String question = "Which mob drops blaze rods?";
        solver.learn(question, "ServerBlaze");
        assertEquals("ServerBlaze", solver.solve(question, true).text());
        solver.setAnswerKey(java.util.Map.of(question, "CustomBlaze"));
        assertEquals("CustomBlaze", solver.solve(question, true).text());
        assertNull(solver.solve("Which mob does not drop blaze rods?", true));
        assertNull(solver.solve("What command sells all drops?", true));
        assertEquals(defaults.size(), solver.defaultAnswerCount());
    }

    @Test
    void externalAnswerKeyLoadsSentenceAnswersSeparatelyFromLearnedTrivia(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var file = directory.resolve("chatgames-answers.json");
        String json = "{\"Which flower do bees prefer on this server?\":\"Blue Orchid\","
            + "\"What command sells all drops?\":\"/sellall\",\"What is 17 - 13?\":\"17\"}";
        java.nio.file.Files.writeString(file, json, java.nio.charset.StandardCharsets.UTF_8);
        solver.learn("Which flower do bees prefer on this server?", "Dandelion");
        solver.setAnswerKey(ChatGameAnswerKey.load(file));
        assertEquals(3, solver.answerKeySize());
        assertEquals("Blue Orchid", solver.solve("[ChatGames] Trivia: WHICH flower do bees prefer on this server!", true).text());
        solver.learn("Which flower do bees prefer on this server?", "Poppy");
        assertEquals("Blue Orchid", solver.solve("Which flower do bees prefer on this server?", true).text());
        assertEquals("Poppy", solver.triviaSnapshot().get(ChatGameSolver.normalizeQuestion("Which flower do bees prefer on this server?")));
        assertEquals("4", solver.solve("What is 17 - 13?", true).text());
        assertEquals("Blue Orchid", solver.solve("Type: Blue Orchid", true).text());
        var reply = ChatLine.reply("", solver.solve("What command sells all drops?", true).text());
        assertEquals("sellall", reply.text());
        assertFalse(reply.command());
        assertEquals(json, java.nio.file.Files.readString(file));
        java.nio.file.Files.writeString(file, "{}");
        solver.setAnswerKey(ChatGameAnswerKey.load(file));
        assertEquals(0, solver.answerKeySize());
        assertEquals("Poppy", solver.solve("Which flower do bees prefer on this server?", true).text());
    }

    @Test
    void externalAnswerKeyRejectsMalformedUnsafeConflictingAndOversizedFiles(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var file = directory.resolve("chatgames-answers.json");
        for (String invalid : List.of("[]", "broken JSON", "{\"Which flower is preferred?\":null}",
            "{\"Which flower is preferred?\":12}", "{\"Which flower is preferred?\":\"one\\ntwo\"}",
            "{\"Chat game! First to answer wins.\":\"creeper\"}",
            "{\"Which flower is preferred?\":\"poppy\",\"WHICH flower is preferred!\":\"rose\"}",
            " ".repeat(256 * 1024 + 1))) {
            java.nio.file.Files.writeString(file, invalid);
            assertThrows(java.io.IOException.class, () -> ChatGameAnswerKey.load(file));
        }
    }

    @Test
    void sentenceFactsRecognizeExplodingMobParaphrasesWithoutGuessingNegativesOrProjectiles() {
        for (String question : List.of(
            "Which mob explodes when it gets close to you?",
            "[ChatGames] Question: What creature blows up near players?",
            "Which monster detonates when you approach it?")) {
            var answer = solver.solve(question, true);
            assertNotNull(answer, question);
            assertEquals(Kind.TRIVIA, answer.kind());
            assertEquals("creeper", answer.text());
        }
        assertNull(solver.solve("Which mob doesn't explode when close to you?", true));
        assertNull(solver.solve("Which mob shoots a fireball that explodes near you?", true));
        assertNull(solver.solve("Which mob gets close to you?", true));
        assertEquals("obsidian", solver.solve("Which block makes a nether portal?", true).text());
        assertEquals("the end", solver.solve("Where does the ender dragon live?", true).text());
    }

    @Test
    void genericGensHqRoundUnscramblesPreophRatherThanRetypingIt() {
        ChatGameSolver minecraft = new ChatGameSolver(List.of("hopper", "glow berries", "sculk shrieker", "listen", "silent"));
        ChatLine.Round round = new ChatLine.Round();
        assertNull(round.accept("Chat game! First to answer wins.", 1000));
        String prompt = round.accept("preoph", 1001);
        assertTrue(round.genericPayload());
        var answer = minecraft.solve(prompt, true, round.genericPayload());
        assertNotNull(answer);
        assertEquals(Kind.UNSCRAMBLE, answer.kind());
        assertEquals("hopper", answer.text());
        assertEquals(Kind.TYPE, minecraft.solve("hopper", true, true).kind());
        assertEquals("GlowBerries", minecraft.solve("GlowBerries", true, true).text());
        assertEquals("SculkShrieker", minecraft.solve("SculkShrieker", true, true).text());
        assertEquals("GensHQ", minecraft.solve("GensHQ", true, true).text());
        assertNull(minecraft.solve("tinsel", true, true));
        assertEquals("preoph", minecraft.solve("Type: preoph", true, false).text());
    }

    @Test
    void aiCandidatesCannotContradictCheckableMathAnagramsOrMinecraftFacts() {
        assertTrue(solver.validatesAiAnswer("Calculate 17 - 13", "4"));
        assertFalse(solver.validatesAiAnswer("Calculate 17 - 13", "17"));
        assertTrue(solver.validatesAiAnswer("Unscramble: mdnaiod", "diamond"));
        assertFalse(solver.validatesAiAnswer("Unscramble: mdnaiod", "obsidian"));
        assertTrue(solver.validatesAiAnswer("What block builds a nether portal?", "obsidian"));
        assertFalse(solver.validatesAiAnswer("What block builds a nether portal?", "crying obsidian"));
        ChatGameSolver ambiguous = new ChatGameSolver(List.of("listen", "silent", "enlist"));
        assertTrue(ambiguous.validatesAiAnswer("Unscramble: tinsel", "silent"));
        assertFalse(ambiguous.validatesAiAnswer("Unscramble: tinsel", "diamond"));
        assertFalse(solver.validatesAiAnswer("Question", null));
    }

    @Test
    void aiModeAcceptsOneAnswerAndRejectsExplanationsAlternativesAndExtraFields() {
        assertEquals("obsidian", AiRouter.sanitize("{\"answer\":\"obsidian\"}"));
        assertEquals("Golden Apple", AiRouter.sanitize("{\"answer\":\"Golden Apple\"}"));
        assertEquals("sellall", AiRouter.sanitize("{\"answer\":\"sellall\"}"));
        assertNull(AiRouter.sanitize("{\"answer\":null}"));
        assertNull(AiRouter.sanitize("{\"answer\":\"obsidian\",\"reason\":\"portal frame\"}"));
        assertNull(AiRouter.sanitize("obsidian because it is used for portal frames"));
        assertNull(AiRouter.sanitize("obsidian\nHere is why"));
        assertNull(AiRouter.sanitize("obsidian or crying obsidian"));
        assertNull(AiRouter.sanitize("```json\n{\"answer\":\"obsidian\"}\n```"));
        assertNull(AiRouter.sanitize("Maybe diamond"));
    }

    @Test
    void localAiOptionAllowsOnlyLiteralLoopbackEndpointsAndRequiresAnInstalledModel() throws Exception {
        assertTrue(AiRouter.isLocalEndpoint("http://127.0.0.1:11434/v1/chat/completions"));
        assertTrue(AiRouter.isLocalEndpoint("http://localhost:1234/v1/chat/completions"));
        assertTrue(AiRouter.isLocalEndpoint("http://[::1]:1234/v1/chat/completions"));
        assertFalse(AiRouter.isLocalEndpoint("https://api.openai.com/v1/chat/completions"));
        assertFalse(AiRouter.isLocalEndpoint("http://localhost.example.com/v1/chat/completions"));
        assertFalse(AiRouter.isLocalEndpoint("http://127.0.0.1@evil.example/v1/chat/completions"));
        assertFalse(AiRouter.isLocalEndpoint("http://localhost:1234/v1/chat/completions?key=secret"));
        assertNull(AiRouter.askLocal("https://example.com", "test", "question", 1).get());
        assertNull(AiRouter.askLocal("http://localhost:11434/v1/chat/completions", "", "question", 1).get());
    }

    @Test
    void localAiDoesNotFollowRedirectsOrSendAuthorization() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var redirected = new java.util.concurrent.atomic.AtomicInteger();
        var authorization = new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/redirect", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Location", "/target");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            redirected.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect";
            assertNull(AiRouter.askLocal(endpoint, "test", "Question", 2).get(3, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, redirected.get());
            assertNull(authorization.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void gameJournalSnapshotsEvidenceWithoutRecordingUnrelatedSecrets(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        ChatGameJournal journal = new ChatGameJournal(directory);
        var event = new com.google.gson.JsonObject();
        event.addProperty("type", "outcome");
        event.addProperty("roundId", java.util.UUID.randomUUID().toString());
        event.addProperty("question", "What block builds a nether portal?");
        event.addProperty("answer", "obsidian");
        event.addProperty("correct", true);
        event.addProperty("reactionUs", 9620);
        event.addProperty("apiKey", "never-record-this");
        assertTrue(journal.append(event));
        event.addProperty("answer", "mutated");
        journal.close();
        assertTrue(journal.awaitClosed(3000));
        var lines = java.nio.file.Files.readAllLines(journal.file(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        var stored = com.google.gson.JsonParser.parseString(lines.get(0)).getAsJsonObject();
        assertEquals("obsidian", stored.get("answer").getAsString());
        assertTrue(stored.get("correct").getAsBoolean());
        assertEquals(9620, stored.get("reactionUs").getAsLong());
        assertFalse(stored.has("apiKey"));
        assertEquals(1, stored.get("schemaVersion").getAsInt());
        assertFalse(journal.append(event));
    }

    @Test
    void gameJournalBoundsRecordCountAndDiskGrowth(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var event = new com.google.gson.JsonObject();
        event.addProperty("type", "question");
        event.addProperty("question", "What command sells all drops?");
        ChatGameJournal limited = new ChatGameJournal(directory.resolve("records"), 1, 4096);
        assertTrue(limited.append(event));
        assertFalse(limited.append(event));
        limited.close();
        assertTrue(limited.awaitClosed(3000));
        assertEquals(1, java.nio.file.Files.readAllLines(limited.file()).size());
        ChatGameJournal small = new ChatGameJournal(directory.resolve("bytes"), 10, 1);
        assertTrue(small.append(event));
        small.close();
        assertTrue(small.awaitClosed(3000));
        assertFalse(small.failure().isEmpty());
        assertEquals(0, java.nio.file.Files.size(small.file()));
    }

    @Test
    void sentenceQuestionsUseMinecraftFactsWithoutIncludingGameLabelsInTheAnswer() {
        String question = "What block do you use to build a nether portal?";
        assertEquals(question, ChatLine.questionText("[ChatGames] Trivia: " + question));
        assertEquals("obsidian", solver.solve("[ChatGames] Trivia: " + question, true).text());
        assertEquals("obsidian", solver.solve("Which material makes the frame of a nether portal?", false).text());
        assertEquals("the end", solver.solve("In what dimension does the ender dragon live?".substring(3), true).text());
        assertEquals("phantom membrane", solver.solve("What item repairs an elytra?", true).text());
        assertNull(solver.solve("What block should not be used to build a nether portal?", true));
        assertNull(solver.solve("What block is used to activate a nether portal?", true));
        assertNull(solver.solve("What is the best block for my house?", true));
    }

    @Test
    void serverCommandQuestionsRequireAdvertisedCommandsAndReturnTextNotAnExecution() {
        String question = "What command sells all drops?";
        assertNull(solver.solve(question, true));
        solver.setCommands(List.of("sellall", "spawn"));
        var answer = solver.solve("[ChatGames] Question: " + question, true);
        assertEquals("sellall", answer.text());
        assertEquals(Kind.TRIVIA, answer.kind());
        assertFalse(ChatLine.reply("", answer.text()).command());
        assertEquals("spawn", solver.solve("Which command teleports you to spawn?", true).text());
        solver.setCommands(List.of("spawn"));
        assertNull(solver.solve(question, true));
    }

    @Test
    void prefixedSentenceLearningKeepsTheQuestionAndLiteralRetypePayloadsSeparate() {
        String question = "What flower do bees prefer on this server?";
        solver.learn("[ChatGames] Trivia: " + question, "Dandelion");
        assertEquals("Dandelion", solver.solve(question, true).text());
        assertEquals("[VIP] Hello World", solver.solve("[ChatGames] Type: [VIP] Hello World", false).text());
        assertTrue(ChatLine.isPlayerChat("ADMIN [glso head]glso [HQ] \u00BB What block builds a nether portal?", List.of("glso")));
        assertFalse(ChatLine.isPlayerChat("[ChatGames] Question: What block builds a nether portal?", List.of("glso")));
    }

    @Test
    void reactionTimingUsesMonotonicElapsedTimeAndRevealMatchingDoesNotGuessUnknownAnswers() {
        assertEquals(1250, ChatLine.elapsedMicros(1_000_000L, 2_250_000L));
        assertEquals(0, ChatLine.elapsedMicros(1000L, 1000L));
        assertEquals(-1, ChatLine.elapsedMicros(2000L, 1000L));
        assertTrue(ChatLine.sameAnswer(" Golden   Apple ", "golden apple"));
        assertTrue(ChatLine.sameAnswer("4", "4"));
        assertFalse(ChatLine.sameAnswer("17", "4"));
        assertFalse(ChatLine.sameAnswer(null, "4"));
        assertFalse(ChatLine.sameAnswer("4", null));
    }

    @Test
    void reportRepresentativeLocalReactionBenchmarkWithoutNetworkOrArtificialDelay() {
        String[][] examples = {
            { "Chat game! First to answer wins.", "17 - 13", "4" },
            { "Unscramble the word:", "mdnaiod", "diamond" },
            { "Reverse the word:", "dnomaid", "diamond" },
            { "Fill in the missing letters:", "d_am_nd", "diamond" },
            { "First to type:", "GensHQ", "GensHQ" }
        };
        long[] samples = new long[5000];
        for (int iteration = -1000; iteration < samples.length; iteration++) {
            String[] example = examples[Math.floorMod(iteration, examples.length)];
            ChatLine.Round round = new ChatLine.Round();
            long started = System.nanoTime();
            assertNull(round.accept(example[0], 1000));
            String prompt = round.accept(example[1], 1001);
            var answer = solver.solve(prompt, true, round.genericPayload());
            long finished = System.nanoTime();
            assertNotNull(answer, prompt);
            assertEquals(example[2], answer.text(), prompt);
            if (iteration >= 0) samples[iteration] = finished - started;
        }
        java.util.Arrays.sort(samples);
        System.out.println("LOCAL_BENCHMARK samples=" + samples.length
            + " medianUs=" + samples[samples.length / 2] / 1000.0
            + " p95Us=" + samples[(int) (samples.length * 0.95)] / 1000.0
            + " maxUs=" + samples[samples.length - 1] / 1000.0
            + " scope=header+payload+local-solve network=false");
    }

    @Test
    void splitHeadersAutomaticallyClassifyTheFollowingPayload() {
        List<String[]> examples = List.of(
            new String[] { "Chat game! Unscramble the word!", "mdnaiod", "diamond", "UNSCRAMBLE" },
            new String[] { "[ChatGames] Math challenge!", "17 - 13", "4", "MATH" },
            new String[] { "Reverse the word:", "dnomaid", "diamond", "REVERSE" },
            new String[] { "Fill in the missing letters:", "d_am_nd", "diamond", "FILL" },
            new String[] { "First to type:", "GensHQ", "GensHQ", "TYPE" });
        for (String[] example : examples) {
            ChatLine.Round round = new ChatLine.Round();
            assertNull(round.accept(example[0], 1000), example[0]);
            String prompt = round.accept(example[1], 1001);
            assertFalse(round.genericPayload(), example[0]);
            var answer = solver.solve(prompt, true, round.genericPayload());
            assertNotNull(answer, prompt);
            assertEquals(example[2], answer.text(), prompt);
            assertEquals(example[3], answer.kind().name(), prompt);
        }
    }

    @Test
    void timedPublicPluginHeadersConsumeOnlyTheirOwnDecoratedWordPayload() {
        String[][] examples = {
            { "You have 30 seconds to unscramble this word!", "> mdnaiod", "diamond", "UNSCRAMBLE" },
            { "You have 45 seconds to fill in the blanks!", "> d_am_nd", "diamond", "FILL" }
        };
        for (String[] example : examples) {
            assertTrue(ChatLine.isGamePrompt(example[0]));
            ChatLine.Round round = new ChatLine.Round();
            assertNull(round.accept(example[0], 1000));
            var answer = solver.solve(round.accept(example[1], 1001), true, round.genericPayload());
            assertNotNull(answer);
            assertEquals(example[2], answer.text());
            assertEquals(example[3], answer.kind().name());
            round.clear();
            String combined = round.accept(example[0] + "\n" + example[1], 2000);
            assertEquals(example[2], solver.solve(combined, true, round.genericPayload()).text());
        }
        assertFalse(ChatLine.isGamePrompt("You have 30 seconds"));
        assertFalse(ChatLine.isGamePrompt("You have 30 seconds to send me diamonds!"));
        ChatLine.Round expired = new ChatLine.Round();
        assertNull(expired.accept(examples[0][0], 1000));
        assertNull(expired.accept("> mdnaiod", 6001));
        ChatLine.Round retype = new ChatLine.Round();
        assertNull(retype.accept("First to type:", 1000));
        assertEquals("Type: > diamond", retype.accept("> diamond", 1001));
    }

    @Test
    void framedPublicPluginAnnouncementsPreserveAnswerTextAndClassifyKnownGames() {
        String[][] examples = {
            { "| SOLVE IT", "| 5 x 5", "25", "MATH" },
            { "| REVERSE IT", "| dnomaid", "diamond", "REVERSE" },
            { "| FILL IN", "| d_am_nd", "diamond", "FILL" },
            { "| COPY IT", "| SculkShrieker", "SculkShrieker", "TYPE" },
            { "| WHO AM I?", "| I sneak up quietly, then explode loudly, leaving a giant hole!", "Creeper", "TRIVIA" }
        };
        for (String[] example : examples) {
            ChatLine.Round round = new ChatLine.Round();
            assertNull(round.accept(example[0], 1000));
            var answer = solver.solve(round.accept(example[1], 1001), true, round.genericPayload());
            assertNotNull(answer);
            assertEquals(example[2], answer.text());
            assertEquals(example[3], answer.kind().name());
            round.clear();
            String prompt = round.accept("----------\n" + example[0] + "\n" + example[1] + "\n----------", 2000);
            assertEquals(example[2], solver.solve(prompt, true, round.genericPayload()).text());
        }
        ChatLine.Round copy = new ChatLine.Round();
        assertNull(copy.accept("| COPY IT", 1000));
        assertEquals("Type: | diamond", copy.accept("| | diamond", 1001));
        copy.clear();
        assertNull(copy.accept("First to type:", 2000));
        assertEquals("Type: | diamond", copy.accept("| diamond", 2001));
        assertNull(solver.solve("| RANGE\n| 0 to 20", false));
    }

    @Test
    void simpleChatGamesPrizeWrappersSolveFactsAndMathWithoutRewritingRetypePayloads() throws Exception {
        ChatLine.Round round = new ChatLine.Round();
        String fact = round.accept("Solve What is the name of the game mode where players have infinite resources? to win a prize!", 1000);
        assertEquals("creative mode", solver.solve(fact, true).text());
        assertEquals("25", solver.solve(round.accept("Solve 5 x 5 to win a prize!", 2000), true).text());
        assertEquals("diamond", solver.solve(round.accept("Unscramble mdnaiod to win a prize!", 3000), true).text());
        assertNull(round.accept("First to type:", 4000));
        assertEquals("Type: Solve 5 x 5 to win a prize!", round.accept("Solve 5 x 5 to win a prize!", 4001));
        assertNull(solver.solve("What is the name of the flying creature that drops leather when killed?", true));
        assertNull(solver.solve("What is the name of the block that players spawn on when they first join a world?", true));
        assertEquals("steak", solver.solve("What is the name of the food that restores the most hunger points?", true).text());
        var words = new java.util.ArrayList<String>();
        try (var input = ChatGameAnswerKey.class.getResourceAsStream("/chatgames/public-plugin-words.txt")) {
            assertNotNull(input);
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))) {
                reader.lines().filter(word -> !word.startsWith("#")).forEach(words::add);
            }
        }
        ChatGameSolver expanded = new ChatGameSolver(words);
        assertEquals("cartography", expanded.solve("Unscramble: yhpargotrac", false).text());
        assertEquals("diamond tools", expanded.solve("Unscramble: sloot dnomaid", false).text());
    }

    @Test
    void combinedTypedRoundsAndTriviaQuestionsAreNotConfusedWithRevealsOrWins() {
        ChatLine.Round round = new ChatLine.Round();
        String prompt = round.accept("Chat game! Unscramble the word!\nmdnaiod\nYou have 60 seconds", 1000);
        assertEquals("diamond", solver.solve(prompt, true, round.genericPayload()).text());
        assertFalse(ChatLine.isRoundOver("Trivia: Who won the last tournament?"));
        assertFalse(ChatLine.isRoundOver("Who won the tournament?"));
        assertNull(ChatLine.revealedAnswer("Question: Which word is mentioned when the answer is revealed?"));
        assertNull(round.accept("Trivia question:", 2000));
        assertEquals("Trivia: Who won the tournament?", round.accept("Who won the tournament?", 2001));
        assertTrue(ChatLine.isRoundOver("Steve won the chat game!"));
        assertEquals("Creeper", ChatLine.revealedAnswer("The answer was Creeper."));
    }

    @Test
    void minecraftVocabularyCoversBlocksItemsBiomesDimensionsEnchantmentsAndEffects() {
        ChatGameSolver minecraft = new ChatGameSolver(List.of(
            "oak planks", "diamond sword", "cherry grove", "the nether", "overworld",
            "silk touch", "fire resistance", "custom crystal biome"));
        assertEquals("oak planks", minecraft.solve("Unscramble: sknalp kao", false).text());
        assertEquals("diamond sword", minecraft.solve("Unscramble: drows dnomaid", false).text());
        assertEquals("cherry grove", minecraft.solve("Unscramble: evorg yrrehc", false).text());
        assertEquals("the nether", minecraft.solve("Unscramble: rehten eht", false).text());
        assertEquals("overworld", minecraft.solve("Unscramble: dlrowrevo", false).text());
        assertEquals("silk touch", minecraft.solve("Unscramble: hcuot klis", false).text());
        assertEquals("fire resistance", minecraft.solve("Unscramble: ecnatsiser erif", false).text());
        assertEquals("custom crystal biome", minecraft.solve("Unscramble: em oib latsyrc motsuc", false).text());
        assertNull(minecraft.solve("Unscramble: zzqqxx", false));
    }

    @Test
    void decimalAndSlashRevealsRemainExact() {
        assertEquals("2.5", ChatLine.revealedAnswer("The answer was 2.5."));
        assertEquals("-2.75", ChatLine.revealedAnswer("The answer was '-2.75'."));
        assertEquals("-2.75", ChatLine.revealedAnswer("The answer was -2.75."));
        assertEquals("/spawn", ChatLine.revealedAnswer("The word was '/spawn'."));
        assertEquals("diamond", ChatLine.revealedAnswer("The answer was diamond. Better luck next time!"));
    }

    @Test
    void aiRequestsCanBeCancelledAndSlowRepliesCannotEscapeTheDeadline() throws Exception {
        var received = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/slow", exchange -> {
            received.countDown();
            try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                exchange.sendResponseHeaders(204, -1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException disconnected) {
                assertTrue(release.getCount() == 0 || received.getCount() == 0);
            } finally {
                exchange.close();
            }
        });
        server.start();
        CompletableFuture<String> cancelled = null;
        CompletableFuture<String> timed = null;
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/slow";
            cancelled = AiRouter.ask(endpoint, "", "test", "first round", 5);
            timed = AiRouter.ask(endpoint, "", "test", "next round", 1);
            assertTrue(received.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(cancelled.cancel(true));
            assertTrue(cancelled.isCancelled());
            assertNull(timed.handle((answer, error) -> answer).get(3, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(timed.isDone());
        } finally {
            if (cancelled != null) cancelled.cancel(true);
            if (timed != null) timed.cancel(true);
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test
    void slashScramblesUseOnlyCurrentServerCommandsAndDoNotBecomeReplyCommands() {
        solver.setCommands(List.of("spawn", "home", "minecraft:warp", "home2"));
        assertEquals("/spawn", solver.solve("Unscramble: /pnaws", false).text());
        assertEquals("/minecraft:warp", solver.solve("Unscramble: /minecraft:prwa", false).text());
        assertEquals("/home2", solver.solve("Unscramble: /mohe2", false).text());
        assertNull(solver.solve("Unscramble: /mohe3", false));
        assertNull(ChatLine.replyCommand("Unscramble: /answer"));
        assertEquals("/answer", ChatLine.replyCommand("Use /answer to submit your answer"));
        assertNull(ChatLine.replyCommand("Use /op to submit your answer"));
        solver.setCommands(List.of("home"));
        assertNull(solver.solve("Unscramble: /pnaws", false));
    }

    @Test
    void ambiguousScramblesAbstainUntilAValidRevealDisambiguatesThem() {
        ChatGameSolver ambiguous = new ChatGameSolver(List.of("listen", "silent", "enlist"));
        assertNull(ambiguous.solve("Unscramble: tinsel", false));
        ambiguous.learn("Unscramble: tinsel", "silent");
        assertEquals("silent", ambiguous.solve("Unscramble: tinsel", false).text());
        ambiguous.learn("Unscramble: tinsel", "diamond");
        assertNull(ambiguous.solve("Unscramble: tinsel", false));
        ambiguous.setCommands(List.of("stop", "post"));
        assertNull(ambiguous.solve("Unscramble: /psto", false));
    }

    @Test
    void unquotedPhrasesAndExplicitRetypeInstructionsAreSupported() {
        assertEquals("golden apple", solver.solve("Unscramble: pelpa nolged", false).text());
        assertEquals("GensHQ", solver.solve("First to type GensHQ wins!", false).text());
        assertEquals("Golden Apple", solver.solve("Type: Golden Apple", false).text());
        assertEquals("/spawn", solver.solve("Type: /spawn", false).text());
    }

    @Test
    void fastRepliesHaveNoArtificialDelayAndSlashAnswersAreNotCommands() {
        assertEquals(0, ChatLine.replyDelay(false, "17 - 13", "4", 3, 20, 499));
        assertEquals(1000, ChatLine.replyDelay(true, "17 - 13", "4", 3, 1, 499));
        assertFalse(ChatLine.reply("", "/spawn").command());
        assertEquals("spawn", ChatLine.reply("", "/spawn").text());
        assertTrue(ChatLine.reply("/answer", "/spawn").command());
        assertEquals("/answer spawn", ChatLine.reply("/answer", "/spawn").text());
        assertFalse(ChatLine.reply("Answer:", "4").command());
    }

    @Test
    void ordinaryChatDoesNotInvalidateAnUnrelatedRoundRevision() {
        ChatLine.Round round = new ChatLine.Round();
        int revision = round.revision();
        assertEquals("Welcome", round.accept("Welcome", 1_000_000));
        assertEquals(revision, round.revision());
        assertNull(round.accept("Chat game! First to answer wins.", 1_000_001));
        assertTrue(round.awaitingPayload(1_000_002));
        assertFalse(round.awaitingPayload(1_005_002));
    }

    @Test
    void aiResponseUsesStructuredJsonAndDoesNotReadDecoyContent() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/completion", exchange -> {
            byte[] body = ("{\"content\":\"decoy\",\"choices\":[{\"message\":{\"content\":\"\\u0047ensHQ\\nwhy\"}}]}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            String reply = AiRouter.ask("http://127.0.0.1:" + server.getAddress().getPort() + "/completion",
                "", "test", "Question", 3).get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("GensHQ\nwhy", reply);
            assertNull(AiRouter.sanitize(reply));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void splitAndCombinedRoundsUsePayloadRatherThanTheRepeatedHeader() {
        ChatLine.Round round = new ChatLine.Round();
        assertNull(round.accept("Chat game! First to answer wins.", 1000));
        String math = round.accept("17 - 13", 1001);
        assertTrue(round.genericPayload());
        assertEquals("4", solver.solve(math, true, round.genericPayload()).text());
        assertNull(round.accept("You have 60 seconds.", 1002));
        assertNull(round.accept("17 - 13", 1003));
        assertNull(round.accept("Chat game! First to answer wins.", 1100));
        String retype = round.accept("GensHQ", 1101);
        assertEquals("GensHQ", retype);
        assertTrue(round.genericPayload());
        assertEquals(Kind.TYPE, solver.solve(retype, true, round.genericPayload()).kind());
        assertEquals("GensHQ", solver.solve(retype, true, round.genericPayload()).text());
        assertNull(round.accept("GensHQ", 1102));
        assertEquals("17 - 13", round.accept(
            "[Chat game] First to answer wins.\n17 - 13\nYou have 60 seconds", 1200));
        assertTrue(round.genericPayload());
    }

    @Test
    void expiredOrClearedHeadersCannotClaimUnrelatedPayloads() {
        ChatLine.Round round = new ChatLine.Round();
        assertNull(round.accept("Chat game! First to answer wins.", 1000));
        assertNull(round.accept("GensHQ", 6001));
        assertFalse(round.genericPayload());
        assertNull(round.accept("Chat game! First to answer wins.", 7000));
        round.clear();
        assertEquals("GensHQ", round.accept("GensHQ", 7001));
        assertFalse(round.genericPayload());
        assertNull(round.accept("------\nYou have 60 seconds!", 7002));
    }

    @Test
    void genericRoundHeadersNeverBecomeReusableTriviaKeys() {
        String header = "\u25B6 Chat game! First to answer wins.";
        solver.learn(header, "4");
        solver.learn("chat game first to answer wins", "GensHQ");
        assertTrue(solver.triviaSnapshot().isEmpty());
        assertNull(solver.solve(header, true));
        assertNull(solver.solve("Chat game! First to answer wins.", false));
    }

    @Test
    void spacedSubtractionOverridesCorruptedTriviaAcrossTheWholeDisplayedRound() {
        String prompt = "[Chat game] First to answer wins.\n17 - 13\nYou have 60 seconds";
        solver.learn(prompt, "17");
        var answer = solver.solve(prompt, true);
        assertNotNull(answer);
        assertEquals(Kind.MATH, answer.kind());
        assertEquals("4", answer.text());
        assertEquals("4", solver.solve("17 - 13", false).text());
        assertEquals("4", solver.solve("17\u00A0-\u00A013", false).text());
        assertEquals("4", solver.solve("17\u202F\u2212\u202F13", false).text());
    }

    private final ChatGameSolver solver = new ChatGameSolver(List.of(
        "diamond", "emerald", "pickaxe", "creeper", "netherite", "golden apple", "beacon", "anvil", "naive"));

    @Test
    void solvesCommonMathPrompts() {
        assertEquals("33", solver.solve("[ChatGames] Solve: 12 + 7 * 3", false).text());
        assertEquals("30", solver.solve("What is 5 x 6?", false).text());
        assertEquals("2.5", solver.solve("Calculate 10 / 4 first to answer wins!", false).text());
        assertEquals("2.5", solver.solve("Calculate 10/4", false).text());
        assertEquals("3", solver.solve("Math: 12/4", false).text());
        assertNull(ChatGameSolver.extractMath("season started 2026-10-02"));
        assertNull(ChatGameSolver.extractMath("event at 10:30 on 12/4"));
        assertEquals(Kind.MATH, solver.solve("Solve 2^10", false).kind());
    }

    @Test
    void neverReadsWordsDatesOrTimesAsMath() {
        assertNull(ChatGameSolver.extractMath("max 5 players in the box"));
        assertNull(ChatGameSolver.extractMath("event at 10:30 on 12/4"));
        assertNull(ChatGameSolver.extractMath("season started 2026-10-02"));
        assertEquals("3*4", ChatGameSolver.extractMath("what is 3x4").replace(" ", ""));
    }

    @Test
    void unscramblesAndKeepsCase() {
        assertEquals("diamond", solver.solve("Unscramble the word: mdnaiod", false).text());
        assertEquals("CREEPER", solver.solve("UNSCRAMBLE: REPCERE", false).text());
        assertEquals("golden apple", solver.solve("Unscramble 'pelpa nolged'", false).text());
        assertNull(solver.solve("Unscramble: zzqqxx", false), "unknown word stays silent");
    }

    @Test
    void scrambleThatIsAlsoAWordReturnsTheOther() {
        ChatGameSolver s = new ChatGameSolver(List.of("listen", "silent"));
        assertEquals("listen", s.unscramble("silent"), "the scramble itself is never the answer");
        assertEquals("anvil", solver.unscramble("naliv"));
    }

    @Test
    void retypeReverseAndFill() {
        assertEquals("Pumpkin Pie", solver.solve("First to type \"Pumpkin Pie\" wins!", false).text());
        assertEquals("diamond", solver.solve("Reverse: dnomaid", false).text());
        assertEquals("emerald", solver.solve("Fill in the missing letters: e_er_ld", false).text());
        assertNull(solver.fill("___"), "too many matches = ambiguous");
    }

    @Test
    void typeWithMathIsMathNotRetype() {
        assertEquals("10", solver.solve("Type the answer to 5+5", false).text());
    }

    @Test
    void wordProblemsOnlyWhenAllowed() {
        assertNull(solver.solve("What is five plus three", false));
        assertEquals("8", solver.solve("What is five plus three", true).text());
    }

    @Test
    void learnsTriviaFromReveals() {
        String q = "Trivia: What mob drops gunpowder?";
        assertNull(solver.solve(q, false));
        solver.learn(q, ChatLine.revealedAnswer("Nobody got it! The answer was Creeper."));
        ChatGameSolver.Answer a = solver.solve("TRIVIA - what mob drops gunpowder", false);
        assertEquals(Kind.TRIVIA, a.kind());
        assertEquals("Creeper", a.text());
    }

    @Test
    void revealAndRoundEndParsing() {
        assertEquals("diamond", ChatLine.revealedAnswer("Steve won! The word was 'diamond'."));
        assertEquals("42", ChatLine.revealedAnswer("The correct answer is: 42"));
        assertNull(ChatLine.revealedAnswer("Steve joined the game"));
        assertTrue(ChatLine.isRoundOver("Nobody answered in time!"));
        assertTrue(ChatLine.isRoundOver("Steve won the chat game!"));
        assertFalse(ChatLine.isRoundOver("First to unscramble 'mdnaoid' wins $500!"), "a prompt promising a win isn't a win");
    }

    @Test
    void playerChatIsIgnoredSoNobodyCanBaitReplies() {
        List<String> online = List.of("Steve", "Alex_99");
        assertTrue(ChatLine.isPlayerChat("<Steve> unscramble: mdnaoid", online));
        assertTrue(ChatLine.isPlayerChat("[VIP] Alex_99: what is 5+5", online));
        assertTrue(ChatLine.isPlayerChat("Steve \u00BB first to type 'lol' wins", online));
        assertFalse(ChatLine.isPlayerChat("[ChatGames] Unscramble: mdnaoid", online));
        assertFalse(ChatLine.isPlayerChat("Games: what is 5+5", online), "the prefix isn't an online player");
    }

    @Test
    void aiRepliesAreTrimmedButKeepWords() {
        assertEquals("diamond", AiRouter.sanitize("The answer is: \"diamond\"."));
        assertNull(AiRouter.sanitize("The answer is: \"diamond\".\nBecause..."));
        assertEquals("42", AiRouter.sanitize("42"));
        assertEquals("Golden Apple", AiRouter.sanitize("Golden Apple"));
        assertNull(AiRouter.sanitize("   "));
    }
}
