package com.example.donutflipscanner.api;

import java.util.Optional;

/**
 * Supplies a defensive copy of the configured API key.
 *
 * <p>The caller owns the returned array and must clear it after use. This
 * boundary lets the future configuration system provide credentials without
 * making the HTTP layer aware of configuration persistence.</p>
 */
@FunctionalInterface
public interface ApiCredentialsProvider {
    Optional<char[]> copyApiKey();
}
