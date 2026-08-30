package com.example.donutflipscanner.data.mock;

import com.example.donutflipscanner.data.FlipOpportunity;
import com.example.donutflipscanner.data.OpportunityActionResult;
import com.example.donutflipscanner.data.provider.OpportunityProvider;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

public final class MockOpportunityProvider implements OpportunityProvider {
    private static final List<FlipOpportunity> STARTING_OPPORTUNITIES = List.of(
            opportunity("mock-001", "minecraft:netherite_ingot", "Netherite Ingot", 4,
                    14_200_000, 20_800_000, 6_600_000, 46.0D, 87.0D, 18,
                    19_900_000, 22_100_000, "High", "8s", "QuartzKing", List.of()),
            opportunity("mock-002", "minecraft:totem_of_undying", "Totem of Undying", 2,
                    7_800_000, 9_900_000, 2_100_000, 27.0D, 82.0D, 31,
                    9_500_000, 10_400_000, "High", "16s", "VexTrader", List.of()),
            opportunity("mock-003", "minecraft:enchanted_golden_apple", "Enchanted Golden Apple", 8,
                    18_600_000, 23_500_000, 4_900_000, 26.0D, 91.0D, 42,
                    22_900_000, 24_200_000, "High", "24s", "OrchardVault", List.of()),
            opportunity("mock-004", "minecraft:diamond_block", "Block of Diamond", 16,
                    31_400_000, 36_600_000, 5_200_000, 17.0D, 89.0D, 67,
                    35_800_000, 37_200_000, "High", "31s", "DeepSlate", List.of()),
            opportunity("mock-005", "minecraft:emerald_block", "Block of Emerald", 32,
                    9_600_000, 12_800_000, 3_200_000, 33.0D, 78.0D, 22,
                    12_100_000, 13_400_000, "Medium", "43s", "VillagerCapital", List.of()),
            opportunity("mock-006", "minecraft:diamond_sword", "Sharpness V Diamond Sword", 1,
                    4_100_000, 6_900_000, 2_800_000, 68.0D, 84.0D, 15,
                    6_500_000, 7_300_000, "Medium", "58s", "BladeBroker", List.of()),
            opportunity("mock-007", "minecraft:blue_shulker_box", "Blue Shulker Box", 1,
                    12_300_000, 15_700_000, 3_400_000, 28.0D, 73.0D, 11,
                    14_800_000, 16_600_000, "Medium", "1m", "EnderLogistics", List.of()),
            opportunity("mock-008", "minecraft:trident", "Tidebreaker Trident", 1,
                    3_200_000, 5_100_000, 1_900_000, 59.0D, 42.0D, 4,
                    4_200_000, 6_400_000, "Low", "2m", "DrownedDeals",
                    List.of("Low comparable-sale count", "Wide recent price range"))
    );

    private final CopyOnWriteArrayList<FlipOpportunity> opportunities =
            new CopyOnWriteArrayList<>(STARTING_OPPORTUNITIES);

    @Override
    public List<FlipOpportunity> getOpportunities() {
        return List.copyOf(opportunities);
    }

    @Override
    public CompletableFuture<OpportunityActionResult> reviewManually(String opportunityId) {
        return CompletableFuture.completedFuture(new OpportunityActionResult(
                false, "Manual auction review is not connected in mock mode."
        ));
    }

    @Override
    public CompletableFuture<Boolean> dismiss(String opportunityId) {
        return CompletableFuture.completedFuture(
                opportunities.removeIf(value -> value.opportunityId().equals(opportunityId))
        );
    }

    private static FlipOpportunity opportunity(
            String opportunityId,
            String itemId,
            String itemName,
            int count,
            long listingPrice,
            long fairValue,
            long estimatedProfit,
            double roiPercent,
            double confidencePercent,
            int comparableSales,
            long recentLowPrice,
            long recentHighPrice,
            String liquidity,
            String listingAge,
            String seller,
            List<String> warnings
    ) {
        return new FlipOpportunity(
                opportunityId,
                itemId,
                itemName,
                count,
                listingPrice,
                fairValue,
                estimatedProfit,
                roiPercent,
                confidencePercent,
                comparableSales,
                recentLowPrice,
                recentHighPrice,
                liquidity,
                listingAge,
                seller,
                "NEW",
                List.of(
                        comparableSales + " recent comparable sales",
                        liquidity + " market liquidity",
                        "Mock fair-value estimate"
                ),
                warnings
        );
    }
}
