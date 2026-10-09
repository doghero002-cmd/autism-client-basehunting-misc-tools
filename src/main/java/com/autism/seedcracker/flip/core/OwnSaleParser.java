package com.autism.seedcracker.flip.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.autism.seedcracker.util.pure.PriceMath;

/**
 * Parses DonutSMP "someone bought your listing" chat lines into confirmed sales. Ground truth for
 * the keyless feed: unlike scan-diff inference these cannot be false positives, so they enter the
 * store as {@code inferred=false} (full weight). Pure (no MC classes) so it's unit-testable.
 *
 * Port of GoNuts' DonutSaleMessageParser patterns:
 *   "PlayerX bought your 64x Diamond Block for $1,200,000"
 *   "PlayerX purchased your Enchanted Golden Apple for $500k"
 */
public final class OwnSaleParser {
    private OwnSaleParser() {}

    // buyer, optional "Nx "/"xN " count, item name, price (digits/commas/k-m-b-t suffix).
    private static final Pattern BOUGHT = Pattern.compile(
        "(?i)^\\s*(?:\\[[^\\]]+\\]\\s*)?([A-Za-z0-9_]{2,16})\\s+(?:bought|purchased)\\s+your\\s+"
            + "(?:(\\d{1,4})\\s*x\\s*|x\\s*(\\d{1,4})\\s+)?(.+?)\\s+for\\s+\\$?([\\d,.]+[kmbt]?)\\b.*$");

    /** A parsed own-sale chat line. */
    public record OwnSale(String buyer, int count, String itemDisplayName, long totalPrice) {}

    /** Returns the parsed sale, or null if the line isn't a sale message. */
    public static OwnSale parse(String chatLine) {
        if (chatLine == null || chatLine.isEmpty() || !chatLine.toLowerCase(Locale.ROOT).contains("your")) return null;
        Matcher m = BOUGHT.matcher(stripFormatting(chatLine));
        if (!m.matches()) return null;
        long price = PriceMath.parseAmount(m.group(5));
        if (price <= 0) return null;
        int count = 1;
        String c = m.group(2) != null ? m.group(2) : m.group(3);
        if (c != null) {
            try { count = Math.max(1, Integer.parseInt(c)); } catch (NumberFormatException ignored) {}
        }
        String item = m.group(4).trim();
        if (item.isEmpty()) return null;
        return new OwnSale(m.group(1), count, item, price);
    }

    /** Normalises a display name to the same item key shape GuiListingReader produces. */
    public static String itemKeyFromDisplayName(String displayName) {
        return displayName.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").replace(' ', '_');
    }

    private static String stripFormatting(String s) {
        return s.replaceAll("\u00a7.", "");
    }
}
