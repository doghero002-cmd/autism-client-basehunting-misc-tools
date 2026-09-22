package kaptainwutax.seedcrackerX.mixin;

import com.autism.seedcracker.modules.FlagDetectorModule;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the Flag Detector's bypass-telemetry HUD panel (no-op unless the module + hud option are on). */
@Mixin(Hud.class)
public abstract class HudFlagTelemetryMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"), require = 0)
    private void seedcracker$renderFlagTelemetry(GuiGraphicsExtractor context, DeltaTracker deltaTracker, CallbackInfo ci) {
        try {
            FlagDetectorModule.renderHud(context);
        } catch (Throwable ignored) {
        }
        try {
            com.autism.seedcracker.modules.BalanceTagsModule.renderHud(context);
        } catch (Throwable ignored) {
        }
    }
}
