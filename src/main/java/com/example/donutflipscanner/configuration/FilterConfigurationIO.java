package com.example.donutflipscanner.configuration;

import com.example.donutflipscanner.market.opportunity.ItemFilterPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Imports only filter fields; unrelated settings in an input file are never applied. */
public final class FilterConfigurationIO {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final ConfigurationCodec codec = new ConfigurationCodec();

    public ItemFilterPolicy read(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            Path normalized = path.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(normalized)
                    || Files.size(normalized) > ConfigurationCodec.MAXIMUM_CONFIG_CHARACTERS * 4L) {
                throw new IOException("filter import is unsafe or too large");
            }
            AppConfig parsed = codec.decode(Files.readString(normalized, StandardCharsets.UTF_8));
            return new ItemFilterPolicy(
                    parsed.filterMode(), parsed.whitelistedItems(), parsed.blacklistedItems()
            );
        } catch (IOException | RuntimeException error) {
            throw new ConfigurationException("Unable to import the filter configuration", error);
        }
    }

    public void write(Path path, ItemFilterPolicy filters) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(filters, "filters");
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", AppConfig.CURRENT_FORMAT_VERSION);
        root.addProperty("filterMode", filters.mode().name());
        root.add("whitelistedItems", ids(filters.whitelistedItemIds()));
        root.add("blacklistedItems", ids(filters.blacklistedItemIds()));
        Path normalized = path.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new ConfigurationException("Filter export path has no parent",
                    new IllegalArgumentException("missing parent"));
        }
        try {
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, "filters-", ".tmp");
            boolean moved = false;
            try {
                Files.writeString(temporary, GSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
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
            throw new ConfigurationException("Unable to export the filter configuration", error);
        }
    }

    private static JsonArray ids(java.util.Set<String> values) {
        JsonArray array = new JsonArray();
        values.stream().sorted().forEach(array::add);
        return array;
    }
}
