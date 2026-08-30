package com.example.donutflipscanner.market.item;

import com.example.donutflipscanner.database.entity.ItemFingerprintEntity;
import com.example.donutflipscanner.market.item.model.ItemFingerprint;
import com.example.donutflipscanner.market.item.model.ItemMatchQuality;
import com.example.donutflipscanner.market.item.model.ItemMatchType;
import com.example.donutflipscanner.market.item.model.NormalizedArmorTrim;
import com.example.donutflipscanner.market.item.model.NormalizedContainedItem;
import com.example.donutflipscanner.market.item.model.NormalizedEnchantment;
import com.example.donutflipscanner.market.item.model.NormalizedItem;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Reconstructs an immutable normalized item from its canonical database fingerprint. */
public final class NormalizedItemPersistenceMapper {
    private static final int MAXIMUM_DEPTH = 8;
    private static final int MAXIMUM_COLLECTION_SIZE = 512;

    public NormalizedItem fromEntity(ItemFingerprintEntity entity, int stackCount) {
        Objects.requireNonNull(entity, "entity");
        if (stackCount < 1) {
            throw new IllegalArgumentException("stackCount must be positive");
        }
        JsonObject root = JsonParser.parseString(entity.normalizedMetadata()).getAsJsonObject();
        NormalizedItem item = decode(root, OptionalInt.of(stackCount), 0);
        if (!item.fingerprint().sha256().equals(entity.fingerprint())) {
            throw new IllegalArgumentException("stored item fingerprint does not match its canonical metadata");
        }
        return item;
    }

    private NormalizedItem decode(JsonObject root, OptionalInt stackCount, int depth) {
        if (depth > MAXIMUM_DEPTH) {
            throw new IllegalArgumentException("stored item nesting is too deep");
        }
        String canonical = root.toString();
        ItemFingerprint fingerprint = new ItemFingerprintFactory().fromCanonicalMetadata(canonical);
        String itemId = requiredString(root, "itemId");
        ItemMatchType matchType = ItemMatchType.valueOf(requiredString(root, "matchType"));
        Optional<String> customName = optionalString(root, "customName");
        List<String> lore = strings(root, "lore");
        List<NormalizedEnchantment> enchantments = enchantments(root);
        Optional<NormalizedArmorTrim> trim = trim(root);
        List<NormalizedContainedItem> contents = contents(root, depth);
        boolean truncated = root.has("contentsTruncated") && root.get("contentsTruncated").getAsBoolean();
        List<String> unknown = strings(root, "unrecognizedFields");
        return new NormalizedItem(
                itemId,
                stackCount,
                customName,
                lore,
                enchantments,
                trim,
                contents,
                truncated,
                unknown,
                ItemMatchQuality.of(matchType, List.of()),
                fingerprint
        );
    }

    private List<NormalizedEnchantment> enchantments(JsonObject root) {
        JsonArray values = array(root, "enchantments");
        List<NormalizedEnchantment> result = new ArrayList<>(values.size());
        for (JsonElement value : values) {
            JsonObject enchantment = value.getAsJsonObject();
            result.add(new NormalizedEnchantment(
                    requiredString(enchantment, "id"), enchantment.get("level").getAsInt()
            ));
        }
        return List.copyOf(result);
    }

    private Optional<NormalizedArmorTrim> trim(JsonObject root) {
        JsonElement value = root.get("trim");
        if (value == null || value.isJsonNull()) {
            return Optional.empty();
        }
        JsonObject trim = value.getAsJsonObject();
        return Optional.of(new NormalizedArmorTrim(
                requiredString(trim, "material"), requiredString(trim, "pattern")
        ));
    }

    private List<NormalizedContainedItem> contents(JsonObject root, int depth) {
        JsonArray values = array(root, "contents");
        List<NormalizedContainedItem> result = new ArrayList<>(values.size());
        for (JsonElement value : values) {
            JsonObject contained = value.getAsJsonObject();
            JsonElement countValue = contained.get("count");
            OptionalInt count = countValue == null || countValue.isJsonNull()
                    ? OptionalInt.empty() : OptionalInt.of(countValue.getAsInt());
            result.add(new NormalizedContainedItem(
                    count, decode(contained.getAsJsonObject("item"), count, depth + 1)
            ));
        }
        return List.copyOf(result);
    }

    private List<String> strings(JsonObject root, String name) {
        JsonArray values = array(root, name);
        List<String> result = new ArrayList<>(values.size());
        values.forEach(value -> result.add(value.getAsString()));
        return List.copyOf(result);
    }

    private JsonArray array(JsonObject root, String name) {
        JsonArray values = root.getAsJsonArray(name);
        if (values == null || values.size() > MAXIMUM_COLLECTION_SIZE) {
            throw new IllegalArgumentException("stored item collection is invalid: " + name);
        }
        return values;
    }

    private static Optional<String> optionalString(JsonObject root, String name) {
        JsonElement value = root.get(name);
        return value == null || value.isJsonNull() ? Optional.empty() : Optional.of(value.getAsString());
    }

    private static String requiredString(JsonObject root, String name) {
        JsonElement value = root.get(name);
        if (value == null || value.isJsonNull()) {
            throw new IllegalArgumentException("stored item is missing " + name);
        }
        return value.getAsString();
    }
}
