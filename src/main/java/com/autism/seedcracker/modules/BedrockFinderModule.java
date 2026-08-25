package com.autism.seedcracker.modules;

import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.bedrock.BedrockFinderScreen;
import net.minecraft.client.Minecraft;

/**
 * Bedrock Finder module. Finds the player's world coordinates by matching a hand-drawn
 * bedrock-floor pattern (Y=-60) against the world seed. Clicking the module opens the pattern
 * grid GUI; the seed is auto-filled from the SeedCracker module's cracked seed when available.
 */
public final class BedrockFinderModule extends Module {

    private final IntSetting radius = add(new IntSetting(
            "radius", "Search radius (chunks)", 100, 1, 10000, 1)
        .description("How many chunks around your current position to search for the pattern.")
        .group("General"));

    public BedrockFinderModule() {
        super(SeedcrackerAddon.ID + ":bedrockfinder", "Bedrock Finder",
            "Locates your coordinates from a bedrock pattern you draw. Click to open the grid GUI.");
    }

    /** Default radius the GUI starts with (kept in sync with the module setting). */
    public int radius() {
        return radius.get();
    }

    @Override
    public boolean opensSettingsOnClick() {
        return true;
    }

    @Override
    public void onEnable() {
        // Opening the module (clicking it) launches the grid GUI on the render thread.
        Minecraft mc = Minecraft.getInstance();
        BedrockFinderScreen.lastRadius = String.valueOf(radius.get());
        mc.execute(() -> mc.setScreenAndShow(new BedrockFinderScreen(null)));
        // Immediately toggle back off: the module acts as a button, not a persistent toggle.
        setEnabledSilently(false);
        AutismClientMessaging.sendPrefixed("§aBedrock Finder opened. Draw your bedrock pattern, then Search.");
    }

    @Override
    public boolean hasActivationToggle() {
        return false;
    }
}
