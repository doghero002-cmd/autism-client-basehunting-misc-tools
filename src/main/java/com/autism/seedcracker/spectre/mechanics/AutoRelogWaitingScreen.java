package com.autism.seedcracker.spectre.mechanics;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;

/** Vanilla server list with immediate cancellation when the user leaves, edits or joins elsewhere. */
public final class AutoRelogWaitingScreen extends JoinMultiplayerScreen {
    private Runnable cancellation;

    public AutoRelogWaitingScreen(Screen parent, Runnable cancellation) {
        super(parent);
        this.cancellation = cancellation;
    }

    /** Release before the module's own connection handoff; the old screen can never rearm it. */
    public void release() {
        cancellation = null;
    }

    private void cancelPending() {
        Runnable cancel = cancellation;
        cancellation = null;
        if (cancel != null) cancel.run();
    }

    @Override
    public void onClose() {
        cancelPending();
        super.onClose();
    }

    @Override
    public void removed() {
        try {
            cancelPending();
        } finally {
            super.removed();
        }
    }
}
