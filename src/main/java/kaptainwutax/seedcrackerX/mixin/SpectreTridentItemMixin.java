package kaptainwutax.seedcrackerX.mixin;

import com.autism.seedcracker.modules.TridentUtilModule;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** SPDX-License-Identifier: GPL-3.0-only. Adapted from Spectre MixinTridentItem, d5a7dd3. */
@Mixin(TridentItem.class)
public abstract class SpectreTridentItemMixin {
    @WrapOperation(method = {"use", "releaseUsing"}, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/player/Player;isInWaterOrRain()Z"))
    private boolean dogsSpectre$localDryUse(Player player, Operation<Boolean> original) {
        return original.call(player) || TridentUtilModule.allowOutOfWater(player);
    }

    @ModifyConstant(method = "releaseUsing", constant = @Constant(intValue = 10))
    private int dogsSpectre$localChargeThreshold(int original, ItemStack stack, Level level,
            LivingEntity entity, int remainingTicks) {
        return TridentUtilModule.minChargeTicks(entity, original);
    }
}
