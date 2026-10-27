package com.autism.seedcracker.spectre.mechanics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RiptideInputTest {
    @Test void scanCodeResolutionNeverPollsInvalidGlfwKeyTokens() {
        for (int invalid : new int[] {-1, 0, 33, 38, 40, 43, 58, 60, 62, 64, 94, 97, 160, 163,
                255, 270, 279, 285, 289, 315, 319, 337, 339, 349}) {
            assertFalse(PhysicalUseKey.pollableGlfwKey(invalid), "invalid token " + invalid);
        }
        for (int valid : new int[] {32, 39, 44, 45, 46, 47, 48, 57, 59, 61, 65, 90, 91, 92, 93,
                96, 161, 162, 256, 269, 280, 284, 290, 314, 320, 336, 340, 348}) {
            assertTrue(PhysicalUseKey.pollableGlfwKey(valid), "valid token " + valid);
        }
    }
}
