package com.autism.seedcracker.bridge;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.compat.ModuleLookup;
import com.autism.seedcracker.modules.BaseScoutModule;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

public final class ScoutControl {
    private static volatile boolean registered,enabled;
    private static volatile String token="";
    private static volatile Path tokenFile;

    private ScoutControl() {}
    public static void register(HttpServer server) { server.createContext("/scout",ScoutControl::handle); registered=true; }
    public static void shutdown() { disable(); registered=false; }

    public static synchronized void enable() throws IOException {
        if(!registered) throw new IOException("Local bridge is unavailable");
        if(enabled) return;
        byte[] random=new byte[32]; new SecureRandom().nextBytes(random);
        String next=Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Path file=FabricLoader.getInstance().getGameDir().resolve("run/base-scout.token");
        Files.createDirectories(file.getParent());
        if(Files.isSymbolicLink(file)) throw new IOException("Token path is a symbolic link");
        Files.writeString(file,next,StandardCharsets.UTF_8);
        tokenFile=file; token=next; enabled=true;
    }

    public static synchronized void disable() {
        enabled=false; String previous=token; Path file=tokenFile; token=""; tokenFile=null;
        Minecraft mc=Minecraft.getInstance();
        BaseScoutModule owned=module(); UUID owner=owned==null ? null : owned.supervisedSession();
        try {
            if(mc!=null && owned!=null && owner!=null) mc.execute(()->owned.remoteDisabled(owner));
        } catch(RuntimeException stopped) { System.err.println("[Scout Control] Client cleanup unavailable: "+stopped.getClass().getSimpleName()); }
        try {
            if(file!=null && !Files.isSymbolicLink(file) && Files.isRegularFile(file)
                && Files.readString(file,StandardCharsets.UTF_8).equals(previous)) Files.deleteIfExists(file);
        } catch(IOException failure) { System.err.println("[Scout Control] Token cleanup failed: "+failure.getClass().getSimpleName()); }
    }

    private static BaseScoutModule module() {
        var module=ModuleLookup.get(SeedcrackerAddon.ID+":base-scout");
        return module instanceof BaseScoutModule scout ? scout : null;
    }

    private static void handle(HttpExchange exchange) throws IOException {
        if(exchange.getRequestHeaders().getFirst("Origin")!=null || !ClientAiControl.allowedHost(exchange.getRequestHeaders().getFirst("Host"))) {
            reply(exchange,403,error("Native loopback clients only")); return;
        }
        String path=exchange.getRequestURI().getPath(), verb=exchange.getRequestMethod();
        if(path.equals("/scout") && verb.equals("GET")) {
            JsonObject result=new JsonObject(); result.addProperty("enabled",enabled);
            if(enabled && tokenFile!=null) result.addProperty("tokenFile",tokenFile.toString());
            reply(exchange,200,result); return;
        }
        if(!enabled) { reply(exchange,503,error("Enable scout remote control in the client")); return; }
        String authorization=exchange.getRequestHeaders().getFirst("Authorization");
        if(!ClientAiControl.authorized(authorization,token)) { reply(exchange,401,error("Authorization required")); return; }
        if(!Set.of("/scout/status","/scout/start","/scout/heartbeat","/scout/stop").contains(path)) { reply(exchange,404,error("Unknown scout endpoint")); return; }
        if(path.equals("/scout/status") ? !verb.equals("GET") : !verb.equals("POST")) { reply(exchange,405,error("GET status; POST mutations")); return; }
        UUID owner=null;
        if(verb.equals("POST")) {
            String contentType=exchange.getRequestHeaders().getFirst("Content-Type");
            if(contentType==null || !contentType.split(";",2)[0].trim().equalsIgnoreCase("application/json")) { reply(exchange,415,error("JSON required")); return; }
            byte[] body=exchange.getRequestBody().readNBytes(1025);
            if(body.length>1024) { reply(exchange,413,error("Request too large")); return; }
            JsonObject input;
            try {
                var parsed=JsonParser.parseString(new String(body,StandardCharsets.UTF_8));
                if(!parsed.isJsonObject()) throw new JsonParseException("Object required");
                input=parsed.getAsJsonObject();
            } catch(JsonParseException invalid) { reply(exchange,400,error("JSON object required")); return; }
            try {
                if(path.equals("/scout/start")) {
                    if(!input.isEmpty()) throw new IllegalArgumentException();
                } else {
                    if(input.size()!=1 || !input.has("sessionId") || !input.get("sessionId").isJsonPrimitive()
                        || !input.get("sessionId").getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
                    String supplied=input.get("sessionId").getAsString(); owner=UUID.fromString(supplied);
                    if(!owner.toString().equals(supplied)) throw new IllegalArgumentException();
                }
            } catch(IllegalArgumentException invalid) { reply(exchange,400,error("Empty start object or canonical session ID required")); return; }
        }
        dispatch(exchange,path,owner,authorization,token);
    }

    private static void dispatch(HttpExchange exchange,String path,UUID owner,String authorization,String generation) throws IOException {
        Minecraft mc=Minecraft.getInstance();
        if(mc==null || module()==null) { reply(exchange,503,error("Scout client unavailable")); return; }
        CountDownLatch done=new CountDownLatch(1);
        AtomicBoolean cancelled=new AtomicBoolean();
        AtomicInteger code=new AtomicInteger(503);
        AtomicReference<JsonObject> result=new AtomicReference<>(error("Client action unavailable"));
        long queuedAt=System.nanoTime();
        mc.execute(()-> {
            try {
                if(!dispatchable(cancelled.get(),queuedAt,System.nanoTime())) return;
                if(!enabled || !generation.equals(token) || !ClientAiControl.authorized(authorization,token)) {
                    code.set(401); result.set(error("Authorization changed before dispatch")); return;
                }
                BaseScoutModule scout=module();
                if(scout==null) { code.set(503); result.set(error("Scout module unavailable")); return; }
                if(path.equals("/scout/start")) scout.startSupervised();
                else if(path.equals("/scout/heartbeat") && !scout.heartbeat(owner)
                    || path.equals("/scout/stop") && !scout.stopSupervised(owner)) {
                    code.set(409); result.set(error("Current live supervised session required")); return;
                }
                result.set(scout.snapshot());
                code.set(200);
            } catch(IllegalStateException refused) { code.set(409); result.set(error(refused.getMessage())); }
            catch(RuntimeException failed) { code.set(503); result.set(error("Scout action failed: "+failed.getClass().getSimpleName())); }
            finally { done.countDown(); }
        });
        try {
            if(!done.await(3,TimeUnit.SECONDS)) { cancelled.set(true); reply(exchange,504,error("Client thread unavailable; request cancelled")); return; }
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt(); cancelled.set(true); reply(exchange,503,error("Request interrupted")); return;
        }
        reply(exchange,code.get(),result.get());
    }

    static boolean dispatchable(boolean cancelled,long queuedAt,long now) {
        long elapsed=now-queuedAt;
        return !cancelled && elapsed>=0 && elapsed<3_000_000_000L;
    }

    private static JsonObject error(String message) { JsonObject result=new JsonObject(); result.addProperty("error",message); return result; }
    private static void reply(HttpExchange exchange,int status,JsonObject body) throws IOException {
        byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control","no-store");
        exchange.sendResponseHeaders(status,bytes.length);
        try(var output=exchange.getResponseBody()) { output.write(bytes); }
        finally { exchange.close(); }
    }
}