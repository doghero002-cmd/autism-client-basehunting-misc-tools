package kaptainwutax.seedcrackerX.mixin;

import com.autism.seedcracker.compat.FreeLookState;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class FreeLookCameraMixin {
    @Inject(method = "getMaxZoom(F)F", at = @At("HEAD"), cancellable = true)
    private void seedcracker$cameraCollision(float distance, CallbackInfoReturnable<Float> callback) {
        if (FreeLookState.active()) callback.setReturnValue(distance);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void seedcracker$cameraCulling(CameraRenderState state, float partialTick, CallbackInfo callback) {
        if (FreeLookState.active()) state.smartCull = false;
    }
}