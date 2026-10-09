package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;

/**
 * Parkour.
 *
 * Auto-jumps the moment you reach the edge of a block, so you can run jump courses
 * (or bridge gaps while exploring) without timing the jump key yourself. You keep
 * full control of direction and sprint; this only presses jump at the exact edge.
 */
public final class ParkourModule extends Module {

    private final BoolSetting requireMove = add(new BoolSetting("only-moving", "Only while moving", true)
        .description("Only auto-jump when you are actually walking forward, not when idling on an edge.")
        .group("General"));

    public ParkourModule() {
        super(SeedcrackerAddon.ID + ":parkour", "Parkour",
            "Automatically jumps when you reach the edge of a block.");
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        if (!p.onGround() || p.isShiftKeyDown() || p.isUsingItem()) return;
        if (mc.options.keyJump.isDown()) return; // player is jumping manually
        if (requireMove.get() && (p.input == null || !p.input.keyPresses.forward())) return;

        // Edge test (classic): shift the collision box half a block down; if nothing is
        // there, the next step walks off the edge, so jump now.
        AABB below = p.getBoundingBox().move(0, -0.5, 0).deflate(0.001, 0, 0.001);
        if (mc.level.noCollision(p, below)) {
            p.jumpFromGround();
        }
    }
}
