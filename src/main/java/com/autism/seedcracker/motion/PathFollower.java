package com.autism.seedcracker.motion;

import java.util.List;

import com.autism.seedcracker.motion.pure.GridPathfinder.Move;
import com.autism.seedcracker.motion.pure.GridPathfinder.Step;
import com.autism.seedcracker.motion.pure.RotationMath;
import com.autism.seedcracker.util.ContainerMutex;
import com.autism.seedcracker.util.pure.StuckLogic;
import com.autism.seedcracker.util.tunnel.Hazards;
import com.autism.seedcracker.util.tunnel.MovementInput;
import com.autism.seedcracker.util.tunnel.SilentRotation;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Walks a planned path with real key presses: faces the next node through {@link RotationEngine},
 * holds forward, jumps ascents, swims, and breaks marked blocks with the best hotbar tool.
 * Never trusts the plan blindly: every node is re-checked for lava/fire that appeared after
 * planning, a break that drags on or a block that turns unbreakable aborts the segment, and
 * losing the camera to a higher-priority module pauses (never walks blind).
 */
final class PathFollower {

    /** CHANGED = the world changed on an upcoming node: replan now (not a failure of ours). */
    enum Result { WALKING, DONE, OFF_PATH, STUCK, BLOCKED, DANGER, CHANGED }

    static final String ROT_OWNER = "motion";
    /** A hop gets its estimated ticks plus this much slack before it counts as stuck (circling, sliding on ice). */
    private static final int NODE_TIMEOUT_SLACK_TICKS = 50;
    private static final int LOOKAHEAD = 5;
    /** Half-width used for swept line checks: the body is 0.3, the rest absorbs octant steering error. */
    private static final double BODY_CLEARANCE = 0.35;
    private static final double CORNER_CUT_RADIUS = 1.0;
    /** Past this the resolved keys include back: stop and turn instead of backpedalling. */
    private static final float MAX_STRAFE_YAW_ERR = 112.5f;
    /** How far ahead along the path the steering point slides (blocks): bigger = smoother, smaller = tighter corners. */
    private static final double CARROT_AHEAD = 2.5;
    /** Never aim at a point closer than this: bearings to a point under your feet swing wildly (the flicks). */
    private static final double MIN_AIM_DIST = 1.2;
    /** Further than this from the path line (not the node) is off-path. */
    private static final double OFF_PATH_DIST = 2.5;
    /** A hop re-priced this much dearer than planned (gravel fell in, a mob moved onto it) triggers a replan. */
    private static final double MAX_COST_INCREASE = 10.0;
    /** Bucket clutch: place the water once this close above the landing block. */
    private static final double CLUTCH_HEIGHT = 3.5;
    private int clutchState;
    private boolean onGroundNow;
    private int unloadedTicks;
    /** Waiting this long for the next chunk to arrive (server lag, slow chunk sending) counts as stuck. */
    private static final int UNLOADED_WAIT_TICKS = 20 * 15;
    /** Planner world + config this path was built with (for re-pricing upcoming hops). */
    com.autism.seedcracker.motion.pure.GridPathfinder.World world;
    com.autism.seedcracker.motion.pure.GridPathfinder.Config config;
    private int nodeTicks;
    private static final int BREAK_TIMEOUT_TICKS = 20 * 12;
    private static final int PLACE_TIMEOUT_TICKS = 20 * 3;
    private int placeTicks;
    boolean sprintJump;

    private List<Step> path = List.of();
    private int index;
    private int stillTicks;
    private Vec3 lastPos;
    private BlockPos breaking;
    private int breakTicks;
    private int swapWait;
    boolean sprint = true;

    void set(List<Step> path) {
        this.path = path;
        this.index = path.size() > 1 ? 1 : path.size();
        stillTicks = 0;
        lastPos = null;
        breaking = null;
        breakTicks = 0;
        swapWait = 0;
        placeTicks = 0;
        nodeTicks = 0;
        clutchState = 0;
        heldYaw = null;
        unstickStage = 0;
        lastAimBearing = Double.NaN;
        refusedBreak = com.autism.seedcracker.motion.pure.GridPathfinder.NO_PLACE;
        placeRefused = false;
    }

    /** Packed cells of the remaining path, for route favoring on the next plan. */
    long[] remainingCells() {
        long[] out = new long[remaining()];
        for (int i = index, j = 0; i < path.size(); i++, j++) {
            Step s = path.get(i);
            out[j] = com.autism.seedcracker.motion.pure.GridPathfinder.pack(s.x(), s.y(), s.z());
        }
        return out;
    }

    /** Blocks the rest of this path will still place (so a look-ahead plan can't spend them twice). */
    int pendingPlacements() {
        int n = 0;
        for (int i = index; i < path.size(); i++) if (path.get(i).place() != com.autism.seedcracker.motion.pure.GridPathfinder.NO_PLACE) n++;
        return n;
    }

    int remaining() {
        return Math.max(0, path.size() - index);
    }

    List<Step> path() {
        return path;
    }

    int index() {
        return index;
    }

    /** Why the last non-WALKING result happened (debug log). */
    String why = "";
    /** Packed block the server wouldn't let us break (protected claim / spawn), or NO_PLACE. Read after BLOCKED. */
    long refusedBreak = com.autism.seedcracker.motion.pure.GridPathfinder.NO_PLACE;
    /** A placement was never accepted: the area doesn't allow building. Read after BLOCKED. */
    boolean placeRefused;

    private Result fail(Result r, String reason) {
        why = reason;
        return r;
    }

    /**
     * Ends the route at node {@code last} (never behind the node being walked to), so a fresh segment can
     * be handed over there while we keep walking.
     */
    void truncate(int last) {
        if (last >= index && last + 1 < path.size()) path = List.copyOf(path.subList(0, last + 1));
    }

    /**
     * Swaps everything after node {@code at} for {@code tail} (a route planned from that node) without stopping:
     * the node being walked to stays the same. False once we've already reached {@code at}, or when the tail
     * doesn't start there.
     */
    boolean splice(int at, List<Step> tail) {
        if (at < index || at >= path.size() || tail.size() < 2) return false;
        Step joint = path.get(at), first = tail.get(0);
        if (joint.x() != first.x() || joint.y() != first.y() || joint.z() != first.z()) return false;
        List<Step> merged = new java.util.ArrayList<>(at + tail.size());
        merged.addAll(path.subList(0, at + 1));
        merged.addAll(tail.subList(1, tail.size()));
        path = List.copyOf(merged);
        return true;
    }

    /**
     * A node a few hops ahead where a replacement route can join without us stopping: plain walking nodes
     * only (no break/place/jump in flight). -1 when the rest of the path is too short or too busy.
     */
    int splicePoint(int minAhead) {
        for (int i = index + minAhead; i < Math.min(path.size() - 1, index + minAhead + 6); i++) {
            Step s = path.get(i);
            if (flowing(s) && (s.move() == Move.WALK || s.move() == Move.DIAGONAL)) return i;
        }
        return -1;
    }

    /**
     * After the player moved us by hand: pick the route back up at the nearest point ahead of where we
     * stand (within reach on the same level). False when we're too far off it to continue.
     */
    boolean rejoin(Minecraft mc) {
        if (mc.player == null || path.size() < 2) return false;
        Vec3 p = mc.player.position();
        int from = Math.max(1, index - 10), to = Math.min(path.size() - 1, index + 40);
        var pr = com.autism.seedcracker.motion.pure.PathGeometry.project(path, from, to, p.x, p.y, p.z, 1.5);
        if (pr == null || pr.distSq() > 2.0 * 2.0) return false;
        index = pr.seg();
        stillTicks = 0;
        lastPos = null;
        nodeTicks = 0;
        placeTicks = 0;
        clutchState = 0;
        heldYaw = null;
        unstickStage = 0;
        return true;
    }

    /** Node we're walking toward, or null between segments. */
    Step current() {
        return index >= 1 && index < path.size() ? path.get(index) : null;
    }

    /** What this tick did, for the trace line ("walk" unless a special move or wait took over). */
    private String tickTag = "walk";
    private String lastWait = "";

