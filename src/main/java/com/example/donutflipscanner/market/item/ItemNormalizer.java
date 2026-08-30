package com.example.donutflipscanner.market.item;

import com.example.donutflipscanner.api.model.ApiAuctionItem;
import com.example.donutflipscanner.market.item.model.ArmorTrimDescriptor;
import com.example.donutflipscanner.market.item.model.ItemDescriptor;
import com.example.donutflipscanner.market.item.model.ItemEnchantmentDescriptor;
import com.example.donutflipscanner.market.item.model.ItemFingerprint;
import com.example.donutflipscanner.market.item.model.ItemMatchQuality;
import com.example.donutflipscanner.market.item.model.ItemMatchType;
import com.example.donutflipscanner.market.item.model.ItemNormalizationIssue;
import com.example.donutflipscanner.market.item.model.NormalizedArmorTrim;
import com.example.donutflipscanner.market.item.model.NormalizedContainedItem;
import com.example.donutflipscanner.market.item.model.NormalizedEnchantment;
import com.example.donutflipscanner.market.item.model.NormalizedItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;

public final class ItemNormalizer {
    public static final int DEFAULT_MAXIMUM_CONTAINER_DEPTH = 4;
    public static final int MAXIMUM_CONTAINER_ENTRIES = 256;

    private static final Set<ItemNormalizationIssue> ALWAYS_UNSUPPORTED = EnumSet.of(
            ItemNormalizationIssue.MISSING_ITEM_ID,
            ItemNormalizationIssue.INVALID_ITEM_ID,
            ItemNormalizationIssue.INVALID_STACK_COUNT,
            ItemNormalizationIssue.INVALID_ENCHANTMENT,
            ItemNormalizationIssue.INCOMPLETE_ARMOR_TRIM,
            ItemNormalizationIssue.UNKNOWN_API_METADATA,
            ItemNormalizationIssue.DAMAGE_METADATA_UNAVAILABLE,
            ItemNormalizationIssue.VALUE_METADATA_UNAVAILABLE,
            ItemNormalizationIssue.FILLED_CONTAINER_CAUTION,
            ItemNormalizationIssue.UNEXPECTED_CONTAINER_CONTENTS,
            ItemNormalizationIssue.CONTAINER_DEPTH_EXCEEDED,
            ItemNormalizationIssue.CONTAINER_ENTRY_LIMIT_EXCEEDED,
            ItemNormalizationIssue.SERVER_SPECIFIC_ITEM
    );

    private final SafeItemCategoryRegistry categoryRegistry;
    private final int maximumContainerDepth;
    private final ApiItemDescriptorMapper apiMapper = new ApiItemDescriptorMapper();
    private final NamespacedIdNormalizer idNormalizer = new NamespacedIdNormalizer();
    private final CanonicalTextNormalizer textNormalizer = new CanonicalTextNormalizer();
    private final ItemFingerprintFactory fingerprintFactory = new ItemFingerprintFactory();

    public ItemNormalizer() {
        this(SafeItemCategoryRegistry.safeDefaults(), DEFAULT_MAXIMUM_CONTAINER_DEPTH);
    }

    public ItemNormalizer(SafeItemCategoryRegistry categoryRegistry, int maximumContainerDepth) {
        this.categoryRegistry = Objects.requireNonNull(categoryRegistry, "categoryRegistry");
        if (maximumContainerDepth < 1 || maximumContainerDepth > 16) {
            throw new IllegalArgumentException("maximumContainerDepth must be between 1 and 16");
        }
        this.maximumContainerDepth = maximumContainerDepth;
    }

    public NormalizedItem normalize(ApiAuctionItem item) {
        return normalize(apiMapper.map(item));
    }

    public NormalizedItem normalize(ItemDescriptor descriptor) {
        return normalize(descriptor, 0);
    }

