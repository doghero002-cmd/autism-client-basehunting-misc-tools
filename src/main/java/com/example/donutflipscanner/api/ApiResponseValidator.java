package com.example.donutflipscanner.api;

import com.example.donutflipscanner.api.model.ApiArmorTrim;
import com.example.donutflipscanner.api.model.ApiAuctionItem;
import com.example.donutflipscanner.api.model.ApiAuctionListing;
import com.example.donutflipscanner.api.model.ApiAuctionPage;
import com.example.donutflipscanner.api.model.ApiCompletedTransaction;
import com.example.donutflipscanner.api.model.ApiContainerItem;
import com.example.donutflipscanner.api.model.ApiEnchantment;
import com.example.donutflipscanner.api.model.ApiItemData;
import com.example.donutflipscanner.api.model.ApiPaginationMetadata;
import com.example.donutflipscanner.api.model.ApiPlayerStats;
import com.example.donutflipscanner.api.model.ApiSeller;
import com.example.donutflipscanner.balance.BalanceAmountParser;
import com.example.donutflipscanner.api.model.ApiTransactionPage;
import com.example.donutflipscanner.security.JsonSafety;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/** Parses only fields present in the official DonutSMP Swagger schema. */
public final class ApiResponseValidator {
    private static final int MAXIMUM_ARRAY_ELEMENTS = 20_000;
    private static final int MAXIMUM_STRING_CHARACTERS = 32_768;
    private static final int MAXIMUM_JSON_DEPTH = 24;

    private final int maximumResponseCharacters;

    public ApiResponseValidator(int maximumResponseCharacters) {
        if (maximumResponseCharacters < 1_024) {
            throw new IllegalArgumentException("maximumResponseCharacters must be at least 1024");
        }
        this.maximumResponseCharacters = maximumResponseCharacters;
    }

    public ApiAuctionPage parseAuctionPage(String body, int requestedPage) {
        JsonObject root = parseRoot(body);
        int status = requiredInt(root, "status");
        requireSuccessfulPayloadStatus(status);
        JsonArray result = requiredArray(root, "result");
        List<ApiAuctionListing> listings = new ArrayList<>(result.size());
        for (JsonElement element : result) {
            listings.add(parseListing(requiredObject(element, "auction listing")));
        }
        return new ApiAuctionPage(status, listings, ApiPaginationMetadata.listings(requestedPage, listings.size()));
    }

    public ApiTransactionPage parseTransactionPage(String body, int requestedPage) {
        JsonObject root = parseRoot(body);
        int status = requiredInt(root, "status");
        requireSuccessfulPayloadStatus(status);
        JsonArray result = requiredArray(root, "result");
        List<ApiCompletedTransaction> transactions = new ArrayList<>(result.size());
        for (JsonElement element : result) {
            transactions.add(parseTransaction(requiredObject(element, "completed transaction")));
        }
        return new ApiTransactionPage(
                status,
                transactions,
                ApiPaginationMetadata.transactions(requestedPage, transactions.size())
        );
    }

    public ApiPlayerStats parsePlayerStats(String body) {
        JsonObject root = parseRoot(body);
        int status = requiredInt(root, "status");
        requireSuccessfulPayloadStatus(status);
        JsonObject result = optionalObject(root, "result")
                .orElseThrow(() -> new ApiResponseException(
                        "DonutSMP API response is missing the 'result' object"
                ));
        JsonElement money = result.get("money");
        if (money == null || money.isJsonNull()) {
            throw new ApiResponseException("DonutSMP API player stats are missing the 'money' field");
        }
        String formatted = requiredString(money, "money");
        try {
            return new ApiPlayerStats(status, BalanceAmountParser.parse(formatted));
        } catch (IllegalArgumentException exception) {
            throw new ApiResponseException(
                    "DonutSMP API player stats contain an invalid money value", exception
            );
        }
    }

    private JsonObject parseRoot(String body) {
        if (body == null || body.isBlank()) {
            throw new ApiResponseException("DonutSMP API returned an empty JSON response");
        }
        if (body.length() > maximumResponseCharacters) {
            throw new ApiResponseException("DonutSMP API response exceeded the configured size limit");
        }
        try {
            JsonSafety.validateBounds(body, maximumResponseCharacters, MAXIMUM_JSON_DEPTH);
            return requiredObject(JsonParser.parseString(body), "response root");
        } catch (JsonParseException | IllegalArgumentException exception) {
            throw new ApiResponseException("DonutSMP API returned invalid JSON", exception);
        }
    }

