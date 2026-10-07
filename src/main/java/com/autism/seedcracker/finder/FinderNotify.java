package com.autism.seedcracker.finder;

import com.autism.seedcracker.compat.ClientNotify;

import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;

/** Shared finder flag notification (toast + prefixed chat + optional ping sound). */
public final class FinderNotify {
    private FinderNotify() {}

    /**
     * @param tag   chat prefix with colour code, e.g. "§6[NetherTunnel]"
     * @param msg   human message (also used for the toast)
     * @param sound play the XP-orb ping
     */
    public static void flag(String tag, String msg, boolean sound) {
        ClientNotify.warning(msg);
        AutismClientMessaging.sendPrefixed(tag + " §f" + msg);
        if (sound) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
    }
}