    private NormalizedItem normalize(ItemDescriptor descriptor, int depth) {
        Objects.requireNonNull(descriptor, "descriptor");
        EnumSet<ItemNormalizationIssue> issues = EnumSet.noneOf(ItemNormalizationIssue.class);

        NamespacedIdNormalizer.Result itemId = idNormalizer.normalize(descriptor.itemId());
        if (itemId.missing()) {
            issues.add(ItemNormalizationIssue.MISSING_ITEM_ID);
        } else if (!itemId.valid()) {
            issues.add(ItemNormalizationIssue.INVALID_ITEM_ID);
        }

        OptionalInt stackCount = normalizedCount(descriptor.stackCount(), issues);
        Optional<String> customName = normalizeCustomName(itemId.value(), descriptor.displayName());
        List<String> lore = descriptor.lore().stream().map(textNormalizer::canonicalize).toList();
        List<NormalizedEnchantment> enchantments = normalizeEnchantments(descriptor.enchantments(), issues);
        Optional<NormalizedArmorTrim> armorTrim = normalizeTrim(descriptor.armorTrim(), issues);

        List<String> collectedUnrecognizedFields = new ArrayList<>(descriptor.unrecognizedFields());
        descriptor.armorTrim().ifPresent(trim -> trim.unrecognizedFields().stream()
                .map(field -> "trim." + field)
                .forEach(collectedUnrecognizedFields::add));
        List<String> unrecognizedFields = collectedUnrecognizedFields.stream().distinct().sorted().toList();
        if (!unrecognizedFields.isEmpty()) {
            issues.add(ItemNormalizationIssue.UNKNOWN_API_METADATA);
        }

        boolean depthExceeded = depth >= maximumContainerDepth && !descriptor.contents().isEmpty();
        boolean entryLimitExceeded = descriptor.contents().size() > MAXIMUM_CONTAINER_ENTRIES;
        boolean contentsTruncated = depthExceeded || entryLimitExceeded;
        List<NormalizedContainedItem> contents;
        if (depthExceeded) {
            issues.add(ItemNormalizationIssue.CONTAINER_DEPTH_EXCEEDED);
            contents = List.of();
        } else {
            if (entryLimitExceeded) {
                issues.add(ItemNormalizationIssue.CONTAINER_ENTRY_LIMIT_EXCEEDED);
            }
            List<ItemDescriptor> boundedContents = entryLimitExceeded
                    ? descriptor.contents().subList(0, MAXIMUM_CONTAINER_ENTRIES)
                    : descriptor.contents();
            contents = normalizeContents(boundedContents, depth, issues);
        }

        SafeItemCategoryRegistry.Category category = categoryRegistry.category(itemId.value());
        applyCategoryIssues(itemId.value(), category, contents, descriptor.contents(), issues);

        boolean hasValueMetadata = customName.isPresent()
                || !lore.isEmpty()
                || !enchantments.isEmpty()
                || armorTrim.isPresent()
                || !contents.isEmpty();
        ItemMatchType matchType = matchType(category, hasValueMetadata, stackCount, issues);
        List<ItemNormalizationIssue> sortedIssues = issues.stream()
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .toList();
        ItemMatchQuality quality = ItemMatchQuality.of(matchType, sortedIssues);

        ItemFingerprint fingerprint = fingerprintFactory.create(
                matchType,
                itemId.value(),
                customName,
                lore,
                enchantments,
                armorTrim,
                contents,
                contentsTruncated,
                unrecognizedFields
        );
        return new NormalizedItem(
                itemId.value(),
                stackCount,
                customName,
                lore,
                enchantments,
                armorTrim,
                contents,
                contentsTruncated,
                unrecognizedFields,
                quality,
                fingerprint
        );
    }

    private OptionalInt normalizedCount(
            OptionalInt count,
            EnumSet<ItemNormalizationIssue> issues
    ) {
        if (count.isEmpty()) {
            issues.add(ItemNormalizationIssue.MISSING_STACK_COUNT);
            return OptionalInt.empty();
        }
        if (count.getAsInt() < 1) {
            issues.add(ItemNormalizationIssue.INVALID_STACK_COUNT);
            return OptionalInt.empty();
        }
        return count;
    }

