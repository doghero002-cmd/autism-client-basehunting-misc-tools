package kaptainwutax.seedcrackerX.mixin;

import com.autism.seedcracker.util.tunnel.MouseRotation;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Redirects the mouse-delta field reads inside {@code MouseHandler.turnPlayer} to the
 * {@link MouseRotation} engine's integer cursor deltas while a legit rotation is active. Because
 * the deltas flow through the vanilla turnPlayer path (sensitivity scaling, smooth-camera, the
 * actual camera update), the server sees exactly what a real hardware mouse would produce.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Redirect(method = "turnPlayer", at = @At(value = "FIELD", target = "Lnet/minecraft/client/MouseHandler;accumulatedDX:D"))
    private double seedcracker$cursorDeltaX(MouseHandler self) {
        if (MouseRotation.get().isActive()) {
            return MouseRotation.get().nextCursorDeltaX();
        }
        return ((MouseHandlerAccessor) self).seedcracker$getAccumulatedDX();
    }

    @Redirect(method = "turnPlayer", at = @At(value = "FIELD", target = "Lnet/minecraft/client/MouseHandler;accumulatedDY:D"))
    private double seedcracker$cursorDeltaY(MouseHandler self) {
        if (MouseRotation.get().isActive()) {
            return MouseRotation.get().nextCursorDeltaY();
        }
        return ((MouseHandlerAccessor) self).seedcracker$getAccumulatedDY();
    }
}
