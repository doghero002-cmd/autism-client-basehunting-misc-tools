package com.autism.seedcracker.motion;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.autism.seedcracker.compat.BaritoneCompat;
import com.autism.seedcracker.motion.pure.GridPathfinder;
import com.autism.seedcracker.motion.pure.GridPathfinder.Status;
import com.autism.seedcracker.motion.pure.GridPathfinder.Step;
import com.autism.seedcracker.motion.pure.TripPolicy;
import com.autism.seedcracker.render.BlockEspRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * The addon's own movement engine (no Baritone needed). {@link #goTo} plans with a budgeted A*
 * across ticks, walks it with {@link PathFollower}, and keeps planning segments until the goal is
 * reached. Long trips never time out while they make progress ({@link TripPolicy}); a bot that is
 * wedged, keeps getting knocked back, or hits an unbreakable wall stops on its own.
 *
 * Callers own the tick: call {@link #tick} from the module's tick while busy.
 * {@link Backend#AUTO} uses Baritone when installed and the built-in engine otherwise.
 */
public final class Motion {
    private Motion() {}

    public enum Backend { AUTO, BUILT_IN, BARITONE }

    /** Off the game thread a plan can afford far more nodes than the old 2,500/tick slice. */
    private static final int MAX_NODES = 250_000;
    /** Bench: ~4x fewer nodes for routes within 0.5% of the cheapest (guaranteed within 10%). */
    private static final double HEURISTIC_WEIGHT = 1.1;
    /** Take a usable partial route after this long (start walking); never search longer than the hard cap.
     * Lower = faster first move (reaction time); the default 250ms covers nearly every short route anyway. */
    private static long planSoftMs = 250, hardMs = 6_000;
    /** Replans after a failure: the bot is standing still, so get it moving quickly and refine while walking. */
    private static final long REPLAN_SOFT_MS = 300;
    /** Look-ahead segments plan while we walk: a 60k-node search is <300ms of CPU, so cap tightly — a long
     * look-ahead only wastes planner-thread time the main plan is waiting on. */
    private static final long AHEAD_SOFT_MS = 400, AHEAD_HARD_MS = 1_500;

    /** "plan-ms" setting: reaction time — how long the planner may run before the first partial starts the walk. */
    public static void setPlanMs(int ms) { planSoftMs = Math.max(50, Math.min(4000, ms)); }

    /** Rotation settings: hold the camera-easing profile the path follower uses each tick. */
    private static volatile com.autism.seedcracker.motion.pure.RotationMath.Profile rotProfile =
        com.autism.seedcracker.motion.pure.RotationMath.Profile.defaults();
    private static volatile boolean rotSmooth = true;
    private static volatile boolean rotHumanize = true;
    public static void setRotation(boolean smooth, float speed, float accel, float jitter, boolean humanize) {
        rotSmooth = smooth;
        rotHumanize = humanize;
        rotProfile = smooth
            ? new com.autism.seedcracker.motion.pure.RotationMath.Profile(speed, accel, jitter, 0.6f, true, humanize)
            : com.autism.seedcracker.motion.pure.RotationMath.Profile.instant();
    }
    public static com.autism.seedcracker.motion.pure.RotationMath.Profile rotationProfile() { return rotProfile; }
    public static boolean rotationSmooth() { return rotSmooth; }
    public static boolean rotationHumanize() { return rotHumanize; }

    /** "silent-rotation" setting: aim the server-side facing without moving the camera (bypass). */
    private static volatile boolean rotSilent;
    public static void setSilentRotation(boolean v) { rotSilent = v; }
    public static boolean silentRotation() { return rotSilent; }
    private static final int MAX_FAILURES = 6;
    private static final double MIN_PROGRESS = 3.0;
    /** Health (half-hearts) below which the trip pauses instead of walking into more damage. */
    private static final float LOW_HEALTH = 6f;
    private static final String RENDER_ID = "motion-path";

    private static final PathFollower FOLLOWER = new PathFollower();
    private static com.autism.seedcracker.motion.pure.AsyncPlan search;
    private static GridPathfinder.Goal goal;
    private static GridPathfinder.Config config = GridPathfinder.Config.defaults();
    /** User-configurable movement options (from the GoTo module settings). */
    private static int cfgMaxDrop = 3;
    private static double cfgHazardPenalty = 6.0;
    /** When the "break-blocks" setting is on, every trip may break blocks (as if ".goto mine"). */
    private static boolean cfgAllowBreak;
    private static TripPolicy policy;
    private static BlockPos goalPos;
    private static boolean active;
    private static boolean usingBaritone;
    private static boolean renderPath = true;
    private static boolean allowParkour;
    private static boolean allowBridge;
    private static boolean longParkour;
    private static boolean waterBucket;
    /** "cushion-blocks" setting: place hay/slime/cobweb/powder-snow to negate fall damage on big drops. */
    private static boolean cushionBlocks;
    private static boolean autoInventory;
    private static boolean avoidMobs = true;
    private static String followName;
    private static int followTicks;
    private static String status = "idle";
    private static Runnable onFinish;
    /** Next segment, planned from the current segment's end while we're still walking it. */
    private static com.autism.seedcracker.motion.pure.AsyncPlan ahead;
    private static int userPauseTicks;
    /** Ticks of player key input that pause the bot (it resumes after they let go). */
    private static final int USER_PAUSE_TICKS = 30;
    /** Plan the next segment once this few nodes are left on the current one. */
    private static final int PLAN_AHEAD_NODES = 30;
    /** Look-ahead segments only need a useful next stretch, not the full 250k flood: a smaller cap keeps the
     * single planner thread from backing up (a 250k look-ahead toward a far goal queued the main plan for ~60s). */
    private static final int AHEAD_MAX_NODES = 60_000;
    private static final int MAX_CHANGED_STREAK = 8;
    private static final int LOW_HEALTH_GIVE_UP_TICKS = 20 * 60;
    private static int changedStreak;
    private static int lowHealthTicks;
    private static long lastTickedAt = Long.MIN_VALUE;
    /** Where trips got stuck in the last two minutes; kept across trips (same snag, same area). */
    private static final com.autism.seedcracker.motion.pure.FailureMemory FAILURES =
        new com.autism.seedcracker.motion.pure.FailureMemory(20 * 120, 64);
    private static Object failuresLevel;
    /** Blocks the server wouldn't let us break (claims, spawn): planned around for the rest of the world session. */
    private static final java.util.LinkedHashSet<Long> REFUSED_BREAKS = new java.util.LinkedHashSet<>();
    private static final int MAX_REFUSED = 256;
    /** Placing was refused (build-protected area): bridging and pillaring stay off until this game tick. */
    private static long noPlaceUntil;
    private static final long NO_PLACE_TICKS = 20 * 120;
    /** Follow mode: a replan that's running while we keep walking, to be spliced in at {@link #followSpliceAt}. */
    private static com.autism.seedcracker.motion.pure.AsyncPlan followPlan;
    private static int followSpliceAt = -1;

    /** Walk to {@code target} (exact block). Returns false only if nothing could start. */
    public static boolean goTo(Minecraft mc, BlockPos target, Backend backend, boolean allowBreak) {
        return start(mc, target, GridPathfinder.block(target.getX(), target.getY(), target.getZ()), backend, allowBreak);
    }

    /** Walk to any Y at this column (surface walks, "get to these coords"). */
    public static boolean goToXZ(Minecraft mc, int x, int z, Backend backend, boolean allowBreak) {
        int y = mc.player == null ? 64 : mc.player.blockPosition().getY();
        return start(mc, new BlockPos(x, y, z), GridPathfinder.xz(x, z), backend, allowBreak);
    }

    /** Get within {@code radius} blocks of a point (interact range). */
    public static boolean goNear(Minecraft mc, BlockPos target, int radius, Backend backend, boolean allowBreak) {
        return start(mc, target, GridPathfinder.near(target.getX(), target.getY(), target.getZ(), radius), backend, allowBreak);
    }

    /** Reach this Y level anywhere (mining layers, surfacing). Built-in engine only. */
    public static boolean goToY(Minecraft mc, int y, boolean allowBreak) {
        if (mc.player == null) return false;
        BlockPos at = mc.player.blockPosition();
        return start(mc, new BlockPos(at.getX(), y, at.getZ()), GridPathfinder.yLevel(y), Backend.BUILT_IN, allowBreak);
    }

    /** Get at least {@code distance} blocks away from where we stand now. Built-in engine only. */
    public static boolean runAway(Minecraft mc, int distance) {
        if (mc.player == null) return false;
        BlockPos at = mc.player.blockPosition();
        return start(mc, at, GridPathfinder.runAway(distance, List.of(new int[]{at.getX(), at.getZ()})), Backend.BUILT_IN, false);
    }

    private static com.autism.seedcracker.motion.pure.ExplorePlanner explore;
    private static int exploreRings;
    private static int[] exploreTarget;

    /**
     * Walk outward in rings from here so new chunks load (feeds the chunk finders). Runs until
     * {@code radiusBlocks} is covered or it's stopped. Built-in engine, never breaks blocks.
     */
    public static boolean explore(Minecraft mc, int radiusBlocks) {
        if (mc.player == null) return false;
        int view = Math.max(2, mc.options.getEffectiveRenderDistance() - 2);
        int spacing = Math.max(2, view * 2 - 2);
        int cx = mc.player.getBlockX() >> 4, cz = mc.player.getBlockZ() >> 4;
        var planner = new com.autism.seedcracker.motion.pure.ExplorePlanner(cx, cz, spacing, view);
        exploreRings = Math.max(1, (radiusBlocks >> 4) / spacing);
        markLoaded(mc, planner, view);
        int[] t = planner.next(cx, cz, exploreRings);
        if (t == null) return false;
        // start() calls stop(), which clears explore state: set it after the trip has started.
        if (!goToXZ(mc, (t[0] << 4) + 8, (t[1] << 4) + 8, Backend.BUILT_IN, false)) return false;
        explore = planner;
        exploreTarget = t;
        status = "exploring";
        return true;
    }

    public static boolean exploring() {
        return explore != null;
    }

    private static void markLoaded(Minecraft mc, com.autism.seedcracker.motion.pure.ExplorePlanner p, int view) {
        int cx = mc.player.getBlockX() >> 4, cz = mc.player.getBlockZ() >> 4;
        for (int dx = -view; dx <= view; dx++) for (int dz = -view; dz <= view; dz++) {
            if (mc.level.hasChunk(cx + dx, cz + dz)) p.markSeen(cx + dx, cz + dz);
        }
    }

    /** Called when a trip ends while exploring: pick the next unseen target, or finish. */
    private static boolean continueExplore(Minecraft mc, boolean arrived) {
        var p = explore;
        if (p == null) return false;
        int view = Math.max(2, mc.options.getEffectiveRenderDistance() - 2);
        markLoaded(mc, p, view);
        if (!arrived && exploreTarget != null) p.abandon(exploreTarget[0], exploreTarget[1]);
        int[] t = p.next(mc.player.getBlockX() >> 4, mc.player.getBlockZ() >> 4, exploreRings);
        if (t == null) return false;
        exploreTarget = t;
        goal = GridPathfinder.xz((t[0] << 4) + 8, (t[1] << 4) + 8);
        goalPos = new BlockPos((t[0] << 4) + 8, mc.player.getBlockY(), (t[1] << 4) + 8);
        policy = new TripPolicy(MAX_FAILURES, MIN_PROGRESS);
        FOLLOWER.set(List.of());
        plan(mc);
        status = "exploring (" + p.seenCount() + " chunks seen)";
        return true;
    }

    /** Nearest of several targets (e.g. any of these chests); stops within {@code radius}. Built-in engine only. */
    public static boolean goNearAny(Minecraft mc, List<BlockPos> targets, int radius, boolean allowBreak) {
        if (targets.isEmpty() || mc.player == null) return false;
        List<GridPathfinder.Goal> gs = new java.util.ArrayList<>();
        BlockPos me = mc.player.blockPosition(), nearest = targets.get(0);
        for (BlockPos t : targets) {
            gs.add(GridPathfinder.near(t.getX(), t.getY(), t.getZ(), radius));
            if (t.distSqr(me) < nearest.distSqr(me)) nearest = t;
        }
        // goalPos drives the give-up progress check: measure toward the nearest target, not an arbitrary one.
        return start(mc, nearest, GridPathfinder.anyOf(gs), Backend.BUILT_IN, allowBreak);
    }

    /** Runs once when the current trip ends for any reason (arrived, gave up, stopped). */
    public static void onFinish(Runnable r) {
        onFinish = r;
    }

    private static boolean start(Minecraft mc, BlockPos target, GridPathfinder.Goal g, Backend backend, boolean allowBreak) {
        if (mc.player == null || mc.level == null) return false;
        stop(mc);
        goalPos = target;
        usingBaritone = backend == Backend.BARITONE || backend == Backend.AUTO && BaritoneCompat.isBaritoneAvailable();
        if (usingBaritone) {
            active = BaritoneCompat.startBaritoneGoTo(mc, target.getX(), target.getY(), target.getZ());
            status = active ? "baritone" : "baritone failed";
            return active;
        }
        goal = g;
        config = GridPathfinder.Config.defaults().withBreaking(allowBreak || cfgAllowBreak)
            .withMaxDrop(cfgMaxDrop).withHazardPenalty(cfgHazardPenalty);
        policy = new TripPolicy(MAX_FAILURES, MIN_PROGRESS);
        active = true;
        MotionDebug.tripStart(target.toShortString() + (allowBreak ? " (mining)" : ""), mc.player.blockPosition());
        plan(mc);
        return true;
    }

    public static void stop(Minecraft mc) {
        if (usingBaritone && active) BaritoneCompat.stopBaritone(mc);
        followName = null;
        explore = null;
        exploreTarget = null;
        boolean wasActive = active;
        if (wasActive && !usingBaritone) MotionDebug.tripEnd("stopped");
        FOLLOWER.release(mc);
        FOLLOWER.set(List.of());
        cancelPlans();
        userPauseTicks = 0;
        changedStreak = 0;
        lowHealthTicks = 0;
        active = false;
        usingBaritone = false;
        status = "idle";
        BlockEspRenderer.clear(RENDER_ID);
        if (wasActive) fireFinish();
    }

    public static boolean isBusy() {
        if (usingBaritone) return active && BaritoneCompat.isBaritoneBusy();
        return active;
    }

    public static String status() {
        return status;
    }

    /** Debug/QA: the follower's current path and progress as a compact string (for the GameBridge /path endpoint). */
    public static String debugPath() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"busy\":").append(isBusy()).append(",\"status\":").append(jsonStr(status));
        sb.append(",\"goal\":").append(goalPos == null ? "null" : "\"" + goalPos.toShortString() + "\"");
        sb.append(",\"index\":").append(FOLLOWER.index());
        sb.append(",\"remaining\":").append(FOLLOWER.remaining());
        sb.append(",\"why\":").append(jsonStr(FOLLOWER.why));
        List<Step> path = FOLLOWER.path();
        sb.append(",\"pathLen\":").append(path.size());
        sb.append(",\"nodes\":[");
        int from = Math.max(0, FOLLOWER.index() - 1), to = Math.min(path.size(), FOLLOWER.index() + 8);
        for (int i = from; i < to; i++) {
            Step s = path.get(i);
            if (i > from) sb.append(',');
            sb.append(String.format(java.util.Locale.ROOT, "{\"i\":%d,\"move\":\"%s\",\"x\":%d,\"y\":%d,\"z\":%d}",
                i, s.move(), s.x(), s.y(), s.z()));
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static BlockPos goal() {
        return goalPos;
    }

    public static void setRenderPath(boolean render) {
        renderPath = render;
    }

    public static void setSprint(boolean sprint) {
        FOLLOWER.sprint = sprint;
    }

    /** Gap jumping and block bridging for the next plans (both off by default: they're riskier). */
    public static void setAdvancedMoves(boolean parkour, boolean bridge) {
        allowParkour = parkour;
        allowBridge = bridge;
    }

    /** Max blocks the planner may drop straight down (vanilla hurts from 4). Re-plans on change. */
    public static void setMaxDrop(int v) {
        if (cfgMaxDrop == Math.max(0, v)) return;
        cfgMaxDrop = Math.max(0, v);
        config = config.withMaxDrop(cfgMaxDrop);
    }

    /** Base cost multiplier for cells next to lava/magma/fire (higher = wider berth). Re-plans on change. */
    public static void setHazardPenalty(double v) {
        if (cfgHazardPenalty == v) return;
        cfgHazardPenalty = v;
        config = config.withHazardPenalty(cfgHazardPenalty);
    }

    /** "break-blocks" setting: every trip may break blocks even without the ".goto mine" flag. */
    public static void setAllowBreak(boolean v) { cfgAllowBreak = v; }

    public static void setAvoidMobs(boolean avoid) {
        avoidMobs = avoid;
    }

    /** 3-block gap jumps after a run-up, and water-bucket clutch falls (both need the matching setting). */
    public static void setRiskyMoves(boolean longJumps, boolean bucket) {
        longParkour = longJumps;
        waterBucket = bucket;
    }

    /** "cushion-blocks" setting: place hay/slime/cobweb/powder-snow at the landing to negate big-drop fall damage. */
    public static void setCushionBlocks(boolean v) { cushionBlocks = v; }

    /** Move bridge blocks / water bucket from the main inventory to the hotbar while planning. */
    public static void setAutoInventory(boolean on) {
        autoInventory = on;
    }

    public static void setSprintJump(boolean on) {
        FOLLOWER.sprintJump = on;
    }

    /**
     * Keep within a few blocks of an online player (must be within render distance). Re-plans as
     * they move; stops when they leave render distance.
     */
    public static boolean follow(Minecraft mc, String name) {
        var target = findPlayer(mc, name);
        if (target == null) return false;
        if (!start(mc, target.blockPosition(), GridPathfinder.near(target.getBlockX(), target.getBlockY(), target.getBlockZ(), 2),
            Backend.BUILT_IN, false)) return false;
        followName = target.getGameProfile().name();
        followTicks = 0;
        return true;
    }

    public static String following() {
        return followName;
    }

    private static net.minecraft.world.entity.player.Player findPlayer(Minecraft mc, String name) {
        if (mc.level == null || name == null) return null;
        for (var p : mc.level.players()) {
            if (p != mc.player && p.getGameProfile().name().equalsIgnoreCase(name)) return p;
        }
        return null;
    }

    /** Follow mode: re-target every second, but only re-plan once they've moved meaningfully. */
    private static void tickFollow(Minecraft mc) {
        if (followName == null || ++followTicks < 20) return;
        followTicks = 0;
        var target = findPlayer(mc, followName);
        if (target == null) {
            finish(mc, "lost " + followName);
            return;
        }
        BlockPos now = target.blockPosition();
        if (goalPos != null && goalPos.distSqr(now) < 9) {
            // Already close and they're standing still: idle here instead of finishing the trip.
            if (mc.player.blockPosition().distSqr(now) <= 9 && search == null) FOLLOWER.release(mc);
            return;
        }
        goalPos = now;
        goal = GridPathfinder.near(now.getX(), now.getY(), now.getZ(), 2);
        // Same failure budget across retargets: a fresh policy each second meant a stuck follower never gave up.
        // Already walking: plan the new route from a node a few steps ahead and splice it in there, so following a
        // moving player doesn't stop-start every second while the planner runs.
        int at = search == null ? FOLLOWER.splicePoint(4) : -1;
        if (at >= 0) {
            Step from = FOLLOWER.path().get(at);
            if (followPlan != null) followPlan.cancel();
            // A look-ahead heads for where they were, and would hold the single planner thread for seconds.
            if (ahead != null) ahead.cancel();
            ahead = null;
            followSpliceAt = at;
            followPlan = com.autism.seedcracker.motion.pure.AsyncPlan.start(
                GridPathfinder.search(planWorld(mc), planConfig(mc, placeBudget(mc)), from.x(), from.y(), from.z(), goal),
                REPLAN_SOFT_MS, hardMs);
            return;
        }
        plan(mc);
    }

    /** Hands a finished follow replan to the follower at its splice node; falls back to a full plan if it can't join. */
    private static void tickFollowSplice(Minecraft mc) {
        var fp = followPlan;
        if (fp == null || !fp.done()) return;
        followPlan = null;
        List<Step> tail = fp.path();
        boolean ok = (fp.status() == Status.FOUND || fp.status() == Status.PARTIAL) && FOLLOWER.splice(followSpliceAt, tail);
        MotionDebug.event("PLAN", "follow splice at node " + followSpliceAt + (ok ? " (" + tail.size() + " nodes)" : " failed: " + fp.status()));
        followSpliceAt = -1;
        if (ok) {
            // The look-ahead plan was for the old route's end.
            if (ahead != null) ahead.cancel();
            ahead = null;
        } else {
            // We walked past the joint (or it found nothing): the old route still heads for where they WERE.
            plan(mc);
        }
    }

    private static int placeBudget(Minecraft mc) {
        if (!allowBridge || mc.level == null || mc.level.getGameTime() < noPlaceUntil) return 0;
        return Math.max(0, BridgeBlocks.count(mc) - FOLLOWER.pendingPlacements());
    }

    /** Drive one tick. Safe to call when idle; stops itself if the world goes away. */
    public static void tick(Minecraft mc) {
        if (!active) return;
        // GoTo and other modules may each call this; stepping twice a tick doubles every timer and the planner budget.
        if (mc.level != null) {
            long now = mc.level.getGameTime();
            if (now == lastTickedAt) return;
            lastTickedAt = now;
        }
        if (mc.player == null || mc.level == null || !mc.player.isAlive()) {
            stop(mc);
            return;
        }
        if (usingBaritone) {
            if (!BaritoneCompat.isBaritoneBusy()) finish(mc, "arrived");
            return;
        }
        // Walking logic means nothing while flying, gliding, riding or in spectator.
        if (mc.player.isSpectator() || mc.player.getAbilities().flying || mc.player.isFallFlying() || mc.player.isPassenger()) {
            finish(mc, "stopped: not on foot");
            return;
        }
        if (UserInput.pressed(mc)) {
            if (userPauseTicks == 0) MotionDebug.event("INPUT", "you took over at " + mc.player.blockPosition().toShortString());
            userPauseTicks = USER_PAUSE_TICKS;
            FOLLOWER.release(mc);
        }
        if (userPauseTicks > 0) {
            userPauseTicks--;
            status = "paused: you're moving";
            // A plan in flight is still good (it started where the trip was going): let it finish instead of restarting.
            if (userPauseTicks == 0) resumeAfterInput(mc);
            return;
        }
        // Burning in lava: keep walking (out is the only fix); otherwise don't push on at low health.
        if (mc.player.getHealth() <= LOW_HEALTH && !mc.player.isInWater() && !mc.player.isInLava() && !mc.player.isOnFire()) {
            FOLLOWER.release(mc);
            status = "paused: low health";
            // Without regen (hardcore, hunger) this never recovers: give the controls back after a minute.
            if (++lowHealthTicks > LOW_HEALTH_GIVE_UP_TICKS) finish(mc, "stopped: low health");
            return;
        }
        lowHealthTicks = 0;
        tickFollow(mc);
        if (!active) return;
        tickFollowSplice(mc);
        if (search != null) {
            // While the planner works we're standing still anyway: stock the hotbar for bridging/clutching.
            if (FOLLOWER.remaining() == 0 && autoInventory) MotionInventory.tick(mc, allowBridge, waterBucket);
            if (!search.done()) {
                FOLLOWER.release(mc);
                status = "planning (" + search.expanded() + ")";
                return;
            }
            var plan = search;
            search = null;
            List<Step> path = plan.path();
            Status s = plan.status();
            MotionDebug.planDone(s, path, plan.expanded(), plan.error());
            if (s == Status.FAILED || path.size() < 2) {
                finish(mc, plan.error() != null ? "planner error: " + plan.error().getClass().getSimpleName()
                    : config.allowBreak() ? "no path" : "no path (try .goto mine x y z)");
                return;
            }
            // The plan started where we stood when it was requested; a shove since then makes it someone else's route.
            if (!startsNear(path.get(0), startCell(mc))) {
                // A current or mob pushing us every plan would replan forever: count it toward giving up.
                MotionDebug.failure("MOVED", "pushed off the start while planning", null, false);
                fail(mc, "moved while planning");
                return;
            }
            FOLLOWER.world = LevelWalkability.live(mc, false);
            FOLLOWER.set(path);
            status = (s == Status.FOUND ? "walking " : "walking (partial) ") + path.size() + " nodes";
        }

        Step failedAt = FOLLOWER.current();
        PathFollower.Result r = FOLLOWER.tick(mc);
        if (renderPath && !MotionDebug.enabled()) feedRender();
        if (MotionDebug.enabled()) {
            MotionDebug.feedMarkers(FOLLOWER.path(), FOLLOWER.index());
            if (mc.level.getGameTime() % 20 == 0) feedStrikes(mc);
        }
        if (r == PathFollower.Result.WALKING) tickAhead(mc);
        if (r != PathFollower.Result.WALKING && r != PathFollower.Result.DONE) {
            boolean struck = r == PathFollower.Result.STUCK || r == PathFollower.Result.BLOCKED
                || r == PathFollower.Result.CHANGED && changedStreak + 1 > MAX_CHANGED_STREAK;
            MotionDebug.failure(r.name(), FOLLOWER.why, failedAt, struck);
        }
        switch (r) {
            case WALKING -> { }
            case DONE -> {
                BlockPos at = startCell(mc);
                // The follower finishes a hop up to ~0.6 off its centre; if the route's last node is the goal, that's arrival.
                boolean reached = goal.reached(at.getX(), at.getY(), at.getZ()) || endsAtGoal(mc);
                if (followName != null && reached) status = "following " + followName;
                else if (reached) finish(mc, "arrived");
                else if (policy.segmentDone(distanceToGoal(mc)) == TripPolicy.Verdict.GIVE_UP) finish(mc, "gave up: no progress");
                else if (!continueAhead(mc, at)) replan(mc, "next segment");
            }
            case OFF_PATH -> {
                // Knockback / lag-back usually leaves us beside the route, not lost: pick it back up before failing.
                if (FOLLOWER.rejoin(mc)) MotionDebug.event("NODE", "rejoined route at node " + FOLLOWER.index() + " after going off it");
                else fail(mc, "off path");
            }
            case STUCK -> {
                strike(mc);
                fail(mc, "stuck");
            }
            case BLOCKED -> {
                learnRefusal(mc);
                strike(mc);
                fail(mc, "blocked");
            }
            case DANGER -> fail(mc, "hazard ahead");
            // The world changed under the plan; replanning isn't our failure, but a world that never settles (flowing
            // lava, a mob parked on the route) would replan forever: past a few in a row it counts as a failure.
            case CHANGED -> {
                if (++changedStreak > MAX_CHANGED_STREAK) {
                    changedStreak = 0;
                    strike(mc);
                    fail(mc, "route keeps changing");
                } else {
                    replan(mc, "world changed");
                }
            }
        }
        if (r != PathFollower.Result.CHANGED && r != PathFollower.Result.WALKING) changedStreak = 0;
    }

    /**
     * Near the end of a PARTIAL segment, start planning the next one from its last node so the bot
     * keeps walking instead of standing still while the planner works.
     */
    private static void tickAhead(Minecraft mc) {
        if (ahead != null) return;
        List<Step> p = FOLLOWER.path();
        if (p.size() < 2 || FOLLOWER.remaining() > PLAN_AHEAD_NODES) return;
        Step end = p.get(p.size() - 1);
        if (goal.reached(end.x(), end.y(), end.z()) || followPlan != null) return;
        int budget = placeBudget(mc);
        MotionDebug.planStart(new BlockPos(end.x(), end.y(), end.z()), AHEAD_MAX_NODES, true);
        ahead = com.autism.seedcracker.motion.pure.AsyncPlan.start(
            GridPathfinder.search(planWorld(mc), planConfig(mc, budget).withMaxNodes(AHEAD_MAX_NODES), end.x(), end.y(), end.z(), goal),
            AHEAD_SOFT_MS, AHEAD_HARD_MS);
    }

    /** Hand over to the look-ahead plan if it's ready and starts where we stand. */
    private static boolean continueAhead(Minecraft mc, BlockPos at) {
        var a = ahead;
        ahead = null;
        if (a == null) return false;
        // Still working: keep it instead of starting over; the planning branch of tick() picks it up.
        if (!a.done()) {
            search = a;
            status = "planning next segment (" + a.expanded() + ")";
            return true;
        }
        Status s = a.status();
        MotionDebug.planDone(s, a.path(), a.expanded(), a.error());
        if (s != Status.FOUND && s != Status.PARTIAL) return false;
        List<Step> next = a.path();
        if (next.size() < 2 || !startsNear(next.get(0), at)) return false;
        FOLLOWER.world = LevelWalkability.live(mc, false);
        FOLLOWER.set(next);
        status = (s == Status.FOUND ? "walking " : "walking (partial) ") + next.size() + " nodes";
        return true;
    }

    private static boolean endsAtGoal(Minecraft mc) {
        List<Step> p = FOLLOWER.path();
        if (p.isEmpty()) return false;
        Step last = p.get(p.size() - 1);
        if (!goal.reached(last.x(), last.y(), last.z())) return false;
        double dx = last.x() + 0.5 - mc.player.getX(), dz = last.z() + 0.5 - mc.player.getZ();
        return dx * dx + dz * dz < 1.0 && Math.abs(mc.player.getY() - last.y()) < 1.0;
    }

    private static boolean startsNear(Step first, BlockPos at) {
        return Math.abs(first.x() - at.getX()) <= 1 && Math.abs(first.z() - at.getZ()) <= 1 && Math.abs(first.y() - at.getY()) <= 1;
    }

    private static void cancelPlans() {
        if (search != null) search.cancel();
        if (ahead != null) ahead.cancel();
        if (followPlan != null) followPlan.cancel();
        search = null;
        ahead = null;
        followPlan = null;
        followSpliceAt = -1;
    }

    private static void feedStrikes(Minecraft mc) {
        List<BlockPos> cells = new java.util.ArrayList<>();
        FAILURES.forEachActive(mc.level.getGameTime(), (x, y, z, c) -> cells.add(new BlockPos(x, y, z)));
        MotionDebug.feedStrikes(cells);
    }

    /** Node being walked to (debug HUD). */
    static Step debugTarget() {
        return FOLLOWER.current();
    }

    static int debugRemaining() {
        return FOLLOWER.remaining();
    }

    /** Remember the node we failed on and forget the route through it (favoring it would just pick it again). */
    private static void strike(Minecraft mc) {
        Step t = FOLLOWER.current();
        if (t != null && mc.level != null) FAILURES.record(t.x(), t.y(), t.z(), mc.level.getGameTime());
        FOLLOWER.set(List.of());
    }

    /** Snapshot of every loaded chunk the planner could reach (render distance), safe for the planner thread. */
    private static LevelWalkability planWorld(Minecraft mc) {
        if (mc.level != failuresLevel) {
            FAILURES.clear();
            REFUSED_BREAKS.clear();
            noPlaceUntil = 0;
            LevelWalkability.clearStateCache();
            failuresLevel = mc.level;
        }
        int radius = Math.max(2, mc.options.getEffectiveRenderDistance() + 1);
        return LevelWalkability.snapshot(mc, avoidMobs, radius).withFailures(FAILURES, mc.level.getGameTime())
            .withRefusedBreaks(REFUSED_BREAKS);
    }

    /** What a BLOCKED result tells us about the server: don't plan the same refused break / placement again. */
    private static void learnRefusal(Minecraft mc) {
        long b = FOLLOWER.refusedBreak;
        if (b != GridPathfinder.NO_PLACE) {
            REFUSED_BREAKS.add(b);
            if (REFUSED_BREAKS.size() > MAX_REFUSED) REFUSED_BREAKS.remove(REFUSED_BREAKS.iterator().next());
            MotionDebug.event("PLAN", "server refused breaking " + GridPathfinder.unpackX(b) + " " + GridPathfinder.unpackY(b)
                + " " + GridPathfinder.unpackZ(b) + ": routing around it");
        }
        if (FOLLOWER.placeRefused && mc.level != null) {
            noPlaceUntil = mc.level.getGameTime() + NO_PLACE_TICKS;
            MotionDebug.event("PLAN", "placing refused: no bridging/pillaring for 2 min");
        }
    }

    /**
     * After the player lets go: keep walking the same route if we're still on it (the old code threw
     * the plan away and replanned every time, which is what "input breaks it" was). Not a failure.
     */
    private static void resumeAfterInput(Minecraft mc) {
        if (search != null) {
            MotionDebug.event("INPUT", "released; plan still running");
            return;
        }
        if (FOLLOWER.rejoin(mc)) {
            MotionDebug.event("INPUT", "released; rejoined route at node " + FOLLOWER.index());
            status = "walking (resumed)";
            return;
        }
        MotionDebug.event("INPUT", "released off the route; replanning");
        replan(mc, "resumed");
    }

    private static void fail(Minecraft mc, String why) {
        if (policy.failure(distanceToGoal(mc)) == TripPolicy.Verdict.GIVE_UP) {
            finish(mc, "gave up: " + why);
            return;
        }
        replan(mc, why);
    }

    private static void replan(Minecraft mc, String why) {
        quickPlan = !why.equals("next segment");
        plan(mc);
        status = "replanning: " + why;
    }

    private static boolean quickPlan;

    /**
     * The feet cell the planner should start from. blockPosition() is the column under the player's centre:
     * standing on a block's lip that's the air beside it, and mid-fall it's somewhere in the air. Planning
     * from there treats thin air as floor.
     */
    static BlockPos startCell(Minecraft mc) {
        BlockPos at = mc.player.blockPosition();
        // Standing in a tall block's top (soul sand, mud, path) puts blockPosition one below the walkable cell. Slabs and
        // carpets (top <= 0.5625) are the planner's LOW feet cells: the node IS that cell, so stay in it.
        var shape = mc.level.getBlockState(at).getCollisionShape(mc.level, at);
        if (!shape.isEmpty() && shape.max(net.minecraft.core.Direction.Axis.Y) > 0.5625
            && mc.level.getBlockState(at.above()).getCollisionShape(mc.level, at.above()).isEmpty()) {
            return at.above();
        }
        if (holdsUp(mc, at) || mc.player.isInWater() || mc.player.onClimbable()) return at;
        if (mc.player.onGround()) {
            // On a lip: the block we're actually standing on is under one of the cells our hitbox overlaps.
            var box = mc.player.getBoundingBox();
            BlockPos best = null;
            double bestD = Double.MAX_VALUE;
            for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX - 1e-7); x++) {
                for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ - 1e-7); z++) {
                    BlockPos c = new BlockPos(x, at.getY(), z);
                    if (c.equals(at) || !holdsUp(mc, c) || !mc.level.getBlockState(c).getCollisionShape(mc.level, c).isEmpty()) continue;
                    double d = Math.hypot(x + 0.5 - mc.player.getX(), z + 0.5 - mc.player.getZ());
                    if (d < bestD) {
                        bestD = d;
                        best = c;
                    }
                }
            }
            return best != null ? best : at;
        }
        // Mid-air: plan from where this fall lands (a short way down), not from the air we're passing through.
        for (int dy = 1; dy <= 6; dy++) {
            BlockPos c = at.below(dy);
            if (!mc.level.getBlockState(c).getCollisionShape(mc.level, c).isEmpty()) return at;
            if (holdsUp(mc, c) || !mc.level.getBlockState(c).getFluidState().isEmpty()) return c;
        }
        return at;
    }

    /** Something under (or in) this feet cell holds a player up. */
    private static boolean holdsUp(Minecraft mc, BlockPos feet) {
        BlockPos below = feet.below();
        return !mc.level.getBlockState(below).getCollisionShape(mc.level, below).isEmpty()
            || mc.level.getBlockState(feet).is(net.minecraft.tags.BlockTags.CLIMBABLE)
            || !mc.level.getBlockState(feet).getFluidState().isEmpty();
    }

    private static void plan(Minecraft mc) {
        cancelPlans();
        BlockPos at = startCell(mc);
        // Preload the chunk cache regions covering the loaded area + the corridor to the goal, so beyond-render-
        // distance lookups during the search come from memory (no per-chunk disk reads on the planner thread).
        if (goalPos != null) com.autism.seedcracker.motion.ChunkCache.preloadBetween(
            at.getX() >> 4, at.getZ() >> 4, goalPos.getX() >> 4, goalPos.getZ() >> 4, 1);
        // Re-read the hotbar every plan: the budget must never promise blocks we no longer carry.
        GridPathfinder.Config c = planConfig(mc, allowBridge && mc.level.getGameTime() >= noPlaceUntil ? BridgeBlocks.count(mc) : 0);
        // Prefer the cells of the segment we were walking so a replan doesn't flip to an equal-cost twin.
        long[] favored = FOLLOWER.remaining() > 0 ? FOLLOWER.remainingCells() : null;
        MotionDebug.planStart(at, MAX_NODES, false);
        if (goalPos != null) MotionDebug.planGoal(goalPos.getX(), goalPos.getY(), goalPos.getZ());
        search = com.autism.seedcracker.motion.pure.AsyncPlan.start(
            GridPathfinder.search(planWorld(mc), c, at.getX(), at.getY(), at.getZ(), goal, favored),
            quickPlan ? REPLAN_SOFT_MS : planSoftMs, hardMs);
        quickPlan = false;
        status = "planning";
    }

    private static GridPathfinder.Config planConfig(Minecraft mc, int placeBudget) {
        boolean bucket = waterBucket && hasItem(mc, net.minecraft.world.item.Items.WATER_BUCKET);
        boolean cushion = cushionBlocks && hasCushion(mc);
        // Gap jumps need a sprint, and hunger 6 or less can't sprint: the follower would refuse every planned jump.
        boolean canSprint = mc.player == null || mc.player.getFoodData().getFoodLevel() > 6 || mc.player.getAbilities().mayfly;
        boolean parkour = allowParkour && canSprint;
        GridPathfinder.Config c = config.withParkour(parkour).withPlaceBudget(placeBudget)
            .withLongParkour(parkour && longParkour).withWaterBucket(bucket).withCushionBlocks(cushion).withMaxNodes(MAX_NODES)
            .withHeuristicWeight(HEURISTIC_WEIGHT);
        FOLLOWER.config = c;
        return c;
    }

    /** Hay bale / slime / cobweb / powder snow in the hotbar: any of them can cushion a landing. */
    private static boolean hasCushion(Minecraft mc) {
        return hasItem(mc, net.minecraft.world.item.Items.HAY_BLOCK)
            || hasItem(mc, net.minecraft.world.item.Items.SLIME_BLOCK)
            || hasItem(mc, net.minecraft.world.item.Items.COBWEB)
            || hasItem(mc, net.minecraft.world.item.Items.POWDER_SNOW_BUCKET);
    }

    private static boolean hasItem(Minecraft mc, net.minecraft.world.item.Item item) {
        if (mc.player == null) return false;
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getItem(i).getItem() == item) return true;
        return false;
    }

    /**
     * Progress measure for the give-up policy: the goal's own heuristic, so run-away (farther is better)
     * and Y-level (only height matters) goals count progress the right way round.
     */
    private static double distanceToGoal(Minecraft mc) {
        if (mc.player == null) return 0;
        BlockPos at = mc.player.blockPosition();
        if (goal != null) return goal.heuristic(at.getX(), at.getY(), at.getZ());
        if (goalPos == null) return 0;
        return Math.sqrt(at.distSqr(goalPos));
    }

    private static void finish(Minecraft mc, String why) {
        if (explore != null && mc.player != null && continueExplore(mc, why.equals("arrived"))) return;
        if (explore != null) why = "explored (" + explore.seenCount() + " chunks)";
        MotionDebug.tripEnd(why);
        explore = null;
        exploreTarget = null;
        FOLLOWER.release(mc);
        active = false;
        cancelPlans();
        userPauseTicks = 0;
        status = why;
        BlockEspRenderer.clear(RENDER_ID);
        fireFinish();
    }

    private static void fireFinish() {
        Runnable r = onFinish;
        onFinish = null;
        if (r != null) r.run();
    }

    private static void feedRender() {
        List<Step> p = FOLLOWER.path();
        if (p.isEmpty()) return;
        Set<BlockPos> blocks = new HashSet<>();
        for (int i = Math.max(0, FOLLOWER.index() - 1); i < p.size(); i++) {
            blocks.add(new BlockPos(p.get(i).x(), p.get(i).y() - 1, p.get(i).z()));
        }
        BlockEspRenderer.init();
        BlockEspRenderer.feed(RENDER_ID, blocks, 0xFF3FE87E, false, false);
    }
}
