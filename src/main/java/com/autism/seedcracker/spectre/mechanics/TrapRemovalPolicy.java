/* SPDX-License-Identifier: GPL-3.0-only */
package com.autism.seedcracker.spectre.mechanics;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Tick-side equivalent of Spectre's entity-added queue; holds no live entities or old worlds. */
public final class TrapRemovalPolicy {
    private Object session;
    private Set<UUID> previous = new HashSet<>();
    private Set<UUID> current = new HashSet<>();
    private boolean firstFrame;

    public void bind(Object session) {
        clear();
        this.session = java.util.Objects.requireNonNull(session);
        firstFrame = true;
    }

    public boolean observe(Object session, UUID uuid, boolean removeExisting, boolean preventSpawn, boolean sweep) {
        if (this.session != session || session == null || uuid == null) return false;
        current.add(uuid);
        return (removeExisting && (firstFrame || sweep)) || (!firstFrame && preventSpawn && !previous.contains(uuid));
    }

    public void finishFrame() {
        Set<UUID> reuse = previous;
        previous = current;
        current = reuse;
        current.clear();
        firstFrame = false;
    }

    public void clear() {
        session = null;
        previous.clear();
        current.clear();
        firstFrame = false;
    }

    public static boolean removable(boolean sameWorldAndEntity, boolean camera, boolean player,
            boolean carryingPlayer, boolean alive) {
        return sameWorldAndEntity && alive && !camera && !player && !carryingPlayer;
    }
}
