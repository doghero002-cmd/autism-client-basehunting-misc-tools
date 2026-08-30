package com.example.donutflipscanner.configuration;

import com.example.donutflipscanner.automation.model.AutomationMode;
import com.example.donutflipscanner.automation.model.AuctionInteractionProfile;
import com.example.donutflipscanner.automation.model.RelistPricingStrategy;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;

/** Persisted limits only. Session arming and active executions are deliberately excluded. */
public record AutomationConfig(
        boolean enabled,
        AutomationMode mode,
        Set<String> allowedServerAddresses,
        BigDecimal maximumPurchasePrice,
        BigDecimal maximumOpenExposure,
        int maximumPurchasesPerSession,
        int minimumConfidence,
        int minimumComparableSales,
        BigDecimal minimumNetProfit,
        BigDecimal minimumRoi,
        long maximumListingAgeSeconds,
        long purchaseCooldownSeconds,
        RelistPricingStrategy relistPricingStrategy,
        BigDecimal targetRoi,
        BigDecimal fixedMarkupPercent,
        boolean requireInventoryVerification,
        boolean requireBalanceVerification,
        boolean requireListingVerification,
        boolean showExecutionNotifications,
        Optional<AuctionInteractionProfile> interactionProfile
) {
    public AutomationConfig {
        Objects.requireNonNull(mode, "mode");
        allowedServerAddresses = Set.copyOf(Objects.requireNonNull(
                allowedServerAddresses, "allowedServerAddresses"
        ).stream().map(AutomationConfig::serverAddress).toList());
        maximumPurchasePrice = nonNegative(maximumPurchasePrice, "maximumPurchasePrice");
        maximumOpenExposure = nonNegative(maximumOpenExposure, "maximumOpenExposure");
        minimumNetProfit = nonNegative(minimumNetProfit, "minimumNetProfit");
        minimumRoi = nonNegative(minimumRoi, "minimumRoi");
        targetRoi = nonNegative(targetRoi, "targetRoi");
        fixedMarkupPercent = nonNegative(fixedMarkupPercent, "fixedMarkupPercent");
        Objects.requireNonNull(relistPricingStrategy, "relistPricingStrategy");
        interactionProfile = Objects.requireNonNullElse(interactionProfile, Optional.empty());
        if (maximumPurchasesPerSession < 0 || minimumComparableSales < 0
                || maximumListingAgeSeconds < 1 || purchaseCooldownSeconds < 0) {
            throw new IllegalArgumentException("automation count and duration limits are invalid");
        }
        if (minimumConfidence < 0 || minimumConfidence > 100) {
            throw new IllegalArgumentException("minimumConfidence must be between 0 and 100");
        }
    }

    public AutomationConfig(
            boolean enabled,
            AutomationMode mode,
            Set<String> allowedServerAddresses,
            BigDecimal maximumPurchasePrice,
            BigDecimal maximumOpenExposure,
            int maximumPurchasesPerSession,
            int minimumConfidence,
            int minimumComparableSales,
            BigDecimal minimumNetProfit,
            BigDecimal minimumRoi,
            long maximumListingAgeSeconds,
            long purchaseCooldownSeconds,
            RelistPricingStrategy relistPricingStrategy,
            BigDecimal targetRoi,
            BigDecimal fixedMarkupPercent,
            boolean requireInventoryVerification,
            boolean requireBalanceVerification,
            boolean requireListingVerification,
            boolean showExecutionNotifications
    ) {
        this(enabled, mode, allowedServerAddresses, maximumPurchasePrice, maximumOpenExposure,
                maximumPurchasesPerSession, minimumConfidence, minimumComparableSales,
                minimumNetProfit, minimumRoi, maximumListingAgeSeconds, purchaseCooldownSeconds,
                relistPricingStrategy, targetRoi, fixedMarkupPercent, requireInventoryVerification,
                requireBalanceVerification, requireListingVerification, showExecutionNotifications,
                Optional.empty());
    }

    public static AutomationConfig defaults() {
        return new AutomationConfig(
                false,
                AutomationMode.DRY_RUN,
                Set.of(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0,
                30,
                8,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                30,
                30,
                RelistPricingStrategy.CONSERVATIVE_FAIR_VALUE,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                true,
                true,
                true,
                true,
                Optional.empty()
        );
    }

    public boolean serverAllowed(String address) {
        return allowedServerAddresses.contains(serverAddress(address));
    }

    private static BigDecimal nonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    private static String serverAddress(String value) {
        Objects.requireNonNull(value, "server address");
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > 255 || normalized.contains("*")
                || normalized.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("server addresses must be explicit, bounded values without wildcards");
        }
        return normalized;
    }
}
