package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.util.tunnel.LegitMovement;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Mace PVP.
 *
 * A subtle mace-burst assistant for DonutSMP-style mace fights. The mace's smash attack is a
 * crit that scales with fall distance (needs >= 1.5 blocks of fall to trigger), so the whole
 * kit is built around getting airborne, landing the crit, and getting out:
 *
 *  - AUTO MACE: when you're falling onto a target with enough fall distance, hit-selects the
 *    mace and swings the moment the smash window opens. Cooldown-aware and human-aimed, so it
 *    reads as a well-timed click, not a script.
 *  - STUNT SLAM (wind charge): throws a wind charge at your feet to launch yourself, then rides
 *    the fall into an auto-mace crit on the way down. Hold-to-launch or single pulse.
 *  - ELYTRA OUT: re-deploys the elytra after the slam so you glide away instead of trading.
 *
 * Subtlety is the point: every aim uses the {@link LegitMovement} human-rotation engine (eased,
 * jittered, never an instant snap), swings respect the attack cooldown, and nothing sends extra
 * packets. Everything is a toggle so you can run just the parts you want.
 */
public final class MacePvpModule extends Module {

    public enum AimStyle { LEGIT, INSTANT, NONE }
    public enum SlamMode { HOLD, PULSE }
    public enum EnchantPrefer { DENSITY, BREACH, WIND_BURST, ANY }

    // ---- auto mace ----
    private final BoolSetting autoMace = add(new BoolSetting("auto-mace", "Auto mace", true)
        .description("Hit-select the mace and swing when you have the fall distance for a smash crit on a target below you.")
        .group("Mace"));
    private final EnumSetting<EnchantPrefer> prefer = add(new EnumSetting<>("prefer", "Prefer enchant", EnchantPrefer.DENSITY, EnchantPrefer.values())
        .description("Anubis: auto-swap to the mace carrying the preferred smash enchant. DENSITY = raw smash damage, BREACH = armour pierce, WIND_BURST = re-launch on hit, ANY = first mace found.")
        .group("Mace"));
    private final BoolSetting weaponsOnly = add(new BoolSetting("weapons-only", "Only mace in hotbar", false)
        .description("Anubis: only auto-swap if the preferred mace is already in your hotbar (never pull it from the inventory grid). Off = also search the inventory.")
        .group("Mace"));
    private final BoolSetting swapBack = add(new BoolSetting("swap-back", "Swap back after swing", true)
        .description("Anubis: return to your previous held slot a few ticks after the swing so you're not left holding the mace.")
        .group("Mace"));
    private final IntSetting backDelay = add(new IntSetting("back-delay", "Swap-back delay (ticks)", 4, 1, 20, 1)
        .description("Ticks to hold the mace before swapping back.")
        .group("Mace").visibleWhen(() -> swapBack.get()));
    private final IntSetting targetRange = add(new IntSetting("target-range", "Target range", 5, 2, 8, 1)
        .description("Reach (blocks) to consider a player a valid crit target.")
        .group("Mace"));
    private final BoolSetting onlySmashReady = add(new BoolSetting("only-smash-ready", "Only when smash-ready", true)
        .description("Only swing when your fall distance is over the 1.5-block smash threshold (every hit is a crit). Off = swing whenever in range.")
        .group("Mace"));
    private final BoolSetting respectCooldown = add(new BoolSetting("respect-cooldown", "Respect attack cooldown", true)
        .description("Wait for the attack cooldown so each swing does full damage (reads human). Off = swing every tick.")
        .group("Mace"));
    private final EnumSetting<AimStyle> aimStyle = add(new EnumSetting<>("aim-style", "Aim style", AimStyle.LEGIT, AimStyle.values())
        .description("LEGIT = eased human aim toward the target before swinging. INSTANT = snap (fastest, more obvious). NONE = don't aim (you aim yourself).")
        .group("Mace"));

    // ---- stunt slam (wind charge) ----
    private final BoolSetting windCharge = add(new BoolSetting("wind-charge", "Wind charge slam", true)
        .description("Throw a wind charge at your feet to launch, then auto-mace crit on the way down.")
        .group("Slam"));
    private final EnumSetting<SlamMode> slamMode = add(new EnumSetting<>("slam-mode", "Slam trigger", SlamMode.PULSE, SlamMode.values())
        .description("HOLD = launch while the key/module is held. PULSE = one launch per activation (cleaner).")
        .group("Slam"));
    private final BoolSetting slamAimDown = add(new BoolSetting("slam-aim-down", "Aim down on launch", true)
        .description("Pitch down toward the target during the slam so the crit lands on them, not beside them.")
        .group("Slam"));

    // ---- elytra ----
    private final BoolSetting elytraOut = add(new BoolSetting("elytra-out", "Elytra re-deploy", true)
        .description("Re-open the elytra after the slam so you glide out instead of trading.")
        .group("Elytra"));
    private final IntSetting elytraMinFall = add(new IntSetting("elytra-min-fall", "Min fall to deploy", 6, 2, 20, 1)
        .description("Blocks of downward travel before the elytra re-deploys (avoids accidental deploys on small hops).")
        .group("Elytra"));

