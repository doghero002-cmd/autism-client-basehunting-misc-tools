package kaptainwutax.seedcrackerX.mixin;

import com.autism.seedcracker.spectre.mechanics.AutoRelogConnectionControl;
import io.netty.channel.ChannelFuture;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Required only for the new AutoRelogModule's owned connection attempt.
 * Minecraft 26.2 ConnectScreen has no public abort API and onClose does not cancel its worker.
 * Mirrors its verified Cancel-button synchronization/fields, without replacing the user's screen.
 * No global packet or screen interception; ordinary/manual connections are never touched.
 */
@Mixin(ConnectScreen.class)
public abstract class SpectreRelogConnectScreenMixin implements AutoRelogConnectionControl {
    @Shadow private volatile Connection connection;
    @Shadow private ChannelFuture channelFuture;
    @Shadow private volatile boolean aborted;

    @Override
    @Unique
    public void autoRelog$cancelOwnedConnection() {
        synchronized (this) {
            aborted = true;
            if (channelFuture != null) {
                channelFuture.cancel(true);
                channelFuture = null;
            }
            if (connection != null) connection.disconnect(ConnectScreen.ABORT_CONNECTION);
        }
    }

    @Override
    @Unique
    public boolean autoRelog$isAborted() { return aborted; }

    @Override
    @Unique
    public boolean autoRelog$isConnection(Connection candidate) {
        return candidate != null && connection == candidate;
    }
}
