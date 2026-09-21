package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.modules.Module;
import autismclient.modules.ModuleRegistry;
import autismclient.util.AutismClientMessaging;

/**
 * TunnelBase Finder.
 *
 * Single, reliable entry point for autonomous DonutSMP base-hunting tunnels. Enabling this hands
 * control straight to the Water tunnel engine ({@link TunnelBaseWaterModule}) - the one mode that
 * doesn't flag the anti-cheat. The old straight-line XENON and 2x1 WALK engines both rubber-banded
 * on the live server, so they were removed; this module now exists only as the friendly
 * "TunnelBase Finder" toggle that drives the Water engine.
 *
 * Base detection (storage blocks / spawners), auto-mend, auto-eat, shop restock, hazard avoidance,
 * Y-recovery and the found-base disconnect all live in the Water engine.
 */
public final class TunnelBaseFinderModule extends Module {

    public TunnelBaseFinderModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":tunnel-base-finder", "TunnelBase Finder", category,
            "Digs a tunnel and alerts on bases using the Water engine (the reliable, non-flagging mode).");
    }

    @Override
    public void onEnable() {
        Module water = ModuleRegistry.get(SeedcrackerAddon.ID + ":tunnel-base-water");
        if (water == null) {
            AutismClientMessaging.sendPrefixed("§cTunnelBase Finder: Water engine module is missing.");
            setEnabledSilently(false);
            return;
        }
        if (!water.isEnabled()) water.setEnabled(true);
        AutismClientMessaging.sendPrefixed("§aTunnelBase Finder: Water engine running.");
        setEnabledSilently(false);
    }

    @Override
    public String info() {
        Module water = ModuleRegistry.get(SeedcrackerAddon.ID + ":tunnel-base-water");
        return water != null && water.isEnabled() ? "water engine on" : "water engine off";
    }
}