    /** One trace line per tick, whichever branch the tick ended in (the old trace only covered plain walking). */
    Result tick(Minecraft mc) {
        tickTag = "walk";
        Result r = tickInner(mc);
        if (mc.player != null && mc.options != null && index < path.size() && !tickTag.equals("walk")) {
            Step t = path.get(index);
            var k = new MovementInput.Keys(mc.options.keyUp.isDown(), mc.options.keyDown.isDown(), mc.options.keyLeft.isDown(),
                mc.options.keyRight.isDown());
            MotionDebug.trace(mc.player.position(), mc.player.getYRot(), mc.player.getXRot(), mc.player.getYRot(), 0f, k,
                mc.options.keyJump.isDown(), mc.player.isSprinting(), mc.player.onGround(), mc.player.isInWater(), t, index,
                t.x() + 0.5, t.z() + 0.5, tickTag);
        }
        String wait = tickTag.startsWith("wait:") ? tickTag.substring(5) : "";
        if (!wait.equals(lastWait)) {
            if (!wait.isEmpty()) MotionDebug.event("WAIT", wait);
            else MotionDebug.event("WAIT", "resumed after: " + lastWait);
            lastWait = wait;
        }
        if (r != Result.WALKING) lastWait = "";
        return r;
    }

    private Result tickInner(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.options == null) return Result.STUCK;
        if (index >= path.size()) {
            release(mc);
            return Result.DONE;
        }
        // Menus and container clicks: keys off and wait, but don't count it as being stuck.
        if (ContainerMutex.containerBusy(mc) || mc.gui.screen() != null) {
            holdStill(mc);
            lastPos = null;
            tickTag = "wait:menu open";
            return Result.WALKING;
        }
        Step target = path.get(index);
        // Next hop's chunk unloaded (it was loaded when planned): stop at the edge rather than walk into unknown ground.
        if (!mc.level.hasChunk(target.x() >> 4, target.z() >> 4)) {
            holdStill(mc);
            lastPos = null;
            tickTag = "wait:next chunk not loaded";
            if (++unloadedTicks > UNLOADED_WAIT_TICKS) {
                unloadedTicks = 0;
                return fail(Result.STUCK, "next chunk never loaded (15s)");
            }
            return Result.WALKING;
        }
        unloadedTicks = 0;
        if (nodeNowDangerous(mc, target)) {
            release(mc);
            return fail(Result.DANGER, "lava/fire appeared on next node");
        }
        if (aheadChanged(mc)) {
            release(mc);
            return fail(Result.CHANGED, why);
        }
        Vec3 p = mc.player.position();
        double cx = target.x() + 0.5, cz = target.z() + 0.5;
        double dx = cx - p.x, dz = cz - p.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        onGroundNow = mc.player.onGround();

        // Swimming bobs the feet ~0.7 under the surface: a dry 0.9 tolerance never counted water nodes as reached.
        boolean inWaterNow = mc.player.isInWater();
        double yTol = inWaterNow ? 1.4 : 0.9;
        boolean centred = horiz < 0.35 && Math.abs(p.y - target.y()) < yTol && clutchDone(mc, target)
            && (target.move() != Move.PILLAR || onGroundNow && p.y >= target.y() - 0.1);
        if (centred || reachedByOvershoot(p, target) || canCutCorner(mc, target, p, horiz)) {
            advanceTo(mc, index + 1, "reached");
            return index >= path.size() ? tick(mc) : Result.WALKING;
        }
        // Walked past this node along the line (off-centre, or the steering point pulled us across): it's done.
        int passed = passedThrough(mc, p, inWaterNow);
        if (passed > index) {
            advanceTo(mc, passed, "passed");
            return index >= path.size() ? tick(mc) : Result.WALKING;
        }
        // Skip ahead if a later node is where we already are (shortcut, or a fall carried us forward).
        for (int i = index + 1; i < Math.min(path.size(), index + 6); i++) {
            Step s = path.get(i);
            if (Math.floor(p.x) == s.x() && Math.floor(p.z) == s.z() && Math.abs(p.y - s.y()) < 0.6) {
                advanceTo(mc, i + 1, "shortcut");
                return index >= path.size() ? tick(mc) : Result.WALKING;
            }
        }
        // Lag-back / rubberband put us on an earlier node: resume from there instead of replanning.
        // Guard: only treat it as a real lag-back if the player actually MOVED backward since the last advance.
        // On a stationary player the shortcut and lag-back matchers both hit the same spot and oscillate forever
        // (and both return before steering, so no movement keys are ever sent — the freeze). If we didn't move,
        // fall through to steering instead of bouncing the index.
        boolean movedSinceAdvance = lastAdvancePos == null || p.distanceToSqr(lastAdvancePos) > 0.0004;
        // A genuine rubber-band teleports us back several blocks at once, so we land near a node 3+ back and far
        // from the current target. A normal forward step also passes within 0.4 of a recent node (a straight walk
        // always does), which made the old 10-back/0.4 check bounce the index in a loop. Require the match to be
        // a few nodes back AND well clear of the node we're walking toward before treating it as a real lag-back.
        double dCur = Double.MAX_VALUE;
        if (index < path.size()) {
            Step cur = path.get(index);
            double cdx = p.x - (cur.x() + 0.5), cdz = p.z - (cur.z() + 0.5);
            dCur = cdx * cdx + cdz * cdz;
        }
        if (movedSinceAdvance && dCur > 1.0) for (int i = Math.max(0, index - 12); i < index - 2; i++) {
            Step s = path.get(i);
            // Genuine lag-back: near an earlier node's CENTRE, not just anywhere in its block. A floor-equality
            // match is a full block wide, so walking -x/-z tripped it on every normal node transition (P3 noise).
            double ddx = p.x - (s.x() + 0.5), ddz = p.z - (s.z() + 0.5);
            if (ddx * ddx + ddz * ddz < 0.16 && Math.abs(p.y - s.y()) < 0.6) {
                MotionDebug.event("NODE", "pulled back to node " + (i + 1) + " from " + index + " (lag-back/knockback)");
                index = i + 1;
                stillTicks = 0;
                nodeTicks = 0;
                return Result.WALKING;
            }
        }
        // Water drops, ladders, clutch falls and 3-block descends legitimately put us far above/below the node.
        boolean vertical = target.move() == Move.WATER_DROP || target.move() == Move.CLIMB_DOWN || target.move() == Move.CLIMB_UP
            || target.move() == Move.LADDER_CATCH || target.move() == Move.BUCKET_DROP || target.move() == Move.CUSHION_DROP
            || target.move() == Move.PILLAR
            || target.move() == Move.DESCEND && !mc.player.onGround();
        double lineDist = distanceToLine(p);
        // Off the route line is enough (the old AND-with-node-distance missed a sideways drift beside a node); the last hop has no line ahead.
        boolean farOff = remaining() > 1 ? lineDist > OFF_PATH_DIST : horiz > 3.5;
        if (farOff && !vertical || !vertical && (p.y - target.y() > 4 || target.y() - p.y > 2.5)) {
            return fail(Result.OFF_PATH, String.format(java.util.Locale.ROOT, "%.1f from path line (%.1f from node), dy %.1f",
                lineDist, horiz, p.y - target.y()));
        }
        if (target.move() == Move.WATER_DROP && !landingStillWater(mc, target)) {
            release(mc);
            return fail(Result.DANGER, "water-drop landing dried up");
        }
        // The floor of the next walk was mined out or fell: replan now. Standing still here ran the unstick
        // routine (jump + back off + sidestep) right beside the new hole.
        if (onGroundNow && edgeWithoutPlan(mc, target)) {
            release(mc);
            return fail(Result.CHANGED, "floor gone under " + target.x() + " " + (target.y() - 1) + " " + target.z());
        }

        Result br = pendingBreak(mc, target);
        if (br != null) {
            tickTag = breaking != null ? "break " + breaking.toShortString() + " t" + breakTicks : "break (aiming/swapping)";
            return br;
        }
        if (target.move() == Move.PILLAR) {
            tickTag = "pillar";
            return pillar(mc, target, p);
        }
        if (target.move() == Move.BUCKET_DROP) {
            Result c = clutch(mc, target, p);
            if (c != null) {
                tickTag = "clutch stage " + clutchState;
                return c;
            }
        }
        if (target.move() == Move.CUSHION_DROP) {
            Result c = cushionClutch(mc, target, p);
            if (c != null) {
                tickTag = "cushion stage " + clutchState;
                return c;
            }
        }
        Result pl = pendingPlace(mc, target);
        if (pl != null) {
            tickTag = "bridge place";
            return pl;
        }
        Result door = pendingDoor(mc, target);
        if (door != null) {
            tickTag = "open door";
            return door;
        }

