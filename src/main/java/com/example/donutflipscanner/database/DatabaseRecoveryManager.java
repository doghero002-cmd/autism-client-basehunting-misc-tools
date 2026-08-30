package com.example.donutflipscanner.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Detects SQLite corruption without deleting or replacing user history. */
final class DatabaseRecoveryManager {
    void verifyExistingDatabase(Path databasePath) throws Exception {
        if (!Files.exists(databasePath) || Files.size(databasePath) == 0L) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(DatabaseManager.jdbcUrl(databasePath));
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
            while (result.next()) {
                if (!"ok".equalsIgnoreCase(result.getString(1))) {
                    throw new RecoveryMarker(corrupt(
                            databasePath, new SQLException("SQLite quick check reported corruption")
                    ));
                }
            }
        } catch (SQLException error) {
            if (error instanceof RecoveryMarker marker) {
                throw marker.recovery;
            }
            if (isCorruption(error)) {
                throw corrupt(databasePath, error);
            }
            throw error;
        }
    }

    private DatabaseRecoveryException corrupt(Path databasePath, SQLException cause) throws IOException {
        Path backupDirectory = databasePath.getParent().resolve("backups");
        Files.createDirectories(backupDirectory);
        String stamp = Long.toString(Instant.now().toEpochMilli());
        List<Path> backups = new ArrayList<>();
        backupIfPresent(databasePath, backupDirectory, stamp, backups);
        backupIfPresent(Path.of(databasePath + "-wal"), backupDirectory, stamp, backups);
        backupIfPresent(Path.of(databasePath + "-shm"), backupDirectory, stamp, backups);
        return new DatabaseRecoveryException(
                "The market database is corrupted. A backup was preserved; automatic replacement was refused.",
                cause,
                backups
        );
    }

    private void backupIfPresent(Path source, Path directory, String stamp, List<Path> backups) throws IOException {
        if (!Files.exists(source)) {
            return;
        }
        Path backup = directory.resolve(source.getFileName() + ".corrupt-" + stamp + ".bak");
        Files.copy(source, backup, StandardCopyOption.COPY_ATTRIBUTES);
        backups.add(backup);
    }

    private static boolean isCorruption(SQLException error) {
        int code = error.getErrorCode();
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(java.util.Locale.ROOT);
        return code == 11 || code == 26 || message.contains("malformed")
                || message.contains("not a database") || message.contains("corrupt");
    }

    private static final class RecoveryMarker extends SQLException {
        private final DatabaseRecoveryException recovery;

        private RecoveryMarker(DatabaseRecoveryException recovery) {
            this.recovery = recovery;
        }
    }
}
