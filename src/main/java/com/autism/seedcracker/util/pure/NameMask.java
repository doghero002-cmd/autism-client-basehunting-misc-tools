package com.autism.seedcracker.util.pure;

import java.util.ArrayList;
import java.util.Optional;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

public final class NameMask {
    private record Span(int start, int end) {}
    private NameMask() {}

    public static Component mask(Component original, String real, String alias) {
        if (original == null || real == null || real.isBlank()) return original;
        Pattern pattern = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(real) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE);
        var matcher = pattern.matcher(original.getString());
        var spans = new ArrayList<Span>();
        while (matcher.find()) spans.add(new Span(matcher.start(), matcher.end()));
        if (spans.isEmpty()) return original;
        String replacement = alias == null || alias.isBlank() || alias.equalsIgnoreCase(real) ? "Anonymous" : alias;
        var result = Component.empty();
        int[] offset = {0};
        original.visit((style, text) -> {
            int base = offset[0];
            int limit = base + text.length();
            int cursor = base;
            for (Span span : spans) {
                if (span.end <= base || span.start >= limit) continue;
                int start = Math.max(base, span.start);
                if (start > cursor) result.append(Component.literal(text.substring(cursor - base, start - base)).setStyle(style));
                if (span.start >= base) result.append(Component.literal(replacement).setStyle(style));
                cursor = Math.min(limit, span.end);
            }
            if (cursor < limit) result.append(Component.literal(text.substring(cursor - base)).setStyle(style));
            offset[0] = limit;
            return Optional.empty();
        }, Style.EMPTY);
        return result;
    }
}