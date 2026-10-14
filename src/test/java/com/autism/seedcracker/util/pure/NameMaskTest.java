package com.autism.seedcracker.util.pure;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NameMaskTest {
    @Test void onlyWholePlayerNamesAreMasked() {
        assertEquals("Timothy met Hidden and Tim_2", NameMask.mask(
            Component.literal("Timothy met TIM and Tim_2"), "Tim", "Hidden").getString());
        assertEquals("Hidden's chest", NameMask.mask(Component.literal("Tim's chest"), "Tim", "Hidden").getString());
        assertEquals("$1", NameMask.mask(Component.literal("Tim"), "Tim", "$1").getString());
    }

    @Test void namesAcrossStyledRunsAreMaskedWithoutLosingUnaffectedStyles() {
        var original = Component.empty().append(Component.literal("Ti").withStyle(ChatFormatting.RED))
            .append(Component.literal("m").withStyle(ChatFormatting.BLUE))
            .append(Component.literal(" hello").withStyle(ChatFormatting.GREEN));
        Component masked = NameMask.mask(original, "Tim", "Hidden");
        assertEquals("Hidden hello", masked.getString());
        assertEquals(net.minecraft.network.chat.TextColor.RED, masked.getSiblings().get(0).getStyle().getColor());
        assertEquals(net.minecraft.network.chat.TextColor.GREEN, masked.getSiblings().get(1).getStyle().getColor());
    }
}