package com.example.donutflipscanner.mixin;

import com.example.donutflipscanner.packet.AuctionHouseDataCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Intercepts auction house screen packets from the server.
 * When the AH screen opens, captured slot data is pushed into {@link AuctionHouseDataCapture}.
 */
@Mixin(ClientPacketListener.class)
public abstract class AuctionHousePacketListener extends ClientCommonPacketListenerImpl {
    @Unique
    private static volatile AuctionHouseDataCapture donutflip$capture;

    protected AuctionHousePacketListener(Minecraft minecraft, Connection connection, CommonListenerCookie cookie) {
        super(minecraft, connection, cookie);
    }

    public static void setCapture(AuctionHouseDataCapture capture) {
        donutflip$capture = capture;
    }

    @Inject(method = "handleOpenScreen", at = @At("TAIL"))
    private void donutflip$onOpenScreen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        AuctionHouseDataCapture capture = donutflip$capture;
        if (capture == null) {
            return;
        }
        String title = packet.getTitle().getString();
        int syncId = packet.getContainerId();
        capture.onScreenOpened(syncId, title);
    }

    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    private void donutflip$onInventory(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        AuctionHouseDataCapture capture = donutflip$capture;
        if (capture == null) {
            return;
        }
        int syncId = packet.containerId();
        List<ItemStack> contents = packet.items();
        capture.onInventorySynced(syncId, contents);
    }

    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
    private void donutflip$onSlotUpdate(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        AuctionHouseDataCapture capture = donutflip$capture;
        if (capture == null) {
            return;
        }
        int syncId = packet.getContainerId();
        int slot = packet.getSlot();
        ItemStack stack = packet.getItem();
        capture.onSlotUpdated(syncId, slot, stack);
    }

    @Inject(method = "handleContainerClose", at = @At("TAIL"))
    private void donutflip$onCloseScreen(ClientboundContainerClosePacket packet, CallbackInfo ci) {
        AuctionHouseDataCapture capture = donutflip$capture;
        if (capture == null) {
            return;
        }
        capture.onScreenClosed(packet.getContainerId());
    }
}
