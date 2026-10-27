// SPDX-License-Identifier: GPL-3.0-only
// Adapted from https://github.com/cody-raves/Spectre, commit d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da.
// Upstream: src/main/java/net/spectre/client/module/impl/misc/PlayerDetectionModule.java
// Retains Dogs Visual Range's whitelist, departure coordinates, flag log and stable module id.
package com.autism.seedcracker.modules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.compat.ClientNotify;
import com.autism.seedcracker.compat.ModuleLookup;
import com.autism.seedcracker.spectre.staff.NameFilter;
import com.autism.seedcracker.spectre.staff.PlayerDetectionLogic;
import com.autism.seedcracker.util.FlagLog;
import com.autism.seedcracker.util.RelogPersistence;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;

public final class VisualRangeModule extends Module {
    public enum NotificationMode { CHAT, TOAST, BOTH }

    private static final SystemToastId TOAST_ID = new SystemToastId();
    private final BoolSetting enterAlert = add(new BoolSetting("enter", "Enter alerts", true).group("Notifications"));
    private final BoolSetting leaveAlert = add(new BoolSetting("leave", "Leave alerts", true).group("Notifications"));
    private final BoolSetting sound = add(new BoolSetting("sound", "Sound ping", true).group("Notifications"));
    private final EnumSetting<NotificationMode> notificationMode = add(new EnumSetting<>("notification-mode", "Notification mode",
        NotificationMode.BOTH, NotificationMode.values()).group("Notifications"));
    private final StringSetting whitelist = add(new StringSetting("whitelist", "Whitelist", "")
        .description("Comma-separated names to ignore. Freecamera is always ignored.").group("Detection"));
    private final BoolSetting tabOnly = add(new BoolSetting("tab-only", "Ignore non-tab NPCs", true)
        .description("Preserve Visual Range's NPC filter. Turn off to include unlisted player entities.").group("Detection"));
    private final BoolSetting logCoords = add(new BoolSetting("log", "Log coordinates locally", true)
        .description("Record enter/leave positions in /flaglog; nothing is uploaded.").group("Detection"));
    private final StringSetting modulesToToggle = add(new StringSetting("modules-to-toggle", "Modules to toggle", "")
        .description("Comma-separated names or ids. Each resolved module is toggled once per arrival batch.").group("Reactions"));
    private final BoolSetting toggleOnPlayer = add(new BoolSetting("toggle-on-detect", "Disable after reacting", false)
        .description("Disable after the reactions finish; a requested disconnect waits 500 ms.").group("Reactions"));
    private final BoolSetting disconnect = add(new BoolSetting("disconnect", "Disconnect on detection", false)
        .description("Opt in to disconnecting after a 500 ms command-flush delay.").group("Reactions"));
    private final BoolSetting panicPay = add(new BoolSetting("enable-panic-pay", "Panic pay (opt in)", false)
        .description("Sends a real in-game /pay command to the configured account.").group("Reactions"));
    private final StringSetting payTarget = add(new StringSetting("target-player", "Pay target", "")
        .group("Reactions").visibleWhen(panicPay::get));
    private final StringSetting payAmount = add(new StringSetting("amount", "Pay amount", "")
        .description("Positive numeric amount, optionally k/m/b/t; no commands or whitespace.")
        .group("Reactions").visibleWhen(panicPay::get));

    private final Map<UUID, Seen> inRange = new LinkedHashMap<>();
    private ClientLevel observedLevel;
    private ClientPacketListener observedConnection;
    private String whitelistRaw;
    private Set<String> ignored = PlayerDetectionLogic.whitelist("");
    private ClientPacketListener pendingConnection;
    private ClientLevel pendingLevel;
    private long disconnectAt;
    private String disconnectReason;
    private boolean disableAfterDisconnect;

    public VisualRangeModule() {
        super(SeedcrackerAddon.ID + ":visual-range", "Player Detection",
            "Detects player arrivals, retains leave coordinates, and optionally toggles modules, pays or disconnects.");
    }

    private final java.util.concurrent.atomic.AtomicLong lifecycleRevision = new java.util.concurrent.atomic.AtomicLong();
    private volatile long lifecycleApplied;

    @Override public void onEnable() { resetOnClient(false); }
    @Override public void onDisable() { resetOnClient(false); }
    @Override public void onGameJoin() { resetOnClient(false); }
    @Override public void onGameLeft() { resetOnClient(true); }

