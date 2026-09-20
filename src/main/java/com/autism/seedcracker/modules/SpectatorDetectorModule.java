package com.autism.seedcracker.modules;

import java.util.HashSet;
import java.util.Set;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.util.FlagLog;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import autismclient.util.AutismNotifications;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.world.level.GameType;

/**
 * Spectator Detector (nyx SpectatorDetector + Nexus StaffDetector vanish trick, merged).
 *
 * Three independent staff-surveillance detectors:
 *  1. SPECTATOR gamemode: any tab-list entry whose game mode flips to SPECTATOR (staff watching
 *     you in spectator still updates their PlayerInfo game mode on most servers).
 *  2. VANISH (latency mismatch): a {@code PlayerInfoUpdate/UPDATE_LATENCY} packet that carries
 *     entries NOT present in the visible tab list - servers keep sending latency updates for
 *     vanished players (Nexus StaffDetector finding).
 *  3. Staff-prefix keywords in tab display names (helper/mod/admin/staff...), with a custom list.
 */
public final class SpectatorDetectorModule extends Module {

    private final BoolSetting detectSpectator = add(new BoolSetting("detect-spectator", "Detect spectator mode", true)
        .description("Alert when any player's game mode becomes SPECTATOR.").group("Detect"));
    private final BoolSetting detectVanish = add(new BoolSetting("detect-vanish", "Detect vanish (latency)", true)
        .description("Alert when latency updates arrive for players missing from the tab list (vanished staff).").group("Detect"));
    private final BoolSetting detectPrefix = add(new BoolSetting("detect-prefix", "Detect staff prefixes", true)
        .description("Alert when a tab-list display name contains a staff keyword.").group("Detect"));
    private final StringSetting keywords = add(new StringSetting("keywords", "Staff keywords",
            "helper,mod,admin,staff,owner,curator")
        .description("Comma-separated keywords matched (case-insensitive) against tab display names.")
        .group("Detect").visibleWhen(() -> detectPrefix.get()))
        ;
    private final IntSetting alertCooldown = add(new IntSetting("cooldown", "Alert cooldown (s)", 30, 5, 300, 5)
        .description("Per-player alert cooldown.").group("General"));
    private final BoolSetting toast = add(new BoolSetting("toast", "Toast notification", true)
        .description("Show an on-screen toast on detection.").group("Notifications"));
    private final BoolSetting chat = add(new BoolSetting("chat", "Chat message", true)
        .description("Print detections to chat.").group("Notifications"));

    private final java.util.Map<String, Long> lastAlertMs = new java.util.concurrent.ConcurrentHashMap<>();
    private int scanTicks = 0;

    public SpectatorDetectorModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":spectator-detector", "Spectator Detector", category,
            "Alerts when staff watch you: spectator gamemode, vanish latency-mismatch, staff tab prefixes.");
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void onDisable() {
        lastAlertMs.clear();
    }

    @Override
    public boolean onPacketReceive(Packet<?> packet) {
        if (!detectVanish.get()) return false;
        if (!(packet instanceof ClientboundPlayerInfoUpdatePacket info)) return false;
        // Vanish check: latency-only updates for UUIDs we cannot see in the tab list.
        if (!info.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY)) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || mc.player == null) return false;

        for (ClientboundPlayerInfoUpdatePacket.Entry entry : info.entries()) {
            if (entry.profileId() == null) continue;
            if (entry.profileId().equals(mc.player.getUUID())) continue;
            PlayerInfo known = mc.getConnection().getPlayerInfo(entry.profileId());
            if (known == null) {
                alert("vanished-" + entry.profileId(),
                    "Latency update for a player NOT in the tab list (vanished staff?) id=" + entry.profileId());
            }
        }
        return false;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;
        if (++scanTicks < 20) return; // 1/s scan
        scanTicks = 0;

        String[] words = keywords.get() == null ? new String[0] : keywords.get().toLowerCase().split(",");
        Set<String> cleaned = new HashSet<>();
        for (String w : words) {
            String t = w.trim();
            if (!t.isEmpty()) cleaned.add(t);
        }

        for (PlayerInfo pi : mc.getConnection().getOnlinePlayers()) {
            String name = pi.getProfile().name();
            if (name.equals(mc.player.getGameProfile().name())) continue;

            if (detectSpectator.get() && pi.getGameMode() == GameType.SPECTATOR) {
                alert("spec-" + name, name + " is in SPECTATOR mode!");
            }
            if (detectPrefix.get() && pi.getTabListDisplayName() != null) {
                String display = pi.getTabListDisplayName().getString().toLowerCase();
                // Only the decorated prefix counts: strip the profile name to avoid matching
                // players actually called "Admin_Steve".
                String prefix = display.replace(name.toLowerCase(), "");
                for (String kw : cleaned) {
                    if (prefix.contains(kw)) {
                        alert("prefix-" + name, name + " has staff prefix (" + kw + ") in tab.");
                        break;
                    }
                }
            }
        }
    }

    private void alert(String key, String message) {
        long now = System.currentTimeMillis();
        Long last = lastAlertMs.get(key);
        if (last != null && now - last < alertCooldown.get() * 1000L) return;
        lastAlertMs.put(key, now);

        FlagLog.warn("STAFF", "SpectatorDetector", message);
        if (chat.get()) AutismClientMessaging.sendPrefixed("§c[Staff] §f" + message);
        if (toast.get()) AutismNotifications.warning("Staff: " + message);
    }
}