        stillTicks = StuckLogic.update(stillTicks, lastPos != null, lastPos == null ? 0 : lastPos.distanceTo(p), 0.02);
        lastPos = p;
        Result unstuck = unstick(mc, target, p);
        if (unstuck != null) {
            tickTag = "unstick " + (unstickStage == 2 ? "back-off" : "") + " still " + stillTicks;
            return unstuck;
        }

        boolean inWater = mc.player.isInWater();
        boolean airborne = !onGroundNow && !inWater && !mc.player.onClimbable();
        double[] aim = steerPoint(new Mc(mc, p));
        double sx = aim[0], sz = aim[1];
        float[] rot = RotationMath.lookAt(sx - p.x, 0, sz - p.z);
        // Climbing: face the ladder column and look up/down the way we're going.
        if (target.move() == Move.CLIMB_UP || target.move() == Move.CLIMB_DOWN) rot = RotationMath.lookAt(cx - p.x, 0, cz - p.z);
        // Mid-air the body can't turn anyway: re-aiming only flicks the camera. Hold the take-off heading.
        if (airborne && heldYaw != null && target.move() != Move.PARKOUR) rot[0] = heldYaw;
        if (!airborne) heldYaw = rot[0];
        float pitch = switch (target.move()) {
            case DIG_DOWN -> 80f;
            case CLIMB_UP -> -40f;
            case CLIMB_DOWN, WATER_DROP -> 50f;
            default -> 10f;
        };
        if (!RotationEngine.request(ROT_OWNER, RotationEngine.PRIORITY_MOVE, rot[0], pitch, Motion.rotationProfile(), Motion.silentRotation())
            && !ROT_OWNER.equals(RotationEngine.owner())) {
            // Another module (combat, safety) has the camera: stand still rather than walk where we can't see.
            holdStill(mc);
            lastPos = null;
            tickTag = "wait:camera held by " + RotationEngine.owner();
            return Result.WALKING;
        }
        if (++nodeTicks > nodeTimeout(target)) {
            release(mc);
            return fail(Result.STUCK, "hop took too long (" + nodeTicks + " ticks, " + target.move() + ")");
        }

