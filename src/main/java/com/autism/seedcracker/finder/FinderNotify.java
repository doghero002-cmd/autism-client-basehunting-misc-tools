package com.autism.seedcracker.finder;

import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;

/** Shared finder flag notification (one prefixed chat line + optional ping sound). */
public final class FinderNotify {
    private FinderNotify() {}

    /**
     * @param tag   chat prefix with colour code, e.g. "§6[NetherTunnel]"
     * @param msg   human message
     * @param sound play the XP-orb ping
     */
    public static void flag(String tag, String msg, boolean sound) {
        // One line only: ClientNotify.warning() is also chat (the overlay shim), so calling both
        // double-posted every finder flag.
        AutismClientMessaging.sendPrefixed(tag + " §f" + msg);
        if (sound) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
    }
}
