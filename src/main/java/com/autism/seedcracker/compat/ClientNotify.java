package com.autism.seedcracker.compat;

import autismclient.util.AutismClientMessaging;

/**
 * Obfuscation-proof notification shim. The client's AutismNotifications overlay util was renamed
 * in later (obfuscated) client builds, which broke every module that called it. AutismClientMessaging
 * survived across versions, so all notification calls route through it instead (a chat line with a
 * warning colour) - no dependency on the renamed class. One place to update if a stable overlay
 * API appears later.
 */
public final class ClientNotify {
    private ClientNotify() {}

    /** Show a warning to the user (was ClientNotify.warning). */
    public static void warning(String message) {
        try {
            AutismClientMessaging.sendPrefixed("§e" + message);
        } catch (Throwable ignored) {}
    }

    /** Show a success message (was ClientNotify.success). */
    public static void success(String message) {
        try {
            AutismClientMessaging.sendPrefixed("§a" + message);
        } catch (Throwable ignored) {}
    }

    /** Show an error to the user (was ClientNotify.error). */
    public static void error(String message) {
        try {
            AutismClientMessaging.sendPrefixed("§c" + message);
        } catch (Throwable ignored) {}
    }
}
