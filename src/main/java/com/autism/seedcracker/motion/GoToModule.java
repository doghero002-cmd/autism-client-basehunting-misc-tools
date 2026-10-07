package com.autism.seedcracker.motion;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;

/** Drives {@link Motion} every tick for the .goto command and shows the planner status. */
public final class GoToModule extends Module {

    private static GoToModule instance;

    /** Travel modes exposed in the settings GUI (the .goto sub-commands, as a category). */
    public enum TravelMode { GOTO, MINE, FOLLOW, EXPLORE, ELYTRA, Y_LEVEL, AWAY }

    private final autismclient.api.module.EnumSetting<TravelMode> travelMode = add(
        new autismclient.api.module.EnumSetting<>("travel-mode", "Travel mode", TravelMode.GOTO, TravelMode.values())
            .description("What to do when you press Start travel: walk to a point (GOTO), mine ore (MINE), follow a player (FOLLOW), wander (EXPLORE), fly with elytra (ELYTRA), reach a height (Y_LEVEL), or run away (AWAY).")
            .group("Travel"));
    private final IntSetting travelX = add(new IntSetting("travel-x", "Target X", 0, -30000000, 30000000, 1)
        .description("X to travel to (GOTO / MINE / ELYTRA).").group("Travel")
        .visibleWhen(() -> travelMode.get() == TravelMode.GOTO || travelMode.get() == TravelMode.MINE || travelMode.get() == TravelMode.ELYTRA));
    private final IntSetting travelY = add(new IntSetting("travel-y", "Target Y", 0, -64, 320, 1)
        .description("Y level to reach (Y_LEVEL, or the exact Y for GOTO/MINE; leave at your feet for any-Y).").group("Travel")
        .visibleWhen(() -> travelMode.get() == TravelMode.GOTO || travelMode.get() == TravelMode.MINE || travelMode.get() == TravelMode.Y_LEVEL));
    private final IntSetting travelZ = add(new IntSetting("travel-z", "Target Z", 0, -30000000, 30000000, 1)
        .description("Z to travel to (GOTO / MINE / ELYTRA).").group("Travel")
        .visibleWhen(() -> travelMode.get() == TravelMode.GOTO || travelMode.get() == TravelMode.MINE || travelMode.get() == TravelMode.ELYTRA));
    private final autismclient.api.module.StringSetting travelBlocks = add(new autismclient.api.module.StringSetting("travel-blocks", "Ore / blocks", "diamond_ore")
        .description("Comma-separated block ids for MINE (e.g. diamond_ore,ancient_debris). 'diamond' expands to both ore variants.").group("Travel")
        .visibleWhen(() -> travelMode.get() == TravelMode.MINE));
    private final autismclient.api.module.StringSetting travelPlayer = add(new autismclient.api.module.StringSetting("travel-player", "Player to follow", "")
        .description("Exact name of the player to follow (FOLLOW).").group("Travel")
        .visibleWhen(() -> travelMode.get() == TravelMode.FOLLOW));
    private final IntSetting travelRadius = add(new IntSetting("travel-radius", "Radius / distance", 128, 4, 20000, 1)
        .description("Blocks to wander (EXPLORE) or run away (AWAY).").group("Travel")
        .visibleWhen(() -> travelMode.get() == TravelMode.EXPLORE || travelMode.get() == TravelMode.AWAY));
    private final autismclient.api.module.ActionSetting travelStart = add(new autismclient.api.module.ActionSetting("travel-start", "Start travel", this::startTravel)
        .buttonLabel("Start")
        .description("Begin the selected travel mode to the configured target.")
        .group("Travel"));
    private final autismclient.api.module.ActionSetting travelStop = add(new autismclient.api.module.ActionSetting("travel-stop", "Stop travel", () -> {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> { MineTask.stop(mc); Motion.stop(mc); com.autism.seedcracker.modules.ElytraTravelModule.stopFlight(); });
    }).buttonLabel("Stop").description("Stop the current trip / flight.").group("Travel"));

    private final BoolSetting renderPath = add(new BoolSetting("render-path", "Show path", true)
        .description("Outline the planned route's floor blocks."));
    private final BoolSetting sprint = add(new BoolSetting("sprint", "Sprint", true)
        .description("Sprint on straight, dry stretches."));
    private final BoolSetting parkour = add(new BoolSetting("parkour", "Jump gaps", false)
        .description("Sprint-jump 1-2 block gaps. Only commits when lined up; still a fall risk on lag."));
    private final BoolSetting bridge = add(new BoolSetting("bridge", "Bridge gaps", false)
        .description("Place cobble/dirt/netherrack/planks from your hotbar to cross holes (sneaks at the edge)."));
    private final BoolSetting avoidMobs = add(new BoolSetting("avoid-mobs", "Avoid hostile mobs", true)
        .description("Route around hostile mobs (creepers get extra room). Checked each time a route is planned."));
    private final BoolSetting sprintJump = add(new BoolSetting("sprint-jump", "Sprint-jump straights", false)
        .description("Bunny-hop on long straight flat stretches (faster, costs more hunger, more noticeable)."));
    private final BoolSetting longJumps = add(new BoolSetting("long-jumps", "3-block jumps", false)
        .description("With Jump gaps on: also jump 3-wide gaps after a 2-block straight run-up. Lag makes these fail.")
        .visibleWhen(parkour::get));
    private final BoolSetting bucket = add(new BoolSetting("water-bucket", "Water-bucket falls", false)
        .description("Allow drops up to 20 blocks by placing water just before landing (needs a water bucket in the hotbar)."));
    private final BoolSetting cushionBlocks = add(new BoolSetting("cushion-blocks", "Cushion-block falls", false)
        .description("Allow drops up to 30 blocks by placing a hay bale / slime / cobweb / powder snow at the landing (needs one in the hotbar)."));
    private final BoolSetting autoInventory = add(new BoolSetting("auto-inventory", "Restock hotbar", true)
        .description("While standing still to plan, move bridge blocks / a water bucket from your inventory into a free hotbar slot."));
    private final BoolSetting collectDrops = add(new BoolSetting("collect-drops", "Collect mined drops", true)
        .description(".goto ore: walk over each ore's drops before heading to the next one."));
    private final IntSetting maxDrop = add(new IntSetting("max-drop", "Max drop (blocks)", 3, 0, 20, 1)
        .description("How far it may drop straight down. Vanilla hurts from 4, so 3 is safe with no feather falling."));
    private final IntSetting hazardPenalty = add(new IntSetting("hazard-penalty", "Hazard avoidance", 6, 0, 50, 1)
        .description("How hard it avoids pathing next to lava/magma/fire. Higher = keeps more distance. 0 = no extra cost."));
    private final BoolSetting breakBlocks = add(new BoolSetting("break-blocks", "Break blocks", false)
        .description("Let every .goto trip break blocks in the way (like always using '.goto mine'). Off = walk around."));
    private final IntSetting planMs = add(new IntSetting("plan-ms", "Reaction time (ms)", 250, 50, 2000, 10)
        .description("How long the planner may run before the first partial route starts the walk. Lower = reacts faster (may take a slightly less optimal first hop on very long trips)."));
    private final BoolSetting smoothRotation = add(new BoolSetting("smooth-rotation", "Smooth rotation", true)
        .description("Ease the camera toward the path like a real mouse instead of snapping. Off = instant (fastest, most bot-like).").group("Rotation"));
    private final IntSetting rotationSpeed = add(new IntSetting("rotation-speed", "Turn speed", 22, 4, 60, 1)
        .description("Peak turn speed (degrees/tick). Lower = slower/smoother, more human; higher = faster reactions.").group("Rotation").visibleWhen(smoothRotation::get));
    private final IntSetting rotationAccel = add(new IntSetting("rotation-accel", "Turn acceleration", 5, 1, 20, 1)
        .description("How quickly the turn ramps up (deg/tick^2). Lower = softer, lazier curves.").group("Rotation").visibleWhen(smoothRotation::get));
    private final IntSetting rotationJitter = add(new IntSetting("rotation-jitter", "Turn jitter %", 12, 0, 40, 1)
        .description("Random wobble in the turn so it isn't a ruler-straight bot line. 0 = perfectly straight.").group("Rotation").visibleWhen(smoothRotation::get));
    private final BoolSetting rotationHumanize = add(new BoolSetting("rotation-humanize", "Human micro-pauses", true)
        .description("Occasionally hesitate mid-turn like a real hand (helps vs anti-cheat).").group("Rotation").visibleWhen(smoothRotation::get));
    private final BoolSetting silentRotation = add(new BoolSetting("silent-rotation", "Silent rotation (bypass)", false)
        .description("EXPERIMENTAL (needs in-game tuning): send the facing to the server but DON'T move your camera (strongest anti-cheat bypass). Currently the bot steers by the silent facing; your own view stays free.").group("Rotation"));
    private final BoolSetting debug = add(new BoolSetting("debug", "Debug mode", false)
        .description("On-screen panel with what the bot is doing, each plan's stats and why it failed.").group("Debug"));
    private final BoolSetting debugMarkers = add(new BoolSetting("debug-markers", "Colour-coded path", true)
        .description("Green walk, yellow jump, red break, blue place, purple fall, cyan climb/swim/door, bright red = spots it's avoiding.")
        .group("Debug").visibleWhen(debug::get));
    private final BoolSetting debugLog = add(new BoolSetting("debug-log", "Write debug logs", true)
        .description("motion-errors.log (just problems, each with the 3s before it) and motion-trace.log (every tick: angles, keys, jumps). Send these when reporting a problem.")
        .group("Debug").visibleWhen(debug::get));

    private static final net.minecraft.resources.Identifier HUD_ID =
        net.minecraft.resources.Identifier.fromNamespaceAndPath(SeedcrackerAddon.ID, "motion_debug");
    private static boolean hudRegistered;

    public GoToModule() {
        super(SeedcrackerAddon.ID + ":goto", "GoTo",
            "Built-in pathfinder (no Baritone). Use .goto x z, .goto x y z, .goto mine x y z, .goto stop.");
        instance = this;
    }

    /** Flips debug mode; returns the new state (false if the module isn't loaded). */
    public static boolean toggleDebug() {
        if (instance == null) return false;
        instance.debug.set(!instance.debug.get());
        ensureEnabled();
        MotionDebug.configure(instance.debug.get(), instance.debugMarkers.get(), instance.debugLog.get());
        return instance.debug.get();
    }

    /** Turns the module on if needed (the command needs someone ticking the engine). */
    public static boolean ensureEnabled() {
        if (instance == null) return false;
        if (!instance.isEnabled()) instance.setEnabled(true);
        return true;
    }

    /** Parse a comma block list into Blocks, expanding bare names like the .goto ore command. */
    private static java.util.Set<net.minecraft.world.level.block.Block> parseBlocks(String list) {
        java.util.Set<net.minecraft.world.level.block.Block> blocks = new java.util.HashSet<>();
        for (String raw : list.split(",")) {
            String name = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (name.isEmpty()) continue;
            for (String id : name.contains("ore") || name.contains(":") ? java.util.List.of(name)
                : java.util.List.of(name, name + "_ore", "deepslate_" + name + "_ore", "nether_" + name + "_ore")) {
                var key = net.minecraft.resources.Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
                if (key == null) continue;
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(key).ifPresent(blocks::add);
            }
        }
        blocks.remove(net.minecraft.world.level.block.Blocks.AIR);
        return blocks;
    }

    /** Dispatch from the Travel settings group: begin the configured travel mode on the game thread. */
    private void startTravel() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        ensureEnabled();
        TravelMode mode = travelMode.get();
        mc.execute(() -> {
            switch (mode) {
                case GOTO -> {
                    boolean exactY = travelY.get() != mc.player.getBlockY();
                    if (exactY) Motion.goTo(mc, new net.minecraft.core.BlockPos(travelX.get(), travelY.get(), travelZ.get()), Motion.Backend.BUILT_IN, false);
                    else Motion.goToXZ(mc, travelX.get(), travelZ.get(), Motion.Backend.BUILT_IN, false);
                }
                case MINE -> {
                    var blocks = parseBlocks(travelBlocks.get());
                    if (!blocks.isEmpty()) MineTask.start(mc, blocks, 64);
                }
                case FOLLOW -> Motion.follow(mc, travelPlayer.get());
                case EXPLORE -> Motion.explore(mc, travelRadius.get());
                case ELYTRA -> com.autism.seedcracker.modules.ElytraTravelModule.start(travelX.get(), travelZ.get());
                case Y_LEVEL -> Motion.goToY(mc, travelY.get(), true);
                case AWAY -> Motion.runAway(mc, travelRadius.get());
            }
        });
    }

    @Override
    public void onEnable() {
        if (!hudRegistered) {
            hudRegistered = true;
            net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.attachElementAfter(
                net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.MISC_OVERLAYS, HUD_ID,
                (net.minecraft.client.gui.GuiGraphicsExtractor ctx, net.minecraft.client.DeltaTracker delta) -> MotionDebug.render(ctx));
        }
    }

    @Override
    public void onDisable() {
        MineTask.stop(Minecraft.getInstance());
        Motion.stop(Minecraft.getInstance());
        MotionDebug.configure(false, false, false);
    }

    @Override
    public void onGameLeft() {
        MineTask.stop(Minecraft.getInstance());
        Motion.stop(Minecraft.getInstance());
        com.autism.seedcracker.motion.ChunkCache.detach();
    }

    private int cacheTicks;
    private boolean cacheAttached;
    private long lastChunkPos = Long.MIN_VALUE;

    private void tickChunkCache(Minecraft mc) {
        if (mc.level == null || mc.player == null) { cacheAttached = false; return; }
        if (!cacheAttached) {
            com.autism.seedcracker.motion.ChunkCache.attach(mc);
            cacheAttached = true;
            lastChunkPos = Long.MIN_VALUE;
        }
        // Record immediately when the player crosses into a new chunk (keeps the cache ahead while travelling),
        // so long trips build coverage en route and chain beyond render distance instead of hitting a cache edge.
        int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
        long cpos = ((long) pcx << 32) | (pcz & 0xFFFFFFFFL);
        if (cpos != lastChunkPos) {
            lastChunkPos = cpos;
            recordAround(mc, pcx, pcz);
            return; // next tick resumes the periodic full record
        }
        if (++cacheTicks < 40) return; // periodic full record + flush every ~2s
        cacheTicks = 0;
        recordAround(mc, pcx, pcz);
        com.autism.seedcracker.motion.ChunkCache.flush();
    }

    private void recordAround(Minecraft mc, int pcx, int pcz) {
        int r = mc.options.getEffectiveRenderDistance();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            int cx = pcx + dx, cz = pcz + dz;
            if (mc.level.hasChunk(cx, cz)) com.autism.seedcracker.motion.ChunkCache.recordLoaded(mc, mc.level.getChunk(cx, cz));
        }
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        Motion.setRenderPath(renderPath.get());
        Motion.setSprint(sprint.get());
        Motion.setAdvancedMoves(parkour.get(), bridge.get());
        Motion.setAvoidMobs(avoidMobs.get());
        Motion.setSprintJump(sprintJump.get());
        Motion.setRiskyMoves(longJumps.get(), bucket.get());
        Motion.setAutoInventory(autoInventory.get());
        Motion.setMaxDrop(maxDrop.get());
        Motion.setHazardPenalty(hazardPenalty.get());
        Motion.setAllowBreak(breakBlocks.get());
        Motion.setCushionBlocks(cushionBlocks.get());
        Motion.setPlanMs(planMs.get());
        Motion.setRotation(smoothRotation.get(), rotationSpeed.get(), rotationAccel.get(),
            rotationJitter.get() / 100f, rotationHumanize.get());
        Motion.setSilentRotation(silentRotation.get());
        MineTask.setCollectDrops(collectDrops.get());
        MotionDebug.configure(debug.get(), debugMarkers.get(), debugLog.get());
        tickChunkCache(mc);
        Motion.tick(mc);
        MineTask.tick(mc);
    }

    @Override
    public String info() {
        return MineTask.active() ? MineTask.status() : Motion.status();
    }
}
