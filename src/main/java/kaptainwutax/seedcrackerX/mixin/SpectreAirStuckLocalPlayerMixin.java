package kaptainwutax.seedcrackerX.mixin;

import com.autism.seedcracker.modules.AirStuckModule;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** SPDX-License-Identifier: GPL-3.0-only. Adapted from Spectre MixinAirStuckLocalPlayer, d5a7dd3. */
@Mixin(LocalPlayer.class)
public abstract class SpectreAirStuckLocalPlayerMixin {
    @Inject(method = "aiStep", at = @At("HEAD"), cancellable = true)
    private void dogsSpectre$freezeLocalMovement(CallbackInfo ci) {
        if (AirStuckModule.freezes((LocalPlayer) (Object) this)) ci.cancel();
    }
}