    private final LegitMovement look = new LegitMovement();
    private int launchTicks = -1;   // ticks since the wind-charge launch (-1 = not slamming)
    private int cooldownWait = 0;
    // Anubis swap-back state.
    private int returnSlot = -1;    // slot we were holding before the auto-swap (-1 = none)
    private int backTick = -1;      // tick to swap back on (-1 = none)

    public MacePvpModule() {
        super(SeedcrackerAddon.ID + ":mace-pvp", "Mace PVP",
            "Subtle mace-burst assistant: auto mace crits, wind-charge stunt slam, elytra re-deploy. Human aim, cooldown-aware.");
    }

    @Override
    public void onEnable() {
        look.reset();
        launchTicks = -1;
        cooldownWait = 0;
    }

    @Override
    public void onGameLeft() {
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (cooldownWait > 0) cooldownWait--;
        tickSwapBack(mc); // Anubis: return to the pre-swap held slot after the swing delay

        LivingEntity target = nearestTarget(mc);
        boolean slamming = launchTicks >= 0;

        // ---- wind-charge stunt slam ----
        if (windCharge.get() && !slamming && wantsLaunch(mc, target)) {
            doLaunch(mc);
        }
        if (slamming) {
            launchTicks++;
            if (launchTicks > 100) launchTicks = -1; // safety reset
        }

        // ---- elytra re-deploy after the slam (or any big fall) ----
        if (elytraOut.get() && !mc.player.onGround() && !mc.player.isFallFlying()
            && hasElytra(mc) && mc.player.fallDistance >= elytraMinFall.get()) {
            deployElytra(mc);
        }

        // ---- auto mace ----
        if (autoMace.get() && target != null && !mc.player.isFallFlying()) {
            boolean smashReady = mc.player.fallDistance >= 1.5f;
            if (!onlySmashReady.get() || smashReady) {
                tryAutoMace(mc, target);
            }
        }
    }

    // ---- auto mace core ----

    private void tryAutoMace(Minecraft mc, LivingEntity target) {
        double reach = targetRange.get();
        if (mc.player.distanceTo(target) > reach) return;
        if (respectCooldown.get() && mc.player.getAttackStrengthScale(0.5f) < 1.0f) return;
        if (cooldownWait > 0) return;

        // Anubis: pick the best mace (enchant-preference) instead of the first one found.
        int mace = bestMace(mc);
        if (mace < 0) return; // no mace available

        // Aim before swinging (subtle): LEGIT eases over a few ticks via the human engine,
        // INSTANT snaps. LEGIT swings only once we're close enough to the target angle.
        if (aimStyle.get() != AimStyle.NONE) {
            float[] goal = yawPitchTo(mc, target);
            if (aimStyle.get() == AimStyle.LEGIT) {
                float[] now = look.update(goal[0], goal[1]);
                mc.player.setYRot(now[0]);
                mc.player.setXRot(now[1]);
                float off = Math.abs(net.minecraft.util.Mth.wrapDegrees(goal[0] - now[0]))
                    + Math.abs(goal[1] - now[1]);
                if (off > 6.0f) return; // still easing on-target; swing next tick
            } else {
                mc.player.setYRot(goal[0]);
                mc.player.setXRot(goal[1]);
            }
        }

        selectMaceWithReturn(mc, mace); // remember return slot, then select the mace
        mc.gameMode.attack(mc.player, target);
        mc.player.swing(InteractionHand.MAIN_HAND);
        cooldownWait = respectCooldown.get() ? 2 : 1;
    }

    // ---- wind charge ----

    private boolean wantsLaunch(Minecraft mc, LivingEntity target) {
        if (findItem(mc, "minecraft:wind_charge") < 0) return false;
        if (slamMode.get() == SlamMode.PULSE) {
            // Pulse: launch only when the player is on the ground near a target (a clean opener).
            return mc.player.onGround() && target != null && mc.player.distanceTo(target) <= targetRange.get() + 1;
        }
        // HOLD: launch whenever the module is active and we're grounded near a target.
        return mc.player.onGround() && target != null;
    }

    private void doLaunch(Minecraft mc) {
        int wind = findItem(mc, "minecraft:wind_charge");
        if (wind < 0) return;
        com.autism.seedcracker.util.InvSync.select(mc, wind);
        // Aim straight down so the charge detonates at our feet = max self-launch.
        mc.player.setXRot(90f);
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        launchTicks = 0;
    }

    // ---- elytra ----

    private void deployElytra(Minecraft mc) {
        // The client re-deploys the elytra by pressing jump while airborne with elytra equipped.
        mc.options.keyJump.setDown(true);
        mc.execute(() -> mc.options.keyJump.setDown(false));
    }

    private boolean hasElytra(Minecraft mc) {
        ItemStack chest = mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
        return !chest.isEmpty() && BuiltInRegistries.ITEM.getKey(chest.getItem()).toString()
            .equals("minecraft:elytra");
    }

    // ---- helpers ----

