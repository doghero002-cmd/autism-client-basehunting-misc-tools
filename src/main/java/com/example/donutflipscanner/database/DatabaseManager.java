package com.example.donutflipscanner.database;

import com.example.donutflipscanner.ModConstants;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the single bounded database executor. JDBC work is never performed by
 * the calling thread.
 */
public final class DatabaseManager implements AutoCloseable {
    public static final Path DEFAULT_RELATIVE_PATH = Path.of(
            "config", ModConstants.CONFIG_DIRECTORY_NAME, "market-data.db"
    );

    private static final int MAXIMUM_QUEUED_OPERATIONS = 256;
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private final Path databasePath;
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> initialization;

    public DatabaseManager(Path databasePath) {
        this.databasePath = Objects.requireNonNull(databasePath, "databasePath").toAbsolutePath().normalize();
        executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAXIMUM_QUEUED_OPERATIONS),
                runnable -> {
                    Thread thread = new Thread(runnable, "donut-market-database");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        initialization = submitRaw(() -> {
            Files.createDirectories(this.databasePath.getParent());
            Class.forName("org.sqlite.JDBC");
            new DatabaseRecoveryManager().verifyExistingDatabase(this.databasePath);
            new DatabaseMigrationManager().migrate(this.databasePath);
            return null;
        });
    }

    public Path databasePath() {
        return databasePath;
    }

    public CompletableFuture<Void> ready() {
        return initialization.thenApply(ignored -> null);
    }

    /** Queues a no-op after prior database work, providing an asynchronous flush barrier. */
    public CompletableFuture<Void> barrier() {
        return execute(connection -> null);
    }

    public int queuedOperationCount() {
        return executor.getQueue().size();
    }

    public int maximumQueuedOperations() {
        return MAXIMUM_QUEUED_OPERATIONS;
    }

    public CompletableFuture<Integer> schemaVersion() {
        return execute(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
                return result.next() ? result.getInt(1) : 0;
            }
        });
    }

    public CompletableFuture<String> integrityCheck() {
        return execute(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("PRAGMA integrity_check")) {
                return result.next() ? result.getString(1) : "no result";
            }
        });
    }

    public CompletableFuture<Void> vacuum() {
        return execute(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("VACUUM");
            }
            return null;
        });
    }

    <T> CompletableFuture<T> execute(DatabaseTransactionManager.SqlOperation<T> operation) {
        Objects.requireNonNull(operation, "operation");
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Database manager is closed"));
        }
        return initialization.thenCompose(ignored -> submitRaw(() -> {
            try (Connection connection = openConnection()) {
                return operation.execute(connection);
            }
        }));
    }

    <T> CompletableFuture<T> transaction(DatabaseTransactionManager.SqlOperation<T> operation) {
        return execute(connection -> DatabaseTransactionManager.execute(connection, operation));
    }

    private Connection openConnection() throws Exception {
        Connection connection = DriverManager.getConnection(jdbcUrl(databasePath));
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        } catch (Exception exception) {
            connection.close();
            throw exception;
        }
        return connection;
    }

    private <T> CompletableFuture<T> submitRaw(ThrowingSupplier<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(action.get());
                } catch (Throwable exception) {
                    future.completeExceptionally(exception instanceof DatabaseException databaseFailure
                            ? databaseFailure
                            : new DatabaseException("Database operation failed", exception));
                }
            });
        } catch (RejectedExecutionException exception) {
            future.completeExceptionally(new DatabaseException("Database operation queue is unavailable", exception));
        }
        return future;
    }

    static String jdbcUrl(Path databasePath) {
        return "jdbc:sqlite:" + databasePath.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
