package kaptainwutax.seedcrackerX.mixin.accessor;

import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes CountPlacement's private count IntProvider (for OreSim). */
@Mixin(CountPlacement.class)
public interface CountPlacementAccessor {
    @Accessor("count")
    IntProvider getCount();
}
