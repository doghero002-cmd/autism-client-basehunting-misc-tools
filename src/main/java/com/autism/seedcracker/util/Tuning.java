package com.autism.seedcracker.util;

/**
 * Central tuning surface for every research-derived threshold in the addon.
 *
 * Each constant documents WHERE the value came from and what breaks if you move it, so future
 * changes are informed instead of guesses. Grouped by concern.
 */
public final class Tuning {
    private Tuning() {}

    // ---- rotation / aim ----

    /** Max yaw/pitch error (deg) before a dig/attack is allowed to fire. Grim's HitBox/rotation
     *  checks compare the reported rotation against the interaction ray; 8 deg keeps us inside
     *  the same-block face window. Raising it risks digging neighbours mid-turn (packet churn). */
    public static final float AIM_CONVERGENCE_DEG = 8.0f;

    /** Extra pitch tolerance for player attacks (pitch matters less for a 2-block-tall target). */
    public static final float ATTACK_PITCH_TOLERANCE_DEG = 10.0f;

    /** Vanilla attack-bar recharge gate (WexSide HitCooldown): swing only above this progress.
     *  Below ~0.9 the hit does reduced damage and reads as autoclicker spam. */
    public static final float ATTACK_STRENGTH_GATE = 0.9f;

    /** Sub-GCD remainder carry clamp, in GCD multiples (AlphaDLC HolyWorldRotation). Stops a long
     *  slow drift from banking a huge snap. 2 = at most 2 mouse counts of banked error. */
    public static final double GCD_REMAINDER_CLAMP = 2.0;

    // ---- movement / stuck ----

    /** Ticks a go-around lane shift stays committed before re-deciding. ~14t = one block of
     *  strafing at walk speed; shorter causes flip-flopping between lanes (bot tell). */
    public static final int GO_AROUND_COMMIT_TICKS = 14;

    /** Failed lane picks (both sides blocked) before a 90-degree turn. */
    public static final int GO_AROUND_MAX_FAILS = 3;

    /** StuckDetector: ticks without moving STUCK_EPSILON blocks before logging. 60-100 depending
     *  on how much legitimate standing-still the module does (digging = more). */
    public static final int STUCK_TICKS_DEFAULT = 60;
    public static final int STUCK_TICKS_TUNNEL = 80;
    public static final int STUCK_TICKS_BUILDER = 100;

    /** Movement epsilon (blocks) below which a tick counts as "not moving". */
    public static final double STUCK_EPSILON = 0.35;

    // ---- Grim speed emulator (Apex azheng, mirrors Grim Simulation SpeedA) ----

    /** Base per-tick XZ speed thresholds: ground / air. Grim uses these exact floats. */
    public static final float GRIM_SPEED_GROUND = 0.31f;
    public static final float GRIM_SPEED_AIR = 0.341f;

    /** Buffer level where we WARN and back off. Grim itself flags above 13; 8 leaves headroom
     *  for a few more fast ticks while modules brake. */
    public static final int SPEED_BUFFER_WARN = 8;

    /** Grim's actual flag threshold (reference only - we never want to reach it). */
    public static final int SPEED_BUFFER_FLAG = 13;

    // ---- dig pacing ----

    /** Ticks of unbreakable-block stall before treating the block as unbreakable and going
     *  around (server-side protected region, wrong tool, etc). */
    public static final int DIG_TIMEOUT_DEFAULT = 100;

    /** Break-desync back-off: min/max ticks to release before re-pressing after the server's
     *  breaking block diverges from the crosshair block (nyx check2). */
    public static final int DESYNC_BACKOFF_MIN = 1;
    public static final int DESYNC_BACKOFF_MAX = 3;

    // ---- container / GUI ----

    /** Ticks the Water buy stages retry a failed click before aborting the whole buy (~2s).
     *  Server GUI opens lag; instant aborts caused /shop spam loops. */
    public static final int BUY_CLICK_RETRIES = 40;

    /** TPS below which delays start stretching (healthy servers bounce 19.5-20). */
    public static final float TPS_SCALE_FLOOR = 19.5f;
}
