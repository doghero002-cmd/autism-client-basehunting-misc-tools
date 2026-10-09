package com.autism.seedcracker.krypton;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Name Protect.
 *
 * Replaces your own username with a fake name in chat. Stream-safe name hiding, applied by the
 * same chat mixin that handles Fake Identity (FakeRolesChatMixin).
 *
 * Port of the Krypton "NameProtect" module to the AUTISM API (Mojang 26.2).
 * ponytail: chat only - tab list/nametag render would need their own mixins; add if asked.
 */
public final class NameProtectModule extends Module {

    public static NameProtectModule INSTANCE;

    private final StringSetting fakeName = add(new StringSetting("fake-name", "Fake name", "Player")
        .description("Name shown instead of your real username.")
        .group("General"));

    public NameProtectModule() {
        super(SeedcrackerAddon.ID + ":name-protect", "Name Protect",
            "Hide your real username (shows a fake name).");
        INSTANCE = this;
    }

    public static String fakeName() {
        return INSTANCE == null ? "Player" : INSTANCE.fakeName.get();
    }

    public static boolean active() {
        return INSTANCE != null && INSTANCE.isEnabled();
    }

    /** Replaces the local player's name in a chat component (flattens formatting on hit lines). */
    public static Component transform(Component original) {
        if (!active() || original == null) return original;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return original;
        String real = mc.player.getGameProfile().name();
        if (real == null || real.isBlank()) return original;
        String text = original.getString();
        if (!text.contains(real)) return original;
        return Component.literal(text.replace(real, fakeName()));
    }
}
