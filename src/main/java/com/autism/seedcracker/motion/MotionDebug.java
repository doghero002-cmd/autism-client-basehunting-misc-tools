package com.autism.seedcracker.motion;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.autism.seedcracker.motion.pure.GridPathfinder;
import com.autism.seedcracker.motion.pure.GridPathfinder.Move;
import com.autism.seedcracker.motion.pure.GridPathfinder.Step;
import com.autism.seedcracker.render.BlockEspRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;

/**
 * Debug view for the movement engine: what it's doing, every plan's stats, and why each failure
 * happened. Feeds a HUD panel, colour-coded path markers, and two files in the client folder:
 * {@code motion-errors.log} (problems only, each with the 3 s lead-up) and {@code motion-trace.log}
 * (every tick: position, angles, keys, jumps, nodes passed).
 */
public final class MotionDebug {
    private MotionDebug() {}

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int RECENT = 8;
    private static final String ESP_PREFIX = "motion-debug-";

    private static volatile boolean enabled;
    private static boolean markers = true;
    private static boolean logFile = true;
    private static final ArrayDeque<String> recent = new ArrayDeque<>();
    /** motion-errors.log: failures, unstick steps and anomalies only, each with the lead-up. */
    private static final Log ERRORS = new Log("motion-errors.log", true);
    /** motion-trace.log: every tick (position, angles, keys, jump, target) plus every event. */
    private static final Log TRACE = new Log("motion-trace.log", false);
    /** Last 3 s of per-tick lines, dumped into the error log when something goes wrong. */
    private static final ArrayDeque<String> history = new ArrayDeque<>();
    private static final int HISTORY_TICKS = 60;
    private static final com.autism.seedcracker.motion.pure.MotionAnomalies ANOMALIES = new com.autism.seedcracker.motion.pure.MotionAnomalies();
    private static final java.util.Set<String> ERROR_CATEGORIES = java.util.Set.of("FAIL", "UNSTICK", "ANOMALY", "LAVA", "TOWER", "MAGMA", "STUCK");

    /** Append-only log file in the client folder, opened on first write; never throws. */
    private static final class Log {
        final String name;
        /** Flush every line (errors: must survive a crash) or at most once a second (trace: 20 lines/s). */
        final boolean flushEach;
        BufferedWriter writer;
        boolean failed;
        Path file;
        long lastFlushMs;

        Log(String name, boolean flushEach) {
            this.name = name;
            this.flushEach = flushEach;
        }

        void write(String line) {
            open();
            if (writer == null) return;
            try {
                writer.write(line);
                writer.newLine();
                long now = System.currentTimeMillis();
                if (flushEach || now - lastFlushMs >= 1000) {
                    writer.flush();
                    lastFlushMs = now;
                }
            } catch (Exception e) {
                failed = true;
                writer = null;
            }
        }

        void flush() {
            try {
                if (writer != null) writer.flush();
            } catch (Exception ignored) {
            }
        }

        void open() {
            if (writer != null || failed) return;
            try {
                Path dir;
                try {
                    dir = autismclient.AutismClientAddon.FOLDER.toPath();
                } catch (Throwable t) {
                    dir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("autism");
                }
                Files.createDirectories(dir);
                file = dir.resolve(name);
                writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                writer.write("---- session " + java.time.LocalDateTime.now() + " ----");
                writer.newLine();
            } catch (Exception e) {
                failed = true;
                writer = null;
            }
        }
    }

    // Last plan, for the HUD.
    private static String lastPlan = "-";
    private static long planStartNs;
    private static int plans, fails, strikes;

    private static volatile boolean gotoOn;
    /** Game time until which another bot (tunnel finder) has asked for logging; lapses if it stops asking. */
    private static volatile long botUntil;

    public static void configure(boolean on, boolean showMarkers, boolean toFile) {
        if (gotoOn && !on) clearMarkers();
        gotoOn = on;
        markers = showMarkers;
        logFile = toFile;
        if (!markers) clearMarkers();
        refresh();
    }

    /** Another bot's own debug toggle: keeps both logs on while it's set (call every tick). */
    public static void requestFromBot(boolean on) {
        Minecraft mc = Minecraft.getInstance();
        if (on && mc.level != null) {
            botUntil = mc.level.getGameTime() + 40;
            if (!gotoOn) logFile = true;
        }
        refresh();
    }

