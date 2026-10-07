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
import com.autism.seedcracker.chatbot.ChatBotPolicy;
import com.autism.seedcracker.chatbot.ChatBotPolicy.Action;
import com.autism.seedcracker.chatbot.ChatBotPolicy.Rule;
import com.autism.seedcracker.motion.Motion;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringListSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;

/**
 * Keyword chat bot. Watches signed player chat for keywords and answers with your reply text.
 * Placeholders ({x} {y} {z} {dim} {health} {ping} {time} {sender}) and actions (share coords,
 * walk to the sender, stop) are opt-in and owner-only; it never runs commands, and every reply
 * goes through {@link ChatBotPolicy#sanitizeOutgoing} so nothing can make it pay, teleport, or leak.
 */
public final class ChatBotModule extends Module {

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
        .description("Owners can make you path to them (built-in GoTo, never breaks blocks).").group("Actions"));
    private final BoolSetting allowStop = add(new BoolSetting("allow-stop", "Allow: stop", true)
        .description("Owners can stop any GoTo trip.").group("Actions"));
    private final BoolSetting allowHealth = add(new BoolSetting("allow-health", "Allow: health", false).group("Actions"));
    private final BoolSetting privateReplies = add(new BoolSetting("private", "Reply privately", true)
        .description("Whisper replies to the sender (/msg) instead of public chat. Coords are ALWAYS private.").group("Reply"));
    private final IntSetting globalCooldown = add(new IntSetting("cooldown", "Cooldown (s)", 4, 0, 120, 1)
        .description("Minimum time between any two replies (owners skip this).").group("Reply"));
    private final IntSetting perSender = add(new IntSetting("per-sender", "Per-player cooldown (s)", 20, 0, 600, 5)
        .group("Reply"));
    private final IntSetting maxPerMinute = add(new IntSetting("max-per-minute", "Max replies / minute", 6, 1, 30, 1)
        .description("Hard cap for everyone, so a spam loop can never flood chat or get you muted.").group("Reply"));
    private final BoolSetting humanDelay = add(new BoolSetting("human-delay", "Human typing delay", true).group("Reply"));
    private final BoolSetting log = add(new BoolSetting("log", "Show decisions", false)
        .description("Print why each keyword hit was answered or ignored (only you see it).").group("Reply"));

    private ChatBotPolicy policy;
    private int rulesHash;

    public ChatBotModule() {
        super(SeedcrackerAddon.ID + ":chat-bot", "Chat Bot",
            "Replies to keywords in chat. Coords/movement are opt-in, owner-only, and can't run commands.");
    }

    @Override
    public void onEnable() {
        policy = null;
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
        if (mc.player == null || mc.getConnection() == null || text == null || senderId == null) return;
        // Our own echoed messages come back as signed chat too; never treat them as input (reply loops).
        if (senderId.equals(mc.player.getUUID())) return;
        PlayerInfo info = mc.getConnection().getPlayerInfo(senderId);
        if (info == null) return;
        String sender = info.getProfile().name();
        String self = mc.player.getGameProfile().name();
        ChatBotPolicy p = policy();
        long now = System.currentTimeMillis();
        ChatBotPolicy.Decision d = p.decide(sender, true, self, text, now);
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
        p.record(sender, now);
        runAction(mc, d.action(), sender);
        // Coords never go to public chat, whatever the setting.
        boolean whisper = privateReplies.get() || d.action() == Action.COORDS;
        send(mc, sender, reply, whisper);
        if (log.get()) AutismClientMessaging.sendPrefixed("\u00a77[ChatBot] " + d.action() + " -> " + sender);
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
                if (!Motion.follow(mc, sender)) AutismClientMessaging.sendPrefixed("\u00a7e[ChatBot] " + sender + " isn't in render distance.");
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
        long delay = humanDelay.get() ? 600 + reply.length() * 90L + ThreadLocalRandom.current().nextLong(600) : 0;
        // The connection we answered on: a reply queued just before a server switch must not land on the new server.
        var conn = mc.getConnection();
        CompletableFuture.delayedExecutor(Math.min(delay, 6000), TimeUnit.MILLISECONDS).execute(() -> mc.execute(() -> {
            if (!isEnabled() || mc.getConnection() == null || mc.getConnection() != conn) return;
            // The target name comes from the server's player list (letters/digits/_ only), never from chat text.
            if (whisper) {
                if (!to.matches("[A-Za-z0-9_]{1,16}")) return;
                mc.getConnection().sendCommand("msg " + to + " " + reply);
            } else {
                mc.getConnection().sendChat(reply);
            }
        }));
    }
}
