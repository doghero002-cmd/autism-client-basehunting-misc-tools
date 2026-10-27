package com.autism.seedcracker.spectre.mechanics;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Checks the actual classpath Minecraft bytecode, without booting Minecraft or claiming mixin application. */
class MovementHookContractTest {
    private static MethodModel method(String owner, String name, String descriptor) throws IOException {
        try (var input = MovementHookContractTest.class.getClassLoader().getResourceAsStream(owner + ".class")) {
            assertNotNull(input, owner);
            return ClassFile.of().parse(input.readAllBytes()).methods().stream()
                .filter(m -> m.methodName().equalsString(name) && m.methodType().equalsString(descriptor))
                .findFirst().orElseThrow(() -> new AssertionError(owner + "." + name + descriptor));
        }
    }

    private static long calls(MethodModel method, String owner, String name, String descriptor) {
        return method.code().orElseThrow().elementStream().filter(e -> e instanceof InvokeInstruction call
            && call.owner().asInternalName().equals(owner) && call.name().equalsString(name)
            && call.type().equalsString(descriptor)).count();
    }

    @Test void airStuckHookTargetsTheExistingLocalAiStep() throws IOException {
        assertTrue(method("net/minecraft/client/player/LocalPlayer", "aiStep", "()V").code().isPresent());
    }

    @Test void speedMineWrapsTheActualProgressCallAndFirstDelayRead() throws IOException {
        var method = method("net/minecraft/client/multiplayer/MultiPlayerGameMode", "continueDestroyBlock",
            "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z");
        assertTrue(calls(method, "net/minecraft/world/level/block/state/BlockState", "getDestroyProgress",
            "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F") > 0);
        var firstDelay = method.code().orElseThrow().elementStream()
            .filter(e -> e instanceof FieldInstruction field
                && field.owner().asInternalName().equals("net/minecraft/client/multiplayer/MultiPlayerGameMode")
                && field.name().equalsString("destroyDelay") && field.type().equalsString("I"))
            .map(FieldInstruction.class::cast).findFirst().orElseThrow();
        assertEquals(Opcode.GETFIELD, firstDelay.opcode());
    }

    @Test void tridentWaterAndChargeHooksStillMatchBothNativeItemPaths() throws IOException {
        var use = method("net/minecraft/world/item/TridentItem", "use",
            "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;");
        var release = method("net/minecraft/world/item/TridentItem", "releaseUsing",
            "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;I)Z");
        assertEquals(1, calls(use, "net/minecraft/world/entity/player/Player", "isInWaterOrRain", "()Z"));
        assertEquals(1, calls(release, "net/minecraft/world/entity/player/Player", "isInWaterOrRain", "()Z"));
        assertEquals(1, release.code().orElseThrow().elementStream()
            .filter(e -> e instanceof ConstantInstruction constant && Integer.valueOf(10).equals(constant.constantValue())).count());
    }

    @Test void riptideUsesVanillaReleaseRatherThanDrivingKeysOrStartingCharges() throws IOException {
        var tick = method("com/autism/seedcracker/modules/RiptideModule", "tick", "()V");
        assertEquals(1, calls(tick, "net/minecraft/client/multiplayer/MultiPlayerGameMode", "releaseUsingItem",
            "(Lnet/minecraft/world/entity/player/Player;)V"));
        assertEquals(0, calls(tick, "net/minecraft/client/multiplayer/MultiPlayerGameMode", "useItem",
            "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;"));
        assertEquals(0, calls(tick, "net/minecraft/client/KeyMapping", "setDown", "(Z)V"));
    }
}
