package com.autism.seedcracker.util.pure;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Clear-key release latch and positions forgotten in the current recording session. */
public final class StorageClearState {
    private int lastBind = -1;
    private boolean armed;
    private final Set<Long> forgotten = new HashSet<>();

    /** GLFW key range, or mouse button encoded as -1000-button; everything else is unbound. */
    public static boolean validBind(int bind) {
        return (bind >= 32 && bind <= 348) || (bind >= -1007 && bind <= -1000);
    }

    public boolean poll(int bind, boolean down, boolean gameplay) {
        if (bind != lastBind) {
            lastBind = bind;
            armed = false;
        }
        boolean allowed = gameplay && validBind(bind);
        boolean pressed = allowed && armed && down;
        armed = allowed && !down;
        return pressed;
    }

    public void disarm() {
        armed = false;
    }

    public void reset() {
        disarm();
        lastBind = -1;
        forgotten.clear();
    }

    public void clear(Map<Long, Byte> record) {
        forgotten.addAll(record.keySet());
        record.clear();
    }

    public boolean allowsRecord(long position) {
        return !forgotten.contains(position);
    }

    /** Only a scanned, loaded chunk can establish that a forgotten position is gone. */
    public void observeMissing(Predicate<Long> missing) {
        forgotten.removeIf(missing);
    }
}
