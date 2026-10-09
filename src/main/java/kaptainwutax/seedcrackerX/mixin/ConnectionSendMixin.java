package kaptainwutax.seedcrackerX.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.autism.seedcracker.util.tunnel.SilentRotation;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;

/**
 * Routes outgoing packets through {@link SilentRotation#processPacket(Packet)} so the silent
 * facing actually reaches the server. The rewrite logic existed but nothing called it - silent
 * rotation set state that no packet ever carried (ledger A1). Same injection target as the
 * client's own AutismPilotEditRerouteMixin.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ConnectionSendMixin {

    @ModifyVariable(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), argsOnly = true)
    private Packet<?> seedcracker$rewriteRotation(Packet<?> packet) {
        return SilentRotation.processPacket(packet);
    }
}
