package com.autism.seedcracker.compat;

import java.lang.reflect.Method;

import autismclient.api.AutismAddons;
import autismclient.modules.Module;

/**
 * Obfuscation-proof per-tab category assignment. The category TYPE is ModuleCategory in the
 * unobfuscated client and a renamed class (e.g. KeyHolder) in obfuscated builds, so the addon can't
 * reference it at compile time. This shim resolves the client's category factory + the Module's
 * category-assign method BY REFLECTION at runtime, so the same jar restores per-tab categories on
 * every client version. If the reflective lookup ever fails (a future rename), it degrades to the
 * auto-category (one tab) instead of crashing - the modules still load.
 */
public final class CategoryAssigner {
    private CategoryAssigner() {}

    private static volatile boolean probed = false;
    private static Method registerCategoryMethod; // AutismAddons$Modules.registerCategory(String)
    private static Method assignCategoryMethod;   // Module.assignCategory(<categoryType>)
    private static boolean usable = false;

    /** Assign a module to a named tab (e.g. "Finders"). No-ops to the auto-category if the
     * reflective category lookup is unavailable on this client. */
    public static void assign(Module module, String tabLabel) {
        if (module == null || tabLabel == null || tabLabel.isBlank()) return;
        if (!probe()) return;
        try {
            Object category = registerCategoryMethod.invoke(AutismAddons.modules(), tabLabel);
            if (category != null) {
                assignCategoryMethod.invoke(module, category);
            }
        } catch (Throwable t) {
            usable = false; // stop trying after the first failure (avoid log spam)
        }
    }

    /** True if per-tab categories are available on this client (for an info line / debugging). */
    public static boolean available() {
        return probe();
    }

    private static synchronized boolean probe() {
        if (probed) return usable;
        probed = true;
        try {
            // 1) The category factory: AutismAddons.modules().registerCategory(String) -> <categoryType>
            Class<?> modulesClass = AutismAddons.modules().getClass();
            Method regCat = null;
            for (Method m : modulesClass.getMethods()) {
                if (m.getName().equals("registerCategory") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == String.class) {
                    regCat = m;
                    break;
                }
            }
            if (regCat == null) { usable = false; return false; }
            Class<?> categoryType = regCat.getReturnType();

            // 2) The Module method that takes that category type (assignCategory, package-private).
            Method assign = null;
            for (Method m : Module.class.getDeclaredMethods()) {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(categoryType)) {
                    assign = m;
                    break;
                }
            }
            if (assign == null) { usable = false; return false; }
            assign.setAccessible(true);

            registerCategoryMethod = regCat;
            assignCategoryMethod = assign;
            usable = true;
        } catch (Throwable t) {
            usable = false;
        }
        return usable;
    }
}
