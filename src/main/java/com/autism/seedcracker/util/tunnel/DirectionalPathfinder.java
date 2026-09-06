package com.autism.seedcracker.util.tunnel;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Plans a tunnel route that keeps a primary direction but detours around hazards.
 *
 * Given the player's position and a {@link PathScanner.ScanResult} hazard, it decides between:
 *  - approaching a far hazard (walk to within a safe distance, then reassess),
 *  - a minimal left/right detour around a close hazard (scan its bounds, pick the shorter side),
 *  - changing the primary direction entirely when completely blocked.
 *
 * The plan is a queue of waypoints the movement driver follows, after which normal scanning
 * resumes in the (possibly new) primary direction.
 *
 * Port of the Krypton AI DirectionalPathfinder (dev.FORE.AI) to Mojang 26.2 mappings.
 */
public final class DirectionalPathfinder {
    private static final int APPROACH_STOP_DISTANCE = 7;
    private static final int DETOUR_START_BUFFER = 5;
    private static final int DETOUR_SIDE_CLEARANCE = 2;

    private Direction primaryDirection;
    private Direction originalPrimaryDirection;
    public Queue<BlockPos> currentDetour = new LinkedList<>();
    public boolean isDetouring = false;
    private final PathScanner pathScanner;
    private int detourCount = 0;
    private int directionChangeCount = 0;
    private String lastDecisionReason = "";

    public DirectionalPathfinder(PathScanner scanner) {
        this.pathScanner = scanner;
    }

    public void setInitialDirection(Direction dir) {
        this.primaryDirection = dir;
        this.originalPrimaryDirection = dir;
    }

    public Direction getPrimaryDirection() { return primaryDirection; }
    public boolean isDetouring() { return isDetouring; }
    public Queue<BlockPos> peekAllWaypoints() { return new LinkedList<>(currentDetour); }

    public BlockPos getNextWaypoint() {
        if (currentDetour.isEmpty()) {
            isDetouring = false;
            return null;
        }
        return currentDetour.poll();
    }

    public BlockPos peekNextWaypoint() { return currentDetour.peek(); }

    public static final class PathPlan {
        public final boolean needsDetour;
        public final Queue<BlockPos> waypoints;
        public final String reason;
        public final Direction newPrimaryDirection;

        public PathPlan(boolean needsDetour, Queue<BlockPos> waypoints, String reason) {
            this.needsDetour = needsDetour;
            this.waypoints = waypoints;
            this.reason = reason;
            this.newPrimaryDirection = null;
        }

        public PathPlan(Direction newDirection, String reason) {
            this.needsDetour = false;
            this.waypoints = new LinkedList<>();
            this.reason = reason;
            this.newPrimaryDirection = newDirection;
        }
    }

    private static final class HazardBounds {
        final int startDistance;
        final int width;
        final int depth;
        final BlockPos center;
        HazardBounds(int startDistance, int width, int depth, BlockPos center) {
            this.startDistance = startDistance;
            this.width = width;
            this.depth = depth;
            this.center = center;
        }
    }

    public PathPlan calculateDetour(BlockPos playerPos, PathScanner.ScanResult hazard) {
        if (hazard.getHazardDistance() > 10) {
            return createApproachPlan(playerPos, hazard);
        }
        HazardBounds bounds = scanHazardBoundaries(playerPos, hazard);
        List<BlockPos> leftPath = buildMinimalDetourPath(playerPos, bounds, true);
        List<BlockPos> rightPath = buildMinimalDetourPath(playerPos, bounds, false);

        if (leftPath != null && rightPath != null) {
            return leftPath.size() <= rightPath.size()
                ? createDetourPlan(leftPath, "Left detour")
                : createDetourPlan(rightPath, "Right detour");
        } else if (leftPath != null) {
            return createDetourPlan(leftPath, "Left detour");
        } else if (rightPath != null) {
            return createDetourPlan(rightPath, "Right detour");
        }
        return handleCompletelyBlocked(playerPos);
    }

