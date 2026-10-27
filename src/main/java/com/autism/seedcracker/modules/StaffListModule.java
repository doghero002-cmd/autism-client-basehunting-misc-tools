// SPDX-License-Identifier: GPL-3.0-only
// Adapted from https://github.com/cody-raves/Spectre, commit d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da.
// Upstream: src/main/java/net/spectre/client/module/impl/misc/StaffListModule.java
package com.autism.seedcracker.modules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.compat.ClientNotify;
import com.autism.seedcracker.compat.ModuleLookup;
import com.autism.seedcracker.spectre.staff.NameFilter;
import com.autism.seedcracker.spectre.staff.PlayerDetectionLogic;
import com.autism.seedcracker.spectre.staff.ShardTracker;
import com.autism.seedcracker.spectre.staff.StaffAlarm;
import com.autism.seedcracker.spectre.staff.StaffGhost;
import com.autism.seedcracker.spectre.staff.StaffMatcher;
import com.autism.seedcracker.util.FlagLog;
import com.autism.seedcracker.util.RelogPersistence;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.PlayerTeam;

public final class StaffListModule extends Module {
    public enum AlarmMode { SIREN, BEEP, BELL, GUARDIAN, OFF }
    public enum Presence { ONLINE, SPECTATOR, UNLISTED }

    public static final List<String> DONUT_STAFF = List.of(
        "FluffyMaster07", "archivePedro", "Munkerlich", "Frenk_Btw", "Napooo_", "auzzitech", "CryptoDaveYT",
        "W1zox_", "ZEEF69", "showered", "CaptainMoose35", "BobIsFound", "NoahvdAa", "0GSummer", "LzouzMP5",
        "Pastagamer08", "u_vv", "Owen1212055", "Splaterd", "Fallerfly");
    private static final Set<String> STAFF_KEYS = Set.copyOf(DONUT_STAFF.stream().map(n -> n.toLowerCase(Locale.ROOT)).toList());
    private static final StaffMatcher RANKS = new StaffMatcher(StaffMatcher.MODE_ALL, StaffMatcher.DEFAULT_RANK_WORDS,
        StaffMatcher.DEFAULT_MARKERS, false, StaffMatcher.DEFAULT_TEAM_RANK_MAX, String.join(",", DONUT_STAFF), "");
    public static final long ALERT_HIGHLIGHT_MS = 4_000L;
    private static final int SCAN_INTERVAL = 10;
    private static final int SETTLE_TICKS = 60;

    private final BoolSetting regionAlert = add(new BoolSetting("server-alert", "Region activity alerts", true)
        .description("Nearby entities or standalone game-mode updates. Mode updates are a clue, not proof of locality.").group("Alerts"));
    private final BoolSetting chat = add(new BoolSetting("chat", "Chat notifications", true).group("Alerts"));
    private final EnumSetting<AlarmMode> alarmMode = add(new EnumSetting<>("alarm", "Alarm", AlarmMode.GUARDIAN, AlarmMode.values()).group("Alerts"));
    private final IntSetting alarmVolume = add(new IntSetting("alarm-volume", "Alarm volume (%)", 100, 0, 100, 5).group("Alerts"));

    private final Map<UUID, Staff> staff = new LinkedHashMap<>();
    private final Map<UUID, Sighting> sightings = new HashMap<>();
    private final Map<UUID, String> pendingAlerts = new LinkedHashMap<>();
    private final StaffAlarm alarm = new StaffAlarm();
    private final ShardTracker shard = new ShardTracker();
    // ponytail: 128 packets / 512 entries per packet; overflow resets evidence, never infers from a partial stream.
    private final ArrayBlockingQueue<QueuedUpdate> updates = new ArrayBlockingQueue<>(128);
    private final java.util.concurrent.atomic.AtomicLong epoch = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong lifecycleRevision = new java.util.concurrent.atomic.AtomicLong();
    private volatile long lifecycleApplied;
    private volatile boolean accepting;
    private volatile boolean overflow;
    private List<Staff> sorted = List.of();
    private ClientPacketListener connection;
    private boolean regionWasEnabled;
    private boolean primed;
    private int settleTicks;
    private int scanTicks;
    private int tickCount;
    private int alertGather;