        // Effective facing: the silent-aim yaw when silent rotation is driving, else the camera yaw.
        float faced = Motion.silentRotation() && SilentRotation.isActive() ? SilentRotation.getYaw() : mc.player.getYRot();
        float yawErr = Math.abs(RotationMath.wrap(rot[0] - faced));
        if (target.move() == Move.PARKOUR) {
            tickTag = String.format(java.util.Locale.ROOT, "parkour yawErr %.1f", yawErr);
            return parkour(mc, target, p, horiz, yawErr);
        }
        if (target.move() == Move.CLIMB_UP || target.move() == Move.CLIMB_DOWN) {
            tickTag = "climb";
            return climb(mc, target, p, horiz);
        }
        // Falling past the ladder: hold forward into it, which is what stops a fall on a climbable.
        if (target.move() == Move.LADDER_CATCH && !mc.player.onGround() && Math.floor(p.x) == target.x()
            && Math.floor(p.z) == target.z()) {
            MovementInput.setKeys(new MovementInput.Keys(true, false, false, false), false);
            mc.options.keyJump.setDown(false);
            tickTag = "ladder catch";
            return Result.WALKING;
        }
        // Keys resolve against the current view, so mid-turn we strafe toward the point instead of standing still.
        boolean go = yawErr < MAX_STRAFE_YAW_ERR && target.move() != Move.DIG_DOWN && !edgeWithoutPlan(mc, target);
        boolean sneakMagma = magmaSneak(mc, target, p);
        boolean canSprint = go && sprint && yawErr < 20f && !inWater && sprintable(mc, target) && !sneakMagma
            && mc.player.getFoodData().getFoodLevel() > 6 && !(slippery(mc, target, p) && straightRunAhead() < 4);
        MovementInput.Keys keys = go ? MovementInput.keysToward(sx, sz) : MovementInput.Keys.NONE;
        // In the air, steer at where we'll land: the next drop node if we just walked off its edge, else the target.
        if (airborne && go) {
            Step land = landingNode(p);
            keys = MovementInput.keysToward(land.x() + 0.5, land.z() + 0.5);
        }
        if (fallingOntoTarget(mc, target, p, cx, cz)) keys = MovementInput.Keys.NONE;
        MovementInput.setKeys(keys, canSprint);
        // Releasing the sprint key doesn't end a sprint: without this the momentum carries us off edges.
        if (!canSprint && mc.player.isSprinting()) mc.player.setSprinting(false);
        boolean jump = shouldJump(mc, target, p, horiz, inWater, canSprint);
        mc.options.keyJump.setDown(jump);
        // Sneak onto a freshly bridged block so a slightly-off step can't walk off the edge; sneaking on magma stops the burn.
        boolean wantSneak = target.move() == Move.BRIDGE || sneakMagma;
        MovementInput.sneak(wantSneak);
        // Log sneak transitions, and flag the dangerous case: we WANT to sneak (magma) but the key isn't down.
        if (wantSneak != lastWantSneak) {
            MotionDebug.event("SNEAK", (wantSneak ? "engage" : "release") + " at " + mc.player.blockPosition().toShortString()
                + (sneakMagma ? " (magma)" : "") + (target.move() == Move.BRIDGE ? " (bridge)" : ""));
            lastWantSneak = wantSneak;
        }
        if (sneakMagma && !mc.options.keyShift.isDown() && onGroundNow) {
            MotionDebug.event("MAGMA", "want sneak but keyShift UP at " + mc.player.blockPosition().toShortString());
        }
        // No-progress watchdog: moving keys held but position barely changes over ~1s while supposedly walking.
        if (onGroundNow && (keys.forward() || keys.back() || keys.left() || keys.right())) {
            if (lastProgressPos == null || p.distanceToSqr(lastProgressPos) > 0.04) {
                lastProgressPos = p; noProgressTicks = 0;
            } else if (++noProgressTicks == 20) {
                MotionDebug.event("STUCK", String.format(java.util.Locale.ROOT,
                    "no movement for 20 ticks at %.2f %.2f %.2f keys=%s target #%d %s %d %d %d yawErr %.1f",
                    p.x, p.y, p.z, (keys.forward()?"W":"")+(keys.back()?"S":"")+(keys.left()?"A":"")+(keys.right()?"D":""),
                    index, target.move(), target.x(), target.y(), target.z(), yawErr));
            }
        } else { noProgressTicks = 0; }
        MotionDebug.trace(p, mc.player.getYRot(), mc.player.getXRot(), rot[0], yawErr, keys, jump, canSprint,
            onGroundNow, inWater, target, index, sx, sz, airborne ? "walk (air)" : "walk");
        return Result.WALKING;
    }

    /**
     * Magma underfoot or on the next floor: sneak (vanilla skips the burn while stepping carefully). Not on the
     * ground before a drop, though: sneaking can't walk off a ledge, so we hold it from take-off to landing instead.
     */
    private static boolean magmaSneak(Minecraft mc, Step t, Vec3 p) {
        boolean onMagma = isMagma(mc, BlockPos.containing(p.x, p.y - 0.2, p.z));
        boolean toMagma = isMagma(mc, new BlockPos(t.x(), t.y() - 1, t.z()));
        // The 0.6-wide hitbox overhangs the block underfoot: on a diagonal/turn past a magma corner the body
        // contacts a neighbouring magma cell that neither "on" nor "to" reads. Sneak if any such cell is magma.
        boolean nearMagma = !onMagma && !toMagma && magmaAdjacent(mc, p);
        // Engage EARLY: if we're heading onto a magma node, sneak while still approaching it. Sneak must be
        // registered server-side BEFORE the body crosses the edge, or the entry tick burns (sneak engaged the
        // same tick we stepped on still took damage).
        boolean approaching = false;
        if (!onMagma && !toMagma && !nearMagma && isMagma(mc, new BlockPos(t.x(), t.y() - 1, t.z()))) {
            double dx = t.x() + 0.5 - p.x, dz = t.z() + 0.5 - p.z;
            approaching = dx * dx + dz * dz < 2.1 * 2.1; // within ~2 blocks of the magma node's centre
        }
        if (!onMagma && !toMagma && !nearMagma && !approaching) return false;
        boolean dropsOff = t.move() == Move.DESCEND || t.move() == Move.WATER_DROP || t.move() == Move.LADDER_CATCH
            || t.move() == Move.BUCKET_DROP || t.move() == Move.CUSHION_DROP || t.move() == Move.PARKOUR;
        return !(dropsOff && mc.player.onGround() && t.y() < p.y - 0.5);
    }

    /** Any magma block the current hitbox could touch: the 3x3 of floor cells around and under the body. */
    private static boolean magmaAdjacent(Minecraft mc, Vec3 p) {
        int y = (int) Math.floor(p.y - 0.2);
        var box = mc.player.getBoundingBox();
        int x0 = (int) Math.floor(box.minX), x1 = (int) Math.floor(box.maxX);
        int z0 = (int) Math.floor(box.minZ), z1 = (int) Math.floor(box.maxZ);
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++)
                if (isMagma(mc, new BlockPos(x, y, z))) return true;
        return false;
    }

    private static boolean isMagma(Minecraft mc, BlockPos pos) {
        return mc.level.getBlockState(pos).getBlock() == net.minecraft.world.level.block.Blocks.MAGMA_BLOCK;
    }

    /** Ice, packed ice, slime: sprint momentum carries us well past a turn. */
    private static boolean slippery(Minecraft mc, Step t, Vec3 p) {
        BlockPos under = BlockPos.containing(p.x, p.y - 0.2, p.z);
        return mc.level.getBlockState(under).getBlock().getFriction() > SLIPPERY_FRICTION
            || mc.level.getBlockState(new BlockPos(t.x(), t.y() - 1, t.z())).getBlock().getFriction() > SLIPPERY_FRICTION;
    }

    /** Vanilla floors are 0.6; slime 0.8, ice 0.98. */
    private static final float SLIPPERY_FRICTION = 0.65f;

    /** Take-off yaw held while airborne (null until the first grounded tick). */
    private Float heldYaw;
    /** Last steering bearing (deg), reused when the aim point is too close to give a stable one. */
    private double lastAimBearing = Double.NaN;
    /** Debug: last sneak intent we logged a transition for. */
    private boolean lastWantSneak;
    /** Debug: no-progress watchdog (position + tick counter). */
    private net.minecraft.world.phys.Vec3 lastProgressPos;
    private int noProgressTicks;
    /** Player position at the last node advance: lag-back only counts if we actually moved away from it. */
    private net.minecraft.world.phys.Vec3 lastAdvancePos;

    /** First upcoming node at or below our feet within 3: where an airborne drop will land. */
    private Step landingNode(Vec3 p) {
        for (int i = index; i < Math.min(path.size(), index + 3); i++) {
            Step s = path.get(i);
            if (s.y() < p.y - 0.2 || s.move() == Move.DESCEND || s.move() == Move.WATER_DROP) return s;
        }
        return path.get(index);
    }

    /**
     * Jump when the next rise is near in ANY direction (diagonal ascends included: the old check
     * only fired on the current node, so a diagonal step was walked into until the bump), when
     * bumping into something we're meant to go up over, or to keep swimming.
     */
    private boolean shouldJump(Minecraft mc, Step target, Vec3 p, double horiz, boolean inWater, boolean canSprint) {
        if (inWater) {
            boolean drowning = mc.player.isUnderWater() && mc.player.getAirSupply() < mc.player.getMaxAirSupply() / 3;
            if (target.move() == Move.SWIM_DOWN) return drowning;
            // Wading through shallow water on the floor needs no jump (it just bounces); swimming or climbing out does.
            boolean deep = mc.player.isUnderWater() || !onGroundNow;
            boolean exitUp = target.y() > Math.floor(p.y + 0.01) || mc.player.horizontalCollision;
            return drowning || deep || exitUp || target.move() == Move.SWIM_UP;
        }
        if (!onGroundNow) return false;
        if (unstickStage == 1) return true;
        if (mc.player.horizontalCollision && target.y() >= Math.floor(p.y + 0.01)) return true;
        int risesIn = upcomingRise(p);
        if (risesIn >= 0) {
            Step r = path.get(risesIn);
            double d = Math.hypot(r.x() + 0.5 - p.x, r.z() + 0.5 - p.z);
            // Close enough to land on top of it: a jump starts ~1.3 blocks out at walk speed, more when sprinting.
            if (d < (mc.player.isSprinting() ? 1.9 : 1.55)) return true;
        }
        return sprintJump && canSprint && mc.player.isSprinting() && straightRunAhead() >= 4;
    }

    /** Index of the first node in the next three that rises above our feet (an ascend), or -1. */
    private int upcomingRise(Vec3 p) {
        int feet = (int) Math.floor(p.y + 0.01);
        for (int i = index; i < Math.min(path.size(), index + 3); i++) {
            Step s = path.get(i);
            if (s.move() == Move.ASCEND && s.y() > feet) return i;
            if (s.y() < feet) return -1;
        }
        return -1;
    }

    private int unstickStage;

    /**
     * Stuck ladder (fast): 0.75 s still = jump; 1.25 s = back off and sidestep; 2 s = give up the
     * segment (replan). The old single 3 s timeout made every snag a long stall.
     */
    private Result unstick(Minecraft mc, Step target, Vec3 p) {
        if (stillTicks < 15) {
            unstickStage = 0;
            return null;
        }
        if (stillTicks >= 40) {
            release(mc);
            return fail(Result.STUCK, "not moving for 2s" + (mc.player.horizontalCollision ? " (walking into something)" : "")
                + " after jump + sidestep");
        }
        if (stillTicks < 25) {
            if (unstickStage < 1) {
                unstickStage = 1;
                MotionDebug.event("UNSTICK", "jump at " + mc.player.blockPosition().toShortString());
            }
            return null;
        }
        if (unstickStage < 2) {
            unstickStage = 2;
            MotionDebug.event("UNSTICK", "back off + sidestep at " + mc.player.blockPosition().toShortString());
        }
        // Step back and to one side (alternating), keeping the view: frees a shoulder caught on a corner.
        // Only back off onto solid ground: a backward step can be a ledge we just climbed.
        boolean left = (stillTicks / 5) % 2 == 0;
        boolean back = safeBehind(mc, p);
        MovementInput.setKeys(new MovementInput.Keys(false, back, left, !left), false);
        mc.options.keyJump.setDown(onGroundNow);
        return Result.WALKING;
    }

    /** The cell 1 block behind the way we're facing has a floor and no hazard. */
    private static boolean safeBehind(Minecraft mc, Vec3 p) {
        double yaw = Math.toRadians(mc.player.getYRot());
        BlockPos behind = BlockPos.containing(p.x + Math.sin(yaw), p.y + 0.01, p.z - Math.cos(yaw));
        BlockPos floor = behind.below();
        return !mc.level.getBlockState(floor).getCollisionShape(mc.level, floor).isEmpty()
            && !Hazards.isHazardous(mc, behind) && !Hazards.isContactHazard(mc.level.getBlockState(floor).getBlock());
    }

    /**
     * Sprinting is safe through flat walks and diagonals, and through a step up or down that sits in
     * a straight line (the hop before and after go the same way), as long as a sprint overshoot
     * can't carry us into a hazard or bonk a ceiling mid-jump.
     */
    private boolean sprintable(Minecraft mc, Step t) {
        Move m = t.move();
        if (m == Move.WALK || m == Move.DIAGONAL) return t.breaks().length == 0;
        if (m != Move.ASCEND && m != Move.DESCEND || index < 1 || index + 1 >= path.size()) return false;
        Step prev = path.get(index - 1), next = path.get(index + 1);
        int dx = t.x() - prev.x(), dz = t.z() - prev.z();
        if (Math.abs(dx) + Math.abs(dz) != 1 || next.x() - t.x() != dx || next.z() - t.z() != dz) return false;
        if (t.breaks().length > 0 || next.breaks().length > 0) return false;
        if (m == Move.DESCEND) {
            // The sprint carries us a block past the landing: that cell must be as safe as the landing.
            if (t.y() != prev.y() - 1) return false;
            BlockPos over = new BlockPos(t.x() + dx, t.y(), t.z() + dz);
            for (int y = 0; y <= 2; y++) {
                BlockPos c = over.above(y);
                if (Hazards.isHazardous(mc, c) || Hazards.isContactHazard(mc.level.getBlockState(c.below()).getBlock())) return false;
            }
            return true;
        }
        // Ascend: a sprint-jump rises higher and earlier, so both columns need a clear third block of headroom.
        BlockPos a = new BlockPos(prev.x(), prev.y() + 2, prev.z()), b = new BlockPos(t.x(), t.y() + 2, t.z());
        return mc.level.getBlockState(a).getCollisionShape(mc.level, a).isEmpty()
            && mc.level.getBlockState(b).getCollisionShape(mc.level, b).isEmpty()
            && !Hazards.isHazardous(mc, b);
    }

    /** Estimated ticks for this hop (planner cost back in ticks) plus slack; long breaks have their own timeout. */
    private static int nodeTimeout(Step t) {
        double est = Math.max(1.0, t.cost()) * com.autism.seedcracker.motion.pure.GridPathfinder.WALK_TICKS_PER_BLOCK;
        return (int) Math.min(20 * 60, est + NODE_TIMEOUT_SLACK_TICKS);
    }

    /** Mid-air over the landing column with momentum already centring us: stop pushing (1x1 water holes). */
    private static boolean fallingOntoTarget(Minecraft mc, Step t, Vec3 p, double cx, double cz) {
        if (mc.player.onGround() || t.move() != Move.WATER_DROP && t.move() != Move.DESCEND
            && t.move() != Move.BUCKET_DROP && t.move() != Move.CUSHION_DROP && t.move() != Move.LADDER_CATCH) return false;
        if (Math.floor(p.x) != t.x() || Math.floor(p.z) != t.z()) return false;
        Vec3 v = mc.player.getDeltaMovement();
        return Math.abs(p.x + v.x - cx) < 0.15 && Math.abs(p.z + v.z - cz) < 0.15;
    }

    /**
     * Re-verifies the next few nodes every tick: lava that spread or a block someone placed in the
     * way means replanning now, not walking up to it first.
     */
    private boolean aheadChanged(Minecraft mc) {
        // The current target too: a block placed right in front of us must replan now, not after STUCK fires.
        for (int i = Math.max(1, index); i < Math.min(path.size(), index + LOOKAHEAD); i++) {
            Step s = path.get(i);
            String at = " at " + s.x() + " " + s.y() + " " + s.z();
            if (i > index && nodeNowDangerous(mc, s)) {
                why = "hazard appeared ahead" + at;
                return true;
            }
            // Mid-hop the player's own body is in the cells being checked; only re-price hops we haven't started.
            if (i == index && nodeTicks > 0) continue;
            if (s.breaks().length > 0 || s.move() == Move.OPEN_DOOR
                || s.place() != com.autism.seedcracker.motion.pure.GridPathfinder.NO_PLACE) continue;
            BlockPos feet = new BlockPos(s.x(), s.y(), s.z());
            if (mc.level.getBlockState(feet).isCollisionShapeFullBlock(mc.level, feet)
                || mc.level.getBlockState(feet.above()).isCollisionShapeFullBlock(mc.level, feet.above())) {
                why = "block now in the way" + at;
                return true;
            }
            // Re-price the hop: a move that's now impossible or much dearer than planned means replan before we reach it.
            if (world != null && config != null) {
                double now = com.autism.seedcracker.motion.pure.GridPathfinder.hopCost(world, config, path.get(i - 1), s);
                if (now - s.cost() > MAX_COST_INCREASE) {
                    why = (Double.isInfinite(now) ? "hop no longer possible " : "hop got dearer ") + s.move() + at;
                    return true;
                }
            }
        }
        return false;
    }

    /** Tower up: jump, and at the top of the jump place a throwaway block where our feet were. */
    private Result pillar(Minecraft mc, Step target, Vec3 p) {
        BlockPos below = BlockPos.of(target.place());
        if (!mc.level.getBlockState(below).canBeReplaced()) {
            // Block is in: wait to land on it (reachedByOvershoot advances the node). Bounded: a block that went in
            // beside us (lag, knockback) used to leave the bot holding still forever.
            holdStill(mc);
            boolean overColumn = Math.floor(p.x) == target.x() && Math.floor(p.z) == target.z();
            if (mc.player.onGround() && !overColumn) {
                release(mc);
                return fail(Result.OFF_PATH, "pillar: landed beside the tower");
            }
            if (++placeTicks > PLACE_TIMEOUT_TICKS * 2) {
                release(mc);
                return fail(Result.STUCK, "pillar: never landed on the placed block");
            }
            return Result.WALKING;
        }
        if (BridgeBlocks.slot(mc) < 0 || ++placeTicks > PLACE_TIMEOUT_TICKS) {
            release(mc);
            placeRefused = BridgeBlocks.slot(mc) >= 0;
            return fail(Result.BLOCKED, BridgeBlocks.slot(mc) < 0 ? "pillar: no blocks" : "pillar: place timed out");
        }
        MovementInput.release();
        MovementInput.sneak(false);
        RotationEngine.request(ROT_OWNER, RotationEngine.PRIORITY_INTERACT, mc.player.getYRot(), 90f);
        mc.options.keyJump.setDown(mc.player.onGround());
        // Place only once our feet have cleared the cell, or the block would push us around.
        if (p.y >= below.getY() + 1.0) BridgeBlocks.place(mc, below, ROT_OWNER);
        lastPos = null;
        return Result.WALKING;
    }

    /**
     * Water-bucket clutch: walk off as normal; once falling close above the landing, look down and
     * place water on the landing block, then pick it back up after we've landed in it.
     */
    private Result clutch(Minecraft mc, Step target, Vec3 p) {
        if (mc.gameMode == null) return null;
        BlockPos landing = new BlockPos(target.x(), target.y() - 1, target.z());
        boolean overColumn = Math.floor(p.x) == target.x() && Math.floor(p.z) == target.z();
        if (clutchState == 0) {
            if (mc.player.onGround() || !overColumn) return null;
            int slot = hotbarSlot(mc, net.minecraft.world.item.Items.WATER_BUCKET);
            if (slot < 0) {
                release(mc);
                return fail(Result.DANGER, "clutch: water bucket gone");
            }
            // Bucket in hand and eyes down from the moment we leave the edge: both cost a tick we won't have later.
            if (mc.player.getInventory().getSelectedSlot() != slot) com.autism.seedcracker.util.InvSync.select(mc, slot);
            boolean aimed = RotationEngine.request(ROT_OWNER, RotationEngine.PRIORITY_SAFETY, mc.player.getYRot(), 90f);
            MovementInput.release();
            double above = p.y - (landing.getY() + 1);
            // Late falls cover ~3+ blocks a tick: fire when the NEXT tick would put us within reach of the floor, not when we already are.
            double nextDrop = -mc.player.getDeltaMovement().y;
            boolean inWindow = above - Math.max(0, nextDrop) <= CLUTCH_HEIGHT;
            if (!inWindow || !aimed || mc.player.getInventory().getSelectedSlot() != slot) return Result.WALKING;
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            mc.player.swing(InteractionHand.MAIN_HAND);
            clutchState = 1;
            return Result.WALKING;
        }
        if (clutchState == 1) {
            if (!mc.player.onGround() && !mc.player.isInWater()) return Result.WALKING;
            int slot = hotbarSlot(mc, net.minecraft.world.item.Items.BUCKET);
            BlockPos water = landing.above();
            if (slot < 0 || mc.level.getBlockState(water).getFluidState().isEmpty()) {
                clutchState = 2;
                return Result.WALKING;
            }
            if (mc.player.getInventory().getSelectedSlot() != slot) {
                com.autism.seedcracker.util.InvSync.select(mc, slot);
                return Result.WALKING;
            }
            if (!RotationEngine.lookAt(ROT_OWNER, RotationEngine.PRIORITY_INTERACT, Vec3.atCenterOf(water))) return Result.WALKING;
            if (!com.autism.seedcracker.util.ActionPacer.tryAction()) return Result.WALKING;
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            clutchState = 2;
            return Result.WALKING;
        }
        return null;
    }

    /**
     * Cushion-block clutch (hay bale / slime / cobweb / powder snow): walk off as normal; once falling close
     * above the landing floor, look down and place the cushion block on it, then land on it (negates the fall
     * damage). Unlike the water bucket, the block stays placed (no scoop).
     */
    private Result cushionClutch(Minecraft mc, Step target, Vec3 p) {
        if (mc.gameMode == null) return null;
        BlockPos landing = new BlockPos(target.x(), target.y() - 1, target.z());
        boolean overColumn = Math.floor(p.x) == target.x() && Math.floor(p.z) == target.z();
        if (clutchState == 0) {
            if (mc.player.onGround() || !overColumn) return null;
            int slot = cushionSlot(mc);
            if (slot < 0) {
                release(mc);
                return fail(Result.DANGER, "cushion: no hay/slime/cobweb/powder-snow block");
            }
            if (mc.player.getInventory().getSelectedSlot() != slot) com.autism.seedcracker.util.InvSync.select(mc, slot);
            boolean aimed = RotationEngine.request(ROT_OWNER, RotationEngine.PRIORITY_SAFETY, mc.player.getYRot(), 90f);
            MovementInput.release();
            double above = p.y - (landing.getY() + 1);
            double nextDrop = -mc.player.getDeltaMovement().y;
            // A solid cushion block must be placed ON the landing floor before impact, so fire closer than the
            // water clutch (which flows down): within reach (~4.3 blocks) and only ~1.5 blocks of descent left.
            boolean inWindow = above <= 4.3 && above - Math.max(0, nextDrop) <= 1.5;
            if (!inWindow || !aimed || mc.player.getInventory().getSelectedSlot() != slot) return Result.WALKING;
            // Place on the top face of the landing floor so the cushion appears where we'll land.
            BlockPos placeAt = landing;
            Vec3 hitPos = Vec3.atCenterOf(placeAt).add(0, 0.5, 0);
            var hit = new net.minecraft.world.phys.BlockHitResult(hitPos, net.minecraft.core.Direction.UP, placeAt, false);
            InteractionResult r = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            if (r.consumesAction()) mc.player.swing(InteractionHand.MAIN_HAND);
            clutchState = 1;
            return Result.WALKING;
        }
        if (clutchState == 1) {
            if (!mc.player.onGround()) return Result.WALKING;
            clutchState = 2;
            return Result.WALKING;
        }
        return null;
    }

    /** First hotbar slot holding a fall-damage-cushion block, or -1. */
    private static int cushionSlot(Minecraft mc) {
        net.minecraft.world.item.Item[] items = {
            net.minecraft.world.item.Items.HAY_BLOCK, net.minecraft.world.item.Items.SLIME_BLOCK,
            net.minecraft.world.item.Items.COBWEB, net.minecraft.world.item.Items.POWDER_SNOW_BUCKET };
        for (int i = 0; i < 9; i++) {
            var it = mc.player.getInventory().getItem(i).getItem();
            for (var c : items) if (it == c) return i;
        }
        return -1;
    }

    /** A clutch-drop node isn't done until the placement is made and we've landed. */
    private boolean clutchDone(Minecraft mc, Step t) {
        if (t.move() == Move.BUCKET_DROP) return clutchState != 1;
        if (t.move() == Move.CUSHION_DROP) return clutchState != 1;
        return true;
    }

    private static int hotbarSlot(Minecraft mc, net.minecraft.world.item.Item item) {
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getItem(i).getItem() == item) return i;
        return -1;
    }

    private void advanceTo(Minecraft mc, int newIndex, String how) {
        for (int i = index; i < Math.min(newIndex, path.size()); i++) MotionDebug.nodePassed(path.get(i), how, mc.player.position());
        index = newIndex;
        lastAdvancePos = mc.player.position();
        stillTicks = 0;
        placeTicks = 0;
        nodeTicks = 0;
        clutchState = 0;
        stopBreaking(mc);
        // Don't drop sneak here if we're on/next to magma: the release + the next tick's re-engage leaves a
        // one-tick window where the server sees us un-sneaking on magma (the corner-clip burn). The per-tick
        // magmaSneak logic owns sneak around magma; only clear it elsewhere.
        if (!magmaAdjacent(mc, mc.player.position()) && !isMagma(mc, mc.player.blockPosition().below()))
            MovementInput.sneak(false);
    }

    /**
     * Index after the last node we've already gone past along a run of plain walk/swim steps, or
     * {@link #index} if none. Uses the projection onto the path line, so passing a node 0.6 off its
     * centre still counts (the old centre-only check made the bot turn back for it = the spins).
     */
    private int passedThrough(Minecraft mc, Vec3 p, boolean inWater) {
        if (index >= path.size() || !flowing(path.get(index))) return index;
        int end = index;
        while (end + 1 < path.size() && end - index < 8 && flowing(path.get(end + 1))) end++;
        var pr = com.autism.seedcracker.motion.pure.PathGeometry.project(path, index, end, p.x, p.y, p.z, inWater ? 1.5 : 1.0);
        if (pr == null || pr.distSq() > 1.0) return index;
        // On the segment ending at node k, every node before k is behind us.
        int behind = pr.seg() - 1;
        if (pr.t() > 0.95 || pr.seg() == end && pr.t() > 0.6) behind = pr.seg();
        return Math.max(index, Math.min(behind + 1, end));
    }

    /** Plain moves the follower can pass through without arriving exactly (no break/place/jump/fall/door). */
    private static boolean flowing(Step s) {
        Move m = s.move();
        return (m == Move.WALK || m == Move.DIAGONAL || m == Move.SWIM_UP || m == Move.SWIM_DOWN) && s.breaks().length == 0
            && s.place() == com.autism.seedcracker.motion.pure.GridPathfinder.NO_PLACE;
    }

    private double distanceToLine(Vec3 p) {
        int from = Math.max(1, index), to = Math.min(path.size() - 1, index + 3);
        var pr = com.autism.seedcracker.motion.pure.PathGeometry.project(path, from, to, p.x, p.y, p.z, 3.0);
        return pr == null ? Double.MAX_VALUE : Math.sqrt(pr.distSq());
    }

    /**
     * Point to steer at: slides {@link #CARROT_AHEAD} blocks along the path from where we are, stopping
     * at the first node that needs an exact arrival (jump, ladder, door, break). Never closer than
     * {@link #MIN_AIM_DIST}, so the bearing can't swing wildly when we're right on a node.
     */
    private double[] steerPoint(Mc ctx) {
        Vec3 p = ctx.p;
        int stop = index;
        while (stop < path.size() && flowing(path.get(stop)) && stop - index < 10) stop++;
        stop = Math.min(stop, path.size() - 1);
        var pr = com.autism.seedcracker.motion.pure.PathGeometry.project(path, index, Math.max(index, stop), p.x, p.y, p.z, 1.5);
        double[] c;
        if (pr == null) {
            Step t = path.get(index);
            c = new double[]{t.x() + 0.5, t.z() + 0.5, index};
        } else {
            c = com.autism.seedcracker.motion.pure.PathGeometry.carrot(path, pr, CARROT_AHEAD, stop);
            // The swept body must fit along the line to the carrot, or fall back to the node itself.
            Step at = path.get((int) c[2]);
            if ((int) c[2] > index && !lineIsSafe(ctx.mc, p, new Step((int) Math.floor(c[0]), at.y(), (int) Math.floor(c[1]), at.move(), at.breaks()))) {
                Step t = path.get(index);
                c = new double[]{t.x() + 0.5, t.z() + 0.5, index};
            }
        }
        double dx = c[0] - p.x, dz = c[1] - p.z, d = Math.hypot(dx, dz);
        if (d < MIN_AIM_DIST && (int) c[2] < path.size() - 1) {
            // Too close for a stable bearing (a few cm of drift = a 180 flip): keep the last one instead.
            double bearing = d > 0.3 || Double.isNaN(lastAimBearing) ? Math.atan2(dz, dx) : lastAimBearing;
            c[0] = p.x + Math.cos(bearing) * MIN_AIM_DIST;
            c[1] = p.z + Math.sin(bearing) * MIN_AIM_DIST;
            lastAimBearing = bearing;
        } else if (d > 1e-6) {
            lastAimBearing = Math.atan2(dz, dx);
        }
        return c;
    }

    private record Mc(Minecraft mc, Vec3 p) {}

    /**
     * The real 0.6x1.8 body swept to {@code s} touches no collision box (fence posts, lanterns and
     * slabs only count where they actually are), and every cell under its footprint is floored and
     * hazard-free.
     */
    private static boolean lineIsSafe(Minecraft mc, Vec3 from, Step s) {
        double tx = s.x() + 0.5, tz = s.z() + 0.5;
        double len = Math.hypot(tx - from.x, tz - from.z);
        int samples = Math.max(1, (int) Math.ceil(len / 0.25));
        double y = s.y();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int k = 1; k <= samples; k++) {
            double t = k / (double) samples;
            double cx = from.x + (tx - from.x) * t, cz = from.z + (tz - from.z) * t;
            // Above carpet/snow-layer height so a flat floor detail doesn't count as a wall.
            var body = new net.minecraft.world.phys.AABB(cx - BODY_CLEARANCE, y + 0.1, cz - BODY_CLEARANCE,
                cx + BODY_CLEARANCE, y + 1.8, cz + BODY_CLEARANCE);
            if (mc.level.getBlockCollisions(mc.player, body).iterator().hasNext()) return false;
            for (int bx = (int) Math.floor(cx - 0.3); bx <= (int) Math.floor(cx + 0.3); bx++) {
                for (int bz = (int) Math.floor(cz - 0.3); bz <= (int) Math.floor(cz + 0.3); bz++) {
                    m.set(bx, s.y(), bz);
                    if (Hazards.isHazardous(mc, m) || Hazards.isHazardous(mc, m.above())) return false;
                    m.set(bx, s.y() - 1, bz);
                    var fs = mc.level.getBlockState(m);
                    if (fs.getCollisionShape(mc.level, m).isEmpty()
                        || Hazards.isContactHazard(fs.getBlock()) && fs.getBlock() != net.minecraft.world.level.block.Blocks.MAGMA_BLOCK) return false;
                }
            }
        }
        return true;
    }

    /** Plain same-level walk with nothing to break or place: safe to leave early or aim past. */
    private static boolean cuttable(Step s) {
        return (s.move() == Move.WALK || s.move() == Move.DIAGONAL) && s.breaks().length == 0
            && s.place() == com.autism.seedcracker.motion.pure.GridPathfinder.NO_PLACE;
    }

    /** Near a corner node with a clear swept line to the one after it: turn now instead of centring first. */
    private boolean canCutCorner(Minecraft mc, Step target, Vec3 p, double horiz) {
        if (horiz > CORNER_CUT_RADIUS || !onGroundNow || index + 1 >= path.size()) return false;
        Step next = path.get(index + 1);
        return cuttable(target) && cuttable(next) && next.y() == target.y() && Math.abs(p.y - target.y()) < 0.5
            && lineIsSafe(mc, p, next);
    }

    /** How many plain flat WALK steps follow in the same direction (sprint-jumping only on long straights). */
    private int straightRunAhead() {
        if (index < 1 || index >= path.size()) return 0;
        Step a = path.get(index - 1), b = path.get(index);
        int dx = b.x() - a.x(), dz = b.z() - a.z(), run = 0;
        for (int i = index; i < path.size(); i++) {
            Step s = path.get(i), prev = path.get(i - 1);
            if (s.move() != Move.WALK || s.y() != b.y() || s.x() - prev.x() != dx || s.z() - prev.z() != dz) break;
            run++;
        }
        return run;
    }

    /** Hold forward into the ladder; jump starts the climb up, sneak-free release slides down. */
    private Result climb(Minecraft mc, Step target, Vec3 p, double horiz) {
        boolean up = target.move() == Move.CLIMB_UP;
        BlockPos feet = BlockPos.containing(p.x, p.y + 0.01, p.z);
        boolean scaffold = isScaffolding(mc, feet) || isScaffolding(mc, feet.below());
        // Walking into a climbable is what makes you climb; drift onto its column first. Scaffolding has no wall to push.
        MovementInput.setKeys(horiz > 0.15 ? MovementInput.keysToward(target.x() + 0.5, target.z() + 0.5)
            : up && !scaffold ? new MovementInput.Keys(true, false, false, false) : MovementInput.Keys.NONE, false);
        // Vanilla climbs while jumping OR pushing into the climbable; only jumping works inside scaffolding and vines.
        mc.options.keyJump.setDown(up && (mc.player.onGround() || mc.player.onClimbable()));
        // Scaffolding is the one climbable you only go down by sneaking; on a ladder sneaking would hold us in place.
        MovementInput.sneak(!up && scaffold);
        return Result.WALKING;
    }

    private static boolean isScaffolding(Minecraft mc, BlockPos pos) {
        return mc.level.getBlockState(pos).getBlock() == net.minecraft.world.level.block.Blocks.SCAFFOLDING;
    }

    /** Door/gate on this node: click it open (once), then walk through. */
    private Result pendingDoor(Minecraft mc, Step target) {
        if (target.move() != Move.OPEN_DOOR || mc.gameMode == null) return null;
        BlockPos pos = new BlockPos(target.x(), target.y(), target.z());
        BlockState s = mc.level.getBlockState(pos);
        boolean closed = s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)
            && !s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN);
        if (!closed) return null;
        if (index > 0) {
            Step from = path.get(index - 1);
            int dx = target.x() - from.x(), dz = target.z() - from.z();
            var panel = LevelWalkability.edgePanel(s.getCollisionShape(mc.level, pos));
            // Walking along a closed door (not through it) needs no click; opening would swing it across our way.
            if (panel == com.autism.seedcracker.motion.pure.GridPathfinder.Kind.PANEL_X && dx == 0
                || panel == com.autism.seedcracker.motion.pure.GridPathfinder.Kind.PANEL_Z && dz == 0) return null;
        }
        if (mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > 4.0) return null;
        holdStill(mc);
        if (++placeTicks > PLACE_TIMEOUT_TICKS) {
            release(mc);
            return fail(Result.BLOCKED, "door wouldn't open");
        }
        Vec3 hit = Vec3.atCenterOf(pos);
        if (!RotationEngine.lookAt(ROT_OWNER, RotationEngine.PRIORITY_INTERACT, hit)) return Result.WALKING;
        if (!com.autism.seedcracker.util.ActionPacer.tryAction()) return Result.WALKING;
        Direction face = Direction.getApproximateNearest(
            mc.player.getEyePosition().x - hit.x, 0, mc.player.getEyePosition().z - hit.z);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
            new net.minecraft.world.phys.BlockHitResult(hit, face, pos, false));
        mc.player.swing(InteractionHand.MAIN_HAND);
        lastPos = null;
        return Result.WALKING;
    }

    /** The water we planned to land in must still be there (it can drain or freeze). */
    private static boolean landingStillWater(Minecraft mc, Step t) {
        return !mc.level.getBlockState(new BlockPos(t.x(), t.y(), t.z())).getFluidState().isEmpty();
    }

    /**
     * Sprint-jump a gap: only commit when facing straight down the jump line and at speed; jump
     * at the lip of the take-off block. A misaligned approach stops instead of leaping blind.
     */
    private Result parkour(Minecraft mc, Step target, Vec3 p, double horiz, float yawErr) {
        Step from = path.get(index - 1);
        // Dropped below the take-off level: the jump failed, we're in the gap.
        if (p.y < from.y() - 0.5) {
            release(mc);
            return fail(Result.OFF_PATH, "parkour jump fell short");
        }
        boolean onTakeoff = Math.floor(p.x) == from.x() && Math.floor(p.z) == from.z();
        if (yawErr > 8f || mc.player.getFoodData().getFoodLevel() <= 6) {
            MovementInput.release();
            mc.options.keyJump.setDown(false);
            return mc.player.onGround() && onTakeoff ? Result.WALKING : fail(Result.OFF_PATH, "parkour approach misaligned");
        }
        MovementInput.setKeys(MovementInput.keysToward(target.x() + 0.5, target.z() + 0.5), true);
        // Distance from the block centre toward the gap: jump in the last ~0.3 of the take-off block.
        double along = Math.abs((p.x - (from.x() + 0.5)) * Integer.signum(target.x() - from.x())
            + (p.z - (from.z() + 0.5)) * Integer.signum(target.z() - from.z()));
        // Waiting for the sprint flag must not run us off the lip: past 0.55 (the hitbox is about to leave the block) jump anyway.
        mc.options.keyJump.setDown(onTakeoff && mc.player.onGround() && along > 0.2 && (mc.player.isSprinting() || along > 0.55));
        MovementInput.sneak(false);
        return Result.WALKING;
    }

    /** Places the node's bridge block; null = nothing to place, WALKING = still placing. */
    private Result pendingPlace(Minecraft mc, Step target) {
        if (target.place() == com.autism.seedcracker.motion.pure.GridPathfinder.NO_PLACE) return null;
        BlockPos floor = BlockPos.of(target.place());
        if (!mc.level.getBlockState(floor).canBeReplaced()) return null;
        if (BridgeBlocks.slot(mc) < 0) {
            release(mc);
            return fail(Result.BLOCKED, "bridge: no blocks");
        }
        if (++placeTicks > PLACE_TIMEOUT_TICKS) {
            release(mc);
            placeRefused = true;
            return fail(Result.BLOCKED, "bridge: place timed out");
        }
        // Stand back from the edge (sneak) while placing so we never step into the hole.
        MovementInput.release();
        mc.options.keyJump.setDown(false);
        MovementInput.sneak(true);
        BridgeBlocks.place(mc, floor, ROT_OWNER);
        lastPos = null;
        return Result.WALKING;
    }

    /** Lava/fire flowed into (or under) the next node since we planned. */
    private static boolean nodeNowDangerous(Minecraft mc, Step t) {
        BlockPos feet = new BlockPos(t.x(), t.y(), t.z());
        var floor = mc.level.getBlockState(feet.below()).getBlock();
        // Magma floors are planned on purpose (crossed sneaking), not a surprise hazard.
        return Hazards.isLava(mc, feet) || Hazards.isLava(mc, feet.above()) || Hazards.isLava(mc, feet.below())
            || Hazards.isContactHazard(mc.level.getBlockState(feet).getBlock())
            || floor != net.minecraft.world.level.block.Blocks.MAGMA_BLOCK && Hazards.isContactHazard(floor);
    }

    /** A WALK node whose floor vanished since planning (mined out, fell): walking on means an unplanned drop. */
    private static boolean edgeWithoutPlan(Minecraft mc, Step t) {
        if (t.move() != Move.WALK && t.move() != Move.DIAGONAL) return false;
        BlockPos floor = new BlockPos(t.x(), t.y() - 1, t.z());
        BlockState s = mc.level.getBlockState(floor);
        // Ladder / scaffolding tops hold us up without a context-free collision box.
        if (s.is(net.minecraft.tags.BlockTags.CLIMBABLE) || mc.level.getBlockState(floor.above()).is(net.minecraft.tags.BlockTags.CLIMBABLE)) {
            return false;
        }
        return s.getCollisionShape(mc.level, floor).isEmpty() && s.getFluidState().isEmpty()
            && mc.level.getBlockState(floor.below()).getCollisionShape(mc.level, floor.below()).isEmpty();
    }

    private boolean reachedByOvershoot(Vec3 p, Step t) {
        boolean column = Math.floor(p.x) == t.x() && Math.floor(p.z) == t.z();
        if (t.move() == Move.WATER_DROP) return column && p.y <= t.y() + 1.0;
        if (t.move() == Move.CLIMB_UP) return column && p.y >= t.y() - 0.1;
        // A pillar hop is only done once its block exists and we're standing on it, not at the top of the jump.
        if (t.move() == Move.PILLAR) return column && Math.abs(p.y - t.y()) < 0.1 && onGroundNow;
        if (t.move() == Move.LADDER_CATCH) return column && p.y <= t.y() + 0.5;
        if (t.move() == Move.CLIMB_DOWN) return column && p.y <= t.y() + 0.2;
        // Landed on top of the step: done, no need to walk to its centre first (stairs went hop, centre, hop).
        if (t.move() == Move.ASCEND && t.breaks().length == 0) return column && onGroundNow && Math.abs(p.y - t.y()) < 0.1;
        return (t.move() == Move.DESCEND || t.move() == Move.DIG_DOWN) && column && Math.abs(p.y - t.y()) < 0.6;
    }

    /** Mines the node's queued blocks. null = nothing left to break; WALKING = still mining. */
    private Result pendingBreak(Minecraft mc, Step target) {
        if (mc.gameMode == null) return null;
        for (long packed : target.breaks()) {
            BlockPos b = BlockPos.of(packed);
            BlockState state = mc.level.getBlockState(b);
            if (state.getCollisionShape(mc.level, b).isEmpty()) continue;
            // Re-check: the block may have changed (someone placed obsidian, a chest appeared, lava flowed in behind it).
            float hardness = state.getDestroySpeed(mc.level, b);
            if (hardness < 0 || state.hasBlockEntity() || touchesLiquid(mc, b)) {
                release(mc);
                return fail(Result.BLOCKED, "can't break " + state.getBlock().getName().getString() + " at " + b.toShortString()
                    + (touchesLiquid(mc, b) ? " (liquid next to it)" : ""));
            }
            // Gravel above a FALLING block (the planner priced the whole stack) is mined through; above anything else it's a cave-in.
            if (!Hazards.isFalling(state) && Hazards.isFalling(mc.level.getBlockState(b.above()))) {
                release(mc);
                return fail(Result.BLOCKED, "gravel/sand would fall in at " + b.toShortString());
            }
            // Wait for falling gravel to settle before swinging at the next block of the stack.
            if (!mc.level.getEntitiesOfClass(net.minecraft.world.entity.item.FallingBlockEntity.class,
                    new net.minecraft.world.phys.AABB(b).inflate(0.5, 2, 0.5)).isEmpty()) {
                holdStill(mc);
                return Result.WALKING;
            }
            holdStill(mc);
            if (!RotationEngine.lookAt(ROT_OWNER, RotationEngine.PRIORITY_INTERACT, Vec3.atCenterOf(b))) {
                return Result.WALKING;
            }
            if (!b.equals(breaking)) {
                stopBreaking(mc);
                // Swap first and give the carried-item packet a tick to land before the first hit.
                if (swapWait == 0 && ToolPicker.equip(mc, state)) {
                    swapWait = 1;
                    return Result.WALKING;
                }
                swapWait = 0;
                breaking = b;
                breakTicks = 0;
                mc.gameMode.startDestroyBlock(b, faceToward(mc, b));
            } else {
                if (++breakTicks > BREAK_TIMEOUT_TICKS) {
                    release(mc);
                    refusedBreak = com.autism.seedcracker.motion.pure.GridPathfinder.pack(b.getX(), b.getY(), b.getZ());
                    return fail(Result.BLOCKED, "break took >12s at " + b.toShortString() + " (server not accepting?)");
                }
                mc.gameMode.continueDestroyBlock(b, faceToward(mc, b));
            }
            mc.player.swing(InteractionHand.MAIN_HAND);
            lastPos = null;
            return Result.WALKING;
        }
        stopBreaking(mc);
        return null;
    }

    /** Fluid on a side or above would pour into the gap (the planner checks this too, but the world moves). */
    private static boolean touchesLiquid(Minecraft mc, BlockPos b) {
        for (Direction d : Direction.values()) {
            if (d == Direction.DOWN) continue;
            if (!mc.level.getBlockState(b.relative(d)).getFluidState().isEmpty()) return true;
        }
        return false;
    }

    private void stopBreaking(Minecraft mc) {
        if (breaking != null && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        breaking = null;
        breakTicks = 0;
    }

    private static void holdStill(Minecraft mc) {
        MovementInput.release();
        mc.options.keyJump.setDown(false);
        MovementInput.sneak(false);
    }

    private static Direction faceToward(Minecraft mc, BlockPos b) {
        Vec3 d = mc.player.getEyePosition().subtract(Vec3.atCenterOf(b));
        return Direction.getApproximateNearest(d.x, d.y, d.z);
    }

    void release(Minecraft mc) {
        if (mc.options != null) holdStill(mc);
        stopBreaking(mc);
        RotationEngine.release(ROT_OWNER);
    }
}