    private PathPlan createApproachPlan(BlockPos playerPos, PathScanner.ScanResult hazard) {
        int safeApproach = Math.max(hazard.getHazardDistance() - APPROACH_STOP_DISTANCE, 0);
        if (safeApproach <= 0) {
            return new PathPlan(false, new LinkedList<>(), "Already close to hazard");
        }
        BlockPos approachPoint = playerPos.relative(primaryDirection, safeApproach);
        if (!validateSegment(playerPos, approachPoint, primaryDirection, safeApproach)) {
            return handleCompletelyBlocked(playerPos);
        }
        List<BlockPos> approachPath = new ArrayList<>();
        approachPath.add(approachPoint);
        Queue<BlockPos> waypoints = new LinkedList<>(approachPath);
        this.currentDetour = new LinkedList<>(waypoints);
        this.isDetouring = true;
        return new PathPlan(true, waypoints, "Approaching distant hazard");
    }

    private List<BlockPos> buildMinimalDetourPath(BlockPos playerPos, HazardBounds bounds, boolean goLeft) {
        Direction sideDir = goLeft ? primaryDirection.getCounterClockWise() : primaryDirection.getClockWise();
        int sideDistance = bounds.width / 2 + DETOUR_SIDE_CLEARANCE;
        for (int attempt = 0; attempt < 3; attempt++) {
            List<BlockPos> path = tryDetourPath(playerPos, bounds, sideDir, sideDistance + attempt);
            if (path != null) return path;
        }
        return null;
    }

    private List<BlockPos> tryDetourPath(BlockPos playerPos, HazardBounds bounds, Direction sideDir, int sideDistance) {
        List<BlockPos> waypoints = new ArrayList<>();
        int approachDistance = Math.max(bounds.startDistance - DETOUR_START_BUFFER, 1);
        int forwardPastHazard = bounds.depth + 3;

        BlockPos turnPoint = adjustToGroundLevel(playerPos.relative(primaryDirection, approachDistance));
        if (!validateSegment(playerPos, turnPoint, primaryDirection, approachDistance)) return null;
        waypoints.add(turnPoint);

        BlockPos sidePoint = adjustToGroundLevel(turnPoint.relative(sideDir, sideDistance));
        if (!validateSegment(turnPoint, sidePoint, sideDir, sideDistance)) return null;
        waypoints.add(sidePoint);

        BlockPos pastHazard = adjustToGroundLevel(sidePoint.relative(primaryDirection, forwardPastHazard));
        if (!validateSegment(sidePoint, pastHazard, primaryDirection, forwardPastHazard)) return null;
        waypoints.add(pastHazard);
        return waypoints;
    }

    private BlockPos adjustToGroundLevel(BlockPos pos) {
        PathScanner.ScanResult currentScan = pathScanner.scanDirection(pos, primaryDirection, 0, 1, false);
        if (currentScan.getHazardType() == PathScanner.HazardType.UNSAFE_GROUND || !isGroundSolid(pos)) {
            BlockPos oneDown = pos.below();
            if (isGroundSolid(oneDown)) return oneDown;
            BlockPos twoDown = pos.below(2);
            if (isGroundSolid(twoDown)) return twoDown;
        }
        BlockPos oneUp = pos.above();
        PathScanner.ScanResult upScan = pathScanner.scanDirection(oneUp, primaryDirection, 0, 1, false);
        if (upScan.isSafe() && !isGroundSolid(pos) && isGroundSolid(pos.below())) return oneUp;
        return pos;
    }

    private boolean isGroundSolid(BlockPos pos) {
        PathScanner.ScanResult groundCheck = pathScanner.scanDirection(pos.above(), primaryDirection, 0, 1, false);
        return groundCheck.isSafe()
            || (groundCheck.getHazardType() != PathScanner.HazardType.LAVA
                && groundCheck.getHazardType() != PathScanner.HazardType.WATER
                && groundCheck.getHazardType() != PathScanner.HazardType.UNSAFE_GROUND);
    }

