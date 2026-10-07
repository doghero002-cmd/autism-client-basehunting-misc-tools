package gpucrack;

/**
 * Unified 16-way model-index acceptance model for texture-rotation cracking, ported from
 * CoordsFinder (github.com/ALaggyDev/CoordsFinder, MIT - credit ALaggyDev).
 *
 * Every crackable block picks one of 16 model variants per position via nextInt(16). A grid
 * cell observation is converted into a 16-bit mask of the model indices that could show what
 * the screenshotter saw. The scanner computes the position's model index once and tests it
 * against the mask - so ordinary top faces, side faces (mirrored variants of stone/deepslate/
 * bedrock/sculk), and netherrack per-face observations all unify into one check, and multiple
 * faces of one block AND together into a single tighter mask.
 *
 * Cell codes used across the pattern grid:
 *   -1        unknown
 *   0..3      ordinary top/bottom face rotation (dirt-style, 4-state)
 *   10 + bit  side face, 2-state (bit 0/1) - mirrored variants
 *   20 + face*4 + rot   netherrack specific face (face 0-5 = up,down,north,south,east,west)
 */
public final class FaceModel {

    private FaceModel() {}

    public static final int UNKNOWN = -1;
    public static final int SIDE_BASE = 10;       // 10,11 = side 0/1
    public static final int NETHERRACK_BASE = 20; // 20 + face*4 + rot

    /** Netherrack's visible clockwise quarter-turn per model index per world face (CoordsFinder). */
    public static final int[][] NETHERRACK_FACE_ROTATIONS = {
        {0, 0, 0, 0, 0, 0},
        {0, 2, 2, 0, 1, 3},
        {0, 0, 2, 2, 2, 2},
        {2, 0, 2, 0, 3, 1},
        {1, 3, 0, 0, 0, 0},
        {1, 1, 3, 1, 2, 0},
        {1, 3, 2, 2, 2, 2},
        {3, 3, 1, 3, 2, 0},
        {2, 2, 0, 0, 0, 0},
        {2, 0, 0, 2, 3, 1},
        {2, 2, 2, 2, 2, 2},
        {0, 2, 0, 2, 1, 3},
        {3, 1, 0, 0, 0, 0},
        {3, 3, 1, 3, 0, 2},
        {3, 1, 2, 2, 2, 2},
        {1, 1, 3, 1, 0, 2},
    };

    /** 16-way index -> ordinary 4-way rotation (Vanilla-3: index>>2; legacy: index&3). */
    private static int visibleFourWay(int index, boolean legacy) {
        return legacy ? (index & 3) : (index >> 2);
    }

    /**
     * The 16-bit mask of model indices that produce observation {@code code} under grid
     * orientation {@code turns} (0-3 CW quarter-turns of the whole grid), for a Vanilla-3
     * (nextInt) or legacy client.
     *
     * Orientation handling matches CoordsFinder's rotate_observation: ordinary top rotation
     * advances with the turn, side stays fixed, and netherrack faces rotate yaw (up-face
     * rotation advances, down-face retreats, side faces keep rotation but change direction).
     */
    public static int acceptMask(int code, int turns, boolean legacy) {
        int mask = 0;
        if (code < 0) return 0xFFFF; // unknown cell accepts everything
        for (int index = 0; index < 16; index++) {
            if (accepted(code, index, turns, legacy)) mask |= 1 << index;
        }
        return mask;
    }

    /** True when model index {@code index} shows observation {@code code} under orientation. */
    private static boolean accepted(int code, int index, int turns, boolean legacy) {
        if (code >= 0 && code <= 3) {
            // Ordinary top/bottom: grid orientation advances the stored rotation into the world.
            int worldRot = (code + turns) & 3;
            return visibleFourWay(index, legacy) == worldRot;
        }
        if (code >= SIDE_BASE && code < SIDE_BASE + 2) {
            int sideBit = code - SIDE_BASE;
            return (visibleFourWay(index, legacy) & 1) == sideBit; // side is orientation-invariant
        }
        if (code >= NETHERRACK_BASE) {
            int rel = code - NETHERRACK_BASE;
            int face = rel / 4;
            int rot = rel % 4;
            // World model index rotates with the grid: (yRot+turns)*4 + xRot.
            int xRot = index % 4, yRot = index / 4;
            int worldIndex = ((yRot + turns) & 3) * 4 + xRot;
            // The face we observe rotates yaw with the grid; up/down rotations shift with it.
            int worldFace = rotateFaceYaw(face, turns);
            int worldRot = switch (face) {
                case 0 -> (rot + turns) & 3;       // up advances
                case 1 -> (rot + 4 - turns) & 3;   // down retreats
                default -> rot;                     // side faces keep in-plane rotation
            };
            return NETHERRACK_FACE_ROTATIONS[worldIndex][worldFace] == worldRot;
        }
        return false;
    }

    /** Rotate a horizontal netherrack face clockwise around Y (up/down unchanged). N=2,E=4,S=3,W=5. */
    private static int rotateFaceYaw(int face, int turns) {
        int f = face;
        for (int i = 0; i < (turns & 3); i++) {
            f = switch (f) {
                case 2 -> 4; // north -> east
                case 4 -> 3; // east -> south
                case 3 -> 5; // south -> west
                case 5 -> 2; // west -> north
                default -> f; // up/down
            };
        }
        return f;
    }

    /** Encode a netherrack face observation (face 0-5, rot 0-3). */
    public static int netherrackCode(int face, int rot) {
        return NETHERRACK_BASE + face * 4 + (rot & 3);
    }

    /** Encode a side-face observation (bit 0/1). */
    public static int sideCode(int bit) {
        return SIDE_BASE + (bit & 1);
    }
}
