package com.example.donutflipscanner.diagnostics;

import com.example.donutflipscanner.security.SensitiveDataSanitizer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Writes a support-safe JSON report. The report contains no credential or filesystem fields. */
public final class DiagnosticsExporter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public void export(Path target, DiagnosticsSnapshot snapshot) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(snapshot, "snapshot");
        Path normalized = target.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("diagnostics target must have a parent");
        }
        try {
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, "diagnostics-", ".tmp");
            boolean moved = false;
            try {
                Files.writeString(temporary, encode(snapshot), StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, normalized, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, normalized, StandardCopyOption.REPLACE_EXISTING);
                }
                moved = true;
            } finally {
                if (!moved) {
                    Files.deleteIfExists(temporary);
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("Unable to export sanitized diagnostics", error);
        }
    }

    String encode(DiagnosticsSnapshot value) {
        JsonObject root = new JsonObject();
        root.addProperty("generatedAt", value.generatedAt().toString());
        JsonObject versions = new JsonObject();
        versions.addProperty("mod", value.versions().modVersion());
        versions.addProperty("minecraft", value.versions().minecraftVersion());
        versions.addProperty("fabricLoader", value.versions().fabricLoaderVersion());
        root.add("versions", versions);
        root.addProperty("scannerState", value.scannerState().name());
        root.addProperty("apiState", value.apiState().name());
        JsonObject requests = new JsonObject();
        requests.addProperty("attempted", value.attemptedApiRequests());
        requests.addProperty("successful", value.successfulApiRequests());
        requests.addProperty("failed", value.failedApiRequests());
        requests.addProperty("currentRateWindow", value.requestsInCurrentWindow());
        requests.addProperty("averageLatencyMillis", millis(value.averageRequestLatency()));
        root.add("apiRequests", requests);
        JsonObject records = new JsonObject();
        records.addProperty("fingerprints", value.databaseRecords().fingerprints());
        records.addProperty("activeListings", value.databaseRecords().activeListings());
        records.addProperty("completedSales", value.databaseRecords().completedSales());
        records.addProperty("marketStatistics", value.databaseRecords().marketStatistics());
        records.addProperty("opportunities", value.databaseRecords().opportunities());
        root.add("databaseRecords", records);
        root.addProperty("latestMigrationVersion", value.latestMigrationVersion());
        root.add("performance", performance(value.performance()));
        value.lastSanitizedError().ifPresent(error ->
                root.addProperty("lastSanitizedError", SensitiveDataSanitizer.sanitize(error)));
        return GSON.toJson(root) + System.lineSeparator();
    }

    private static JsonObject performance(PerformanceSnapshot value) {
        JsonObject result = new JsonObject();
        JsonObject timings = new JsonObject();
        for (Map.Entry<PerformanceOperation, TimingMetricSnapshot> entry : value.timings().entrySet()) {
            JsonObject timing = new JsonObject();
            timing.addProperty("samples", entry.getValue().samples());
            timing.addProperty("averageMicros", micros(entry.getValue().average()));
            timing.addProperty("maximumMicros", micros(entry.getValue().maximum()));
            timings.add(entry.getKey().name(), timing);
        }
        result.add("timings", timings);
        JsonObject caches = new JsonObject();
        caches.addProperty("pageHashes", value.caches().pageHashEntries());
        caches.addProperty("trackedOpportunities", value.caches().trackedOpportunities());
        caches.addProperty("knownActiveListings", value.caches().knownActiveListings());
        caches.addProperty("estimatedBytes", value.caches().estimatedBytes());
        result.add("caches", caches);
        result.addProperty("databaseQueueSize", value.databaseQueueSize());
        result.addProperty("opportunityQueueSize", value.opportunityQueueSize());
        return result;
    }

    private static long millis(Duration value) {
        return Math.max(0L, value.toMillis());
    }

    private static long micros(Duration value) {
        return Math.max(0L, value.toNanos() / 1_000L);
    }
}
