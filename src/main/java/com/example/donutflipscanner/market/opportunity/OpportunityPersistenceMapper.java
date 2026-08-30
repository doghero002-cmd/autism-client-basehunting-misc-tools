package com.example.donutflipscanner.market.opportunity;

import com.example.donutflipscanner.database.entity.OpportunityEntity;
import com.example.donutflipscanner.market.confidence.ConfidenceBreakdown;
import com.example.donutflipscanner.market.profit.ProfitBreakdown;
import com.example.donutflipscanner.market.profit.ProfitEvaluation;
import com.example.donutflipscanner.market.value.FairValueEstimate;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/** Converts a structured evaluation to the stable database record consumed by live GUI providers. */
public final class OpportunityPersistenceMapper {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public OpportunityEntity toEntity(OpportunityEvaluation evaluation) {
        Objects.requireNonNull(evaluation, "evaluation");
        Optional<FairValueEstimate> fairValue = evaluation.explanation().fairValue();
        Optional<ProfitEvaluation> profit = evaluation.explanation().profit();
        Optional<ProfitBreakdown> breakdown = profit.flatMap(ProfitEvaluation::breakdown);
        Optional<ConfidenceBreakdown> confidence = evaluation.explanation().confidence();
        BigDecimal conservativeValue = breakdown.map(ProfitBreakdown::conservativeFairValue)
                .or(() -> fairValue.flatMap(FairValueEstimate::conservativeValue))
                .orElse(BigDecimal.ZERO);
        BigDecimal estimatedProfit = breakdown.map(ProfitBreakdown::estimatedNetProfit).orElse(BigDecimal.ZERO);
        BigDecimal roi = breakdown.flatMap(ProfitBreakdown::roiPercent).orElse(BigDecimal.ZERO);
        BigDecimal confidenceScore = confidence.map(value -> BigDecimal.valueOf(value.totalScore()))
                .orElse(BigDecimal.ZERO);
        Optional<String> rejection = evaluation.explanation().rejections().stream()
                .map(value -> value.code().name())
                .findFirst();
        return new OpportunityEntity(
                evaluation.opportunityId(), evaluation.listingKey(), evaluation.itemFingerprint(),
                evaluation.evaluatedAt(), evaluation.listingPrice(), conservativeValue, estimatedProfit,
                roi, confidenceScore, evaluation.state().name(), rejection,
                Optional.of(explanationJson(evaluation)), evaluation.evaluationVersion()
        );
    }

    private String explanationJson(OpportunityEvaluation evaluation) {
        OpportunityExplanation explanation = evaluation.explanation();
        JsonObject root = new JsonObject();
        root.addProperty("accepted", evaluation.accepted());
        root.addProperty("itemId", evaluation.itemId());
        root.addProperty("itemMatchType", explanation.itemMatchType().name());
        root.addProperty("acceptedComparableSales", explanation.acceptedComparableSales());
        root.addProperty("rejectedComparableSales", explanation.rejectedComparableSales());
        explanation.fairValue().flatMap(FairValueEstimate::conservativeValue)
                .ifPresent(value -> root.addProperty("conservativeFairValue", value.toPlainString()));
        explanation.profit().flatMap(ProfitEvaluation::breakdown).ifPresent(value -> {
            root.addProperty("estimatedNetProfit", value.estimatedNetProfit().toPlainString());
            value.roiPercent().ifPresent(roi -> root.addProperty("roiPercent", roi.toPlainString()));
        });
        explanation.confidence().ifPresent(value -> root.addProperty("confidence", value.totalScore()));
        explanation.riskAssessment().ifPresent(value -> {
            root.addProperty("riskLevel", value.riskLevel().name());
            root.addProperty("riskScore", value.riskScore());
        });
        JsonArray rejections = new JsonArray();
        explanation.rejections().forEach(value -> rejections.add(value.code().name()));
        root.add("rejections", rejections);
        return GSON.toJson(root);
    }
}
