package com.example.donutflipscanner.market.item;

import com.example.donutflipscanner.market.item.model.ItemFingerprint;
import com.example.donutflipscanner.market.item.model.ItemMatchType;
import com.example.donutflipscanner.market.item.model.NormalizedArmorTrim;
import com.example.donutflipscanner.market.item.model.NormalizedContainedItem;
import com.example.donutflipscanner.market.item.model.NormalizedEnchantment;
import com.example.donutflipscanner.util.HashingUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class ItemFingerprintFactory {
    public static final int FORMAT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    ItemFingerprint create(
            ItemMatchType matchType,
            String itemId,
            Optional<String> customName,
            List<String> lore,
            List<NormalizedEnchantment> enchantments,
            Optional<NormalizedArmorTrim> armorTrim,
            List<NormalizedContainedItem> contents,
            boolean contentsTruncated,
            List<String> unrecognizedFields
    ) {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", FORMAT_VERSION);
        root.addProperty("matchType", matchType.name());
        root.addProperty("itemId", itemId);
        if (customName.isPresent()) {
            root.addProperty("customName", customName.get());
        } else {
            root.add("customName", JsonNull.INSTANCE);
        }

        JsonArray loreArray = new JsonArray();
        lore.forEach(loreArray::add);
        root.add("lore", loreArray);

        JsonArray enchantmentArray = new JsonArray();
        enchantments.stream().sorted().forEach(enchantment -> {
            JsonObject value = new JsonObject();
            value.addProperty("id", enchantment.id());
            value.addProperty("level", enchantment.level());
            enchantmentArray.add(value);
        });
        root.add("enchantments", enchantmentArray);

        if (armorTrim.isPresent()) {
            JsonObject trim = new JsonObject();
            trim.addProperty("material", armorTrim.get().material());
            trim.addProperty("pattern", armorTrim.get().pattern());
            root.add("trim", trim);
        } else {
            root.add("trim", JsonNull.INSTANCE);
        }

        JsonArray contentArray = new JsonArray();
        contents.stream()
                .sorted(Comparator.comparing((NormalizedContainedItem value) -> value.item().fingerprint().sha256())
                        .thenComparingInt(value -> value.count().orElse(-1)))
                .forEach(contained -> {
                    JsonObject value = new JsonObject();
                    if (contained.count().isPresent()) {
                        value.addProperty("count", contained.count().getAsInt());
                    } else {
                        value.add("count", JsonNull.INSTANCE);
                    }
                    value.add("item", JsonParser.parseString(contained.item().fingerprint().canonicalMetadata()));
                    contentArray.add(value);
                });
        root.add("contents", contentArray);
        root.addProperty("contentsTruncated", contentsTruncated);

        JsonArray unknownArray = new JsonArray();
        unrecognizedFields.stream().distinct().sorted().forEach(unknownArray::add);
        root.add("unrecognizedFields", unknownArray);

        String canonical = GSON.toJson(root);
        return new ItemFingerprint(HashingUtil.sha256(canonical), canonical);
    }

    public ItemFingerprint fromCanonicalMetadata(String canonicalMetadata) {
        JsonParser.parseString(canonicalMetadata);
        return new ItemFingerprint(HashingUtil.sha256(canonicalMetadata), canonicalMetadata);
    }
}
