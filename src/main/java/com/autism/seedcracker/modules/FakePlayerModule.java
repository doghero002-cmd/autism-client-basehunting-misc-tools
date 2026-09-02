package com.autism.seedcracker.modules;

import java.util.UUID;

import com.mojang.authlib.GameProfile;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;

/**
 * Fake Player.
 *
 * Spawns a client-side fake player entity at your position as a decoy. The fake copies your
 * position, rotation, and pose so it looks like a real player standing where you were, and is
 * removed again when the module is disabled. Only you can see it (it is never sent to the
 * server).
 *
 * Clean-room port of the obfuscated Zelith "FakePlayer" module.
 */
public final class FakePlayerModule extends Module {

    private final StringSetting playerName = add(new StringSetting("name", "Name", "FakePlayer")
        .description("The nametag shown above the fake player.")
        .group("General"));

    private RemotePlayer fake;

    public FakePlayerModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-fake-player", "Fake Player", category,
            "Spawns a client-side fake player decoy at your position.");
    }

    @Override
    public void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        String name = playerName.get();
        if (name == null || name.isBlank()) name = "FakePlayer";
        GameProfile profile = new GameProfile(UUID.randomUUID(), name.trim());

        RemotePlayer decoy = new RemotePlayer(mc.level, profile);
        decoy.snapTo(mc.player.getX(), mc.player.getY(), mc.player.getZ(),
            mc.player.getYRot(), mc.player.getXRot());
        decoy.setYHeadRot(mc.player.getYHeadRot());
        decoy.setYBodyRot(mc.player.getYRot());
        decoy.setPose(mc.player.getPose());
        decoy.setSprinting(mc.player.isSprinting());
        mc.level.addEntity(decoy);
        fake = decoy;
    }

    @Override
    public void onDisable() {
        if (fake != null) {
            fake.discard();
            fake = null;
        }
    }

    @Override
    public void onGameLeft() {
        // The world is gone; just drop our reference so we don't hold a stale entity.
        fake = null;
    }
}
