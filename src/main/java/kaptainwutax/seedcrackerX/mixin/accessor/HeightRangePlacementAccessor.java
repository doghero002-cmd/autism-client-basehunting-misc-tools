package kaptainwutax.seedcrackerX.mixin.accessor;

import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes HeightRangePlacement's private height HeightProvider (for OreSim). */
@Mixin(HeightRangePlacement.class)
public interface HeightRangePlacementAccessor {
    @Accessor("height")
    HeightProvider getHeight();
}