    private LivingEntity nearestTarget(Minecraft mc) {
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le) || e == mc.player || !(e instanceof Player)) continue;
            if (le.isSpectator() || !le.isAlive()) continue;
            double d = mc.player.distanceTo(le);
            // Prefer targets below us (that's who we crit on the way down).
            if (d < bestDist && le.getY() <= mc.player.getY() + 2.0) {
                bestDist = d;
                best = le;
            }
        }
        return best;
    }

    private int findItem(Minecraft mc, String id) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(id)) return i;
        }
        return -1;
    }

    // ---- Anubis enchant-preference mace selection ----

    /** The registry id of the enchant we prefer on the mace, per the prefer setting. */
    private String preferredEnchantId() {
        return switch (prefer.get()) {
            case DENSITY -> "minecraft:density";
            case BREACH -> "minecraft:breach";
            case WIND_BURST -> "minecraft:wind_burst";
            case ANY -> null;
        };
    }

    /** Level of `enchantId` on the stack (0 = not present). Reads the item's stored enchantments. */
    private static int enchantLevel(ItemStack stack, String enchantId) {
        if (stack.isEmpty() || enchantId == null) return 0;
        try {
            var ench = stack.getEnchantments();
            for (var e : ench.entrySet()) {
                var keyOpt = e.getKey().unwrapKey();
                if (keyOpt.isPresent() && keyOpt.get().toString().equals(enchantId)) return e.getIntValue();
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    /**
     * Anubis: pick the BEST mace slot - prefer one carrying the chosen smash enchant (highest level
     * wins). Searches the hotbar first; if weapons-only is off, also searches the inventory grid
     * (returning a hotbar slot it can be swapped into). Returns a hotbar slot, or -1 if none.
     */
    private int bestMace(Minecraft mc) {
        String want = preferredEnchantId();
        int anyHotbar = -1, bestHotbar = -1, bestHotbarLvl = -1;
        int anyInv = -1, bestInv = -1, bestInvLvl = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s.isEmpty() || !BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals("minecraft:mace")) continue;
            boolean hotbar = i < 9;
            int lvl = want == null ? 1 : enchantLevel(s, want);
            if (hotbar) {
                if (anyHotbar < 0) anyHotbar = i;
                if (lvl > bestHotbarLvl) { bestHotbarLvl = lvl; bestHotbar = i; }
            } else {
                if (anyInv < 0) anyInv = i;
                if (lvl > bestInvLvl) { bestInvLvl = lvl; bestInv = i; }
            }
        }
        // Prefer a hotbar mace with the wanted enchant; else any hotbar mace; else inventory (if allowed).
        if (want != null && bestHotbarLvl > 0) return bestHotbar;
        if (anyHotbar >= 0) return anyHotbar;
        if (!weaponsOnly.get() && want != null && bestInvLvl > 0) return swapIntoHotbar(mc, bestInv);
        if (!weaponsOnly.get() && anyInv >= 0) return swapIntoHotbar(mc, anyInv);
        return -1;
    }

    /** Swap an inventory-grid stack into the selected hotbar slot (returns that hotbar slot). */
    private int swapIntoHotbar(Minecraft mc, int invSlot) {
        int selected = mc.player.getInventory().getSelectedSlot();
        com.autism.seedcracker.compat.ClientInventory.swapInventorySlots(mc, invSlot, selected);
        return selected;
    }

    /** Remember the slot to return to, then select the mace slot (Anubis swap pattern). Only records
     * a return slot when we're actually CHANGING slots - if you're already holding the mace there's
     * nothing to swap back to. */
    private void selectMaceWithReturn(Minecraft mc, int maceSlot) {
        int current = mc.player.getInventory().getSelectedSlot();
        if (swapBack.get() && returnSlot < 0 && current != maceSlot) {
            returnSlot = current;
            backTick = backDelay.get();
        }
        com.autism.seedcracker.util.InvSync.select(mc, maceSlot);
    }

    /** Swap back to the held slot after the swing delay (called from tick). Only acts if we recorded
     * a genuine slot change; if the player manually swapped away from the mace, respect that and
     * don't yank them back. */
    private void tickSwapBack(Minecraft mc) {
        if (returnSlot < 0) return;
        if (backTick > 0) { backTick--; return; }
        int current = mc.player.getInventory().getSelectedSlot();
        if (current != returnSlot) com.autism.seedcracker.util.InvSync.select(mc, returnSlot);
        returnSlot = -1;
        backTick = -1;
    }

    private float[] yawPitchTo(Minecraft mc, LivingEntity target) {
        double dx = target.getX() - mc.player.getX();
        double dz = target.getZ() - mc.player.getZ();
        double dy = (target.getY() + target.getBbHeight() * 0.5) - (mc.player.getY() + mc.player.getEyeHeight());
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
        float pitch = (float) (-(Mth.atan2(dy, dist) * Mth.RAD_TO_DEG));
        if (slamAimDown.get() && launchTicks >= 0) pitch = 55f; // look down-ish during the slam
        return new float[]{yaw, Mth.clamp(pitch, -90f, 90f)};
    }

    @Override
    public String info() {
        if (launchTicks >= 0) return "slamming";
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.fallDistance >= 1.5f) return "smash ready";
        return null;
    }
}