    private static void refresh() {
        Minecraft mc = Minecraft.getInstance();
        boolean bot = mc.level != null && mc.level.getGameTime() <= botUntil;
        enabled = gotoOn || bot;
    }

    public static boolean enabled() {
        return enabled;
    }

    /** Folder holding motion-errors.log and motion-trace.log. */
    public static synchronized Path logPath() {
        ERRORS.open();
        return ERRORS.file == null ? null : ERRORS.file.getParent();
    }

    /**
     * One timestamped event line. Everything goes to the trace log and the HUD; failures, unstick steps
     * and anomalies also go to the error log together with the last 3 s of per-tick history.
     */
    public static synchronized void event(String category, String text) {
        if (!enabled) return;
        String line = LocalTime.now().format(TS) + " " + category + " " + text;
        recent.addLast(line);
        while (recent.size() > RECENT) recent.removeFirst();
        if (!logFile) return;
        TRACE.write(line);
        if (ERROR_CATEGORIES.contains(category)) {
            TRACE.flush();
            ERRORS.write(line);
            if (!category.equals("UNSTICK") && !history.isEmpty()) {
                ERRORS.write("    last " + history.size() + " ticks (pos | yaw->aim err | keys | flags | node):");
                for (String h : history) ERRORS.write("    " + h);
            }
        }
    }

    /**
     * Per-tick state from the follower: written to the trace log, kept as error-log history, and
     * checked for flicks, spinning and twitchy keys.
     */
    static void trace(net.minecraft.world.phys.Vec3 p, float yaw, float pitch, float aimYaw, float yawErr,
                      com.autism.seedcracker.util.tunnel.MovementInput.Keys keys, boolean jump, boolean sprint,
                      boolean ground, boolean water, Step target, int index, double aimX, double aimZ, String doing) {
        if (!enabled) return;
        Minecraft mci = Minecraft.getInstance();
        boolean sneak = mci.options != null && mci.options.keyShift.isDown();
        boolean onMagma = mci.level != null && mci.player != null
            && mci.level.getBlockState(net.minecraft.core.BlockPos.containing(p.x, p.y - 0.2, p.z)).getBlock()
                == net.minecraft.world.level.block.Blocks.MAGMA_BLOCK;
        String k = (keys.forward() ? "W" : "") + (keys.back() ? "S" : "") + (keys.left() ? "A" : "") + (keys.right() ? "D" : "")
            + (jump ? "+jump" : "") + (sprint ? "+sprint" : "") + (sneak ? "+sneak" : "");
        String line = String.format(Locale.ROOT, "%.2f %.2f %.2f | yaw %.1f->%.1f err %.1f pitch %.0f | %s | %s%s%s | #%d %s %d %d %d aim %.1f %.1f | %s",
            p.x, p.y, p.z, yaw, aimYaw, yawErr, pitch, k.isEmpty() ? "-" : k, ground ? "ground" : "air", water ? ",water" : "",
            onMagma ? ",magma" : "", index, target.move(), target.x(), target.y(), target.z(), aimX, aimZ, doing);
        trackBody(p, ground);
        // Burn while on magma means the sneak wasn't held this tick: log the lead-up so we can see why.
        if (onMagma && !sneak && ground) {
            event("MAGMA", String.format(Locale.ROOT, "on magma NOT sneaking (burn risk) at %.2f %.2f %.2f keys=%s sprint=%s doing=%s",
                p.x, p.y, p.z, k.isEmpty() ? "-" : k, sprint, doing));
        }
        synchronized (MotionDebug.class) {
            history.addLast(line);
            while (history.size() > HISTORY_TICKS) history.removeFirst();
            if (logFile) TRACE.write(LocalTime.now().format(TS) + " TICK " + line);
        }
        int mask = (keys.forward() ? 1 : 0) | (keys.back() ? 2 : 0) | (keys.left() ? 4 : 0) | (keys.right() ? 8 : 0) | (jump ? 16 : 0);
        mqTick(p, yaw, sprint, jump, ground, mask != 0);
        for (String a : ANOMALIES.sample(yaw, p.x, p.z, mask)) event("ANOMALY", a + " near " + target.x() + " " + target.y() + " " + target.z());
        if (jump && ground) event("JUMP", String.format(Locale.ROOT, "take-off at %.2f %.2f %.2f toward #%d %s %d %d %d",
            p.x, p.y, p.z, index, target.move(), target.x(), target.y(), target.z()));
    }

