package kaptainwutax.seedcrackerX.mixin;

import com.autism.seedcracker.modules.TranslateModule;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Appends an auto-translated line under incoming system-chat messages (Translate module).
 * Same hook shape as {@link FakeRolesChatMixin}: translation is cached/async, so on a cache
 * miss the message passes through unchanged and the translated line appears on the next
 * identical message (or via the module's local re-print).
 */
@Mixin(ClientPacketListener.class)
public abstract class TranslateChatMixin {

    @ModifyVariable(method = "handleSystemChat", at = @At("HEAD"), argsOnly = true)
    private ClientboundSystemChatPacket seedbased$translate(ClientboundSystemChatPacket packet) {
        Component original = packet.content();
        Component replaced = TranslateModule.transformChat(original);
        if (replaced == original) {
            return packet;
        }
        return new ClientboundSystemChatPacket(replaced, packet.overlay());
    }
}
