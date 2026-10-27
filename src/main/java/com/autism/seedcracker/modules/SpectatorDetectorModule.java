// SPDX-License-Identifier: GPL-3.0-only
// Adapted from https://github.com/cody-raves/Spectre, commit d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da.
// Upstream: src/main/java/net/spectre/client/module/impl/misc/AntiVanishModule.java
package com.autism.seedcracker.modules;

import autismclient.modules.Module;
import autismclient.api.module.BoolSetting;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.compat.ModuleLookup;
import com.autism.seedcracker.compat.ClientNotify;
import com.autism.seedcracker.spectre.staff.NameFilter;
import com.autism.seedcracker.spectre.staff.StaffGhost;
import com.autism.seedcracker.util.FlagLog;

import com.mojang.brigadier.suggestion.Suggestion;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import com.autism.seedcracker.spectre.staff.AntiVanishHeuristics;
import com.autism.seedcracker.spectre.staff.AntiVanishText;
import com.autism.seedcracker.spectre.staff.ShardTracker;
import com.autism.seedcracker.spectre.staff.StaffAlarm;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
public final class SpectatorDetectorModule extends Module {

   private static final String PROBE_COMMAND = "minecraft:msg";
   private static final int SENSOR_RANGE = 64;
   private static final long SIGNAL_WINDOW_MS = 15000L;
   private static final long DETECTION_TTL_MS = 20000L;
   private static final long CRITICAL_COOLDOWN_MS = 12000L;
   private static final long ANNOUNCE_COOLDOWN_MS = 1500L;
   private static final long ANNOUNCE_WINDOW_MS = 4000L;
   private static final int MAX_ANNOUNCE_PER_WINDOW = 6;
   private static final int MAX_OBSERVATIONS_PER_TICK = 512;
   private static final int CRITICAL_SCORE = 35;
   private static final long SELF_PLACE_TTL_MS = 6000L;
   private static final long CONTAINER_SELF_GRACE_MS = 2500L;
   private static final long SPELL_SETTLE_MS = 1000L;
   private final ArrayBlockingQueue<PacketWork> packetQueue = new ArrayBlockingQueue<>(1024);
   private final java.util.concurrent.atomic.AtomicLong packetEpoch = new java.util.concurrent.atomic.AtomicLong();
   private final java.util.concurrent.atomic.AtomicLong lifecycleRevision = new java.util.concurrent.atomic.AtomicLong();
   private volatile long lifecycleApplied;
   private volatile boolean accepting;
   private volatile boolean overflow;
   private final Map<UUID, SpectatorDetectorModule.KnownPlayer> knownPlayers = new HashMap<>();
   private final Map<String, SpectatorDetectorModule.Detection> detections = new LinkedHashMap<>();
   private final Map<String, Long> signalCooldowns = new HashMap<>();
   private final Map<String, Long> announceCooldowns = new HashMap<>();
   private final Deque<Long> announceTimes = new ArrayDeque<>();
   private final Map<Long, Long> selfPlacedBlocks = new HashMap<>();
   private final Map<UUID, Long> confirmedDepartures = new HashMap<>();
   private final Map<Long, Long> automatedMechanisms = new HashMap<>();
   private final Map<String, Deque<Long>> weakParticleBursts = new HashMap<>();
   private final Deque<SpectatorDetectorModule.Signal> signals = new ArrayDeque<>();
   private final Deque<SpectatorDetectorModule.ExplosionEvent> recentExplosions = new ArrayDeque<>();
   private final Deque<SpectatorDetectorModule.PendingVanish> pendingVanishes = new ArrayDeque<>();
   private final Map<String, SpectatorDetectorModule.HiddenSpell> hiddenSpells = new LinkedHashMap<>();
   private final Deque<SpectatorDetectorModule.RecentMessage> recentMessages = new ArrayDeque<>();
   private boolean serverSendsLeaveMessages;
   private final Map<Integer, Long> completionRequestIds = new HashMap<>();
   private List<String> pendingCompletionNames;
   private int nextCompletionId = -30000;
   private static final long RECENT_MESSAGE_TTL_MS = 8000L;
   private static final long TRUSTED_LISTED_MS = 1500L;
   private final Map<UUID, Long> listedSinceMs = new HashMap<>();
   private final Set<UUID> trustedPlayers = new HashSet<>();
   private final ShardTracker shard = new ShardTracker();
   private int tickCounter;
   private long lastLocalActionMs;
   private long lastContainerActivityMs;
   private long lastCriticalMs;
   private long criticalUntilMs;
   private String criticalWatcher = "";
   private final Set<UUID> pendingChimes = new HashSet<>();
   private UUID ghostId;
   private String ghostName;

   private static final SystemToastId TOAST_ID = new SystemToastId();
   private final StaffAlarm alarm = new StaffAlarm();
   private final BoolSetting detectVanish = add(new BoolSetting("detect-vanish", "Tab visibility clues", true)
      .description("Small unlist/removal events for previously observed roster staff; not a guarantee of vanish.").group("Detection"));
   private final BoolSetting detectSpectator = add(new BoolSetting("detect-spectator", "Spectator changes", true).group("Detection"));
   private final BoolSetting sensors = add(new BoolSetting("local-sensors", "Local sounds, particles and invisibility", true)
      .description("Uses only received packets and loaded entities, with local-action and environmental exclusions.").group("Detection"));
   private final BoolSetting completionProbe = add(new BoolSetting("completion-probe", "Command completion probe (opt in)", false)
      .description("Sends minecraft:msg completion requests to this game server every 5 seconds. Off by default.").group("Detection"));
   private final BoolSetting chat = add(new BoolSetting("chat", "Chat messages", true).group("Notifications"));
   private final BoolSetting toast = add(new BoolSetting("toast", "Toasts", true).group("Notifications"));
   private final BoolSetting sound = add(new BoolSetting("sound", "Alarm", true).group("Notifications"));

   public SpectatorDetectorModule() {
      super(SeedcrackerAddon.ID + ":spectator-detector", "Anti Vanish",
         "Staff tab-visibility, spectator and local environmental clues; not guaranteed vanish detection.");
   }

   @Override public void onEnable() { resetOnClient(true, false); }
   @Override public void onDisable() { resetOnClient(false, false); }
   @Override public void onGameJoin() { resetOnClient(true, false); }
   @Override public void onGameLeft() { resetOnClient(false, true); }

