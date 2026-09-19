package kaptainwutax.seedcrackerX.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accessor for the private mouse-delta fields on {@link MouseHandler}. */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
    @Accessor("accumulatedDX")
    double seedcracker$getAccumulatedDX();

    @Accessor("accumulatedDY")
    double seedcracker$getAccumulatedDY();
}
