package com.autism.seedcracker.bridge;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.lang.classfile.ClassFile;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientAiControlTest {
    @Test void answeredQuestionsReturnTheActualAnswerRatherThanPlaceholderSummary() {
        assertEquals("minecraft:overworld", ClientAiControl.taskResult("sync", "Not started", "minecraft:overworld"));
        assertEquals("Completed objective", ClientAiControl.taskResult("update", "Completed objective", "Finished"));
        assertEquals("Summary answer", ClientAiControl.taskResult("sync", "Summary answer", ""));
        assertEquals(8000, ClientAiControl.taskResult("sync", "", "x".repeat(9000)).length());
    }

    public enum NamedPhase {
        IDLE, PREPARING, RUNNING, PAUSED, ANSWERED, COMPLETED, THREAT_ESCALATED, FAILED;
        public static final NamedPhase init = IDLE, load = PREPARING, read = RUNNING, write = PAUSED,
            sync = ANSWERED, update = COMPLETED, flush = THREAT_ESCALATED, close = FAILED;
    }

    @Test void obfuscatedEnumFieldsDoNotHaveToMatchRuntimeEnumNames() throws Exception {
        var phases = ClientAiControl.phaseKeys(NamedPhase.class);
        assertEquals("init", phases.get(NamedPhase.IDLE));
        assertEquals("update", phases.get(NamedPhase.COMPLETED));
        assertTrue(ClientAiControl.hostAccepts(phases.get(NamedPhase.IDLE), false, false, 0));
        assertFalse(ClientAiControl.hostAccepts(phases.get(NamedPhase.PAUSED), false, false, 0));
    }

    @Test void alternateQaPortDoesNotChangeTheSafeDefaultOrAcceptInvalidPorts() {
        assertEquals(25661, GameBridge.configuredPort(25661));
        assertEquals(25660, GameBridge.configuredPort(0));
        assertEquals(25660, GameBridge.configuredPort(65536));
        assertEquals(25660, GameBridge.port());
        assertFalse(ClientAiControl.allowedHost("localhost:25661"));
    }

    @Test void vendoredControllerFingerprintAndDescriptorsMatchWithoutInitializingHostAi() throws Exception {
        try (ZipFile jar = new ZipFile(Path.of("libs/autism-5.1-26.2-ai.jar").toFile())) {
            byte[] controller;
            try (var input = jar.getInputStream(jar.getEntry("autismclient/ai/aX.class"))) { controller = input.readAllBytes(); }
            assertEquals("67B22C40A86D8F57AB320DC731589765B0C15373F339C4E64871BEE450252DF0", HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(controller)));
            var parsed = ClassFile.of().parse(controller);
            for (String descriptor : Set.of("init()Lautismclient/ai/aX;", "load()Lautismclient/ai/aX;", "write(Ljava/lang/String;)V", "update()V", "init()Lautismclient/ai/aX$EventBus;", "init()Ljava/lang/String;", "read()Ljava/lang/String;", "flush()Ljava/lang/String;", "poll()Ljava/lang/String;", "close()Z", "update()Z", "init()Ljava/util/List;")) {
                assertTrue(parsed.methods().stream().anyMatch(method -> (method.methodName().stringValue() + method.methodType().stringValue()).equals(descriptor)), descriptor);
            }
            byte[] phase;
            try (var input = jar.getInputStream(jar.getEntry("autismclient/ai/aX$EventBus.class"))) { phase = input.readAllBytes(); }
            Set<String> constants = ClassFile.of().parse(phase).fields().stream().filter(field -> field.fieldType().stringValue().equals("Lautismclient/ai/aX$EventBus;")).map(field -> field.fieldName().stringValue()).collect(java.util.stream.Collectors.toSet());
            assertEquals(Set.of("init", "load", "read", "write", "sync", "update", "flush", "close"), constants);
        }
    }

    @Test void rejectedRepeatOfCompletedGoalCannotBeMistakenForAcceptedWork() {
        assertFalse(ClientAiControl.accepted("update", "goal", "update", "goal", false, false));
        assertTrue(ClientAiControl.accepted("update", "goal", "load", "goal", true, false));
        assertTrue(ClientAiControl.accepted("init", "old", "load", "new", true, false));
    }

    @Test void actualHttpHandlerRejectsUnauthorizedOriginsMethodsAndBodies() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var enabled = ClientAiControl.class.getDeclaredField("enabled"); enabled.setAccessible(true);
        var token = ClientAiControl.class.getDeclaredField("token"); token.setAccessible(true);
        var registered = ClientAiControl.class.getDeclaredField("registered"); registered.setAccessible(true);
        Object oldEnabled = enabled.get(null), oldToken = token.get(null), oldRegistered = registered.get(null);
        try {
            ClientAiControl.register(server); server.start();
            enabled.set(null, true); token.set(null, "fixture-token");
            int port = server.getAddress().getPort();
            assertEquals(200, http(port, "GET", "/ai", "", ""));
            assertEquals(401, http(port, "GET", "/ai/status", "", ""));
            String auth = "Authorization: Bearer fixture-token\r\n";
            assertEquals(200, http(port, "GET", "/ai/status", auth, ""));
            assertEquals(403, http(port, "GET", "/ai/status", auth + "Origin: http://localhost\r\n", ""));
            assertEquals(405, http(port, "GET", "/ai/task", auth, ""));
            assertEquals(404, http(port, "POST", "/ai/unknown", auth, "{}"));
            assertEquals(415, http(port, "POST", "/ai/task", auth, "{}"));
            String json = auth + "Content-Type: application/json\r\n";
            assertEquals(400, http(port, "POST", "/ai/task", json, "[]"));
            assertEquals(400, http(port, "POST", "/ai/task", json, "{broken"));
            assertEquals(400, http(port, "POST", "/ai/task", json, "{\"prompt\":\"  \"}"));
            assertEquals(413, http(port, "POST", "/ai/task", json, "x".repeat(16385)));
            enabled.set(null, false);
            assertEquals(503, http(port, "GET", "/ai/status", auth, ""));
        } finally {
            server.stop(0); enabled.set(null, oldEnabled); token.set(null, oldToken); registered.set(null, oldRegistered);
        }
    }

    static int http(int port, String verb, String path, String headers, String body) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            String request = verb + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1:25660\r\nConnection: close\r\n" + headers + "Content-Length: " + payload.length + "\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(payload); socket.getOutputStream().flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return Integer.parseInt(response.substring(response.indexOf(' ') + 1, response.indexOf(' ') + 4));
        }
    }

    @Test void cancelledOrAlreadyDispatchedRequestsCannotRunAgain() {
        assertTrue(ClientAiControl.dispatchable("QUEUED"));
        for (String status : new String[]{"CANCELLED", "RUNNING", "FAILED", "COMPLETED", "SUPERSEDED"}) assertFalse(ClientAiControl.dispatchable(status));
    }

    @Test void pausedPlansUserQueuesAndUnknownPhasesCannotBeReplaced() {
        assertTrue(ClientAiControl.hostAccepts("init", false, false, 0));
        assertTrue(ClientAiControl.hostAccepts("update", false, false, 0));
        assertFalse(ClientAiControl.hostAccepts("write", false, false, 0));
        assertFalse(ClientAiControl.hostAccepts("init", false, true, 0));
        assertFalse(ClientAiControl.hostAccepts("init", true, false, 0));
        assertFalse(ClientAiControl.hostAccepts("init", false, false, 1));
        assertFalse(ClientAiControl.hostAccepts("unknown", false, false, 0));
    }

    public static class GenericValue<Value> { public Value read() { return null; } }
    public static class StringValue extends GenericValue<String> { @Override public String read() { return "result"; } }

    @Test void returnTypeOnlyOverloadsResolveByTheExactDescriptor() throws Exception {
        var typed = ClientAiControl.method(StringValue.class, "read", String.class);
        var bridge = ClientAiControl.method(StringValue.class, "read", Object.class);
        assertNotEquals(typed, bridge);
        assertEquals("result", typed.invoke(new StringValue()));
        assertEquals("result", bridge.invoke(new StringValue()));
        assertThrows(NoSuchMethodException.class, () -> ClientAiControl.method(StringValue.class, "read", void.class));
    }

    @Test void authenticationAndHostChecksRejectAnonymousAndBrowserRebindingRequests() {
        String secret = "test-value";
        assertTrue(ClientAiControl.authorized("Bearer " + secret, secret));
        assertFalse(ClientAiControl.authorized(null, secret));
        assertFalse(ClientAiControl.authorized("Bearer different", secret));
        assertFalse(ClientAiControl.authorized("Bearer ", ""));
        assertTrue(ClientAiControl.allowedHost("127.0.0.1:25660"));
        assertTrue(ClientAiControl.allowedHost("localhost:25660"));
        assertFalse(ClientAiControl.allowedHost("attacker.example:25660"));
        assertFalse(ClientAiControl.allowedHost("127.0.0.1.attacker.example"));
    }

    @Test void onlyTheSameOwnedObjectiveAndSessionCanBeStopped() {
        assertTrue(ClientAiControl.owns("session", "goal", "session", "goal"));
        assertFalse(ClientAiControl.owns("session", "goal", "new-session", "goal"));
        assertFalse(ClientAiControl.owns("session", "goal", "session", "amended goal"));
        assertFalse(ClientAiControl.owns("", "goal", "", "goal"));
    }

    @Test void promptsRequireBoundedNonEmptyStrings() {
        JsonObject input = new JsonObject();
        assertThrows(IllegalArgumentException.class, () -> ClientAiControl.prompt(input));
        input.addProperty("prompt", "   ");
        assertThrows(IllegalArgumentException.class, () -> ClientAiControl.prompt(input));
        input.addProperty("prompt", "x".repeat(8001));
        assertThrows(IllegalArgumentException.class, () -> ClientAiControl.prompt(input));
        input.addProperty("prompt", "  inspect nearby chunks  ");
        assertEquals("inspect nearby chunks", ClientAiControl.prompt(input));
    }
}