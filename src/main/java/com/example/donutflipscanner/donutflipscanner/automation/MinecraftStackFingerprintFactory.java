package com.example.donutflipscanner.automation;

import com.example.donutflipscanner.automation.model.AuctionInteractionProfile;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Builds a stable string fingerprint for an {@link ItemStack} so auction
 * listings can be matched against the immutable trade request. Mojang-mappings
 * replacement for the removed Yarn-backed factory.
 */
public final class MinecraftStackFingerprintFactory {

    /**
     * A fingerprint of the intrinsic item identity (id + components) plus the
     * visible lore, excluding any lore prefixes the profile marks as ignorable
     * (e.g. per-listing keys that change between otherwise identical listings).
     */
    public String fingerprint(ItemStack stack, AuctionInteractionProfile profile) {
        if (stack == null || stack.isEmpty()) {
            return "empty";
        }
        StringBuilder canonical = new StringBuilder();
        canonical.append(itemId(stack));
        appendEnchantments(stack, canonical);
        for (String loreLine : visibleLore(stack)) {
            if (profile != null && isIgnored(loreLine, profile)) {
                continue;
            }
            canonical.append("|lore:").append(normalize(loreLine));
        }
        return sha256(canonical.toString());
    }

    /** The lore lines that are actually visible on the stack, as plain text. */
    public List<String> visibleLore(ItemStack stack) {
        List<String> lines = new ArrayList<>();
        if (stack == null || stack.isEmpty()) {
            return lines;
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return lines;
        }
        for (Component line : lore.lines()) {
            lines.add(line == null ? "" : line.getString());
        }
        return lines;
    }

    /**
     * A copy of the stack reduced to its intrinsic identity (count forced to 1),
     * used for equality checks that should ignore stack size and listing lore.
     */
    public ItemStack intrinsicCopy(ItemStack stack, AuctionInteractionProfile profile) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack copy = stack.copy();
        copy.setCount(1);
        if (profile != null && !profile.ignoredLorePrefixes().isEmpty()) {
            ItemLore lore = copy.get(DataComponents.LORE);
            if (lore != null) {
                List<Component> kept = new ArrayList<>();
                for (Component line : lore.lines()) {
                    String text = line == null ? "" : line.getString();
                    if (!isIgnored(text, profile)) {
                        kept.add(line);
                    }
                }
                copy.set(DataComponents.LORE, new ItemLore(kept));
            }
        }
        return copy;
    }

    private void appendEnchantments(ItemStack stack, StringBuilder canonical) {
        ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
        if (enchantments == null || enchantments.isEmpty()) {
            return;
        }
        TreeMap<String, Integer> sorted = new TreeMap<>();
        for (var entry : enchantments.entrySet()) {
            Holder<Enchantment> holder = entry.getKey();
            String id = holder.unwrapKey()
                    .map(key -> key.identifier().toString())
                    .orElse("unknown");
            sorted.put(id, entry.getIntValue());
        }
        sorted.forEach((id, level) -> canonical.append("|ench:").append(id).append('=').append(level));
    }

    private static boolean isIgnored(String loreLine, AuctionInteractionProfile profile) {
        if (loreLine == null) {
            return false;
        }
        String normalized = normalize(loreLine);
        for (String prefix : profile.ignoredLorePrefixes()) {
            if (prefix != null && !prefix.isEmpty() && normalized.startsWith(normalize(prefix))) {
                return true;
            }
        }
        return false;
    }

    private static String itemId(ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "unknown" : id.toString();
    }

    private static String normalize(String text) {
        return text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