    public StaffListModule() {
        super(SeedcrackerAddon.ID + ":staff-list", "Staff List",
            "Spectre's bundled Donut staff roster, tab visibility and nearby/region activity clues. Local only.");
    }

    @Override public void onEnable() { resetOnClient(true, false, 0); }
    @Override public void onDisable() { resetOnClient(false, false, SETTLE_TICKS); }
    @Override public void onGameJoin() { resetOnClient(true, false, SETTLE_TICKS); }
    @Override public void onGameLeft() { resetOnClient(false, true, SETTLE_TICKS); }

    private void resetOnClient(boolean enable, boolean leaving, int settle) {
        accepting = false;
        long revision = lifecycleRevision.incrementAndGet();
        epoch.incrementAndGet();
        updates.clear();
        MC.execute(() -> {
            if (lifecycleRevision.get() != revision) return;
            try {
                reset(settle);
                accepting = enable && isEnabled();
                if (leaving) {
                    connection = null;
                    if (RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
                }
            } finally {
                lifecycleApplied = Math.max(lifecycleApplied, revision);
            }
        });
    }
    private void reset(int settle) {
        epoch.incrementAndGet();
        updates.clear();
        overflow = false;
        staff.clear();
        sorted = List.of();
        forgetRegion();
        shard.clear();
        connection = MC.getConnection();
        primed = false;
        settleTicks = settle;
        scanTicks = 0;
        tickCount = 0;
        regionWasEnabled = regionAlert.get();
    }

    private void forgetRegion() {
        sightings.clear();
        pendingAlerts.clear();
        alarm.stop();
        StaffGhost.clear(id());
        alertGather = 0;
    }

    @Override
    public boolean onPacketReceive(Packet<?> packet) {
        long captured = epoch.get();
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> part : bundle.subPackets()) queueUpdate(part, captured);
        } else queueUpdate(packet, captured);
        return false;
    }

    private void queueUpdate(Packet<?> packet, long captured) {
        if (accepting && lifecycleApplied == lifecycleRevision.get() && captured == epoch.get() && packet instanceof ClientboundPlayerInfoUpdatePacket info
            && info.actions().contains(Action.UPDATE_GAME_MODE) && !info.actions().contains(Action.ADD_PLAYER)) {
            if (info.entries().size() > 512 || !updates.offer(new QueuedUpdate(captured, info))) overflow = true;
        }
    }

    @Override
    public void tick() {
        if (lifecycleApplied != lifecycleRevision.get()) return;
        if (MC.level == null || MC.player == null || MC.getConnection() == null) {
            accepting = false;
            reset(SETTLE_TICKS);
            connection = null;
            return;
        }
        boolean moved = shard.moved(MC);
        if (connection != MC.getConnection() || moved || overflow) {
            reset(SETTLE_TICKS);
            shard.moved(MC);
        }
        accepting = true;
        if (regionWasEnabled && !regionAlert.get()) forgetRegion();
        regionWasEnabled = regionAlert.get();
        if (alarmMode.get() == AlarmMode.OFF || alarmVolume.get() == 0) alarm.stop();
        alarm.tick();
        tickCount++;
        if (settleTicks > 0) settleTicks--;
        long now = System.currentTimeMillis();
        drainUpdates(now);
        if (--scanTicks <= 0) {
            scanTicks = SCAN_INTERVAL;
            Changes changes = scan();
            if (primed) announce(changes);
            else if (settleTicks == 0) primed = true;
        }
        if (regionAlert.get()) trackBodies(now);
        if (!pendingAlerts.isEmpty() && --alertGather <= 0) raise();
        pruneSightings(now);
        sorted = staff.values().stream().sorted(Comparator.<Staff>comparingInt(s -> group(s, now))
            .thenComparingInt(s -> s.seniority).thenComparing(s -> s.name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    private Changes scan() {
        Set<UUID> listed = new HashSet<>();
        for (PlayerInfo info : connection.getListedOnlinePlayers()) listed.add(info.getProfile().id());
        Set<UUID> inTab = new HashSet<>();
        Changes changes = new Changes();
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            UUID id = info.getProfile().id();
            String name = info.getProfile().name();
            if (!isAccount(id, name) || id.equals(MC.player.getUUID()) || !isStaffName(name)) continue;
            inTab.add(id);
            Presence presence = info.getGameMode() == GameType.SPECTATOR ? Presence.SPECTATOR
                : listed.contains(id) ? Presence.ONLINE : Presence.UNLISTED;
            Staff tracked = staff.get(id);
            if (tracked == null) {
                tracked = new Staff(id);
                staff.put(id, tracked);
                changes.joined.add(tracked);
            } else if (tracked.presence != presence) changes.changed.add(tracked);
            StaffMatcher.Match rank = rank(info, name);
            tracked.name = name;
            tracked.rank = rank == null ? "Staff" : rank.rank();
            tracked.seniority = rank == null ? Integer.MAX_VALUE : rank.seniority();
            tracked.presence = presence;
            tracked.offTabScans = 0;
        }
        var iterator = staff.values().iterator();
        while (iterator.hasNext()) {
            Staff tracked = iterator.next();
            if (inTab.contains(tracked.id)) continue;
            Player body = MC.level.getPlayerByUUID(tracked.id);
            if (body != null && !body.isRemoved()) {
                if (++tracked.offTabScans >= 2 && tracked.presence != Presence.UNLISTED) {
                    tracked.presence = Presence.UNLISTED;
                    changes.changed.add(tracked);
                }
            } else {
                iterator.remove();
                changes.left.add(tracked);
            }
        }
        return changes;
    }

    private static StaffMatcher.Match rank(PlayerInfo info, String name) {
        PlayerTeam team = info.getTeam();
        return RANKS.match(name, team == null ? null : team.getName(), plain(info.getTabListDisplayName()),
            team == null ? null : plain(team.getPlayerPrefix()), team == null ? null : plain(team.getPlayerSuffix()));
    }

    private static String plain(Component text) { return text == null ? null : text.getString(); }
    private static boolean isAccount(UUID id, String name) { return id != null && id.version() != 2 && PlayerDetectionLogic.accountName(name); }

    private void drainUpdates(long now) {
        for (int packets = 0; packets < 64; packets++) {
            QueuedUpdate queued = updates.poll();
            if (queued == null) break;
            if (queued.epoch() != epoch.get() || !regionAlert.get() || settleTicks > 0) continue;
            for (var change : queued.packet().entries()) {
                UUID id = change.profileId();
                if (id == null || id.equals(MC.player.getUUID()) || change.gameMode() == null) continue;
                PlayerInfo info = connection.getPlayerInfo(id);
                Staff tracked = staff.get(id);
                String name = tracked != null ? tracked.name : info == null ? null : info.getProfile().name();
                if (!isStaffName(name) || !isAccount(id, name)) continue;
                Sighting seen = sightings.computeIfAbsent(id, unused -> new Sighting());
                seen.provenAt = now;
                if (!seen.coolingDown(now)) {
                    seen.switchTick = tickCount;
                    seen.spectatorSwitch = change.gameMode() == GameType.SPECTATOR;
                    alert(id, name, seen, now);
                }
            }
        }
    }

    private void trackBodies(long now) {
        for (Player body : MC.level.players()) {
            if (body == MC.player || body.isRemoved()) continue;
            Staff tracked = staff.get(body.getUUID());
            if (tracked == null) continue;
            Sighting seen = sightings.computeIfAbsent(tracked.id, unused -> new Sighting());
            seen.provenAt = now;
            seen.bodyTick = tickCount;
            seen.distance = MC.player.distanceTo(body);
            if (!seen.nearby && (seen.bodyLostAt == 0 || now - seen.bodyLostAt > 10_000L)) alert(tracked.id, tracked.name, seen, now);
            seen.nearby = true;
        }
        for (Sighting seen : sightings.values()) {
            if (seen.nearby && seen.bodyTick != tickCount) {
                seen.nearby = false;
                seen.bodyLostAt = now;
            }
        }
    }

    private void alert(UUID id, String name, Sighting seen, long now) {
        seen.alertedAt = now;
        seen.highlightUntil = now + ALERT_HIGHLIGHT_MS;
        if (pendingAlerts.isEmpty()) alertGather = 5;
        pendingAlerts.put(id, name);
    }

    private void raise() {
        var first = pendingAlerts.entrySet().iterator().next();
        String names = String.join(", ", pendingAlerts.values().stream().map(NameFilter::apply).toList());
        notifyLocal(names + ": nearby entity or region activity clue (not proof of watching).", true);
        alarm.start(EnumSetting.titleCase(alarmMode.get()), 3.0F, alarmVolume.get() / 100F);
        StaffGhost.watching(id(), first.getKey(), first.getValue(), pendingAlerts.size() - 1, "Nearby entity / regional activity clue");
        pendingAlerts.clear();
    }

    private void announce(Changes changes) {
        changes.changed.removeIf(this::echoesSwitchAlert);
        describe(changes.joined, "appeared in the player list");
        describe(changes.changed, "changed visibility");
        describe(changes.left, "is no longer observed");
        if ((!changes.joined.isEmpty() || changes.changed.stream().anyMatch(s -> s.presence != Presence.ONLINE))
            && alarmMode.get() != AlarmMode.OFF && alarmVolume.get() > 0) {
            MC.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.25F, alarmVolume.get() / 100F));
        }
    }

    private void describe(List<Staff> group, String verb) {
        if (group.isEmpty()) return;
        if (group.size() > 3) {
            notifyLocal(group.size() + " staff " + verb + ": " + String.join(", ", group.stream().map(s -> NameFilter.apply(s.name)).toList()), false);
        } else {
            for (Staff s : group) {
                String visibility = switch (s.presence) {
                    case ONLINE -> "listed";
                    case SPECTATOR -> "spectator mode";
                    case UNLISTED -> s.offTabScans > 0 ? "off TAB, entity still nearby" : "hidden from TAB";
                };
                notifyLocal(NameFilter.apply(s.name) + " (" + s.rank + ") " + verb + " [" + visibility + "]", false);
            }
        }
    }

    private void notifyLocal(String message, boolean urgent) {
        if (urgent) FlagLog.warn("STAFF", "StaffList", message);
        else FlagLog.info("STAFF", "StaffList", message);
        if (chat.get()) ClientNotify.warning("[Staff List] " + message);
    }

    private boolean echoesSwitchAlert(Staff s) {
        Sighting seen = sightings.get(s.id);
        return seen != null && seen.switchTick >= 0 && tickCount - seen.switchTick <= 20
            && (s.presence == Presence.SPECTATOR ? seen.spectatorSwitch : s.presence == Presence.ONLINE && !seen.spectatorSwitch);
    }

    private void pruneSightings(long now) {
        var iterator = sightings.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Sighting seen = entry.getValue();
            if (staff.containsKey(entry.getKey())) seen.untrackedSince = 0;
            else if (seen.untrackedSince == 0) seen.untrackedSince = now;
            else if (now - seen.untrackedSince > 10_000L) iterator.remove();
        }
    }

    private int group(Staff s, long now) {
        Sighting seen = sightings.get(s.id);
        if (seen != null && seen.nearby) return 0;
        if (seen != null && seen.marked(now)) return 1;
        return s.presence == Presence.ONLINE ? 3 : 2;
    }

    private static StaffListModule instance() {
        Module module = ModuleLookup.get(SeedcrackerAddon.ID + ":staff-list");
        return module instanceof StaffListModule staff ? staff : null;
    }

    public static boolean isStaffName(String name) { return name != null && STAFF_KEYS.contains(name.toLowerCase(Locale.ROOT)); }
    public static boolean shouldShowHud() { StaffListModule m = instance(); return m != null && m.isEnabled() && !m.sorted.isEmpty(); }

    /** Includes heuristic game-mode-update clues; this is not a guarantee of server locality. */
    public static boolean isInYourRegion(UUID id) {
        StaffListModule module = instance();
        Sighting seen = module != null && module.isEnabled() ? module.sightings.get(id) : null;
        return seen != null && seen.marked(System.currentTimeMillis());
    }

    public static boolean alarmedRecently(UUID id) {
        StaffListModule module = instance();
        Sighting seen = module != null && module.isEnabled() && module.alarmMode.get() != AlarmMode.OFF && module.alarmVolume.get() > 0
            ? module.sightings.get(id) : null;
        return seen != null && seen.alertedAt != 0 && System.currentTimeMillis() - seen.alertedAt < 5_000L;
    }

    public static HudList hudRows() {
        StaffListModule module = instance();
        if (module == null || !module.isEnabled()) return new HudList(List.of(), 0);
        int shown = module.sorted.size() <= 8 ? module.sorted.size() : 7;
        long now = System.currentTimeMillis();
        List<HudRow> rows = new ArrayList<>(shown);
        for (Staff s : module.sorted.subList(0, shown)) {
            Sighting seen = module.sightings.get(s.id);
            boolean marked = seen != null && seen.marked(now);
            boolean nearby = seen != null && seen.nearby;
            String name = NameFilter.apply(s.name);
            rows.add(new HudRow(name, s.rank, s.id, s.presence, marked, marked ? Math.max(0, now - seen.provenAt) : -1,
                nearby, nearby ? seen.distance : -1, seen == null ? 0 : Math.clamp((seen.highlightUntil - now) / 4000F, 0F, 1F), !name.equals(s.name)));
        }
        return new HudList(List.copyOf(rows), module.sorted.size() - shown);
    }

    public static String formatAge(long ms) {
        long seconds = Math.max(0L, ms) / 1000;
        long minutes = seconds / 60;
        return minutes >= 60 ? "%d:%02d:%02d".formatted(minutes / 60, minutes % 60, seconds % 60)
            : "%d:%02d".formatted(minutes, seconds % 60);
    }

    @Override public String info() { return sorted.isEmpty() ? "" : sorted.size() + " staff"; }

    public record HudList(List<HudRow> rows, int more) {}
    public record HudRow(String name, String rank, UUID id, Presence presence, boolean sameServer, long sameServerAgeMs,
                         boolean nearby, float distance, float alertLevel, boolean nameHidden) {
        public String sameServerAge() { return sameServer ? formatAge(sameServerAgeMs) : ""; }
        public String distanceLabel() { return nearby ? Math.round(distance) + "m" : ""; }
    }
    private record QueuedUpdate(long epoch, ClientboundPlayerInfoUpdatePacket packet) {}
    private static final class Changes {
        final List<Staff> joined = new ArrayList<>(), changed = new ArrayList<>(), left = new ArrayList<>();
    }
    private static final class Staff {
        final UUID id;
        String name = "", rank = "Staff";
        int seniority, offTabScans;
        Presence presence = Presence.ONLINE;
        Staff(UUID id) { this.id = id; }
    }
    private static final class Sighting {
        long provenAt, alertedAt, highlightUntil, bodyLostAt, untrackedSince;
        boolean nearby, spectatorSwitch;
        float distance;
        int bodyTick, switchTick = -1;
        boolean marked(long now) { return nearby || provenAt != 0 && now - provenAt < 600_000L; }
        boolean coolingDown(long now) { return alertedAt != 0 && now - alertedAt < 120_000L; }
    }
}
