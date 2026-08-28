package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;

import java.util.ArrayList;
import java.util.List;

/**
 * Anti Trap.
 *
 * Attacks and removes "trap" entities placed around you by other players (armor stands and
 * minecart variants) that are used to trap/suffocate you. Clears them each tick while enabled.
 *
 * Clean-room port of the obfuscated Zelith "AntiTrap" module.
 */
public final class AntiTrapModule extends Module {

    private final BoolSetting armorStands = add(new BoolSetting("armor-stands", "Armor stands", true)
        .description("Attack nearby armor stands.").group("Targets"));
    private final BoolSetting minecarts = add(new BoolSetting("minecarts", "Minecarts", true)
        .description("Attack plain minecarts.").group("Targets"));
    private final BoolSetting chestMinecarts = add(new BoolSetting("chest-minecarts", "Chest minecarts", true)
        .description("Attack chest minecarts.").group("Targets"));
    private final BoolSetting hopperMinecarts = add(new BoolSetting("hopper-minecarts", "Hopper minecarts", true)
        .description("Attack hopper minecarts.").group("Targets"));
    private final IntSetting range = add(new IntSetting("range", "Range", 5, 1, 10, 1)
        .description("How far to look for trap entities.").group("General"));

    public AntiTrapModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-anti-trap", "Anti Trap", category,
            "Attacks armor stands and minecarts used to trap you.");
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        List<Entity> targets = new ArrayList<>();
        double rSq = (double) range.get() * range.get();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == null || !e.isAlive()) continue;
            if (!isTarget(e)) continue;
            if (e.distanceToSqr(mc.player) > rSq) continue;
            targets.add(e);
        }
        for (Entity e : targets) {
            mc.gameMode.attack(mc.player, e);
            mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }
    }

    private boolean isTarget(Entity e) {
        if (e instanceof ArmorStand) return armorStands.get();
        if (e instanceof MinecartHopper) return hopperMinecarts.get();
        if (e instanceof MinecartChest) return chestMinecarts.get();
        if (e instanceof Minecart) return minecarts.get();
        if (e instanceof AbstractMinecart) return minecarts.get(); // other variants fall under plain minecarts
        return false;
    }
}
