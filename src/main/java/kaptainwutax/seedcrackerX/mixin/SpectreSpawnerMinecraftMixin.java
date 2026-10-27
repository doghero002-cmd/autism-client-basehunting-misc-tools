/* SPDX-License-Identifier: GPL-3.0-only
 * Adapted from Spectre MixinSpawnerProtectMinecraft, d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da.
 * Only the idle vanilla cancellation of an owned dig is blocked; manual attacks remain native.
 */
package kaptainwutax.seedcrackerX.mixin;

import com.autism.seedcracker.modules.SpawnerProtectModule;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class SpectreSpawnerMinecraftMixin {
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void spectre$protectionContinueAttack(boolean down, CallbackInfo ci) {
        SpawnerProtectModule module = SpawnerProtectModule.active;
        if (module != null && module.onVanillaContinueAttack(down)) ci.cancel();
    }
}
