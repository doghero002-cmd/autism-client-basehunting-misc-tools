package com.autism.seedcracker.modules;

import java.util.ArrayList;
import java.util.List;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.compat.ModuleLookup;

import autismclient.api.module.BoolSetting;
import autismclient.modules.Module;

/**
 * Finders - one menu entry that bundles the simple "set-and-forget" finder tools (the ones with
 * only a handful of settings you rarely touch) so they stop taking up menu space as six separate
 * modules. The heavily-tuned finders (Stash, Sus Chunk, Chunk, Prime Chunk, etc.) stay as their
 * own visible modules so their settings remain easy to reach.
 *
 * Each toggle drives the matching underlying finder's enabled state, kept in sync both ways so a
 * loadout or keybind that flips a finder is reflected here too. This module is a control panel
 * (no scan loop of its own), so it never shows in the on-screen ArrayList.
 */
public final class FindersModule extends Module {

    /** A finder the hub controls: its display name and module id (without the addon prefix). */
    private record Entry(String name, String shortId) {}

    /** One toggle per bundled finder. Only the low-settings, rarely-tuned finders are grouped. */
    private static final Entry[] FINDERS = {
        new Entry("Sign Finder", ":sign-finder"),
        new Entry("Portal Finder", ":portal-finder"),
        new Entry("Player Chunks", ":player-chunks"),
        new Entry("Structure Detector", ":structure-detector"),
        new Entry("Base Webhook", ":base-webhook"),
        new Entry("Finder Overlay", ":finder-overlay"),
    };

    /** A hub toggle bound to an underlying finder, with the last state we pushed/pulled. */
    private static final class Link {
        final BoolSetting toggle;
        final Module target;
        boolean last;

        Link(BoolSetting toggle, Module target) {
            this.toggle = toggle;
            this.target = target;
            this.last = target.isEnabled();
        }
    }

    private final List<Link> links = new ArrayList<>();

    public FindersModule() {
        super(SeedcrackerAddon.ID + ":finders", "Finders",
            "All base/stash chunk-finding tools in one place. Toggle a finder here; to fine-tune one, "
                + "it's still in the full module list (it's just no longer on the main menu).");

        for (Entry e : FINDERS) {
            Module finder = ModuleLookup.get(SeedcrackerAddon.ID + e.shortId());
            if (finder == null) continue;

            BoolSetting toggle = add(new BoolSetting("on" + e.shortId().replace(":", "-"), e.name(), finder.isEnabled())
                .description("Turn " + e.name() + " on/off. Fine settings for it live under its own entry.")
                .group("Finders"));
            toggle.keepOnReset();
            links.add(new Link(toggle, finder));
        }
    }

    @Override
    public void tick() {
        // Keep each hub toggle and its finder in sync, whichever side changed last.
        for (Link l : links) {
            boolean finderOn = l.target.isEnabled();
            if (l.last != finderOn) {
                // The finder changed externally (loadout/keybind) -> mirror it into the hub.
                l.toggle.set(finderOn);
                l.last = finderOn;
                continue;
            }
            boolean hub = l.toggle.get();
            if (hub != finderOn) {
                l.target.setEnabled(hub);
                l.last = l.target.isEnabled();
            }
        }
    }

    @Override
    public boolean ticksWhenDisabled() {
        // The hub is a control panel, not a feature: keep mirroring finder state even when "off".
        return true;
    }

    @Override
    public boolean hasDisabledTickWork() {
        return true;
    }

    @Override
    public boolean hasActivationToggle() {
        return false; // always a live panel; the per-finder toggles do the work
    }

    @Override
    public boolean showInArrayList() {
        return false; // never list "Finders" on-screen; the finders show themselves when active
    }

    @Override
    public boolean emitsToggleMessage() {
        return false;
    }
}