    private static boolean wasGround = true;
    private static double airTopY = Double.NaN;
    private static float lastHealth = -1;
    private static String lastRotOwner;

    /** Landings (with fall height), damage taken, and who holds the camera: what the per-tick line can't show at a glance. */
    private static void trackBody(net.minecraft.world.phys.Vec3 p, boolean ground) {
        Minecraft mc = Minecraft.getInstance();
        if (!ground) {
            airTopY = Double.isNaN(airTopY) ? p.y : Math.max(airTopY, p.y);
        } else if (!wasGround && !Double.isNaN(airTopY)) {
            double fell = airTopY - p.y;
            event("LAND", String.format(Locale.ROOT, "at %.2f %.2f %.2f after %s %.2f blocks", p.x, p.y, p.z,
                fell >= 0 ? "falling" : "rising", Math.abs(fell)));
            airTopY = Double.NaN;
        }
        wasGround = ground;
        if (mc.player != null) {
            float hp = mc.player.getHealth();
            if (lastHealth >= 0 && hp < lastHealth - 0.01f) {
                boolean sneaking = mc.options != null && mc.options.keyShift.isDown();
                String under = mc.level != null
                    ? mc.level.getBlockState(mc.player.blockPosition().below()).getBlock().toString() : "?";
                event("FAIL", String.format(Locale.ROOT, "took %.1f damage (%.1f -> %.1f hp)%s%s%s at %s standingOn=%s", lastHealth - hp, lastHealth, hp,
                    mc.player.isOnFire() ? " on fire" : "", mc.player.isInLava() ? " in lava" : "",
                    sneaking ? " sneaking" : " NOT-sneaking", mc.player.blockPosition().toShortString(), under));
            }
            lastHealth = hp;
        }
        String owner = RotationEngine.owner();
        if (!java.util.Objects.equals(owner, lastRotOwner)) {
            event("CAMERA", "rotation owner " + lastRotOwner + " -> " + owner);
            lastRotOwner = owner;
        }
    }

    /**
     * Generic per-tick line for other bots (the tunnel finder): same history buffer and anomaly checks,
     * so their errors come with the lead-up too.
     */
    public static void traceBot(String bot, net.minecraft.world.phys.Vec3 p, float yaw, float pitch, String state, boolean ground,
                                int keyMask) {
        if (!enabled) return;
        String keys = ((keyMask & 1) != 0 ? "W" : "") + ((keyMask & 2) != 0 ? "S" : "") + ((keyMask & 4) != 0 ? "A" : "")
            + ((keyMask & 8) != 0 ? "D" : "") + ((keyMask & 16) != 0 ? "+jump" : "") + ((keyMask & 32) != 0 ? "+attack" : "")
            + ((keyMask & 64) != 0 ? "+use" : "");
        String line = String.format(Locale.ROOT, "%s %.2f %.2f %.2f | yaw %.1f pitch %.1f | %s | %s | %s", bot, p.x, p.y, p.z, yaw, pitch,
            keys.isEmpty() ? "-" : keys, ground ? "ground" : "air", state);
        trackBody(p, ground);
        synchronized (MotionDebug.class) {
            history.addLast(line);
            while (history.size() > HISTORY_TICKS) history.removeFirst();
            if (logFile) TRACE.write(LocalTime.now().format(TS) + " TICK " + line);
        }
        for (String a : ANOMALIES.sample(yaw, p.x, p.z, keyMask & 0x1F)) event("ANOMALY", bot + " " + a + " state=" + state);
    }

    static void nodePassed(Step s, String how, net.minecraft.world.phys.Vec3 p) {
        if (!enabled) return;
        event("NODE", String.format(Locale.ROOT, "%s %s %d %d %d (at %.2f %.2f %.2f)", how, s.move(), s.x(), s.y(), s.z(), p.x, p.y, p.z));
    }

    // ---- hooks called by Motion ----

    static void tripStart(String goal, BlockPos from) {
        plans = fails = strikes = 0;
        ANOMALIES.reset();
        mqReset();
        synchronized (MotionDebug.class) {
            history.clear();
        }
        event("TRIP", "start " + goal + " from " + from.toShortString());
    }

