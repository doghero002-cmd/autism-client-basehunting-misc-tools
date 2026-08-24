package com.autism.seedcracker;

import kaptainwutax.seedcrackerX.SeedCracker;
import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric client entrypoint for the addon.
 *
 * The heavy lifting (config load, feature init, finder events, /seedcracker client
 * commands, database fetch) is delegated to the SeedCrackerX engine, which stays in
 * its own package and registers everything through Fabric exactly like the original mod.
 */
public final class SeedcrackerInit implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        new SeedCracker().onInitialize();
    }
}
