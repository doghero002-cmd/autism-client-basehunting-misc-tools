package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.util.tunnel.LegitMovement;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Tunnel Base (Water) — BETA.
 *
 * A faithful port of the Water Client "TunnelBaseFinder" autonomous DonutSMP base-finder, kept as a
 * separate beta option alongside the main Tunnel Base Finder. It digs a tunnel until it finds a
 * base, with full sub-state-machines: auto-mend (offhand XP), auto-eat, /shop restock of XP /
 * pearls / obsidian / golden carrots, hazard (lava/water/gravel) avoidance with left/right/above
 * fallback, Y-recovery towering, totem-pop + low-durability + hunger watchdogs, and a disconnect
 * when a base (or spawner) is found.
 *
 * Rotation uses the shared {@link LegitMovement} engine, which is the same distance-eased
 * "RotateCharacter" math Water uses (ease by distance/20, 5% hesitation on big flicks).
 *
 * Mining styles: CRAWL (1x1 swim-crawl), STANDING (2x1 walk), AMETHYST (wider hazard scan).
 * WARNING: fully automated digging/shop movement - may flag anti-cheats.
 */
public final class TunnelBaseWaterModule extends Module {

    private static final int MIN_Y_LEVEL = -59;
    private static final long PHASE_TIME_MS = 5000;
    private static final Item[] JUNK_ITEMS = {
        Items.STONE, Items.COBBLESTONE, Items.DEEPSLATE, Items.COBBLED_DEEPSLATE, Items.ANDESITE,
        Items.DIORITE, Items.GRANITE, Items.TUFF, Items.CALCITE, Items.DRIPSTONE_BLOCK,
        Items.POINTED_DRIPSTONE, Items.GRAVEL, Items.FLINT, Items.DIRT, Items.GRASS_BLOCK,
        Items.COARSE_DIRT, Items.ROOTED_DIRT, Items.NETHERRACK, Items.BLACKSTONE, Items.BASALT,
        Items.SMOOTH_BASALT, Items.END_STONE, Items.COAL, Items.RAW_IRON, Items.RAW_COPPER,
        Items.COBWEB, Items.STRING, Items.CLAY, Items.TOTEM_OF_UNDYING
    };

    public enum MiningMode { CRAWL, STANDING, AMETHYST }

    private final EnumSetting<MiningMode> mode = add(new EnumSetting<>(
            "mining-style", "Mining style", MiningMode.AMETHYST, MiningMode.values())
        .description("CRAWL = 1x1 swim-crawl. STANDING = 2x1 walk. AMETHYST = wider hazard scan.")
        .group("General"));
    private final BoolSetting spawnerCritical = add(new BoolSetting("spawner-critical", "Spawner critical", false)
        .description("Disconnect the moment any spawner is detected (else only on chest/shulker/piston counts).")
        .group("General"));
    private final IntSetting obiSlot = add(new IntSetting("obsidian-slot", "Obsidian slot", 2, 1, 9, 1).group("Slots"));
    private final IntSetting pearlSlot = add(new IntSetting("pearl-slot", "Pearl slot", 3, 1, 9, 1).group("Slots"));
    private final IntSetting xpSlot = add(new IntSetting("bottle-slot", "Bottle slot", 4, 1, 9, 1).group("Slots"));
    private final IntSetting carrotSlot = add(new IntSetting("carrot-slot", "GoldenCarrot slot", 5, 1, 9, 1).group("Slots"));
    private final BoolSetting humanize = add(new BoolSetting("humanize", "Humanize", true)
        .description("Randomise shop/mend action delays.").group("General"));
    private final IntSetting delayRandomness = add(new IntSetting("delay-randomness", "Delay randomness", 3, 0, 10, 1)
        .group("General").visibleWhen(() -> humanize.get()));
    private final BoolSetting kickOnNoTotem = add(new BoolSetting("kick-on-no-totem", "Kick on no totem", true)
        .description("Disconnect when you have no totem (startup + when it pops). OFF = just warn and keep tunneling (risky).")
        .group("Totem"));
    private final BoolSetting autoBuyTotem = add(new BoolSetting("auto-buy-totem", "Auto buy totem", false)
        .description("When you have no totem, open /shop and buy one automatically instead of stopping.")
        .group("Totem"));
    private final autismclient.api.module.StringSetting totemPrice = add(new autismclient.api.module.StringSetting("totem-price", "Totem price", "50000")
        .description("The /shop totem price (for reference - the buy flow clicks the totem slot; the price is informational / for your tracking).")
        .group("Totem").visibleWhen(() -> autoBuyTotem.get()));
    private final IntSetting turnSpeed = add(new IntSetting("turn-speed", "Turn speed", 12, 2, 40, 1)
        .description("Degrees per tick the view turns toward a new direction (higher = snappier, lower = slower/smoother).")
        .group("Movement"));
    private final BoolSetting turnWhileWalking = add(new BoolSetting("turn-while-walking", "Turn while walking", true)
        .description("Keep walking while turning to a new direction (looks less bot-like than stop-turn-go).")
        .group("Movement"));
    private final BoolSetting kickOnFind = add(new BoolSetting("kick-on-find", "Kick on base find", true)
        .description("Disconnect when a base is found. OFF = play a sound + keep going (you loot it yourself).")
        .group("Find"));
    private final autismclient.api.module.StringSetting findSound = add(new autismclient.api.module.StringSetting("find-sound", "Found sound", "entity.player.levelup")
        .description("Sound event to play when a base is found (only when kick-on-find is off).")
        .group("Find").visibleWhen(() -> !kickOnFind.get()));

    // ---- Base-detection thresholds (separate settings group) ----
    private final IntSetting chestThreshold = add(new IntSetting("chest-threshold", "Chest/barrel count", 35, 1, 200, 1)
        .description("Chests/barrels below Y0 in loaded chunks needed to count as a BASE.").group("Base Detection"));
    private final IntSetting shulkerThreshold = add(new IntSetting("shulker-threshold", "Shulker count", 35, 1, 200, 1)
        .description("Shulker boxes below Y0 needed to count as a BASE.").group("Base Detection"));
    private final IntSetting pistonThreshold = add(new IntSetting("piston-threshold", "Moving piston count", 10, 1, 100, 1)
        .description("Moving pistons below Y0 needed to count as a BASE.").group("Base Detection"));

