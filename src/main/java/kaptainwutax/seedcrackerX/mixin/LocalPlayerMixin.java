package kaptainwutax.seedcrackerX.mixin;

import kaptainwutax.seedcrackerX.SeedCracker;
import kaptainwutax.seedcrackerX.config.Config;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {

    /** Throttle: DataStorage.tick() submits a TimeMachine task every call while idle, which was
     * a steady CPU churn (~1200 submissions/sec) even with every module off. Only run the real
     * tick when the cracker is active; otherwise do the cheap GUI-flag check a few times a second. */
    private int tickCounter = 0;

    @Inject(method = "tick", at = @At("HEAD"))
    private void tick(CallbackInfo ci) {
        if (Config.get().active) {
            SeedCracker.get().getDataStorage().tick();
        } else if (++tickCounter >= 5) {
            tickCounter = 0;
            SeedCracker.get().getDataStorage().tickGuiOnly();
        }
    }

}
