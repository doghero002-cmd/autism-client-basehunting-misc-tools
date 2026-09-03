package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.baselog.BaseLogScreen;

import autismclient.api.module.ActionSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;

/**
 * Base Log Browser.
 *
 * Opens a GUI listing every base coordinate the RTP stash finder and the Relog Loader logged to
 * bases.txt. Supports filtering, sorting by newest/nearest, copy-coords, and deleting entries.
 * The module itself does nothing while toggled - enabling it simply opens the browser.
 */
public final class BaseLogBrowserModule extends Module {

    public BaseLogBrowserModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":base-log-browser", "Base Log Browser", category,
            "Browse bases logged to bases.txt. Enable (or press the button) to open the browser.");

        add(new ActionSetting("open", "Open Base Log Browser", BaseLogBrowserModule::open)
            .buttonLabel("Open")
            .description("Open the base log browser GUI.")
            .group("General"));
    }

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.setScreen(new BaseLogScreen(mc.gui.screen())));
    }

    @Override
    public void onEnable() {
        // Enabling the module just opens the GUI, then turns itself back off.
        open();
        setEnabledSilently(false);
    }
}