    private Optional<String> normalizeCustomName(String itemId, Optional<String> displayName) {
        if (displayName.isEmpty()) {
            return Optional.empty();
        }
        String canonical = textNormalizer.canonicalize(displayName.get());
        String comparison = textNormalizer.comparisonText(canonical);
        if (comparison.isBlank()) {
            return Optional.empty();
        }
        String expected = itemId.substring(itemId.indexOf(':') + 1)
                .replace('_', ' ')
                .toLowerCase(Locale.ROOT);
        boolean decorated = canonical.indexOf('\u00a7') >= 0
                || canonical.startsWith("{")
                || canonical.startsWith("[")
                || canonical.startsWith("\"");
        return comparison.equals(expected) && !decorated ? Optional.empty() : Optional.of(canonical);
    }

    private List<NormalizedEnchantment> normalizeEnchantments(
            List<ItemEnchantmentDescriptor> values,
            EnumSet<ItemNormalizationIssue> issues
    ) {
        List<NormalizedEnchantment> normalized = new ArrayList<>();
        Set<String> encounteredIds = new HashSet<>();
        for (ItemEnchantmentDescriptor value : values) {
            NamespacedIdNormalizer.Result id = idNormalizer.normalize(Optional.ofNullable(value.id()));
            if (id.missing() || !id.valid() || value.level() < 1 || !encounteredIds.add(id.value())) {
                issues.add(ItemNormalizationIssue.INVALID_ENCHANTMENT);
            }
            normalized.add(new NormalizedEnchantment(id.value(), value.level()));
        }
        normalized.sort(Comparator.naturalOrder());
        return List.copyOf(normalized);
    }

    private Optional<NormalizedArmorTrim> normalizeTrim(
            Optional<ArmorTrimDescriptor> value,
            EnumSet<ItemNormalizationIssue> issues
    ) {
        if (value.isEmpty()) {
            return Optional.empty();
        }
        ArmorTrimDescriptor descriptor = value.orElseThrow();
        Optional<String> materialValue = descriptor.material().filter(text -> !text.isBlank());
        Optional<String> patternValue = descriptor.pattern().filter(text -> !text.isBlank());
        if (materialValue.isEmpty() && patternValue.isEmpty() && descriptor.unrecognizedFields().isEmpty()) {
            return Optional.empty();
        }
        NamespacedIdNormalizer.Result material = idNormalizer.normalize(materialValue);
        NamespacedIdNormalizer.Result pattern = idNormalizer.normalize(patternValue);
        if (material.missing() || pattern.missing() || !material.valid() || !pattern.valid()) {
            issues.add(ItemNormalizationIssue.INCOMPLETE_ARMOR_TRIM);
        }
        return Optional.of(new NormalizedArmorTrim(material.value(), pattern.value()));
    }

    private List<NormalizedContainedItem> normalizeContents(
            List<ItemDescriptor> descriptors,
            int parentDepth,
            EnumSet<ItemNormalizationIssue> parentIssues
    ) {
        Map<String, ContentAccumulator> grouped = new TreeMap<>();
        List<NormalizedContainedItem> missingCounts = new ArrayList<>();
        for (ItemDescriptor descriptor : descriptors) {
            NormalizedItem item = normalize(descriptor, parentDepth + 1);
            if (item.matchQuality().issues().contains(ItemNormalizationIssue.CONTAINER_DEPTH_EXCEEDED)) {
                parentIssues.add(ItemNormalizationIssue.CONTAINER_DEPTH_EXCEEDED);
            }
            if (item.matchQuality().matchType() == ItemMatchType.UNSUPPORTED) {
                parentIssues.add(ItemNormalizationIssue.VALUE_METADATA_UNAVAILABLE);
            }
            if (item.stackCount().isEmpty()) {
                missingCounts.add(new NormalizedContainedItem(OptionalInt.empty(), item));
                continue;
            }
            ContentAccumulator accumulator = grouped.computeIfAbsent(
                    item.fingerprint().sha256(),
                    ignored -> new ContentAccumulator(item)
            );
            accumulator.count += item.stackCount().getAsInt();
            if (accumulator.count > Integer.MAX_VALUE) {
                parentIssues.add(ItemNormalizationIssue.INVALID_STACK_COUNT);
            }
        }

        List<NormalizedContainedItem> result = new ArrayList<>();
        for (ContentAccumulator value : grouped.values()) {
            OptionalInt count = value.count > Integer.MAX_VALUE
                    ? OptionalInt.empty()
                    : OptionalInt.of((int) value.count);
            result.add(new NormalizedContainedItem(count, value.item));
        }
        result.addAll(missingCounts);
        result.sort(Comparator.comparing((NormalizedContainedItem value) -> value.item().fingerprint().sha256())
                .thenComparingInt(value -> value.count().orElse(-1)));
        return List.copyOf(result);
    }

