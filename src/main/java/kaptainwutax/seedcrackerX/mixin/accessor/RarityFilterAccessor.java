package kaptainwutax.seedcrackerX.mixin.accessor;

import net.minecraft.world.level.levelgen.placement.RarityFilter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes RarityFilter's private chance (for OreSim). */
@Mixin(RarityFilter.class)
public interface RarityFilterAccessor {
    @Accessor("chance")
    float getChance();
}
