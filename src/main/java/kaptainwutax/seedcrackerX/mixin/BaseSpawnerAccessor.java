package kaptainwutax.seedcrackerX.mixin;

import net.minecraft.world.level.BaseSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read the spawner's current spawn delay (Shoreline activated-spawner detection). */
@Mixin(BaseSpawner.class)
public interface BaseSpawnerAccessor {

    @Accessor("spawnDelay")
    int seedcracker$getSpawnDelay();
}
