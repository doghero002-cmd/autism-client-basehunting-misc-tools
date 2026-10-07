package kaptainwutax.seedcrackerX.mixin;

import com.autism.seedcracker.modules.HomeSetterModule;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SilentHome (Water port): cancels the server's home-command confirmation lines during the
 * HomeSetter suppression window, so setting a stash home leaves no visible chat trace.
 */
@Mixin(ClientPacketListener.class)
public abstract class HomeSetterChatMixin {

    @Inject(method = "handleSystemChat", at = @At("HEAD"), cancellable = true)
    private void seedbased$silentHome(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        if (HomeSetterModule.shouldSuppress(packet.content())) {
            ci.cancel();
        }
    }
}
