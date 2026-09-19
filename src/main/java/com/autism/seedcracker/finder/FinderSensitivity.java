package com.autism.seedcracker.finder;

/**
 * Shared sensitivity level for the finder modules.
 *
 * Scales the module's evidence threshold: HIGH flags on half the evidence (more finds, more
 * false positives), MEDIUM is the module's tuned default, LOW needs double (quiet, high
 * confidence). Modules with extra structural filters can also gate them by level.
 */
public enum FinderSensitivity {
    HIGH, MEDIUM, LOW;

    /** Scale an evidence threshold by the sensitivity (HIGH halves it, LOW doubles it). */
    public int scale(int threshold) {
        return switch (this) {
            case HIGH -> Math.max(1, threshold / 2);
            case MEDIUM -> threshold;
            case LOW -> threshold * 2;
        };
    }
}
