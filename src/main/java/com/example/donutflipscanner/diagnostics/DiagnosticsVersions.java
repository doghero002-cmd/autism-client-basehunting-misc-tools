package com.example.donutflipscanner.diagnostics;

import java.util.Objects;

public record DiagnosticsVersions(String modVersion, String minecraftVersion, String fabricLoaderVersion) {
    public DiagnosticsVersions {
        modVersion = required(modVersion, "modVersion");
        minecraftVersion = required(minecraftVersion, "minecraftVersion");
        fabricLoaderVersion = required(fabricLoaderVersion, "fabricLoaderVersion");
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}
