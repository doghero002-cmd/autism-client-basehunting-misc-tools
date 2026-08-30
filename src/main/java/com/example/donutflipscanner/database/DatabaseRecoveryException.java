package com.example.donutflipscanner.database;

import java.nio.file.Path;
import java.util.List;

/** Signals fail-closed recovery: evidence was backed up and the original database was not replaced. */
public final class DatabaseRecoveryException extends DatabaseException {
    private final List<Path> backupFiles;

    public DatabaseRecoveryException(String message, Throwable cause, List<Path> backupFiles) {
        super(message, cause);
        this.backupFiles = List.copyOf(backupFiles);
    }

    public List<Path> backupFiles() {
        return backupFiles;
    }
}
