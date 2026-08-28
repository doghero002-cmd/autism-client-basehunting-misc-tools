package kaptainwutax.seedcrackerX.mixin;

import com.autism.seedcracker.modules.FakeRolesModule;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Rewrites incoming system-chat messages so the local player's nametag shows the fake
 * role/tag chosen in the Fake Roles module. Ported hook for the Zelith "FakeRoles" spoof.
 */
@Mixin(ClientPacketListener.class)
public abstract class FakeRolesChatMixin {

    @ModifyVariable(method = "handleSystemChat", at = @At("HEAD"), argsOnly = true)
    private ClientboundSystemChatPacket seedbased$fakeRoles(ClientboundSystemChatPacket packet) {
        Component original = packet.content();
        Component replaced = FakeRolesModule.transform(original);
        if (replaced == original) {
            return packet;
        }
        return new ClientboundSystemChatPacket(replaced, packet.overlay());
    }
}
