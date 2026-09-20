package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.modules.Module;

/**
 * Scoreboard Hider.
 *
 * Hides the scoreboard sidebar (the objective list servers pin to the right edge of the
 * screen). Render-only: the scoreboard data still arrives and other modules can read it -
 * only the drawing is cancelled, so there is nothing a server can detect.
 */
public final class ScoreboardHiderModule extends Module {

    private static volatile boolean hide = false;

    /** Read by the Hud mixin every frame. */
    public static boolean shouldHide() {
        return hide;
    }

    public ScoreboardHiderModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":scoreboard-hider", "Scoreboard Hider", category,
            "Hides the server's scoreboard sidebar (render-only, undetectable).");
    }

    @Override
    public void onEnable() {
        hide = true;
    }

    @Override
    public void onDisable() {
        hide = false;
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }
}
