package com.autism.seedcracker.spectre.palette;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpawnerDataTest {
    @Test void readsMobRangeAndDelayDirectlyFromRawSpawnerTag() {
        CompoundTag tag = new CompoundTag();
        CompoundTag spawn = new CompoundTag();
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:skeleton");
        spawn.put("entity", entity);
        tag.put("SpawnData", spawn);
        tag.putShort("RequiredPlayerRange", (short) 24);
        tag.putShort("Delay", (short) 73);
        var result = BlockEntityCapture.readSpawner(tag, BlockEntityLeaks.Spawner.unknown());
        assertEquals("minecraft:skeleton", result.entityId());
        assertEquals(24, result.activationRange());
        assertEquals(73, result.delay());
    }

    @Test void partialUpdatePreservesPreviousFieldsAndNullIsSafe() {
        var previous = new BlockEntityLeaks.Spawner("minecraft:zombie", 32, 61);
        CompoundTag tag = new CompoundTag();
        tag.putShort("Delay", (short) 20);
        var result = BlockEntityCapture.readSpawner(tag, previous);
        assertEquals("minecraft:zombie", result.entityId());
        assertEquals(32, result.activationRange());
        assertEquals(20, result.delay());
        assertEquals(previous, BlockEntityCapture.readSpawner(null, previous));
    }

    @Test void malformedFieldsCannotCreateUnlimitedRadiusOrUnboundedLabels() {
        CompoundTag tag = new CompoundTag();
        CompoundTag spawn = new CompoundTag();
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:" + "z".repeat(1000));
        spawn.put("entity", entity);
        tag.put("SpawnData", spawn);
        tag.putInt("RequiredPlayerRange", Integer.MAX_VALUE);
        tag.putInt("Delay", Integer.MIN_VALUE);
        var result = BlockEntityCapture.readSpawner(tag, BlockEntityLeaks.Spawner.unknown());
        assertEquals("", result.entityId());
        assertEquals(16, result.activationRange());
        assertEquals(-1, result.delay());
    }
}
