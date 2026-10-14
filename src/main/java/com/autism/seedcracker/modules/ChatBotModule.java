package com.autism.seedcracker.modules;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.chatbot.ChatBotConversation;
import com.autism.seedcracker.chatbot.V4ChatProvider;
import com.autism.seedcracker.chatbot.ChatBotPolicy;
import com.autism.seedcracker.chatbot.ChatBotPolicy.Action;
import com.autism.seedcracker.chatbot.ChatBotPolicy.Rule;
import com.autism.seedcracker.chatgames.AiRouter;
import com.autism.seedcracker.motion.Motion;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringListSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;

/**
 * Watches signed player chat for keyword rules or addressed, per-player AI conversations.
 * Placeholders ({x} {y} {z} {dim} {health} {ping} {time} {sender}) and actions (share coords,
 * walk to the sender, stop) are opt-in and owner-only; it never runs commands, and every reply
 * is sanitized. AI replies are plain text and never enter the owner-action dispatcher.
 */
public final class ChatBotModule extends Module {

    public enum ConversationMode { RULES, AI, LOCAL_AI, V4_AI }

    private final EnumSetting<ConversationMode> mode = add(new EnumSetting<>("mode", "Conversation mode", V4ChatProvider.available() ? ConversationMode.V4_AI : ConversationMode.RULES, ConversationMode.values())
        .description("AI/V4_AI use the V4 AI screen on V4. Older clients use separate AI settings. RULES uses canned replies; LOCAL_AI uses loopback.").group("Conversation"));
    private final StringSetting trigger = add(new StringSetting("ai-trigger", "Conversation trigger", "gemini")
        .description("Address this trigger or your player name to ask the AI. Each player has separate short-lived history.")
        .group("Conversation").visibleWhen(() -> mode.get() != ConversationMode.RULES));
    private final IntSetting historyTurns = add(new IntSetting("ai-history", "Remembered exchanges", 12, 1, 20, 1)
        .description("In-memory only; cleared on disable, connection/world changes and five minutes of inactivity.")
        .group("Conversation").visibleWhen(() -> mode.get() != ConversationMode.RULES));
    private final StringSetting aiEndpoint = add(new StringSetting("ai-endpoint", "AI endpoint", "https://api.openai.com/v1/chat/completions")
        .group("AI").visibleWhen(() -> mode.get() == ConversationMode.AI && !V4ChatProvider.available()));
    private final StringSetting aiModel = add(new StringSetting("ai-model", "AI model", "gpt-4o-mini")
        .group("AI").visibleWhen(() -> mode.get() == ConversationMode.AI && !V4ChatProvider.available()));
    private final StringSetting aiKey = add(new StringSetting("ai-key", "AI API key", "")
        .description("Enter directly here. Addressed messages and their recent history are sent to the configured provider.")
        .group("AI").visibleWhen(() -> mode.get() == ConversationMode.AI && !V4ChatProvider.available()));
    private final StringSetting localEndpoint = add(new StringSetting("local-ai-endpoint", "Local AI endpoint", "http://127.0.0.1:11434/v1/chat/completions")
        .description("Loopback only, no redirects or API key.").group("AI").visibleWhen(() -> mode.get() == ConversationMode.LOCAL_AI));
    private final StringSetting localModel = add(new StringSetting("local-ai-model", "Installed local model", "")
        .group("AI").visibleWhen(() -> mode.get() == ConversationMode.LOCAL_AI));
    private final IntSetting aiDeadline = add(new IntSetting("ai-deadline", "AI deadline (s)", 30, 5, 60, 1)
        .group("AI").visibleWhen(() -> mode.get() != ConversationMode.RULES));

