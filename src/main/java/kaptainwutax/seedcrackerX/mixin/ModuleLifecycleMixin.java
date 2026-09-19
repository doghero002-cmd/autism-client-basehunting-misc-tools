package kaptainwutax.seedcrackerX.mixin;

import com.autism.seedcracker.modules.FlagDetectorModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks every module's enable/disable into the Flag Detector's automation registry, so setback /
 * rotation / kick flags are correlated with exactly which modules were active - without editing
 * each of the ~48 modules individually. Fires on the Module base class lifecycle hooks.
 */
@Mixin(value = autismclient.modules.Module.class, remap = false)
public abstract class ModuleLifecycleMixin {

    @Inject(method = "onEnable", at = @At("HEAD"))
    private void seedcracker$trackEnable(CallbackInfo ci) {
        try {
            FlagDetectorModule.track(this, true);
        } catch (Throwable ignored) {}
    }

    @Inject(method = "onDisable", at = @At("HEAD"))
    private void seedcracker$trackDisable(CallbackInfo ci) {
        try {
            FlagDetectorModule.track(this, false);
        } catch (Throwable ignored) {}
    }
}
