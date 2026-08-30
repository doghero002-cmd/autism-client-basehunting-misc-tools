package com.example.donutflipscanner.configuration;

import com.example.donutflipscanner.ModConstants;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded JSON persistence with atomic replacement and explicit corruption backups. */
public final class ConfigurationManager {
    public static final Path DEFAULT_RELATIVE_PATH = Path.of(
            "config", ModConstants.CONFIG_DIRECTORY_NAME, "config.json"
    );

    private final Path configurationPath;
    private final ConfigurationCodec codec = new ConfigurationCodec();

    public ConfigurationManager(Path configurationPath) {
        this.configurationPath = Objects.requireNonNull(configurationPath, "configurationPath")
                .toAbsolutePath().normalize();
    }

    public ConfigurationLoadResult load() {
        if (!Files.exists(configurationPath)) {
            AppConfig defaults = AppConfig.defaults();
            save(defaults);
            return new ConfigurationLoadResult(defaults, false, Optional.empty(), List.of());
        }
        try {
            if (Files.isSymbolicLink(configurationPath)) {
                throw new IOException("symbolic-link configurations are not accepted");
            }
            long byteLimit = ConfigurationCodec.MAXIMUM_CONFIG_CHARACTERS * 4L;
            if (Files.size(configurationPath) > byteLimit) {
                throw new IOException("configuration exceeds the byte limit");
            }
            String json = Files.readString(configurationPath, StandardCharsets.UTF_8);
            AppConfig config = codec.decode(json);
            return new ConfigurationLoadResult(config, false, Optional.empty(), List.of());
        } catch (IOException | RuntimeException corrupt) {
            return recoverCorruptConfiguration(corrupt);
        }
    }

    public void save(AppConfig configuration) {
        Objects.requireNonNull(configuration, "configuration");
        try {
            Files.createDirectories(parent());
            Path temporary = Files.createTempFile(parent(), "config-", ".tmp");
            boolean moved = false;
            try {
                byte[] bytes = codec.encode(configuration).getBytes(StandardCharsets.UTF_8);
                Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
                try {
                    Files.move(temporary, configurationPath, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, configurationPath, StandardCopyOption.REPLACE_EXISTING);
                }
                moved = true;
            } finally {
                if (!moved) {
                    Files.deleteIfExists(temporary);
                }
            }
        } catch (IOException error) {
            throw new ConfigurationException("Unable to save the local configuration", error);
        }
    }

    public Path configurationPath() {
        return configurationPath;
    }

    private ConfigurationLoadResult recoverCorruptConfiguration(Throwable cause) {
        try {
            Path backupDirectory = parent().resolve("backups");
            Files.createDirectories(backupDirectory);
            Path backup = backupDirectory.resolve(
                    "config.corrupt-" + Instant.now().toEpochMilli() + ".json.bak"
            );
            Files.copy(configurationPath, backup, StandardCopyOption.COPY_ATTRIBUTES);
            AppConfig defaults = AppConfig.defaults();
            save(defaults);
            return new ConfigurationLoadResult(
                    defaults, true, Optional.of(backup),
                    List.of("The configuration was invalid. A backup was preserved and defaults were loaded.")
            );
        } catch (IOException | RuntimeException recoveryFailure) {
            recoveryFailure.addSuppressed(cause);
            throw new ConfigurationException(
                    "Configuration is invalid and could not be backed up safely", recoveryFailure
            );
        }
    }

    private Path parent() {
        Path parent = configurationPath.getParent();
        if (parent == null) {
            throw new ConfigurationException("Configuration path has no parent directory",
                    new IllegalArgumentException("missing parent"));
        }
        return parent;
    }
}
