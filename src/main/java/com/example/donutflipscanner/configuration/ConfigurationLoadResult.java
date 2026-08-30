package com.example.donutflipscanner.configuration;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ConfigurationLoadResult(
        AppConfig configuration,
        boolean recoveredFromCorruption,
        Optional<Path> backupPath,
        List<String> warnings
) {
    public ConfigurationLoadResult {
        Objects.requireNonNull(configuration, "configuration");
        backupPath = Objects.requireNonNullElse(backupPath, Optional.empty());
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }
}
