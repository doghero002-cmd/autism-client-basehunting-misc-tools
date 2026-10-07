package com.autism.seedcracker.compat;

/**
 * Which addon API version to declare to the running AUTISM Client.
 *
 * The client skips an addon that declares a newer API than it provides ("needs API vN") and logs a warning
 * for one that declares an older API ("built against API vN"). The addon only uses calls that every
 * supported host provides (checked against 5.0 = API v3 and 5.1 / V4 = API v4), so it declares the
 * highest version both sides support: the host's own version, capped at the one we compiled against.
 */
public final class ApiCompat {
    private ApiCompat() {}

    /** The API version to declare, given the one we compiled against and the one the host reports. */
    public static int declared(int compiledAgainst, int host) {
        // A host that can't report a sane version gets what we were built for.
        if (host <= 0) return compiledAgainst;
        return Math.min(compiledAgainst, host);
    }
}
