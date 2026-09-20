package kaptainwutax.seedcrackerX.mixin;

import com.autism.seedcracker.modules.ScoreboardHiderModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cancels the scoreboard sidebar draw while the Scoreboard Hider module is on (render-only). */
@Mixin(Hud.class)
public abstract class HudScoreboardMixin {

    @Inject(method = "extractScoreboardSidebar", at = @At("HEAD"), cancellable = true, require = 0)
    private void seedcracker$hideScoreboardExtract(GuiGraphicsExtractor ctx, net.minecraft.client.DeltaTracker delta, CallbackInfo ci) {
        if (ScoreboardHiderModule.shouldHide()) ci.cancel();
    }

    @Inject(method = "displayScoreboardSidebar", at = @At("HEAD"), cancellable = true, require = 0)
    private void seedcracker$hideScoreboardDisplay(GuiGraphicsExtractor ctx, net.minecraft.world.scores.Objective objective, CallbackInfo ci) {
        if (ScoreboardHiderModule.shouldHide()) ci.cancel();
    }
}