    private void requireSuccessfulPayloadStatus(int status) {
        if (status < 200 || status >= 300) {
            throw new ApiResponseException("DonutSMP API returned a non-success payload status");
        }
    }

    private ApiAuctionListing parseListing(JsonObject object) {
        return new ApiAuctionListing(
                optionalObject(object, "item").map(this::parseItem),
                optionalDecimal(object, "price"),
                optionalObject(object, "seller").map(this::parseSeller),
                optionalLong(object, "time_left")
        );
    }

    private ApiCompletedTransaction parseTransaction(JsonObject object) {
        return new ApiCompletedTransaction(
                optionalObject(object, "item").map(this::parseItem),
                optionalDecimal(object, "price"),
                optionalObject(object, "seller").map(this::parseSeller),
                optionalInteger(object, "unixMillisDateSold")
        );
    }

    private ApiAuctionItem parseItem(JsonObject object) {
        validateIgnoredDurability(object);
        List<String> lore = new ArrayList<>();
        optionalArray(object, "lore").ifPresent(array -> {
            for (JsonElement line : array) {
                lore.add(requiredString(line, "item lore line"));
            }
        });

        List<ApiContainerItem> contents = new ArrayList<>();
        optionalArray(object, "contents").ifPresent(array -> {
            for (JsonElement entry : array) {
                contents.add(parseContainerItem(requiredObject(entry, "container item")));
            }
        });

        return new ApiAuctionItem(
                optionalString(object, "id"),
                optionalInt(object, "count"),
                optionalString(object, "display_name"),
                lore,
                optionalObject(object, "enchants").map(this::parseItemData),
                contents,
                unrecognizedFields(object, Set.of(
                        "contents", "count", "damage", "display_name", "durability",
                        "enchants", "id", "lore", "max_damage"
                ))
        );
    }

    private ApiContainerItem parseContainerItem(JsonObject object) {
        validateIgnoredDurability(object);
        return new ApiContainerItem(
                optionalString(object, "id"),
                optionalInt(object, "count"),
                optionalString(object, "display_name"),
                optionalObject(object, "enchants").map(this::parseItemData),
                unrecognizedFields(object, Set.of(
                        "count", "damage", "display_name", "durability", "enchants", "id", "max_damage"
                ))
        );
    }

    /** Durability is validated but deliberately excluded from the market fingerprint. */
    private void validateIgnoredDurability(JsonObject object) {
        for (String field : List.of("damage", "durability", "max_damage")) {
            OptionalInt value = optionalInt(object, field);
            if (value.isPresent() && value.getAsInt() < 0) {
                throw new ApiResponseException("DonutSMP API field '" + field + "' must not be negative");
            }
        }
    }

    private ApiItemData parseItemData(JsonObject object) {
        List<ApiEnchantment> enchantments = new ArrayList<>();
        List<String> unrecognized = new ArrayList<>(unrecognizedFields(object, Set.of("enchantments", "trim")));
        optionalObject(object, "enchantments").ifPresent(enchantmentObject -> {
            unrecognizedFields(enchantmentObject, Set.of("levels")).stream()
                    .map(field -> "enchantments." + field)
                    .forEach(unrecognized::add);
            optionalObject(enchantmentObject, "levels").ifPresent(levels -> {
                    if (levels.size() > MAXIMUM_ARRAY_ELEMENTS) {
                        throw new ApiResponseException("DonutSMP API returned too many enchantments");
                    }
                    for (Map.Entry<String, JsonElement> entry : levels.entrySet()) {
                        requireBoundedString(entry.getKey(), "enchantment id");
                        enchantments.add(new ApiEnchantment(entry.getKey(), requiredInt(entry.getValue(), "enchantment level")));
                    }
            });
        });
        enchantments.sort(Comparator.comparing(ApiEnchantment::id));

        Optional<ApiArmorTrim> trim = optionalObject(object, "trim").flatMap(value -> {
            Optional<String> material = optionalString(value, "material");
            Optional<String> pattern = optionalString(value, "pattern");
            List<String> unknown = unrecognizedFields(value, Set.of("material", "pattern"));
            if (material.isEmpty() && pattern.isEmpty() && unknown.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new ApiArmorTrim(material, pattern, unknown));
        });
        return new ApiItemData(enchantments, trim, unrecognized.stream().sorted().toList());
    }

