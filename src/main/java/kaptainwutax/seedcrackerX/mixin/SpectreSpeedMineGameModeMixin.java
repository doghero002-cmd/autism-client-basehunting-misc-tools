package kaptainwutax.seedcrackerX.mixin;

import com.autism.seedcracker.modules.SpeedMineModule;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** SPDX-License-Identifier: GPL-3.0-only. Adapted from Spectre MixinSpeedMineMultiPlayerGameMode, d5a7dd3. */
@Mixin(MultiPlayerGameMode.class)
public abstract class SpectreSpeedMineGameModeMixin {
    @Shadow @Final private Minecraft minecraft;

    // Change only this call's delay test, not the stored vanilla delay. Disabling needs no
    // write-back and cannot overwrite another module's delay or retain an altered cooldown.
    @ModifyExpressionValue(method = "continueDestroyBlock", at = @At(value = "FIELD",
        target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;destroyDelay:I",
        opcode = Opcodes.GETFIELD, ordinal = 0))
    private int dogsSpectre$effectiveDestroyDelay(int original, BlockPos pos, Direction direction) {
        if (original <= 0 || minecraft.player == null || minecraft.level == null) return original;
        BlockState state = minecraft.level.getBlockState(pos);
        return SpeedMineModule.dropsDelay(state, state.getDestroyProgress(minecraft.player, minecraft.level, pos))
            ? 0 : original;
    }

    @WrapOperation(method = "continueDestroyBlock", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/state/BlockState;getDestroyProgress(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F"))
    private float dogsSpectre$scaleDestroyProgress(BlockState state, Player player, BlockGetter level,
            BlockPos pos, Operation<Float> original) {
        return SpeedMineModule.scaleProgress(state, original.call(state, player, level, pos));
    }
}
