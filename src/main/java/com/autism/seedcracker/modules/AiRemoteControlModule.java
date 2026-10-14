package com.autism.seedcracker.modules;

import autismclient.modules.Module;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.bridge.ClientAiControl;
import com.autism.seedcracker.compat.ClientNotify;

public final class AiRemoteControlModule extends Module {
    public AiRemoteControlModule() {
        super(SeedcrackerAddon.ID + ":ai-remote-control", "AI Remote Control", "Allows authenticated local tools to submit and monitor host AI objectives.");
    }

    @Override public void onEnable() {
        try { ClientAiControl.enable(); }
        catch (Exception | LinkageError error) {
            ClientNotify.warning("AI Remote Control needs the verified V4 AI-enabled client build; bridge unavailable.");
            setEnabledSilently(false);
        }
    }
    @Override public void onDisable() { ClientAiControl.disable(); }
    @Override public void onGameLeft() { ClientAiControl.disable(); setEnabledSilently(false); }
    @Override public void tick() { ClientAiControl.refresh(); }
}