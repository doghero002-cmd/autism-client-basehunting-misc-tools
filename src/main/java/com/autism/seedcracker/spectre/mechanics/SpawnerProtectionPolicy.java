/* SPDX-License-Identifier: GPL-3.0-only
 * Detection thresholds/title stages adapted from Spectre SpawnerProtectModule at
 * d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da. Duplicate breaks are bounded and deduplicated;
 * shop actions require both a donor title and a relevant button, not just a hard-coded slot.
 */
package com.autism.seedcracker.spectre.mechanics;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class SpawnerProtectionPolicy {
    private final Set<Long> brokenPositions = new HashSet<>();
    private long lastBreak;
    private boolean hasBreak;

    public boolean recordBreak(long blockKey, long nowNanos, int threshold, int windowSeconds) {
        int limit = Math.max(1, Math.min(20, threshold));
        long window = Math.max(1, Math.min(600, windowSeconds)) * 1_000_000_000L;
        long elapsed = nowNanos - lastBreak;
        if (!hasBreak || elapsed < 0 || elapsed > window) brokenPositions.clear();
        lastBreak = nowNanos;
        hasBreak = true;
        if (brokenPositions.size() < 20) brokenPositions.add(blockKey);
        return brokenPositions.size() >= limit;
    }

    public void clear() { brokenPositions.clear(); hasBreak = false; lastBreak = 0; }

    public static boolean remoteBreak(boolean nearSpawn, int y, boolean oldAir, boolean newAir,
            boolean shulker, boolean friendlyNearby, double eyeDistanceSquared, double horizontalSquared,
            double range) {
        return !nearSpawn && y > -64 && !oldAir && newAir && !shulker && !friendlyNearby
            && Double.isFinite(eyeDistanceSquared) && eyeDistanceSquared >= 36.0
            && Double.isFinite(horizontalSquared) && horizontalSquared >= 0.0
            && Double.isFinite(range) && range > 0.0 && horizontalSquared <= range * range;
    }

    public static boolean nearSpawn(double x, double z) { return Math.abs(x) < 100.0 && Math.abs(z) < 100.0; }

    public static Set<String> whitelist(String text) {
        if (text == null || text.isBlank()) return Set.of();
        Set<String> names = new HashSet<>();
        for (String entry : text.split("[,;\\s]+")) {
            if (!entry.isBlank()) names.add(entry.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(names);
    }

    public static boolean shopButton(int step, String title, String label, boolean enderChest) {
        if (title == null || label == null) return false;
        String t = title.toUpperCase(Locale.ROOT), name = label.toUpperCase(Locale.ROOT);
        return switch (step) {
            case 1 -> t.contains("SHOP") && name.contains("END");
            case 2 -> t.contains("END") && enderChest;
            case 3 -> t.contains("ENDER CHEST") && (name.contains("CONFIRM") || name.contains("BUY") || name.contains("PURCHASE"));
            default -> false;
        };
    }

    public static int shopSlot(int step) {
        return switch (step) { case 1 -> 11; case 2 -> 9; case 3 -> 25; default -> -1; };
    }

    public static boolean mayDrive(boolean enabled, boolean sameSession, boolean screenOpen,
            boolean holding, boolean manualInput) {
        return enabled && sameSession && !screenOpen && !holding && !manualInput;
    }

    public static boolean manualActionInterrupts(boolean driving, boolean ownedAction, boolean userActionPacket) {
        return driving && !ownedAction && userActionPacket;
    }
}