    private boolean validateSegment(BlockPos start, BlockPos end, Direction moveDir, int distance) {
        if (distance <= 10) {
            for (int i = 1; i <= distance; i++) {
                BlockPos checkPos = start.relative(moveDir, i);
                PathScanner.ScanResult scan = pathScanner.scanDirection(checkPos, moveDir, 1, 4, false);
                if (!scan.isSafe()) return false;
                if (moveDir != primaryDirection && i == distance) {
                    PathScanner.ScanResult forwardCheck = pathScanner.scanDirection(checkPos, primaryDirection, 2, 4, false);
                    if (!forwardCheck.isSafe() && forwardCheck.getHazardDistance() <= 1) return false;
                }
                if (!checkGroundSafety(checkPos)) return false;
            }
            return true;
        }
        BlockPos currentPos = start;
        for (int chunk = 0; chunk < distance; chunk += 5) {
            int chunkSize = Math.min(5, distance - chunk);
            for (int i = 1; i <= chunkSize; i++) {
                BlockPos checkPos = currentPos.relative(moveDir, i);
                int scanAhead = distance > 10 ? 2 : 1;
                PathScanner.ScanResult scan = pathScanner.scanDirection(checkPos, moveDir, scanAhead, 4, false);
                if (!scan.isSafe()) return false;
                if (!checkGroundSafety(checkPos)) return false;
            }
            currentPos = currentPos.relative(moveDir, chunkSize);
        }
        return true;
    }

    private boolean checkGroundSafety(BlockPos pos) {
        for (int depth = 1; depth <= 2; depth++) {
            BlockPos below = pos.below(depth);
            for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                PathScanner.ScanResult scan = pathScanner.scanDirection(below, dir, 0, 1, false);
                if (!scan.isSafe() && scan.getHazardDistance() == 0 && scan.getHazardType() == PathScanner.HazardType.LAVA) {
                    return false;
                }
            }
        }
        return true;
    }

    private PathPlan createDetourPlan(List<BlockPos> waypoints, String reason) {
        Queue<BlockPos> waypointQueue = new LinkedList<>(waypoints);
        this.currentDetour = new LinkedList<>(waypointQueue);
        this.isDetouring = true;
        detourCount++;
        this.lastDecisionReason = "Detour #" + detourCount + ": " + reason;
        return new PathPlan(true, waypointQueue, this.lastDecisionReason);
    }

    private HazardBounds scanHazardBoundaries(BlockPos playerPos, PathScanner.ScanResult initialHazard) {
        int hazardDistance = initialHazard.getHazardDistance();
        BlockPos hazardCenter = playerPos.relative(primaryDirection, hazardDistance);
        int leftWidth = 0;
        int rightWidth = 0;
        int forwardDepth = 0;
        Direction leftDir = primaryDirection.getCounterClockWise();
        Direction rightDir = primaryDirection.getClockWise();

        for (int i = 1; i <= 10; i++, leftWidth = i) {
            BlockPos checkPos = hazardCenter.relative(leftDir, i);
            if (pathScanner.scanDirection(checkPos, primaryDirection, 1, 4, false).isSafe()) break;
        }
        for (int i = 1; i <= 10; i++, rightWidth = i) {
            BlockPos checkPos = hazardCenter.relative(rightDir, i);
            if (pathScanner.scanDirection(checkPos, primaryDirection, 1, 4, false).isSafe()) break;
        }
        for (int i = 0; i <= 20; i++, forwardDepth = i) {
            BlockPos checkPos = hazardCenter.relative(primaryDirection, i);
            if (pathScanner.scanDirection(checkPos, primaryDirection, 1, 4, false).isSafe()) break;
        }

        int totalWidth = leftWidth + rightWidth + 1;
        int totalDepth = Math.max(forwardDepth, 1);
        return new HazardBounds(hazardDistance, totalWidth, totalDepth, hazardCenter);
    }

    private PathPlan handleCompletelyBlocked(BlockPos playerPos) {
        Direction[] alternatives = new Direction[]{primaryDirection.getClockWise(), primaryDirection.getCounterClockWise()};
        for (Direction newDir : alternatives) {
            PathScanner.ScanResult scan = pathScanner.scanDirection(playerPos, newDir, 20, 4, false);
            if (scan.isSafe()) {
                directionChangeCount++;
                lastDecisionReason = "Perpendicular direction change #" + directionChangeCount;
                return new PathPlan(newDir, lastDecisionReason);
            }
        }
        return new PathPlan((Direction) null, "No valid paths - recovery needed");
    }

    public void completeDetour() {
        this.isDetouring = false;
        this.currentDetour.clear();
    }

    public String getDebugInfo() {
        return primaryDirection == null
            ? "Primary: NOT SET"
            : String.format("Primary: %s | Detouring: %s | Detours: %d | Changes: %d | %s",
                primaryDirection.getName(), isDetouring, detourCount, directionChangeCount, lastDecisionReason);
    }
}
