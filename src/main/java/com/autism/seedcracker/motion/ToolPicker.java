package com.autism.seedcracker.motion;

import com.autism.seedcracker.util.InvSync;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Hotbar tool choice for path mining: fastest harvesting tool that isn't about to break. */
final class ToolPicker {
    private ToolPicker() {}

    /** Durability left (fraction) below which a tool is kept safe and never used. */
    private static final double SAFE_FRACTION = 0.05;

    /** Best hotbar slot for the block, or -1 when the current hand is already as good as it gets. */
    static int bestSlot(Minecraft mc, BlockState state) {
        if (mc.player == null) return -1;
        int current = mc.player.getInventory().getSelectedSlot();
        double bestScore = score(mc.player.getInventory().getItem(current), state);
        int best = -1;
        for (int slot = 0; slot < 9; slot++) {
            if (slot == current) continue;
            double s = score(mc.player.getInventory().getItem(slot), state);
            if (s > bestScore + 1e-6) {
                bestScore = s;
                best = slot;
            }
        }
        return best;
    }

    /** Fastest mining speed we can reach with the hotbar (for path costs). */
    static float bestSpeed(Minecraft mc, BlockState state) {
        float best = 1f;
        if (mc.player == null) return best;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack s = mc.player.getInventory().getItem(slot);
            if (!usable(s)) continue;
            best = Math.max(best, s.getDestroySpeed(state));
        }
        return best;
    }

    /**
     * Ticks to mine {@code state} with the best usable hotbar tool, following vanilla's
     * Player.getDestroySpeed: Efficiency, Haste/Conduit, Mining Fatigue, the break-speed attribute,
     * the underwater penalty and the airborne /5.
     */
    static double breakTicks(Minecraft mc, BlockState state, float hardness) {
        if (mc.player == null) return hardness <= 0 ? 1 : hardness * 100;
        return MiningProfile.capture(mc).breakTicks(state, hardness);
    }

    /**
     * The hotbar tools and mining-speed effects at one moment, so break times can be priced on the
     * planner thread without touching the live player. Same maths as vanilla Player.getDestroySpeed.
     */
    record MiningProfile(ItemStack[] tools, double[] efficiency, double multiplier) {
        static MiningProfile capture(Minecraft mc) {
            var p = mc.player;
            ItemStack[] tools = new ItemStack[9];
            double[] eff = new double[9];
            for (int slot = 0; slot < 9; slot++) {
                ItemStack s = p.getInventory().getItem(slot);
                // Worn-out tools are never used; an empty hand is the fallback.
                tools[slot] = !s.isEmpty() && !usable(s) ? null : s.copy();
                eff[slot] = tools[slot] == null || tools[slot].isEmpty() ? 0 : efficiencyBonus(mc, tools[slot]);
            }
            double mul = 1.0;
            if (net.minecraft.world.effect.MobEffectUtil.hasDigSpeed(p)) {
                mul *= 1.0 + (net.minecraft.world.effect.MobEffectUtil.getDigSpeedAmplification(p) + 1) * 0.2;
            }
            var fatigue = p.getEffect(net.minecraft.world.effect.MobEffects.MINING_FATIGUE);
            if (fatigue != null) {
                mul *= switch (fatigue.getAmplifier()) {
                    case 0 -> 0.3;
                    case 1 -> 0.09;
                    case 2 -> 0.0027;
                    default -> 8.1E-4;
                };
            }
            mul *= p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_BREAK_SPEED);
            if (p.isEyeInFluid(net.minecraft.tags.FluidTags.WATER)) {
                mul *= p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.SUBMERGED_MINING_SPEED);
            }
            // No airborne /5: path blocks are mined standing, even when the plan was made mid-jump.
            return new MiningProfile(tools, eff, mul);
        }

        double breakTicks(BlockState state, float hardness) {
            if (hardness <= 0) return 1;
            double best = 0;
            for (int slot = 0; slot < 9; slot++) {
                ItemStack s = tools[slot];
                if (s == null) continue;
                double speed = s.isEmpty() ? 1.0 : s.getDestroySpeed(state);
                if (speed > 1.0) speed += efficiency[slot];
                boolean h = !state.requiresCorrectToolForDrops() || !s.isEmpty() && s.isCorrectToolForDrops(state);
                // A harvesting tool always beats a faster one that drops nothing (the per-tick damage divisor is 30 vs 100).
                best = Math.max(best, speed / (h ? 30.0 : 100.0));
            }
            if (best <= 0) return Double.POSITIVE_INFINITY;
            double damagePerTick = best * multiplier / hardness;
            if (damagePerTick >= 1.0) return 1;
            return Math.ceil(1.0 / damagePerTick);
        }

        boolean canHarvest(BlockState state) {
            if (!state.requiresCorrectToolForDrops()) return true;
            for (ItemStack s : tools) if (s != null && !s.isEmpty() && s.isCorrectToolForDrops(state)) return true;
            return false;
        }
    }

    private static double efficiencyBonus(Minecraft mc, ItemStack s) {
        try {
            var reg = mc.level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
            var eff = reg.get(net.minecraft.world.item.enchantment.Enchantments.EFFICIENCY);
            if (eff.isEmpty()) return 0;
            int lvl = s.getEnchantments().getLevel(eff.get());
            return lvl > 0 ? lvl * lvl + 1 : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** True when some hotbar item can harvest the block (or it needs no tool). */
    static boolean canHarvest(Minecraft mc, BlockState state) {
        if (!state.requiresCorrectToolForDrops()) return true;
        return mc.player != null && MiningProfile.capture(mc).canHarvest(state);
    }

    /** Switch to the best tool; true if a swap happened this tick (wait a tick before mining). */
    static boolean equip(Minecraft mc, BlockState state) {
        int slot = bestSlot(mc, state);
        if (slot < 0) return false;
        InvSync.select(mc, slot);
        return true;
    }

    private static double score(ItemStack s, BlockState state) {
        if (s.isEmpty()) return 1.0;
        if (!usable(s)) return -1.0;
        double speed = s.getDestroySpeed(state);
        // Only harvesting tools count when the block needs one; otherwise it drops nothing and mines slowly.
        if (state.requiresCorrectToolForDrops() && !s.isCorrectToolForDrops(state)) speed = Math.min(speed, 1.0);
        return speed;
    }

    private static boolean usable(ItemStack s) {
        if (s.isEmpty() || s.getMaxDamage() <= 0) return !s.isEmpty();
        return (s.getMaxDamage() - s.getDamageValue()) > s.getMaxDamage() * SAFE_FRACTION;
    }
}
