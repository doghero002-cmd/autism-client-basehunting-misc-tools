package com.autism.seedcracker.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

public final class ClientAiControl {
    private static final String CONTROLLER = "autismclient.ai.aX";
    private static final String FINGERPRINT = "67B22C40A86D8F57AB320DC731589765B0C15373F339C4E64871BEE450252DF0";
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "ANSWERED", "FAILED", "CANCELLED", "SUPERSEDED");
    private static volatile boolean enabled;
    private static volatile boolean registered;
    private static volatile String token = "";
    private static volatile Path tokenFile;
    private static volatile Task task;
    private static volatile JsonObject snapshot = new JsonObject();
    private static volatile long worldRevision;
    private static ClientLevel observedLevel;
    private static Adapter adapter;

    private static final class Task {
        final String id = UUID.randomUUID().toString();
        final String prompt;
        final long revision;
        volatile String status = "QUEUED";
        volatile String result = "";
        String session = "";
        ClientLevel world;
        Task(String prompt, long revision) { this.prompt = prompt; this.revision = revision; }
    }

    private static final class Adapter {
        final Method current;
        final Method initialize;
        final Method submit;
        final Method stop;
        final Method state;
        final Method goal;
        final Method summary;
        final Method message;
        final Method session;
        final Method busy;
        final Method awaiting;
        final Method queued;
        final Map<Enum<?>, String> phases;

        Adapter(Class<?> type, Class<?> phase) throws ReflectiveOperationException {
            current = method(type, "load", type);
            initialize = method(type, "init", type);
            submit = method(type, "write", void.class, String.class);
            stop = method(type, "update", void.class);
            state = method(type, "init", phase);
            goal = method(type, "init", String.class);
            summary = method(type, "read", String.class);
            message = method(type, "flush", String.class);
            session = method(type, "poll", String.class);
            busy = method(type, "close", boolean.class);
            awaiting = method(type, "update", boolean.class);
            queued = method(type, "init", List.class);
            if (!Modifier.isStatic(current.getModifiers()) || !Modifier.isStatic(initialize.getModifiers()) || !phase.isEnum()) throw new NoSuchMethodException("Unsupported AI controller");
            phases = phaseKeys(phase);
        }

        Object current() throws ReflectiveOperationException { return current.invoke(null); }
        String phase(Object controller) throws ReflectiveOperationException {
            String key = phases.get((Enum<?>) state.invoke(controller));
            if (key == null) throw new IllegalStateException("Unknown AI phase");
            return key;
        }
        String text(Method getter, Object controller) throws ReflectiveOperationException {
            return String.valueOf(getter.invoke(controller));
        }
    }

    private ClientAiControl() {}

    public static void register(HttpServer server) { server.createContext("/ai", ClientAiControl::handle); registered = true; }

    public static void shutdown() { disable(); registered = false; }

    public static void enable() throws IOException, ReflectiveOperationException, NoSuchAlgorithmException {
        if (!registered) throw new IllegalStateException("Local bridge is not available");
        ClassLoader loader = ClientAiControl.class.getClassLoader();
        try (var input = loader.getResourceAsStream(CONTROLLER.replace('.', '/') + ".class")) {
            if (input == null || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())).equalsIgnoreCase(FINGERPRINT))
                throw new UnsupportedOperationException("Unverified AI-enabled client build");
        }
        Class<?> type = Class.forName(CONTROLLER, false, loader);
        adapter = new Adapter(type, Class.forName(CONTROLLER + "$EventBus", false, loader));
        if (adapter.current() == null) adapter.initialize.invoke(null);
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String nextToken = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Path nextFile = FabricLoader.getInstance().getGameDir().resolve("run/autism-ai-bridge.token");
        Files.createDirectories(nextFile.getParent());
        Files.writeString(nextFile, nextToken, StandardCharsets.UTF_8);
        tokenFile = nextFile;
        token = nextToken;
        worldRevision++;
        task = null;
        observedLevel = null;
        enabled = true;
        refresh();
    }

    public static void disable() {
        enabled = false;
        Task owned = task;
        Minecraft mc = Minecraft.getInstance();
        if (owned != null && adapter != null) {
            if (mc.isSameThread()) stopOwned(owned);
            else mc.execute(() -> stopOwned(owned));
        }
        String oldToken = token;
        Path oldFile = tokenFile;
        token = "";
        tokenFile = null;
        task = null;
        observedLevel = null;
        snapshot = new JsonObject();
        worldRevision++;
        try {
            if (oldFile != null && Files.isRegularFile(oldFile) && Files.size(oldFile) <= 128
                && Files.readString(oldFile, StandardCharsets.UTF_8).trim().equals(oldToken)) Files.deleteIfExists(oldFile);
        } catch (IOException error) {
            System.err.println("[AI Remote Control] token cleanup failed: " + error.getClass().getSimpleName());
        }
    }

    public static void refresh() {
        if (!enabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (observedLevel != mc.level) { observedLevel = mc.level; worldRevision++; }
        JsonObject next = new JsonObject();
        next.addProperty("inWorld", mc.level != null && mc.player != null);
        next.addProperty("worldRevision", worldRevision);
        Task owned = task;
        try {
            Object controller = adapter.current();
            next.addProperty("ready", controller != null);
            if (controller != null) {
                String phase = adapter.phase(controller);
                String session = adapter.text(adapter.session, controller);
                String goal = adapter.text(adapter.goal, controller);
                boolean awaiting = (boolean) adapter.awaiting.invoke(controller);
                next.addProperty("hostState", ((Enum<?>) adapter.state.invoke(controller)).name());
                next.addProperty("awaitingApproval", awaiting);
                next.addProperty("message", clipped(adapter.text(adapter.message, controller)));
                if (owned != null && !owned.status.equals("QUEUED") && !TERMINAL.contains(owned.status)) {
                    if (owned.world != mc.level || !owns(owned.session, owned.prompt, session, goal)) owned.status = "SUPERSEDED";
                    else {
                        owned.result = taskResult(phase, adapter.text(adapter.summary, controller), adapter.text(adapter.message, controller));
                        owned.status = awaiting ? "AWAITING_APPROVAL" : switch (phase) {
                            case "update" -> "COMPLETED";
                            case "sync" -> "ANSWERED";
                            case "close" -> "FAILED";
                            case "write", "flush" -> "PAUSED";
                            case "init" -> "CANCELLED";
                            default -> "RUNNING";
                        };
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            next.addProperty("ready", false);
            next.addProperty("error", "AI status unavailable: " + error.getClass().getSimpleName());
        }
        snapshot = next;
    }

    private static void submit(Task requested) {
        refresh();
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || task != requested || !dispatchable(requested.status) || requested.revision != worldRevision || mc.level == null || mc.player == null) {
            requested.status = "CANCELLED"; requested.result = "Client/world changed before dispatch"; return;
        }
        try {
            Object controller = adapter.current();
            if (controller == null || !hostAccepts(adapter.phase(controller),
                (boolean) adapter.busy.invoke(controller), (boolean) adapter.awaiting.invoke(controller), ((List<?>) adapter.queued.invoke(controller)).size())) {
                requested.status = "FAILED"; requested.result = "Host AI is not ready, is busy, or has pending user objectives"; return;
            }
            requested.world = mc.level;
            requested.session = adapter.text(adapter.session, controller);
            String previousPhase = adapter.phase(controller);
            String previousGoal = adapter.text(adapter.goal, controller);
            adapter.submit.invoke(controller, requested.prompt);
            String nextGoal = adapter.text(adapter.goal, controller);
            if (!owns(requested.session, requested.prompt, adapter.text(adapter.session, controller), nextGoal)
                || !accepted(previousPhase, previousGoal, adapter.phase(controller), nextGoal,
                    (boolean) adapter.busy.invoke(controller), (boolean) adapter.awaiting.invoke(controller))) {
                requested.status = "FAILED"; requested.result = "Host did not accept the objective; check the client AI panel";
            } else requested.status = "RUNNING";
        } catch (ReflectiveOperationException | RuntimeException error) {
            requested.status = "FAILED"; requested.result = "AI dispatch failed: " + error.getClass().getSimpleName();
        }
        refresh();
    }

    private static void stopOwned(Task owned) {
        if (TERMINAL.contains(owned.status)) return;
        if (dispatchable(owned.status)) { owned.status = "CANCELLED"; return; }
        try {
            Object controller = adapter.current();
            if (controller != null && owned.world == Minecraft.getInstance().level
                && owns(owned.session, owned.prompt, adapter.text(adapter.session, controller), adapter.text(adapter.goal, controller))
                && ((List<?>) adapter.queued.invoke(controller)).isEmpty()) {
                adapter.stop.invoke(controller);
                owned.status = "CANCELLED";
            } else { owned.status = "SUPERSEDED"; owned.result = "Host task/session changed; nothing was stopped"; }
        } catch (ReflectiveOperationException | RuntimeException error) {
            owned.status = "FAILED"; owned.result = "AI stop failed: " + error.getClass().getSimpleName();
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        if (exchange.getRequestHeaders().getFirst("Origin") != null || !allowedHost(exchange.getRequestHeaders().getFirst("Host"))) {
            reply(exchange, 403, error("Native loopback clients only")); return;
        }
        String path = exchange.getRequestURI().getPath();
        String verb = exchange.getRequestMethod();
        if (path.equals("/ai") && verb.equals("GET")) {
            JsonObject available = new JsonObject();
            available.addProperty("enabled", enabled);
            available.addProperty("supportedController", FINGERPRINT);
            if (enabled && tokenFile != null) available.addProperty("tokenFile", tokenFile.toString());
            available.addProperty("ready", snapshot.has("ready") && snapshot.get("ready").getAsBoolean());
            reply(exchange, 200, available); return;
        }
        if (!enabled) { reply(exchange, 503, error("Enable AI Remote Control in the client")); return; }
        if (!authorized(exchange.getRequestHeaders().getFirst("Authorization"), token)) { reply(exchange, 401, error("Authorization required")); return; }
        if (path.equals("/ai/status") && verb.equals("GET")) { reply(exchange, 200, status()); return; }
        if (!verb.equals("POST")) { reply(exchange, 405, error("Use POST for task and cancel")); return; }
        if (!path.equals("/ai/task") && !path.equals("/ai/cancel")) { reply(exchange, 404, error("Unknown AI endpoint")); return; }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) { reply(exchange, 415, error("JSON required")); return; }
        byte[] body = exchange.getRequestBody().readNBytes(16385);
        if (body.length > 16384) { reply(exchange, 413, error("Request too large")); return; }
        JsonObject input;
        try {
            var parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) { reply(exchange, 400, error("JSON object required")); return; }
            input = parsed.getAsJsonObject();
        } catch (JsonParseException parseError) { reply(exchange, 400, error("Invalid JSON")); return; }
        if (path.equals("/ai/task")) {
            String prompt;
            try { prompt = prompt(input); } catch (IllegalArgumentException invalid) { reply(exchange, 400, error(invalid.getMessage())); return; }
            Task requested;
            synchronized (ClientAiControl.class) {
                if (!enabled || !authorized(exchange.getRequestHeaders().getFirst("Authorization"), token)) {
                    reply(exchange, 401, error("Bridge authorization changed before dispatch")); return;
                }
                if (task != null && !TERMINAL.contains(task.status)) { reply(exchange, 409, error("A bridge task is already pending")); return; }
                requested = new Task(prompt, worldRevision);
                task = requested;
            }
            Minecraft.getInstance().execute(() -> submit(requested));
            JsonObject result = new JsonObject();
            result.addProperty("taskId", requested.id); result.addProperty("status", "QUEUED");
            reply(exchange, 202, result);
        } else {
            Task owned = task;
            if (owned == null || !input.has("taskId") || !input.get("taskId").isJsonPrimitive()
                || !input.get("taskId").getAsJsonPrimitive().isString() || !owned.id.equals(input.get("taskId").getAsString())) {
                reply(exchange, 409, error("Current bridge task ID required")); return;
            }
            if (TERMINAL.contains(owned.status)) {
                JsonObject result = new JsonObject(); result.addProperty("taskId", owned.id); result.addProperty("status", owned.status);
                reply(exchange, 200, result); return;
            }
            Minecraft.getInstance().execute(() -> { if (enabled && task == owned) { if (owned.status.equals("QUEUED")) owned.status = "CANCELLED"; else stopOwned(owned); refresh(); } });
            JsonObject result = new JsonObject(); result.addProperty("taskId", owned.id); result.addProperty("status", "CANCEL_REQUESTED");
            reply(exchange, 202, result);
        }
    }

    private static JsonObject status() {
        JsonObject out = snapshot.deepCopy();
        Task owned = task;
        if (owned != null) {
            out.addProperty("taskId", owned.id); out.addProperty("status", owned.status);
            out.addProperty("objective", owned.prompt); out.addProperty("result", owned.result);
        } else out.addProperty("status", "NO_BRIDGE_TASK");
        return out;
    }

    static Method method(Class<?> type, String name, Class<?> result, Class<?>... arguments) throws NoSuchMethodException {
        Method selected = null;
        for (Method candidate : type.getMethods()) {
            if (candidate.getName().equals(name) && candidate.getReturnType() == result && Arrays.equals(candidate.getParameterTypes(), arguments)) {
                if (selected != null) throw new NoSuchMethodException("Ambiguous AI descriptor");
                selected = candidate;
            }
        }
        if (selected == null) throw new NoSuchMethodException("Missing AI descriptor: " + name);
        return selected;
    }

    static boolean authorized(String header, String expected) {
        return header != null && expected != null && !expected.isBlank() && header.length() <= 128
            && MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8), ("Bearer " + expected).getBytes(StandardCharsets.UTF_8));
    }

    static boolean allowedHost(String host) {
        if (host == null) return false;
        String value = host.toLowerCase(Locale.ROOT);
        return value.equals("127.0.0.1:" + GameBridge.port()) || value.equals("localhost:" + GameBridge.port()) || value.equals("127.0.0.1") || value.equals("localhost");
    }

    static boolean owns(String expectedSession, String expectedGoal, String actualSession, String actualGoal) {
        return expectedSession != null && !expectedSession.isBlank() && expectedSession.equals(actualSession)
            && expectedGoal != null && !expectedGoal.isBlank() && expectedGoal.equals(actualGoal);
    }

    static boolean dispatchable(String status) { return "QUEUED".equals(status); }

    static String taskResult(String phase, String summary, String message) {
        return clipped("sync".equals(phase) && message != null && !message.isBlank() ? message : summary);
    }

    static Map<Enum<?>, String> phaseKeys(Class<?> type) throws ReflectiveOperationException {
        Map<Enum<?>, String> keys = new java.util.HashMap<>();
        for (String key : Set.of("init", "load", "read", "write", "sync", "update", "flush", "close")) {
            Enum<?> value = (Enum<?>) type.getField(key).get(null);
            if (keys.put(value, key) != null) throw new NoSuchMethodException("Duplicate AI phase");
        }
        return Map.copyOf(keys);
    }

    static boolean accepted(String previousPhase, String previousGoal, String phase, String goal, boolean busy, boolean awaiting) {
        return busy || awaiting || !java.util.Objects.equals(previousPhase, phase) || !java.util.Objects.equals(previousGoal, goal);
    }

    static boolean hostAccepts(String phase, boolean busy, boolean awaiting, int queued) {
        return phase != null && Set.of("init", "update", "sync", "close").contains(phase)
            && !busy && !awaiting && queued == 0;
    }

    static String prompt(JsonObject input) {
        if (!input.has("prompt") || !input.get("prompt").isJsonPrimitive() || !input.get("prompt").getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("String prompt required");
        String value = input.get("prompt").getAsString().trim();
        if (value.isBlank() || value.length() > 8000) throw new IllegalArgumentException("Prompt must contain 1-8000 characters");
        return value;
    }

    private static String clipped(String value) { return value.length() <= 8000 ? value : value.substring(0, 8000); }
    private static JsonObject error(String message) { JsonObject out = new JsonObject(); out.addProperty("error", message); return out; }
    private static void reply(HttpExchange exchange, int status, JsonObject data) throws IOException {
        byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
        finally { exchange.close(); }
    }
}