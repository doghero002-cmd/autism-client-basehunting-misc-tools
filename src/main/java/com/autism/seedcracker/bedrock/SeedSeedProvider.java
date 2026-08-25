package com.autism.seedcracker.bedrock;

import java.util.Set;

import kaptainwutax.seedcrackerX.SeedCracker;

/** Reads the cracked world seed from the SeedCracker engine, if it has been found. */
final class SeedSeedProvider {
    private SeedSeedProvider() {}

    /** Returns the single cracked world seed, or null if not yet cracked. */
    static Long crackedSeed() {
        try {
            SeedCracker sc = SeedCracker.get();
            if (sc == null || sc.getDataStorage() == null) return null;
            Set<Long> seeds = sc.getDataStorage().getTimeMachine().worldSeeds;
            if (seeds != null && seeds.size() == 1) {
                return seeds.iterator().next();
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
