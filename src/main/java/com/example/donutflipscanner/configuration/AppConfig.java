package com.example.donutflipscanner.configuration;

import com.example.donutflipscanner.market.opportunity.ItemFilterMode;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable persisted settings. Credentials are deliberately stored separately. */
public record AppConfig(
        int formatVersion,
        boolean scannerEnabled,
        boolean mockDataMode,
        boolean animationsEnabled,
        boolean notificationsEnabled,
        double interfaceScale,
        ItemFilterMode filterMode,
        Set<String> whitelistedItems,
        Set<String> blacklistedItems,
        Map<String, ItemThresholdConfig> itemThresholds,
        AutomationConfig automation
) {
    public static final int CURRENT_FORMAT_VERSION = 3;
    public static final int MAXIMUM_FILTER_ITEMS = 10_000;
    private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    public AppConfig {
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException("configuration must use the current format version");
        }
        if (!Double.isFinite(interfaceScale) || interfaceScale < 0.5D || interfaceScale > 2.0D) {
            throw new IllegalArgumentException("interfaceScale must be between 0.5 and 2.0");
        }
        Objects.requireNonNull(filterMode, "filterMode");
        whitelistedItems = validatedIds(whitelistedItems, "whitelistedItems");
        blacklistedItems = validatedIds(blacklistedItems, "blacklistedItems");
        itemThresholds = Map.copyOf(Objects.requireNonNull(itemThresholds, "itemThresholds"));
        Objects.requireNonNull(automation, "automation");
        if (itemThresholds.size() > MAXIMUM_FILTER_ITEMS) {
            throw new IllegalArgumentException("too many item-specific settings");
        }
        itemThresholds.forEach((id, value) -> {
            validateItemId(id);
            Objects.requireNonNull(value, "item threshold for " + id);
        });
        HashSet<String> overlap = new HashSet<>(whitelistedItems);
        overlap.retainAll(blacklistedItems);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException("an item cannot be both whitelisted and blacklisted");
        }
    }

    public AppConfig(
            int formatVersion,
            boolean scannerEnabled,
            boolean mockDataMode,
            boolean animationsEnabled,
            boolean notificationsEnabled,
            double interfaceScale,
            ItemFilterMode filterMode,
            Set<String> whitelistedItems,
            Set<String> blacklistedItems,
            Map<String, ItemThresholdConfig> itemThresholds
    ) {
        this(formatVersion, scannerEnabled, mockDataMode, animationsEnabled, notificationsEnabled,
                interfaceScale, filterMode, whitelistedItems, blacklistedItems, itemThresholds,
                AutomationConfig.defaults());
    }

    public static AppConfig defaults() {
        return new AppConfig(
                CURRENT_FORMAT_VERSION, true, true, true, true, 1.0D,
                ItemFilterMode.ALL_ITEMS, Set.of(), Set.of(), Map.of(), AutomationConfig.defaults()
        );
    }

    private static Set<String> validatedIds(Set<String> source, String name) {
        Set<String> copy = Set.copyOf(Objects.requireNonNull(source, name));
        if (copy.size() > MAXIMUM_FILTER_ITEMS) {
            throw new IllegalArgumentException(name + " contains too many items");
        }
        copy.forEach(AppConfig::validateItemId);
        return copy;
    }

    static void validateItemId(String value) {
        Objects.requireNonNull(value, "itemId");
        if (value.length() > 256 || !ITEM_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid namespaced item ID");
        }
    }
}
