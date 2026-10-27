/* SPDX-License-Identifier: GPL-3.0-only
 * Adapted with permission from Spectre-26.2, d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da,
 * net/spectre/client/module/impl/misc/AntiTrapModule.java (no per-file MIT notice present).
 * Native-addon deviations: retain every legacy setting and the module id; bound removal to the
 * old range, honor both removal switches, poll new entities on the game thread instead of an
 * event bus, and keep paced server attacks as an explicit, default-off legacy mode.
 */
package com.autism.seedcracker.modules;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.compat.ClientNotify;
import com.autism.seedcracker.spectre.mechanics.ProtectionSession;
import com.autism.seedcracker.spectre.mechanics.TrapRemovalPolicy;
import com.autism.seedcracker.util.ActionPacer;
import com.autism.seedcracker.util.ContainerMutex;
import com.autism.seedcracker.util.RelogPersistence;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;

public final class AntiTrapModule extends Module {
    private final BoolSetting attackEntities = add(new BoolSetting("attack-entities", "Attack entities (legacy)", false)
        .description("Send paced attacks instead of Spectre's client-side removal. Opt-in; changes the server world.").group("General"));
    private final BoolSetting removeExisting = add(new BoolSetting("remove-existing", "Remove existing", true)
        .description("Remove nearby existing traps locally on enable and every second.").group("General")
        .visibleWhen(() -> !attackEntities.get()));
    private final BoolSetting preventSpawn = add(new BoolSetting("prevent-spawn", "Prevent spawn", true)
        .description("Remove newly observed nearby traps locally on the next client tick.").group("General")
        .visibleWhen(() -> !attackEntities.get()));
    private final BoolSetting armorStands = add(new BoolSetting("armor-stands", "Armor stands", true)
        .description("Include armor stands.").group("Targets"));
    private final BoolSetting minecarts = add(new BoolSetting("minecarts", "Minecarts", true)
        .description("Include plain and other non-storage minecarts.").group("Targets"));
    private final BoolSetting chestMinecarts = add(new BoolSetting("chest-minecarts", "Chest minecarts", true)
        .description("Include chest minecarts.").group("Targets"));
    private final BoolSetting hopperMinecarts = add(new BoolSetting("hopper-minecarts", "Hopper minecarts", true)
        .description("Include hopper minecarts.").group("Targets"));
    private final IntSetting range = add(new IntSetting("range", "Range", 5, 1, 10, 1)
        .description("Maximum distance to a trap, in blocks. Attacks additionally obey vanilla reach.").group("General"));
    private final IntSetting cooldown = add(new IntSetting("cooldown", "Attack cooldown (ticks)", 10, 0, 60, 1)
        .description("Ticks between legacy-mode attacks; also subject to the shared action budget.").group("General")
        .visibleWhen(attackEntities::get));
    private final BoolSetting notify = add(new BoolSetting("notify", "Notifications", true)
        .description("Local chat and toast when traps are removed or attacked.").group("General"));

    private final ProtectionSession session = new ProtectionSession();
    private final TrapRemovalPolicy removal = new TrapRemovalPolicy();
    private int cooldownTicks;
    private int ticks;
    private int noticeTicks;
    private int pendingNotice;
    private boolean previousAttackMode;

    public AntiTrapModule() {
        super(SeedcrackerAddon.ID + ":z-anti-trap", "Anti Trap",
            "Removes nearby trap entities locally; optional paced legacy attacks.");
    }

    @Override public void onEnable() { reset(); }
    @Override public void onDisable() { reset(); }
    @Override public void onGameJoin() { reset(); }
    @Override public void onGameLeft() {
        reset();
        if (RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    private void reset() {
        session.clear();
        removal.clear();
        ticks = cooldownTicks = noticeTicks = pendingNotice = 0;
        previousAttackMode = attackEntities.get();
    }

    @Override public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (!isEnabled() || !mc.isSameThread()) return;
        if (mc.player == null || mc.level == null || mc.getConnection() == null || !mc.player.isAlive()) {
            reset();
            return;
        }
        if (!session.owns(mc.level, mc.player, mc.getConnection()) || previousAttackMode != attackEntities.get()) {
            reset();
            session.bind(mc.level, mc.player, mc.getConnection());
            removal.bind(session.token());
        }
        ticks++;
        if (cooldownTicks > 0) cooldownTicks--;
        if (noticeTicks > 0) noticeTicks--;
        List<Entity> targets = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!isTarget(entity)) continue;
            boolean remove = removal.observe(session.token(), entity.getUUID(), removeExisting.get(), preventSpawn.get(), ticks % 20 == 0);
            if (!TrapRemovalPolicy.removable(entity.level() == mc.level && mc.level.getEntity(entity.getId()) == entity,
                    entity == mc.getCameraEntity(), entity == mc.player, entity.hasIndirectPassenger(mc.player),
                    entity.isAlive() && !entity.isRemoved())) continue;
            if (entity.distanceToSqr(mc.player) > (double) range.get() * range.get()) continue;
            if (attackEntities.get() || remove) targets.add(entity);
        }
        removal.finishFrame();
        if (attackEntities.get()) {
            if (mc.gameMode == null || mc.gui.screen() != null || mc.player.isUsingItem() || cooldownTicks > 0
                    || ContainerMutex.containerBusy(mc) || ActionPacer.holding()) return;
            for (Entity entity : targets) {
                if (!mc.player.isWithinEntityInteractionRange(entity, 0) || !mc.player.hasLineOfSight(entity)) continue;
                if (!ActionPacer.tryAction()) return;
                mc.gameMode.attack(mc.player, entity);
                mc.player.swing(InteractionHand.MAIN_HAND);
                cooldownTicks = cooldown.get();
                if (notify.get()) announce("Attacked trap entity: " + entity.getName().getString());
                break;
            }
        } else {
            // Mutation is deferred until after the render-entity iterator, and only on this game thread.
            for (Entity entity : targets) entity.discard();
            pendingNotice += targets.size();
            if (pendingNotice > 0 && noticeTicks == 0) {
                if (notify.get()) announce("Removed " + pendingNotice + " nearby trap entities locally");
                pendingNotice = 0;
                noticeTicks = 20;
            }
        }
    }

    private boolean isTarget(Entity entity) {
        if (entity instanceof ArmorStand) return armorStands.get();
        if (entity instanceof MinecartHopper) return hopperMinecarts.get();
        if (entity instanceof MinecartChest) return chestMinecarts.get();
        return entity instanceof AbstractMinecart && minecarts.get();
    }

    private void announce(String message) {
        autismclient.util.AutismClientMessaging.sendPrefixed("§c[AntiTrap] §f" + message);
        ClientNotify.warning("AntiTrap: " + message);
    }
}
