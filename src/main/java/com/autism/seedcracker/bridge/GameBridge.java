package com.autism.seedcracker.bridge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local two-way bridge so an external QA driver can talk to the live client without window focus or
 * simulated keystrokes. Binds 127.0.0.1 only. Endpoints:
 *   GET  /state            -> JSON snapshot (pos, health, gm, dim, ground, sneak, block under/ahead, goto busy)
 *   GET|POST /cmd?c=TEXT   -> run TEXT as a chat message or /command on the client thread; returns after dispatch
 *   GET  /healthz          -> "ok" (liveness)
 * Started once from SeedcrackerAddon.onInitialize; stops on client stop.
 */
public final class GameBridge {
    private static final int PORT = 25660;
    private static HttpServer server;

    private GameBridge() {}

    public static synchronized void start() {
        if (server != null) return;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        } catch (IOException e) {
            System.err.println("[GameBridge] failed to bind " + PORT + ": " + e);
            return;
        }
        server.createContext("/healthz", GameBridge::healthz);
        server.createContext("/state", GameBridge::state);
        server.createContext("/path", GameBridge::path);
        server.createContext("/cmd", GameBridge::cmd);
        server.createContext("/closescreen", GameBridge::closeScreen);
        server.createContext("/respawn", GameBridge::respawn);
        server.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "GameBridge");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        System.out.println("[GameBridge] listening on 127.0.0.1:" + PORT);
    }

    public static synchronized void stop() {
        if (server != null) { server.stop(0); server = null; }
    }

    private static void healthz(HttpExchange ex) throws IOException { reply(ex, 200, "ok"); }

    /** Respawn a dead player (clicks the DeathScreen's respawn button), or no-op if alive. */
    private static void respawn(HttpExchange ex) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> out = new AtomicReference<>("alive");
        mc.execute(() -> {
            try {
                var scr = mc.gui.screen();
                if (mc.player != null && mc.player.isDeadOrDying() && scr instanceof net.minecraft.client.gui.screens.DeathScreen) {
                    // Find and press the respawn button.
                    boolean pressed = false;
                    for (var w : scr.children()) {
                        if (w instanceof net.minecraft.client.gui.components.Button b) {
                            b.onClick(new MouseButtonEvent(b.getX() + 1, b.getY() + 1, new MouseButtonInfo(0, 0)), false);
                            pressed = true;
                            break;
                        }
                    }
                    out.set(pressed ? "respawned" : "no-button");
                } else {
                    mc.gui.setScreen(null);
                    out.set("alive");
                }
            } catch (Throwable t) { out.set("error:" + t.getClass().getSimpleName()); }
            finally { done.countDown(); }
        });
        try { done.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        reply(ex, 200, "{\"result\":" + json(out.get()) + "}");
    }

    private static void closeScreen(HttpExchange ex) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> had = new AtomicReference<>("");
        mc.execute(() -> {
            try {
                var s = mc.gui.screen();
                had.set(s == null ? "" : s.getClass().getSimpleName());
                if (s != null) mc.gui.setScreen(null);
            } finally { done.countDown(); }
        });
        try { done.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        reply(ex, 200, "{\"closed\":" + json(had.get()) + "}");
    }

    private static void path(HttpExchange ex) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        AtomicReference<String> out = new AtomicReference<>("{}");
        CountDownLatch done = new CountDownLatch(1);
        mc.execute(() -> {
            try { out.set(com.autism.seedcracker.motion.Motion.debugPath()); }
            catch (Throwable t) { out.set("{\"error\":" + json(t.toString()) + "}"); }
            finally { done.countDown(); }
        });
        try { done.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        reply(ex, 200, out.get());
    }

    private static void cmd(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        String c = q.get("c");
        if (c == null || c.isBlank()) { reply(ex, 400, "{\"error\":\"missing c\"}"); return; }
        Minecraft mc = Minecraft.getInstance();
        AtomicReference<String> result = new AtomicReference<>("dispatched");
        CountDownLatch done = new CountDownLatch(1);
        mc.execute(() -> {
            try {
                if (mc.getConnection() == null) { result.set("no-connection"); return; }
                if (c.startsWith("/")) mc.getConnection().sendCommand(c.substring(1));
                else mc.getConnection().sendChat(c);
                result.set("ok");
            } catch (Throwable t) {
                result.set("error:" + t.getClass().getSimpleName());
            } finally {
                done.countDown();
            }
        });
        try { done.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        reply(ex, 200, "{\"cmd\":" + json(c) + ",\"result\":" + json(result.get()) + "}");
    }

    private static void state(HttpExchange ex) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        AtomicReference<String> out = new AtomicReference<>("{}");
        CountDownLatch done = new CountDownLatch(1);
        mc.execute(() -> {
            try { out.set(snapshot(mc)); } catch (Throwable t) { out.set("{\"error\":" + json(t.toString()) + "}"); }
            finally { done.countDown(); }
        });
        try { done.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        reply(ex, 200, out.get());
    }

    private static String snapshot(Minecraft mc) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"inWorld\":").append(mc.level != null && mc.player != null);
        if (mc.player != null && mc.level != null) {
            var p = mc.player;
            BlockPos bp = p.blockPosition();
            BlockPos below = bp.below();
            sb.append(",\"x\":").append(fmt(p.getX())).append(",\"y\":").append(fmt(p.getY())).append(",\"z\":").append(fmt(p.getZ()));
            sb.append(",\"bx\":").append(bp.getX()).append(",\"by\":").append(bp.getY()).append(",\"bz\":").append(bp.getZ());
            sb.append(",\"health\":").append(fmt(p.getHealth())).append(",\"maxHealth\":").append(fmt(p.getMaxHealth()));
            sb.append(",\"food\":").append(p.getFoodData().getFoodLevel());
            sb.append(",\"onGround\":").append(p.onGround()).append(",\"sneaking\":").append(p.isShiftKeyDown());
            sb.append(",\"sprinting\":").append(p.isSprinting()).append(",\"onFire\":").append(p.isOnFire()).append(",\"inLava\":").append(p.isInLava());
            sb.append(",\"inWater\":").append(p.isInWater());
            sb.append(",\"gameMode\":").append(json(mc.gameMode == null ? "?" : mc.gameMode.getPlayerMode().getName()));
            sb.append(",\"dimension\":").append(json(mc.level.dimension().identifier().toString()));
            sb.append(",\"blockUnder\":").append(json(mc.level.getBlockState(below).getBlock().toString()));
            sb.append(",\"blockAt\":").append(json(mc.level.getBlockState(bp).getBlock().toString()));
            sb.append(",\"dead\":").append(p.isDeadOrDying());
            sb.append(",\"gotoBusy\":").append(com.autism.seedcracker.motion.Motion.isBusy());
            var scr = mc.gui.screen();
            sb.append(",\"screen\":").append(json(scr == null ? "" : scr.getClass().getSimpleName()));
        }
        sb.append("}");
        return sb.toString();
    }

    // ---- helpers ----
    private static Map<String, String> query(HttpExchange ex) throws IOException {
        Map<String, String> m = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            q = q == null ? body : q + "&" + body;
        }
        if (q != null) for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void reply(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static String json(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) { case '"': sb.append("\\\""); break; case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break; case '\r': sb.append("\\r"); break; case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String fmt(double d) { return String.format(java.util.Locale.ROOT, "%.3f", d); }
}
