/* SPDX-License-Identifier: GPL-3.0-only
 * Spectre MixinSpawnerProtectKeyboardInput, d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da,
 * adapted to transient native Input construction. Unlike the donor, physical input is never
 * suppressed, key mappings are never changed, and only this session's local player is driven.
 */
package kaptainwutax.seedcrackerX.mixin;

import com.autism.seedcracker.modules.SpawnerProtectModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(KeyboardInput.class)
public abstract class SpectreSpawnerKeyboardInputMixin {
    @ModifyArgs(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/player/Input;<init>(ZZZZZZZ)V"))
    private void spectre$protectionInput(Args args) {
        SpawnerProtectModule module = SpawnerProtectModule.active;
        var player = Minecraft.getInstance().player;
        if (module == null || player == null || player.input != (Object) this || !module.isDriving()) return;
        Input physical = new Input(args.get(0), args.get(1), args.get(2), args.get(3), args.get(4), args.get(5), args.get(6));
        Input result = module.protectionInput(physical);
        args.set(0, result.forward());
        args.set(5, result.shift());
    }
}
