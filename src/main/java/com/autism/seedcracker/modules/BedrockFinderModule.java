package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.bedrock.BedrockFinderScreen;

import autismclient.api.module.ActionSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;

/**
 * Bedrock Finder module. Finds the player's world coordinates by matching a hand-drawn
 * bedrock-floor pattern (Y=-60) against the world seed. The pattern grid GUI is opened from
 * inside this module's settings (the "Open Bedrock Finder" button), so it lives in the normal
 * client module menu; the seed is auto-filled from the SeedCracker module's cracked seed.
 * The module can also be opened with the .bfinder command (uses the client's command prefix).
 */
public final class BedrockFinderModule extends Module {

    private final IntSetting radius = add(new IntSetting(
            "radius", "Search radius (chunks)", 100, 1, 10000, 1)
        .description("How many chunks around your current position to search for the pattern.")
        .group("General"));

    private final ActionSetting openGui = add(new ActionSetting(
            "open-gui", "Open Bedrock Finder", this::openScreen)
        .buttonLabel("Open Grid GUI")
        .description("Open the bedrock pattern grid to draw and search.")
        .group("General"));

    public BedrockFinderModule() {
        super(SeedcrackerAddon.ID + ":bedrockfinder", "Bedrock Finder",
            "Locates your coordinates from a bedrock pattern you draw. Open the grid GUI from settings.");
    }

    /** Default radius the GUI starts with (kept in sync with the module setting). */
    public int radius() {
        return radius.get();
    }

    private void openScreen() {
        Minecraft mc = Minecraft.getInstance();
        BedrockFinderScreen.lastRadius = String.valueOf(radius.get());
        mc.gui.setScreen(new BedrockFinderScreen(mc.gui.screen()));
    }
}
