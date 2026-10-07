package com.autism.seedcracker.motion.pure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Block-grid A* for walking a player (pure, unit tested). A node is the block the player's FEET
 * occupy. Moves: walk, diagonal (no corner cutting), step up 1, drop up to {@code maxDrop}, swim
 * up/down, and optionally tunnel (break blocks in the way) and dig straight down.
 *
 * The search is resumable: call {@link Search#step(int)} once per tick with a node budget so a
 * long search never freezes a frame. When the budget or loaded area runs out, the path to the
 * node closest to the goal is returned as a PARTIAL segment; callers walk it and replan.
 */
public final class GridPathfinder {
    private GridPathfinder() {}

    public enum Kind {
        /** Air-like: the body fits, nothing to stand on. */
        OPEN,
        /** Low collision (slab, bed, carpet): feet can stand in it, it also works as a floor. */
        LOW,
        /** Full block: blocks the body, can be stood on. */
        SOLID,
        /** Taller than a block (fence, wall): blocks the body, can't be stood on usefully. */
        WALL,
        WATER,
        /** Lava, fire, magma, cobweb, berry bush... never enter or stand on. */
        DANGER,
        /** Ladder / vine / scaffolding: passable, holds the player, climbable up and down. */
        CLIMB,
        /** Closed door or fence gate the player can open by hand: passable after a click. */
        DOOR,
        /** Thin panel on a cell edge (open door/trapdoor) blocking movement along X; walkable along Z. */
        PANEL_X,
        /** Thin panel on a cell edge blocking movement along Z; walkable along X. */
        PANEL_Z,
        /** Chunk not loaded (or outside the world border): unknown, never expand into it. */
        UNLOADED
    }

    public interface World {
        Kind kind(int x, int y, int z);

        /** Cost to break the block, or {@link Double#POSITIVE_INFINITY} when it must not be broken. */
        double breakCost(int x, int y, int z);

        /** Extra cost for standing in this cell (hostile mobs nearby); 0 = nothing to avoid. */
        default double avoidCost(int x, int y, int z) {
            return 0;
        }

        /** Walking speed multiplier with feet in this cell (soul sand / honey underfoot are below 1). */
        default double speedFactor(int x, int y, int z) {
            return 1.0;
        }

        /** Height the player stands at inside a LOW cell (carpet 0.06, slab 0.5); 0 for anything else. */
        default double floorHeight(int x, int y, int z) {
            return 0;
        }

        /** Jump height multiplier standing in this cell (honey underfoot is 0.5: no full-block jumps). */
        default double jumpFactor(int x, int y, int z) {
            return 1.0;
        }

        /** Extra cost to LAND (not walk) on this feet cell: farmland and turtle eggs break under a fall. */
        default double trampleCost(int x, int y, int z) {
            return 0;
        }

        /**
         * Landing in this WATER cell cancels any fall (a source or full falling column). A thin layer of
         * flowing water over a floor doesn't: the floor is hit at full speed.
         */
        default boolean softLanding(int x, int y, int z) {
            return true;
        }
    }

    public interface Goal {
        boolean reached(int x, int y, int z);

        double heuristic(int x, int y, int z);
    }

    public enum Move { START, WALK, DIAGONAL, ASCEND, DESCEND, SWIM_UP, SWIM_DOWN, DIG_DOWN, PARKOUR, BRIDGE,
        CLIMB_UP, CLIMB_DOWN, OPEN_DOOR, WATER_DROP, PILLAR, LADDER_CATCH, BUCKET_DROP, CUSHION_DROP }

    /** Longest drop considered even into water (keeps the search from scanning to bedrock). */
    static final int MAX_WATER_DROP = 40;
    /** Longest drop trusted to a water-bucket landing or a ladder catch (faster falls can skip the cell). */
    static final int MAX_BUCKET_DROP = 20;
    /** Longest drop trusted to a cushion-block landing (hay/slime/cobweb/powder-snow negate fall damage). */
    static final int MAX_CUSHION_DROP = 30;
    /** Ticks to walk one block (20 / 4.317 m/s): converts tick timings into this planner's block units. */
    public static final double WALK_TICKS_PER_BLOCK = 4.633;
    /** Cheapest cost per block of height each way (ladders up, water drops down): keeps heuristics admissible. */
    static final double UP_PER_BLOCK = 1.0;
    static final double DOWN_PER_BLOCK = 0.3;
    /** A partial path ending closer than this to the start isn't worth walking. */
    static final double MIN_PARTIAL_DIST = 5;
    /** Weights trading path cost against progress when picking where a partial path ends. */
    static final double[] COEFFICIENTS = {1.5, 2, 2.5, 3, 4, 5, 10};
    /** Long partial paths drop their last 10%: that end is where the planner knew the least. */
    static final int CUTOFF_MIN_LENGTH = 30;
    static final double CUTOFF_FACTOR = 0.9;
    /** Float noise from mixed walk/diagonal sums isn't worth a heap update. */
    private static final double MIN_IMPROVEMENT = 0.01;
    /**
     * Expansions without a better partial candidate, once unloaded ground has been seen, before settling. Detours
     * around a wall or lake improve the candidates every few thousand nodes; edge-flooding never does.
     */
    static final int STALL_NODES = 40_000;
    /** Most consecutive head-under-water hops a route may take before it must surface. */
    static final int MAX_UNDERWATER_HOPS = 12;
    /**
     * Highest floor-to-floor rise a jump lands on. The jump apex is ~1.25 blocks, so a block with a
     * slab on top (1.5) or carpet on a block from the ground (1.06 is fine, 1.5 is not) is unjumpable.
     */
    static final double MAX_JUMP_RISE = 1.2;
    /** Vanilla step height: rises up to this are walked, not jumped. */
    static final double STEP_HEIGHT = 0.6;

    /**
     * {@code breaks} are packed block positions (see {@link #pack}) to clear before moving in;
     * {@code place} is a packed position to place a block at (bridge floor / pillar), or {@link #NO_PLACE};
     * {@code cost} is what the planner charged for this hop, before route favoring.
     */
    public record Step(int x, int y, int z, Move move, long[] breaks, long place, double cost) {
        public Step(int x, int y, int z, Move move, long[] breaks) {
            this(x, y, z, move, breaks, NO_PLACE, 0);
        }

        public Step(int x, int y, int z, Move move, long[] breaks, long place) {
            this(x, y, z, move, breaks, place, 0);
        }
    }

    public static final long NO_PLACE = Long.MIN_VALUE;

    public enum Status { RUNNING, FOUND, PARTIAL, FAILED }

    /**
     * {@code placeBudget} = blocks the bot may place this plan (bridging + pillaring; 0 = never).
     * {@code longParkour} allows 3-block gaps after a straight run-up; {@code waterBucket} allows
     * clutch landings up to {@link #MAX_BUCKET_DROP} blocks.
     */
    public record Config(int maxDrop, boolean allowBreak, boolean allowSwim, double jumpCost, double hazardPenalty,
                         int maxNodes, boolean allowParkour, int placeBudget, boolean longParkour,
                         boolean diagonalVertical, boolean waterBucket, double heuristicWeight, boolean cushionBlocks) {
        public Config(int maxDrop, boolean allowBreak, boolean allowSwim, double jumpCost, double hazardPenalty,
                      int maxNodes, boolean allowParkour, int placeBudget, boolean longParkour,
                      boolean diagonalVertical, boolean waterBucket) {
            this(maxDrop, allowBreak, allowSwim, jumpCost, hazardPenalty, maxNodes, allowParkour, placeBudget, longParkour,
                diagonalVertical, waterBucket, 1.0, false);
        }

        public Config(int maxDrop, boolean allowBreak, boolean allowSwim, double jumpCost, double hazardPenalty,
                      int maxNodes, boolean allowParkour, int placeBudget, boolean longParkour,
                      boolean diagonalVertical, boolean waterBucket, double heuristicWeight) {
            this(maxDrop, allowBreak, allowSwim, jumpCost, hazardPenalty, maxNodes, allowParkour, placeBudget, longParkour,
                diagonalVertical, waterBucket, heuristicWeight, false);
        }

        public static Config defaults() {
            return new Config(3, false, true, 1.5, 6.0, 40_000, false, 0, false, true, false);
        }

        private Config with(boolean waterBucket, boolean cushionBlocks) {
            return new Config(maxDrop, allowBreak, allowSwim, jumpCost, hazardPenalty, maxNodes, allowParkour, placeBudget,
                longParkour, diagonalVertical, waterBucket, heuristicWeight, cushionBlocks);
        }

        /**
         * Weighted A*: the heuristic counts {@code w} times, so the search dives toward the goal instead of
         * fanning out over equal-cost ties. The route found costs at most {@code w} x the cheapest one.
         */
        public Config withHeuristicWeight(double w) {
            return new Config(maxDrop, allowBreak, allowSwim, jumpCost, hazardPenalty, maxNodes, allowParkour, placeBudget,
                longParkour, diagonalVertical, waterBucket, Math.max(1.0, w), cushionBlocks);
        }

        public Config withBreaking(boolean v) {
            return new Config(maxDrop, v, allowSwim, jumpCost, hazardPenalty, maxNodes, allowParkour, placeBudget,
                longParkour, diagonalVertical, waterBucket, heuristicWeight, cushionBlocks);
        }

        public Config withParkour(boolean v) {
            return new Config(maxDrop, allowBreak, allowSwim, jumpCost, hazardPenalty, maxNodes, v, placeBudget,
                longParkour, diagonalVertical, waterBucket, heuristicWeight, cushionBlocks);
        }

        public Config withPlaceBudget(int blocks) {
            return new Config(maxDrop, allowBreak, allowSwim, jumpCost, hazardPenalty, maxNodes, allowParkour,
                Math.max(0, blocks), longParkour, diagonalVertical, waterBucket, heuristicWeight, cushionBlocks);
        }

        public Config withLongParkour(boolean v) {
            return new Config(maxDrop, allowBreak, allowSwim, jumpCost, hazardPenalty, maxNodes, allowParkour, placeBudget,
                v, diagonalVertical, waterBucket, heuristicWeight, cushionBlocks);
        }

        public Config withDiagonalVertical(boolean v) {
            return new Config(maxDrop, allowBreak, allowSwim, jumpCost, hazardPenalty, maxNodes, allowParkour, placeBudget,
                longParkour, v, waterBucket, heuristicWeight, cushionBlocks);
        }

        public Config withWaterBucket(boolean v) {
            return with(v, cushionBlocks);
        }

        public Config withCushionBlocks(boolean v) {
            return with(waterBucket, v);
        }

        public Config withMaxNodes(int v) {
            return new Config(maxDrop, allowBreak, allowSwim, jumpCost, hazardPenalty, Math.max(1, v), allowParkour, placeBudget,
                longParkour, diagonalVertical, waterBucket, heuristicWeight, cushionBlocks);
        }

        public Config withMaxDrop(int v) {
            return new Config(Math.max(0, v), allowBreak, allowSwim, jumpCost, hazardPenalty, maxNodes, allowParkour, placeBudget,
                longParkour, diagonalVertical, waterBucket, heuristicWeight, cushionBlocks);
        }

        public Config withHazardPenalty(double v) {
            return new Config(maxDrop, allowBreak, allowSwim, jumpCost, v, maxNodes, allowParkour, placeBudget,
                longParkour, diagonalVertical, waterBucket, heuristicWeight, cushionBlocks);
        }
    }

    // ---- goals ----

    public static Goal block(int gx, int gy, int gz) {
        return new Goal() {
            public boolean reached(int x, int y, int z) {
                return x == gx && y == gy && z == gz;
            }

            public double heuristic(int x, int y, int z) {
                return octile(x - gx, z - gz) + vertical(y, gy);
            }
        };
    }

    /** Any Y at this column. */
    public static Goal xz(int gx, int gz) {
        return new Goal() {
            public boolean reached(int x, int y, int z) {
                return x == gx && z == gz;
            }

            public double heuristic(int x, int y, int z) {
                return octile(x - gx, z - gz);
            }
        };
    }

    /** Within {@code r} blocks (euclidean) of the target. */
    public static Goal near(int gx, int gy, int gz, int r) {
        return new Goal() {
            public boolean reached(int x, int y, int z) {
                long dx = x - gx, dy = y - gy, dz = z - gz;
                return dx * dx + dy * dy + dz * dz <= (long) r * r;
            }

            public double heuristic(int x, int y, int z) {
                // Inside the sphere both the horizontal and the vertical gap shrink by at most r, so take r off each
                // (octile over-reads a straight line by up to 8%). Subtracting r once from the sum overestimated
                // 3D offsets: 10 across + 10 up with r=3 claimed 17, but (8, 8) is in range for 16.
                double horiz = Math.max(0, octile(x - gx, z - gz) - r * OCTILE_SLACK);
                int dy = gy - y;
                int ty = Math.abs(dy) <= r ? y : gy - Integer.signum(dy) * r;
                return horiz + vertical(y, ty);
            }
        };
    }

    /** Largest octile / euclidean ratio (a 22.5 degree heading): converts a euclidean radius into octile units. */
    static final double OCTILE_SLACK = 1.0824;

    /** Feet at exactly this Y, anywhere ("get to y=-58"). */
    public static Goal yLevel(int gy) {
        return new Goal() {
            public boolean reached(int x, int y, int z) {
                return y == gy;
            }

            public double heuristic(int x, int y, int z) {
                return vertical(y, gy);
            }
        };
    }

    /** Reached when any of the goals is; heads for whichever is cheapest. */
    public static Goal anyOf(List<Goal> goals) {
        List<Goal> gs = List.copyOf(goals);
        return new Goal() {
            public boolean reached(int x, int y, int z) {
                for (Goal g : gs) if (g.reached(x, y, z)) return true;
                return false;
            }

            public double heuristic(int x, int y, int z) {
                double best = Double.POSITIVE_INFINITY;
                for (Goal g : gs) best = Math.min(best, g.heuristic(x, y, z));
                return best;
            }
        };
    }

    /** At least {@code distance} blocks (horizontal) from every {x, z} point (retreat from mobs / a spot). */
    public static Goal runAway(int distance, List<int[]> from) {
        List<int[]> pts = List.copyOf(from);
        return new Goal() {
            private double nearest(int x, int z) {
                double best = Double.POSITIVE_INFINITY;
                for (int[] p : pts) best = Math.min(best, Math.hypot(x - p[0], z - p[1]));
                return best;
            }

            public boolean reached(int x, int y, int z) {
                return nearest(x, z) >= distance;
            }

            public double heuristic(int x, int y, int z) {
                return Math.max(0, distance - nearest(x, z));
            }
        };
    }

    /** Least possible cost to change height, for heuristics. */
    static double vertical(int fromY, int toY) {
        int d = toY - fromY;
        return d > 0 ? d * UP_PER_BLOCK : -d * DOWN_PER_BLOCK;
    }

    static double octile(int dx, int dz) {
        int ax = Math.abs(dx), az = Math.abs(dz);
        return Math.max(ax, az) + (Math.sqrt(2) - 1) * Math.min(ax, az);
    }

    // ---- packing (same bit layout as vanilla BlockPos.asLong) ----

    public static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    public static int unpackX(long p) {
        return (int) (p >> 38);
    }

    public static int unpackY(long p) {
        return (int) (p << 52 >> 52);
    }

    public static int unpackZ(long p) {
        return (int) (p << 26 >> 38);
    }

    // ---- search ----

    /** Cells on the previous segment cost this fraction, so a replan keeps to the route already being walked. */
    static final double FAVOR_COEFFICIENT = 0.5;

    public static Search search(World world, Config config, int sx, int sy, int sz, Goal goal) {
        return new Search(world, config, sx, sy, sz, goal, null);
    }

    /** {@code favored}: packed cells of the previous segment (see {@link #FAVOR_COEFFICIENT}). */
    public static Search search(World world, Config config, int sx, int sy, int sz, Goal goal, long[] favored) {
        return new Search(world, config, sx, sy, sz, goal, favored);
    }

    /**
     * Re-prices one planned hop against the world as it is now. +inf when that move is no longer
     * possible as planned. Hops that rely on blocks the path itself places, or on a run-up, return
     * their planned cost (the follower checks those as it executes them).
     */
    public static double hopCost(World world, Config config, Step from, Step to) {
        if (to.move() == Move.PARKOUR || to.move() == Move.PILLAR || to.move() == Move.BUCKET_DROP
            || to.place() != NO_PLACE || from.place() != NO_PLACE) return to.cost();
        // Called for several hops every tick: keep the tables tiny (one expansion touches ~100 cells).
        Search s = new Search(world, config.withPlaceBudget(0), from.x(), from.y(), from.z(),
            block(to.x(), to.y(), to.z()), null, 128);
        s.start.move = from.move();
        s.expand(s.start);
        NodeRec r = s.nodes.get(pack(to.x(), to.y(), to.z()));
        if (r == null || r.parent != s.start || r.move != to.move()) return Double.POSITIVE_INFINITY;
        // Every block it now needs broken must already be in the plan: an unplanned break means the follower walks into a wall.
        for (long b : r.breaks) {
            boolean planned = false;
            for (long p : to.breaks()) if (p == b) planned = true;
            if (!planned) return Double.POSITIVE_INFINITY;
        }
        return r.stepCost;
    }

    private static final class NodeRec {
        final int x, y, z;
        final double h;
        double g = Double.POSITIVE_INFINITY, f, stepCost;
        NodeRec parent;
        Move move;
        long[] breaks;
        long place = NO_PLACE;
        int placed;
        int heapIndex = -1;
        boolean closed;

        NodeRec(int x, int y, int z, double h) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.h = h;
        }
    }

    public static final class Search {
        private static final long[] NONE = new long[0];
        private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        private static final int[][] DIAGONAL = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

        private final World world;
        private final Config config;
        private final Goal goal;
        private final CellCache kinds;
        private final LongTable<Double> breakCosts = new LongTable<>(64);
        private final LongTable<NodeRec> nodes;
        private final LongTable<Boolean> favored;
        private NodeRec[] heap;
        private int heapSize;
        private final NodeRec start;
        private final NodeRec[] bestSoFar = new NodeRec[COEFFICIENTS.length];
        private final double[] bestScore = new double[COEFFICIENTS.length];
        private NodeRec found;
        private NodeRec partialEnd;
        private int expanded;
        private Status status = Status.RUNNING;
        /** Expansion count when a partial-route candidate last improved; the stall check below caches its answer per value. */
        private int lastImproved;
        private int edgeCheckedAt = -1;
        private boolean edgeStall;
        /** Best (lowest) heuristic-to-goal seen so far, and the expansion count when it last dropped meaningfully. */
        private double bestH = Double.MAX_VALUE;
        private int lastHDrop;

        Search(World world, Config config, int sx, int sy, int sz, Goal goal, long[] favored) {
            this(world, config, sx, sy, sz, goal, favored, 8192);
        }

        Search(World world, Config config, int sx, int sy, int sz, Goal goal, long[] favored, int capacity) {
            kinds = new CellCache(world, capacity < 1024);
            nodes = new LongTable<>(capacity);
            heap = new NodeRec[Math.max(16, capacity / 8)];
            this.world = world;
            this.config = config;
            this.goal = goal;
            if (favored != null && favored.length > 0) {
                this.favored = new LongTable<>(favored.length * 2);
                for (long p : favored) this.favored.put(p, Boolean.TRUE);
            } else {
                this.favored = null;
            }
            start = new NodeRec(sx, sy, sz, goal.heuristic(sx, sy, sz) * config.heuristicWeight());
            start.g = 0;
            start.f = start.h;
            start.move = Move.START;
            start.breaks = NONE;
            nodes.put(pack(sx, sy, sz), start);
            heapInsert(start);
            Arrays.fill(bestSoFar, start);
            for (int i = 0; i < COEFFICIENTS.length; i++) bestScore[i] = start.h;
            bestH = start.h;
            lastHDrop = 0;
            dbgAscendCalls = dbgAscendRise = dbgAscendNoJump = dbgAscendHead = dbgAscendClear = dbgAscendOffer = 0;
            dbgFirstNote = ""; dbgOfferNote = ""; dbgExpandNote = "";
        }

        public Status status() {
            return status;
        }

        /** Debug: ASCEND move-generation counters for the climb-gap diagnosis (BUG-P6). */
        public static String dbgAscendSummary() {
            return String.format(java.util.Locale.ROOT, "ascend calls=%d rise=%d noJump=%d head=%d clear=%d offer=%d firstNote=[%s] offerNote=[%s] expandNote=[%s]",
                dbgAscendCalls, dbgAscendRise, dbgAscendNoJump, dbgAscendHead, dbgAscendClear, dbgAscendOffer, dbgFirstNote, dbgOfferNote, dbgExpandNote);
        }

        public int expanded() {
            return expanded;
        }

        /** Expands up to {@code budget} nodes. */
        public Status step(int budget) {
            if (status != Status.RUNNING) return status;
            for (int i = 0; i < budget; i++) {
                NodeRec n = heapPoll();
                if (n == null) return finish();
                n.closed = true;
                if (goal.reached(n.x, n.y, n.z)) {
                    found = n;
                    return status = Status.FOUND;
                }
                if (++expanded >= config.maxNodes()) return finish();
                // Debug: log stall evaluation values once when we cross the stall threshold.
                if (expanded == STALL_NODES + 1) {
                    NodeRec pc = partialCandidate();
                    com.autism.seedcracker.motion.MotionDebug.event("PLAN", String.format(java.util.Locale.ROOT,
                        "stall eval: expanded=%d lastImproved=%d lastHDrop=%d bestH=%.2f partial=%s",
                        expanded, lastImproved, lastHDrop, bestH,
                        pc == null ? "null" : (pc.x + "," + pc.y + "," + pc.z + " d2=" + distSqFromStart(pc))));
                }
                // No partial-candidate improvement for a long while: the frontier is flooding, not approaching
                // the goal. Settle now. If a usable partial exists this returns PARTIAL, else FAILED (finish()
                // handles both) — either is far better than flooding the whole budget. The loaded-edge case is
                // the most common flood; an obstructed-but-loaded goal (a sealed column, a tree) floods through
                // loaded ground the same way, so don't require the UNLOADED border. A search that IS progressing
                // improves bestScore regularly and never trips this.
                // Stall threshold scales with the budget: a search allowed 250k nodes must not give up at 40k of
                // no-improvement (a hard loaded maze snakes that far between bestScore gains), while a default
                // 40k-budget search settles at 40k as before. Cap so tiny budgets still flood-guard sensibly.
                int stallLimit = (int) Math.min(config.maxNodes(), Math.max(STALL_NODES, config.maxNodes() / 4));
                // Only bail early when the flood is into UNLOADED ground (the real waste case): a loaded maze
                // legitimately plateaus bestScore while backtracking, so it must be allowed its full budget.
                if (expanded - lastImproved > stallLimit && partialAtLoadedEdge()) return finish();
                // Note: a "goal-distance plateau" exit (bailing when bestH hasn't dropped) was removed: in a
                // maze the straight-line heuristic legitimately stalls while the path backtracks through walls,
                // so that check falsely gave up on solvable-but-snaking routes (regression in PathfinderBenchTest).
                // The lastImproved (bestScore, which includes path cost) check above is the correct flood guard:
                // a flooding search stops improving bestScore, a progressing one keeps improving it even in a maze.
                expand(n);
            }
            return status;
        }

        /** Out of time: settle for the best partial route found so far. */
        public Status stopNow() {
            return status == Status.RUNNING ? finish() : status;
        }

        /** A partial route worth walking already exists (what {@link #stopNow} would return). */
        public boolean hasPartial() {
            for (NodeRec n : bestSoFar) if (distSqFromStart(n) > MIN_PARTIAL_DIST * MIN_PARTIAL_DIST) return true;
            return false;
        }

        /**
         * Out of nodes or loaded area: end the segment at the best node under the gentlest weight
         * that still gets somewhere. Pure closest-to-goal picks dead ends; weighting in path cost
         * prefers the node a cheap route actually heads toward.
         */
        private Status finish() {
            NodeRec n = partialCandidate();
            if (n == null) return status = Status.FAILED;
            partialEnd = n;
            return status = Status.PARTIAL;
        }

        private NodeRec partialCandidate() {
            for (NodeRec n : bestSoFar) if (distSqFromStart(n) > MIN_PARTIAL_DIST * MIN_PARTIAL_DIST) return n;
            return null;
        }

        /** The node a partial route would end at borders unloaded ground (so no loaded detour can beat it). */
        private boolean partialAtLoadedEdge() {
            if (edgeCheckedAt == lastImproved) return edgeStall;
            edgeCheckedAt = lastImproved;
            NodeRec n = partialCandidate();
            edgeStall = false;
            if (n == null) return false;
            for (int[] d : CARDINAL) {
                for (int dx = 1; dx <= 2 && !edgeStall; dx++) {
                    if (kind(n.x + d[0] * dx, n.y, n.z + d[1] * dx) == Kind.UNLOADED) edgeStall = true;
                }
            }
            return edgeStall;
        }

        private double distSqFromStart(NodeRec n) {
            double dx = n.x - start.x, dy = n.y - start.y, dz = n.z - start.z;
            return dx * dx + dy * dy + dz * dz;
        }

        /** Path from start (inclusive) to the goal, or toward it for PARTIAL. */
        public List<Step> path() {
            NodeRec end = status == Status.FOUND ? found : status == Status.PARTIAL ? partialEnd : null;
            if (end == null) return List.of();
            List<Step> out = new ArrayList<>();
            for (NodeRec n = end; n != null; n = n.parent) out.add(new Step(n.x, n.y, n.z, n.move, n.breaks, n.place, n.stepCost));
            Collections.reverse(out);
            if (status == Status.PARTIAL && out.size() >= CUTOFF_MIN_LENGTH) {
                out = new ArrayList<>(out.subList(0, (int) ((out.size() - 1) * CUTOFF_FACTOR) + 1));
            }
            return out;
        }

        /** Whether the node being expanded allows a jump: every jump-type move starts from it. */
        private boolean jumpHere;

        private void expand(NodeRec n) {
            int x = n.x, y = n.y, z = n.z;
            jumpHere = canJump(x, y, z);
            Kind feetHere = kind(x, y, z), headHere = kind(x, y + 1, z);
            // Debug (BUG-P6): log when an ASCEND node is expanded near the start, to trace the climb chain.
            if (n.move == Move.ASCEND && dbgExpandNote.isEmpty()) {
                int ddx = x - start.x, ddz = z - start.z;
                if (ddx * ddx + ddz * ddz <= 36)
                    dbgExpandNote = String.format(java.util.Locale.ROOT, "expand ASCEND %d,%d,%d jumpHere=%s", x, y, z, jumpHere);
            }
            boolean inWater = feetHere == Kind.WATER;
            boolean inPanel = isPanel(feetHere) || isPanel(headHere);
            // A door cell is straight-through only: turning inside it walks into the door leaf.
            boolean doorway = n.move == Move.OPEN_DOOR && n.parent != null;
            int inX = doorway ? x - n.parent.x : 0, inZ = doorway ? z - n.parent.z : 0;
            boolean canPlace = config.placeBudget() > n.placed && !inWater;
            for (int[] d : CARDINAL) {
                if (doorway && (d[0] != inX || d[1] != inZ)) continue;
                // Leaving an open-door cell across its panel is as blocked as walking into a wall.
                if (inPanel && !(crossable(feetHere, d[0], d[1]) && crossable(headHere, d[0], d[1]))) continue;
                int nx = x + d[0], nz = z + d[1];
                tryWalk(n, nx, y, nz, inWater);
                tryAscend(n, nx, y, nz);
                tryDescend(n, nx, y, nz);
                if (config.allowParkour() && !inWater) tryParkour(n, d[0], d[1]);
                if (canPlace) tryBridge(n, nx, y, nz);
            }
            if (!inPanel && !doorway) {
                for (int[] d : DIAGONAL) {
                    tryDiagonal(n, x + d[0], y, z + d[1], x, z);
                    if (config.diagonalVertical() && !inWater) tryDiagonalVertical(n, d[0], d[1]);
                }
            }
            tryClimb(n, x, y, z);
            if (canPlace && !inPanel) tryPillar(n, x, y, z);
            if (config.allowSwim() && inWater) {
                if (standable(x, y + 1, z)) offer(n, x, y + 1, z, Move.SWIM_UP, 2.0, NONE);
                // Diving only where there's air within reach above the dive: long underwater routes drown the player.
                if (kind(x, y - 1, z) == Kind.WATER && standable(x, y - 1, z) && underwaterDepth(n) < MAX_UNDERWATER_HOPS) {
                    offer(n, x, y - 1, z, Move.SWIM_DOWN, 2.0, NONE);
                }
            }
            if (config.allowBreak()) tryDigDown(n, x, y, z);
        }

        // ---- open set: binary heap with decrease-key ----

        private void heapInsert(NodeRec n) {
            if (heapSize + 1 >= heap.length) heap = Arrays.copyOf(heap, heap.length << 1);
            heap[++heapSize] = n;
            n.heapIndex = heapSize;
            siftUp(n);
        }

        /** Equal f: prefer the node closer to the goal. Flat ground is full of ties, and FIFO order on them floods sideways. */
        private static boolean before(NodeRec a, NodeRec b) {
            return a.f < b.f || a.f == b.f && a.h < b.h;
        }

        private void siftUp(NodeRec n) {
            int i = n.heapIndex;
            while (i > 1) {
                NodeRec p = heap[i >>> 1];
                if (!before(n, p)) break;
                heap[i] = p;
                p.heapIndex = i;
                i >>>= 1;
            }
            heap[i] = n;
            n.heapIndex = i;
        }

        private NodeRec heapPoll() {
            if (heapSize == 0) return null;
            NodeRec top = heap[1];
            NodeRec last = heap[heapSize];
            heap[heapSize--] = null;
            top.heapIndex = -1;
            if (heapSize > 0) {
                int i = 1;
                while (true) {
                    int c = i << 1;
                    if (c > heapSize) break;
                    if (c < heapSize && before(heap[c + 1], heap[c])) c++;
                    if (!before(heap[c], last)) break;
                    heap[i] = heap[c];
                    heap[i].heapIndex = i;
                    i = c;
                }
                heap[i] = last;
                last.heapIndex = i;
            }
            return top;
        }

        /** Hops in a row with the head under water: 300 ticks of air is ~30 swims, keep well under it. */
        private int underwaterDepth(NodeRec n) {
            int d = 0;
            for (NodeRec c = n; c != null && kind(c.x, c.y + 1, c.z) == Kind.WATER; c = c.parent) {
                if (++d >= MAX_UNDERWATER_HOPS) break;
            }
            return d;
        }

        /** Slow-floor multiplier for the cell (soul sand, honey): walking there costs 1/speed. */
        private double slow(int x, int y, int z) {
            double s = world.speedFactor(x, y, z);
            return s <= 0.05 ? 20 : 1.0 / Math.min(1.0, s);
        }

        private void tryWalk(NodeRec n, int nx, int y, int nz, boolean fromWater) {
            if (!supported(nx, y, nz)) return;
            if (kind(nx, y + 1, nz) == Kind.WATER && underwaterDepth(n) >= MAX_UNDERWATER_HOPS) return;
            // A closed door/gate in the way: open it (a click) instead of breaking or detouring.
            if (kind(nx, y, nz) == Kind.DOOR) {
                Kind head = kind(nx, y + 1, nz);
                if (head == Kind.DOOR || headPassable(head)) offer(n, nx, y, nz, Move.OPEN_DOOR, 2.0, NONE);
                return;
            }
            double[] clear = clearCost(nx, y, nz, y + 1, nx - n.x, nz - n.z);
            if (clear == null) return;
            double cost = slow(nx, y, nz) + clear[0] + (kind(nx, y, nz) == Kind.WATER || fromWater ? 1.0 : 0.0);
            offer(n, nx, y, nz, Move.WALK, cost, breaksFor(clear, nx, y, nz, y + 1));
        }

        private void tryAscend(NodeRec n, int nx, int y, int nz) {
            // Slabs/carpets are LOW and get walked onto (step height); only full blocks need a jump.
            if (kind(nx, y, nz) != Kind.SOLID) return;
            dbgAscendCalls++;
            double rise = rise(n, nx, y + 1, nz);
            // A block topped with a slab is 1.5 up: past the jump apex, the player just bonks into its side.
            if (rise > MAX_JUMP_RISE) { dbgAscendRise++; dbgNote(n, nx, y + 1, nz, "rise " + rise); return; }
            // Off a bottom slab the next full block is only half a block up: a step, no jump needed (works on honey too).
            boolean step = rise <= STEP_HEIGHT;
            if (!step && !jumpHere) { dbgAscendNoJump++; dbgNote(n, nx, y + 1, nz, "noJump"); return; }
            // Jumping off a slab starts half a block higher: the head sweeps through y+3 above the take-off cell.
            if (!step && standHeight(n.x, y, n.z) - y > 0.2 && !headPassable(kind(n.x, y + 3, n.z))) { dbgAscendHead++; dbgNote(n, nx, y + 1, nz, "head"); return; }
            // The jump needs headroom above where we stand now.
            double[] above = clearCost(n.x, y + 2, n.z, y + 2);
            double[] body = clearCost(nx, y + 1, nz, y + 2);
            if (above == null || body == null) { dbgAscendClear++; dbgNote(n, nx, y + 1, nz, "clear above=" + (above != null) + " body=" + (body != null)); return; }
            long[] br = concat(breaksFor(above, n.x, y + 2, n.z, y + 2), breaksFor(body, nx, y + 1, nz, y + 2));
            // A step still charges the per-block climb so the heuristic (1 per block up) stays admissible.
            dbgAscendOffer++;
            int odx = nx - start.x, odz = nz - start.z;
            if (dbgOfferNote.isEmpty() && odx * odx + odz * odz <= 25)
                dbgOfferNote = String.format(java.util.Locale.ROOT, "offered %d,%d,%d from %d,%d,%d move=ASCEND",
                    nx, y + 1, nz, n.x, n.y, n.z);
            offer(n, nx, y + 1, nz, Move.ASCEND, slow(nx, y + 1, nz) + (step ? UP_PER_BLOCK : config.jumpCost()) + above[0] + body[0], br);
        }

        static String dbgOfferNote = "";
        static String dbgExpandNote = "";

        // Debug counters for the climb-gap (BUG-P6): reset per search, dumped on planDone.
        static int dbgAscendCalls, dbgAscendRise, dbgAscendNoJump, dbgAscendHead, dbgAscendClear, dbgAscendOffer;
        static String dbgFirstNote = "";
        private void dbgNote(NodeRec n, int tx, int ty, int tz, String why) {
            if (!dbgFirstNote.isEmpty()) return;
            // Only note rejections near the search start (the ones that matter for a short failed hop).
            int dx = tx - start.x, dz = tz - start.z;
            if (dx * dx + dz * dz > 36) return;
            dbgFirstNote = String.format(java.util.Locale.ROOT, "from %d,%d,%d to %d,%d,%d: %s (feet=%s head=%s floor=%s)",
                n.x, n.y, n.z, tx, ty, tz, why, kind(tx, ty, tz), kind(tx, ty + 1, tz), kind(tx, ty - 1, tz));
        }

        /** Vertical distance from the floor under {@code n} to the floor of feet cell (x,y,z). */
        private double rise(NodeRec n, int x, int y, int z) {
            return standHeight(x, y, z) - standHeight(n.x, n.y, n.z);
        }

        /** Height the feet rest at in this cell: slab and carpet tops raise it above the cell's floor. */
        private double standHeight(int x, int y, int z) {
            return kind(x, y, z) == Kind.LOW ? y + world.floorHeight(x, y, z) : y;
        }

        private void tryDescend(NodeRec n, int nx, int y, int nz) {
            if (!clearNoBreak(nx, y, nz) || supported(nx, y, nz)) return;
            fallFrom(n, nx, y, nz, 1.0, Move.DESCEND);
        }

        /**
         * Walking off an edge into column (nx, nz): find where the fall ends. {@code base} is the
         * horizontal part of the cost (a diagonal step-off is longer than a straight one).
         */
        private void fallFrom(NodeRec n, int nx, int y, int nz, double base, Move plain) {
            for (int k = 1; k <= MAX_WATER_DROP; k++) {
                int ty = y - k;
                Kind feet = kind(nx, ty, nz);
                if (!feetPassable(feet) && feet != Kind.CLIMB) return;
                // Landing in water cancels fall damage at any height.
                if (feet == Kind.WATER) {
                    // Water hanging over air doesn't stop the fall: we'd sink straight through it.
                    Kind under = kind(nx, ty - 1, nz);
                    if (under == Kind.OPEN || under == Kind.DANGER || under == Kind.UNLOADED) return;
                    // Only a still pool breaks a long fall; a thin flowing layer lets us hit the floor at full speed.
                    if (k > config.maxDrop() && !world.softLanding(nx, ty, nz)) return;
                    if (k > config.maxDrop()) offer(n, nx, ty, nz, Move.WATER_DROP, base + DOWN_PER_BLOCK * k, NONE);
                    else offer(n, nx, ty, nz, plain, base + 0.5 * k, NONE);
                    return;
                }
                // Ladder/vine in the fall line: grab it (holding forward into a climbable stops the fall).
                if (feet == Kind.CLIMB) {
                    if (k <= config.maxDrop()) {
                        offer(n, nx, ty, nz, plain, base + 0.5 * k, NONE);
                        return;
                    }
                    if (k <= MAX_BUCKET_DROP && plain == Move.DESCEND) {
                        offer(n, nx, ty, nz, Move.LADDER_CATCH, base + 0.5 * k + 2.0, NONE);
                    }
                    return;
                }
                // Ladder tops have no floor: a falling player drops into the ladder cell, not onto it.
                if (kind(nx, ty - 1, nz) == Kind.CLIMB) continue;
                if (standable(nx, ty, nz)) {
                    // Damage-aware cost: vanilla hurts from a 4-block fall, so k<=3 is free of a damage penalty
                    // (a 3-block drop is safe). k>=4 carries a modest penalty so the planner PREFERS a safe
                    // detour (stairs) when one exists, but will still take a damaging drop within max-drop when
                    // it's the only way down (the penalty never makes it worse than "no path").
                    if (k <= config.maxDrop()) {
                        double dmgPenalty = k <= 3 ? 0.0 : (k - 3) * 0.75;
                        offer(n, nx, ty, nz, plain, base + 0.5 * k + dmgPenalty + world.trampleCost(nx, ty, nz), NONE);
                    } else if (config.waterBucket() && k <= MAX_BUCKET_DROP && plain == Move.DESCEND
                        && kind(nx, ty - 1, nz) == Kind.SOLID && clutchSafe(nx, ty, nz)) {
                        // Water-bucket clutch: place water on the landing block just before impact, pick it back up.
                        offer(n, nx, ty, nz, Move.BUCKET_DROP, base + 0.5 * k + 6.0, NONE);
                    } else if (config.cushionBlocks() && k <= MAX_CUSHION_DROP && plain == Move.DESCEND
                        && kind(nx, ty - 1, nz) == Kind.SOLID) {
                        // Cushion-block clutch: place a hay/slime/cobweb/powder-snow block on the landing floor
                        // before impact, negating (most of) the fall damage. Lands standing on the placed block.
                        offer(n, nx, ty, nz, Move.CUSHION_DROP, base + 0.5 * k + 5.0, NONE);
                    }
                    return;
                }
            }
        }

        /** Water placed here must stay put (no flowing off an edge) and can't land in lava. */
        private boolean clutchSafe(int x, int y, int z) {
            if (kind(x, y, z) != Kind.OPEN || kind(x, y + 1, z) != Kind.OPEN) return false;
            for (int[] d : CARDINAL) if (kind(x + d[0], y, z + d[1]) == Kind.DANGER) return false;
            return true;
        }

        /** Up/down a ladder, vine or scaffolding column. */
        private void tryClimb(NodeRec n, int x, int y, int z) {
            // Up: we're on a ladder (or one starts at head height) and there's room above our head.
            if ((kind(x, y, z) == Kind.CLIMB || kind(x, y + 1, z) == Kind.CLIMB)
                && feetPassable(kind(x, y + 1, z)) && headPassable(kind(x, y + 2, z))) {
                offer(n, x, y + 1, z, Move.CLIMB_UP, 1.8, NONE);
            }
            if (kind(x, y - 1, z) == Kind.CLIMB) offer(n, x, y - 1, z, Move.CLIMB_DOWN, 1.3, NONE);
        }

        private void tryDiagonal(NodeRec n, int nx, int y, int nz, int x, int z) {
            if (!standable(nx, y, nz)) return;
            if (!clearNoBreak(nx, y, z) || !clearNoBreak(x, y, nz)) return;
            // Cutting the corner past a hazard is allowed, but hazardPenalty prices cells beside one, so the
            // search prefers a wider berth (see hazardPenalty: orthogonal + diagonal adjacency at feet and floor).
            offer(n, nx, y, nz, Move.DIAGONAL, Math.sqrt(2) * slow(nx, y, nz) + (kind(nx, y, nz) == Kind.WATER ? 1.0 : 0.0), NONE);
        }

        /**
         * Diagonal step up onto a full block, or diagonal step off an edge. Both corner columns must
         * be clear at the higher level so the 0.6-wide body never clips a corner mid-move.
         */
        private void tryDiagonalVertical(NodeRec n, int dx, int dz) {
            int x = n.x, y = n.y, z = n.z, nx = x + dx, nz = z + dz;
            // Up: the target block is solid, both corners are open for feet+head one level up, and so is our headroom.
            if (jumpHere && kind(nx, y, nz) == Kind.SOLID && clearNoBreak(nx, y + 1, nz) && headPassable(kind(x, y + 2, z))
                && rise(n, nx, y + 1, nz) <= MAX_JUMP_RISE
                && cornerClear(x + dx, y + 1, z) && cornerClear(x, y + 1, z + dz)
                && feetOrLowPassable(kind(x + dx, y, z)) && feetOrLowPassable(kind(x, y, z + dz))) {
                offer(n, nx, y + 1, nz, Move.ASCEND, Math.sqrt(2) * slow(nx, y + 1, nz) + config.jumpCost(), NONE);
            }
            // Down: step diagonally off an edge; corners must be clear at our level.
            if (clearNoBreak(nx, y, nz) && !supported(nx, y, nz) && clearNoBreak(x + dx, y, z) && clearNoBreak(x, y, z + dz)) {
                fallFrom(n, nx, y, nz, Math.sqrt(2), Move.DESCEND);
            }
        }

        private boolean cornerClear(int x, int y, int z) {
            return headPassable(kind(x, y, z)) && headPassable(kind(x, y + 1, z));
        }

        private static boolean feetOrLowPassable(Kind k) {
            return k == Kind.OPEN || k == Kind.LOW;
        }

        /**
         * Sprint-jump straight across a gap: 1-2 blocks level, 3 blocks level after a straight
         * run-up (longParkour), or 1-2 blocks up one level. Every cell over the gap needs headroom
         * for the arc, and the landing must be solid with a safe overshoot cell.
         */
        private void tryParkour(NodeRec n, int dx, int dz) {
            int y = n.y;
            if (kind(n.x, y - 1, n.z) != Kind.SOLID || !headPassable(kind(n.x, y + 2, n.z)) || !jumpHere) return;
            int maxGap = config.longParkour() && runUp(n, dx, dz) ? 3 : 2;
            for (int gap = 1; gap <= maxGap; gap++) {
                int gx = n.x + dx * gap, gz = n.z + dz * gap;
                // Each gap cell: open for the body + the jump arc, and actually a gap (else walk is better).
                if (!clearNoBreak(gx, y, gz) || !headPassable(kind(gx, y + 2, gz)) || supported(gx, y, gz)) return;
                int lx = gx + dx, lz = gz + dz;
                // Jump up one level onto the far side (only short gaps reach that high).
                if (gap <= 2 && kind(lx, y, lz) == Kind.SOLID && clearNoBreak(lx, y + 1, lz) && headPassable(kind(lx, y + 3, lz))
                    && headPassable(kind(gx, y + 3, gz))) {
                    if (rise(n, lx, y + 1, lz) <= MAX_JUMP_RISE && overshootSafe(lx + dx, y + 1, lz + dz)) {
                        offer(n, lx, y + 1, lz, Move.PARKOUR, 2.5 + gap * 1.5 + config.jumpCost() + world.trampleCost(lx, y + 1, lz), NONE);
                    }
                    return;
                }
                if (kind(lx, y - 1, lz) == Kind.SOLID && clearNoBreak(lx, y, lz) && headPassable(kind(lx, y + 2, lz))) {
                    if (overshootSafe(lx + dx, y, lz + dz)) offer(n, lx, y, lz, Move.PARKOUR, 2.0 + gap * 1.5 + world.trampleCost(lx, y, lz), NONE);
                    return;
                }
            }
        }

        /** Two straight flat cells behind us in the jump direction: enough run-up to reach full sprint speed. */
        private boolean runUp(NodeRec n, int dx, int dz) {
            NodeRec a = n.parent;
            if (a == null || a.y != n.y || a.x != n.x - dx || a.z != n.z - dz) return false;
            NodeRec b = a.parent;
            return b != null && b.y == n.y && b.x == a.x - dx && b.z == a.z - dz
                && (a.move == Move.WALK || a.move == Move.START);
        }

        /** A sprint-jump landing slides into the next cell: that cell must not burn or hurt. */
        private boolean overshootSafe(int x, int y, int z) {
            return kind(x, y, z) != Kind.DANGER && kind(x, y + 1, z) != Kind.DANGER && kind(x, y - 1, z) != Kind.DANGER;
        }

        /** Place a floor block under the next cell when it's a gap with nothing to stand on. */
        private void tryBridge(NodeRec n, int nx, int y, int nz) {
            if (supported(nx, y, nz) || !clearNoBreak(nx, y, nz)) return;
            Kind floor = kind(nx, y - 1, nz);
            if (floor != Kind.OPEN) return;
            // Something to place against: the block we stand on (real, or the one this branch just placed).
            // The shared kind cache is never edited, so other branches can't walk on a block they never placed.
            boolean standingOnPlaced = n.place == pack(n.x, y - 1, n.z);
            if (kind(n.x, y - 1, n.z) != Kind.SOLID && !standingOnPlaced) return;
            NodeRec rec = offer(n, nx, y, nz, Move.BRIDGE, 3.5, NONE);
            if (rec != null) {
                rec.place = pack(nx, y - 1, nz);
                rec.placed = n.placed + 1;
            }
        }

        /** Jump and place a block under our feet to rise one level (towering out of a pit). */
        private void tryPillar(NodeRec n, int x, int y, int z) {
            // The block goes where our feet are: a slab or carpet there can't be placed into.
            if (kind(x, y, z) != Kind.OPEN || !jumpHere) return;
            boolean onPlaced = n.place == pack(x, y - 1, z);
            if (kind(x, y - 1, z) != Kind.SOLID && !onPlaced) return;
            // Room for the jump: the cell we rise into needs feet+head clear (no breaking mid-air).
            if (!headPassable(kind(x, y + 1, z)) || !headPassable(kind(x, y + 2, z))) return;
            NodeRec rec = offer(n, x, y + 1, z, Move.PILLAR, 6.0, NONE);
            if (rec != null) {
                rec.place = pack(x, y, z);
                rec.placed = n.placed + 1;
            }
        }

        private void tryDigDown(NodeRec n, int x, int y, int z) {
            Kind floor = kind(x, y - 1, z);
            if (floor != Kind.SOLID && floor != Kind.LOW) return;
            // Never dig into a drop: the block under the one we break must hold us.
            if (kind(x, y - 2, z) != Kind.SOLID) return;
            double c = breakCost(x, y - 1, z);
            if (Double.isInfinite(c)) return;
            offer(n, x, y - 1, z, Move.DIG_DOWN, 1.5 + c, new long[]{pack(x, y - 1, z)});
        }

        private boolean clearNoBreak(int x, int y, int z) {
            return feetPassable(kind(x, y, z)) && headPassable(kind(x, y + 1, z));
        }

        private double[] clearCost(int x, int y0, int z, int y1) {
            return clearCost(x, y0, z, y1, 0, 0);
        }

        /** Shared result for the common case: nothing to break. Never written to. */
        private static final double[] CLEAR = {0, 0};

        /** {totalBreakCost, mask bit0=feet bit1=head} or null when blocked. (dx,dz) = travel axis, for panels. */
        private double[] clearCost(int x, int y0, int z, int y1, int dx, int dz) {
            double cost = 0;
            int mask = 0;
            for (int y = y0, bit = 0; y <= y1; y++, bit++) {
                Kind k = kind(x, y, z);
                boolean ok = isPanel(k) ? (dx != 0 || dz != 0) && crossable(k, dx, dz)
                    : y == y0 && y0 != y1 ? feetPassable(k) : headPassable(k);
                if (ok) continue;
                if (!config.allowBreak() || !breakable(k)) return null;
                double c = breakCost(x, y, z);
                if (Double.isInfinite(c)) return null;
                cost += c;
                mask |= 1 << bit;
            }
            return mask == 0 ? CLEAR : new double[]{cost, mask};
        }

        private static long[] breaksFor(double[] clear, int x, int y0, int z, int y1) {
            int mask = (int) clear[1];
            if (mask == 0) return NONE;
            List<Long> out = new ArrayList<>();
            // Head first so a dislodged block above can't fall into the gap we're clearing.
            for (int y = y1, bit = y1 - y0; y >= y0; y--, bit--) if ((mask & (1 << bit)) != 0) out.add(pack(x, y, z));
            long[] arr = new long[out.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
            return arr;
        }

        private static long[] concat(long[] a, long[] b) {
            if (a.length == 0) return b;
            if (b.length == 0) return a;
            long[] r = new long[a.length + b.length];
            System.arraycopy(a, 0, r, 0, a.length);
            System.arraycopy(b, 0, r, a.length, b.length);
            return r;
        }

        private boolean standable(int x, int y, int z) {
            return feetPassable(kind(x, y, z)) && headPassable(kind(x, y + 1, z)) && supported(x, y, z);
        }

        /** Something holds the player up at this feet cell. */
        private boolean supported(int x, int y, int z) {
            Kind feet = kind(x, y, z);
            Kind below = kind(x, y - 1, z);
            // Water only holds us up over more water or a floor; a source hanging over a drop is a hole we sink through.
            if (feet == Kind.WATER) return below != Kind.OPEN && below != Kind.DANGER && below != Kind.UNLOADED;
            if (feet == Kind.LOW || feet == Kind.CLIMB) return true;
            // Standing on top of a ladder column is fine too.
            return below == Kind.SOLID || below == Kind.CLIMB;
        }

        private static boolean feetPassable(Kind k) {
            return k == Kind.OPEN || k == Kind.LOW || k == Kind.WATER || k == Kind.CLIMB;
        }

        private static boolean headPassable(Kind k) {
            return k == Kind.OPEN || k == Kind.WATER || k == Kind.CLIMB;
        }

        private static boolean breakable(Kind k) {
            return k == Kind.SOLID || k == Kind.LOW || k == Kind.WALL;
        }

        private static boolean isPanel(Kind k) {
            return k == Kind.PANEL_X || k == Kind.PANEL_Z;
        }

        /** Moving along (dx,dz) doesn't cross this cell's panel (always true for non-panels). */
        private static boolean crossable(Kind k, int dx, int dz) {
            if (k == Kind.PANEL_X) return dx == 0;
            if (k == Kind.PANEL_Z) return dz == 0;
            return true;
        }

        /** Standing high in a LOW cell (slab) puts the 1.8-tall body into y+2: that cell must be clear too. */
        private boolean fitsBody(int x, int y, int z) {
            return kind(x, y, z) != Kind.LOW || world.floorHeight(x, y, z) <= 0.2 || headPassable(kind(x, y + 2, z));
        }

        /** Can a full-block jump start from this feet cell (honey blocks don't allow one). */
        private boolean canJump(int x, int y, int z) {
            return world.jumpFactor(x, y, z) >= 0.9;
        }

        /** Returns the node when this route to it is the best so far (callers may annotate it), else null. */
        private NodeRec offer(NodeRec from, int x, int y, int z, Move move, double cost, long[] breaks) {
            if (kind(x, y, z) == Kind.UNLOADED || kind(x, y - 1, z) == Kind.UNLOADED) return null;
            if (!fitsBody(x, y, z)) return null;
            cost += hazardPenalty(x, y, z) + world.avoidCost(x, y, z);
            // An infinite (or NaN) world cost means "never": it must not become a reachable node with g = inf.
            if (!(cost < Double.POSITIVE_INFINITY)) return null;
            long key = pack(x, y, z);
            double charged = favored != null && favored.contains(key) ? cost * FAVOR_COEFFICIENT : cost;
            double g = from.g + charged;
            NodeRec rec = nodes.get(key);
            if (rec == null) {
                rec = new NodeRec(x, y, z, goal.heuristic(x, y, z) * config.heuristicWeight());
                nodes.put(key, rec);
            } else if (rec.closed || rec.g - g <= MIN_IMPROVEMENT) {
                return null;
            }
            rec.g = g;
            rec.f = g + rec.h;
            rec.stepCost = cost;
            rec.parent = from;
            rec.move = move;
            rec.breaks = breaks;
            rec.place = NO_PLACE;
            rec.placed = from.placed;
            if (rec.heapIndex > 0) siftUp(rec);
            else heapInsert(rec);
            // Track the closest we've got to the goal (heuristic). A meaningful drop resets the plateau clock.
            if (bestH - rec.h > 0.5) {
                bestH = rec.h;
                lastHDrop = expanded;
            }
            for (int i = 0; i < COEFFICIENTS.length; i++) {
                double score = rec.h + g / COEFFICIENTS[i];
                if (bestScore[i] - score > MIN_IMPROVEMENT) {
                    bestScore[i] = score;
                    bestSoFar[i] = rec;
                    lastImproved = expanded;
                }
            }
            return rec;
        }

        // A cell is offered from up to ~20 neighbours; its 8 surrounding kind lookups only need doing once.
        // Hazard zone is graduated: touching a hazard (ring 1) costs the full penalty, one more cell out (ring 2)
        // costs a fraction, so a detour curves a couple of blocks clear of magma/lava instead of hugging the corner.
        private double hazardPenalty(int x, int y, int z) {
            int h = kinds.hazard(x, y, z);
            if (h == 0) {
                boolean near = false;
                // Ring 1: orthogonal AND diagonal neighbours, at feet level and one below (the floor you'd clip).
                for (int[] d : CARDINAL) {
                    if (kind(x + d[0], y, z + d[1]) == Kind.DANGER || kind(x + d[0], y - 1, z + d[1]) == Kind.DANGER) { near = true; break; }
                }
                if (!near) for (int[] d : DIAGONAL) {
                    if (kind(x + d[0], y, z + d[1]) == Kind.DANGER || kind(x + d[0], y - 1, z + d[1]) == Kind.DANGER) { near = true; break; }
                }
                if (near) { kinds.setHazard(x, y, z, 2); h = 2; }
                else {
                    // Ring 2: any hazard two cells away (Chebyshev), giving the path a wider safety margin.
                    boolean ring2 = false;
                    for (int dx = -2; dx <= 2 && !ring2; dx++)
                        for (int dz = -2; dz <= 2; dz++) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) != 2) continue; // ring 1 already handled
                            if (kind(x + dx, y, z + dz) == Kind.DANGER || kind(x + dx, y - 1, z + dz) == Kind.DANGER) { ring2 = true; break; }
                        }
                    if (ring2) { kinds.setHazard(x, y, z, 3); h = 3; }
                    else { kinds.setHazard(x, y, z, 1); h = 1; }
                }
            }
            if (h == 2) return config.hazardPenalty();
            if (h == 3) return config.hazardPenalty() * 0.5;
            return 0;
        }

        private Kind kind(int x, int y, int z) {
            return kinds.kind(x, y, z);
        }

        private double breakCost(int x, int y, int z) {
            long p = pack(x, y, z);
            Double c = breakCosts.get(p);
            if (c == null) {
                c = world.breakCost(x, y, z);
                breakCosts.put(p, c);
            }
            return c;
        }
    }
}
