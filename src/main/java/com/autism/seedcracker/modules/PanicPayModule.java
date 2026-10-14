package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.util.FlagLog;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * Panic Pay (Radium PanicPay port).
 *
 * Dead-man's switch for carrying big balances: when a non-whitelisted player appears within
 * range, sends one validated payment and waits for a matching acknowledgement before disconnecting.
 * An unrecognised response or timeout never causes a second payment attempt.
 */
public final class PanicPayModule extends Module {

    private final StringSetting payTarget = add(new StringSetting("pay-target", "Pay target", "")
        .description("Username to /pay when panicking (your alt).").group("General"));
    private final StringSetting amount = add(new StringSetting("amount", "Amount", "all")
        .description("Amount for the /pay command (many servers accept 'all').").group("General"));
    private final IntSetting radius = add(new IntSetting("radius", "Trigger radius", 64, 8, 256, 8)
        .description("A player inside this radius triggers the panic.").group("General"));
    private final StringSetting whitelist = add(new StringSetting("whitelist", "Whitelist", "")
        .description("Comma-separated usernames that never trigger the panic.").group("General"));
    private final BoolSetting alsoDisconnect = add(new BoolSetting("disconnect", "Disconnect after pay", true)
        .description("Disconnect right after sending the /pay.").group("General"));
    private final IntSetting graceSeconds = add(new IntSetting("join-grace", "Join grace (s)", 10, 0, 60, 1)
        .description("No triggering for this long after joining (spawn crowds).").group("General"));

    private long enabledAtMs = 0;
    private boolean fired = false;
    private net.minecraft.client.multiplayer.ClientPacketListener pendingConnection;
    private String pendingRecipient = "";
    private String pendingThreat = "";
    private long acknowledgementDeadline;
    private boolean acknowledged;
    private boolean disconnectAfterAcknowledgement;
    private volatile int paymentGeneration;
    private String safeCacheKey = "";
    private java.util.Set<String> safeCache = java.util.Set.of();

    public PanicPayModule() {
        super(SeedcrackerAddon.ID + ":panic-pay", "Panic Pay",
            "Player detected nearby -> /pay your alt everything + disconnect (dead-man's switch).");
    }

    @Override
    public void onEnable() {
        clearPending();
        enabledAtMs = System.currentTimeMillis();
        fired = false;
    }

    @Override
    public void onGameJoin() {
        clearPending();
        enabledAtMs = System.currentTimeMillis();
        fired = false;
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
        clearPending();
    }

    @Override public void onDisable() { clearPending(); }

    private void clearPending() {
        paymentGeneration++;
        pendingConnection = null;
        pendingRecipient = "";
        pendingThreat = "";
        acknowledgementDeadline = 0;
        acknowledged = false;
    }

    @Override public boolean onPacketReceive(net.minecraft.network.protocol.Packet<?> packet) {
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket chatPacket) {
            String message = chatPacket.content().getString();
            int generation = paymentGeneration;
            var connection = pendingConnection;
            if (connection == null) return false;
            Minecraft.getInstance().execute(() -> {
                if (isEnabled() && generation == paymentGeneration && pendingConnection == connection
                    && Minecraft.getInstance().getConnection() == connection
                    && com.autism.seedcracker.util.pure.PaymentPolicy.acknowledged(message, pendingRecipient)) acknowledged = true;
            });
        }
        return false;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;
        if (fired) {
            if (pendingConnection == null) return;
            if (mc.getConnection() != pendingConnection) { clearPending(); setEnabledSilently(false); return; }
            if (acknowledged) {
                if (disconnectAfterAcknowledgement) pendingConnection.getConnection().disconnect(Component.literal("[PanicPay] " + pendingThreat));
                clearPending();
                setEnabledSilently(false);
            } else if (System.nanoTime() >= acknowledgementDeadline) {
                AutismClientMessaging.sendPrefixed("[PanicPay] Payment not acknowledged; no retry and no automatic disconnect.");
                clearPending();
                setEnabledSilently(false);
            }
            return;
        }
        if (System.currentTimeMillis() - enabledAtMs < graceSeconds.get() * 1000L) return;

        String target = payTarget.get() == null ? "" : payTarget.get().trim();
        if (target.isEmpty()) return; // not configured - do nothing rather than pay nobody
        String paymentAmount = amount.get() == null ? "" : amount.get().trim();
        if (!com.autism.seedcracker.util.pure.PaymentPolicy.recipient(target, mc.player.getGameProfile().name())
            || !com.autism.seedcracker.util.pure.PaymentPolicy.amount(paymentAmount)) {
            AutismClientMessaging.sendPrefixed("[PanicPay] Invalid recipient or amount; no payment sent.");
            setEnabledSilently(false);
            return;
        }

        // Cached safe-set: rebuilt only when the inputs change (was per-tick string churn).
        String rawWl = whitelist.get() == null ? "" : whitelist.get();
        String cacheKey = target + "|" + rawWl + "|" + mc.player.getGameProfile().name();
        if (!cacheKey.equals(safeCacheKey)) {
            safeCacheKey = cacheKey;
            java.util.Set<String> next = new java.util.HashSet<>();
            next.add(mc.player.getGameProfile().name().toLowerCase());
            next.add(target.toLowerCase());
            for (String w : rawWl.split(",")) {
                String t = w.trim().toLowerCase();
                if (!t.isEmpty()) next.add(t);
            }
            safeCache = next;
        }
        java.util.Set<String> safe = safeCache;

        double r2 = (double) radius.get() * radius.get();
        for (Player p : mc.level.players()) {
            if (p == mc.player || p.isSpectator()) continue;
            // Never pay out over an NPC: real players are in the tab list, bots are not (A19).
            if (mc.getConnection() == null || mc.getConnection().getPlayerInfo(p.getUUID()) == null) continue;
            if (safe.contains(p.getPlainTextName().toLowerCase())) continue;
            if (mc.player.distanceToSqr(p) > r2) continue;

            fired = true;
            String threat = p.getPlainTextName();
            FlagLog.warn("PANIC", "PanicPay", "player " + threat + " in range -> paying " + target);
            AutismClientMessaging.sendPrefixed("§c[PanicPay] §f" + threat + " detected! Paying " + target + "...");
            pendingConnection = mc.getConnection();
            pendingRecipient = target;
            pendingThreat = threat;
            disconnectAfterAcknowledgement = alsoDisconnect.get();
            acknowledgementDeadline = System.nanoTime() + 10_000_000_000L;
            acknowledged = false;
            paymentGeneration++;
            pendingConnection.sendCommand("pay " + target + " " + paymentAmount);
            return;
        }
    }
}
