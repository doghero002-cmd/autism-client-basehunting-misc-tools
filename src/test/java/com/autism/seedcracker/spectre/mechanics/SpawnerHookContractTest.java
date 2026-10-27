/* SPDX-License-Identifier: GPL-3.0-only */
package com.autism.seedcracker.spectre.mechanics;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.jupiter.api.Assertions.*;

/** Bytecode contract checks, not a claim of native mixin application or live server behavior. */
class SpawnerHookContractTest {
    private static ClassNode type(String name) throws IOException {
        try (InputStream input = SpawnerHookContractTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input, name + " must come from the actual cached/native classpath");
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }
    private static MethodNode method(ClassNode type, String name, String descriptor) {
        return type.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).findFirst()
            .orElseThrow(() -> new AssertionError(type.name + "." + name + descriptor + " missing"));
    }
    private static long calls(MethodNode method, String owner, String name, String descriptor) {
        return Arrays.stream(method.instructions.toArray()).filter(i -> i instanceof MethodInsnNode m
            && m.owner.equals(owner) && m.name.equals(name) && m.desc.equals(descriptor)).count();
    }
    @Test void beforeBlockHooksHaveExactlyOneClientThreadHandoff() throws Exception {
        var type = type("net/minecraft/client/multiplayer/ClientPacketListener");
        String handoff = "(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";
        for (String[] signature : new String[][] {
            {"handleBlockUpdate", "ClientboundBlockUpdatePacket"}, {"handleChunkBlocksUpdate", "ClientboundSectionBlocksUpdatePacket"}
        }) {
            var m = method(type, signature[0], "(Lnet/minecraft/network/protocol/game/" + signature[1] + ";)V");
            assertEquals(1, calls(m, "net/minecraft/network/protocol/PacketUtils", "ensureRunningOnSameThread", handoff));
        }
    }
    @Test void allInventoryTailTargetsExistOnActualMinecraftJar() throws Exception {
        var type = type("net/minecraft/client/multiplayer/ClientPacketListener");
        for (String[] signature : new String[][] {
            {"handleContainerContent", "ClientboundContainerSetContentPacket"},
            {"handleContainerSetSlot", "ClientboundContainerSetSlotPacket"},
            {"handleSetPlayerInventory", "ClientboundSetPlayerInventoryPacket"},
            {"handleSetCursorItem", "ClientboundSetCursorItemPacket"},
            {"handleOpenScreen", "ClientboundOpenScreenPacket"},
            {"handleContainerClose", "ClientboundContainerClosePacket"}
        }) assertNotNull(method(type, signature[0], "(Lnet/minecraft/network/protocol/game/" + signature[1] + ";)V"));
    }
    @Test void inputConstructorAndContinuousDigHooksMatchNativeDescriptors() throws Exception {
        var keyboard = method(type("net/minecraft/client/player/KeyboardInput"), "tick", "()V");
        assertEquals(1, calls(keyboard, "net/minecraft/world/entity/player/Input", "<init>", "(ZZZZZZZ)V"));
        assertNotNull(method(type("net/minecraft/client/Minecraft"), "continueAttack", "(Z)V"));
    }
    @Test void nativePacketCallbacksAreObserversNotCancellationAndLifecycleIsPublic() throws Exception {
        var module = type("com/autism/seedcracker/modules/SpawnerProtectModule");
        for (String callback : new String[] {"onPacketSend", "onPacketReceive"}) {
            var body = method(module, callback, "(Lnet/minecraft/network/protocol/Packet;)Z");
            var returns = Arrays.stream(body.instructions.toArray()).filter(i -> i.getOpcode() == org.objectweb.asm.Opcodes.IRETURN).toList();
            assertFalse(returns.isEmpty());
            for (var instruction : returns) assertEquals(org.objectweb.asm.Opcodes.ICONST_0, instruction.getPrevious().getOpcode(), callback);
        }
        for (String name : new String[] {"AntiTrapModule", "SpawnerProtectModule"}) {
            var nativeModule = type("com/autism/seedcracker/modules/" + name);
            for (String hook : new String[] {"onEnable", "onDisable", "onGameLeft", "onGameJoin", "tick"}) {
                assertTrue((method(nativeModule, hook, "()V").access & org.objectweb.asm.Opcodes.ACC_PUBLIC) != 0, name + "." + hook);
            }
        }
    }
    @Test void existingIdsAndSettingKeysSurviveThePort() throws Exception {
        for (String[] contract : new String[][] {
            {"AntiTrapModule", ":z-anti-trap", "armor-stands", "minecarts", "chest-minecarts", "hopper-minecarts", "range", "cooldown", "notify"},
            {"SpawnerProtectModule", ":z-spawner-protect", "whitelist", "webhook", "enemy-radius", "sneak-mine", "store-shulker", "store-echest"}
        }) {
            var nativeModule = type("com/autism/seedcracker/modules/" + contract[0]);
            var constants = nativeModule.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray()))
                .filter(i -> i instanceof org.objectweb.asm.tree.LdcInsnNode)
                .map(i -> ((org.objectweb.asm.tree.LdcInsnNode) i).cst).filter(String.class::isInstance).map(String.class::cast).toList();
            assertTrue(constants.stream().anyMatch(s -> s.endsWith(contract[1])), contract[0] + " id changed");
            for (int i = 2; i < contract.length; i++) assertTrue(constants.contains(contract[i]), "Missing setting " + contract[i]);
        }
    }
    @Test void serverClickContractBroadcastsActualChangesWithoutInventedAcknowledgements() throws Exception {
        var click = method(type("net/minecraft/server/network/ServerGamePacketListenerImpl"), "handleContainerClick",
            "(Lnet/minecraft/network/protocol/game/ServerboundContainerClickPacket;)V");
        assertEquals(1, calls(click, "net/minecraft/world/inventory/AbstractContainerMenu", "broadcastChanges", "()V"));
        assertEquals(1, calls(click, "net/minecraft/world/inventory/AbstractContainerMenu", "broadcastFullState", "()V"));
        assertEquals(1, calls(click, "net/minecraft/network/protocol/game/ServerboundContainerClickPacket", "changedSlots",
            "()Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;"));
    }
}
