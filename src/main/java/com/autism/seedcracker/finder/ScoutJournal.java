package com.autism.seedcracker.finder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class ScoutJournal implements AutoCloseable {
    private final Path file;
    private final ThreadPoolExecutor writer;
    private volatile String failure = "";
    private int records;

    public ScoutJournal(Path directory, UUID session) throws IOException {
        Files.createDirectories(directory);
        file = directory.resolve(session + ".jsonl");
        Files.createFile(file);
        writer = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(64), task -> {
            Thread thread = new Thread(task,"Scout-Evidence"); thread.setDaemon(true); return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }

    public Path file() { return file; }
    public String failure() { return failure; }
    public boolean append(JsonObject event) {
        String line = event.toString() + "\n";
        if (!failure.isEmpty() || records >= 512 || line.length() > 8192 || writer.isShutdown()) return false;
        try {
            writer.execute(() -> {
                try {
                    if (Files.size(file) >= 4 * 1024 * 1024) throw new IOException("Evidence size limit");
                    Files.writeString(file,line,StandardCharsets.UTF_8,StandardOpenOption.APPEND);
                } catch (IOException error) { failure = error.getClass().getSimpleName(); }
            });
            records++; return true;
        } catch (java.util.concurrent.RejectedExecutionException full) { failure = "Evidence queue full"; return false; }
    }

    @Override public void close() { writer.shutdown(); }
    boolean awaitClosed(long millis) throws InterruptedException { return writer.awaitTermination(millis,TimeUnit.MILLISECONDS); }

    public static void review(Path directory, String session, String candidate, String label) throws IOException {
        UUID sessionId = UUID.fromString(session), candidateId = UUID.fromString(candidate);
        if (!Set.of("confirmed-base","natural","unknown").contains(label)) throw new IllegalArgumentException("Review label must be confirmed-base, natural or unknown");
        Path file = directory.resolve(sessionId + ".jsonl");
        if (!Files.isRegularFile(file) || Files.size(file) > 4 * 1024 * 1024) throw new IOException("Evidence file unavailable or too large");
        boolean found = false;
        for (String line : Files.readAllLines(file,StandardCharsets.UTF_8)) {
            if (line.length() > 8192) throw new IOException("Invalid evidence record");
            try {
                var event = JsonParser.parseString(line).getAsJsonObject();
                if (event.has("type") && "candidate".equals(event.get("type").getAsString()) && event.has("candidateId")
                    && candidateId.toString().equals(event.get("candidateId").getAsString())) { found = true; break; }
            } catch (RuntimeException malformed) { throw new IOException("Malformed evidence record",malformed); }
        }
        if (!found) throw new IllegalArgumentException("Candidate does not belong to this session");
        JsonObject event = new JsonObject(); event.addProperty("type","review"); event.addProperty("candidateId",candidateId.toString());
        event.addProperty("label",label); event.addProperty("reviewer","manual"); event.addProperty("at",System.currentTimeMillis());
        Files.writeString(file,event + "\n",StandardCharsets.UTF_8,StandardOpenOption.APPEND);
    }
}