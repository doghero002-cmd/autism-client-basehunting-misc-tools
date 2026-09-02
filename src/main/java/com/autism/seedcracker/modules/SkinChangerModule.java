package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;

/**
 * Skin Changer (settings holder).
 *
 * Stores the target skin / cape configuration for client-side skin spoofing. Full skin spoofing
 * requires a mixin into the player renderer / GameProfile texture property, which lives outside
 * this module; this module keeps the user-facing settings and reports the configured skin name.
 *
 * Clean-room port of the obfuscated Zelith "SkinChanger" module. The original performed an async
 * Mojang API lookup and rewrote the rendered skin; here we keep the settings and intent only.
 */
public final class SkinChangerModule extends Module {

    private final StringSetting playerName = add(new StringSetting("player-name", "Skin source player", "")
        .description("Name of the player whose skin to copy (leave blank to use your own skin).")
        .group("General"));
    private final BoolSetting includeCape = add(new BoolSetting("include-cape", "Include cape", false)
        .description("Also spoof the target player's cape, if they have one.")
        .group("General"));
    private final BoolSetting slimModel = add(new BoolSetting("slim-model", "Slim (Alex) arms", false)
        .description("Use the slim-armed skin model instead of the classic model.")
        .group("General"));

    public SkinChangerModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-skin-changer", "Skin Changer", category,
            "Holds skin-spoof settings. True client-side skin replacement needs a renderer mixin.");
    }

    @Override
    public void onEnable() {
        String name = playerName.get().trim();
        Minecraft mc = Minecraft.getInstance();
        if (name.isEmpty()) {
            AutismClientMessaging.sendPrefixed("§7[Skin Changer] Using your own skin"
                + (mc.player != null ? " (§f" + mc.player.getName().getString() + "§7)" : "") + ".");
        } else {
            AutismClientMessaging.sendPrefixed("§7[Skin Changer] Would spoof skin of §f" + name
                + "§7 (cape: " + (includeCape.get() ? "on" : "off")
                + ", model: " + (slimModel.get() ? "slim" : "classic") + ").");
            AutismClientMessaging.sendPrefixed("§e[Skin Changer] Full skin spoofing is limited client-side; settings are stored only.");
        }
    }

    @Override
    public String info() {
        String name = playerName.get().trim();
        return name.isEmpty() ? "self" : name;
    }
}
