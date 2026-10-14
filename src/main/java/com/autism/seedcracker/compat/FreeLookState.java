package com.autism.seedcracker.compat;

import autismclient.modules.Module;

public final class FreeLookState {
    private static volatile boolean active;
    private FreeLookState() {}
    public static void update(Module module) {
        if ("free-look".equals(module.id())) active = module.isEnabled();
    }
    public static boolean active() { return active; }
}