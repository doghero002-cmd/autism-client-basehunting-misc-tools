package com.autism.seedcracker.modules;

import java.util.ArrayDeque;
import java.util.Deque;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;

/**
 * Fake Latency (Shoreline FakeLatency / Frog PingSpoof port).
 *
 * Inflates your apparent ping by holding keep-alive and transaction (ping/pong) replies for a
 * configured delay before answering. Servers and other players see a laggy connection; lag
 * compensation gets more generous with a "500ms player". Replies are queued, never dropped, so
 * you cannot be kicked for timing out (the delay stays well under the 30s keepalive limit).
 */
public final class FakeLatencyModule extends Module {

    private final IntSetting delayMs = add(new IntSetting("delay-ms", "Added latency (ms)", 500, 50, 5000, 50)
        .description("How long keep-alive/transaction replies are held.").group("General"));
    private final BoolSetting holdKeepAlive = add(new BoolSetting("keep-alive", "Delay keep-alives", true)
        .description("Delay ServerboundKeepAlive replies (drives the tab-list ping number).").group("General"));
    private final BoolSetting holdPong = add(new BoolSetting("pong", "Delay transactions", true)
        .description("Delay pong replies to anti-cheat transaction pings (lag-comp abuse).").group("General"));

    private record Held(long releaseAtMs, Packet<?> packet) {}

    private final Deque<Held> queue = new ArrayDeque<>();
    /** Re-entrancy guard: our own flushed packets pass through the hook again. */
    private boolean flushing = false;

    public FakeLatencyModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":fake-latency", "Fake Latency", category,
            "Holds keep-alive/transaction replies to fake a laggy connection.");
    }

    @Override
    public void onDisable() {
        flushAll();
    }

    @Override
    public void onGameLeft() {
        synchronized (queue) { queue.clear(); }
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public boolean onPacketReceive(Packet<?> packet) {
        // Answer from OUR side with a delayed reply and cancel vanilla's instant one? No - vanilla
        // replies on receive, so instead we intercept the outgoing reply in onPacketSend.
        return false;
    }

    @Override
    public boolean onPacketSend(Packet<?> packet) {
        if (flushing) return false;
        boolean hold = (packet instanceof ServerboundKeepAlivePacket && holdKeepAlive.get())
            || (packet instanceof ServerboundPongPacket && holdPong.get());
        if (!hold) return false;

        synchronized (queue) {
            queue.addLast(new Held(System.currentTimeMillis() + delayMs.get(), packet));
        }
        return true; // cancel the immediate send; we release it later
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            synchronized (queue) { queue.clear(); }
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (queue) {
            while (!queue.isEmpty() && queue.peekFirst().releaseAtMs() <= now) {
                Held h = queue.removeFirst();
                send(mc, h.packet());
            }
        }
    }

    private void flushAll() {
        Minecraft mc = Minecraft.getInstance();
        synchronized (queue) {
            if (mc.getConnection() == null) {
                queue.clear();
                return;
            }
            while (!queue.isEmpty()) send(mc, queue.removeFirst().packet());
        }
    }

    private void send(Minecraft mc, Packet<?> packet) {
        flushing = true;
        try {
            mc.getConnection().send(packet);
        } catch (Throwable ignored) {
        } finally {
            flushing = false;
        }
    }

    @Override
    public String info() {
        return "+" + delayMs.get() + "ms";
    }
}
