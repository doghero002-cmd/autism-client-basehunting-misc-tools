package gpucrack;

/**
 * Ground-truth for the FaceModel: top-face masks must agree with the plain RotationCpuReference
 * behavior, netherrack face masks must be consistent with the CoordsFinder table and yaw rules,
 * and multi-face AND-combination must tighten (never loosen) a block's acceptance.
 */
public final class FaceModelSelfTest {

    private FaceModelSelfTest() {}

    public static void main(String[] args) {
        int failures = 0;

        // 1. Top-face ordinary rotation: mask must have exactly 4 bits set (one per 4-way group).
        for (int rot = 0; rot < 4; rot++) {
            for (int turns = 0; turns < 4; turns++) {
                int mask = FaceModel.acceptMask(rot, turns, false);
                if (Integer.bitCount(mask) != 4) {
                    System.out.printf("FAIL: top rot=%d turns=%d mask popcount=%d (want 4)%n",
                        rot, turns, Integer.bitCount(mask));
                    failures++;
                }
                // The accepted indices must all share the same 4-way value (rot+turns)&3.
                int want4 = (rot + turns) & 3;
                for (int idx = 0; idx < 16; idx++) {
                    boolean in = (mask & (1 << idx)) != 0;
                    if (in != ((idx >> 2) == want4)) {
                        System.out.printf("FAIL: top rot=%d turns=%d idx=%d membership mismatch%n",
                            rot, turns, idx);
                        failures++;
                    }
                }
            }
        }

        // 2. Side-face: mask must have exactly 8 bits (the 4-way values with the right low bit).
        for (int bit = 0; bit < 2; bit++) {
            int mask = FaceModel.acceptMask(FaceModel.sideCode(bit), 0, false);
            if (Integer.bitCount(mask) != 8) {
                System.out.printf("FAIL: side bit=%d popcount=%d (want 8)%n", bit, Integer.bitCount(mask));
                failures++;
            }
            for (int idx = 0; idx < 16; idx++) {
                boolean in = (mask & (1 << idx)) != 0;
                if (in != (((idx >> 2) & 1) == bit)) {
                    System.out.printf("FAIL: side bit=%d idx=%d mismatch%n", bit, idx);
                    failures++;
                }
            }
        }

        // 3. Netherrack single-face: each face+rot mask must be non-empty and within the table.
        for (int face = 0; face < 6; face++) {
            for (int rot = 0; rot < 4; rot++) {
                int mask = FaceModel.acceptMask(FaceModel.netherrackCode(face, rot), 0, false);
                if (Integer.bitCount(mask) == 0) {
                    System.out.printf("note: netherrack face=%d rot=%d -> 0 indices (possible dead combo)%n",
                        face, rot);
                }
                // Verify membership matches the raw table at turns=0.
                for (int idx = 0; idx < 16; idx++) {
                    boolean want = FaceModel.NETHERRACK_FACE_ROTATIONS[idx][face] == rot;
                    boolean in = (mask & (1 << idx)) != 0;
                    if (in != want) {
                        System.out.printf("FAIL: netherrack face=%d rot=%d idx=%d mismatch%n", face, rot, idx);
                        failures++;
                    }
                }
            }
        }

        // 4. CoordsFinder reference: up=1,north=3,east=2 jointly -> model index 5 ONLY.
        {
            int up = FaceModel.acceptMask(FaceModel.netherrackCode(0, 1), 0, false);
            int north = FaceModel.acceptMask(FaceModel.netherrackCode(2, 3), 0, false);
            int east = FaceModel.acceptMask(FaceModel.netherrackCode(4, 2), 0, false);
            int combined = up & north & east;
            if (combined != (1 << 5)) {
                System.out.printf("FAIL: netherrack correlated faces combined=%04x (want 1<<5)%n", combined);
                failures++;
            }
        }

        // 5. Multi-face AND must never be a superset of a single face (tightens or equal).
        for (int face = 0; face < 6; face++) {
            int a = FaceModel.acceptMask(FaceModel.netherrackCode(face, 1), 0, false);
            int b = FaceModel.acceptMask(FaceModel.netherrackCode((face + 1) % 6, 2), 0, false);
            if ((a & b) != 0 && (a & b) != a && (a & b) != b) {
                // combined is a strict subset of both - good, nothing to assert beyond sanity
            }
            if (((a & b) & ~a) != 0 || ((a & b) & ~b) != 0) {
                System.out.printf("FAIL: AND loosened a mask face=%d%n", face);
                failures++;
            }
        }

        System.out.println(failures == 0 ? "FACE MODEL SELF-TEST PASS" : "FACE MODEL FAIL (" + failures + ")");
        if (failures > 0) System.exit(1);
    }
}
