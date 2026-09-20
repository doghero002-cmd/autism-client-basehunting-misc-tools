package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.util.FlagLog;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import autismclient.util.AutismNotifications;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;

/**
 * Macro Protector (Shimmer port).
 *
 * Staff "control checks" work by forcibly moving your camera / position / hotbar slot from the
 * server and watching whether your "macro" keeps running as if nothing happened. This module
 * watches for those server-forced packets and immediately disables every running automation
 * module so the client reacts exactly like a human would (stops, looks around confused).
 *
 * Detection channels (any = trip):
 *  - {@link ClientboundPlayerRotationPacket}: server forcibly rotates the camera (the classic
 *    admin "look check").
 *  - {@link ClientboundSetHeldSlotPacket}: server changes the held hotbar slot.
 *  - {@link ClientboundPlayerPositionPacket} while automation runs (optional - noisy on laggy
 *    servers, the Flag Detector already logs these as setbacks).
 */
public final class MacroProtectorModule extends Module {

    private final BoolSetting onRotation = add(new BoolSetting("on-rotation", "On forced rotation", true)
        .description("Trip when the server forcibly rotates your camera (admin look check).").group("Triggers"));
    private final BoolSetting onSlot = add(new BoolSetting("on-slot", "On forced hotbar slot", true)
        .description("Trip when the server changes your held hotbar slot.").group("Triggers"));
    private final BoolSetting onTeleport = add(new BoolSetting("on-teleport", "On teleport", false)
        .description("Trip on server position packets too (noisy: rubber-bands also count). The Flag Detector logs these anyway.").group("Triggers"));
    private final IntSetting cooldownSeconds = add(new IntSetting("cooldown", "Re-trip cooldown (s)", 10, 1, 120, 1)
        .description("Ignore further triggers for this long after tripping (one trip = one shutdown).").group("General"));
    private final BoolSetting notifyToast = add(new BoolSetting("toast", "Toast notification", true)
        .description("Show an on-screen toast when tripped.").group("Notifications"));
    private final BoolSetting notifyChat = add(new BoolSetting("chat", "Chat message", true)
        .description("Print which modules were shut down.").group("Notifications"));

    /** Modules that must be shut down on a control check (automation that would expose a macro). */
    private static final java.util.Set<String> AUTOMATION_IDS = java.util.Set.of(
        "TunnelBaseFinderModule", "TunnelBaseWaterModule", "SchematicBuilderModule",
        "AutoMineModule", "AutoEatModule", "AutoToolModule", "AutoFireworkModule",
        "KeyPearlModule", "BoneDropperModule", "NetherTunnelFinderModule",
        "DonutRTPStashFinderModule", "RelogLoaderModule", "AutoTPAModule", "ShopBuyerModule",
        "AHSniperModule", "AHFlipperModule", "AhSellModule", "TPASpammerModule",
        "AntiAFKModule", "SpawnerProtectModule", "QuickMacroModule");

    private long lastTripMs = 0;

    public MacroProtectorModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":macro-protector", "Macro Protector", category,
            "Kills all automation the instant the server force-rotates/teleports you or swaps your slot (staff control check).");
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public boolean onPacketReceive(Packet<?> packet) {
        if (packet instanceof ClientboundPlayerRotationPacket) {
            if (onRotation.get()) trip("forced rotation");
        } else if (packet instanceof ClientboundSetHeldSlotPacket) {
            if (onSlot.get()) trip("forced hotbar slot");
        } else if (packet instanceof ClientboundPlayerPositionPacket) {
            if (onTeleport.get()) trip("server teleport");
        }
        return false;
    }

    private void trip(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastTripMs < cooldownSeconds.get() * 1000L) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Grace window first: joins/respawns/dimension changes send these packets legitimately.
        // Reuse FlagDetector's grace logic by checking whether we've been in the world >5s.
        if (mc.player.tickCount < 100) return;

        java.util.List<String> stopped = new java.util.ArrayList<>();
        for (autismclient.modules.Module m : autismclient.modules.ModuleRegistry.all()) {
            if (!m.isEnabled()) continue;
            String simple = m.getClass().getSimpleName();
            if (AUTOMATION_IDS.contains(simple)) {
                try {
                    m.setEnabled(false);
                    stopped.add(m.name());
                } catch (Throwable t) {
                    FlagLog.warn("MACRO", "MacroProtector", "failed to disable " + simple + ": " + t);
                }
            }
        }
        if (stopped.isEmpty()) return; // nothing was running - no need to alert

        lastTripMs = now;
        FlagLog.flag("MACRO", "MacroProtector", reason + " -> stopped " + String.join(",", stopped));
        if (notifyChat.get()) {
            AutismClientMessaging.sendPrefixed("§c[MacroProtector] §f" + reason
                + " detected! Stopped: §e" + String.join("§f, §e", stopped));
        }
        if (notifyToast.get()) {
            AutismNotifications.warning("Control check: " + reason + " - automation stopped!");
        }
    }
}