    private void resetOnClient(boolean leaving) {
        long revision = lifecycleRevision.incrementAndGet();
        MC.execute(() -> {
            if (revision != lifecycleRevision.get()) return;
            try {
                reset();
                if (leaving && RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
            } finally {
                lifecycleApplied = Math.max(lifecycleApplied, revision);
            }
        });
    }

    private void reset() {
        inRange.clear();
        observedLevel = null;
        observedConnection = null;
        pendingConnection = null;
        pendingLevel = null;
        disconnectAt = 0;
        disconnectReason = null;
        disableAfterDisconnect = false;
    }

    @Override
    public void tick() {
        if (lifecycleApplied != lifecycleRevision.get()) return;
        if (MC.player == null || MC.level == null || MC.getConnection() == null || !MC.getConnection().getConnection().isConnected()) {
            reset();
            return;
        }
        if (observedLevel != MC.level || observedConnection != MC.getConnection()) {
            reset();
            observedLevel = MC.level;
            observedConnection = MC.getConnection();
        }
        if (pendingConnection != null) {
            if (pendingConnection != MC.getConnection() || pendingLevel != MC.level || !disconnect.get()) {
                reset();
                return;
            }
            if (System.currentTimeMillis() >= disconnectAt) {
                ClientPacketListener session = pendingConnection;
                String reason = disconnectReason;
                boolean disable = disableAfterDisconnect;
                reset();
                session.getConnection().disconnect(Component.literal(reason));
                if (disable) setEnabled(false);
            }
            return;
        }

        String raw = whitelist.get() == null ? "" : whitelist.get();
        if (!raw.equals(whitelistRaw)) {
            ignored = PlayerDetectionLogic.whitelist(raw);
            whitelistRaw = raw;
        }
        Map<UUID, Seen> current = new LinkedHashMap<>();
        for (Player player : MC.level.players()) {
            if (player == MC.player || player.getUUID().equals(MC.player.getUUID()) || player.isRemoved()) continue;
            String name = player.getGameProfile().name();
            if (PlayerDetectionLogic.ignored(name, MC.player.getGameProfile().name(), ignored)) continue;
            if (tabOnly.get() && MC.getConnection().getPlayerInfo(player.getUUID()) == null) continue;
            current.put(player.getUUID(), new Seen(name, player.getBlockX(), player.getBlockY(), player.getBlockZ()));
        }
        List<Seen> entered = new ArrayList<>();
        for (UUID id : PlayerDetectionLogic.entered(inRange.keySet(), current.keySet())) {
            Seen seen = current.get(id);
            entered.add(seen);
            log("enter", seen);
            if (enterAlert.get()) notifyPlayer(seen, true);
        }
        for (var entry : inRange.entrySet()) {
            Seen seen = entry.getValue();
            if (current.containsKey(entry.getKey()) || PlayerDetectionLogic.ignored(seen.name(), MC.player.getGameProfile().name(), ignored)) continue;
            log("leave", seen);
            if (leaveAlert.get()) notifyPlayer(seen, false);
        }
        inRange.clear();
        inRange.putAll(current);
        if (!entered.isEmpty()) {
            if (enterAlert.get() && sound.get()) MC.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0F, 0.6F);
            react(entered);
        }
    }

    private void notifyPlayer(Seen seen, boolean arrival) {
        String shown = NameFilter.apply(seen.name()) + (arrival ? " entered visual range at " : " left visual range near ") + seen.position();
        if (notificationMode.get() != NotificationMode.TOAST) ClientNotify.warning("[Player Detection] " + shown);
        if (arrival && notificationMode.get() != NotificationMode.CHAT) {
            SystemToast.addOrUpdate(MC.gui.toastManager(), TOAST_ID, Component.literal(name()), Component.literal(shown));
        }
    }

    private void log(String action, Seen seen) {
        if (logCoords.get()) FlagLog.info("VISUAL", "VisualRange", action + " " + seen.name() + " " + seen.position());
    }

    private void react(List<Seen> players) {
        if (lifecycleApplied != lifecycleRevision.get() || !isEnabled()) return;
        for (Module module : resolveModules()) module.toggle();
        if (lifecycleApplied != lifecycleRevision.get() || !isEnabled() || MC.getConnection() == null || MC.level == null) return;
        if (panicPay.get()) {
            String command = PlayerDetectionLogic.paymentCommand(payTarget.get(), payAmount.get());
            if (command == null || payTarget.get().trim().equalsIgnoreCase(MC.player.getGameProfile().name())) {
                ClientNotify.warning("[Player Detection] Panic pay skipped: configure a valid other account and positive amount.");
            } else {
                MC.getConnection().sendCommand(command);
                ClientNotify.warning("[Player Detection] Configured panic pay sent (server acceptance unverified).");
            }
        }
        if (disconnect.get()) {
            pendingConnection = MC.getConnection();
            pendingLevel = MC.level;
            disconnectAt = System.currentTimeMillis() + 500L;
            disconnectReason = "Player(s) detected: " + String.join(", ", players.stream().limit(12).map(s -> NameFilter.apply(s.name())).toList());
            disableAfterDisconnect = toggleOnPlayer.get();
        } else if (toggleOnPlayer.get()) {
            setEnabled(false);
        }
    }

    private List<Module> resolveModules() {
        String raw = modulesToToggle.get();
        if (raw == null || raw.isBlank()) return List.of();
        Set<Module> resolved = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String key = part.trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) continue;
            if (key.equals("anti-vanish")) key = "spectator-detector";
            if (key.equals("player-detection")) key = "visual-range";
            for (Module module : ModuleLookup.includingHost()) {
                String id = module.id();
                String suffix = id.substring(id.lastIndexOf(':') + 1);
                if (module != this && (id.equalsIgnoreCase(key) || suffix.equalsIgnoreCase(key) || module.name().equalsIgnoreCase(key))) {
                    resolved.add(module);
                    break;
                }
            }
        }
        return List.copyOf(resolved);
    }

    @Override public String info() { return inRange.isEmpty() ? "" : inRange.size() + " visible"; }

    private record Seen(String name, int x, int y, int z) {
        String position() { return x + " " + y + " " + z; }
    }
}
