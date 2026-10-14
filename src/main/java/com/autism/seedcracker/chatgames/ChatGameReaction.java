package com.autism.seedcracker.chatgames;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

public final class ChatGameReaction {
    private static final Pattern COMMAND = Pattern.compile("/chatgames answer [0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern TARGET = Pattern.compile("(?i)\\bclick\\s+(?:on\\s+)?[\"']([^\"']{1,80})[\"']");

    private ChatGameReaction() {}

    private record Button(String command, StringBuilder label) {}

    public static boolean prompt(String text) {
        if (text == null || text.length() > 4096) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("click") && (lower.contains("chatgame") || lower.contains("chat game")
            || lower.contains("reaction game") || lower.contains("first to click"));
    }

    public static String select(Component content) {
        if (content == null || !prompt(content.getString())) return null;
        var buttons = new ArrayList<Button>();
        String[] previous = {null};
        boolean[] invalid = {false};
        int[] segments = {0};
        content.visit((style, text) -> {
            if (++segments[0] > 128) {
                invalid[0] = true;
                return Optional.of(true);
            }
            if (style.getClickEvent() instanceof ClickEvent.RunCommand click) {
                if (!COMMAND.matcher(click.command()).matches()) invalid[0] = true;
                else {
                    if (!click.command().equals(previous[0])) buttons.add(new Button(click.command(), new StringBuilder()));
                    buttons.getLast().label().append(text);
                    previous[0] = click.command();
                }
            } else {
                previous[0] = null;
                if (style.getClickEvent() != null) invalid[0] = true;
            }
            return Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        if (invalid[0] || buttons.isEmpty()) return null;
        if (buttons.size() == 1) return buttons.getFirst().command();
        var target = TARGET.matcher(content.getString());
        if (!target.find()) return null;
        String label = target.group(1).trim();
        if (target.find()) return null;
        String chosen = null;
        for (var button : buttons) {
            if (!label.equalsIgnoreCase(button.label().toString().trim())) continue;
            if (chosen != null) return null;
            chosen = button.command();
        }
        return chosen;
    }
}