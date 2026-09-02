package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Auto Tool.
 *
 * While you hold the attack button, automatically swaps your hotbar selection to the best tool
 * for whatever you're pointing at: the fastest tool for the block under the crosshair, or the
 * highest-damage weapon for a targeted entity.
 *
 * Clean-room port of the obfuscated Zelith "AutoTool" module.
 */
public final class AutoToolModule extends Module {

    private final BoolSetting forBlocks = add(new BoolSetting("blocks", "Best tool for blocks", true)
        .description("Swap to the fastest mining tool when breaking blocks.")
        .group("General"));
    private final BoolSetting forEntities = add(new BoolSetting("entities", "Best weapon for entities", true)
        .description("Swap to the highest attack-damage weapon when attacking an entity.")
        .group("General"));
    private final BoolSetting requireCorrectTool = add(new BoolSetting("correct-tool", "Only if it can drop", false)
        .description("Only consider tools that are the 'correct' tool for the block (so it drops).")
        .group("General"));

    public AutoToolModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-auto-tool", "Auto Tool", category,
            "Automatically swaps to the best hotbar tool for the block or entity you're attacking.");
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.options == null) return;
        if (!mc.options.keyAttack.isDown()) return;
        HitResult hit = mc.hitResult;
        if (hit == null) return;

        if (hit.getType() == HitResult.Type.ENTITY && hit instanceof EntityHitResult entityHit) {
            if (forEntities.get() && entityHit.getEntity() != null) {
                selectBestWeapon(mc);
            }
        } else if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit) {
            if (forBlocks.get()) {
                selectBestTool(mc, mc.level.getBlockState(blockHit.getBlockPos()));
            }
        }
    }

    /** Swap to the hotbar slot with the fastest destroy speed for the given block. */
    private void selectBestTool(Minecraft mc, BlockState state) {
        int bestSlot = -1;
        double bestSpeed = -1.0;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            double speed = miningScore(stack, state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = slot;
            }
        }
        if (bestSlot == -1) return;
        double current = miningScore(mc.player.getMainHandItem(), state);
        if (bestSpeed > current) {
            mc.player.getInventory().setSelectedSlot(bestSlot);
        }
    }

    /** Swap to the hotbar slot with the highest attack damage. */
    private void selectBestWeapon(Minecraft mc) {
        int bestSlot = -1;
        double bestDamage = Double.NEGATIVE_INFINITY;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            double damage = attackScore(stack);
            if (damage > bestDamage) {
                bestDamage = damage;
                bestSlot = slot;
            }
        }
        if (bestSlot != -1 && bestSlot != mc.player.getInventory().getSelectedSlot()) {
            mc.player.getInventory().setSelectedSlot(bestSlot);
        }
    }

    /**
     * Mining effectiveness of a stack against a block. Returns -1 when the stack can't harvest
     * the block (so it's never picked), otherwise the destroy speed.
     */
    private double miningScore(ItemStack stack, BlockState state) {
        if (stack.isEmpty()) return -1.0;
        if (!isTool(stack)) return -1.0;
        if (requireCorrectTool.get() && !stack.isCorrectToolForDrops(state)) return -1.0;
        // A sword is a poor general-purpose mining tool unless the block is a web.
        if (itemId(stack).endsWith("_sword") && !state.is(Blocks.COBWEB)) return -1.0;
        float speed = stack.getDestroySpeed(state);
        if (speed <= 1.0f) return -1.0; // no bonus over hand
        return speed;
    }

    /** Attack damage of a stack, falling back to tier heuristics for swords/axes. */
    private double attackScore(ItemStack stack) {
        double damage = attributeAttackDamage(stack);
        if (damage > 0.0) return damage;
        String id = itemId(stack);
        if (id.endsWith("_sword")) return 10.0 + tierBonus(id);
        if (id.endsWith("_axe")) return 5.0 + tierBonus(id);
        return damage;
    }

    private double attributeAttackDamage(ItemStack stack) {
        ItemAttributeModifiers modifiers = stack.get(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS);
        if (modifiers == null) return 0.0;
        double total = 0.0;
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            if (entry.attribute().is(Attributes.ATTACK_DAMAGE)) {
                AttributeModifier modifier = entry.modifier();
                total += modifier.amount();
            }
        }
        return total;
    }

    private static boolean isTool(ItemStack stack) {
        String id = itemId(stack);
        return id.endsWith("_pickaxe") || id.endsWith("_axe") || id.endsWith("_shovel")
            || id.endsWith("_hoe") || id.endsWith("_sword");
    }

    private static double tierBonus(String id) {
        if (id.startsWith("netherite_")) return 6.0;
        if (id.startsWith("diamond_")) return 5.0;
        if (id.startsWith("iron_")) return 4.0;
        if (id.startsWith("golden_")) return 3.0;
        if (id.startsWith("stone_")) return 2.0;
        if (id.startsWith("wooden_")) return 1.0;
        return 0.0;
    }

    private static String itemId(ItemStack stack) {
        Item item = stack.getItem();
        net.minecraft.resources.Identifier key = BuiltInRegistries.ITEM.getKey(item);
        return key == null ? "" : key.toString();
    }
}
