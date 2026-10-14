package com.autism.seedcracker.bridge;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScoutControlTest {
    @Test void legacyMutationsRejectBrowsersAndGetRequests() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/legacy",exchange->{
            if(GameBridge.nativeMutation(exchange)) { exchange.sendResponseHeaders(204,-1); exchange.close(); }
        });
        server.start();
        try {
            int port=server.getAddress().getPort();
            assertEquals(405,ClientAiControlTest.http(port,"GET","/legacy","",""));
            assertEquals(403,ClientAiControlTest.http(port,"POST","/legacy","Origin: https://example.invalid\r\n",""));
            assertEquals(204,ClientAiControlTest.http(port,"POST","/legacy","",""));
        } finally { server.stop(0); }
    }

    @Test void cancelledExpiredAndClockReversedRequestsCannotDispatch() {
        assertTrue(ScoutControl.dispatchable(false,100,101));
        assertFalse(ScoutControl.dispatchable(true,100,101));
        assertFalse(ScoutControl.dispatchable(false,100,99));
        assertFalse(ScoutControl.dispatchable(false,100,100+3_000_000_000L));
    }

    @Test void actualLoopbackHandlerEnforcesAuthMethodsAndBoundedOwnershipInputs() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var enabled=ScoutControl.class.getDeclaredField("enabled"); enabled.setAccessible(true);
        var token=ScoutControl.class.getDeclaredField("token"); token.setAccessible(true);
        var registered=ScoutControl.class.getDeclaredField("registered"); registered.setAccessible(true);
        Object oldEnabled=enabled.get(null), oldToken=token.get(null), oldRegistered=registered.get(null);
        try {
            ScoutControl.register(server); server.start(); int port=server.getAddress().getPort();
            enabled.set(null,true); token.set(null,"fixture-token");
            String auth="Authorization: Bearer fixture-token\r\n", json=auth+"Content-Type: application/json\r\n";
            assertEquals(200,ClientAiControlTest.http(port,"GET","/scout","",""));
            assertEquals(401,ClientAiControlTest.http(port,"GET","/scout/status","",""));
            assertEquals(403,ClientAiControlTest.http(port,"GET","/scout", "Origin: http://localhost\r\n",""));
            assertEquals(405,ClientAiControlTest.http(port,"GET","/scout/start",auth,""));
            assertEquals(404,ClientAiControlTest.http(port,"POST","/scout/unknown",json,"{}"));
            assertEquals(415,ClientAiControlTest.http(port,"POST","/scout/start",auth,"{}"));
            assertEquals(413,ClientAiControlTest.http(port,"POST","/scout/start",json,"x".repeat(1025)));
            assertEquals(400,ClientAiControlTest.http(port,"POST","/scout/start",json,"[]"));
            assertEquals(400,ClientAiControlTest.http(port,"POST","/scout/start",json,"{broken"));
            assertEquals(400,ClientAiControlTest.http(port,"POST","/scout/start",json,"{\"load\":128}"));
            assertEquals(400,ClientAiControlTest.http(port,"POST","/scout/stop",json,"{}"));
            assertEquals(400,ClientAiControlTest.http(port,"POST","/scout/heartbeat",json,"{\"sessionId\":\"1-1-1-1-1\"}"));
            assertEquals(400,ClientAiControlTest.http(port,"POST","/scout/stop",json,"{\"sessionId\":7}"));
            enabled.set(null,false);
            assertEquals(503,ClientAiControlTest.http(port,"GET","/scout/status",auth,""));
        } finally {
            server.stop(0); enabled.set(null,oldEnabled); token.set(null,oldToken); registered.set(null,oldRegistered);
        }
    }
}