   private void resetOnClient(boolean enable, boolean leaving) {
      accepting = false;
      long revision = lifecycleRevision.incrementAndGet();
      packetEpoch.incrementAndGet();
      packetQueue.clear();
      MC.execute(() -> {
         if (lifecycleRevision.get() != revision) return;
         try {
            resetRuntime();
            accepting = enable && isEnabled();
            if (accepting && MC.player != null && MC.level != null) {
               shard.moved(MC);
               trackListedPlayers();
            }
            if (leaving && com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
         } finally {
            lifecycleApplied = Math.max(lifecycleApplied, revision);
         }
      });
   }
   @Override
   public void tick() {
      if (lifecycleApplied != lifecycleRevision.get()) return;
      if (MC.player == null || MC.level == null || MC.getConnection() == null) {
         this.accepting = false;
         this.resetRuntime();
         return;
      }
      if (this.shard.moved(MC) || this.overflow) {
         this.resetRuntime();
         this.shard.moved(MC);
         this.trackListedPlayers();
      }
      this.accepting = true;
      this.alarm.tick();
      if (!sound.get()) this.alarm.stop();
      this.playPendingChime();
      this.tickCounter++;
      if (MC.player.containerMenu != MC.player.inventoryMenu) this.lastContainerActivityMs = System.currentTimeMillis();
      this.drainObservations();
      if (detectVanish.get()) this.processPendingVanishes();
      else this.pendingVanishes.clear();
      if (completionProbe.get()) {
         this.processCompletionProbe();
         if (this.tickCounter % 100 == 0) this.sendCompletionProbe();
      } else {
         this.completionRequestIds.clear();
         this.pendingCompletionNames = null;
      }
      if (this.tickCounter % 10 == 0) {
         this.trackListedPlayers();
         this.reviewSpells();
      }
      if (sensors.get() && this.tickCounter % 5 == 0) this.scanInvisiblePlayers();
      this.pruneState();
   }

   @Override
   public boolean onPacketSend(Packet<?> packet) {
      long epoch = packetEpoch.get();
      if (packet instanceof ServerboundUseItemOnPacket || packet instanceof ServerboundUseItemPacket
         || packet instanceof ServerboundPlayerActionPacket || packet instanceof ServerboundInteractPacket
         || packet instanceof ServerboundSwingPacket) enqueue(packet, true, epoch);
      return false;
   }

   private void enqueue(Packet<?> packet, boolean outbound, long epoch) {
      // ponytail: bounded packet backlog; overflow resets evidence instead of guessing from partial history.
      if (accepting && lifecycleApplied == lifecycleRevision.get() && epoch == packetEpoch.get() && !packetQueue.offer(new PacketWork(epoch, packet, outbound, System.currentTimeMillis()))) overflow = true;
   }

   private void handleOutbound(Packet<?> packet, long atMs) {
      if (packet != null) {
         if (packet instanceof ServerboundUseItemOnPacket useOn) {
            this.lastLocalActionMs = atMs;
            if (useOn.getHitResult() != null && useOn.getHitResult().getBlockPos() != null) {
               long until = atMs + SELF_PLACE_TTL_MS;
               BlockPos hit = useOn.getHitResult().getBlockPos();
               BlockPos adjacent = hit.relative(useOn.getHitResult().getDirection());
               this.selfPlacedBlocks.put(hit.asLong(), until);
               this.selfPlacedBlocks.put(adjacent.asLong(), until);
               String itemPath = "";
               if (MC.player != null && useOn.getHand() != null) {
                  Identifier itemId = BuiltInRegistries.ITEM.getKey(MC.player.getItemInHand(useOn.getHand()).getItem());
                  itemPath = AntiVanishHeuristics.path(itemId == null ? "" : itemId.toString());
               }

               markSelfMultiBlockFootprint(hit, itemPath, this.selfPlacedBlocks, until);
               markSelfMultiBlockFootprint(adjacent, itemPath, this.selfPlacedBlocks, until);
            }
         } else {
            if (packet instanceof ServerboundPlayerActionPacket
               || packet instanceof ServerboundUseItemPacket
               || packet instanceof ServerboundInteractPacket
               || packet instanceof ServerboundSwingPacket) {
               this.lastLocalActionMs = atMs;
            }
         }
      }
   }

   static void markSelfMultiBlockFootprint(BlockPos pos, String path, Map<Long, Long> targets, long until) {
      if (pos != null && targets != null) {
         targets.put(pos.asLong(), until);
         String id = path == null ? "" : path;
         boolean vertical = id.endsWith("_door") && !id.endsWith("trapdoor")
            || id.contains("sunflower")
            || id.contains("lilac")
            || id.contains("rose_bush")
            || id.contains("peony")
            || id.contains("tall_grass")
            || id.contains("large_fern")
            || id.contains("pitcher_plant");
         if (vertical) {
            targets.put(pos.above().asLong(), until);
            targets.put(pos.below().asLong(), until);
         }

         if (id.endsWith("_bed")) {
            targets.put(pos.offset(1, 0, 0).asLong(), until);
            targets.put(pos.offset(-1, 0, 0).asLong(), until);
            targets.put(pos.offset(0, 0, 1).asLong(), until);
            targets.put(pos.offset(0, 0, -1).asLong(), until);
         }
      }
   }
   @Override
   public boolean onPacketReceive(Packet<?> packet) {
      long epoch = packetEpoch.get();
      if (packet instanceof ClientboundBundlePacket bundle) {
         for (Packet<?> part : bundle.subPackets()) queueInbound(part, epoch);
      } else queueInbound(packet, epoch);
      return false;
   }

   private void queueInbound(Packet<?> packet, long epoch) {
      if (!accepting || epoch != packetEpoch.get()) return;
      if (packet instanceof ClientboundPlayerInfoUpdatePacket info) {
         if (info.actions().contains(Action.ADD_PLAYER)
            || !info.actions().contains(Action.UPDATE_LISTED) && !info.actions().contains(Action.UPDATE_GAME_MODE)) return;
         if (info.entries().size() > 512) {
            overflow = true;
            return;
         }
      }
      if (packet instanceof ClientboundPlayerInfoRemovePacket || packet instanceof ClientboundPlayerInfoUpdatePacket
         || packet instanceof ClientboundSystemChatPacket || packet instanceof ClientboundCommandSuggestionsPacket
         || packet instanceof ClientboundSetEntityDataPacket || packet instanceof ClientboundExplodePacket
         || packet instanceof ClientboundSoundEntityPacket || packet instanceof ClientboundLevelParticlesPacket
         || packet instanceof ClientboundSoundPacket) enqueue(packet, false, epoch);
   }

   private void handleInbound(Packet<?> packet) {
      if (packet instanceof ClientboundPlayerInfoRemovePacket remove) {
         if (detectVanish.get() && remove.profileIds().size() < 4) {
            for (UUID id : remove.profileIds()) processObservation(Observation.tabRemove(id));
         }
      } else if (packet instanceof ClientboundPlayerInfoUpdatePacket info) {
         boolean adding = info.actions().contains(Action.ADD_PLAYER);
         if (detectVanish.get() && info.actions().contains(Action.UPDATE_LISTED)) {
            int unlisted = (int)info.entries().stream().filter(entry -> entry != null && !entry.listed()).count();
            if (AntiVanishHeuristics.inspectTabHide(adding, unlisted)) {
               for (Entry entry : info.entries()) if (entry != null && !entry.listed()) processObservation(Observation.tabHide(entry.profileId()));
            }
         }
         // UPDATE_LISTED and UPDATE_GAME_MODE can coexist in the same packet.
         if (detectSpectator.get() && info.actions().contains(Action.UPDATE_GAME_MODE)) queueGamemodeUpdates(info);
      } else if (packet instanceof ClientboundSystemChatPacket chatPacket) {
         String text = chatPacket.content() == null ? "" : chatPacket.content().getString();
         if (!text.isBlank()) processObservation(Observation.systemChat(text.substring(0, Math.min(2048, text.length()))));
         String departed = departedPlayerName(chatPacket.content());
         if (!departed.isBlank() && departed.length() <= 128) processObservation(Observation.playerLeft(departed));
      } else if (packet instanceof ClientboundCommandSuggestionsPacket suggestions) {
         Long expires = completionRequestIds.remove(suggestions.id());
         if (!completionProbe.get() || expires == null || expires < System.currentTimeMillis()) return;
         Set<String> names = new java.util.LinkedHashSet<>();
         for (Suggestion suggestion : suggestions.toSuggestions().getList()) {
            String name = suggestion.getText();
            if (name != null && StaffListModule.isStaffName(name.trim())) names.add(name.trim());
         }
         pendingCompletionNames = List.copyOf(names);
      } else if (sensors.get()) {
         if (packet instanceof ClientboundSetEntityDataPacket metadata) processObservation(Observation.entityMetadata(metadata.id()));
         else if (packet instanceof ClientboundExplodePacket explosion) processObservation(Observation.explosion(explosion.center(), explosion.radius()));
         else if (packet instanceof ClientboundSoundEntityPacket entitySound) {
            processObservation(Observation.entitySound(entitySound.getId(), soundId(entitySound.getSound().value().location())));
         } else if (packet instanceof ClientboundLevelParticlesPacket particles) {
            Identifier id = BuiltInRegistries.PARTICLE_TYPE.getKey(particles.getParticle().getType());
            processObservation(Observation.position(ObservationType.PARTICLE, particles.getX(), particles.getY(), particles.getZ(), id == null ? "" : id.toString()));
         } else if (packet instanceof ClientboundSoundPacket soundPacket) handleSoundPacket(soundPacket);
      }
   }
   private void handleSoundPacket(ClientboundSoundPacket packet) {
      if (packet != null && packet.getSound() != null && packet.getSound().value() != null) {
         String id = soundId(packet.getSound().value().location());
         this.processObservation(SpectatorDetectorModule.Observation.position(SpectatorDetectorModule.ObservationType.POSITIONAL_SOUND, packet.getX(), packet.getY(), packet.getZ(), id));
      }
   }

   public static boolean shouldShowHud() {
      SpectatorDetectorModule module = instance();
      return module != null && module.isEnabled() && module.hasHudContent();
   }

   private boolean hasHudContent() {
      long now = System.currentTimeMillis();
      if (now < this.criticalUntilMs) {
         return true;
      } else {
         for (SpectatorDetectorModule.Detection detection : this.detections.values()) {
            if (detection.expiresAt > now && detectionWorthShowing(detection)) {
               return true;
            }
         }

         return false;
      }
   }

   private static boolean detectionWorthShowing(SpectatorDetectorModule.Detection detection) {
      if (detection == null) {
         return false;
      } else {
         String name = detection.name == null ? "" : detection.name.trim();
         return !name.isBlank() && !"Unknown".equalsIgnoreCase(name) && !"You".equalsIgnoreCase(name) && !"CRITICAL".equalsIgnoreCase(name);
      }
   }

   public static String hudTag(SpectatorDetectorModule.HudEntry entry) {
      if (entry == null) {
         return "CLUE";
      } else if ("CRITICAL".equalsIgnoreCase(entry.name())) {
         return "ALERT";
      } else {
         String lower = entry.reason() == null ? "" : entry.reason().toLowerCase(Locale.ROOT);
         if (lower.startsWith("tab visibility")) {
            return lower.contains("spectator") ? "SPECTATOR" : "TAB";
         } else if (lower.startsWith("invisible entity")) {
            return "INVIS";
         } else if (lower.startsWith("suspicious sound")) {
            return "SOUND";
         } else {
            return lower.startsWith("unexplained particle") ? "PARTICLE" : "CLUE";
         }
      }
   }

   public static String hudValue(SpectatorDetectorModule.HudEntry entry) {
      if (entry == null) {
         return "Staff";
      } else if ("CRITICAL".equalsIgnoreCase(entry.name())) {
         return shortName(entry.reason());
      } else {
         String name = entry.name() == null ? "" : entry.name().trim();
         return !name.isBlank() && !"Unknown".equalsIgnoreCase(name) && !"You".equalsIgnoreCase(name) ? shortName(name) : "Staff";
      }
   }

   private static String shortName(String name) {
      String shown = name == null ? "" : NameFilter.apply(name.trim());
      return shown.length() <= 16 ? shown : shown.substring(0, 16);
   }

   public static List<SpectatorDetectorModule.HudEntry> hudEntries() {
      SpectatorDetectorModule module = instance();
      return module != null && shouldShowHud() ? module.hudSnapshot() : List.of();
   }

   private static SpectatorDetectorModule instance() {
      Module module = ModuleLookup.get(SeedcrackerAddon.ID + ":spectator-detector");
      return module instanceof SpectatorDetectorModule detector ? detector : null;
   }

   private void drainObservations() {
      for (int i = 0; i < 256; i++) {
         PacketWork work = this.packetQueue.poll();
         if (work == null) break;
         if (work.epoch() != packetEpoch.get()) continue;
         if (work.outbound()) handleOutbound(work.packet(), work.atMs());
         else handleInbound(work.packet());
      }
   }

   private void processObservation(SpectatorDetectorModule.Observation observation) {
      switch (observation.type) {
         case TAB_REMOVE:
            this.handleTabRemoval(observation.profileId);
            break;
         case TAB_HIDE:
            this.handleTabHidden(observation.profileId);
            break;
         case PLAYER_LEFT:
            this.serverSendsLeaveMessages = true;
            this.confirmDeparture(observation.detail);
            break;
         case SYSTEM_CHAT:
            this.cacheRecentMessage(observation.detail);
            break;
         case ENTITY_METADATA:
            this.inspectInvisibleEntity(observation.entityId);
            break;
         case POSITIONAL_SOUND:
            this.inspectPositionalSound(observation);
            break;
         case ENTITY_SOUND:
            this.inspectEntitySound(observation);
            break;
         case PARTICLE:
            this.inspectParticle(observation);
            break;
         case EXPLOSION:
            this.rememberExplosion(observation);
            break;
         case GAMEMODE:
            this.announceGamemode(observation.profileId, observation.detail);
      }
   }

   private void trackListedPlayers() {
      if (MC.getConnection() == null) return;
      long now = System.currentTimeMillis();
      Set<UUID> listed = new HashSet<>();
      for (PlayerInfo info : MC.getConnection().getListedOnlinePlayers()) {
         UUID uuid = info.getProfile().id();
         String name = info.getProfile().name();
         if (uuid == null || MC.player != null && MC.player.getUUID().equals(uuid) || !StaffListModule.isStaffName(name)) continue;
         listed.add(uuid);
         listedSinceMs.putIfAbsent(uuid, now);
         if (AntiVanishHeuristics.matureListing(listedSinceMs.get(uuid), now)) trustedPlayers.add(uuid);
         knownPlayers.put(uuid, new KnownPlayer(uuid, name, now));
         if (!hiddenSpells.isEmpty()) endSpell(name, false, SPELL_SETTLE_MS);
      }
      listedSinceMs.keySet().retainAll(listed);
      if (knownPlayers.size() > 128) knownPlayers.keySet().retainAll(listed);
      trustedPlayers.retainAll(knownPlayers.keySet());
   }

   private boolean trustedListed(UUID uuid) {
      return uuid != null && trustedPlayers.contains(uuid);
   }
   private boolean credibleSubject(UUID uuid, String name) {
      return uuid != null && AntiVanishText.isUsername(name) && StaffListModule.isStaffName(name) && this.trustedListed(uuid);
   }

   private void handleTabRemoval(UUID uuid) {
      if (uuid != null && !MC.player.getUUID().equals(uuid)) {
         String name = this.knownName(uuid);
         if (this.credibleSubject(uuid, name) && AntiVanishText.isPlausiblePlayerName(name)) {
            this.pendingVanishes.removeIf(pending -> pending.uuid.equals(uuid));
            this.pendingVanishes.addLast(new SpectatorDetectorModule.PendingVanish(uuid, name, this.tickCounter + 20));
         }
      }
   }

   private void handleTabHidden(UUID uuid) {
      if (uuid != null && !MC.player.getUUID().equals(uuid)) {
         SpectatorDetectorModule.KnownPlayer known = this.knownPlayers.get(uuid);
         String name = known != null && known.name != null ? known.name : this.knownName(uuid);
         if (this.credibleSubject(uuid, name) && AntiVanishText.isPlausiblePlayerName(name)) {
            if (!this.recentMessageNames(name)) {
               this.reportHidden(uuid, name, false, "TAB visibility: hidden from TAB", "hidden from TAB", 100);
            }
         }
      }
   }

   private String knownName(UUID uuid) {
      SpectatorDetectorModule.KnownPlayer known = this.knownPlayers.get(uuid);
      if (known != null && known.name != null && !known.name.isBlank()) {
         return known.name;
      } else {
         if (MC.getConnection() != null) {
            PlayerInfo info = MC.getConnection().getPlayerInfo(uuid);
            if (info != null && info.getProfile() != null) {
               return info.getProfile().name();
            }
         }

         return null;
      }
   }

   private UUID knownUuid(String name) {
      for (SpectatorDetectorModule.KnownPlayer known : this.knownPlayers.values()) {
         if (known.name.equalsIgnoreCase(name)) {
            return known.uuid;
         }
      }

      return null;
   }

   private void confirmDeparture(String displayedName) {
      long now = System.currentTimeMillis();

      for (SpectatorDetectorModule.KnownPlayer known : this.knownPlayers.values()) {
         if (AntiVanishText.containsPlayerName(displayedName, known.name)) {
            this.confirmedDepartures.put(known.uuid, now + 5000L);
            this.trustedPlayers.remove(known.uuid);
            this.listedSinceMs.remove(known.uuid);
            this.pendingChimes.remove(known.uuid);
            if (known.uuid.equals(this.ghostId)) {
               this.criticalUntilMs = 0L;
               this.criticalWatcher = "";
               this.pendingChimes.clear();
               this.ghostId = null;
               this.ghostName = null;
               this.alarm.stop();
               StaffGhost.clear(id());
            }
            this.signals.removeIf(signal -> signal.proof != null && known.uuid.equals(signal.proof.id()));
            this.detections.values().removeIf(detection -> known.name.equalsIgnoreCase(detection.name));
            this.pendingVanishes.removeIf(pending -> pending.uuid.equals(known.uuid));
            this.hiddenSpells.remove(spellKey(known.name));
            SpectatorDetectorModule.Detection detection = this.detections.get(known.uuid.toString());
            if (detection != null && tabDepartureReason(detection.reason)) {
               this.detections.remove(known.uuid.toString());
            }

            this.signals
               .removeIf(
                  signal -> signal.type == SpectatorDetectorModule.SignalType.VANISH
                     && signal.subject.equalsIgnoreCase(known.name)
                     && tabDepartureReason(signal.reason)
               );
         }
      }
   }

   private void processPendingVanishes() {
      while (!this.pendingVanishes.isEmpty() && this.pendingVanishes.peekFirst().dueTick <= this.tickCounter) {
         SpectatorDetectorModule.PendingVanish pending = this.pendingVanishes.removeFirst();
         if (MC.getConnection().getPlayerInfo(pending.uuid) == null) {
            long now = System.currentTimeMillis();
            if (this.confirmedDepartures.getOrDefault(pending.uuid, 0L) <= now) {
               if (this.recentMessageNames(pending.name)) {
                  this.serverSendsLeaveMessages = true;
               } else {
                  Player remaining = MC.level.getPlayerByUUID(pending.uuid);
                  if (remaining != null && !remaining.isRemoved()) {
                     this.reportHidden(pending.uuid, pending.name, false, "TAB visibility: entity remained", "off TAB, still nearby", 100);
                  } else {
                     String reason = "TAB visibility: silent TAB disappearance";
                     int score = silentTabRemovalScore(this.serverSendsLeaveMessages);
                     if (this.serverSendsLeaveMessages) {
                        this.reportHidden(pending.uuid, pending.name, false, reason, "left TAB silently", score);
                     } else {
                        SpectatorDetectorModule.RegionProof proof = this.regionProof(pending.uuid);
                        if (proof != null) {
                           this.signals.addLast(new SpectatorDetectorModule.Signal(SpectatorDetectorModule.SignalType.VANISH, pending.name, proof, reason, score, now));
                           this.evaluateCritical();
                        }
                     }
                  }
               }
            }
         }
      }
   }

   static int silentTabRemovalScore(boolean serverSendsLeaveMessages) {
      return AntiVanishHeuristics.silentTabRemovalScore(serverSendsLeaveMessages);
   }

   static boolean tabDepartureReason(String reason) {
      return AntiVanishHeuristics.tabDepartureReason(reason);
   }

   private void cacheRecentMessage(String text) {
      if (text != null && !text.isBlank()) {
         long now = System.currentTimeMillis();
         this.recentMessages.addLast(new SpectatorDetectorModule.RecentMessage(text, now));
         for (KnownPlayer known : knownPlayers.values()) {
            if (AntiVanishText.looksLikeLeaveMessage(text, known.name)) {
               serverSendsLeaveMessages = true;
               confirmDeparture(known.name);
            }
         }

         while (!this.recentMessages.isEmpty() && (now - this.recentMessages.peekFirst().atMs > 8000L || this.recentMessages.size() > 64)) {
            this.recentMessages.removeFirst();
         }
      }
   }

   private boolean recentMessageNames(String name) {
      if (name != null && !name.isBlank()) {
         long now = System.currentTimeMillis();

         for (SpectatorDetectorModule.RecentMessage message : this.recentMessages) {
            if (now - message.atMs <= 8000L && AntiVanishText.looksLikeLeaveMessage(message.text, name)) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   private void sendCompletionProbe() {
      if (!completionProbe.get() || MC.getConnection() == null || completionRequestIds.size() >= 8) return;
      int id = nextCompletionId--;
      if (nextCompletionId == Integer.MIN_VALUE) nextCompletionId = -30000;
      completionRequestIds.put(id, System.currentTimeMillis() + 10000L);
      try {
         // Negative ids cannot claim a normal pending chat-completion request (vanilla uses nonnegative ids).
         MC.getConnection().send(new ServerboundCommandSuggestionPacket(id, PROBE_COMMAND + " "));
      } catch (RuntimeException failure) {
         completionRequestIds.remove(id);
         FlagLog.warn("STAFF", "AntiVanish", "Opt-in completion probe failed: " + failure.getClass().getSimpleName());
      }
   }

   private void processCompletionProbe() {
      List<String> current = this.pendingCompletionNames;
      if (current != null) {
         this.pendingCompletionNames = null;
         if (MC.getConnection() != null && MC.player != null) {
            Set<String> tabNames = new HashSet<>();

            for (PlayerInfo info : MC.getConnection().getOnlinePlayers()) {
               if (info != null && info.getProfile() != null && info.getProfile().name() != null) {
                  tabNames.add(info.getProfile().name().toLowerCase(Locale.ROOT));
               }
            }

            String self = MC.player.getName().getString();
            List<String> hidden = new ArrayList<>();

            for (String name : current) {
               if (StaffListModule.isStaffName(name)
                  && AntiVanishText.isPlausiblePlayerName(name)
                  && !name.equalsIgnoreCase(self)
                  && !tabNames.contains(name.toLowerCase(Locale.ROOT))
                  && !this.recentMessageNames(name)) {
                  hidden.add(name);
               }
            }

            if (!hidden.isEmpty() && hidden.size() <= 3) {
               for (String namex : hidden) {
                  this.reportHidden(this.knownUuid(namex), namex, false, "TAB visibility: hidden but targetable", "off TAB, still targetable", 90);
               }
            }
         }
      }
   }

   private static String departedPlayerName(Component component) {
      if (component != null && component.getContents() instanceof TranslatableContents translated && "multiplayer.player.left".equals(translated.getKey())) {
         Object[] args = translated.getArgs();
         if (args.length != 0 && args[0] != null) {
            return args[0] instanceof Component name ? name.getString() : String.valueOf(args[0]);
         } else {
            return "";
         }
      } else {
         return "";
      }
   }

   private void reportHidden(UUID uuid, String name, boolean spectator, String reason, String detail, int score) {
      this.upsertDetection(detectionKey(uuid, name), name, reason, score, System.currentTimeMillis() + 20000L);
      SpectatorDetectorModule.HiddenSpell spell = this.hiddenSpells.get(spellKey(name));
      if (spell == null || spell.spectator != spectator) {
         this.hiddenSpells.put(spellKey(name), new SpectatorDetectorModule.HiddenSpell(uuid, name, spectator, detail, this.regionProof(uuid), this.tickCounter));
      }
   }

   private void reviewSpells() {
      Iterator<SpectatorDetectorModule.HiddenSpell> it = this.hiddenSpells.values().iterator();

      while (it.hasNext()) {
         SpectatorDetectorModule.HiddenSpell spell = it.next();
         if (spell.spectator && MC.getConnection().getPlayerInfo(spell.uuid) == null) {
            it.remove();
         } else if (!spell.watched && spell.sinceTick < this.tickCounter) {
            SpectatorDetectorModule.RegionProof proof = this.regionProof(spell.uuid);
            if (proof == null && !spell.told) {
               proof = spell.proof;
            }

            if (proof != null) {
               spell.told = true;
               spell.watched = true;
               this.alertRegionalClue(spell.name, proof);
            } else if (!spell.told) {
               spell.told = true;
               this.news(spell.name, spell.how(), spell.detail);
            }
         }
      }
   }

   private boolean endSpell(String name, boolean spectator, long settleMs) {
      SpectatorDetectorModule.HiddenSpell spell = this.hiddenSpells.get(spellKey(name));
      if (spell != null && spell.spectator == spectator && System.currentTimeMillis() - spell.sinceMs >= settleMs) {
         this.hiddenSpells.remove(spellKey(name));
         this.detections.remove(detectionKey(spell.uuid, spell.name));
         if (spell.told) {
            this.news(spell.name, "is listed again", "");
         }

         return true;
      } else {
         return false;
      }
   }

   private SpectatorDetectorModule.RegionProof regionProof(UUID uuid) {
      if (uuid == null) {
         return null;
      } else {
         Player body = MC.level.getPlayerByUUID(uuid);
         if (body != null && body != MC.player) {
            return new SpectatorDetectorModule.RegionProof(uuid, Math.round(body.distanceTo(MC.player)));
         } else {
            return StaffListModule.isInYourRegion(uuid) ? new SpectatorDetectorModule.RegionProof(uuid, -1) : null;
         }
      }
   }

   private static String spellKey(String name) {
      return name.toLowerCase(Locale.ROOT);
   }

   private static String detectionKey(UUID uuid, String name) {
      return uuid != null ? uuid.toString() : name;
   }

   private void scanInvisiblePlayers() {
      double rangeSq = this.sensorRangeSq();

      for (Player player : MC.level.players()) {
         if (player != null && player != MC.player && player.isInvisible() && !(player.distanceToSqr(MC.player) > rangeSq) && this.realPlayer(player)) {
            this.triggerSensor(SpectatorDetectorModule.SignalType.INVISIBLE, player, "Invisible Entity: metadata flag", 35, 5000L);
         }
      }
   }

   private void inspectInvisibleEntity(int entityId) {
      if (MC.level.getEntity(entityId) instanceof Player player && player != MC.player && player.isInvisible()) {
         if (!(player.distanceToSqr(MC.player) > this.sensorRangeSq())) {
            if (this.realPlayer(player)) {
               this.triggerSensor(SpectatorDetectorModule.SignalType.INVISIBLE, player, "Invisible Entity: metadata flag", 35, 5000L);
            }
         }
      }
   }

   private boolean realPlayer(Player player) {
      return this.credibleSubject(player.getUUID(), profileName(player));
   }

   private static String profileName(Player player) {
      return player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().name();
   }

   private void inspectPositionalSound(SpectatorDetectorModule.Observation observation) {
      if (AntiVanishHeuristics.suspiciousSound(observation.detail)) {
         Vec3 source = observation.position();
         long now = System.currentTimeMillis();
         if (this.nearPlayer(source)
            && !this.hasVisibleCause(source)
            && !this.isExplosionRelated(source)
            && !this.isPoweredMechanism(source, observation.detail)
            && now - this.lastLocalActionMs >= 1000L
            && !this.recentlySelfPlaced(source)
            && !this.villagerToggledDoor(observation.detail, source)
            && (!this.selfContainerActive() || !isContainerSignal(observation.detail))) {
            this.triggerSensor(SpectatorDetectorModule.SignalType.SOUND, this.locatedSubject(source), "Suspicious Sound: " + shortId(observation.detail), 14, 3000L);
         }
      }
   }

   private void inspectEntitySound(SpectatorDetectorModule.Observation observation) {
      if (AntiVanishHeuristics.suspiciousSound(observation.detail)) {
         if (MC.level.getEntity(observation.entityId) instanceof Player player
            && player != MC.player
            && player.isInvisible()
            && player.distanceToSqr(MC.player) <= this.sensorRangeSq()
            && this.realPlayer(player)) {
            this.triggerSensor(SpectatorDetectorModule.SignalType.SOUND, player, "Suspicious Sound: invisible source", 16, 3000L);
         }
      }
   }

   private void inspectParticle(SpectatorDetectorModule.Observation observation) {
      if (AntiVanishHeuristics.suspiciousParticle(observation.detail)) {
         Vec3 source = observation.position();
         long now = System.currentTimeMillis();
         if (this.nearPlayer(source)
            && !this.hasVisibleCause(source)
            && !this.isExplosionRelated(source)
            && now - this.lastLocalActionMs >= 900L
            && !this.hasAmbientParticleSource(source, observation.detail)) {
            String particle = shortId(observation.detail);
            if (!particle.equals("block") && !particle.contains("smoke") || !this.nearSelf(source, 6.25)) {
               if (!particle.equals("block") && !particle.contains("smoke") || this.particleBurstReady(particle, now)) {
                  this.triggerSensor(
                     SpectatorDetectorModule.SignalType.PARTICLE, this.locatedSubject(source), "Unexplained particle: " + shortId(observation.detail), 16, 3000L
                  );
               }
            }
         }
      }
   }

   private boolean villagerToggledDoor(String blockId, Vec3 source) {
      String path = AntiVanishHeuristics.path(blockId);
      boolean doorLike = path.contains("door") && !path.contains("trapdoor") || path.contains("fence_gate");
      if (doorLike && source != null && MC.level != null) {
         for (Entity entity : MC.level.entitiesForRendering()) {
            if (entity.getType() == net.minecraft.world.entity.EntityTypes.VILLAGER && entity.position().distanceToSqr(source) <= 9.0) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   private boolean recentlySelfPlaced(Vec3 source) {
      if (source == null) {
         return false;
      } else {
         Long until = this.selfPlacedBlocks.get(BlockPos.containing(source).asLong());
         return until != null && until > System.currentTimeMillis();
      }
   }

   private void rememberExplosion(SpectatorDetectorModule.Observation observation) {
      long now = System.currentTimeMillis();
      this.recentExplosions
         .addLast(new SpectatorDetectorModule.ExplosionEvent(observation.position(), Math.max(2.0, parseDouble(observation.detail, 4.0) + 4.0), now));

      while (this.recentExplosions.size() > 8) {
         this.recentExplosions.removeFirst();
      }
   }

   private boolean particleBurstReady(String particle, long now) {
      Deque<Long> burst = this.weakParticleBursts.computeIfAbsent(particle, ignored -> new ArrayDeque<>());
      burst.addLast(now);

      while (!burst.isEmpty() && now - burst.peekFirst() > 2000L) {
         burst.removeFirst();
      }

      if (burst.size() < 2) {
         return false;
      } else {
         burst.clear();
         return true;
      }
   }

   private void triggerSensor(SpectatorDetectorModule.SignalType type, String where, String reason, int weight, long cooldownMs) {
      this.addSignal(type, where, null, reason, weight, cooldownMs);
   }

   private void triggerSensor(SpectatorDetectorModule.SignalType type, Player player, String reason, int weight, long cooldownMs) {
      this.addSignal(type, profileName(player), this.regionProof(player.getUUID()), reason, weight, cooldownMs);
   }

   private void addSignal(SpectatorDetectorModule.SignalType type, String subject, SpectatorDetectorModule.RegionProof proof, String reason, int weight, long cooldownMs) {
      long now = System.currentTimeMillis();
      String cooldownKey = type.name() + "|" + subject.toLowerCase(Locale.ROOT);
      long last = this.signalCooldowns.getOrDefault(cooldownKey, 0L);
      if (now - last >= cooldownMs) {
         this.signalCooldowns.put(cooldownKey, now);
         this.signals.addLast(new SpectatorDetectorModule.Signal(type, subject, proof, reason, weight, now));
         this.upsertDetection("signal:" + cooldownKey, subject, reason, weight, now + 20000L);
         this.announceTrigger(cooldownKey, subject, proof != null, reason);
         this.evaluateCritical();
      }
   }

   private void evaluateCritical() {
      long now = System.currentTimeMillis();
      pruneSignals(now);
      if (now - lastCriticalMs < CRITICAL_COOLDOWN_MS) return;
      for (Signal watcher : signals) {
         if (watcher.proof == null) continue;
         EnumMap<SignalType, Integer> strongest = new EnumMap<>(SignalType.class);
         for (Signal signal : signals) {
            // Unattributed environmental sounds must not be blamed on an unrelated named player.
            if (signal.proof != null && signal.proof.id().equals(watcher.proof.id())) strongest.merge(signal.type, signal.weight, Math::max);
         }
         int score = strongest.values().stream().mapToInt(Integer::intValue).sum();
         if (AntiVanishHeuristics.corroborated(strongest.size(), score, true)) {
            alertRegionalClue(watcher.subject, watcher.proof);
            return;
         }
      }
   }

   private void alertRegionalClue(String name, SpectatorDetectorModule.RegionProof proof) {
      long now = System.currentTimeMillis();
      this.lastCriticalMs = now;
      this.criticalUntilMs = now + 7000L;
      this.criticalWatcher = name;
      this.pendingChimes.add(proof.id());
      this.ghostId = proof.id();
      this.ghostName = name;
   }

   private void playPendingChime() {
      if (pendingChimes.isEmpty()) return;
      boolean covered = pendingChimes.stream().allMatch(StaffListModule::alarmedRecently);
      pendingChimes.clear();
      if (!covered) {
         if (sound.get()) alarm.start(StaffAlarm.GUARDIAN, 3.0F, 1.0F);
         StaffGhost.watching(id(), ghostId, ghostName, 0, "Visibility / local activity clues (not proof of watching)");
      }
   }

   private void news(String name, String what, String detail) {
      notifyLocal(NameFilter.apply(name) + " " + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
   }

   private void notifyLocal(String message) {
      FlagLog.warn("STAFF", "AntiVanish", message);
      if (chat.get()) ClientNotify.warning("[Anti Vanish] " + message);
      if (toast.get() && MC.gui != null) SystemToast.addOrUpdate(MC.gui.toastManager(), TOAST_ID,
         Component.literal("Anti Vanish - evidence only"), Component.literal(message));
   }

   private void queueGamemodeUpdates(ClientboundPlayerInfoUpdatePacket info) {
      if (!info.actions().contains(Action.ADD_PLAYER)) {
         for (Entry entry : info.entries()) {
            if (entry != null && entry.gameMode() != null && entry.profileId() != null) {
               this.processObservation(SpectatorDetectorModule.Observation.gamemode(entry.profileId(), entry.gameMode().getName()));
            }
         }
      }
   }

   private void announceGamemode(UUID uuid, String gameMode) {
      if (uuid != null && (MC.player == null || !MC.player.getUUID().equals(uuid))) {
         SpectatorDetectorModule.KnownPlayer known = this.knownPlayers.get(uuid);
         if (known != null && StaffListModule.isStaffName(known.name)) {
            if (GameType.SPECTATOR.getName().equals(gameMode)) {
               if (this.credibleSubject(uuid, known.name)) {
                  this.reportHidden(uuid, known.name, true, "TAB visibility: switched to spectator", "", 100);
                  return;
               }
            } else if (this.endSpell(known.name, true, 0L)) {
               return;
            }

            this.news(known.name, "switched game mode to " + gameMode, "");
         }
      }
   }

   private void announceTrigger(String eventKey, String subject, boolean named, String reason) {
      long now = System.currentTimeMillis();
      Long last = announceCooldowns.get(eventKey);
      if (last != null && now - last < ANNOUNCE_COOLDOWN_MS) return;
      while (!announceTimes.isEmpty() && now - announceTimes.peekFirst() > ANNOUNCE_WINDOW_MS) announceTimes.removeFirst();
      if (announceTimes.size() >= MAX_ANNOUNCE_PER_WINDOW) return;
      announceCooldowns.put(eventKey, now);
      announceTimes.addLast(now);
      String detail = subject == null || subject.isBlank() || "Unknown".equalsIgnoreCase(subject) ? ""
         : " (" + (named ? NameFilter.apply(subject) : subject) + ")";
      notifyLocal(reason + detail + " - clue only");
   }

   private void upsertDetection(String key, String name, String reason, int score, long expiresAt) {
      SpectatorDetectorModule.Detection existing = this.detections.get(key);
      if (existing == null) {
         this.detections.put(key, new SpectatorDetectorModule.Detection(name, reason, score, expiresAt));
      } else {
         existing.name = name;
         existing.expiresAt = Math.max(existing.expiresAt, expiresAt);
         if (score >= existing.score) {
            existing.reason = reason;
            existing.score = score;
         }
      }
   }

   private List<SpectatorDetectorModule.HudEntry> hudSnapshot() {
      long now = System.currentTimeMillis();
      List<SpectatorDetectorModule.HudEntry> out = new ArrayList<>();
      if (now < this.criticalUntilMs) {
         out.add(new SpectatorDetectorModule.HudEntry("CRITICAL", this.criticalWatcher));
      }

      Set<String> seenRows = new HashSet<>();
      this.detections
         .values()
         .stream()
         .filter(detection -> detection.expiresAt > now)
         .filter(SpectatorDetectorModule::detectionWorthShowing)
         .sorted(
            Comparator.<SpectatorDetectorModule.Detection>comparingInt(detection -> detection.score)
               .reversed()
               .thenComparing(detection -> detection.name, String.CASE_INSENSITIVE_ORDER)
         )
         .map(detection -> new SpectatorDetectorModule.HudEntry(detection.name, detection.reason))
         .filter(entry -> seenRows.add(hudTag(entry) + "\u0000" + hudValue(entry)))
         .limit(4L)
         .forEach(out::add);
      return List.copyOf(out);
   }

   private void pruneState() {
      long now = System.currentTimeMillis();
      this.completionRequestIds.values().removeIf(expires -> expires <= now);
      this.hiddenSpells.values().removeIf(spell -> now - spell.sinceMs > 120000L);
      this.knownPlayers.entrySet().removeIf(entry -> now - entry.getValue().lastListedMs > 120000L
         && MC.level.getPlayerByUUID(entry.getKey()) == null);
      this.trustedPlayers.retainAll(this.knownPlayers.keySet());
      this.detections.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
      this.signalCooldowns.entrySet().removeIf(entry -> now - entry.getValue() > 60000L);
      this.announceCooldowns.entrySet().removeIf(entry -> now - entry.getValue() > 60000L);
      this.selfPlacedBlocks.entrySet().removeIf(entry -> entry.getValue() <= now);
      this.confirmedDepartures.entrySet().removeIf(entry -> entry.getValue() <= now);
      this.automatedMechanisms.entrySet().removeIf(entry -> entry.getValue() <= now);
      this.weakParticleBursts.values().removeIf(burst -> {
         while (!burst.isEmpty() && now - burst.peekFirst() > 2000L) {
            burst.removeFirst();
         }

         return burst.isEmpty();
      });

      while (!this.recentExplosions.isEmpty() && now - this.recentExplosions.peekFirst().timeMs > 3000L) {
         this.recentExplosions.removeFirst();
      }

      this.pruneSignals(now);
   }

   private void pruneSignals(long now) {
      while (!this.signals.isEmpty() && now - this.signals.peekFirst().timeMs > 15000L) {
         this.signals.removeFirst();
      }
   }

   private boolean nearPlayer(Vec3 source) {
      return source != null && source.distanceToSqr(MC.player.position()) <= this.sensorRangeSq();
   }

   private boolean nearSelf(Vec3 source, double maxDistSq) {
      return source != null && MC.player != null && source.distanceToSqr(MC.player.position()) < maxDistSq;
   }

   private boolean selfContainerActive() {
      return System.currentTimeMillis() - this.lastContainerActivityMs < 2500L;
   }

   private static boolean isContainerSignal(String id) {
      String path = AntiVanishHeuristics.path(id);
      return path.contains("chest") || path.contains("barrel") || path.contains("shulker");
   }

   private String locatedSubject(Vec3 source) {
      if (source != null && MC.player != null) {
         Vec3 me = MC.player.position();
         double dx = source.x - me.x;
         double dy = source.y - me.y;
         double dz = source.z - me.z;
         long dist = Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
         String dir = compass(dx, dz);
         return dir.isEmpty() ? dist + "m" : dist + "m " + dir;
      } else {
         return "nearby";
      }
   }

   private static String compass(double dx, double dz) {
      String ns = dz < -1.0 ? "N" : (dz > 1.0 ? "S" : "");
      String ew = dx > 1.0 ? "E" : (dx < -1.0 ? "W" : "");
      return ns + ew;
   }

   private boolean hasVisibleCause(Vec3 source) {
      if (source == null) {
         return true;
      } else {
         for (Entity entity : MC.level.entitiesForRendering()) {
            if (entity != MC.player) {
               if (entity instanceof Player player) {
                  if (!player.isInvisible() && entity.position().distanceToSqr(source) <= 16.0) {
                     return true;
                  }
               } else if (entity instanceof Projectile && entity.position().distanceToSqr(source) <= 16.0) {
                  return true;
               }
            }
         }

         return false;
      }
   }

   private boolean isExplosionRelated(Vec3 source) {
      if (source == null) {
         return false;
      } else {
         long now = System.currentTimeMillis();

         while (!this.recentExplosions.isEmpty() && now - this.recentExplosions.peekFirst().timeMs > 3000L) {
            this.recentExplosions.removeFirst();
         }

         for (SpectatorDetectorModule.ExplosionEvent explosion : this.recentExplosions) {
            if (explosion.center.distanceToSqr(source) <= explosion.radius * explosion.radius) {
               return true;
            }
         }

         return false;
      }
   }

   private boolean hasAmbientParticleSource(Vec3 source, String particleId) {
      if (source != null && shortId(particleId).contains("smoke")) {
         BlockPos center = BlockPos.containing(source);

         for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
               for (int dz = -2; dz <= 2; dz++) {
                  BlockPos pos = center.offset(dx, dy, dz);
                  Identifier id = BuiltInRegistries.BLOCK.getKey(MC.level.getBlockState(pos).getBlock());
                  String path = shortId(id == null ? "" : id.toString());
                  if (path.contains("campfire")
                     || path.contains("furnace")
                     || path.contains("smoker")
                     || path.contains("torch")
                     || path.contains("fire")
                     || path.contains("candle")
                     || path.contains("respawn_anchor")) {
                     return true;
                  }
               }
            }
         }

         return false;
      } else {
         return false;
      }
   }

   private boolean isPoweredMechanism(Vec3 source, String soundId) {
      String path = shortId(soundId);
      if (!path.contains("door") && !path.contains("trapdoor")) {
         return false;
      } else {
         BlockPos center = BlockPos.containing(source);
         long now = System.currentTimeMillis();

         for (int dy = -1; dy <= 1; dy++) {
            BlockPos pos = center.offset(0, dy, 0);
            BlockState state = MC.level.getBlockState(pos);
            long key = pos.asLong();
            boolean powered = state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED);
            if (powered || MC.level.hasNeighborSignal(pos)) {
               this.automatedMechanisms.put(key, now + 5000L);
               return true;
            }

            if (this.automatedMechanisms.getOrDefault(key, 0L) > now) {
               return true;
            }
         }

         return false;
      }
   }

   private double sensorRangeSq() {
      double r = 64.0;
      return r * r;
   }

   private static double parseDouble(String value, double fallback) {
      if (value == null) return fallback;
      try {
         double number = Double.parseDouble(value);
         return Double.isFinite(number) ? number : fallback;
      } catch (NumberFormatException invalid) {
         return fallback;
      }
   }

   private static String soundId(Identifier id) {
      return id == null ? "" : id.toString();
   }

   private static String shortId(String id) {
      return AntiVanishHeuristics.path(id);
   }

   private void resetRuntime() {
      this.packetEpoch.incrementAndGet();
      this.packetQueue.clear();
      this.overflow = false;
      this.alarm.stop();
      StaffGhost.clear(id());
      this.knownPlayers.clear();
      this.detections.clear();
      this.signalCooldowns.clear();
      this.announceCooldowns.clear();
      this.announceTimes.clear();
      this.selfPlacedBlocks.clear();
      this.confirmedDepartures.clear();
      this.automatedMechanisms.clear();
      this.weakParticleBursts.clear();
      this.signals.clear();
      this.recentExplosions.clear();
      this.pendingVanishes.clear();
      this.hiddenSpells.clear();
      this.shard.clear();
      this.tickCounter = 0;
      this.lastLocalActionMs = 0L;
      this.lastContainerActivityMs = 0L;
      this.lastCriticalMs = 0L;
      this.criticalUntilMs = 0L;
      this.criticalWatcher = "";
      this.pendingChimes.clear();
      this.ghostId = null;
      this.ghostName = null;
      this.recentMessages.clear();
      this.listedSinceMs.clear();
      this.trustedPlayers.clear();
      this.serverSendsLeaveMessages = false;
      this.completionRequestIds.clear();
      this.pendingCompletionNames = null;

   }
   private static final class Detection {
      String name;
      String reason;
      int score;
      long expiresAt;

      Detection(String name, String reason, int score, long expiresAt) {
         this.name = name;
         this.reason = reason;
         this.score = score;
         this.expiresAt = expiresAt;
      }
   }
   private record PacketWork(long epoch, Packet<?> packet, boolean outbound, long atMs) {}

   private record ExplosionEvent(Vec3 center, double radius, long timeMs) {
   }
   private static final class HiddenSpell {
      final UUID uuid;
      final String name;
      final boolean spectator;
      final String detail;
      final SpectatorDetectorModule.RegionProof proof;
      final int sinceTick;
      final long sinceMs = System.currentTimeMillis();
      boolean told;
      boolean watched;

      HiddenSpell(UUID uuid, String name, boolean spectator, String detail, SpectatorDetectorModule.RegionProof proof, int sinceTick) {
         this.uuid = uuid;
         this.name = name;
         this.spectator = spectator;
         this.detail = detail;
         this.proof = proof;
         this.sinceTick = sinceTick;
      }

      String how() { return this.spectator ? "switched to spectator" : "has a TAB visibility clue"; }
   }
   public record HudEntry(String name, String reason) {
   }
   private record KnownPlayer(UUID uuid, String name, long lastListedMs) {
   }
   private record Observation(SpectatorDetectorModule.ObservationType type, UUID profileId, int entityId, double x, double y, double z, String detail) {
      static SpectatorDetectorModule.Observation tabRemove(UUID id) {
         return new SpectatorDetectorModule.Observation(SpectatorDetectorModule.ObservationType.TAB_REMOVE, id, -1, 0.0, 0.0, 0.0, "");
      }

      static SpectatorDetectorModule.Observation tabHide(UUID id) {
         return new SpectatorDetectorModule.Observation(SpectatorDetectorModule.ObservationType.TAB_HIDE, id, -1, 0.0, 0.0, 0.0, "");
      }

      static SpectatorDetectorModule.Observation gamemode(UUID id, String mode) {
         return new SpectatorDetectorModule.Observation(SpectatorDetectorModule.ObservationType.GAMEMODE, id, -1, 0.0, 0.0, 0.0, mode);
      }

      static SpectatorDetectorModule.Observation playerLeft(String name) {
         return new SpectatorDetectorModule.Observation(SpectatorDetectorModule.ObservationType.PLAYER_LEFT, null, -1, 0.0, 0.0, 0.0, name);
      }

      static SpectatorDetectorModule.Observation systemChat(String text) {
         return new SpectatorDetectorModule.Observation(SpectatorDetectorModule.ObservationType.SYSTEM_CHAT, null, -1, 0.0, 0.0, 0.0, text);
      }

      static SpectatorDetectorModule.Observation entityMetadata(int id) {
         return new SpectatorDetectorModule.Observation(SpectatorDetectorModule.ObservationType.ENTITY_METADATA, null, id, 0.0, 0.0, 0.0, "");
      }

      static SpectatorDetectorModule.Observation explosion(Vec3 center, float radius) {
         return new SpectatorDetectorModule.Observation(SpectatorDetectorModule.ObservationType.EXPLOSION, null, -1, center.x, center.y, center.z, Float.toString(radius));
      }

      static SpectatorDetectorModule.Observation entitySound(int id, String sound) {
         return new SpectatorDetectorModule.Observation(SpectatorDetectorModule.ObservationType.ENTITY_SOUND, null, id, 0.0, 0.0, 0.0, sound);
      }

      static SpectatorDetectorModule.Observation position(SpectatorDetectorModule.ObservationType type, double x, double y, double z, String detail) {
         return new SpectatorDetectorModule.Observation(type, null, -1, x, y, z, detail);
      }

      Vec3 position() {
         return new Vec3(this.x, this.y, this.z);
      }
   }
   private static enum ObservationType {
      TAB_REMOVE,
      TAB_HIDE,
      PLAYER_LEFT,
      SYSTEM_CHAT,
      ENTITY_METADATA,
      POSITIONAL_SOUND,
      ENTITY_SOUND,
      PARTICLE,
      EXPLOSION,
      GAMEMODE;
   }
   private record PendingVanish(UUID uuid, String name, int dueTick) {
   }
   private record RecentMessage(String text, long atMs) {
   }
   private record RegionProof(UUID id, int distance) {
   }
   private record Signal(SpectatorDetectorModule.SignalType type, String subject, SpectatorDetectorModule.RegionProof proof, String reason, int weight, long timeMs) {
   }
   private static enum SignalType {
      VANISH,
      INVISIBLE,
      PARTICLE,
      SOUND;
   }
}
