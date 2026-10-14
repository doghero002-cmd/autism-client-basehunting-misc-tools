package com.autism.seedcracker.commands;

import autismclient.commands.AutismCommandSource;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GotoCommandTest {
    @Test
    void localAiDiagnosticDoesNotShadowSetupCommands() {
        var root = LiteralArgumentBuilder.<AutismCommandSource>literal("qql");
        new QqlCommand().build(root);
        var dispatcher = new CommandDispatcher<AutismCommandSource>();
        dispatcher.register(root);
        for (String input : new String[] {"qql ai-test", "qql ai-test Explain redstone repeaters", "qql ai-test-context", "qql list", "qql off", "qql base keep"}) {
            var parsed = dispatcher.parse(input, null);
            assertFalse(parsed.getReader().canRead(), input);
            assertNotNull(parsed.getContext().getCommand(), input);
        }
    }

    @Test
    void playerTargetsAndFollowDoNotShadowCoordinatesOrLiterals() {
        var root = LiteralArgumentBuilder.<AutismCommandSource>literal("goto");
        new GotoCommand().build(root);
        var dispatcher = new CommandDispatcher<AutismCommandSource>();
        dispatcher.register(root);
        for (String input : new String[] {"goto doghero9", "goto follow doghero9", "goto 123 456", "goto -123 70 456", "goto stop", "goto mine 123 70 456"}) {
            var parsed = dispatcher.parse(input, null);
            assertFalse(parsed.getReader().canRead(), input);
            assertNotNull(parsed.getContext().getCommand(), input);
        }
        var player = dispatcher.parse("goto doghero9", null);
        assertEquals("doghero9", player.getContext().getArguments().get("player").getResult());
        var follow = dispatcher.parse("goto follow doghero9", null);
        assertEquals("doghero9", follow.getContext().getArguments().get("player").getResult());
        assertNull(dispatcher.parse("goto follow", null).getContext().getCommand());
        assertTrue(dispatcher.parse("goto follow doghero9 extra", null).getReader().canRead());
    }
}