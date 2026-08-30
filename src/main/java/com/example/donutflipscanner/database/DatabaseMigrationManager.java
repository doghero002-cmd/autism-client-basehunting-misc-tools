package com.example.donutflipscanner.database;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

public final class DatabaseMigrationManager {
    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "market records", "/db/migration/V001__market_records.sql", false),
            new Migration(2, "market statistics", "/db/migration/V002__market_statistics.sql", false),
            new Migration(3, "opportunities", "/db/migration/V003__opportunities.sql", false),
            new Migration(4, "trade execution journal", "/db/migration/V004__trade_execution_journal.sql", false),
            new Migration(5, "personal profit ledger", "/db/migration/V005__personal_profit_ledger.sql", false)
    );

    public void migrate(Path databasePath) throws Exception {
        int currentVersion;
        try (Connection connection = open(databasePath)) {
            prepareVersionTable(connection);
            currentVersion = currentVersion(connection);
            checkpoint(connection);
        }

        if (MIGRATIONS.stream().anyMatch(migration -> migration.version() > currentVersion && migration.destructive())) {
            backup(databasePath, currentVersion);
        }

        try (Connection connection = open(databasePath)) {
            for (Migration migration : MIGRATIONS) {
                if (migration.version() > currentVersion) {
                    apply(connection, migration);
                }
            }
        }
    }

    private Connection open(Path databasePath) throws SQLException {
        Connection connection = DriverManager.getConnection(DatabaseManager.jdbcUrl(databasePath));
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA journal_mode = WAL");
        }
        return connection;
    }

    private void prepareVersionTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS schema_version (
                        version INTEGER PRIMARY KEY,
                        description TEXT NOT NULL,
                        applied_at INTEGER NOT NULL
                    )
                    """);
        }
    }

    private int currentVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private void apply(Connection connection, Migration migration) throws Exception {
        String script = readScript(migration.resource());
        DatabaseTransactionManager.execute(connection, transactionalConnection -> {
            for (String sql : statements(script)) {
                try (Statement statement = transactionalConnection.createStatement()) {
                    statement.execute(sql);
                }
            }
            try (PreparedStatement statement = transactionalConnection.prepareStatement(
                    "INSERT INTO schema_version(version, description, applied_at) VALUES (?, ?, ?)")) {
                statement.setInt(1, migration.version());
                statement.setString(2, migration.description());
                statement.setLong(3, Instant.now().toEpochMilli());
                statement.executeUpdate();
            }
            return null;
        });
    }

    private String readScript(String resource) throws IOException {
        try (InputStream stream = DatabaseMigrationManager.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Missing embedded database migration");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private List<String> statements(String script) {
        String withoutComments = script.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .collect(Collectors.joining("\n"));
        return java.util.Arrays.stream(withoutComments.split(";"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private void checkpoint(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
    }

    private void backup(Path databasePath, int currentVersion) throws IOException {
        if (!Files.exists(databasePath)) {
            return;
        }
        Path backupDirectory = databasePath.getParent().resolve("backups");
        Files.createDirectories(backupDirectory);
        String fileName = databasePath.getFileName() + ".before-v" + (currentVersion + 1)
                + "-" + Instant.now().toEpochMilli() + ".bak";
        Files.copy(databasePath, backupDirectory.resolve(fileName), StandardCopyOption.COPY_ATTRIBUTES);
    }

    private record Migration(int version, String description, String resource, boolean destructive) {
    }
}
