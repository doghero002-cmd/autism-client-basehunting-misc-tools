package com.autism.seedcracker.chatbot;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.autism.seedcracker.chatbot.ChatBotPolicy.Action;
import com.autism.seedcracker.chatbot.ChatBotPolicy.Rule;

import static org.junit.jupiter.api.Assertions.*;

class ChatBotPolicyTest {

    private static ChatBotPolicy policy(Set<Action> enabled) {
        return new ChatBotPolicy(Set.of("Boss"), Set.of("Friend"), enabled,
            List.of(new Rule("hello", "hi {sender}!", Action.REPLY), new Rule("coords", "I'm at {x} {y} {z}", Action.COORDS),
                new Rule("come", "on my way", Action.GOTO_ME), new Rule("hp", "{health} hp", Action.HEALTH)),
            2000, 10_000, 6);
    }

    @Test
    void strangersOnlyGetPlainReplies() {
        ChatBotPolicy p = policy(EnumSet.allOf(Action.class));
        assertTrue(p.decide("Rando", true, "Me", "hello there", 0).allowed());
        assertFalse(p.decide("Rando", true, "Me", "coords pls", 100_000).allowed(), "coords are owner-only");
        assertFalse(p.decide("Rando", true, "Me", "come here", 200_000).allowed(), "movement is owner-only");
        assertFalse(p.decide("Friend", true, "Me", "coords", 300_000).allowed(), "trusted isn't owner");
        assertTrue(p.decide("Friend", true, "Me", "hp?", 400_000).allowed());
    }

    @Test
    void ownerNameInUnsignedTextGetsNoTrust() {
        ChatBotPolicy p = policy(EnumSet.allOf(Action.class));
        assertTrue(p.decide("Boss", true, "Me", "coords", 0).allowed());
        assertFalse(p.decide("Boss", false, "Me", "coords", 100_000).allowed(), "a faked '<Boss> coords' line is a stranger");
    }

    @Test
    void sensitiveActionsAreOffUntilEnabled() {
        ChatBotPolicy p = policy(EnumSet.of(Action.REPLY));
        assertFalse(p.decide("Boss", true, "Me", "coords", 0).allowed());
    }

    @Test
    void neverAnswersItselfOrPartialWords() {
        ChatBotPolicy p = policy(EnumSet.allOf(Action.class));
        assertFalse(p.decide("Me", true, "Me", "hello", 0).allowed());
        assertFalse(p.decide("Rando", true, "Me", "othello", 0).allowed());
    }

    @Test
    void rateLimitsStrangersButNotTheOwner() {
        ChatBotPolicy p = policy(EnumSet.allOf(Action.class));
        assertTrue(p.decide("A", true, "Me", "hello", 0).allowed());
        p.record("A", 0);
        assertFalse(p.decide("B", true, "Me", "hello", 500).allowed(), "global cooldown");
        assertFalse(p.decide("A", true, "Me", "hello", 5000).allowed(), "per-sender cooldown");
        assertTrue(p.decide("B", true, "Me", "hello", 5000).allowed());
        assertTrue(p.decide("Boss", true, "Me", "coords", 600).allowed(), "owner skips cooldowns");
        for (int i = 0; i < 6; i++) p.record("Boss", 1000 + i);
        assertFalse(p.decide("Boss", true, "Me", "coords", 2000).allowed(), "hard per-minute cap applies to everyone");
    }

    @Test
    void outgoingFilterBlocksCommandsAndLeaks() {
        assertNull(ChatBotPolicy.sanitizeOutgoing("/pay Rando 1000000"));
        assertNull(ChatBotPolicy.sanitizeOutgoing("sure, tpaccept"));
        assertNull(ChatBotPolicy.sanitizeOutgoing("my password is hunter2"));
        assertNull(ChatBotPolicy.sanitizeOutgoing("join discord.gg/abc"));
        assertEquals("hi Steve!", ChatBotPolicy.sanitizeOutgoing("\u00a7ahi Steve!"));
    }

    @Test
    void plainReplyRulesCantLeakCoordsToStrangers() {
        ChatBotPolicy p = policy(EnumSet.allOf(Action.class));
        Map<String, String> all = Map.of("x", "100", "z", "-50", "health", "20", "sender", "Rando");
        assertEquals("at ? ? hp ?", ChatBotPolicy.fill("at {x} {z} hp {health}", p.visible(all, "Rando", true)));
        assertEquals("at 100 -50 hp 20", ChatBotPolicy.fill("at {x} {z} hp {health}", p.visible(all, "Boss", true)));
        assertEquals("at ? ? hp ?", ChatBotPolicy.fill("at {x} {z} hp {health}", p.visible(all, "Boss", false)), "unverified owner name");
        ChatBotPolicy off = policy(EnumSet.of(Action.REPLY));
        assertEquals("at ? ?", ChatBotPolicy.fill("at {x} {z}", off.visible(all, "Boss", true)), "coords toggle off = never filled");
    }

    @Test
    void placeholdersCantBeInjectedThroughNames() {
        String out = ChatBotPolicy.fill("hi {sender}", Map.of("sender", "{x}", "x", "123"));
        assertEquals("hi (x)", out);
    }
}