    // ---- Buying (master toggle for all /shop restock) ----
    private final BoolSetting buying = add(new BoolSetting("buying", "Enable buying", true)
        .description("Allow the module to /shop-restock XP / pearls / obsidian / carrots / totems. OFF = never opens the shop (you supply everything).")
        .group("Buying"));

    // ---- state ----
    private enum State { NONE, MINING, GOABOVEHAZARD, YRECOVERY, BUYOBI, PEARL, BUYPEARL, AUTOMEND, BUYXP, AUTOEAT, BUYCARROT, BUYTOTEM }
    private enum MendStage { ENSURE, ROTATE_DOWN, OFFHAND_XP, THROW_XP, REOFFHAND_TOTEM, ROTATE_BACK, RESET }
    private enum Phase { DIG, TOWER }
    private enum BuyStage { NONE, OPENSHOP, WAIT1, CLICK1, WAIT2, CLICK2, WAIT3, CLICKSTACK, WAIT4, DROPITEMS, WAIT5, BUY, WAIT6, CLOSE, WAIT7, RESET }

    private State state = State.NONE;
    private State backup = State.NONE;
    private Direction currentDirection = null;
    private final LegitMovement look = new LegitMovement();
    private boolean isRotating = false;
    private Runnable rotCallback = null;

    private MendStage mendStage = MendStage.ENSURE;
    private Phase phase = Phase.DIG;
    private long phaseStartTime = 0;
    private boolean towerRotationDone = false;
    private BlockPos towerBasePos = null;
    private boolean yRecoveryRotationDone = false;
    private BlockPos yRecoveryBasePos = null;

    private BuyStage buyStage = BuyStage.NONE;
    private int buyWait = 0, waitTarget = 0;
    private int buyItemSlot = -1; // shop GUI slot to click for the item being bought
    private State buyReturnState = State.NONE;

    private int stuckTicks = 0;
    private BlockPos lastCoords = null;
    private boolean isBackup = false;
    private Direction backupDirection = null;
    private int mendingGraceTicks = 0;
    private boolean jumped = false;
    private boolean pearlReset = true;
    private boolean shouldCloseInventory = false;
    private int resetMiningTick = 0, resetUseTick = 0;
    private boolean wasScreenOpen = false;
    private final com.autism.seedcracker.util.StuckDetector stuck = new com.autism.seedcracker.util.StuckDetector("TunnelBaseWaterModule",
        com.autism.seedcracker.util.Tuning.STUCK_TICKS_TUNNEL, com.autism.seedcracker.util.Tuning.STUCK_EPSILON);