    private void applyCategoryIssues(
            String itemId,
            SafeItemCategoryRegistry.Category category,
            List<NormalizedContainedItem> contents,
            List<ItemDescriptor> rawContents,
            EnumSet<ItemNormalizationIssue> issues
    ) {
        if (!itemId.startsWith("minecraft:") && category == SafeItemCategoryRegistry.Category.UNSUPPORTED) {
            issues.add(ItemNormalizationIssue.SERVER_SPECIFIC_ITEM);
        }
        if (category == SafeItemCategoryRegistry.Category.UNSUPPORTED) {
            if (categoryRegistry.damageMetadataRequired(itemId)) {
                issues.add(ItemNormalizationIssue.DAMAGE_METADATA_UNAVAILABLE);
            } else if (categoryRegistry.valueMetadataUnavailable(itemId)) {
                issues.add(ItemNormalizationIssue.VALUE_METADATA_UNAVAILABLE);
            }
        }
        if (category == SafeItemCategoryRegistry.Category.VISIBLE_METADATA_ONLY) {
            issues.add(ItemNormalizationIssue.VISIBLE_METADATA_ONLY);
            if (categoryRegistry.damageMetadataRequired(itemId)) {
                issues.add(ItemNormalizationIssue.DURABILITY_NOT_EXPOSED);
            }
        }
        if (category == SafeItemCategoryRegistry.Category.CONTAINER && !rawContents.isEmpty()) {
            issues.add(ItemNormalizationIssue.FILLED_CONTAINER_CAUTION);
        } else if (category != SafeItemCategoryRegistry.Category.CONTAINER && !rawContents.isEmpty()) {
            issues.add(ItemNormalizationIssue.UNEXPECTED_CONTAINER_CONTENTS);
        }
        if (category == SafeItemCategoryRegistry.Category.UNREGISTERED) {
            issues.add(ItemNormalizationIssue.CATEGORY_NOT_EXPLICITLY_SAFE);
        }
        if (contents.stream().anyMatch(value -> value.count().isEmpty())) {
            issues.add(ItemNormalizationIssue.INVALID_STACK_COUNT);
        }
    }

    private ItemMatchType matchType(
            SafeItemCategoryRegistry.Category category,
            boolean hasValueMetadata,
            OptionalInt count,
            EnumSet<ItemNormalizationIssue> issues
    ) {
        if (issues.stream().anyMatch(ALWAYS_UNSUPPORTED::contains)) {
            return ItemMatchType.UNSUPPORTED;
        }
        if (category == SafeItemCategoryRegistry.Category.UNSUPPORTED) {
            return ItemMatchType.UNSUPPORTED;
        }
        if (category == SafeItemCategoryRegistry.Category.COMMODITY) {
            if (count.isEmpty()) {
                return ItemMatchType.UNSUPPORTED;
            }
            return hasValueMetadata ? ItemMatchType.EXACT : ItemMatchType.COMMODITY;
        }
        if (category == SafeItemCategoryRegistry.Category.VISIBLE_METADATA_ONLY) {
            return count.isPresent() ? ItemMatchType.VISIBLE_METADATA : ItemMatchType.UNSUPPORTED;
        }
        if (issues.contains(ItemNormalizationIssue.MISSING_STACK_COUNT)
                || category == SafeItemCategoryRegistry.Category.UNREGISTERED) {
            return ItemMatchType.APPROXIMATE;
        }
        return ItemMatchType.EXACT;
    }

    private static final class ContentAccumulator {
        private final NormalizedItem item;
        private long count;

        private ContentAccumulator(NormalizedItem item) {
            this.item = item;
        }
    }
}
