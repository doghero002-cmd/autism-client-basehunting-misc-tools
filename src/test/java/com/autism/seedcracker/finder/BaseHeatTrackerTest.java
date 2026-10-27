package com.autism.seedcracker.finder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BaseHeatTrackerTest {
    @BeforeEach void reset() { BaseHeatTracker.clear(); BaseHeatTracker.discardPending(); }
    @AfterEach void clean() { BaseHeatTracker.clear(); BaseHeatTracker.discardPending(); }

    @Test void onlyCellsAreImportedNotUnrelatedNumbersElsewhereInTheDocument() throws Exception {
        apply("{\"cells\":{\"41\":2},\"metadata\":{\"1\":999},\"cellBlocks\":50000}");
        assertEquals(2, BaseHeatTracker.finds(41));
        assertEquals(0, BaseHeatTracker.finds(1));
        assertEquals(1f, BaseHeatTracker.heat(41));
    }

    @Test void malformedOrIncompatibleDataCannotEraseTheLastValidMap() throws Exception {
        apply("{\"cells\":{\"41\":7},\"cellBlocks\":50000}");
        for (String bad : new String[]{"not json", "{\"cells\":{\"41\":1}", "[]",
                "{\"cells\":{\"41\":2},\"cellBlocks\":1000}",
                "{\"cells\":{\"41\":-1}}", "{\"cells\":{\"41\":1.5}}",
                "{\"cells\":{\"1\":2,\"41\":9999999999999999999999}}"}) {
            assertDoesNotThrow(() -> apply(bad), bad);
            assertEquals(7, BaseHeatTracker.finds(41), bad);
            assertEquals(0, BaseHeatTracker.finds(1), bad);
        }
    }

    @Test void anExplicitEmptyMapClearsGlobalHeatAndTheLegacyFlatMapStillWorks() throws Exception {
        apply("{\"41\":3}");
        assertEquals(3, BaseHeatTracker.finds(41));
        apply("{\"cells\":{}}");
        assertEquals(0, BaseHeatTracker.finds(41));
    }

    @Test void localAndGlobalCountsShareTheSameNormalizationWithoutOverflow() throws Exception {
        apply("{\"cells\":{\"41\":10,\"42\":10}}");
        BaseHeatTracker.recordFind(0, 0, 50000);
        assertEquals(11, BaseHeatTracker.finds(41));
        assertEquals(10f / 11f, BaseHeatTracker.heat(42), 0.00001f);
        BaseHeatTracker.clear();
        apply("{\"cells\":{\"41\":2147483647}}");
        BaseHeatTracker.recordFind(0, 0, 50000);
        assertEquals(Integer.MAX_VALUE, BaseHeatTracker.finds(41));
        assertEquals(1f, BaseHeatTracker.heat(41));
    }

    @Test void invalidUrlNeverEscapesTheRenderCallback() {
        assertDoesNotThrow(() -> BaseHeatTracker.refreshGlobal("https://bad uri", 50000));
        assertDoesNotThrow(() -> BaseHeatTracker.refreshGlobal("file:///not-a-remote-map.json", 50000));
    }

    @Test void clearingTheHeatmapAlsoDiscardsUnconfirmedFinds() {
        BaseHeatTracker.queueFind(0, 0, 50000);
        BaseHeatTracker.clear();
        assertEquals(0, BaseHeatTracker.pendingCount());
        assertEquals(0, BaseHeatTracker.confirmPending());
        assertEquals(0, BaseHeatTracker.finds(41));
    }

    private static void apply(String json) throws Exception {
        Method parse = BaseHeatTracker.class.getDeclaredMethod("parseGlobal", String.class, int.class);
        parse.setAccessible(true);
        try {
            parse.invoke(null, json, 50000);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            throw error;
        }
    }
}