    private final StringListSetting rules = add(new StringListSetting("rules", "Rules (keyword => reply)",
            List.of("hello => hi {sender}!", "!ping => pong ({ping}ms)", "!coords => {x} {y} {z} ({dim}) | coords",
                "!come => on my way | come", "!stop => stopping | stop"))
        .description("One per line: keyword => reply. Add '| coords', '| come', '| stop', '| health' or '| time' after the reply "
            + "to make it an action (each needs its toggle below and an allowed sender).")
        .group("Rules"));
    private final StringListSetting owners = add(new StringListSetting("owners", "Owners", List.of())
        .description("Exact player names allowed to use owner actions (coords, come, stop). Checked against the signed sender, not chat text.")
        .group("Who"));
    private final StringListSetting trusted = add(new StringListSetting("trusted", "Trusted", List.of())
        .description("Players allowed to ask for health/hunger.").group("Who"));
    private final BoolSetting publicReplies = add(new BoolSetting("public", "Answer everyone", true)
        .description("Plain keyword replies for anyone. Off = only owners/trusted get any reply.").group("Who"));

    private final BoolSetting allowCoords = add(new BoolSetting("allow-coords", "Allow: share coords", false)
        .description("Owners can get your coordinates. They're whispered privately if 'Reply privately' is on.").group("Actions"));
    private final BoolSetting allowCome = add(new BoolSetting("allow-come", "Allow: walk to owner", false)
        .description("Owners can start a built-in GoTo trip to their nearby position, subject to GoTo settings.").group("Actions"));
    private final BoolSetting allowStop = add(new BoolSetting("allow-stop", "Allow: stop", true)
        .description("Owners can stop any GoTo trip.").group("Actions"));
    private final BoolSetting allowHealth = add(new BoolSetting("allow-health", "Allow: health", false).group("Actions"));
    private final BoolSetting privateReplies = add(new BoolSetting("private", "Reply privately", true)
        .description("Whisper replies to the sender (/msg) instead of public chat. Coords are ALWAYS private.").group("Reply"));
    private final IntSetting globalCooldown = add(new IntSetting("cooldown", "Cooldown (s)", 4, 0, 120, 1)
        .description("Minimum time between any two replies (owners skip this).").group("Reply"));
    private final BoolSetting showAdvanced = add(new BoolSetting("advanced", "Show advanced", false)
        .description("Reveal per-player cooldown, rate cap, typing delay and debug log.").group("Reply"));
    private final IntSetting perSender = add(new IntSetting("per-sender", "Per-player cooldown (s)", 20, 0, 600, 5)
        .group("Reply").visibleWhen(showAdvanced::get));
    private final IntSetting maxPerMinute = add(new IntSetting("max-per-minute", "Max replies / minute", 6, 1, 30, 1)
        .description("Hard cap for everyone, so a spam loop can never flood chat or get you muted.").group("Reply").visibleWhen(showAdvanced::get));
    private final BoolSetting humanDelay = add(new BoolSetting("human-delay", "Human typing delay", true).group("Reply").visibleWhen(showAdvanced::get));
    private final BoolSetting log = add(new BoolSetting("log", "Show decisions", false)
        .description("Print why each keyword hit was answered or ignored (only you see it).").group("Reply").visibleWhen(showAdvanced::get));

    private ChatBotPolicy policy;
    private int rulesHash;
    private final ChatBotConversation conversation = new ChatBotConversation();
    private CompletableFuture<String> pending;
    private ChatBotConversation.Request activeRequest;
    private Object conversationConnection;
    private Object conversationWorld;
    private List<Object> conversationSettings = List.of();
    private long generation;

    public ChatBotModule() {
        super(SeedcrackerAddon.ID + ":chat-bot", "Chat Bot",
            "Keyword replies or per-player AI conversations. Coordinates and movement remain opt-in and owner-only.");
    }

    @Override
    public void onEnable() {
        policy = null;
        clearConversation();
    }

    @Override
    public void onDisable() {
        clearConversation();
    }

    @Override
    public void tick() {
        syncConversation(Minecraft.getInstance());
    }

