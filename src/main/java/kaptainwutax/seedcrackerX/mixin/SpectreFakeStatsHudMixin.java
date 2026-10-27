// Spectre Fake Stats presentation adapter; GPL-3.0-only. See SPECTRE-NOTICES.txt.
package kaptainwutax.seedcrackerX.mixin;

import com.autism.seedcracker.modules.FakeStatsModule;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(Hud.class)
public abstract class SpectreFakeStatsHudMixin {
    @ModifyArg(method = "extractScoreboardSidebar", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/Hud;displayScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/scores/Objective;)V"), index = 1)
    private Objective spectre$renderOnlyStats(Objective original) {
        return FakeStatsModule.sidebarForRender(original);
    }
}
