package com.autism.seedcracker.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shape-probe check against fakes mimicking the client hierarchy:
 * Module-package category type with a static (String,String) find-or-create.
 */
class CategoryAssignerTest {

    // --- fakes: same package = probe's "category lives beside Module" rule ---

    public static class FakeCategory {
        static final java.util.Map<String, FakeCategory> REG = new java.util.HashMap<>();
        final String key;
        final String label;

        FakeCategory(String key, String label) {
            this.key = key;
            this.label = label;
        }

        // find-or-create: same key twice = same instance
        public static FakeCategory register(String key, String label) {
            return REG.computeIfAbsent(key, k -> new FakeCategory(k, label));
        }
    }

    public static class FakeModule {
        FakeCategory category;

        public void assignCategory(FakeCategory c) {
            this.category = c;
        }
    }

    @Test
    void assignsDistinctTabsPerLabel() {
        FakeModule a = new FakeModule();
        FakeModule b = new FakeModule();
        FakeModule c = new FakeModule();

        CategoryAssigner.assign(a, "Trading");
        CategoryAssigner.assign(b, "Render");
        CategoryAssigner.assign(c, "Trading");

        assertNotNull(a.category, "probe should resolve on the fake hierarchy");
        assertNotNull(b.category);
        assertNotSame(a.category, b.category, "different labels = different tabs");
        assertSame(a.category, c.category, "same label = same tab");
        assertEquals("Dogs Trading", a.category.label);
        assertEquals("dogs-trading", a.category.key);
    }
}
