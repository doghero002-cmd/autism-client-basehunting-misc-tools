package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.util.FlagLog;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.phys.Vec3;

/**
 * Flag Detector.
 *
 * Watches for signs that the anti-cheat (or the server) is correcting / rejecting the client's
 * automated behaviour, and logs every event to <autism-client>/flag-log.txt so the client can be
 * improved from real data. Detection channels:
 *
 *  - SETBACK / rubber-band: the server teleports you back after our movement (the #1 Grim flag
 *    tell). We flag when a position packet yanks you more than {@code setbackDist} blocks from
 *    where you were, especially while an automation module is on.
 *  - ROTATION correction: a position packet that also forces a large yaw/pitch change (server is
 *    rejecting our rotation).
 *  - KICK / disconnect reasons ( Grim / anti-cheat / spam / flying ).
 *  - Module-reported events: break-desync, place-fail, slot-desync (other modules call
 *    {@link #report(...)}).
 *
 * Read the log with the {@code path} in {@link FlagLog}; each line is timestamped + categorized so
 * you can see exactly which module / movement style is getting flagged.
 */
public final class FlagDetectorModule extends Module {

    private final BoolSetting detectSetback = add(new BoolSetting("detect-setback", "Detect setbacks", true)
        .description("Flag server rubber-bands (position teleports) - the main Grim flag tell.").group("Detect"));
    private final IntSetting setbackDist = add(new IntSetting("setback-dist", "Setback distance", 3, 1, 50, 1)
        .description("Minimum teleport distance (blocks) that counts as a rubber-band.").group("Detect"));
    private final BoolSetting detectRotation = add(new BoolSetting("detect-rotation", "Detect rotation corrections", true)
        .description("Flag position packets that also force a large view change (server rejecting our rotation).").group("Detect"));
    private final BoolSetting detectKick = add(new BoolSetting("detect-kick", "Log kick reasons", true)
        .description("Log disconnect/kick reasons (anti-cheat / flying / spam).").group("Detect"));
    private final BoolSetting chat = add(new BoolSetting("chat-alerts", "Chat alerts", true)
        .description("Also echo flags to chat so you see them live.").group("General"));
    private final BoolSetting hud = add(new BoolSetting("hud-counter", "HUD counter", true)
        .description("Show a live flag/setback counter panel on screen.").group("HUD"));
    private final BoolSetting hudOnlyInGame = add(new BoolSetting("hud-only-in-game", "HUD only in game", true)
        .description("Hide the panel on menus.").group("HUD").visibleWhen(() -> hud.get()));
    private final BoolSetting hudSession = add(new BoolSetting("hud-session", "HUD session time", true)
        .description("Show how long telemetry has been recording.").group("HUD").visibleWhen(() -> hud.get()));
    private final autismclient.api.module.IntSetting hudX = add(new autismclient.api.module.IntSetting("hud-x", "HUD X", 4, 0, 4000, 1)
        .description("Panel X position.").group("HUD").visibleWhen(() -> hud.get()));
    private final autismclient.api.module.IntSetting hudY = add(new autismclient.api.module.IntSetting("hud-y", "HUD Y", 4, 0, 4000, 1)
        .description("Panel Y position.").group("HUD").visibleWhen(() -> hud.get()));
    private final autismclient.api.module.IntSetting hudWidth = add(new autismclient.api.module.IntSetting("hud-width", "HUD width", 152, 110, 240, 2)
        .description("Panel width.").group("HUD").visibleWhen(() -> hud.get()));
    private final autismclient.api.module.ColorSetting hudAccent = add(new autismclient.api.module.ColorSetting("hud-accent", "HUD accent", 0xFFFF6C6C)
        .description("Panel accent color.").group("HUD").visibleWhen(() -> hud.get()));
    private final BoolSetting onlyWhileAutomating = add(new BoolSetting("only-automating", "Only while automating", false)
        .description("Only log setbacks while an automation module (tunnel/build/mine) is likely running (less noise).").group("Detect"));
    private final BoolSetting rtpGrace = add(new BoolSetting("rtp-grace", "RTP grace period", true)
        .description("Suppress setback flags for a few seconds after /rtp (a legit self-teleport).").group("Grace"));
    private final IntSetting rtpGraceSeconds = add(new IntSetting("rtp-grace-seconds", "RTP grace (s)", 10, 1, 60, 1)
        .description("Seconds of setback suppression after /rtp.").group("Grace").visibleWhen(() -> rtpGrace.get()));
    private final BoolSetting pearlGrace = add(new BoolSetting("pearl-grace", "Ender pearl grace", true)
        .description("Suppress setback flags for a few seconds after throwing an ender pearl (a legit self-teleport).").group("Grace"));
    private final IntSetting pearlGraceSeconds = add(new IntSetting("pearl-grace-seconds", "Pearl grace (s)", 3, 1, 30, 1)
        .description("Seconds of setback suppression after an ender pearl throw.").group("Grace").visibleWhen(() -> pearlGrace.get()));
    private final BoolSetting predictSpeed = add(new BoolSetting("predict-speed", "Predict speed flags", true)
        .description("Client-side Grim Simulation(SpeedA) emulator (Apex): tracks our own move deltas vs Grim's thresholds and warns BEFORE the server flags.").group("Predict"));

