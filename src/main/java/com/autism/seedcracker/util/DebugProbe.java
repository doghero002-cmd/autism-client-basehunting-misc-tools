package com.autism.seedcracker.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import autismclient.util.AutismClientMessaging;

/**
 * Lightweight per-module debug tracing for the big untested state machines.
 *
 * A module calls {@code DebugProbe.trace(id(), "phase", "MINE -> TOWER")} on interesting
 * transitions; nothing happens unless that module's debug toggle is on. Traces go to chat
 * (throttled) and to the FlagLog ring so /flaglog shows the sequence that led to a stall or
 * failsafe trip - the practical substitute for behavioral tests on 1400-line modules.
 */
public final class DebugProbe {
    private DebugProbe() {}

    private static final Map<String, Boolean> ENABLED = new ConcurrentHashMap<>();
    /** moduleId|key -> last emit ms (per-signal chat throttle). */
    private static final Map<String, Long> LAST_EMIT = new ConcurrentHashMap<>();
    private static final long CHAT_THROTTLE_MS = 1000;

    /** Wire a module's debug BoolSetting: call from tick or the setting's change hook. */
    public static void setEnabled(String moduleId, boolean on) {
        if (on) ENABLED.put(moduleId, Boolean.TRUE);
        else ENABLED.remove(moduleId);
    }

    public static boolean isEnabled(String moduleId) {
        return ENABLED.containsKey(moduleId);
    }

    /**
     * Emit a trace signal. Always cheap when the module's debug is off (single map lookup).
     * Chat output is throttled per (module,key); the FlagLog ring gets every emit.
     */
    public static void trace(String moduleId, String key, String detail) {
        if (!ENABLED.containsKey(moduleId)) return;
        FlagLog.info("DEBUG", shortId(moduleId), key + ": " + detail);
        long now = System.currentTimeMillis();
        String throttleKey = moduleId + "|" + key;
        Long last = LAST_EMIT.get(throttleKey);
        if (last != null && now - last < CHAT_THROTTLE_MS) return;
        LAST_EMIT.put(throttleKey, now);
        AutismClientMessaging.sendPrefixed("§8[dbg:" + shortId(moduleId) + "] §7" + key + " §f" + detail);
    }

    /** Trace only when the value CHANGED since the last emit for this key (state transitions). */
    public static void traceChange(String moduleId, String key, String value) {
        if (!ENABLED.containsKey(moduleId)) return;
        String stateKey = moduleId + "|state|" + key;
        Long prevHash = LAST_EMIT.get(stateKey);
        long hash = value == null ? 0 : value.hashCode();
        if (prevHash != null && prevHash == hash) return;
        LAST_EMIT.put(stateKey, hash);
        trace(moduleId, key, value);
    }

    private static String shortId(String moduleId) {
        int idx = moduleId.lastIndexOf(':');
        return idx >= 0 ? moduleId.substring(idx + 1) : moduleId;
    }
}
