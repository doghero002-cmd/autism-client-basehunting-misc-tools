package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.util.FlagLog;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.world.phys.Vec3;

/**
 * Position Packet Filter (Krypton port).
 *
 * Drops malicious / broken entity position packets before they reach the client: NaN or
 * absurdly-large coordinates crash renderers and yank ESP tracers across the world (a known
 * anti-ESP griefing trick is teleporting marker entities to +-30M every tick). Filtered packets
 * are counted and logged, never applied.
 */
public final class PositionPacketFilterModule extends Module {

    private final BoolSetting filterNaN = add(new BoolSetting("filter-nan", "Filter NaN/Inf", true)
        .description("Drop entity position packets containing NaN or infinite coordinates.").group("General"));
    private final IntSetting maxCoord = add(new IntSetting("max-coord", "Max |coordinate|", 30_000_000, 100_000, 30_000_000, 100_000)
        .description("Drop entity positions beyond this absolute X/Z (world border is 30M).").group("General"));
    private final BoolSetting logDrops = add(new BoolSetting("log", "Log drops", true)
        .description("Log each dropped packet to flag-log.txt.").group("General"));

    private long dropped = 0;

    public PositionPacketFilterModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":pos-packet-filter", "Position Filter", category,
            "Drops NaN/absurd entity position packets (anti-ESP-grief, anti-crash).");
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public boolean onPacketReceive(Packet<?> packet) {
        if (!(packet instanceof ClientboundEntityPositionSyncPacket sync)) return false;

        Vec3 pos = sync.values().position();
        boolean bad = false;
        if (filterNaN.get()
            && (!Double.isFinite(pos.x) || !Double.isFinite(pos.y) || !Double.isFinite(pos.z))) {
            bad = true;
        }
        double cap = maxCoord.get();
        if (!bad && (Math.abs(pos.x) > cap || Math.abs(pos.z) > cap || Math.abs(pos.y) > 100_000)) {
            bad = true;
        }
        if (!bad) return false;

        dropped++;
        if (logDrops.get() && dropped % 20 == 1) { // sample the log: these can spam
            FlagLog.warn("POSFILTER", "PositionFilter",
                "dropped entity pos id=" + sync.id() + " pos=" + pos + " (total " + dropped + ")");
        }
        return true; // cancel
    }

    @Override
    public String info() {
        return dropped + " dropped";
    }
}
