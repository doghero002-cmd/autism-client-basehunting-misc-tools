package com.autism.seedcracker.util.pure;

import com.autism.seedcracker.SeedcrackerAddon;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class RegionMapImageStateTest {

    @Test
    void bundledMapMatchesItsIdentifierAndDecodesCompletely() throws Exception {
        String resource = "/assets/" + SeedcrackerAddon.ID + "/" + RegionMapImageState.BUILTIN_TEXTURE;
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            var image = ImageIO.read(in);
            assertNotNull(image, "Bundled map must be a readable PNG");
            assertEquals(372, image.getWidth());
            assertEquals(372, image.getHeight());
        }
    }

    @Test
    void customImageIsOptInAndBlankPathsSelectTheBuiltinMap() {
        var state = new RegionMapImageState();
        assertFalse(state.select(false, "regionmap.png"));
        assertFalse(state.select(true, null));
        assertFalse(state.select(true, " \t "));
        assertEquals("", state.path());
    }

    @Test
    void failedAttemptIsNotRepeatedEachFrame() {
        var state = new RegionMapImageState();
        assertTrue(state.select(true, "missing.png"));
        for (int frame = 0; frame < 1000; frame++) {
            assertFalse(state.select(true, "missing.png"));
        }
        assertEquals("missing.png", state.path());
    }

    @Test
    void togglingOffOrClearingThePathAllowsAnExplicitRetry() {
        var state = new RegionMapImageState();
        assertTrue(state.select(true, "regionmap.png"));
        assertTrue(state.select(false, "regionmap.png"));
        assertEquals("", state.path());
        assertFalse(state.select(false, "regionmap.png"));
        assertTrue(state.select(true, "regionmap.png"));
        assertTrue(state.select(true, " "));
        assertEquals("", state.path());
        assertTrue(state.select(true, "regionmap.png"));
    }

    @Test
    void pathChangesAndLifecycleResetAllowReloadWithoutChangingPathSyntax() {
        var state = new RegionMapImageState();
        assertTrue(state.select(true, "  folder\\my map.png  "));
        assertEquals("folder\\my map.png", state.path());
        assertFalse(state.select(true, "folder\\my map.png"));
        assertTrue(state.select(true, " C:\\maps\\regionmap.png "));
        assertEquals("C:\\maps\\regionmap.png", state.path());
        state.reset();
        assertEquals("", state.path());
        assertTrue(state.select(true, "C:\\maps\\regionmap.png"));
    }
}
