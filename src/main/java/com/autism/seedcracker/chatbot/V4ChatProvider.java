package com.autism.seedcracker.chatbot;

import java.net.URI;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class V4ChatProvider {
    private V4ChatProvider() {}

    public record Profile(String provider, String endpoint, String model, boolean builtIn) {
        @Override
        public String toString() { return "V4ChatProvider.Profile[provider=" + provider + ", credentials=redacted]"; }
    }

    public static Profile read(Path path) {
        try {
            Class<?> settingsType = Class.forName("autismclient.ai.AiSettings");
            Object settings = snapshot(path, settingsType);
            if (settings == null) return null;
            String provider = ((Enum<?>) settingsType.getField("provider").get(settings)).name();
            String model = (String) settingsType.getField("model").get(settings);
            String endpoint = (String) settingsType.getField("endpoint").get(settings);
            return new Profile(provider, endpoint, model, settingsType.getField("builtIn").getBoolean(settings));
        } catch (ReflectiveOperationException | java.io.IOException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    public static boolean available() {
        try {
            Class<?> settingsType = Class.forName("autismclient.ai.AiSettings", false, V4ChatProvider.class.getClassLoader());
            transportType(settingsType);
            return true;
        } catch (ReflectiveOperationException | LinkageError unavailable) { return false; }
    }

    static Class<?> transportType(Class<?> settingsType) throws ReflectiveOperationException {
        for (String name : List.of("autismclient.ai.S", "autismclient.ai.aC")) {
            try {
                Class<?> transport = Class.forName(name, false, settingsType.getClassLoader());
                if (!AutoCloseable.class.isAssignableFrom(transport)) continue;
                transport.getConstructor(settingsType);
                Method request = transport.getMethod("init", String.class, List.class, JsonArray.class, Consumer.class, BooleanSupplier.class);
                if (!Modifier.isStatic(request.getModifiers()) && request.getReturnType() != void.class) return transport;
            } catch (ClassNotFoundException | NoSuchMethodException absent) {
                continue;
            }
        }
        throw new NoSuchMethodException("Supported V4 text transport unavailable");
    }

    public static boolean credentialsAvailable(Path path) {
        try {
            Class<?> settingsType = Class.forName("autismclient.ai.AiSettings");
            Object settings = snapshot(path, settingsType);
            if (settings == null) return false;
            String provider = ((Enum<?>) settingsType.getField("provider").get(settings)).name();
            return (Boolean) settingsType.getMethod("hasKey", String.class).invoke(settings, provider);
        } catch (ReflectiveOperationException | java.io.IOException | RuntimeException | LinkageError unavailable) { return false; }
    }

    static String failureCategory(Throwable error) {
        while (error instanceof java.lang.reflect.InvocationTargetException && error.getCause() != null) error = error.getCause();
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("api key") || message.contains("api-key") || message.contains("unauthorized") || message.matches(".*\\b401\\b.*")) return "provider authentication";
        if (message.contains("model") && (message.contains("not found") || message.contains("invalid") || message.contains("unsupported"))) return "provider model selection";
        if (message.contains("limit")) return "provider limits";
        return error.getClass().getSimpleName();
    }

    private static Object snapshot(Path path, Class<?> settingsType) throws ReflectiveOperationException, java.io.IOException {
        for (String name : List.of("autismclient.ai.ah", "autismclient.ai.aX")) {
            try {
                Class<?> controllerType = Class.forName(name, false, settingsType.getClassLoader());
                Method current = Arrays.stream(controllerType.getDeclaredMethods()).filter(method -> method.getName().equals("load")
                    && Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0 && method.getReturnType() == controllerType).findFirst().orElse(null);
                Method settings = Arrays.stream(controllerType.getDeclaredMethods()).filter(method -> method.getName().equals("init")
                    && !Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0 && method.getReturnType() == settingsType).findFirst().orElse(null);
                if (current != null && settings != null && current.trySetAccessible() && settings.trySetAccessible()) {
                    Object controller = current.invoke(null);
                    if (controller != null) return settings.invoke(controller);
                }
            } catch (ClassNotFoundException absent) {
                continue;
            }
        }
        return loadSaved(path, settingsType);
    }

    private static Object loadSaved(Path path, Class<?> settingsType) throws ReflectiveOperationException, java.io.IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > 1_048_576) return null;
        return settingsType.getMethod("load", Path.class).invoke(null, path.getParent());
    }

    public static CompletableFuture<String> converse(Path path, List<ChatBotConversation.Turn> turns, int timeoutSeconds) {
        var answer = new CompletableFuture<String>();
        var active = new AtomicReference<Object>();
        answer.orTimeout(Math.max(1, Math.min(60, timeoutSeconds)), java.util.concurrent.TimeUnit.SECONDS);
        answer.whenComplete((value, error) -> {
            Object owned = active.getAndSet(null);
            if (owned != null) CompletableFuture.runAsync(() -> close(owned));
        });
        CompletableFuture.runAsync(() -> {
            try {
                Class<?> settingsType = Class.forName("autismclient.ai.AiSettings");
                Object settings = snapshot(path, settingsType);
                if (settings == null || settingsType.getField("builtIn").getBoolean(settings)) throw new IllegalStateException("V4 language-model provider required");
                settingsType.getField("outputTokens").setInt(settings, Math.max(256, Math.min(2048, settingsType.getField("outputTokens").getInt(settings))));
                Class<?> transportType = transportType(settingsType);
                Object transport = transportType.getConstructor(settingsType).newInstance(settings);
                active.set(transport);
                if (answer.isDone()) return;
                String provider = ((Enum<?>) settingsType.getField("provider").get(settings)).name();
                List<JsonObject> history = messages(provider, turns);
                Method request = transportType.getMethod("init", String.class, List.class, JsonArray.class, Consumer.class, BooleanSupplier.class);
                Object result = request.invoke(transport, com.autism.seedcracker.chatgames.AiRouter.conversationPrompt(), history,
                    new JsonArray(), (Consumer<String>) ignored -> {}, (BooleanSupplier) answer::isDone);
                Method text = Arrays.stream(result.getClass().getDeclaredMethods()).filter(method -> method.getName().equals("init")
                    && method.getParameterCount() == 0 && method.getReturnType() == String.class).findFirst().orElseThrow();
                Method calls = Arrays.stream(result.getClass().getDeclaredMethods()).filter(method -> method.getName().equals("init")
                    && method.getParameterCount() == 0 && method.getReturnType() == List.class).findFirst().orElseThrow();
                if (!((List<?>) calls.invoke(result)).isEmpty()) throw new IllegalStateException("V4 returned tools instead of text");
                answer.complete((String) text.invoke(result));
            } catch (ReflectiveOperationException | java.io.IOException | RuntimeException | LinkageError failed) {
                answer.completeExceptionally(new IllegalStateException("V4 native text request failed: " + failureCategory(failed)));
            } finally {
                Object owned = active.getAndSet(null);
                if (owned != null) close(owned);
            }
        });
        return answer;
    }

    static List<JsonObject> messages(String provider, List<ChatBotConversation.Turn> turns) {
        if (turns == null || turns.isEmpty() || turns.size() > 41 || turns.size() % 2 != 1) throw new IllegalArgumentException("Invalid conversation");
        List<JsonObject> messages = new ArrayList<>();
        for (int index = 0; index < turns.size(); index++) {
            var turn = turns.get(index);
            String expected = index % 2 == 0 ? "user" : "assistant";
            if (turn == null || !expected.equals(turn.role()) || turn.content() == null || turn.content().isBlank()
                || turn.content().length() > (expected.equals("user") ? 512 : 220)) throw new IllegalArgumentException("Invalid turn");
            var message = new JsonObject();
            message.addProperty("role", "GEMINI".equals(provider) && expected.equals("assistant") ? "model" : expected);
            if ("GEMINI".equals(provider)) {
                var parts = new JsonArray();
                var part = new JsonObject();
                part.addProperty("text", turn.content());
                parts.add(part);
                message.add("parts", parts);
            } else message.addProperty("content", turn.content());
            messages.add(message);
        }
        return List.copyOf(messages);
    }

    private static void close(Object transport) {
        try {
            ((AutoCloseable) transport).close();
            for (var field : transport.getClass().getDeclaredFields()) {
                if (field.getType() == HttpClient.class && field.trySetAccessible()) ((HttpClient) field.get(transport)).shutdownNow();
            }
        } catch (Exception failed) {
            System.err.println("[ChatBot] V4 transport cleanup failed: " + failed.getClass().getSimpleName());
        }
    }

    public static long revision(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (java.io.IOException unavailable) { return -1; }
    }

    public static String endpoint(String provider, String endpoint, String model) {
        try {
            URI uri = URI.create(endpoint);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equalsIgnoreCase(uri.getScheme()) || com.autism.seedcracker.chatgames.AiRouter.isLocalEndpoint(endpoint))) return null;
            String base = endpoint.replaceAll("/+$", "");
            return switch (provider) {
                case "OPENAI", "DASHSCOPE", "MOONSHOT", "DEEPSEEK" -> base.endsWith("/chat/completions") ? base : base + "/chat/completions";
                case "GEMINI" -> model == null || !model.matches("[A-Za-z0-9._-]{1,128}") ? null
                    : base.endsWith(":generateContent") ? base : base + "/models/" + model + ":generateContent";
                case "ANTHROPIC" -> base.endsWith("/messages") ? base : base.endsWith("/v1") ? base + "/messages" : base + "/v1/messages";
                default -> null;
            };
        } catch (RuntimeException invalid) { return null; }
    }
}