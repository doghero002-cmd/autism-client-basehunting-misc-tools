package com.autism.seedcracker.chatgames;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class ChatGameJournal implements AutoCloseable {
    private static final Set<String> FIELDS = Set.of("type", "roundId", "question", "kind", "mode", "answer",
        "rawAnswer", "revealed", "outcome", "winner", "exactMatch", "normalizedMatch", "correct", "won",
        "queueUs", "detectUs", "solveUs", "reactionUs", "aiWaitUs", "enqueueUs", "configuredDelayMs",
        "command", "sent", "reason", "server", "localWorld");
    private final Path file;
    private final UUID session;
    private final ThreadPoolExecutor writer;
    private final int recordLimit;
    private final long byteLimit;
    private int records;
    private volatile String failure = "";

    public ChatGameJournal(Path directory) throws IOException {
        this(directory, 4096, 4 * 1024 * 1024L);
    }

    ChatGameJournal(Path directory, int recordLimit, long byteLimit) throws IOException {
        Files.createDirectories(directory);
        try (var files = Files.list(directory)) {
            if (files.filter(path -> path.getFileName().toString().endsWith(".jsonl")).limit(64).count() >= 64) {
                throw new IOException("Chat-game session file limit");
            }
        }
        this.recordLimit = recordLimit;
        this.byteLimit = byteLimit;
        session = UUID.randomUUID();
        file = directory.resolve(session + ".jsonl");
        Files.createFile(file);
        writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64), task -> {
            Thread thread = new Thread(task, "Chat-Game-Evidence");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    public Path file() { return file; }
    public String failure() { return failure; }

    public synchronized boolean append(JsonObject event) {
        if (event == null || writer.isShutdown() || !failure.isEmpty()) return false;
        if (records >= recordLimit) { failure = "Record limit reached"; return false; }
        JsonObject snapshot = new JsonObject();
        for (String field : FIELDS) {
            var value = event.get(field);
            if (value == null || !value.isJsonPrimitive()) continue;
            if (value.getAsJsonPrimitive().isString() && value.getAsString().length() > 512) continue;
            if (value.getAsJsonPrimitive().isNumber() && !Double.isFinite(value.getAsDouble())) continue;
            snapshot.add(field, value.deepCopy());
        }
        snapshot.addProperty("schemaVersion", 1);
        snapshot.addProperty("sessionId", session.toString());
        snapshot.addProperty("at", System.currentTimeMillis());
        byte[] line = (snapshot + "\n").getBytes(StandardCharsets.UTF_8);
        if (line.length > 8192) { failure = "Record size limit"; return false; }
        try {
            writer.execute(() -> {
                try {
                    if (Files.size(file) + line.length > byteLimit) throw new IOException("File size limit");
                    Files.write(file, line, StandardOpenOption.APPEND);
                } catch (IOException error) {
                    failure = error.getClass().getSimpleName();
                }
            });
            records++;
            return true;
        } catch (RejectedExecutionException full) {
            failure = "Writer queue full";
            return false;
        }
    }

    @Override public void close() { writer.shutdown(); }
    boolean awaitClosed(long millis) throws InterruptedException { return writer.awaitTermination(millis, TimeUnit.MILLISECONDS); }
}