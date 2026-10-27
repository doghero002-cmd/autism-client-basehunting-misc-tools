package com.autism.seedcracker.finder;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class BaseHeatTrackerHttpTest {
    private HttpServer server;
    private ExecutorService executor;
    private CountDownLatch releaseSlow;

    @BeforeEach void start() throws IOException {
        BaseHeatTracker.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
    }

    @AfterEach void stop() {
        BaseHeatTracker.clear();
        if (releaseSlow != null) releaseSlow.countDown();
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test void correctingAnInvalidUrlCanFetchImmediatelyAndBlankingItKeepsOnlyLocalHeat() throws Exception {
        server.createContext("/map", exchange -> respond(exchange, "{\"cells\":{\"41\":3},\"cellBlocks\":50000}"));
        BaseHeatTracker.recordFind(0, 0, 50000);
        BaseHeatTracker.refreshGlobal("https://bad uri", 50000);
        BaseHeatTracker.refreshGlobal(url("/map"), 50000);
        await(() -> BaseHeatTracker.finds(41) == 4);
        BaseHeatTracker.refreshGlobal("", 50000);
        assertEquals(1, BaseHeatTracker.finds(41));
        assertFalse(refreshing());
    }

    @Test void clearingDuringAnOldRequestCannotOverwriteANewerMap() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        releaseSlow = new CountDownLatch(1);
        server.createContext("/old", exchange -> {
            entered.countDown();
            try {
                if (releaseSlow.await(5, TimeUnit.SECONDS)) respond(exchange, "{\"cells\":{\"41\":99}}");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException cancelledRequest) {
                // The client may cancel and close the old response before it is released.
            } finally {
                exchange.close();
                returned.countDown();
            }
        });
        server.createContext("/new", exchange -> respond(exchange, "{\"cells\":{\"42\":6}}"));
        BaseHeatTracker.refreshGlobal(url("/old"), 50000);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        BaseHeatTracker.clear();
        BaseHeatTracker.refreshGlobal(url("/new"), 50000);
        await(() -> BaseHeatTracker.finds(42) == 6);
        releaseSlow.countDown();
        assertTrue(returned.await(5, TimeUnit.SECONDS));
        assertEquals(0, BaseHeatTracker.finds(41));
        assertEquals(6, BaseHeatTracker.finds(42));
    }

    @Test void changingCellSizeRejectsOldCoordinatesAndOversizedResponsesAreBounded() throws Exception {
        server.createContext("/map", exchange -> respond(exchange, "{\"cells\":{\"41\":3},\"cellBlocks\":50000}"));
        BaseHeatTracker.refreshGlobal(url("/map"), 50000);
        await(() -> BaseHeatTracker.finds(41) == 3);
        BaseHeatTracker.refreshGlobal(url("/map"), 1000);
        await(() -> !refreshing());
        assertEquals(0, BaseHeatTracker.finds(41));

        server.createContext("/large", exchange -> respond(exchange,
            "{\"cells\":{\"41\":9},\"padding\":\"" + "x".repeat(70_000) + "\"}"));
        BaseHeatTracker.refreshGlobal(url("/large"), 50000);
        await(() -> !refreshing());
        assertEquals(0, BaseHeatTracker.finds(41));
    }

    private String url(String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        try (exchange) {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private static boolean refreshing() {
        try {
            Field fetch = BaseHeatTracker.class.getDeclaredField("fetch");
            fetch.setAccessible(true);
            synchronized (BaseHeatTracker.class) { return fetch.get(null) != null; }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "asynchronous heatmap update did not settle");
    }
}
