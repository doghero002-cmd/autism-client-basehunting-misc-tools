package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.setup.Loadouts;

import autismclient.api.module.ActionSetting;
import autismclient.api.module.BoolSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;

/**
 * QQL Setup - the in-game "start here" panel. Instead of making a new user guess which of ~90
 * modules to enable, this module exposes one button per curated loadout (Stash Hunting, Tunnel
 * Base, Trading, PvP/Utility, Stay Safe) plus a "turn everything off" button.
 *
 * It does no work while enabled; the buttons do everything, so it disables itself after being
 * toggled on (same pattern as Base Log Browser).
 */
public final class QqlSetupModule extends Module {

    private final BoolSetting replace = add(new BoolSetting("replace", "Replace current setup", true)
        .description("ON: a loadout turns off every other addon module first, so you land on exactly that "
            + "setup. OFF: the loadout is layered on top of what you already have enabled.")
        .group("General"));

    public QqlSetupModule() {
        super(SeedcrackerAddon.ID + ":qql-setup", "QQL Setup",
            "One-click loadouts. Pick what you want to do and this enables the right modules for you.");

        // One button per loadout.
        for (Loadouts.Loadout l : Loadouts.all()) {
            add(new ActionSetting("apply-" + l.key, l.name, () -> apply(l))
                .buttonLabel("Apply")
                .description(l.summary)
                .group("Loadouts"));
        }

        add(new ActionSetting("print", "List in chat", Loadouts::printOverview)
            .buttonLabel("List")
            .description("Print every loadout and the modules it enables to chat.")
            .group("Loadouts"));

        add(new ActionSetting("off", "Turn everything off", this::turnEverythingOff)
            .buttonLabel("Disable all")
            .description("Switch off every addon module (clean slate / panic).")
            .group("Reset"));
    }

    private void apply(Loadouts.Loadout loadout) {
        AutismClientMessaging.sendPrefixed(Loadouts.apply(loadout, replace.get()));
    }

    private void turnEverythingOff() {
        AutismClientMessaging.sendPrefixed(Loadouts.disableAll());
    }

    // This module is a button panel, not a toggle: clicking it opens the settings (where the
    // loadout buttons live) instead of flipping an on/off state, and it never shows in the HUD
    // array list.
    @Override
    public boolean opensSettingsOnClick() {
        return true;
    }

    @Override
    public boolean hasActivationToggle() {
        return false;
    }

    @Override
    public boolean showInArrayList() {
        return false;
    }

    @Override
    public boolean emitsToggleMessage() {
        return false;
    }
}
