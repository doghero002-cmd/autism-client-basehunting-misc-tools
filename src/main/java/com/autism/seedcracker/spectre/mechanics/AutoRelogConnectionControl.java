package com.autism.seedcracker.spectre.mechanics;

import net.minecraft.network.Connection;

/** Narrow cancellation hook implemented only by SpectreRelogConnectScreenMixin. */
public interface AutoRelogConnectionControl {
    void autoRelog$cancelOwnedConnection();
    boolean autoRelog$isAborted();
    boolean autoRelog$isConnection(Connection candidate);
}
