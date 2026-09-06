package com.autism.seedcracker.krypton;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.StringSetting;
import autismclient.modules.Module;

/**
 * Name Protect.
 *
 * Replaces your own username with a fake name wherever the client renders it (chat, tab list,
 * HUD). Stream-safe name hiding.
 *
 * Port of the Krypton "NameProtect" module to the AUTISM API (Mojang 26.2). The name replacement
 * is applied by a chat/render mixin reading fakeName().
 */
public final class NameProtectModule extends Module {

    public static NameProtectModule INSTANCE;

    private final StringSetting fakeName = add(new StringSetting("fake-name", "Fake name", "Player")
        .description("Name shown instead of your real username.")
        .group("General"));

    public NameProtectModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":name-protect", "Name Protect", category,
            "Hide your real username (shows a fake name).");
        INSTANCE = this;
    }

    public static String fakeName() {
        return INSTANCE == null ? "Player" : INSTANCE.fakeName.get();
    }

    public static boolean active() {
        return INSTANCE != null && INSTANCE.isEnabled();
    }
}
