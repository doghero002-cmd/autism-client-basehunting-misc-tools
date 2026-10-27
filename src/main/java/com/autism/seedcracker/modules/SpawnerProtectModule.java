/* SPDX-License-Identifier: GPL-3.0-only
 * Adapted with permission from Spectre-26.2 d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da:
 * net/spectre/client/module/impl/misc/SpawnerProtectModule.java and MixinSpawnerProtect*.
 * No per-file MIT notice was present in those sources. Existing addon id/settings are retained.
 * Intentional safety deviations: native lifecycle/settings, manual input wins, real ray/reach and
 * Silk Touch checks, bounded retries, optional shop/logout, server-confirmed inventory/placement,
 * and only OUR confirmed filled shulker may be recovered/stored. No Discord/telemetry/runtime donor.
 */
package com.autism.seedcracker.modules;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.DoubleSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.compat.ClientCompatUtil;
import com.autism.seedcracker.compat.ClientNotify;
import com.autism.seedcracker.motion.RotationEngine;
import com.autism.seedcracker.spectre.mechanics.ProtectionInventoryTransaction;
import com.autism.seedcracker.spectre.mechanics.ProtectionSession;
import com.autism.seedcracker.spectre.mechanics.SpawnerInventoryGuard;
import com.autism.seedcracker.spectre.mechanics.SpawnerProtectionPolicy;
import com.autism.seedcracker.spectre.mechanics.SpawnerStorageProof;
import com.autism.seedcracker.util.ActionPacer;
import com.autism.seedcracker.util.ContainerMutex;
import com.autism.seedcracker.util.InvSync;
import com.autism.seedcracker.util.RelogPersistence;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.HashedStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class SpawnerProtectModule extends Module {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(SpawnerProtectModule.class);
    /** Only the three NEW SpectreSpawner* mixins refer to this hook; native packet hooks don't consume packets. */
    public static volatile SpawnerProtectModule active;
    private static final int ACTION_TIMEOUT = 100;
    private static final int MINE_TIMEOUT = 240;
    private static final int RUN_TIMEOUT = 20 * 300;
    private static final Predicate<ItemStack> SPAWNER = stack -> stack.is(Items.SPAWNER);
    private static final Predicate<ItemStack> ENDER_CHEST = stack -> stack.is(Items.ENDER_CHEST);

    // Existing keys/defaults survive configuration round trips. Webhook values are retained locally,
    // explicitly deprecated rather than silently transmitting a player's location/account to anyone.
    private final StringSetting whitelist = add(new StringSetting("whitelist", "Whitelist", "")
        .description("Player names to ignore; commas, semicolons or whitespace. Teams are also ignored.").group("General"));
    private final StringSetting webhook = add(new StringSetting("webhook", "Webhook (legacy, not sent)", "")
        .description("Preserved for old configurations only. This port never sends webhooks or telemetry.").group("General")
        .visibleWhen(() -> false)); // migration-only key, not a disconnected visible control
    private final IntSetting enemyRadius = add(new IntSetting("enemy-radius", "Enemy radius", 32, 4, 64, 1)
        .description("Nearby non-whitelisted player trigger, in blocks.").group("General"));
    private final BoolSetting sneakMine = add(new BoolSetting("sneak-mine", "Sneak mine (hold shift)", true)
        .description("Request transient sneak input while mining; physical controls always win.").group("Storage"));
    private final BoolSetting storeShulker = add(new BoolSetting("store-shulker", "Store in shulker", true)
        .description("Pack into an EMPTY shulker from your inventory; no unrelated box is mined.").group("Storage"));
    private final BoolSetting storeEChest = add(new BoolSetting("store-echest", "Store in ender chest", true)
        .description("Store the confirmed filled shulker, or loose spawners when shulker packing is off.").group("Storage"));
    private final IntSetting targetStackCount = add(new IntSetting("stack-to-deposit", "Stack to deposit", 3, 1, 30, 1)
        .description("Begin a storage batch after this many stacks, or when no spawners remain.").group("Storage"));
    private final IntSetting scanRange = add(new IntSetting("trigger-range", "Trigger range", 64, 16, 128, 1)
        .description("Horizontal radius for remote block-break detection, in blocks.").group("Detection"));
    private final DoubleSetting rotationSpeed = add(new DoubleSetting("rotation-speed", "Rotation speed", 15, 1, 30, 1)
        .description("Maximum visible rotation per client tick, in degrees.").group("General"));
    private final DoubleSetting breakRange = add(new DoubleSetting("break-range", "Break range", 5.5, 1, 8, 0.5)
        .description("Desired mining range; never exceeds native block interaction reach.").group("General"));
    private final BoolSetting detectBlockUpdates = add(new BoolSetting("detect-block-updates", "Detect block updates", true)
        .description("Trigger on remote breaks, excluding spawn, nearby self/friendly breaks and shulkers.").group("Detection"));
    private final IntSetting blockUpdateThreshold = add(new IntSetting("block-update-threshold", "Block update threshold", 5, 1, 20, 1)
        .description("Distinct remote broken blocks required to trigger.").group("Detection"));
    private final IntSetting blockUpdateWindow = add(new IntSetting("block-update-window", "Block update window (seconds)", 60, 1, 600, 1)
        .description("Reset remote break count after this much inactivity.").group("Detection"));
    private final BoolSetting buyEChest = add(new BoolSetting("buy-echest", "Buy missing ender chest", false)
        .description("Opt-in: run /shop and use Spectre's verified-title End/Ender Chest/Confirm sequence. May spend money.").group("Storage"));
    private final BoolSetting logoutAfter = add(new BoolSetting("logout-after", "Disconnect after completion", false)
        .description("Opt-in: disconnect two seconds after confirmed completion, never after failure.").group("General"));

    private enum State {
        WAITING, WORKING, PREPARE_SHULKER, WAIT_SHULKER, OPEN_SHULKER, FILL_SHULKER,
        BREAK_SHULKER, COLLECT_SHULKER, PREPARE_ECHEST, WAIT_ECHEST, OPEN_ECHEST,
        DEPOSIT_ECHEST, SHOP, EXIT_WAIT, COMPLETE, FAILED
    }
    private volatile State state = State.WAITING;
    private final ProtectionSession session = new ProtectionSession();
    private final SpawnerInventoryGuard inventory = new SpawnerInventoryGuard();
    private final SpawnerProtectionPolicy detection = new SpawnerProtectionPolicy();
    private final SpawnerStorageProof storageProof = new SpawnerStorageProof();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean selectedByUser = new AtomicBoolean();
    private volatile Thread actionThread;
    private long tick, stateSince, runSince, selectionReadyAt, lastUseTick, lastScanTick;
    private int noTargetTicks, collectedCount, miningTicks, breakCooldown;
    private boolean forwardHeld, sneakHeld, miningOwned, mineAttempt, brokenConfirmed;
    private BlockPos target, targetChest, placedShulker, placement;
    private Block placementBlock;
    private boolean placementConfirmed, shulkerOwned;
    private int placementSlot = -1, placementCount, collectionBefore, packedBefore;
    private long placementVersion, collectionVersion, packedVersion;
    private ItemStack placedStack = ItemStack.EMPTY, packedStack = ItemStack.EMPTY;
    private AbstractContainerMenu ownedMenu;
    private boolean openSent;
    private int shopStep, packedSlot = -1, shopBefore;
    private long shopVersion, shopSince;
    private String lastWhitelist;
    private Set<String> names = Set.of();
    private String failure = "";

    public SpawnerProtectModule() {
        super(SeedcrackerAddon.ID + ":z-spawner-protect", "Spawner Protect",
            "Collects spawners on a nearby-player or remote-break trigger and confirms safe storage.");
    }

    @Override public void onEnable() { reset(Minecraft.getInstance(), true); active = this; }
    @Override public void onDisable() { if (active == this) active = null; reset(Minecraft.getInstance(), true); }
    @Override public void onGameLeft() {
        if (active == this) active = null;
        reset(Minecraft.getInstance(), false);
        if (RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }
    @Override public void onGameJoin() { reset(Minecraft.getInstance(), false); if (isEnabled()) active = this; }
    @Override protected void onOptionValueChanged(String key) { if (isDriving()) cancelled.set(true); }

    private void reset(Minecraft mc, boolean restoreSlot) {
        releaseControls(mc);
        if (restoreSlot) restoreSlot(mc);
        session.clear();
        inventory.clear();
        detection.clear();
        storageProof.clear();
        cancelled.set(false);
        selectedByUser.set(false);
        state = State.WAITING;
        tick = stateSince = runSince = selectionReadyAt = lastUseTick = lastScanTick = 0;
        noTargetTicks = collectedCount = miningTicks = breakCooldown = 0;
        target = targetChest = placedShulker = placement = null;
        placementBlock = null;
        mineAttempt = brokenConfirmed = placementConfirmed = shulkerOwned = openSent = false;
        placementSlot = packedSlot = -1;
        ownedMenu = null;
        placedStack = packedStack = ItemStack.EMPTY;
        shopStep = 0;
        lastWhitelist = null;
        names = Set.of();
        failure = "";
    }

    public boolean isDriving() { return isEnabled() && state != State.WAITING && state != State.COMPLETE && state != State.FAILED; }
    private boolean sameSession(Minecraft mc) { return session.owns(mc.level, mc.player, mc.getConnection()); }
    private boolean ownAction() { return Thread.currentThread() == actionThread; }
    private void owned(Runnable action) {
        Thread previous = actionThread;
        actionThread = Thread.currentThread();
        try { action.run(); } finally { actionThread = previous; }
    }
    private void sendOwned(Packet<?> packet) { owned(() -> Minecraft.getInstance().getConnection().send(packet)); }

    @Override public boolean onPacketSend(Packet<?> packet) {
        boolean manual = packet instanceof ServerboundContainerClickPacket || packet instanceof ServerboundContainerClosePacket
            || packet instanceof ServerboundSetCarriedItemPacket || packet instanceof ServerboundUseItemOnPacket
            || packet instanceof ServerboundUseItemPacket || packet instanceof ServerboundInteractPacket
            || packet instanceof ServerboundPlayerActionPacket;
        if (SpawnerProtectionPolicy.manualActionInterrupts(isDriving(), ownAction(), manual)) {
            if (packet instanceof ServerboundSetCarriedItemPacket) selectedByUser.set(true);
            cancelled.set(true);
        }
        return false; // observation is NOT cancellation; the user's packet always wins
    }
    @Override public boolean onPacketReceive(Packet<?> packet) { return false; } // native callback may be off-thread
    @Override public void onMouseRotation(double dx, double dy) {
        if (isDriving() && (dx != 0 || dy != 0)) cancelled.set(true);
    }
    @Override public boolean shouldCancelAttack(HitResult hit) { interruptManualAction(); return false; }
    @Override public boolean shouldCancelUse(HitResult hit, InteractionHand hand) { interruptManualAction(); return false; }
    @Override public boolean onStartDestroyBlock(BlockPos pos, Direction face) {
        if (isDriving() && !ownAction()) { miningOwned = false; cancelled.set(true); }
        return false;
    }
    @Override public boolean shouldCancelStartBreakingBlock(BlockPos pos, Direction face) {
        return onStartDestroyBlock(pos, face);
    }
    private void interruptManualAction() { if (isDriving() && !ownAction()) cancelled.set(true); }

    /** Prevent only vanilla's idle continueAttack(false) from cancelling OUR continuous dig. */
    public boolean onVanillaContinueAttack(boolean down) {
        Minecraft mc = Minecraft.getInstance();
        if (!isDriving() || !sameSession(mc) || mc.gui.screen() != null) return false;
        if (down) { miningOwned = false; cancelled.set(true); return false; }
        return miningOwned && !cancelled.get() && !ActionPacer.holding();
    }

    /** Called before KeyboardInput constructs its Input/vector. Never writes KeyMappings. */
    public Input protectionInput(Input physical) {
        Minecraft mc = Minecraft.getInstance();
        boolean manual = physical.forward() || physical.backward() || physical.left() || physical.right()
            || physical.jump() || physical.shift() || physical.sprint();
        if (isDriving() && manual) cancelled.set(true);
        if (!SpawnerProtectionPolicy.mayDrive(isDriving(), sameSession(mc), mc.gui.screen() != null,
                ActionPacer.holding(), manual || cancelled.get())) return physical;
        return new Input(forwardHeld, false, false, false, false, sneakHeld, false);
    }

    @Override public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (!isEnabled() || !mc.isSameThread()) return;
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.getConnection() == null
                || !mc.player.isAlive() || mc.player.isSpectator()) {
            reset(mc, false);
            return;
        }
        if (!sameSession(mc)) {
            reset(mc, false);
            session.bind(mc.level, mc.player, mc.getConnection());
            inventory.bind(mc);
            active = this;
        }
        tick++;
        forwardHeld = sneakHeld = false;
        if (state == State.COMPLETE || state == State.FAILED) return;
        if (isDriving() && (cancelled.getAndSet(false) || physicalAction(mc)
                || !session.slotUnchanged(mc.player.getInventory().getSelectedSlot()) || mc.getCameraEntity() != mc.player)) {
            fail(mc, "Cancelled by manual input or another controller; inventory left intact");
            return;
        }
        if (state == State.WAITING) {
            if (!SpawnerProtectionPolicy.nearSpawn(mc.player.getX(), mc.player.getZ()) && enemyNear(mc)) startWork("Nearby player detected");
            return;
        }
        if (SpawnerProtectionPolicy.nearSpawn(mc.player.getX(), mc.player.getZ())) { fail(mc, "Entered spawn safety area"); return; }
        if (tick - runSince > RUN_TIMEOUT) { fail(mc, "Protection run timed out"); return; }
        if (inventory.hasTransaction()) {
            stopMining(mc);
            var result = inventory.poll(mc, session.token(), tick);
            if (result == ProtectionInventoryTransaction.Result.PENDING) return;
            if (result != ProtectionInventoryTransaction.Result.CONFIRMED) { fail(mc, "Server inventory confirmation: " + result); return; }
            int transferred = inventory.takeConfirmedDepositCount();
            if (transferred > 0) storageProof.transferred(transferred);
            // Do not act on the confirmation in the same tick as its last inventory mutation.
            return;
        }
        if (!mc.player.containerMenu.getCarried().isEmpty()) { fail(mc, "Cursor occupied; menu left open, nothing dropped"); return; }
        if (mc.player.isUsingItem()) { fail(mc, "Item use interrupted protection"); return; }
        if (ActionPacer.holding()) { releaseControls(mc); return; }
        if (mc.gui.screen() != null && mc.player.containerMenu != ownedMenu) {
            fail(mc, "Unexpected screen; protection stopped"); return;
        }
        if (ownedMenu != null && mc.player.containerMenu != ownedMenu) { fail(mc, "Owned menu was closed or replaced"); return; }
        try {
            switch (state) {
                case WORKING -> work(mc);
                case PREPARE_SHULKER -> prepareShulker(mc);
                case WAIT_SHULKER, WAIT_ECHEST -> waitPlacement(mc);
                case OPEN_SHULKER, OPEN_ECHEST -> openStorage(mc);
                case FILL_SHULKER, DEPOSIT_ECHEST -> deposit(mc);
                case BREAK_SHULKER -> recoverShulker(mc);
                case COLLECT_SHULKER -> collectShulker(mc);
                case PREPARE_ECHEST -> prepareEnderChest(mc);
                case SHOP -> shop(mc);
                case EXIT_WAIT -> finishExit(mc);
                default -> { }
            }
        } catch (RuntimeException error) {
            LOGGER.error("SpawnerProtect failed in {} (target {}, shulker {})", state, target, placedShulker, error);
            fail(mc, "Protection failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private boolean physicalAction(Minecraft mc) {
        return mc.options.keyUp.isDown() || mc.options.keyDown.isDown() || mc.options.keyLeft.isDown()
            || mc.options.keyRight.isDown() || mc.options.keyJump.isDown() || mc.options.keyShift.isDown()
            || mc.options.keyAttack.isDown() || mc.options.keyUse.isDown();
    }

    private void startWork(String reason) {
        runSince = tick;
        transition(State.WORKING);
        detection.clear();
        notifyLocal(reason + "; collecting spawners");
    }
    private void transition(State next) { state = next; stateSince = tick; forwardHeld = sneakHeld = false; }

    private boolean enemyNear(Minecraft mc) {
        for (Player other : mc.level.players()) {
            if (other != mc.player && other.isAlive() && !other.isSpectator() && !friendly(other)
                    && other.distanceToSqr(mc.player) <= (double) enemyRadius.get() * enemyRadius.get()) return true;
        }
        return false;
    }
    private boolean friendly(Player player) {
        String raw = whitelist.get();
        if (!java.util.Objects.equals(raw, lastWhitelist)) { names = SpawnerProtectionPolicy.whitelist(raw); lastWhitelist = raw; }
        return names.contains(player.getGameProfile().name().toLowerCase(Locale.ROOT)) || ClientCompatUtil.isFriendOrTeam(player);
    }

    /** Invoked AFTER PacketUtils' thread handoff but BEFORE old block state is overwritten. */
    public void onServerBlockUpdate(ClientPacketListener source, BlockPos pos, BlockState next) {
        Minecraft mc = Minecraft.getInstance();
        if (!acceptPacket(mc, source)) return;
        if (placement != null && pos.equals(placement) && next.is(placementBlock)) placementConfirmed = true;
        if (target != null && pos.equals(target) && next.isAir()) brokenConfirmed = true;
        if (placedShulker != null && pos.equals(placedShulker) && shulkerOwned
                && !(next.getBlock() instanceof ShulkerBoxBlock)) {
            if (state == State.BREAK_SHULKER && next.isAir()) brokenConfirmed = true;
            else shulkerOwned = false;
        }
        if (state != State.WAITING || !detectBlockUpdates.get()) return;
        BlockState old = mc.level.getBlockState(pos);
        Vec3 center = Vec3.atCenterOf(pos);
        boolean friendlyNear = false;
        for (Player other : mc.level.players()) {
            if (other != mc.player && friendly(other) && other.getEyePosition().distanceToSqr(center) < 64.0) {
                friendlyNear = true; break;
            }
        }
        double dx = mc.player.getX() - pos.getX(), dz = mc.player.getZ() - pos.getZ();
        if (SpawnerProtectionPolicy.remoteBreak(SpawnerProtectionPolicy.nearSpawn(mc.player.getX(), mc.player.getZ()),
                pos.getY(), old.isAir(), next.isAir(), old.getBlock() instanceof ShulkerBoxBlock, friendlyNear,
                mc.player.getEyePosition().distanceToSqr(center), dx * dx + dz * dz, scanRange.get())
                && detection.recordBreak(pos.asLong(), System.nanoTime(), blockUpdateThreshold.get(), blockUpdateWindow.get())) {
            startWork("Remote block-break threshold reached");
        }
    }

    /** Only post-handler inbound payloads reach the evidence adapter; never predicted menus. */
    public void onServerInventoryPacket(ClientPacketListener source, Packet<?> packet) {
        Minecraft mc = Minecraft.getInstance();
        if (!acceptPacket(mc, source)) return;
        if (packet instanceof ClientboundContainerSetContentPacket p) inventory.content(mc, session.token(), p, tick);
        else if (packet instanceof ClientboundContainerSetSlotPacket p) inventory.slot(mc, session.token(), p, tick);
        else if (packet instanceof ClientboundSetPlayerInventoryPacket p) inventory.playerSlot(p.slot(), p.contents());
        else if (packet instanceof ClientboundSetCursorItemPacket p) inventory.cursor(p.contents().isEmpty());
        else if (packet instanceof ClientboundOpenScreenPacket) {
            if ((state == State.OPEN_SHULKER && openSent && mc.player.containerMenu instanceof ShulkerBoxMenu)
                    || (state == State.OPEN_ECHEST && openSent && mc.player.containerMenu instanceof ChestMenu chest && chest.getRowCount() == 3)
                    || (state == State.SHOP && shopStep > 0 && mc.player.containerMenu instanceof ChestMenu)) {
                ownedMenu = mc.player.containerMenu;
            } else if (isDriving()) cancelled.set(true);
        } else if (packet instanceof ClientboundContainerClosePacket && isDriving() && ownedMenu != null) {
            // Shop plugins may close between their own pages or immediately after purchase.
            // An outbound user close still interrupts through onPacketSend.
            if (state == State.SHOP && shopStep >= 2) ownedMenu = null;
            else cancelled.set(true);
        }
    }
    private boolean acceptPacket(Minecraft mc, ClientPacketListener source) {
        return isEnabled() && mc.isSameThread() && sameSession(mc) && source == mc.getConnection()
            && source.getLevel() == mc.level && state != State.FAILED && state != State.COMPLETE;
    }

    private void work(Minecraft mc) {
        if (!worldReady(mc)) return;
        if (mineAttempt && inventory.version() > collectionVersion && inventory.count(SPAWNER) > collectionBefore) {
            int gained = inventory.count(SPAWNER) - collectionBefore;
            // Donut stacked spawners can yield an item while retaining the spawner block itself.
            collectedCount += gained;
            stopMining(mc);
            mineAttempt = brokenConfirmed = false;
            target = null;
            breakCooldown = 6;
            notifyLocal("Server confirmed " + gained + " collected spawner(s)");
            return;
        }
        if (mineAttempt && tick - stateSince > MINE_TIMEOUT) { fail(mc, "Mining/collection timed out; no collection assumed"); return; }
        if (breakCooldown > 0) { breakCooldown--; return; }
        if (inventory.count(SPAWNER) >= targetStackCount.get() * 64 && !mineAttempt) { beginStorage(mc); return; }
        ItemEntity dropped = droppedItem(mc, SPAWNER);
        if (dropped != null && (!mineAttempt || brokenConfirmed || !isSpawner(mc, target))) {
            stopMining(mc);
            approach(mc, dropped.position(), 1.0);
            return;
        }
        if (mineAttempt && !isSpawner(mc, target)) {
            stopMining(mc); // local prediction / server break alone is NOT item collection
            return;
        }
        if (target == null || (!mineAttempt && !isSpawner(mc, target))) {
            if (tick - lastScanTick >= 10 || lastScanTick == 0) {
                target = nearestBlock(mc, Blocks.SPAWNER, 16);
                lastScanTick = tick;
            }
            if (target == null) {
                stopMining(mc);
                if (++noTargetTicks >= 40) { if (inventory.count(SPAWNER) > 0) beginStorage(mc); else complete(mc); }
                return;
            }
            noTargetTicks = 0;
        }
        int pick = hotbar(mc, stack -> silkPickaxe(mc, stack));
        if (pick < 0 || !select(mc, pick)) return;
        if (!hasInventorySpace(mc, new ItemStack(Items.SPAWNER))) {
            if (!mineAttempt && inventory.count(SPAWNER) > 0) beginStorage(mc);
            else fail(mc, "No safe inventory space for a spawner");
            return;
        }
        double reach = Math.min(breakRange.get(), mc.player.blockInteractionRange());
        if (mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)) > reach * reach) {
            stopMining(mc); approach(mc, Vec3.atCenterOf(target), Math.max(1.0, reach - 1.0)); return;
        }
        if (!lookAt(mc, Vec3.atCenterOf(target))) return;
        BlockHitResult hit = hit(mc, target, reach);
        if (hit == null) return;
        sneakHeld = sneakMine.get();
        // Wait for native input to publish sneak before the first dig; do not forge player flags.
        if (sneakHeld && !mc.player.isShiftKeyDown()) return;
        if (!mineAttempt) {
            if (!ActionPacer.tryAction()) return;
            collectionBefore = inventory.count(SPAWNER);
            collectionVersion = inventory.version();
            mineAttempt = true;
            brokenConfirmed = false;
            miningTicks = 0;
            stateSince = tick;
            miningOwned = true;
            owned(() -> mc.gameMode.startDestroyBlock(target, hit.getDirection()));
        } else {
            miningOwned = true;
            owned(() -> mc.gameMode.continueDestroyBlock(target, hit.getDirection()));
            // Donor right-click refresh after a stalled 60-tick mine; real ray and shared pacing.
            if (++miningTicks >= 60 && ActionPacer.tryAction()) {
                owned(() -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit));
                miningTicks = 0;
            }
        }
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private void beginStorage(Minecraft mc) {
        stopMining(mc);
        target = null;
        mineAttempt = false;
        if (storeShulker.get()) transition(State.PREPARE_SHULKER);
        else if (storeEChest.get()) transition(State.PREPARE_ECHEST);
        else complete(mc);
    }
    private void prepareShulker(Minecraft mc) {
        if (expired(mc, ACTION_TIMEOUT, "No empty shulker/placement available")) return;
        if (!worldReady(mc)) return;
        int slot = hotbar(mc, SpawnerProtectModule::emptyShulker);
        if (slot < 0 || !select(mc, slot)) return;
        if (place(mc, slot)) transition(State.WAIT_SHULKER);
    }
    private void prepareEnderChest(Minecraft mc) {
        if (expired(mc, ACTION_TIMEOUT, "No ender chest or safe placement available")) return;
        if (!worldReady(mc)) return;
        if (targetChest == null) targetChest = nearestBlock(mc, Blocks.ENDER_CHEST, 4);
        if (targetChest != null) { openSent = false; transition(State.OPEN_ECHEST); return; }
        if (findInventory(mc, ENDER_CHEST) < 0) {
            if (!buyEChest.get()) { fail(mc, "Need an ender chest; automatic purchase is off"); return; }
            shopStep = 0;
            shopSince = tick;
            shopBefore = inventory.count(ENDER_CHEST);
            shopVersion = inventory.version();
            transition(State.SHOP);
            return;
        }
        int slot = hotbar(mc, ENDER_CHEST);
        if (slot < 0 || !select(mc, slot)) return;
        if (place(mc, slot)) transition(State.WAIT_ECHEST);
    }

    private boolean place(Minecraft mc, int slot) {
        if (mc.player.isShiftKeyDown()) return false; // previous mining sneak must finish natively first
        if (!(mc.player.getInventory().getItem(slot).getItem() instanceof BlockItem item)) return false;
        BlockPos spot = placementSpot(mc);
        if (spot == null) return false;
        Vec3 top = new Vec3(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
        if (!lookAt(mc, top)) return false;
        BlockHitResult hit = hit(mc, spot.below(), mc.player.blockInteractionRange());
        if (hit == null || hit.getDirection() != Direction.UP || !ActionPacer.tryAction()) return false;
        placement = spot;
        placementBlock = item.getBlock();
        placementSlot = slot;
        placementCount = inventory.item(slot).getCount();
        placementVersion = inventory.version();
        placedStack = inventory.item(slot).copyWithCount(1);
        placementConfirmed = false;
        owned(() -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit));
        mc.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }
    private void waitPlacement(Minecraft mc) {
        if (expired(mc, ACTION_TIMEOUT, "Placement was not confirmed by the server")) return;
        ItemStack remaining = inventory.item(placementSlot);
        if (!SpawnerStorageProof.placed(placementConfirmed, inventory.updated(placementSlot, placementVersion),
                remaining.isEmpty() || ItemStack.isSameItemSameComponents(placedStack, remaining),
                placementCount, remaining.getCount())) return;
        if (!mc.level.getBlockState(placement).is(placementBlock)) { fail(mc, "Placed container changed"); return; }
        if (state == State.WAIT_SHULKER) {
            placedShulker = placement;
            shulkerOwned = true;
            transition(State.OPEN_SHULKER);
        } else { targetChest = placement; transition(State.OPEN_ECHEST); }
        placement = null;
        openSent = false;
    }

    private void openStorage(Minecraft mc) {
        if (expired(mc, ACTION_TIMEOUT, "Container open/content timed out")) return;
        if (ownedMenu != null) {
            if (inventory.hasContent(ownedMenu)) {
                int expected = state == State.OPEN_SHULKER || packedStack.isEmpty() ? inventory.count(SPAWNER) : 1;
                if (expected < 1) { fail(mc, "No items remain to confirm in storage"); return; }
                storageProof.begin(expected);
                transition(state == State.OPEN_SHULKER ? State.FILL_SHULKER : State.DEPOSIT_ECHEST);
            }
            return;
        }
        if (!worldReady(mc) || mc.player.isShiftKeyDown()) return;
        BlockPos pos = state == State.OPEN_SHULKER ? placedShulker : targetChest;
        if (pos == null || (state == State.OPEN_SHULKER ? !ownedShulkerPresent(mc) : !mc.level.getBlockState(pos).is(Blocks.ENDER_CHEST))) {
            fail(mc, "Storage block changed or is missing"); return;
        }
        double reach = mc.player.blockInteractionRange();
        if (mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > reach * reach) {
            approach(mc, Vec3.atCenterOf(pos), Math.max(1, reach - 1)); return;
        }
        if (!lookAt(mc, Vec3.atCenterOf(pos))) return;
        BlockHitResult hit = hit(mc, pos, reach);
        if (hit != null && (!openSent || tick - lastUseTick >= 12) && ActionPacer.tryAction()) {
            openSent = true;
            lastUseTick = tick;
            owned(() -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit));
        }
    }

    private void deposit(Minecraft mc) {
        if (expired(mc, 20 * 60, "Storage batch timed out")) return;
        if (ownedMenu == null || !inventory.hasContent(ownedMenu)) { fail(mc, "No server-confirmed storage menu"); return; }
        boolean box = state == State.FILL_SHULKER;
        if (box && (!(ownedMenu instanceof ShulkerBoxMenu) || !ownedShulkerPresent(mc))) { fail(mc, "Shulker ownership lost"); return; }
        if (!box && !(ownedMenu instanceof ChestMenu)) { fail(mc, "Unexpected ender chest menu"); return; }
        Predicate<ItemStack> allowed = box || packedStack.isEmpty() ? SPAWNER : this::packedShulker;
        int source = -1;
        for (int i = 0; i < ownedMenu.slots.size(); i++) {
            var slot = ownedMenu.getSlot(i);
            if (slot.container == mc.player.getInventory() && allowed.test(slot.getItem())
                    && (box || packedStack.isEmpty() || slot.getContainerSlot() == packedSlot)) { source = i; break; }
        }
        if (source >= 0) {
            if (!SpawnerInventoryGuard.hasSpace(mc, ownedMenu, ownedMenu.getSlot(source).getItem())) {
                fail(mc, "Storage is full; remaining items and placed box left intact"); return;
            }
            inventory.deposit(mc, session.token(), ownedMenu, source, tick, allowed, this::sendOwned);
            return;
        }
        if (!storageProof.complete()) { fail(mc, "Items disappeared without a confirmed storage transfer"); return; }
        if (box) {
            List<ItemStack> contents = new ArrayList<>(27);
            for (var slot : ownedMenu.slots) if (slot.container != mc.player.getInventory()) {
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty() && !SPAWNER.test(stack)) { fail(mc, "Unrelated items in shulker; refusing to mine it"); return; }
                contents.add(stack.copy());
            }
            if (contents.stream().allMatch(ItemStack::isEmpty)) { fail(mc, "Empty shulker is not a confirmed storage batch"); return; }
            packedStack = placedStack.copyWithCount(1);
            packedStack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
            closeOwnedMenu(mc);
            if (!storeEChest.get()) { complete(mc); return; }
            packedVersion = inventory.version();
            packedBefore = inventory.count(this::packedShulker);
            brokenConfirmed = false;
            transition(State.BREAK_SHULKER);
        } else {
            if (!packedStack.isEmpty() && inventory.count(this::packedShulker) > packedBefore) {
                fail(mc, "Filled shulker transfer was not confirmed"); return;
            }
            closeOwnedMenu(mc);
            placedShulker = null;
            shulkerOwned = false;
            packedStack = ItemStack.EMPTY;
            packedSlot = -1;
            BlockPos remaining = nearestBlock(mc, Blocks.SPAWNER, 16);
            if (remaining != null || inventory.count(SPAWNER) > 0) {
                target = remaining;
                mineAttempt = false;
                noTargetTicks = 0;
                transition(State.WORKING);
            } else complete(mc);
        }
    }

    private void recoverShulker(Minecraft mc) {
        if (expired(mc, MINE_TIMEOUT, "Couldn't recover filled shulker")) return;
        if (!storageProof.complete()) { fail(mc, "Shulker contents were not confirmed"); return; }
        if (!worldReady(mc)) return;
        if (brokenConfirmed && mc.level.getBlockState(placedShulker).isAir()) {
            stopMining(mc);
            shulkerOwned = false;
            transition(State.COLLECT_SHULKER);
            return;
        }
        if (!ownedShulkerPresent(mc)) {
            // Predicted air is not permission to leave; await an actual block packet or timeout.
            if (shulkerOwned && mc.level.getBlockState(placedShulker).isAir()) { stopMining(mc); return; }
            fail(mc, "Shulker ownership lost; refusing to mine a replacement"); return;
        }
        if (!hasInventorySpace(mc, packedStack)) { fail(mc, "No space to pick up the filled shulker"); return; }
        int pick = hotbar(mc, stack -> stack.is(ItemTags.PICKAXES));
        if (pick < 0 || !select(mc, pick) || !lookAt(mc, Vec3.atCenterOf(placedShulker))) return;
        BlockHitResult hit = hit(mc, placedShulker, mc.player.blockInteractionRange());
        if (hit == null) return;
        if (!miningOwned) {
            if (!ActionPacer.tryAction()) return;
            miningOwned = true;
            owned(() -> mc.gameMode.startDestroyBlock(placedShulker, hit.getDirection()));
        } else owned(() -> mc.gameMode.continueDestroyBlock(placedShulker, hit.getDirection()));
        mc.player.swing(InteractionHand.MAIN_HAND);
    }
    private void collectShulker(Minecraft mc) {
        if (expired(mc, ACTION_TIMEOUT, "Filled shulker pickup not confirmed; no other shulker will be stored")) return;
        if (inventory.count(this::packedShulker) > packedBefore) {
            int slot = inventory.updatedSlot(this::packedShulker, packedVersion);
            if (slot >= 0 && inventory.matchesInventory(mc, slot)) {
                packedSlot = slot;
                targetChest = null;
                transition(State.PREPARE_ECHEST);
                return;
            }
        }
        if (!worldReady(mc)) return;
        ItemEntity dropped = droppedItem(mc, this::packedShulker);
        if (dropped != null) approach(mc, dropped.position(), 0.8);
        else if (placedShulker != null) approach(mc, Vec3.atCenterOf(placedShulker), 0.8);
    }

    private void shop(Minecraft mc) {
        if (!buyEChest.get()) { fail(mc, "Purchase permission removed"); return; }
        if (tick - shopSince > ACTION_TIMEOUT + 30) { fail(mc, "Shop transition timed out; no retry purchase"); return; }
        if (shopStep == 0) {
            if (!worldReady(mc) || tick - shopSince < 30 || !ActionPacer.tryAction()) return;
            shopStep = 1;
            shopSince = tick;
            owned(() -> mc.getConnection().sendCommand("shop"));
            return;
        }
        if (shopStep == 4) {
            if (inventory.version() > shopVersion && inventory.count(ENDER_CHEST) > shopBefore) {
                if (ownedMenu != null) closeOwnedMenu(mc);
                targetChest = null;
                transition(State.PREPARE_ECHEST);
            }
            return;
        }
        if (ownedMenu == null || !inventory.hasContent(ownedMenu) || mc.gui.screen() == null || tick - shopSince < 30) return;
        int slotId = SpawnerProtectionPolicy.shopSlot(shopStep);
        if (slotId < 0 || slotId >= ownedMenu.slots.size()
                || ownedMenu.getSlot(slotId).container == mc.player.getInventory()) { fail(mc, "Unsupported shop layout"); return; }
        ItemStack button = ownedMenu.getSlot(slotId).getItem();
        if (!SpawnerProtectionPolicy.shopButton(shopStep, mc.gui.screen().getTitle().getString(), button.getHoverName().getString(), button.is(Items.ENDER_CHEST))) return;
        if (!ownedMenu.getCarried().isEmpty() || !ActionPacer.tryAction()) return;
        // Server-side menu buttons only. A cursor item immediately aborts; never close/drop it.
        ContainerMutex.notifyContainerAction();
        sendOwned(new ServerboundContainerClickPacket(ownedMenu.containerId, ownedMenu.getStateId(), (short) slotId,
            (byte) 0, ContainerInput.PICKUP, Int2ObjectMaps.emptyMap(), HashedStack.EMPTY));
        shopStep++;
        shopSince = tick;
    }

    private int hotbar(Minecraft mc, Predicate<ItemStack> match) {
        for (int i = 0; i < 9; i++) if (match.test(mc.player.getInventory().getItem(i)) && inventory.matchesInventory(mc, i)) return i;
        int source = -1, empty = -1;
        for (int i = 9; i < 36; i++) if (match.test(mc.player.getInventory().getItem(i)) && inventory.matchesInventory(mc, i)) { source = i; break; }
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getItem(i).isEmpty() && inventory.matchesInventory(mc, i)) { empty = i; break; }
        if (source < 0) { fail(mc, "Required tool/container not available (Silk Touch pickaxe needed for spawners)"); return -1; }
        if (empty < 0) { fail(mc, "Need an empty hotbar slot; other items will not be displaced"); return -1; }
        inventory.swapIntoEmptyHotbar(mc, session.token(), source, empty, tick, this::sendOwned);
        return -1;
    }
    private boolean select(Minecraft mc, int slot) {
        int current = mc.player.getInventory().getSelectedSlot();
        if (!session.slotUnchanged(current)) { fail(mc, "Hotbar ownership changed"); return false; }
        if (current != slot) {
            if (!ActionPacer.tryAction()) return false;
            if (!(mc.gameMode instanceof autismclient.mixin.accessor.AutismMultiPlayerGameModeAccessor)) {
                fail(mc, "Native carried-item synchronization hook unavailable"); return false;
            }
            if (!session.select(current, slot)) { fail(mc, "Hotbar lease rejected"); return false; }
            owned(() -> InvSync.select(mc, slot));
            selectionReadyAt = tick + 2;
            return false;
        }
        return tick >= selectionReadyAt;
    }
    private void restoreSlot(Minecraft mc) {
        if (selectedByUser.getAndSet(false)) { session.releaseSlot(); return; }
        if (mc.player == null || mc.gameMode == null || !mc.isSameThread()) { session.releaseSlot(); return; }
        int slot = session.restoreSlot(mc.level, mc.player, mc.getConnection(), mc.player.getInventory().getSelectedSlot());
        if (slot >= 0 && slot != mc.player.getInventory().getSelectedSlot()
                && mc.gameMode instanceof autismclient.mixin.accessor.AutismMultiPlayerGameModeAccessor) owned(() -> InvSync.select(mc, slot));
    }

    private boolean worldReady(Minecraft mc) {
        if (mc.gui.screen() != null || mc.player.containerMenu != mc.player.inventoryMenu || ContainerMutex.containerBusy(mc)) {
            stopMining(mc); return false;
        }
        return true;
    }
    private boolean lookAt(Minecraft mc, Vec3 point) {
        Vec3 delta = point.subtract(mc.player.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
        float step = rotationSpeed.get().floatValue();
        float nextYaw = mc.player.getYRot() + Mth.clamp(Mth.wrapDegrees(yaw - mc.player.getYRot()), -step, step);
        float nextPitch = mc.player.getXRot() + Mth.clamp(pitch - mc.player.getXRot(), -step, step);
        if (!RotationEngine.write(id(), RotationEngine.PRIORITY_SAFETY, nextYaw, nextPitch)) return false;
        return Math.abs(Mth.wrapDegrees(yaw - mc.player.getYRot())) < 6 && Math.abs(pitch - mc.player.getXRot()) < 6;
    }
    private BlockHitResult hit(Minecraft mc, BlockPos pos, double range) {
        if (pos == null || !mc.player.isWithinBlockInteractionRange(pos, 0)) return null;
        HitResult ray = mc.player.pick(Math.min(range, mc.player.blockInteractionRange()), 1.0f, false);
        return ray instanceof BlockHitResult hit && ray.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos) ? hit : null;
    }
    private void approach(Minecraft mc, Vec3 point, double arrive) {
        if (!worldReady(mc)) return;
        double dx = point.x - mc.player.getX(), dz = point.z - mc.player.getZ();
        double distance = Math.hypot(dx, dz);
        boolean aligned = lookAt(mc, point);
        if (distance <= arrive || !aligned || !mc.player.onGround() || mc.player.horizontalCollision) return;
        BlockPos ahead = BlockPos.containing(mc.player.getX() + dx / distance * 0.7, mc.player.getY(), mc.player.getZ() + dz / distance * 0.7);
        BlockPos floor = ahead.below();
        BlockState ground = mc.level.getBlockState(floor);
        if (ground.getCollisionShape(mc.level, floor).isEmpty() || ground.is(Blocks.MAGMA_BLOCK)
                || mc.level.getBlockState(ahead).is(Blocks.LAVA) || mc.level.getBlockState(ahead).is(Blocks.FIRE)) return;
        forwardHeld = true;
        sneakHeld = sneakMine.get();
    }
    private BlockPos placementSpot(Minecraft mc) {
        BlockPos feet = mc.player.blockPosition();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos spot = feet.relative(direction), below = spot.below();
            if (loadedChunk(mc, spot) && mc.level.getBlockState(spot).canBeReplaced()
                    && mc.level.getBlockState(below).isFaceSturdy(mc.level, below, Direction.UP)
                    && mc.level.getEntities(mc.player, new AABB(spot)).isEmpty()
                    && mc.player.isWithinBlockInteractionRange(below, 0)) return spot;
        }
        return null;
    }
    private BlockPos nearestBlock(Minecraft mc, Block block, int radius) {
        BlockPos origin = mc.player.blockPosition(), best = null;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double bestDistance = Double.MAX_VALUE;
        for (int x = -radius; x <= radius; x++) for (int y = -radius; y <= radius; y++) for (int z = -radius; z <= radius; z++) {
            if (x * x + y * y + z * z > radius * radius) continue;
            pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
            if (!loadedChunk(mc, pos) || !mc.level.getBlockState(pos).is(block)) continue;
            double distance = mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos));
            if (distance < bestDistance) { best = pos.immutable(); bestDistance = distance; }
        }
        return best;
    }
    private static boolean loadedChunk(Minecraft mc, BlockPos pos) {
        return mc.level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4,
            net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) != null;
    }
    private ItemEntity droppedItem(Minecraft mc, Predicate<ItemStack> match) {
        ItemEntity best = null;
        double distance = 256;
        for (Entity entity : mc.level.entitiesForRendering()) if (entity instanceof ItemEntity item && item.isAlive() && match.test(item.getItem())) {
            double candidate = item.distanceToSqr(mc.player);
            if (candidate < distance) { best = item; distance = candidate; }
        }
        return best;
    }
    private boolean silkPickaxe(Minecraft mc, ItemStack stack) {
        if (stack.isEmpty() || !stack.is(ItemTags.PICKAXES)) return false;
        var silk = mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(Enchantments.SILK_TOUCH.identifier());
        return silk.isPresent() && EnchantmentHelper.getItemEnchantmentLevel(silk.get(), stack) > 0;
    }
    private static boolean emptyShulker(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem block && block.getBlock() instanceof ShulkerBoxBlock
            && stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).equals(ItemContainerContents.EMPTY);
    }
    private boolean packedShulker(ItemStack stack) {
        return !packedStack.isEmpty() && !stack.isEmpty() && ItemStack.isSameItemSameComponents(packedStack, stack);
    }
    private boolean ownedShulkerPresent(Minecraft mc) {
        return shulkerOwned && placedShulker != null && placedStack.getItem() instanceof BlockItem block
            && block.getBlock() instanceof ShulkerBoxBlock && mc.level.getBlockState(placedShulker).is(block.getBlock());
    }
    private boolean isSpawner(Minecraft mc, BlockPos pos) { return pos != null && mc.level.getBlockState(pos).is(Blocks.SPAWNER); }
    private int findInventory(Minecraft mc, Predicate<ItemStack> match) {
        for (int i = 0; i < 36; i++) if (match.test(mc.player.getInventory().getItem(i))) return i;
        return -1;
    }
    private boolean hasInventorySpace(Minecraft mc, ItemStack wanted) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty() || (ItemStack.isSameItemSameComponents(stack, wanted) && stack.getCount() < stack.getMaxStackSize())) return true;
        }
        return false;
    }

    private boolean expired(Minecraft mc, int timeout, String reason) {
        if (tick - stateSince < timeout) return false;
        fail(mc, reason); return true;
    }
    private void closeOwnedMenu(Minecraft mc) {
        if (ownedMenu != null && mc.player.containerMenu == ownedMenu && ownedMenu.getCarried().isEmpty()) {
            owned(mc.player::closeContainer);
            ContainerMutex.notifyContainerAction();
        }
        ownedMenu = null;
        openSent = false;
    }
    private void stopMining(Minecraft mc) {
        if (miningOwned && sameSession(mc) && mc.gameMode != null && mc.gameMode.isDestroying()) owned(mc.gameMode::stopDestroyBlock);
        miningOwned = false;
    }
    private void releaseControls(Minecraft mc) {
        forwardHeld = sneakHeld = false;
        stopMining(mc);
        RotationEngine.release(id());
    }
    private void fail(Minecraft mc, String reason) {
        String location = placedShulker == null ? "" : "; shulker location: " + placedShulker.toShortString();
        releaseControls(mc);
        restoreSlot(mc);
        inventory.clear();
        detection.clear();
        storageProof.clear();
        ownedMenu = null; // leave any actual GUI/cursor untouched for the player
        failure = reason;
        transition(State.FAILED);
        notifyLocal(reason + location);
    }
    private void complete(Minecraft mc) {
        releaseControls(mc);
        restoreSlot(mc);
        if (logoutAfter.get()) {
            transition(State.EXIT_WAIT);
            notifyLocal("Server-confirmed work complete; disconnecting in two seconds");
        } else {
            transition(State.COMPLETE);
            notifyLocal("Server-confirmed work complete" + (placedShulker != null && shulkerOwned ? "; filled shulker at " + placedShulker.toShortString() : ""));
        }
    }
    private void finishExit(Minecraft mc) {
        if (tick - stateSince < 40) return;
        if (!inventory.hasTransaction() && mc.player.containerMenu.getCarried().isEmpty() && sameSession(mc)) {
            transition(State.COMPLETE);
            mc.getConnection().getConnection().disconnect(Component.literal("[SpawnerProtect] Confirmed protection completed."));
        }
    }
    private void notifyLocal(String text) {
        autismclient.util.AutismClientMessaging.sendPrefixed("§5[SpawnerProtect] §f" + text);
        ClientNotify.warning("SpawnerProtect: " + text);
    }
    @Override public String info() {
        if (state == State.FAILED) return "Stopped: " + failure;
        return state == State.WAITING ? "Watching" : state.name().toLowerCase(Locale.ROOT).replace('_', ' ') + " / " + collectedCount;
    }
}
