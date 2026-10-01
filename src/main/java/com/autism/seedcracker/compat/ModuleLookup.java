package com.autism.seedcracker.compat;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import autismclient.modules.Module;

/**
 * Obfuscation-proof module registry for THIS addon's modules. The client's ModuleRegistry util
 * (get/all) was renamed in later (obfuscated) builds, breaking cross-module lookups (renderHud
 * guards, MacroProtector's disable-other-automation sweep). The addon registers every module it
 * owns, so it can keep its own references with zero client dependency: SeedcrackerAddon calls
 * track() on each module as it registers, and modules look each other up here by namespaced id.
 */
public final class ModuleLookup {
    private ModuleLookup() {}

    private static final Map<String, Module> BY_ID = new ConcurrentHashMap<>();

    /** Register a module instance (called by the addon entrypoint for every module). */
    public static void track(Module module) {
        if (module == null || module.id() == null) return;
        BY_ID.put(module.id(), module);
    }

    /** Look up a module by namespaced id (was ModuleRegistry.get). */
    public static Module get(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    /** All tracked modules (was ModuleRegistry.all). Only contains THIS addon's modules. */
    public static Collection<Module> all() {
        return BY_ID.values();
    }

    public static void clear() {
        BY_ID.clear();
    }
}