    private List<Object> conversationSettings() {
        return List.of(mode.get(), trigger.get(), historyTurns.get(), aiEndpoint.get(), aiModel.get(), aiKey.get(),
            localEndpoint.get(), localModel.get(), aiDeadline.get(), privateReplies.get(), humanDelay.get(),
            List.copyOf(rules.get()), List.copyOf(owners.get()), List.copyOf(trusted.get()), publicReplies.get(),
            allowCoords.get(), allowCome.get(), allowStop.get(), allowHealth.get(), globalCooldown.get(), perSender.get(), maxPerMinute.get(),
            usesV4() ? V4ChatProvider.revision(v4SettingsPath()) : -1L);
    }

    private void syncConversation(Minecraft mc) {
        List<Object> settings = conversationSettings();
        if (mc.getConnection() != conversationConnection || mc.level != conversationWorld || !settings.equals(conversationSettings)) {
            clearConversation();
            conversationConnection = mc.getConnection();
            conversationWorld = mc.level;
            conversationSettings = settings;
        }
    }

    private static java.nio.file.Path v4SettingsPath() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("autism/ai/settings.json");
    }

    private boolean usesV4() {
        return mode.get() == ConversationMode.V4_AI || mode.get() == ConversationMode.AI && V4ChatProvider.available();
    }

    private void clearConversation() {
        generation++;
        if (pending != null) pending.cancel(true);
        pending = null;
        activeRequest = null;
        conversation.clear();
        conversationConnection = null;
        conversationWorld = null;
        conversationSettings = List.of();
    }

    private ChatBotPolicy policy() {
        int h = rules.get().hashCode() * 31 + owners.get().hashCode() * 17 + trusted.get().hashCode()
            + (allowCoords.get() ? 1 : 0) + (allowCome.get() ? 2 : 0) + (allowStop.get() ? 4 : 0) + (allowHealth.get() ? 8 : 0)
            + (publicReplies.get() ? 16 : 0) + globalCooldown.get() * 131 + perSender.get() * 137 + maxPerMinute.get() * 139;
        if (policy != null && h == rulesHash) return policy;
        rulesHash = h;
        Set<Action> enabled = EnumSet.of(Action.PING, Action.TIME);
        if (publicReplies.get()) enabled.add(Action.REPLY);
        if (allowCoords.get()) enabled.add(Action.COORDS);
        if (allowCome.get()) enabled.add(Action.GOTO_ME);
        if (allowStop.get()) enabled.add(Action.STOP);
        if (allowHealth.get()) enabled.add(Action.HEALTH);
        policy = new ChatBotPolicy(new HashSet<>(owners.get()), new HashSet<>(trusted.get()), enabled, parseRules(rules.get()),
            globalCooldown.get() * 1000L, perSender.get() * 1000L, maxPerMinute.get());
        return policy;
    }

    static List<Rule> parseRules(List<String> lines) {
        List<Rule> out = new ArrayList<>();
        for (String line : lines) {
            int arrow = line.indexOf("=>");
            if (arrow <= 0) continue;
            String kw = line.substring(0, arrow).trim();
            String rest = line.substring(arrow + 2).trim();
            Action a = Action.REPLY;
            int bar = rest.lastIndexOf('|');
            if (bar >= 0) {
                String tag = rest.substring(bar + 1).trim().toLowerCase(Locale.ROOT);
                Action parsed = switch (tag) {
                    case "coords" -> Action.COORDS;
                    case "come" -> Action.GOTO_ME;
                    case "stop" -> Action.STOP;
                    case "health" -> Action.HEALTH;
                    case "time" -> Action.TIME;
                    case "ping" -> Action.PING;
                    default -> null;
                };
                if (parsed != null) {
                    a = parsed;
                    rest = rest.substring(0, bar).trim();
                }
            }
            if (!kw.isEmpty()) out.add(new Rule(kw, rest, a));
        }
        return out;
    }

    @Override
    public boolean onPacketReceive(Packet<?> packet) {
        // Signed player chat carries the sender's UUID: the only sender identity a player can't fake by typing.
        if (!(packet instanceof ClientboundPlayerChatPacket chat)) return false;
        String text = chat.body() == null ? null : chat.body().content();
        java.util.UUID sender = chat.sender();
        Minecraft.getInstance().execute(() -> handle(sender, text));
        return false;
    }

    private void handle(java.util.UUID senderId, String text) {
        Minecraft mc = Minecraft.getInstance();
        if (!isEnabled() || mc.player == null || mc.getConnection() == null || text == null || senderId == null) return;
        syncConversation(mc);
        // Our own echoed messages come back as signed chat too; never treat them as input (reply loops).
        if (senderId.equals(mc.player.getUUID())) return;
        PlayerInfo info = mc.getConnection().getPlayerInfo(senderId);
        if (info == null) return;
        String sender = info.getProfile().name();
        String self = mc.player.getGameProfile().name();
        ChatBotPolicy p = policy();
        long now = System.currentTimeMillis();
        ChatBotPolicy.Decision d = p.decide(sender, true, self, text, now);
        if (mode.get() != ConversationMode.RULES && ("no keyword".equals(d.why()) || d.allowed() && d.action() == Action.REPLY)
            && ChatBotConversation.addressed(text, self, trigger.get())) {
            askConversation(mc, senderId, sender, self, text, p, now);
            return;
        }
        if (!d.allowed()) {
            if (log.get() && !"no keyword".equals(d.why()) && !"self".equals(d.why())) {
                AutismClientMessaging.sendPrefixed("\u00a77[ChatBot] ignored " + sender + ": " + d.why());
            }
            return;
        }
        String reply = ChatBotPolicy.fill(d.reply(), p.visible(values(mc, sender, info), sender, true));
        reply = ChatBotPolicy.sanitizeOutgoing(reply);
        if (reply == null) {
            AutismClientMessaging.sendPrefixed("\u00a7e[ChatBot] blocked an unsafe reply to " + sender + " (check your rule text).");
            return;
        }
        generation++;
        if (pending != null) pending.cancel(true);
        pending = null;
        activeRequest = null;
        p.record(sender, now);
        runAction(mc, d.action(), sender);
        // Coords never go to public chat, whatever the setting.
        boolean whisper = privateReplies.get() || d.action() == Action.COORDS;
        send(mc, sender, reply, whisper);
        if (log.get()) AutismClientMessaging.sendPrefixed("\u00a77[ChatBot] " + d.action() + " -> " + sender);
    }

    private void askConversation(Minecraft mc, java.util.UUID senderId, String sender, String self, String text, ChatBotPolicy policy, long now) {
        ChatBotPolicy.Decision permission = policy.converse(sender, true, self, now);
        if (!permission.allowed() || text.length() > 512 || ChatBotPolicy.sanitizeConversation(text) == null
            || pending != null && activeRequest != null && !activeRequest.sender().equals(senderId)) return;
        policy.record(sender, now);
        boolean local = mode.get() == ConversationMode.LOCAL_AI;
        V4ChatProvider.Profile profile = usesV4() ? V4ChatProvider.read(v4SettingsPath()) : null;
        if (usesV4() && profile == null) {
            AutismClientMessaging.sendPrefixed("\u00a7e[ChatBot] V4 AI profile unavailable. Open V4's AI screen and select a language-model provider.");
            return;
        }
        if (profile != null && profile.builtIn()) {
            AutismClientMessaging.sendPrefixed("\u00a7e[ChatBot] V4's built-in assistant handles local game intents, not Q&A. Select a language-model provider in V4's AI screen.");
            return;
        }
        String model = profile != null ? profile.model() : local ? localModel.get() : aiModel.get();
        String endpoint = profile != null ? profile.endpoint() : local ? localEndpoint.get() : aiEndpoint.get();
        if (profile != null && AiRouter.isLocalEndpoint(endpoint)) local = true;
        if (model.isBlank() || endpoint.isBlank() || local && !AiRouter.isLocalEndpoint(endpoint)) {
            AutismClientMessaging.sendPrefixed("\u00a7e[ChatBot] Set a model and a valid chat-completions endpoint in AI settings.");
            return;
        }
        var request = conversation.begin(senderId, text, historyTurns.get(), now);
        if (request == null) return;
        generation++;
        long token = generation;
        if (pending != null) pending.cancel(true);
        activeRequest = request;
        var future = profile != null ? V4ChatProvider.converse(v4SettingsPath(), request.turns(), aiDeadline.get())
            : AiRouter.converse(endpoint, local ? "" : aiKey.get(), model, request.turns(), aiDeadline.get(), local);
        pending = future;
        future.whenComplete((raw, error) -> mc.execute(() -> {
            if (!isEnabled()) return;
            syncConversation(mc);
            if (token != generation || pending != future || !conversation.current(request) || mc.player == null) return;
            pending = null;
            activeRequest = null;
            PlayerInfo current = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(senderId);
            if (current == null || !sender.equals(current.getProfile().name())) return;
            String reply = error == null ? ChatBotPolicy.sanitizeConversation(raw) : null;
            if (reply == null) {
                AutismClientMessaging.sendPrefixed("\u00a7e[ChatBot] AI request failed or returned an unsafe reply; check model, endpoint and credentials.");
                return;
            }
            send(mc, sender, reply, privateReplies.get(), request);
        }));
    }

    private static Map<String, String> values(Minecraft mc, String sender, PlayerInfo senderInfo) {
        Map<String, String> v = new HashMap<>();
        v.put("sender", sender);
        v.put("name", mc.player.getGameProfile().name());
        v.put("x", Integer.toString(mc.player.getBlockX()));
        v.put("y", Integer.toString(mc.player.getBlockY()));
        v.put("z", Integer.toString(mc.player.getBlockZ()));
        v.put("dim", mc.level == null ? "?" : mc.level.dimension().identifier().getPath());
        v.put("health", Integer.toString(Math.round(mc.player.getHealth())));
        PlayerInfo me = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        v.put("ping", me == null ? "?" : Integer.toString(me.getLatency()));
        v.put("time", java.time.LocalTime.now().withNano(0).withSecond(0).toString());
        return v;
    }

    private void runAction(Minecraft mc, Action a, String sender) {
        switch (a) {
            case GOTO_ME -> {
                if (!com.autism.seedcracker.motion.GoToModule.ensureEnabled()) return;
                if (!Motion.goToPlayer(mc, sender)) AutismClientMessaging.sendPrefixed("\u00a7e[ChatBot] " + sender + " isn't in render distance.");
                else AutismClientMessaging.sendPrefixed("\u00a7a[ChatBot] walking to " + sender + " (.goto stop to cancel)");
            }
            case STOP -> {
                com.autism.seedcracker.motion.MineTask.stop(mc);
                Motion.stop(mc);
            }
            default -> { }
        }
    }

    private void send(Minecraft mc, String to, String reply, boolean whisper) {
        send(mc, to, reply, whisper, null);
    }

    private void send(Minecraft mc, String to, String reply, boolean whisper, ChatBotConversation.Request request) {
        long delay = humanDelay.get() ? 600 + reply.length() * 90L + ThreadLocalRandom.current().nextLong(600) : 0;
        // The connection we answered on: a reply queued just before a server switch must not land on the new server.
        var conn = mc.getConnection();
        var world = mc.level;
        long token = generation;
        List<Object> settings = conversationSettings();
        CompletableFuture.delayedExecutor(Math.min(delay, 6000), TimeUnit.MILLISECONDS).execute(() -> mc.execute(() -> {
            if (!isEnabled() || mc.getConnection() == null || mc.getConnection() != conn || mc.level != world
                || generation != token || !settings.equals(conversationSettings())) return;
            if (whisper && !to.matches("[A-Za-z0-9_]{1,16}")) return;
            if (request != null && !conversation.complete(request, reply, System.currentTimeMillis())) return;
            // The target name comes from the server's player list (letters/digits/_ only), never from chat text.
            if (whisper) {
                mc.getConnection().sendCommand("msg " + to + " " + reply);
            } else {
                mc.getConnection().sendChat(reply);
            }
        }));
    }
}
