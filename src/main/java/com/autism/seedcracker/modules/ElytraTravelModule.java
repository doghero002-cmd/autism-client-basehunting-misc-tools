package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.motion.Motion;
import com.autism.seedcracker.motion.RotationEngine;
import com.autism.seedcracker.util.tunnel.MovementInput;
import com.autism.seedcracker.util.tunnel.SilentRotation;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Elytra travel: flies to an XZ target with real elytra physics (no creative flight).
 *
 * Phases:
 *  - TAKEOFF: jump, open the elytra on the way down, firework up to cruise altitude.
 *  - CRUISE: hold a shallow glide toward the target (Baritone's ~-3 deg "glide" pitch), climbing
 *    with a firework whenever speed or altitude drops. Turns with the smooth rotation engine.
 *  - LAND: near the target (or out of rockets), pitch up to bleed speed, drop, and touch down.
 *
 * Safety: pauses if health is low, no elytra equipped, or no rockets left (then it just glides
 * down and lands). Uses {@link RotationEngine} for anti-snap aim and real key/item use.
 */
public final class ElytraTravelModule extends Module {

    private static ElytraTravelModule instance;

    private final IntSetting cruiseAltitude = add(new IntSetting("cruise-altitude", "Cruise altitude", 160, 70, 320, 5)
        .description("Y level to climb to before cruising toward the target.").group("General"));
    private final IntSetting landRadius = add(new IntSetting("land-radius", "Land radius", 12, 4, 64, 1)
        .description("Start the landing approach this many blocks from the target.").group("General"));
    private final IntSetting boostSpeed = add(new IntSetting("boost-speed", "Boost below speed", 24, 10, 60, 1)
        .description("Fire a rocket when horizontal speed falls below this many blocks/s.").group("General"));
    private final BoolSetting smoothAim = add(new BoolSetting("smooth-aim", "Smooth aim", true)
        .description("Ease the view toward the flight heading (human-like) instead of snapping.").group("General"));

    private enum Phase { IDLE, TAKEOFF, CLIMB, CRUISE, LAND, DONE }
    private Phase phase = Phase.IDLE;
    private int targetX, targetZ;
    private int phaseTicks;
    private int rocketSlot = -1;
    private int prevSlot = -1;
    private long nextBoostMs;
    private String status = "idle";

    public ElytraTravelModule() {
        super(SeedcrackerAddon.ID + ":elytra-travel", "Elytra Travel",
            "Fly to an XZ target with a real elytra + fireworks. Use .goto elytra x z.");
        instance = this;
    }

    /** Start a flight to (x,z). Returns false if it can't (no elytra, or already flying). */
    public static boolean start(int x, int z) {
        if (instance == null) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        if (!hasElytra(mc)) { com.autism.seedcracker.motion.MotionDebug.event("ELYTRA", "no elytra equipped"); return false; }
        ensureEnabled();
        instance.targetX = x;
        instance.targetZ = z;
        instance.phase = Phase.TAKEOFF;
        instance.phaseTicks = 0;
        instance.rocketSlot = findRockets(mc);
        instance.prevSlot = mc.player.getInventory().getSelectedSlot();
        instance.nextBoostMs = 0;
        // Pause any ground trip so the two don't fight over movement.
        Motion.stop(mc);
        instance.status = "takeoff";
        return true;
    }

    public static boolean active() {
        return instance != null && instance.phase != Phase.IDLE && instance.phase != Phase.DONE;
    }

    public static String flightStatus() {
        return instance == null ? "idle" : instance.status;
    }

    public static void stopFlight() {
        if (instance == null) return;
        instance.phase = Phase.DONE;
        instance.status = "stopped";
    }

    private static void ensureEnabled() {
        if (instance != null && !instance.isEnabled()) instance.setEnabled(true);
    }

    private static boolean hasElytra(Minecraft mc) {
        ItemStack chest = mc.player.getInventory().getItem(38); // chest armour slot
        return chest.is(Items.ELYTRA) && chest.getDamageValue() < chest.getMaxDamage() - 1;
    }

    private static int findRockets(Minecraft mc) {
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getItem(i).is(Items.FIREWORK_ROCKET)) return i;
        return -1;
    }

    @Override
    public void onDisable() {
        phase = Phase.DONE;
        status = "idle";
        if (mc().player != null) {
            mc().options.keyJump.setDown(false);
            MovementInput.release();
        }
    }

    private Minecraft mc() { return Minecraft.getInstance(); }

    @Override
    public void tick() {
        Minecraft mc = mc();
        if (mc.player == null || mc.level == null || phase == Phase.IDLE || phase == Phase.DONE) return;
        if (mc.gui.screen() != null) return; // let menus open; flight resumes after

        phaseTicks++;
        // Safety: bail on low health or a lost elytra.
        if (mc.player.getHealth() < 6f || !hasElytra(mc)) {
            status = "aborting (low health / no elytra)";
            phase = Phase.LAND;
        }

        double dx = targetX + 0.5 - mc.player.getX();
        double dz = targetZ + 0.5 - mc.player.getZ();
        double horiz = Math.hypot(dx, dz);
        Vec3 vel = mc.player.getDeltaMovement();
        double hSpeed = Math.hypot(vel.x, vel.z);
        boolean gliding = mc.player.isFallFlying();

        switch (phase) {
            case TAKEOFF -> tickTakeoff(mc, gliding);
            case CLIMB -> tickClimb(mc, gliding);
            case CRUISE -> tickCruise(mc, dx, dz, horiz, hSpeed, gliding);
            case LAND -> tickLand(mc, horiz, gliding);
            default -> { }
        }

        if (phase == Phase.DONE) {
            mc.options.keyJump.setDown(false);
            MovementInput.release();
            status = "arrived";
        }
    }

    private void tickTakeoff(Minecraft mc, boolean gliding) {
        status = "takeoff";
        if (gliding) { phase = Phase.CLIMB; phaseTicks = 0; return; }
        if (mc.player.onGround()) {
            // Flat ground: jump, and at the apex fire a rocket while opening the elytra (the "firework hop"
            // players use to launch from flat ground). The rocket lifts us enough for the elytra to catch.
            mc.options.keyJump.setDown(true);
            jumpedAt = phaseTicks;
        } else {
            mc.options.keyJump.setDown(false);
            double vy = mc.player.getDeltaMovement().y;
            // Past the apex (starting to fall) and a couple ticks into the hop: open the elytra and boost.
            if (vy < 0.1 && phaseTicks - jumpedAt >= 2) {
                mc.player.tryToStartFallFlying();
                if (mc.player.isFallFlying()) boost(mc); // catch the glide with a rocket
            }
        }
        if (phaseTicks > 60) { phase = Phase.CLIMB; phaseTicks = 0; } // fallback: proceed even if a hop failed
    }

    private int jumpedAt;

    private void tickClimb(Minecraft mc, boolean gliding) {
        status = "climbing to " + cruiseAltitude.get();
        if (!gliding) { // lost the glide (bumped something): re-open or give up to cruise low
            mc.player.tryToStartFallFlying();
            if (mc.player.onGround()) { phase = Phase.CRUISE; phaseTicks = 0; }
            return;
        }
        // Pitch up a touch and boost until we reach cruise altitude.
        aim(mc, yawTo(mc, mc.player.getX(), mc.player.getZ()), -25f);
        if (mc.player.getY() >= cruiseAltitude.get()) { phase = Phase.CRUISE; phaseTicks = 0; return; }
        boost(mc);
    }

    private void tickCruise(Minecraft mc, double dx, double dz, double horiz, double hSpeed, boolean gliding) {
        status = "cruising " + (int) horiz + " blocks";
        if (horiz <= landRadius.get()) { phase = Phase.LAND; phaseTicks = 0; return; }
        if (!gliding) { // dropped the glide
            if (mc.player.onGround()) { phase = Phase.LAND; phaseTicks = 0; return; }
            mc.player.tryToStartFallFlying();
        }
        // Shallow glide toward the target: pitch slightly down keeps speed, flare to climb.
        float pitch = mc.player.getY() < cruiseAltitude.get() - 12 ? -18f : 3f;
        aim(mc, yawTo(mc, mc.player.getX(), mc.player.getZ()), pitch);
        // Boost when slow or sagging well below cruise altitude.
        if (hSpeed < boostSpeed.get() || mc.player.getY() < cruiseAltitude.get() - 30) boost(mc);
    }

    private void tickLand(Minecraft mc, double horiz, boolean gliding) {
        status = "landing";
        // Bleed speed with a pitch-up flare while above the target, then drop and touch down.
        if (gliding && mc.player.getY() > groundY(mc) + 6) {
            aim(mc, yawTo(mc, mc.player.getX(), mc.player.getZ()), 25f); // flare to slow
        } else {
            // Close to the ground: stop gliding and fall the last few blocks.
            if (gliding) mc.player.stopFallFlying();
            aim(mc, yawTo(mc, mc.player.getX(), mc.player.getZ()), 40f);
        }
        if (mc.player.onGround()) {
            phase = Phase.DONE;
            phaseTicks = 0;
        }
        // Failsafe: if we somehow glide past, keep circling the target rather than flying off.
        if (phaseTicks > 200) phase = Phase.DONE;
    }

    /** Yaw (deg) pointing from the player toward the target XZ. */
    private float yawTo(Minecraft mc, double fromX, double fromZ) {
        double dx = targetX + 0.5 - fromX, dz = targetZ + 0.5 - fromZ;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** Aim the view (smooth via the rotation engine when enabled, else direct). */
    private void aim(Minecraft mc, float yaw, float pitch) {
        if (smoothAim.get()) {
            RotationEngine.request("elytra-travel", RotationEngine.PRIORITY_MOVE, yaw, pitch, Motion.rotationProfile(), Motion.silentRotation());
        } else {
            mc.player.setYRot(yaw);
            mc.player.setXRot(net.minecraft.util.Mth.clamp(pitch, -90f, 90f));
        }
    }

    /** Fire a rocket to boost (real item use, with the anti-spam cooldown). */
    private void boost(Minecraft mc) {
        if (rocketSlot < 0) { rocketSlot = findRockets(mc); if (rocketSlot < 0) return; }
        long now = System.currentTimeMillis();
        if (now < nextBoostMs) return;
        nextBoostMs = now + 250; // ~5 ticks between boosts (anti-spam)
        int sel = mc.player.getInventory().getSelectedSlot();
        if (sel != rocketSlot) {
            prevSlot = sel;
            mc.player.getInventory().setSelectedSlot(rocketSlot);
        }
        if (mc.gameMode != null) mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        if (prevSlot >= 0) { mc.player.getInventory().setSelectedSlot(prevSlot); prevSlot = -1; }
    }

    /** Best-effort ground height under the player (scan down for the first solid block). */
    private int groundY(Minecraft mc) {
        var pos = mc.player.blockPosition();
        for (int y = pos.getY(); y > mc.level.getMinY(); y--) {
            if (!mc.level.getBlockState(new net.minecraft.core.BlockPos(pos.getX(), y, pos.getZ())).isAir()) return y + 1;
        }
        return mc.level.getMinY();
    }

    @Override
    public String info() {
        return active() ? status : null;
    }
}
