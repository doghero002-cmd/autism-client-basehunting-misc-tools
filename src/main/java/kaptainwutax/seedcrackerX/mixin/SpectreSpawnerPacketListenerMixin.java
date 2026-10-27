/* SPDX-License-Identifier: GPL-3.0-only
 * Adapted from Spectre MixinSpawnerProtectClientPacketListener, revision
 * d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da. Inventory TAIL hooks are addon safety additions.
 * No asynchronous packet callback reads/mutates the world; block detection runs after handoff.
 */
package kaptainwutax.seedcrackerX.mixin;

import com.autism.seedcracker.modules.SpawnerProtectModule;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class SpectreSpawnerPacketListenerMixin {
    @Inject(method = "handleBlockUpdate", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
        shift = At.Shift.AFTER))
    private void spectre$protectionBlock(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        SpawnerProtectModule module = SpawnerProtectModule.active;
        if (module != null) module.onServerBlockUpdate((ClientPacketListener) (Object) this, packet.getPos(), packet.getBlockState());
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
        shift = At.Shift.AFTER))
    private void spectre$protectionSection(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        SpawnerProtectModule module = SpawnerProtectModule.active;
        if (module != null) packet.runUpdates((pos, state) -> module.onServerBlockUpdate((ClientPacketListener) (Object) this, pos, state));
    }

    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    private void spectre$protectionContent(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        SpawnerProtectModule module = SpawnerProtectModule.active;
        if (module != null) module.onServerInventoryPacket((ClientPacketListener) (Object) this, packet);
    }
    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
    private void spectre$protectionSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        SpawnerProtectModule module = SpawnerProtectModule.active;
        if (module != null) module.onServerInventoryPacket((ClientPacketListener) (Object) this, packet);
    }
    @Inject(method = "handleSetPlayerInventory", at = @At("TAIL"))
    private void spectre$protectionPlayerInventory(ClientboundSetPlayerInventoryPacket packet, CallbackInfo ci) {
        SpawnerProtectModule module = SpawnerProtectModule.active;
        if (module != null) module.onServerInventoryPacket((ClientPacketListener) (Object) this, packet);
    }
    @Inject(method = "handleSetCursorItem", at = @At("TAIL"))
    private void spectre$protectionCursor(ClientboundSetCursorItemPacket packet, CallbackInfo ci) {
        SpawnerProtectModule module = SpawnerProtectModule.active;
        if (module != null) module.onServerInventoryPacket((ClientPacketListener) (Object) this, packet);
    }
    @Inject(method = "handleOpenScreen", at = @At("TAIL"))
    private void spectre$protectionOpen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        SpawnerProtectModule module = SpawnerProtectModule.active;
        if (module != null) module.onServerInventoryPacket((ClientPacketListener) (Object) this, packet);
    }
    @Inject(method = "handleContainerClose", at = @At("TAIL"))
    private void spectre$protectionClose(ClientboundContainerClosePacket packet, CallbackInfo ci) {
        SpawnerProtectModule module = SpawnerProtectModule.active;
        if (module != null) module.onServerInventoryPacket((ClientPacketListener) (Object) this, packet);
    }
}
