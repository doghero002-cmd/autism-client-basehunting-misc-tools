package com.example.donutflipscanner.configuration;

import com.example.donutflipscanner.market.opportunity.ItemFilterMode;
import com.example.donutflipscanner.security.JsonSafety;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ConfigurationCodec {
    static final int MAXIMUM_CONFIG_CHARACTERS = 2 * 1024 * 1024;
    static final int MAXIMUM_JSON_DEPTH = 24;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    AppConfig decode(String json) {
        JsonSafety.validateBounds(json, MAXIMUM_CONFIG_CHARACTERS, MAXIMUM_JSON_DEPTH);
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("configuration root must be an object");
        }
        JsonObject root = parsed.getAsJsonObject();
        int sourceVersion = integer(root, "formatVersion", 1);
        if (sourceVersion < 1 || sourceVersion > AppConfig.CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException("unsupported configuration format version");
        }
        double scale = decimal(root, sourceVersion == 1 ? "guiScale" : "interfaceScale", BigDecimal.ONE)
                .doubleValue();
        return new AppConfig(
                AppConfig.CURRENT_FORMAT_VERSION,
                bool(root, "scannerEnabled", true),
                bool(root, "mockDataMode", true),
                bool(root, "animationsEnabled", true),
                bool(root, "notificationsEnabled", true),
                scale,
                enumeration(root, "filterMode", ItemFilterMode.class, ItemFilterMode.ALL_ITEMS),
                idSet(root, "whitelistedItems"),
                idSet(root, "blacklistedItems"),
                thresholds(root),
                automation(root)
        );
    }

    String encode(AppConfig config) {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", AppConfig.CURRENT_FORMAT_VERSION);
        root.addProperty("scannerEnabled", config.scannerEnabled());
        root.addProperty("mockDataMode", config.mockDataMode());
        root.addProperty("animationsEnabled", config.animationsEnabled());
        root.addProperty("notificationsEnabled", config.notificationsEnabled());
        root.addProperty("interfaceScale", config.interfaceScale());
        root.addProperty("filterMode", config.filterMode().name());
        root.add("whitelistedItems", ids(config.whitelistedItems()));
        root.add("blacklistedItems", ids(config.blacklistedItems()));
        JsonObject itemSettings = new JsonObject();
        config.itemThresholds().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ItemThresholdConfig value = entry.getValue();
            JsonObject item = new JsonObject();
            item.addProperty("enabled", value.enabled());
            item.addProperty("minimumProfit", value.minimumProfit().toPlainString());
            item.addProperty("minimumRoi", value.minimumRoi().toPlainString());
            item.addProperty("minimumConfidence", value.minimumConfidence());
            item.addProperty("maximumPurchasePrice", value.maximumPurchasePrice().toPlainString());
            item.addProperty("minimumComparableSales", value.minimumComparableSales());
            item.addProperty("minimumStackSize", value.minimumStackSize());
            item.addProperty("maximumStackSize", value.maximumStackSize());
            item.addProperty("includeEnchanted", value.includeEnchanted());
            item.addProperty("includeRenamed", value.includeRenamed());
            item.addProperty("includeDamaged", value.includeDamaged());
            item.addProperty("includeContainers", value.includeContainers());
            itemSettings.add(entry.getKey(), item);
        });
        root.add("itemThresholds", itemSettings);
        root.add("automation", automation(config.automation()));
        return GSON.toJson(root) + System.lineSeparator();
    }

    private AutomationConfig automation(JsonObject root) {
        AutomationConfig defaults = AutomationConfig.defaults();
        JsonElement value = root.get("automation");
        if (value == null || value.isJsonNull()) {
            return defaults;
        }
        if (!value.isJsonObject()) {
            throw new IllegalArgumentException("automation must be an object");
        }
        JsonObject object = value.getAsJsonObject();
        return new AutomationConfig(
                bool(object, "enabled", defaults.enabled()),
                enumeration(object, "mode", com.example.donutflipscanner.automation.model.AutomationMode.class,
                        defaults.mode()),
                stringSet(object, "allowedServerAddresses", 128),
                decimal(object, "maximumPurchasePrice", defaults.maximumPurchasePrice()),
                decimal(object, "maximumOpenExposure", defaults.maximumOpenExposure()),
                integer(object, "maximumPurchasesPerSession", defaults.maximumPurchasesPerSession()),
                integer(object, "minimumConfidence", defaults.minimumConfidence()),
                integer(object, "minimumComparableSales", defaults.minimumComparableSales()),
                decimal(object, "minimumNetProfit", defaults.minimumNetProfit()),
                decimal(object, "minimumRoi", defaults.minimumRoi()),
                longInteger(object, "maximumListingAgeSeconds", defaults.maximumListingAgeSeconds()),
                longInteger(object, "purchaseCooldownSeconds", defaults.purchaseCooldownSeconds()),
                enumeration(object, "relistPricingStrategy",
                        com.example.donutflipscanner.automation.model.RelistPricingStrategy.class,
                        defaults.relistPricingStrategy()),
                decimal(object, "targetRoi", defaults.targetRoi()),
                decimal(object, "fixedMarkupPercent", defaults.fixedMarkupPercent()),
                bool(object, "requireInventoryVerification", defaults.requireInventoryVerification()),
                bool(object, "requireBalanceVerification", defaults.requireBalanceVerification()),
                bool(object, "requireListingVerification", defaults.requireListingVerification()),
                bool(object, "showExecutionNotifications", defaults.showExecutionNotifications()),
                interactionProfile(object)
        );
    }

    private JsonObject automation(AutomationConfig config) {
        JsonObject object = new JsonObject();
        object.addProperty("enabled", config.enabled());
        object.addProperty("mode", config.mode().name());
        object.add("allowedServerAddresses", strings(config.allowedServerAddresses()));
        object.addProperty("maximumPurchasePrice", config.maximumPurchasePrice().toPlainString());
        object.addProperty("maximumOpenExposure", config.maximumOpenExposure().toPlainString());
        object.addProperty("maximumPurchasesPerSession", config.maximumPurchasesPerSession());
        object.addProperty("minimumConfidence", config.minimumConfidence());
        object.addProperty("minimumComparableSales", config.minimumComparableSales());
        object.addProperty("minimumNetProfit", config.minimumNetProfit().toPlainString());
        object.addProperty("minimumRoi", config.minimumRoi().toPlainString());
        object.addProperty("maximumListingAgeSeconds", config.maximumListingAgeSeconds());
        object.addProperty("purchaseCooldownSeconds", config.purchaseCooldownSeconds());
        object.addProperty("relistPricingStrategy", config.relistPricingStrategy().name());
        object.addProperty("targetRoi", config.targetRoi().toPlainString());
        object.addProperty("fixedMarkupPercent", config.fixedMarkupPercent().toPlainString());
        object.addProperty("requireInventoryVerification", config.requireInventoryVerification());
        object.addProperty("requireBalanceVerification", config.requireBalanceVerification());
        object.addProperty("requireListingVerification", config.requireListingVerification());
        object.addProperty("showExecutionNotifications", config.showExecutionNotifications());
        config.interactionProfile().ifPresent(profile -> {
            JsonObject value = new JsonObject();
            value.addProperty("profileId", profile.profileId());
            value.addProperty("searchCommandTemplate", profile.searchCommandTemplate());
            value.addProperty("resultsScreenTitle", profile.resultsScreenTitle());
            value.addProperty("firstResultSlot", profile.firstResultSlot());
            value.addProperty("lastResultSlot", profile.lastResultSlot());
            value.addProperty("purchaseConfirmationTitle", profile.purchaseConfirmationTitle());
            value.addProperty("purchaseConfirmationSlot", profile.purchaseConfirmationSlot());
            value.addProperty("listingCommandTemplate", profile.listingCommandTemplate());
            value.addProperty("listingCreatedTitle", profile.listingCreatedTitle());
            value.addProperty("listingKeyLorePrefix", profile.listingKeyLorePrefix());
            value.addProperty("priceLorePrefix", profile.priceLorePrefix());
            value.addProperty("sellerLorePrefix", profile.sellerLorePrefix());
            value.addProperty("nextPageSlot", profile.nextPageSlot());
            value.addProperty("previousPageSlot", profile.previousPageSlot());
            value.addProperty("maximumPages", profile.maximumPages());
            JsonArray ignoredLore = new JsonArray();
            profile.ignoredLorePrefixes().forEach(ignoredLore::add);
            value.add("ignoredLorePrefixes", ignoredLore);
            object.add("interactionProfile", value);
        });
        return object;
    }

    private java.util.Optional<com.example.donutflipscanner.automation.model.AuctionInteractionProfile>
    interactionProfile(JsonObject automation) {
        JsonElement element = automation.get("interactionProfile");
        if (element == null || element.isJsonNull()) {
            return java.util.Optional.empty();
        }
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("interactionProfile must be an object");
        }
        JsonObject value = element.getAsJsonObject();
        return java.util.Optional.of(new com.example.donutflipscanner.automation.model.AuctionInteractionProfile(
                requiredString(value, "profileId"), requiredString(value, "searchCommandTemplate"),
                requiredString(value, "resultsScreenTitle"), integer(value, "firstResultSlot", -1),
                integer(value, "lastResultSlot", -1), requiredString(value, "purchaseConfirmationTitle"),
                integer(value, "purchaseConfirmationSlot", -1),
                requiredString(value, "listingCommandTemplate"),
                requiredString(value, "listingCreatedTitle"), requiredString(value, "listingKeyLorePrefix"),
                requiredString(value, "priceLorePrefix"), requiredString(value, "sellerLorePrefix"),
                integer(value, "nextPageSlot", -1), integer(value, "previousPageSlot", -1),
                integer(value, "maximumPages", 1), stringList(value, "ignoredLorePrefixes", 64)
        ));
    }

    private List<String> stringList(JsonObject root, String name, int maximumEntries) {
        JsonElement value = root.get(name);
        if (value == null || value.isJsonNull()) {
            return List.of();
        }
        if (!value.isJsonArray() || value.getAsJsonArray().size() > maximumEntries) {
            throw new IllegalArgumentException(name + " must be a bounded array");
        }
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (JsonElement entry : value.getAsJsonArray()) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(name + " may contain only strings");
            }
            result.add(entry.getAsString());
        }
        return List.copyOf(result);
    }

    private Map<String, ItemThresholdConfig> thresholds(JsonObject root) {
        JsonElement value = root.get("itemThresholds");
        if (value == null || value.isJsonNull()) {
            return Map.of();
        }
        if (!value.isJsonObject() || value.getAsJsonObject().size() > AppConfig.MAXIMUM_FILTER_ITEMS) {
            throw new IllegalArgumentException("itemThresholds must be a bounded object");
        }
        Map<String, ItemThresholdConfig> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            AppConfig.validateItemId(entry.getKey());
            if (!entry.getValue().isJsonObject()) {
                throw new IllegalArgumentException("item threshold must be an object");
            }
            JsonObject item = entry.getValue().getAsJsonObject();
            ItemThresholdConfig defaults = ItemThresholdConfig.defaults();
            result.put(entry.getKey(), new ItemThresholdConfig(
                    bool(item, "enabled", defaults.enabled()),
                    decimal(item, "minimumProfit", defaults.minimumProfit()),
                    decimal(item, "minimumRoi", defaults.minimumRoi()),
                    integer(item, "minimumConfidence", defaults.minimumConfidence()),
                    decimal(item, "maximumPurchasePrice", defaults.maximumPurchasePrice()),
                    integer(item, "minimumComparableSales", defaults.minimumComparableSales()),
                    integer(item, "minimumStackSize", defaults.minimumStackSize()),
                    integer(item, "maximumStackSize", defaults.maximumStackSize()),
                    bool(item, "includeEnchanted", defaults.includeEnchanted()),
                    bool(item, "includeRenamed", defaults.includeRenamed()),
                    bool(item, "includeDamaged", defaults.includeDamaged()),
                    bool(item, "includeContainers", defaults.includeContainers())
            ));
        }
        return Map.copyOf(result);
    }

    private Set<String> idSet(JsonObject root, String name) {
        JsonElement value = root.get(name);
        if (value == null || value.isJsonNull()) {
            return Set.of();
        }
        if (!value.isJsonArray() || value.getAsJsonArray().size() > AppConfig.MAXIMUM_FILTER_ITEMS) {
            throw new IllegalArgumentException(name + " must be a bounded array");
        }
        Set<String> result = new LinkedHashSet<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(name + " may contain only strings");
            }
            String id = element.getAsString();
            AppConfig.validateItemId(id);
            result.add(id);
        }
        return Set.copyOf(result);
    }

    private JsonArray ids(Set<String> values) {
        JsonArray array = new JsonArray();
        values.stream().sorted().forEach(array::add);
        return array;
    }

    private Set<String> stringSet(JsonObject root, String name, int maximumEntries) {
        JsonElement value = root.get(name);
        if (value == null || value.isJsonNull()) {
            return Set.of();
        }
        if (!value.isJsonArray() || value.getAsJsonArray().size() > maximumEntries) {
            throw new IllegalArgumentException(name + " must be a bounded array");
        }
        Set<String> result = new LinkedHashSet<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(name + " may contain only strings");
            }
            result.add(element.getAsString());
        }
        return Set.copyOf(result);
    }

    private JsonArray strings(Set<String> values) {
        JsonArray array = new JsonArray();
        values.stream().sorted().forEach(array::add);
        return array;
    }

    private static boolean bool(JsonObject object, String name, boolean fallback) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(name + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static String requiredString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return value.getAsString();
    }

    private static int integer(JsonObject object, String name, int fallback) {
        BigDecimal value = decimal(object, name, BigDecimal.valueOf(fallback));
        try {
            return value.intValueExact();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(name + " must be an integer", error);
        }
    }

    private static long longInteger(JsonObject object, String name, long fallback) {
        BigDecimal value = decimal(object, name, BigDecimal.valueOf(fallback));
        try {
            return value.longValueExact();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(name + " must be a long integer", error);
        }
    }

    private static BigDecimal decimal(JsonObject object, String name, BigDecimal fallback) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        if (!value.isJsonPrimitive()) {
            throw new IllegalArgumentException(name + " must be numeric");
        }
        try {
            return new BigDecimal(value.getAsString());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(name + " must be numeric", error);
        }
    }

    private static <T extends Enum<T>> T enumeration(
            JsonObject object,
            String name,
            Class<T> type,
            T fallback
    ) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        try {
            return Enum.valueOf(type, value.getAsString());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(name + " has an unsupported value", error);
        }
    }
}