    public TunnelBaseWaterModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":tunnel-base-water", "Tunnel Base (Water) [BETA]", category,
            "Water Client autonomous tunnel base-finder (beta). Digs until it finds a base, with shop restock + auto-mend/eat.");
    }

    @Override
    public void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) { setEnabledSilently(false); return; }
        state = State.NONE; backup = State.NONE; isRotating = false; rotCallback = null;
        phase = Phase.DIG; phaseStartTime = 0; towerRotationDone = false; towerBasePos = null;
        yRecoveryRotationDone = false; yRecoveryBasePos = null; mendStage = MendStage.ENSURE;
        buyStage = BuyStage.NONE; buyWait = 0; stuckTicks = 0; lastCoords = null;
        isBackup = false; mendingGraceTicks = 0; pearlReset = true; shouldCloseInventory = false;
        resetMiningTick = 0; resetUseTick = 0; wasScreenOpen = false; jumped = false;
        look.reset();
        stuck.reset();

        if (!isHoldingTool(mc)) {
            int pick = findPickaxe(mc);
            if (pick != -1) com.autism.seedcracker.util.InvSync.select(mc, pick);
            else { disconnect(mc, "YOU DON'T HAVE PICKAXE"); return; }
        }
        if (!hasTotemOffhand(mc)) {
            int t = findInInventory(mc, Items.TOTEM_OF_UNDYING);
            if (t != -1) offhandFromInventory(mc, t, Items.TOTEM_OF_UNDYING);
            else if (autoBuyTotem.get()) { state = State.BUYTOTEM; buyStage = BuyStage.NONE; }
            else if (kickOnNoTotem.get()) { disconnect(mc, "YOU DON'T HAVE TOTEM"); return; }
            else AutismClientMessaging.sendPrefixed("§e[TunnelBase-Water] §fNo totem - continuing anyway (kick-on-no-totem is off).");
        }
        currentDirection = horizontalDir(mc);
        float[] v = dirValues(currentDirection);
        rotateTo(v[0], v[1], () -> state = State.MINING);
        AutismClientMessaging.sendPrefixed("§c[TunnelBase-Water] §fStarting (Water logic). Fully automated - watch for flags.");
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null) {
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
            updateMining(mc, false);
            updateUsage(mc, false);
        }
        state = State.NONE; backup = State.NONE; isRotating = false; rotCallback = null;
        stuck.reset();
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false); }

    // ---- rotation (Water RotateCharacter via LegitMovement) ----
    private void rotateTo(float yaw, float pitch, Runnable cb) {
        isRotating = true;
        rotCallback = cb;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) { currentSmoothedYaw = mc.player.getYRot(); currentSmoothedPitch = mc.player.getXRot(); }
        pendingYaw = yaw; pendingPitch = pitch;
    }
    private float pendingYaw, pendingPitch;

    private void tickRotation(Minecraft mc) {
        if (!isRotating) return;
        // Bounded turn-speed step (not the slow LegitMovement default): snappy enough to keep pace
        // while walking, without an instant head-flick. turnSpeed is degrees per tick.
        float step = turnSpeed.get();
        currentSmoothedYaw = LookRotationCompat.approachAngle(currentSmoothedYaw, pendingYaw, step);
        currentSmoothedPitch = LookRotationCompat.approach(currentSmoothedPitch, pendingPitch, step);
        mc.player.setYRot(currentSmoothedYaw);
        mc.player.setXRot(net.minecraft.util.Mth.clamp(currentSmoothedPitch, -90f, 90f));
        float yawDiff = Math.abs(net.minecraft.util.Mth.wrapDegrees(pendingYaw - mc.player.getYRot()));
        float pitchDiff = Math.abs(pendingPitch - mc.player.getXRot());
        if (yawDiff < 0.5f && pitchDiff < 0.5f) {
            // Land on the GCD-aligned target (mouse-sensitivity grid), not a raw arbitrary-precision
            // snap - the final tiny step must also look like a real mouse movement.
            mc.player.setYRot(com.autism.seedcracker.util.tunnel.SilentRotation.Gcd.quantize(pendingYaw));
            mc.player.setXRot(net.minecraft.util.Mth.clamp(com.autism.seedcracker.util.tunnel.SilentRotation.Gcd.quantize(pendingPitch), -90f, 90f));
            isRotating = false;
            if (rotCallback != null) { Runnable r = rotCallback; rotCallback = null; r.run(); }
        }
    }

    private float currentSmoothedYaw, currentSmoothedPitch;

    /** Minimal angle/pitch approach helpers (avoid re-entering LegitMovement state). */
    private static final class LookRotationCompat {
        static float approach(float c, float t, float s) { float d = t - c; return c + (d > s ? s : Math.max(d, -s)); }
        static float approachAngle(float c, float t, float s) {
            float d = t - c;
            while (d > 180f) d -= 360f;
            while (d < -180f) d += 360f;
            return c + (d > s ? s : Math.max(d, -s));
        }
    }

    // ---- main tick ----
    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options == null) return;

        if (jumped) { jumped = false; mc.options.keyJump.setDown(false); }
        tickRotation(mc);

        if (shouldCloseInventory) {
            if (mc.gui.screen() instanceof InventoryScreen) mc.gui.setScreen(null);
            shouldCloseInventory = false;
        }

        scanForBase(mc);
        if (!isEnabled()) return; // scanForBase may have disconnected

        // Grim SpeedA predictor: pause movement while a speed flag is imminent (buffer drains).
        if (FlagDetectorModule.speedFlagImminent()) {
            stopMovement(mc);
            return;
        }

        if (mendingGraceTicks > 0) mendingGraceTicks--;

        boolean busy = state == State.AUTOMEND || state == State.AUTOEAT
            || state == State.BUYXP || state == State.BUYPEARL
            || state == State.BUYCARROT || state == State.BUYOBI;
        if (!busy) {
            if (mendingGraceTicks <= 0 && !hasTotemOffhand(mc)) {
                int t = findInInventory(mc, Items.TOTEM_OF_UNDYING);
                if (t != -1) offhandFromInventory(mc, t, Items.TOTEM_OF_UNDYING);
                else if (autoBuyTotem.get()) { backup = state; state = State.BUYTOTEM; buyStage = BuyStage.NONE; return; }
                else if (kickOnNoTotem.get()) { disconnect(mc, "YOUR TOTEM POPPED"); return; }
            }
            if (isPickaxeLow(mc)) { backup = state; state = State.AUTOMEND; }
            else if (mc.player.getFoodData().getFoodLevel() <= 6) { backup = state; state = State.AUTOEAT; }
        }

        switch (state) {
            case MINING -> handleMining(mc);
            case GOABOVEHAZARD -> handleGoAbove(mc);
            case YRECOVERY -> handleYRecovery(mc);
            case PEARL -> handlePearl(mc);
            case AUTOMEND -> handleMend(mc);
            case AUTOEAT -> handleEating(mc);
            case BUYXP, BUYPEARL, BUYOBI, BUYCARROT, BUYTOTEM -> {
                if (buying.get()) handleBuy(mc);
                else state = State.MINING; // buying disabled: skip the shop and keep tunneling
            }
            default -> {}
        }
        wasScreenOpen = mc.gui.screen() != null;
        // Stuck detector: log the exact state if we stop making progress.
        stuck.setAction("state=" + state + " dir=" + currentDirection
            + (isRotating ? " rotating" : "") + (isBackup ? " backup->" + backupDirection : "")
            + " phase=" + phase + " buy=" + buyStage);
        stuck.tick(mc);
    }

    // ---- base detection (Water thresholds) ----
    private void scanForBase(Minecraft mc) {
        int chests = 0, shulkers = 0, movingPiston = 0;
        boolean foundSpawner = false;
        // Water scans every loaded chunk's block entities within the client's view distance.
        int vd = mc.options.getEffectiveRenderDistance();
        net.minecraft.world.level.ChunkPos center = mc.player.chunkPosition();
        for (int dx = -vd; dx <= vd; dx++) {
            for (int dz = -vd; dz <= vd; dz++) {
                int cx = center.x() + dx, cz = center.z() + dz;
                if (!mc.level.hasChunk(cx, cz)) continue;
                net.minecraft.world.level.chunk.LevelChunk chunk = mc.level.getChunk(cx, cz);
                for (BlockPos pos : chunk.getBlockEntities().keySet()) {
                    BlockEntity be = chunk.getBlockEntities().get(pos);
                    if (be == null) continue;
                    if (be instanceof SpawnerBlockEntity) foundSpawner = true;
                    if (pos.getY() > 0) continue;
                    Block b = be.getBlockState().getBlock();
                    if (b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST || b == Blocks.BARREL) chests++;
                    else if (isShulker(b)) shulkers++;
                    else if (b == Blocks.MOVING_PISTON) movingPiston++;
                }
            }
        }
        boolean found = false; String reason = "";
        if (chests >= chestThreshold.get()) { found = true; reason = "BASE"; }
        else if (shulkers >= shulkerThreshold.get()) { found = true; reason = "BASE"; }
        else if (movingPiston >= pistonThreshold.get()) { found = true; reason = "BASE"; }
        else if (foundSpawner && spawnerCritical.get()) { found = true; reason = "SPAWNER"; }
        if (found) onBaseFound(mc, reason);
    }

    /** Kick or play a sound on base find, per the kick-on-find setting. */
    private void onBaseFound(Minecraft mc, String reason) {
        FlagDetectorModule.report("BASE_FOUND", "TunnelBaseWater", reason);
        if (kickOnFind.get()) {
            disconnect(mc, "YOU FOUND A " + reason);
        } else {
            playFoundSound(mc);
            AutismClientMessaging.sendPrefixed("§a[TunnelBase-Water] §fYOU FOUND A " + reason + "!");
        }
    }

    private void playFoundSound(Minecraft mc) {
        if (mc.player == null) return;
        try {
            net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.parse(findSound.get().trim());
            net.minecraft.sounds.SoundEvent evt = net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.getValue(id);
            if (evt != null) mc.player.playSound(evt, 1.0f, 1.0f);
        } catch (Throwable t) {
            mc.player.playSound(net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, 1.0f, 1.0f);
        }
    }

    // ---- MINING ----
    private void handleMining(Minecraft mc) {
        // Turn while walking: don't freeze movement just because a rotation is in flight (that
        // stop-turn-go pattern is a bot tell). Only skip if turnWhileWalking is off.
        if (isRotating && !turnWhileWalking.get()) { stopMovement(mc); return; }
        if (mc.player.blockPosition().getY() < MIN_Y_LEVEL) {
            state = State.YRECOVERY; yRecoveryBasePos = null; yRecoveryRotationDone = false;
            phase = Phase.DIG; phaseStartTime = System.currentTimeMillis();
            return;
        }
        com.autism.seedcracker.util.InvSync.select(mc, findPickaxe(mc));
        if (mode.get() == MiningMode.CRAWL && mc.player.getPose() != Pose.SWIMMING) state = State.PEARL;

        if (mc.hitResult == null || mc.hitResult.getType() == HitResult.Type.MISS) {
            BlockPos cur = mc.player.blockPosition();
            if (cur.equals(lastCoords)) {
                if (stuckTicks < 20) stuckTicks++;
                else {
                    stuckTicks = 0;
                    avoidHazard(mc, false);
                }
            } else { stuckTicks = 0; lastCoords = cur; }
        } else { stuckTicks = 0; lastCoords = mc.player.blockPosition(); }

        tryJumpStep(mc);

        if (isBackup) {
            if (!checkHazardDirection(mc, backupDirection, 0)) {
                currentDirection = backupDirection;
                isBackup = false;
                float[] v = dirValues(currentDirection);
                rotateTo(v[0], v[1], null);
            }
        }

        mc.options.keyUp.setDown(true);
        if (mode.get() == MiningMode.STANDING) {
            if (mc.hitResult instanceof BlockHitResult hit) {
                updateMining(mc, hit.getBlockPos().getY() >= mc.player.blockPosition().getY());
            }
        } else {
            updateMining(mc, true);
        }
        if (checkHazardDirection(mc, currentDirection, 0)) avoidHazard(mc, true);
    }

    private int hazardCommitTicks = 0; // lock a hazard-turn decision so we don't flip-flop

    private void avoidHazard(Minecraft mc, boolean markBackup) {
        // If we recently committed to a turn, hold it for a few ticks instead of re-deciding every
        // tick (the left<->right oscillation that looks like a stuck loop).
        if (hazardCommitTicks > 0) { hazardCommitTicks--; return; }
        // Try to go AROUND the hazard first: left, then right (wider scan), then the two back
        // diagonals. Only tower up/down as an absolute last resort when fully boxed in - never dig
        // straight down into bedrock-level lava.
        Direction[] sides = { dirLeft(currentDirection), dirRight(currentDirection),
            currentDirection.getOpposite(), dirLeft(currentDirection), dirRight(currentDirection) };
        int[] extras = { 5, 5, 0, 12, 12 }; // progressively wider search before giving up
        for (int i = 0; i < sides.length; i++) {
            if (!checkHazardDirection(mc, sides[i], extras[i])) {
                if (markBackup) { isBackup = true; backupDirection = currentDirection; }
                currentDirection = sides[i];
                hazardCommitTicks = 8;
                float[] v = dirValues(currentDirection);
                rotateTo(v[0], v[1], null);
                return;
            }
        }
        // Fully boxed in by hazard on every side. Only tower if it's actually safe above/below -
        // never dig straight down into lava at bedrock.
        if (!checkHazardAbove(mc) && !checkHazardBelow(mc)) {
            state = State.GOABOVEHAZARD;
        } else {
            // No safe path at all: stop and warn rather than digging into lava.
            com.autism.seedcracker.modules.FlagDetectorModule.report("HAZARD_BOXED", "TunnelBaseWater",
                "fully boxed by lava at " + mc.player.blockPosition());
            stopMovement(mc);
        }
    }

    /** True if there's lava/water directly below (so towering down would dig into it). */
    private boolean checkHazardBelow(Minecraft mc) {
        BlockPos pp = mc.player.blockPosition();
        for (int i = 1; i <= 4; i++) {
            if (isLavaOrWater(mc.level.getBlockState(pp.below(i)).getBlock())) return true;
        }
        return false;
    }

    private int jumpCooldown = 0;

    private void tryJumpStep(Minecraft mc) {
        if (jumpCooldown > 0) { jumpCooldown--; return; }
        BlockPos down = mc.player.blockPosition().relative(currentDirection);
        BlockPos up = down.above(), up2 = up.above(), up3 = mc.player.blockPosition().above(2);
        if (!isAir(mc, down) && isAir(mc, up) && isAir(mc, up2) && isAir(mc, up3)) {
            mc.options.keyJump.setDown(true);
            jumped = true;
            jumpCooldown = 4; // don't jump-spam every tick (jitter) - one hop per few ticks
        }
    }

    // ---- PEARL (crawl entry) ----
    private void handlePearl(Minecraft mc) {
        if (!ensureInHotbar(mc, Items.ENDER_PEARL, pearlSlot.get() - 1, State.BUYPEARL)) return;
        if (pearlReset) {
            updateUsage(mc, false);
            pearlReset = false;
            com.autism.seedcracker.util.InvSync.select(mc, findPickaxe(mc));
            rotateTo(mc.player.getYRot(), 0f, () -> state = State.MINING);
        }
        BlockPos front = mc.player.blockPosition().relative(currentDirection);
        BlockPos aboveOne = front.above().relative(currentDirection);
        BlockPos aboveTwo = front.above(3);
        if (!isAir(mc, front) && !isAir(mc, aboveTwo)) {
            mc.options.keyUp.setDown(false);
            updateMining(mc, true);
            if (isAir(mc, aboveOne) && !isRotating) {
                rotateTo(mc.player.getYRot(), 11f, () -> {
                    com.autism.seedcracker.util.InvSync.select(mc, pearlSlot.get() - 1);
                    updateUsage(mc, true);
                    pearlReset = true;
                });
            }
        } else {
            mc.options.keyUp.setDown(true);
            updateMining(mc, true);
        }
        if (checkHazardDirection(mc, currentDirection, 0)) avoidHazard(mc, false);
        tryJumpStep(mc);
    }

    // ---- GO ABOVE HAZARD (tower) ----
    private void handleGoAbove(Minecraft mc) {
        if (!ensureInHotbar(mc, Items.OBSIDIAN, obiSlot.get() - 1, State.BUYOBI)) return;
        if (checkHazardAbove(mc)) { disconnect(mc, "NO SAFE PATH - HAZARD ABOVE"); return; }
        if (towerBasePos == null) { towerBasePos = mc.player.blockPosition(); phase = Phase.DIG; phaseStartTime = System.currentTimeMillis(); }
        BlockPos below = mc.player.blockPosition().below();
        if (!isAir(mc, below) && !checkHazardDirection(mc, currentDirection, 0)) {
            float[] v = dirValues(currentDirection);
            rotateTo(v[0], v[1], () -> { com.autism.seedcracker.util.InvSync.select(mc, findPickaxe(mc)); state = State.MINING; resetTower(); });
            updateMining(mc, false); mc.options.keyJump.setDown(false); mc.options.keyUse.setDown(false);
            return;
        }
        if (isRotating) { updateMining(mc, false); return; }
        towerPhase(mc);
    }

    // ---- Y RECOVERY (tower back up) ----
    private void handleYRecovery(Minecraft mc) {
        if (!ensureInHotbar(mc, Items.OBSIDIAN, obiSlot.get() - 1, State.BUYOBI)) return;
        if (yRecoveryBasePos == null) { yRecoveryBasePos = mc.player.blockPosition(); phase = Phase.DIG; phaseStartTime = System.currentTimeMillis(); }
        BlockPos below = mc.player.blockPosition().below();
        if (mc.player.blockPosition().getY() >= MIN_Y_LEVEL && !isAir(mc, below)) {
            float[] v = dirValues(currentDirection);
            rotateTo(v[0], v[1], () -> {
                com.autism.seedcracker.util.InvSync.select(mc, findPickaxe(mc));
                state = State.MINING; yRecoveryBasePos = null; yRecoveryRotationDone = false;
                phase = Phase.DIG; phaseStartTime = System.currentTimeMillis();
            });
            updateMining(mc, false); mc.options.keyJump.setDown(false); mc.options.keyUse.setDown(false);
            return;
        }
        if (isRotating) { updateMining(mc, false); return; }
        towerPhase(mc);
    }

    private void towerPhase(Minecraft mc) {
        switch (phase) {
            case DIG -> {
                mc.options.keyJump.setDown(false); mc.options.keyUse.setDown(false);
                rotateTo(mc.player.getYRot(), 90f, () -> { com.autism.seedcracker.util.InvSync.select(mc, findPickaxe(mc)); updateMining(mc, true); });
                if (System.currentTimeMillis() - phaseStartTime >= PHASE_TIME_MS) { updateMining(mc, false); switchPhase(mc, Phase.TOWER); }
            }
            case TOWER -> {
                updateMining(mc, false);
                if (!towerRotationDone && !isRotating) {
                    towerRotationDone = true;
                    rotateTo(mc.player.getYRot(), 90f, () -> com.autism.seedcracker.util.InvSync.select(mc, obiSlot.get() - 1));
                }
                // Legit towering: only place when on the ground (never use-spam while airborne, the
                // classic scaffold flag), with a jittered per-place cooldown. Jump between places.
                if (placeCooldown > 0) { placeCooldown--; mc.options.keyUse.setDown(false); }
                else if (mc.player.onGround()) {
                    com.autism.seedcracker.util.InvSync.select(mc, obiSlot.get() - 1);
                    mc.options.keyJump.setDown(true);
                    updateUsage(mc, true);
                    placeCooldown = 2 + (int) (Math.random() * 2); // 2-3 ticks between places
                } else {
                    mc.options.keyJump.setDown(false);
                    updateUsage(mc, false);
                }
                if (System.currentTimeMillis() - phaseStartTime >= PHASE_TIME_MS) {
                    mc.options.keyJump.setDown(false); updateUsage(mc, false); switchPhase(mc, Phase.DIG);
                }
            }
        }
    }

    private int placeCooldown = 0;

    private void switchPhase(Minecraft mc, Phase p) {
        phase = p; phaseStartTime = System.currentTimeMillis(); towerRotationDone = false;
        updateMining(mc, false); updateUsage(mc, false); mc.options.keyJump.setDown(false);
    }

    private void resetTower() { towerBasePos = null; towerRotationDone = false; phase = Phase.DIG; phaseStartTime = System.currentTimeMillis(); }

    // ---- AUTO MEND ----
    private void handleMend(Minecraft mc) {
        switch (mendStage) {
            case ENSURE -> { if (!ensureXpReady(mc)) return; mendStage = MendStage.ROTATE_DOWN; }
            case ROTATE_DOWN -> {
                if (Math.abs(mc.player.getXRot() - 90) > 0.05) {
                    if (!isRotating) rotateTo(mc.player.getYRot(), 90f, () -> { com.autism.seedcracker.util.InvSync.select(mc, xpSlot.get() - 1); mendStage = MendStage.OFFHAND_XP; });
                } else mendStage = MendStage.OFFHAND_XP;
            }
            case OFFHAND_XP -> {
                if (hasXpOffhand(mc)) { mendStage = MendStage.THROW_XP; return; }
                offhandFromInventory(mc, xpSlot.get() - 1, Items.EXPERIENCE_BOTTLE);
                mendingGraceTicks = 40;
                mendStage = MendStage.THROW_XP;
            }
            case THROW_XP -> {
                if (!hasXpOffhand(mc) || mc.player.getOffhandItem().isEmpty()) { mendStage = MendStage.REOFFHAND_TOTEM; return; }
                mendingGraceTicks = 40;
                updateUsage(mc, true);
                com.autism.seedcracker.util.InvSync.select(mc, findPickaxe(mc));
            }
            case REOFFHAND_TOTEM -> {
                updateUsage(mc, false);
                if (!hasTotemOffhand(mc)) { offhandFromInventory(mc, findInInventory(mc, Items.TOTEM_OF_UNDYING), Items.TOTEM_OF_UNDYING); mendingGraceTicks = 40; return; }
                mendStage = MendStage.ROTATE_BACK;
            }
            case ROTATE_BACK -> {
                float[] v = dirValues(currentDirection);
                if (!isRotating) rotateTo(v[0], v[1], () -> { com.autism.seedcracker.util.InvSync.select(mc, findPickaxe(mc)); mendStage = MendStage.RESET; });
            }
            case RESET -> { mendStage = MendStage.ENSURE; state = backup; backup = State.NONE; }
        }
    }

    // ---- AUTO EAT ----
    private void handleEating(Minecraft mc) {
        if (!ensureInHotbar(mc, Items.GOLDEN_CARROT, carrotSlot.get() - 1, State.BUYCARROT)) return;
        if (mc.player.getInventory().getSelectedSlot() != carrotSlot.get() - 1) {
            com.autism.seedcracker.util.InvSync.select(mc, carrotSlot.get() - 1);
        } else {
            if (mc.player.getFoodData().getFoodLevel() <= 6) updateUsage(mc, true);
            else { updateUsage(mc, false); state = backup; backup = State.NONE; }
        }
    }

    // ---- SHOP RESTOCK (XP / pearl / obi / carrot share one stage machine) ----
    private void handleBuy(Minecraft mc) {
        switch (buyStage) {
            case NONE -> buyStage = BuyStage.OPENSHOP;
            case OPENSHOP -> {
                mc.getConnection().sendCommand("shop");
                buyWait = 0; waitTarget = randomDelay(7);
                buyItemSlot = switch (state) {
                    case BUYXP, BUYPEARL, BUYOBI, BUYTOTEM -> 13; // gear/combat category
                    default -> 14; // food category
                };
                buyStage = BuyStage.WAIT1;
            }
            case WAIT1 -> { if (++buyWait >= waitTarget) buyStage = BuyStage.CLICK1; }
            case CLICK1 -> {
                if (!clickIf(mc, 11, Items.END_STONE, buyItemSlot)) { retryOrReset(); return; }
                buyWait = 0; waitTarget = randomDelay(7); buyStage = BuyStage.WAIT2;
            }
            case WAIT2 -> { if (++buyWait >= waitTarget) buyStage = BuyStage.CLICK2; }
            case CLICK2 -> {
                if (state == State.BUYTOTEM) {
                    // Totem: scan the shop GUI for a totem item (slot varies by menu layout).
                    if (!clickItem(mc, Items.TOTEM_OF_UNDYING)) { retryOrReset(); return; }
                } else {
                    int slot = switch (state) { case BUYXP -> 16; case BUYPEARL -> 14; case BUYOBI -> 9; default -> 16; };
                    if (!clickIf(mc, 16, state == State.BUYCARROT ? Items.GOLDEN_CARROT : Items.EXPERIENCE_BOTTLE, slot)) { retryOrReset(); return; }
                }
                buyWait = 0; waitTarget = randomDelay(7); buyStage = BuyStage.WAIT3;
            }
            case WAIT3 -> { if (++buyWait >= waitTarget) buyStage = BuyStage.CLICKSTACK; }
            case CLICKSTACK -> {
                if (!clickIfLime(mc, 17)) { retryOrReset(); return; }
                buyWait = 0; waitTarget = randomDelay(7); buyStage = BuyStage.WAIT4;
            }
            case WAIT4 -> { if (++buyWait >= waitTarget) buyStage = BuyStage.DROPITEMS; }
            case DROPITEMS -> {
                // One junk stack per tick; stay in this stage until nothing junk is left.
                if (isInventoryFull(mc) && dropJunk(mc)) return;
                buyWait = 0; waitTarget = randomDelay(7); buyStage = BuyStage.WAIT5;
            }
            case WAIT5 -> { if (++buyWait >= waitTarget) buyStage = BuyStage.BUY; }
            case BUY -> {
                if (!clickIfHasStack(mc, 23)) { retryOrReset(); return; }
                buyWait = 0; waitTarget = randomDelay(7); buyStage = BuyStage.WAIT6;
            }
            case WAIT6 -> { if (++buyWait >= waitTarget) buyStage = BuyStage.CLOSE; }
            case CLOSE -> {
                if (mc.gui.screen() != null) mc.gui.setScreen(null);
                buyWait = 0; waitTarget = randomDelay(7); buyStage = BuyStage.WAIT7;
            }
            case WAIT7 -> { if (++buyWait >= waitTarget) buyStage = BuyStage.RESET; }
            case RESET -> {
                State ret = switch (state) {
                    case BUYXP -> State.AUTOMEND;
                    case BUYPEARL -> State.PEARL;
                    case BUYOBI -> State.MINING;
                    case BUYCARROT -> State.AUTOEAT;
                    case BUYTOTEM -> backup != State.NONE ? backup : State.MINING;
                    default -> State.MINING;
                };
                buyStage = BuyStage.NONE; buyWait = 0;
                backup = State.NONE;
                state = ret;
            }
        }
    }

    private void buyReset() { buyStage = BuyStage.NONE; buyWait = 0; buyRetries = 0; }

    private int buyRetries = 0;

    /**
     * A click stage failed (GUI not open yet / wrong page - servers lag GUI opens). Retry the
     * same stage a few ticks before aborting the buy; instant aborts caused an open-shop spam loop.
     */
    private boolean retryOrReset() {
        if (++buyRetries <= com.autism.seedcracker.util.Tuning.BUY_CLICK_RETRIES) return true;
        buyReset();
        return false;
    }

    private boolean clickIf(Minecraft mc, int guardSlot, Item guard, int clickSlot) {
        if (mc.gui.screen() instanceof ContainerScreen) {
            AbstractContainerMenu h = mc.player.containerMenu;
            if (guardSlot < h.slots.size() && clickSlot < h.slots.size()
                && h.getSlot(guardSlot).getItem().is(guard)) {
                com.autism.seedcracker.util.ContainerMutex.notifyContainerAction(); mc.gameMode.handleContainerInput(h.containerId, clickSlot, 0, ContainerInput.PICKUP, mc.player);
                buyRetries = 0;
                return true;
            }
        }
        return false;
    }

    private boolean clickIfLime(Minecraft mc, int slot) {
        if (mc.gui.screen() instanceof ContainerScreen) {
            AbstractContainerMenu h = mc.player.containerMenu;
            if (slot < h.slots.size() && ShopBuyerModule.isLimePane(h.getSlot(slot).getItem())) {
                com.autism.seedcracker.util.ContainerMutex.notifyContainerAction(); mc.gameMode.handleContainerInput(h.containerId, slot, 0, ContainerInput.PICKUP, mc.player);
                buyRetries = 0;
                return true;
            }
        }
        return false;
    }

    private boolean clickIfHasStack(Minecraft mc, int slot) {
        if (mc.gui.screen() instanceof ContainerScreen) {
            AbstractContainerMenu h = mc.player.containerMenu;
            if (slot < h.slots.size() && h.getSlot(slot).hasItem()) {
                com.autism.seedcracker.util.ContainerMutex.notifyContainerAction(); mc.gameMode.handleContainerInput(h.containerId, slot, 0, ContainerInput.PICKUP, mc.player);
                buyRetries = 0;
                return true;
            }
        }
        return false;
    }

    /** Scan the open shop GUI for an item and click its slot (for the totem, whose slot varies). */
    private boolean clickItem(Minecraft mc, Item item) {
        if (mc.gui.screen() instanceof ContainerScreen) {
            AbstractContainerMenu h = mc.player.containerMenu;
            int containerSlots = h.slots.size() - 36;
            for (int i = 0; i < containerSlots; i++) {
                var slot = h.slots.get(i);
                if (slot.hasItem() && slot.getItem().is(item)) {
                    com.autism.seedcracker.util.ContainerMutex.notifyContainerAction(); mc.gameMode.handleContainerInput(h.containerId, i, 0, ContainerInput.PICKUP, mc.player);
                    return true;
                }
            }
        }
        return false;
    }

    // ---- inventory helpers ----
    private boolean ensureXpReady(Minecraft mc) {
        if (hasXpOffhand(mc)) return true;
        return ensureInHotbar(mc, Items.EXPERIENCE_BOTTLE, xpSlot.get() - 1, State.BUYXP);
    }

    /** Ensure an item is in the given hotbar slot; if it's elsewhere, 3-click it over (open inv). */
    private boolean ensureInHotbar(Minecraft mc, Item item, int hotbarSlot, State buyState) {
        if (mc.player.getInventory().getItem(hotbarSlot).is(item)) return true;
        int found = findInInventory(mc, item);
        if (found == -1) { state = buyState; buyStage = BuyStage.NONE; return false; }
        if (!(mc.gui.screen() instanceof InventoryScreen)) { mc.gui.setScreen(new InventoryScreen(mc.player)); return false; }
        moveSlot(mc, found, hotbarSlot);
        shouldCloseInventory = true;
        return false;
    }

    /**
     * Move an inventory stack into a HOTBAR slot with a single vanilla SWAP click (hover slot +
     * number key) instead of 3 pickup clicks in one tick - one packet, exactly what a real
     * player's "hover + press 3" does.
     */
    private void moveSlot(Minecraft mc, int from, int to) {
        AbstractContainerMenu h = mc.player.inventoryMenu;
        if (to < 9) {
            com.autism.seedcracker.util.ContainerMutex.notifyContainerAction();
            mc.gameMode.handleContainerInput(h.containerId, toHandler(from), to, ContainerInput.SWAP, mc.player);
            return;
        }
        // Non-hotbar destination: keep the pickup dance (rare path).
        int fromH = toHandler(from), toH = toHandler(to);
        com.autism.seedcracker.util.ContainerMutex.notifyContainerAction(); mc.gameMode.handleContainerInput(h.containerId, fromH, 0, ContainerInput.PICKUP, mc.player);
        com.autism.seedcracker.util.ContainerMutex.notifyContainerAction(); mc.gameMode.handleContainerInput(h.containerId, toH, 0, ContainerInput.PICKUP, mc.player);
        com.autism.seedcracker.util.ContainerMutex.notifyContainerAction(); mc.gameMode.handleContainerInput(h.containerId, fromH, 0, ContainerInput.PICKUP, mc.player);
    }

    /** Offhand swap: single vanilla SWAP click with button 40 (the F-key), not 3 pickups. */
    private void offhandFromInventory(Minecraft mc, int invSlot, Item item) {
        if (invSlot < 0) return;
        AbstractContainerMenu h = mc.player.inventoryMenu;
        com.autism.seedcracker.util.ContainerMutex.notifyContainerAction();
        mc.gameMode.handleContainerInput(h.containerId, toHandler(invSlot), 40, ContainerInput.SWAP, mc.player);
    }

    /** Water's convertSlotIndex: inventory index (0-35) -> player-inventory-menu handler slot. */
    private static int toHandler(int slotIndex) {
        if (slotIndex < 9) return 36 + slotIndex; // hotbar
        return slotIndex; // main inventory rows map 1:1
    }

    private static int findInInventory(Minecraft mc, Item item) {
        for (int i = 0; i < 36; i++) if (mc.player.getInventory().getItem(i).is(item)) return i;
        return -1;
    }

    private int findPickaxe(Minecraft mc) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (!s.isEmpty() && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString().endsWith("_pickaxe")) return i;
        }
        return 0;
    }

    private boolean isHoldingTool(Minecraft mc) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem()).toString().endsWith("_pickaxe");
    }

    private boolean isPickaxeLow(Minecraft mc) {
        ItemStack s = mc.player.getMainHandItem();
        if (s.isEmpty() || s.getMaxDamage() <= 0) return false;
        double pct = ((s.getMaxDamage() - s.getDamageValue()) / (double) s.getMaxDamage()) * 100.0;
        return pct <= 5.0;
    }

    private static boolean hasTotemOffhand(Minecraft mc) { return mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING); }
    private static boolean hasXpOffhand(Minecraft mc) { return mc.player.getOffhandItem().is(Items.EXPERIENCE_BOTTLE); }

    private boolean isInventoryFull(Minecraft mc) {
        for (int i = 0; i < 36; i++) if (mc.player.getInventory().getItem(i).isEmpty()) return false;
        return true;
    }

    /** Throw ONE junk stack per call (36 THROW clicks in one tick is a bot packet burst). */
    private boolean dropJunk(Minecraft mc) {
        AbstractContainerMenu h = mc.player.containerMenu;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            boolean junk = false;
            for (Item j : JUNK_ITEMS) if (s.is(j)) { junk = true; break; }
            if (junk) {
                com.autism.seedcracker.util.ContainerMutex.notifyContainerAction(); mc.gameMode.handleContainerInput(h.containerId, toHandler(i), 1, ContainerInput.THROW, mc.player);
                return true; // one per tick; caller stays in the stage until we return false
            }
        }
        return false;
    }

    // ---- hazard detection (Water logic) ----
    private boolean checkHazardDirection(Minecraft mc, Direction facing, int extra) {
        if (facing == null) return false;
        BlockPos pp = mc.player.blockPosition();
        int py = pp.getY();
        int minY = py - 1;
        int maxY = switch (mode.get()) {
            case STANDING -> py + 2;
            case CRAWL -> py + 1;
            default -> py + 3;
        };
        int scan = 5 + extra;
        int expand = mode.get() == MiningMode.AMETHYST ? 2 : 1;
        for (int y = minY; y <= maxY; y++) {
            int ex = y >= py ? expand : 0;
            for (int x = -ex; x <= ex; x++) {
                for (int i = 1; i <= scan; i++) {
                    BlockPos fwd = pp.relative(facing, i);
                    BlockPos pos = (facing == Direction.NORTH || facing == Direction.SOUTH)
                        ? new BlockPos(fwd.getX() + x, y, fwd.getZ())
                        : new BlockPos(fwd.getX(), y, fwd.getZ() + x);
                    net.minecraft.world.level.block.state.BlockState st = mc.level.getBlockState(pos);
                    if (isFluid(st)) return true;
                    if (isContactHazard(st.getBlock())) return true;
                    if (y < py && st.isAir() && x == 0) {
                        BlockPos down = pos.below();
                        while (mc.level.getBlockState(down).isAir() && down.getY() > mc.level.getMinY()) down = down.below();
                        if (isFluid(mc.level.getBlockState(down))) return true;
                    }
                    // Any gravity block above head height in our lane will fall on us when disturbed.
                    if (y > py && x == 0
                        && st.getBlock() instanceof net.minecraft.world.level.block.FallingBlock) return true;
                }
            }
        }
        return false;
    }

    private boolean checkHazardAbove(Minecraft mc) {
        for (int i = 1; i <= 4; i++) {
            net.minecraft.world.level.block.state.BlockState st = mc.level.getBlockState(mc.player.blockPosition().above(i));
            if (isFluid(st) || st.getBlock() instanceof net.minecraft.world.level.block.FallingBlock) return true;
            if (!st.isAir()) break; // solid roof shields anything higher
        }
        return false;
    }

    private static boolean isLavaOrWater(Block b) { return b == Blocks.LAVA || b == Blocks.WATER; }

    /** Fluid state check: catches flowing lava/water and waterlogged blocks, not just source blocks. */
    private static boolean isFluid(net.minecraft.world.level.block.state.BlockState st) {
        return !st.getFluidState().isEmpty();
    }

    /** Blocks that hurt on contact while tunneling through them (shared Hazards classification). */
    private static boolean isContactHazard(Block b) {
        return com.autism.seedcracker.util.tunnel.Hazards.isContactHazard(b);
    }
    private static boolean isShulker(Block b) {
        net.minecraft.resources.Identifier id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b);
        return id != null && id.toString().endsWith("shulker_box");
    }
    private static boolean isAir(Minecraft mc, BlockPos p) { return mc.level.getBlockState(p).isAir(); }

    // ---- direction helpers ----
    private static Direction dirLeft(Direction d) {
        return switch (d) { case NORTH -> Direction.WEST; case WEST -> Direction.SOUTH; case SOUTH -> Direction.EAST; case EAST -> Direction.NORTH; default -> d; };
    }
    private static Direction dirRight(Direction d) {
        return switch (d) { case NORTH -> Direction.EAST; case EAST -> Direction.SOUTH; case SOUTH -> Direction.WEST; case WEST -> Direction.NORTH; default -> d; };
    }

    private float[] dirValues(Direction d) {
        float pitch = mode.get() == MiningMode.STANDING ? 45f : 0f;
        float yaw = switch (d) {
            case NORTH -> 180f; case SOUTH -> 0f; case WEST -> 90f; case EAST -> 270f;
            case UP -> { pitch = -90f; yield 0f; }
            case DOWN -> { pitch = 90f; yield 0f; }
        };
        return new float[] { yaw, pitch };
    }

    private static Direction horizontalDir(Minecraft mc) {
        float yaw = ((mc.player.getYRot() % 360f) + 360f) % 360f;
        if (yaw >= 45f && yaw < 135f) return Direction.WEST;
        if (yaw >= 135f && yaw < 225f) return Direction.NORTH;
        if (yaw >= 225f && yaw < 315f) return Direction.EAST;
        return Direction.SOUTH;
    }

    // ---- movement / action key helpers (Water updateMining/updateUsage) ----
    private void updateMining(Minecraft mc, boolean mining) {
        boolean screenOpen = mc.gui.screen() != null;
        if (mining) {
            if (resetMiningTick > 0) { resetMiningTick--; mc.options.keyAttack.setDown(false); return; }
            if (wasScreenOpen && !screenOpen) { mc.options.keyAttack.setDown(false); resetMiningTick = 1; return; }
            mc.options.keyAttack.setDown(true);
        } else { resetMiningTick = 0; mc.options.keyAttack.setDown(false); }
    }

    private void updateUsage(Minecraft mc, boolean active) {
        boolean screenOpen = mc.gui.screen() != null;
        if (active) {
            if (resetUseTick > 0) { resetUseTick--; mc.options.keyUse.setDown(false); return; }
            if (wasScreenOpen && !screenOpen) { mc.options.keyUse.setDown(false); resetUseTick = 1; return; }
            mc.options.keyUse.setDown(true);
        } else { resetUseTick = 0; mc.options.keyUse.setDown(false); }
    }

    private void stopMovement(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        updateUsage(mc, false);
        mc.options.keyJump.setDown(false);
        updateMining(mc, false);
    }

    private int randomDelay(int base) {
        int d = base;
        if (humanize.get()) {
            int r = (int) (Math.random() * delayRandomness.get() * 2) - delayRandomness.get();
            d = Math.max(1, base + r);
        }
        // TPS sync: GUI responses lag with the server; clicking on client-tick pacing lands early.
        return com.autism.seedcracker.util.TickRateTracker.scale(d);
    }

    private void disconnect(Minecraft mc, String text) {
        AutismClientMessaging.sendPrefixed("§c[TunnelBase-Water] §f" + text);
        setEnabledSilently(false);
        if (mc.getConnection() != null) {
            mc.getConnection().getConnection().disconnect(net.minecraft.network.chat.Component.literal("TunnelBase(Water) | " + text));
        }
    }

    @Override public String info() {
        return state == null ? "idle" : state.name().toLowerCase(java.util.Locale.ROOT);
    }
}