    private Vec3 lastPos = null;
    private long lastSetbackLogMs = 0;
    private static volatile long graceUntilMs = 0L;

    /** Begin a setback-suppression grace window (called on /rtp and ender-pearl throws). */
    public static void grace(long millis) {
        graceUntilMs = Math.max(graceUntilMs, System.currentTimeMillis() + millis);
    }

    /** True while a teleport grace window is active (setbacks suppressed). */
    private static boolean inGrace() {
        return System.currentTimeMillis() < graceUntilMs;
    }

    public FlagDetectorModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":flag-detector", "Flag Detector", category,
            "Logs anti-cheat flags / setbacks / desyncs to flag-log.txt so the client can be improved.");
    }

    @Override
    public void onEnable() {
        lastPos = null;
        lastSetbackLogMs = 0;
        lastDimension = null;
        lastDeathMs = 0;
        FlagLog.info("INFO", "FlagDetector", "enabled path=" + FlagLog.path());
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    private net.minecraft.resources.Identifier lastDimension = null;
    private long lastDeathMs = 0;
    private long joinGraceUntilMs = 0;
    private boolean wasDead = false;

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        lastPos = mc.player.position();

        // Track dimension changes + deaths + joins: these cause legitimate big teleports that must
        // not be flagged. Grace a few seconds around each.
        net.minecraft.resources.Identifier dim = mc.level.dimension().identifier();
        if (lastDimension == null) {
            lastDimension = dim;
            joinGraceUntilMs = System.currentTimeMillis() + 5000; // join / respawn grace
        } else if (!dim.equals(lastDimension)) {
            lastDimension = dim;
            grace(5000L); // dimension change (portal / end gateway)
            FlagLog.info("GRACE", "FlagDetector", "dimension change -> 5s grace");
        }
        boolean dead = mc.player.isDeadOrDying();
        if (dead) {
            wasDead = true;
        } else if (wasDead) {
            // Just respawned after being dead: the respawn teleport is legitimate, not a flag.
            wasDead = false;
            lastDeathMs = System.currentTimeMillis();
            grace(8000L);
            FlagLog.info("GRACE", "FlagDetector", "respawn -> 8s grace");
        }

        if (predictSpeed.get()) tickSpeedEmulator(mc);
    }

    // ---- Grim SpeedA emulator (Apex azheng Emulator): predict speed flags before the server ----

    private int emuSlimeTicks, emuGroundTicks, emuIceTicks, emuUnderBlockTicks;
    private boolean emuOnStairSlab;
    private int speedABuffer = 0;
    private double emuLastX = Double.NaN, emuLastZ;
    private long lastPredictLogMs = 0;

    private void tickSpeedEmulator(Minecraft mc) {
        // Grim exempts these states from Simulation SpeedA; without the same exemptions we'd
        // false-predict on every elytra flight / boat ride / knockback / lava boost.
        if (mc.player.isFallFlying() || mc.player.isPassenger() || mc.player.isSwimming()
            || mc.player.isAutoSpinAttack()
            || mc.player.hasEffect(net.minecraft.world.effect.MobEffects.LEVITATION)
            || mc.player.isInLava() || inGrace()) {
            speedABuffer = Math.max(0, speedABuffer - 1);
            imminentSpeedFlag = false;
            emuLastX = Double.NaN;
            return;
        }
        if (mc.player.onGround()) {
            emuGroundTicks++;
            var under = mc.level.getBlockState(mc.player.blockPosition()
                .atY((int) (mc.player.getBoundingBox().minY - 0.5000001)));
            var block = under.getBlock();
            boolean onIce = block == net.minecraft.world.level.block.Blocks.ICE
                || block == net.minecraft.world.level.block.Blocks.PACKED_ICE
                || block == net.minecraft.world.level.block.Blocks.BLUE_ICE;
            emuIceTicks = com.autism.seedcracker.util.pure.GrimSpeedModel.updateTickState(emuIceTicks, onIce, 3);
            boolean onSlime = block == net.minecraft.world.level.block.Blocks.SLIME_BLOCK;
            emuSlimeTicks = com.autism.seedcracker.util.pure.GrimSpeedModel.updateTickState(emuSlimeTicks, onSlime, 8);
            emuOnStairSlab = block instanceof net.minecraft.world.level.block.StairBlock
                || block instanceof net.minecraft.world.level.block.SlabBlock;
            boolean underBlock = !mc.level.getBlockState(mc.player.blockPosition().above(2)).isAir();
            emuUnderBlockTicks = com.autism.seedcracker.util.pure.GrimSpeedModel.updateTickState(emuUnderBlockTicks, underBlock, 3);
        } else {
            emuGroundTicks = 0;
        }

        double x = mc.player.getX(), z = mc.player.getZ();
        if (!Double.isNaN(emuLastX)) {
            double deltaXZ = Math.hypot(x - emuLastX, z - emuLastZ);
            double threshold = grimSpeedThreshold(mc);
            int newBuffer = com.autism.seedcracker.util.pure.GrimSpeedModel.updateBuffer(speedABuffer, deltaXZ, threshold);
            boolean increased = newBuffer > speedABuffer;
            speedABuffer = newBuffer;
            if (increased && speedABuffer >= com.autism.seedcracker.util.Tuning.SPEED_BUFFER_WARN
                && System.currentTimeMillis() - lastPredictLogMs > 3000) {
                lastPredictLogMs = System.currentTimeMillis();
                log(mc, "PREDICT", String.format(java.util.Locale.ROOT,
                    "speed near-flag: dxz=%.3f threshold=%.3f buffer=%d", deltaXZ, threshold, speedABuffer));
            }
            imminentSpeedFlag = speedABuffer >= com.autism.seedcracker.util.Tuning.SPEED_BUFFER_WARN;
        }
        emuLastX = x;
        emuLastZ = z;
    }

    /** Grim Simulation speed threshold model (Apex emulate()) - math in GrimSpeedModel. */
    private double grimSpeedThreshold(Minecraft mc) {
        var speed = mc.player.getEffect(net.minecraft.world.effect.MobEffects.SPEED);
        int speedLevel = speed == null ? 0 : (speed.getAmplifier() + 1);
        return com.autism.seedcracker.util.pure.GrimSpeedModel.threshold(
            mc.player.onGround(), speedLevel, emuGroundTicks,
            emuSlimeTicks > 0, emuOnStairSlab, emuIceTicks > 0,
            emuUnderBlockTicks > 0 && mc.player.getDeltaMovement().y != 0.0,
            com.autism.seedcracker.util.Tuning.GRIM_SPEED_GROUND,
            com.autism.seedcracker.util.Tuning.GRIM_SPEED_AIR);
    }

    /** True while the speed emulator says we're close to a Grim SpeedA flag (modules can back off). */
    public static boolean speedFlagImminent() {
        return imminentSpeedFlag;
    }

    private static volatile boolean imminentSpeedFlag = false;

    @Override
    public boolean onPacketReceive(Packet<?> packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;

        if (packet instanceof ClientboundPlayerPositionPacket posPacket && detectSetback.get()) {
            handleSetback(mc, posPacket);
        } else if (packet instanceof ClientboundDisconnectPacket dc && detectKick.get()) {
            handleKick(mc, dc);
        }
        return false; // never block packets
    }

    /** Outgoing packets: /rtp command + ender-pearl throw start a teleport grace window. */
    @Override
    public boolean onPacketSend(Packet<?> packet) {
        // /rtp / tpaccept / home / warp etc -> teleport grace. Vanilla brigadier commands arrive
        // as a ChatCommand packet; DonutSMP plugin commands typed with a leading '/' arrive as Chat.
        if (rtpGrace.get() && packet instanceof net.minecraft.network.protocol.game.ServerboundChatCommandPacket cmd) {
            if (isTeleportCommand(cmd.command())) {
                grace(rtpGraceSeconds.get() * 1000L);
                FlagLog.info("GRACE", "FlagDetector", "teleport command -> " + rtpGraceSeconds.get() + "s grace");
            }
        }
        if (rtpGrace.get() && packet instanceof net.minecraft.network.protocol.game.ServerboundChatPacket chat) {
            String msg = chat.message();
            if (msg != null && msg.startsWith("/") && isTeleportCommand(msg.substring(1))) {
                grace(rtpGraceSeconds.get() * 1000L);
                FlagLog.info("GRACE", "FlagDetector", "teleport chat -> " + rtpGraceSeconds.get() + "s grace");
            }
        }
        // Ender pearl throw / chorus fruit (consumable self-teleports) -> grace.
        if (pearlGrace.get() && packet instanceof net.minecraft.network.protocol.game.ServerboundUseItemPacket) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                var held = mc.player.getMainHandItem();
                if (held.is(net.minecraft.world.item.Items.ENDER_PEARL)) {
                    grace(pearlGraceSeconds.get() * 1000L);
                    FlagLog.info("GRACE", "FlagDetector", "ender pearl throw -> " + pearlGraceSeconds.get() + "s grace");
                } else if (held.is(net.minecraft.world.item.Items.CHORUS_FRUIT)) {
                    grace(pearlGraceSeconds.get() * 1000L);
                    FlagLog.info("GRACE", "FlagDetector", "chorus fruit -> " + pearlGraceSeconds.get() + "s grace");
                }
            }
        }
        return false; // never block packets
    }

    private void handleSetback(Minecraft mc, ClientboundPlayerPositionPacket packet) {
        var change = packet.change();
        if (change == null) return;
        Vec3 target = change.position();
        Vec3 from = lastPos != null ? lastPos : mc.player.position();
        double dist = from.distanceTo(target);

        // Rotation correction: a position packet that also swings the view a lot. Apply the SAME
        // legit-teleport filters as setbacks: /rtp, pearls, respawn, dimension change, big jumps
        // all legitimately set rotation too (this used to false-flag every RTP).
        if (detectRotation.get() && !inGrace()
            && System.currentTimeMillis() - lastDeathMs >= 5000
            && System.currentTimeMillis() >= joinGraceUntilMs
            && !mc.player.isPassenger()
            && dist <= 64.0) {
            float yawDiff = Math.abs(net.minecraft.util.Mth.wrapDegrees(change.yRot() - mc.player.getYRot()));
            float pitchDiff = Math.abs(change.xRot() - mc.player.getXRot());
            if (yawDiff > 20f || pitchDiff > 20f) {
                log(mc, "ROTATION", "server forced view change yawDiff=" + (int) yawDiff
                    + " pitchDiff=" + (int) pitchDiff + " pos=" + fmt(target));
            }
        }

        if (dist < setbackDist.get()) return;
        if (onlyWhileAutomating.get() && !automationLikelyActive()) return;

        // ---- natural-occurrence filters (don't false-flag vanilla/legit teleports) ----
        // Teleport grace: suppress setbacks right after a legit self-teleport (/rtp, ender pearl,
        // tpaccept, dimension change, join/respawn).
        if (inGrace()) {
            if (chat.get()) AutismClientMessaging.sendPrefixed("§7[Flag] setback suppressed (teleport grace)");
            return;
        }
        // Recently died / joined / respawned: big legitimate teleports, not flags.
        if (System.currentTimeMillis() - lastDeathMs < 5000 || System.currentTimeMillis() < joinGraceUntilMs) return;
        // Riding a vehicle / boat / horse: the server moves the vehicle, not the player directly.
        if (mc.player.isPassenger()) return;
        // A real rubber-band is a SHORT pull-back toward where we were. A huge jump (>64 blocks)
        // is almost always a legitimate server/vanilla teleport (portal, /spawn, chorus fruit,
        // world border, plugin TP), not an anti-cheat correction.
        if (dist > 64.0) return;

        // Throttle identical setback spam to one per 2s.
        long now = System.currentTimeMillis();
        if (now - lastSetbackLogMs < 2000) return;
        lastSetbackLogMs = now;
        lastSetbackDist = dist;

        log(mc, "SETBACK", "rubber-band dist=" + String.format(java.util.Locale.ROOT, "%.2f", dist)
            + " from=" + fmt(from) + " to=" + fmt(target)
            + " automating=" + automationLikelyActive()
            + " modules=" + activeAutomationNames());
    }

    private void handleKick(Minecraft mc, ClientboundDisconnectPacket packet) {
        String reason = "";
        try { reason = packet.reason().getString(); } catch (Throwable ignored) {}
        String lower = reason.toLowerCase(java.util.Locale.ROOT);
        boolean suspicious = lower.contains("grim") || lower.contains("anti") || lower.contains("cheat")
            || lower.contains("fly") || lower.contains("spam") || lower.contains("packet")
            || lower.contains("invalid") || lower.contains("exploit");
        log(mc, suspicious ? "KICK" : "INFO", "disconnect reason=\"" + reason + "\" suspicious=" + suspicious);
    }

    /** The set of ALL module names currently enabled (drives log context). */
    private static final java.util.Set<String> ACTIVE_AUTOMATION =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Modules that drive automated movement/interaction (used for setback correlation). */
    private static final java.util.Set<String> MOVEMENT_MODULES = java.util.Set.of(
        "TunnelBaseFinderModule", "TunnelBaseWaterModule", "SchematicBuilderModule",
        "AutoMineModule", "SpawnerProtectModule", "AutoToolModule", "AutoEatModule",
        "AutoFireworkModule", "KeyPearlModule", "BoneDropperModule", "NetherTunnelFinderModule",
        "DonutRTPStashFinderModule", "RelogLoaderModule", "AutoTPAModule", "ShopBuyerModule",
        "AhSellModule", "AHSniperModule", "AHFlipperModule", "TPASpammerModule", "QuickMacroModule");

    /** True if a movement-driving automation module is currently enabled. */
    private static boolean automationLikelyActive() {
        for (String name : ACTIVE_AUTOMATION) if (MOVEMENT_MODULES.contains(name)) return true;
        return false;
    }

    /** Names of the currently-active modules (for richer log context). */
    public static String activeAutomationNames() {
        return ACTIVE_AUTOMATION.isEmpty() ? "none" : String.join(",", ACTIVE_AUTOMATION);
    }

    /**
     * The Module-lifecycle mixin reports every module's enable/disable here so the Flag Detector
     * can correlate flags with exactly which modules were active. No per-module edits needed.
     */
    public static void track(String moduleName, boolean active) {
        if (moduleName == null) return;
        if (active) ACTIVE_AUTOMATION.add(moduleName);
        else ACTIVE_AUTOMATION.remove(moduleName);
    }

    /** Convenience: track using the caller's simple class name. */
    public static void track(Object module, boolean active) {
        if (module == null) return;
        track(module.getClass().getSimpleName(), active);
    }

    public static void automationStarted() { /* legacy no-op; use track(name, true) */ }
    public static void automationStopped() { /* legacy no-op; use track(name, false) */ }

    private void log(Minecraft mc, String category, String detail) {
        FlagLog.flag(category, "FlagDetector", detail);
        tally(category);
        if (chat.get()) {
            AutismClientMessaging.sendPrefixed("§c[Flag] §7" + category + " §f" + detail);
        }
    }

    // ---- live HUD counters (tallied on every real flag, decayed over a 60s window) ----
    private static final java.util.Deque<long[]> EVENT_WINDOW = new java.util.ArrayDeque<>();
    private static final long WINDOW_MS = 60_000L;
    private static volatile int totalSetbacks, totalRotations, totalKicks, totalPredicts;
    private static volatile long sessionStartMs = -1L;
    private static volatile double lastSetbackDist = -1.0D;

    // category encoding for the sliding window deque
    private static final int C_SETBACK = 0, C_ROTATION = 1, C_KICK = 2, C_PREDICT = 3;

    private static void tally(String category) {
        if (category == null) return;
        long now = System.currentTimeMillis();
        int code;
        switch (category) {
            case "SETBACK" -> { totalSetbacks++; code = C_SETBACK; }
            case "ROTATION" -> { totalRotations++; code = C_ROTATION; }
            case "KICK" -> { totalKicks++; code = C_KICK; }
            case "PREDICT" -> { totalPredicts++; code = C_PREDICT; }
            default -> { return; }
        }
        synchronized (EVENT_WINDOW) {
            if (sessionStartMs < 0L) sessionStartMs = now;
            EVENT_WINDOW.addLast(new long[]{now, code});
            while (!EVENT_WINDOW.isEmpty() && now - EVENT_WINDOW.peekFirst()[0] > WINDOW_MS) EVENT_WINDOW.pollFirst();
        }
    }

    /** Live snapshot for the bypass-telemetry HUD. */
    public static HudStats hudStats() {
        long now = System.currentTimeMillis();
        int wSetback = 0, wRotation = 0, wKick = 0, wPredict = 0;
        synchronized (EVENT_WINDOW) {
            while (!EVENT_WINDOW.isEmpty() && now - EVENT_WINDOW.peekFirst()[0] > WINDOW_MS) EVENT_WINDOW.pollFirst();
            for (long[] e : EVENT_WINDOW) {
                switch ((int) e[1]) {
                    case C_SETBACK -> wSetback++;
                    case C_ROTATION -> wRotation++;
                    case C_KICK -> wKick++;
                    case C_PREDICT -> wPredict++;
                }
            }
        }
        long sessionElapsed = sessionStartMs < 0L ? 0L : now - sessionStartMs;
        return new HudStats(totalSetbacks, totalRotations, totalKicks, totalPredicts,
            wSetback, wRotation, wKick, wPredict, sessionElapsed, lastSetbackDist,
            speedFlagImminent());
    }

    public static void resetHudStats() {
        synchronized (EVENT_WINDOW) {
            EVENT_WINDOW.clear();
            totalSetbacks = 0;
            totalRotations = 0;
            totalKicks = 0;
            totalPredicts = 0;
            sessionStartMs = -1L;
            lastSetbackDist = -1.0D;
        }
    }

    public record HudStats(int totalSetbacks, int totalRotations, int totalKicks, int totalPredicts,
                           int wSetbacks, int wRotations, int wKicks, int wPredicts,
                           long sessionElapsedMs, double lastSetbackDist, boolean speedImminent) {
        public int windowAll() { return wSetbacks + wRotations + wKicks + wPredicts; }
        public int totalAll() { return totalSetbacks + totalRotations + totalKicks + totalPredicts; }
        public int heat() {
            int w = windowAll();
            if (w == 0) return 0;
            if (w < 3) return 1;
            if (w < 8) return 2;
            return 3;
        }
    }

    /** True for rtp-style random-teleport commands (rtp / tpr / wild / wilderness). */
    private static boolean isRtpCommand(String c) {
        if (c == null) return false;
        c = c.toLowerCase(java.util.Locale.ROOT).trim();
        return c.equals("rtp") || c.startsWith("rtp ") || c.equals("tpr") || c.startsWith("tpr ")
            || c.equals("wild") || c.startsWith("wild ") || c.equals("wilderness") || c.startsWith("wilderness ");
    }

    /** True for teleport-accept commands (tpaccept / tpyes / tpahere accept / spawn / home / warp). */
    private static boolean isTeleportCommand(String c) {
        if (c == null) return false;
        c = c.toLowerCase(java.util.Locale.ROOT).trim();
        if (isRtpCommand(c)) return true;
        return c.equals("tpaccept") || c.startsWith("tpaccept ") || c.equals("tpyes") || c.startsWith("tpyes ")
            || c.equals("tpyes") || c.equals("tpaconfirm") || c.equals("etpaccept")
            || c.equals("home") || c.startsWith("home ") || c.equals("spawn") || c.startsWith("spawn ")
            || c.equals("warp") || c.startsWith("warp ") || c.equals("tpa") || c.startsWith("tpa ")
            || c.equals("tpahere") || c.startsWith("tpahere ") || c.equals("back") || c.startsWith("back ");
    }

    /** True for rtp-style random-teleport commands (rtp / tpr / wild / wilderness). */
    private static boolean isRtp(String c) { return isRtpCommand(c); }

    /** Let other modules report their own flags/desyncs into the same log. */
    public static void report(String category, String module, String detail) {
        FlagLog.flag(category, module, detail);
    }

    private static String fmt(Vec3 v) {
        return String.format(java.util.Locale.ROOT, "%.1f,%.1f,%.1f", v.x, v.y, v.z);
    }

    @Override public String info() {
        return "log: flag-log.txt";
    }

    // ---- HUD panel rendering (called from the InGameHud mixin) ----

    /** Render the bypass-telemetry panel. No-op unless the module + hud option are on. */
    public static void renderHud(net.minecraft.client.gui.GuiGraphicsExtractor context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.font == null) return;
        var mod = autismclient.modules.ModuleRegistry.get(SeedcrackerAddon.ID + ":flag-detector");
        if (!(mod instanceof FlagDetectorModule fd) || !fd.isEnabled() || !fd.hud.get()) return;
        if (fd.hudOnlyInGame.get() && (mc.player == null || mc.getConnection() == null)) return;
        if (mc.gui != null && mc.gui.hud.isHidden()) return;
        if (autismclient.modules.PackHideState.isActive()) return;

        HudStats s = hudStats();
        int width = fd.hudWidth.get();
        int contentWidth = width - 12;
        java.util.List<autismclient.gui.vanillaui.direct.DirectHudPanelRenderer.Row> rows = new java.util.ArrayList<>();

        rows.add(autismclient.gui.vanillaui.direct.DirectHudPanelRenderer.Row.body(
            trimHud(mc.font, "last 60s: " + s.windowAll() + "  total: " + s.totalAll(), contentWidth), 0xFFE8E8E8));
        rows.add(autismclient.gui.vanillaui.direct.DirectHudPanelRenderer.Row.body(
            trimHud(mc.font, "setbacks: " + s.wSetbacks() + " (" + s.totalSetbacks() + ")", contentWidth), 0xFFFF8A80));
        rows.add(autismclient.gui.vanillaui.direct.DirectHudPanelRenderer.Row.body(
            trimHud(mc.font, "rot-corr: " + s.wRotations() + " (" + s.totalRotations() + ")", contentWidth), 0xFFFFCC80));
        if (s.totalKicks() > 0) {
            rows.add(autismclient.gui.vanillaui.direct.DirectHudPanelRenderer.Row.body(
                trimHud(mc.font, "kicks: " + s.wKicks() + " (" + s.totalKicks() + ")", contentWidth), 0xFFEF9A9A));
        }
        rows.add(autismclient.gui.vanillaui.direct.DirectHudPanelRenderer.Row.body(
            trimHud(mc.font, "speed-pred: " + s.wPredicts() + " (" + s.totalPredicts() + ")", contentWidth), 0xFFFFFF8D));
        if (s.lastSetbackDist() >= 0.0D) {
            rows.add(autismclient.gui.vanillaui.direct.DirectHudPanelRenderer.Row.body(
                trimHud(mc.font, "last setback: " + String.format(java.util.Locale.ROOT, "%.2f", s.lastSetbackDist()) + "b", contentWidth),
                0xFFCE93D8));
        }
        int heat = s.heat();
        StringBuilder bar = new StringBuilder("heat [");
        for (int i = 0; i < 4; i++) bar.append(i < heat ? '#' : '-');
        bar.append(']');
        if (s.speedImminent()) bar.append(" !");
        rows.add(autismclient.gui.vanillaui.direct.DirectHudPanelRenderer.Row.body(
            trimHud(mc.font, bar.toString(), contentWidth), heatColor(heat, s.speedImminent())));
        if (fd.hudSession.get()) {
            rows.add(autismclient.gui.vanillaui.direct.DirectHudPanelRenderer.Row.body(
                trimHud(mc.font, "session: " + formatElapsed(s.sessionElapsedMs()), contentWidth), 0xFFB0BEC5));
        }

        String title = trimHud(mc.font, "BYPASS TELEMETRY", contentWidth);
        autismclient.gui.vanillaui.direct.DirectHudPanelRenderer.render(
            context, mc.font, fd.hudX.get(), fd.hudY.get(), width, title, rows, fd.hudAccent.get());
    }

    private static int heatColor(int heat, boolean imminent) {
        if (imminent) return 0xFFE57373;
        return switch (heat) {
            case 0 -> 0xFFA5D6A7;
            case 1 -> 0xFFFFF59D;
            case 2 -> 0xFFFFB74D;
            default -> 0xFFE57373;
        };
    }

    private static String trimHud(net.minecraft.client.gui.Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        String ellipsis = "..";
        while (!text.isEmpty() && font.width(text + ellipsis) > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }
        return text + ellipsis;
    }

    private static String formatElapsed(long ms) {
        long totalSec = Math.max(0L, ms / 1000L);
        long h = totalSec / 3600L;
        long m = (totalSec % 3600L) / 60L;
        long sec = totalSec % 60L;
        return h > 0L ? String.format(java.util.Locale.ROOT, "%dh %02dm", h, m)
            : String.format(java.util.Locale.ROOT, "%dm %02ds", m, sec);
    }
}
