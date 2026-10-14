package com.autism.seedcracker.motion.pure;

import java.util.Objects;
import java.util.UUID;

public final class ScoutLease {
    public static final long WINDOW_NANOS = 15_000_000_000L;
    private UUID owner;
    private long heartbeatAt;

    public void bind(UUID session, long now) {
        if (owner != null) throw new IllegalStateException("Supervisor lease already owned");
        owner = Objects.requireNonNull(session);
        heartbeatAt = now;
    }

    public boolean owns(UUID session) { return owner != null && owner.equals(session); }
    public boolean bound() { return owner != null; }
    public boolean expired(long now) {
        long elapsed = now - heartbeatAt;
        return owner != null && (elapsed < 0 || elapsed >= WINDOW_NANOS);
    }

    public boolean heartbeat(UUID session, long now) {
        if (!owns(session) || expired(now)) return false;
        heartbeatAt = now;
        return true;
    }

    public long remaining(long now) { return owner == null || expired(now) ? 0 : WINDOW_NANOS - (now - heartbeatAt); }
    public void clear() { owner = null; heartbeatAt = 0; }
}