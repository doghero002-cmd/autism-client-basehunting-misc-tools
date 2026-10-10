package com.autism.seedcracker.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Puts modules into their own GUI tabs.
 *
 * The client's public registerCategory(label) dedups by addon id (the custom
 * label never reaches the category registry), so every label an addon passes
 * collapses into one tab. Workaround: call the category class's static
 * find-or-create factory (String key, String label) directly with distinct
 * keys - distinct key = distinct tab - and write the result into the module's
 * category field BEFORE registration (the client only stamps its default
 * category when the field is still null).
 *
 * Everything is shape-probed reflection, zero client names (they differ across
 * 4.4/5.0/obfuscated 5.1): the category is the Module field whose type lives in
 * Module's package and owns a static (String,String) factory returning itself
 * with find-or-create semantics (same key twice = same instance, verified at
 * probe time). Any probe miss = silent no-op: modules stay in the default
 * addon tab, the old behavior.
 */
public final class CategoryAssigner {
    private static final Map<String, Object> TABS = new HashMap<>();
    private static Method factory; // static category factory(key, label)
    private static Field catField; // Module's category field
    private static boolean broken;

    private CategoryAssigner() {}

    public static void assign(Object module, String label) {
        if (broken) return;
        try {
            if (factory == null && !resolve(module.getClass(), label)) {
                broken = true;
                org.slf4j.LoggerFactory.getLogger("dogs-tabs")
                        .warn("category probe found no match; modules stay in one tab");
                return;
            }
            Object tab = TABS.get(label);
            if (tab == null) {
                tab = factory.invoke(null, key(label), "Dogs " + label);
                TABS.put(label, tab);
            }
            catField.set(module, tab);
        } catch (Throwable t) {
            broken = true;
            org.slf4j.LoggerFactory.getLogger("dogs-tabs").warn("category assign failed", t);
        }
    }

    private static String key(String label) {
        return "dogs-" + label.toLowerCase(Locale.ROOT).replace(' ', '-');
    }

    private static boolean resolve(Class<?> moduleClass, String label) {
        for (Class<?> c = moduleClass; c != null; c = c.getSuperclass()) {
            for (Field fd : c.getDeclaredFields()) {
                if (Modifier.isStatic(fd.getModifiers())) continue;
                Method f = categoryFactory(fd.getType(), c, label);
                if (f != null) {
                    fd.setAccessible(true);
                    catField = fd;
                    factory = f;
                    org.slf4j.LoggerFactory.getLogger("dogs-tabs").info("category probe: {}.{} via {}.{}",
                            c.getSimpleName(), fd.getName(), fd.getType().getSimpleName(), f.getName());
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The category type lives beside Module and owns a static (String,String)
     * find-or-create: calling it twice with the same key must return the SAME
     * instance (filters out look-alike factories that merely construct).
     */
    private static Method categoryFactory(Class<?> cat, Class<?> moduleClass, String label) {
        if (cat == moduleClass || cat.isPrimitive() || cat.getPackage() == null
                || !cat.getPackageName().equals(moduleClass.getPackageName())) return null;
        for (Method f : cat.getDeclaredMethods()) {
            if (Modifier.isStatic(f.getModifiers())
                    && f.getReturnType() == cat
                    && f.getParameterCount() == 2
                    && f.getParameterTypes()[0] == String.class
                    && f.getParameterTypes()[1] == String.class) {
                try {
                    f.setAccessible(true);
                    Object a = f.invoke(null, key(label), "Dogs " + label);
                    Object b = f.invoke(null, key(label), "Dogs " + label);
                    if (a != null && a == b) return f;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }
}