    private List<String> unrecognizedFields(JsonObject object, Set<String> recognized) {
        return object.keySet().stream()
                .filter(field -> !recognized.contains(field))
                .sorted()
                .toList();
    }

    private ApiSeller parseSeller(JsonObject object) {
        return new ApiSeller(optionalString(object, "name"), optionalString(object, "uuid"));
    }

    private JsonArray requiredArray(JsonObject object, String name) {
        return optionalArray(object, name)
                .orElseThrow(() -> new ApiResponseException("DonutSMP API response is missing the '" + name + "' array"));
    }

    private Optional<JsonArray> optionalArray(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return Optional.empty();
        }
        if (!value.isJsonArray()) {
            throw new ApiResponseException("DonutSMP API field '" + name + "' must be an array");
        }
        JsonArray array = value.getAsJsonArray();
        if (array.size() > MAXIMUM_ARRAY_ELEMENTS) {
            throw new ApiResponseException("DonutSMP API array '" + name + "' is too large");
        }
        return Optional.of(array);
    }

    private Optional<JsonObject> optionalObject(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return Optional.empty();
        }
        return Optional.of(requiredObject(value, name));
    }

    private JsonObject requiredObject(JsonElement value, String description) {
        if (value == null || value.isJsonNull() || !value.isJsonObject()) {
            throw new ApiResponseException("DonutSMP API " + description + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private int requiredInt(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            throw new ApiResponseException("DonutSMP API response is missing the '" + name + "' field");
        }
        return requiredInt(value, name);
    }

    private int requiredInt(JsonElement value, String description) {
        try {
            return new BigInteger(requiredNumberText(value, description)).intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new ApiResponseException("DonutSMP API field '" + description + "' is not a valid integer", exception);
        }
    }

    private OptionalInt optionalInt(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(requiredInt(value, name));
    }

    private OptionalLong optionalLong(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(new BigInteger(requiredNumberText(value, name)).longValueExact());
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new ApiResponseException("DonutSMP API field '" + name + "' is not a valid long", exception);
        }
    }

    private Optional<BigInteger> optionalInteger(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigInteger(requiredNumberText(value, name)));
        } catch (NumberFormatException exception) {
            throw new ApiResponseException("DonutSMP API field '" + name + "' is not a valid integer", exception);
        }
    }

    private Optional<BigDecimal> optionalDecimal(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return Optional.empty();
        }
        try {
            BigDecimal number = new BigDecimal(requiredNumberText(value, name));
            if (number.signum() < 0) {
                throw new ApiResponseException("DonutSMP API field '" + name + "' must not be negative");
            }
            return Optional.of(number);
        } catch (NumberFormatException exception) {
            throw new ApiResponseException("DonutSMP API field '" + name + "' is not a valid number", exception);
        }
    }

    private String requiredNumberText(JsonElement value, String description) {
        if (!value.isJsonPrimitive() || (!value.getAsJsonPrimitive().isNumber() && !value.getAsJsonPrimitive().isString())) {
            throw new ApiResponseException("DonutSMP API field '" + description + "' must be numeric");
        }
        return value.getAsString().trim();
    }

    private Optional<String> optionalString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return Optional.empty();
        }
        String parsed = requiredString(value, name);
        return parsed.isBlank() ? Optional.empty() : Optional.of(parsed);
    }

    private String requiredString(JsonElement value, String description) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new ApiResponseException("DonutSMP API field '" + description + "' must be a string");
        }
        return requireBoundedString(value.getAsString(), description);
    }

    private String requireBoundedString(String value, String description) {
        if (value.length() > MAXIMUM_STRING_CHARACTERS) {
            throw new ApiResponseException("DonutSMP API field '" + description + "' is too long");
        }
        return value;
    }
}