    // ---- movement-quality accumulator (per trip): finds smoothness/efficiency improvements ----
    private static double mqDist, mqYawDelta, mqLastYaw = Double.NaN, mqLastX = Double.NaN, mqLastZ = Double.NaN;
    private static int mqTicks, mqStops, mqSprintTicks, mqJumps, mqNodes;
    private static double mqMaxSpeed;

    private static void mqReset() {
        mqDist = mqYawDelta = 0; mqLastYaw = mqLastX = mqLastZ = Double.NaN;
        mqTicks = mqStops = mqSprintTicks = mqJumps = mqNodes = 0; mqMaxSpeed = 0;
    }

    /** Called from trace() each tick with the live movement state. */
    private static void mqTick(net.minecraft.world.phys.Vec3 p, float yaw, boolean sprint, boolean jump, boolean ground, boolean keysDown) {
        mqTicks++;
        if (!Double.isNaN(mqLastX)) {
            double d = Math.sqrt((p.x - mqLastX) * (p.x - mqLastX) + (p.z - mqLastZ) * (p.z - mqLastZ));
            mqDist += d;
            double speed = d * 20; // blocks/sec (ticks are 20/s)
            if (speed > mqMaxSpeed) mqMaxSpeed = speed;
            if (d < 0.01 && keysDown && ground) mqStops++; // pushing a key but not moving = wasted/stuck tick
        }
        if (!Double.isNaN(mqLastYaw)) {
            float dy = Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - (float) mqLastYaw));
            mqYawDelta += dy; // total yaw turned (jitter/smoothness)
        }
        mqLastYaw = yaw; mqLastX = p.x; mqLastZ = p.z;
        // Read ACTUAL sprint state (the player can be sprinting even when the follower's canSprint intent is off),
        // so the metric reflects real movement, not just intended.
        boolean actualSprint = net.minecraft.client.Minecraft.getInstance().player != null
            && net.minecraft.client.Minecraft.getInstance().player.isSprinting();
        if (actualSprint) mqSprintTicks++;
        if (jump && ground) mqJumps++;
    }

    /** Trip-end movement-quality summary: speed, smoothness (yaw jitter per block), wasted-stop ratio. */
    private static String mqSummary() {
        if (mqTicks == 0 || mqDist < 0.5) return null;
        double avgSpeed = mqDist / (mqTicks / 20.0);
        double yawPerBlock = mqYawDelta / mqDist; // deg turned per block traveled (lower = smoother/straighter)
        double stopPct = 100.0 * mqStops / mqTicks;
        double sprintPct = 100.0 * mqSprintTicks / mqTicks;
        return String.format(Locale.ROOT,
            "moves: dist=%.1f avgSpeed=%.2f b/s (max %.2f) yawJitter=%.0f deg/blk stops=%.0f%% sprint=%.0f%% jumps=%d ticks=%d",
            mqDist, avgSpeed, mqMaxSpeed, yawPerBlock, stopPct, sprintPct, mqJumps, mqTicks);
    }

    static void planStart(BlockPos from, int maxNodes, boolean ahead) {
        if (!enabled) return;
        planStartNs = System.nanoTime();
        planFrom = from;
        event("PLAN", (ahead ? "ahead " : "") + "from " + from.toShortString() + " budget " + maxNodes);
    }

    /** Goal of the plan about to run, so a flood can be diagnosed against it. */
    static void planGoal(int x, int y, int z) { goalX = x; goalY = y; goalZ = z; hasGoal = true; }

    private static BlockPos planFrom;
    private static int goalX, goalY, goalZ;
    private static boolean hasGoal;

    static void planDone(GridPathfinder.Status status, List<Step> path, int expanded, Throwable error) {
        if (!enabled) return;
        plans++;
        double ms = planStartNs == 0 ? -1 : (System.nanoTime() - planStartNs) / 1e6;
        int breaks = 0, places = 0, jumps = 0;
        double cost = 0;
        for (Step s : path) {
            breaks += s.breaks().length;
            if (s.place() != GridPathfinder.NO_PLACE) places++;
            if (s.move() == Move.PARKOUR) jumps++;
            cost += s.cost();
        }
        lastPlan = String.format(Locale.ROOT, "%s %d steps, %d nodes, %.0fms", status, path.size(), expanded, ms);
        event("PLAN", String.format(Locale.ROOT, "%s steps=%d nodes=%d ms=%.0f cost=%.1f breaks=%d places=%d jumps=%d%s | %s",
            status, path.size(), expanded, ms, cost, breaks, places, jumps,
            error == null ? "" : " ERROR " + error,
            com.autism.seedcracker.motion.pure.GridPathfinder.Search.dbgAscendSummary()));
        // A plan that found nothing (or crashed) is an error worth the lead-up, not just a trace line.
        if (error != null || status == GridPathfinder.Status.FAILED) {
            StringBuilder st = new StringBuilder();
            if (error != null) for (StackTraceElement e : error.getStackTrace()) {
                if (st.length() > 600) break;
                st.append("\n        at ").append(e);
            }
            event("FAIL", "planner " + status + " after " + expanded + " nodes" + (error == null ? "" : ": " + error + st));
        }
        // Budget blowout (PARTIAL/FAILED near the node cap): dump the partial path's shape so we can see
        // whether the search spread sideways along an edge instead of toward the goal.
        if (expanded >= 100000 && status != GridPathfinder.Status.FOUND) {
            StringBuilder sb = new StringBuilder();
            int n = path.size();
            for (int i = 0; i < n; i += Math.max(1, n / 12)) {
                Step s = path.get(i);
                sb.append(String.format(Locale.ROOT, " [%d]%s %d,%d,%d", i, s.move(), s.x(), s.y(), s.z()));
            }
            if (n > 0) { Step s = path.get(n - 1); sb.append(String.format(Locale.ROOT, " [end]%s %d,%d,%d", s.move(), s.x(), s.y(), s.z())); }
            event("FAIL", "budget blowout: " + status + " expanded=" + expanded + " pathLen=" + n + " waypoints:" + sb);
            floodDiagnosis(path);
        }
    }

    /** Why the direct route to the goal failed: sample walkability along the start->goal corridor + the goal cell. */
    private static void floodDiagnosis(List<Step> path) {
        if (!hasGoal || planFrom == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
            "flood diag: from %d,%d,%d goal %d,%d,%d dist=%.1f", planFrom.getX(), planFrom.getY(), planFrom.getZ(),
            goalX, goalY, goalZ, Math.sqrt(planFrom.distSqr(new BlockPos(goalX, goalY, goalZ)))));
        // Corridor: sample the straight line every 2 blocks and report the block at feet and under.
        int x0 = planFrom.getX(), z0 = planFrom.getZ(), y0 = planFrom.getY();
        double dx = goalX - x0, dz = goalZ - z0, len = Math.sqrt(dx * dx + dz * dz);
        if (len > 0.5) {
            sb.append(" corridor:");
            int steps = (int) Math.min(12, Math.ceil(len / 2));
            for (int i = 0; i <= steps; i++) {
                int cx = (int) Math.round(x0 + dx * i / steps), cz = (int) Math.round(z0 + dz * i / steps);
                BlockPos feet = new BlockPos(cx, y0, cz);
                String f = mc.level.getBlockState(feet).getBlock().toString().replace("Block{minecraft:", "").replace("}", "");
                String u = mc.level.getBlockState(feet.below()).getBlock().toString().replace("Block{minecraft:", "").replace("}", "");
                boolean loaded = mc.level.hasChunkAt(feet);
                sb.append(String.format(Locale.ROOT, " %d,%d[%s/%s%s]", cx, cz, f, u, loaded ? "" : ",UNLOADED"));
            }
        }
        // Goal cell: is it standable (feet passable + head passable + floor under)?
        BlockPos g = new BlockPos(goalX, goalY, goalZ);
        boolean gLoaded = mc.level.hasChunkAt(g);
        String gf = mc.level.getBlockState(g).getBlock().toString().replace("Block{minecraft:", "").replace("}", "");
        String gu = mc.level.getBlockState(g.below()).getBlock().toString().replace("Block{minecraft:", "").replace("}", "");
        sb.append(String.format(Locale.ROOT, " | goal[%s/%s loaded=%s]", gf, gu, gLoaded));
        event("FAIL", sb.toString());
    }

    static void failure(String result, String why, Step at, boolean struck) {
        if (!enabled) return;
        fails++;
        if (struck) strikes++;
        event("FAIL", result + ": " + why + (at == null ? "" : " [node " + at.x() + " " + at.y() + " " + at.z() + " " + at.move() + "]")
            + (struck ? " (spot remembered)" : ""));
    }

    static void tripEnd(String why) {
        String mq = mqSummary();
        event("TRIP", "end: " + why + " (plans " + plans + ", failures " + fails + ")" + (mq == null ? "" : " | " + mq));
        synchronized (MotionDebug.class) {
            TRACE.flush();
        }
        clearMarkers();
    }

    // ---- world markers ----

    /** Path nodes coloured by what the bot does there (walk/jump/break/place/fall/climb). */
    static void feedMarkers(List<Step> path, int from) {
        if (!gotoOn || !markers) return;
        Set<BlockPos> walk = new HashSet<>(), jump = new HashSet<>(), dig = new HashSet<>(), place = new HashSet<>(),
            fall = new HashSet<>(), climb = new HashSet<>();
        for (int i = Math.max(1, from); i < path.size(); i++) {
            Step s = path.get(i);
            BlockPos p = new BlockPos(s.x(), s.y(), s.z());
            if (s.breaks().length > 0) {
                for (long b : s.breaks()) dig.add(BlockPos.of(b));
            } else if (s.place() != GridPathfinder.NO_PLACE) {
                place.add(BlockPos.of(s.place()));
            } else {
                switch (s.move()) {
                    case PARKOUR, ASCEND, PILLAR -> jump.add(p);
                    case DESCEND, WATER_DROP, BUCKET_DROP, CUSHION_DROP, LADDER_CATCH, DIG_DOWN -> fall.add(p);
                    case CLIMB_UP, CLIMB_DOWN, SWIM_UP, SWIM_DOWN, OPEN_DOOR -> climb.add(p);
                    default -> walk.add(p);
                }
            }
        }
        BlockEspRenderer.init();
        BlockEspRenderer.feed(ESP_PREFIX + "walk", walk, 0x553FE87E, false, false);
        BlockEspRenderer.feed(ESP_PREFIX + "jump", jump, 0xFFFFC857, false, true);
        BlockEspRenderer.feed(ESP_PREFIX + "dig", dig, 0xFFFF5B5B, false, true);
        BlockEspRenderer.feed(ESP_PREFIX + "place", place, 0xFF4FA3FF, false, true);
        BlockEspRenderer.feed(ESP_PREFIX + "fall", fall, 0xFFC86BFF, false, true);
        BlockEspRenderer.feed(ESP_PREFIX + "climb", climb, 0xFF4FE6E6, false, true);
    }

    /** Cells the failure memory is steering around. */
    static void feedStrikes(List<BlockPos> cells) {
        if (!gotoOn || !markers) return;
        BlockEspRenderer.init();
        BlockEspRenderer.feed(ESP_PREFIX + "strikes", new HashSet<>(cells), 0xFFFF2020, false, true);
    }

    private static void clearMarkers() {
        for (String k : new String[]{"walk", "jump", "dig", "place", "fall", "climb", "strikes"}) BlockEspRenderer.clear(ESP_PREFIX + k);
    }

    // ---- HUD ----

    public static void render(GuiGraphicsExtractor g) {
        if (!gotoOn) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.font == null) return;
        List<String> lines = new ArrayList<>();
        lines.add("Motion: " + Motion.status());
        lines.add("Plan: " + lastPlan);
        Step t = Motion.debugTarget();
        if (t != null) {
            double dx = t.x() + 0.5 - mc.player.getX(), dz = t.z() + 0.5 - mc.player.getZ();
            lines.add(String.format(Locale.ROOT, "Next: %s %d %d %d (%.1fm, %d left)", t.move(), t.x(), t.y(), t.z(),
                Math.hypot(dx, dz), Motion.debugRemaining()));
        }
        lines.add(String.format(Locale.ROOT, "Trip: %d plans, %d fails, %d remembered spots", plans, fails, strikes));
        lines.add("Rotation owner: " + String.valueOf(RotationEngine.owner()));
        synchronized (MotionDebug.class) {
            lines.addAll(recent);
        }
        int x = 4, y = 40, w = 0;
        for (String l : lines) w = Math.max(w, mc.font.width(l));
        g.fill(x - 2, y - 2, x + w + 2, y + lines.size() * 10, 0xA0000000);
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            int color = l.contains(" FAIL ") ? 0xFFFF6B6B : l.contains(" PLAN ") ? 0xFF9AD0FF : l.contains(" TRIP ") ? 0xFFFFC857
                : i < 5 ? 0xFFFFFFFF : 0xFFB0B0B0;
            g.text(mc.font, l, x, y + i * 10, color, true);
        }
    }
}
