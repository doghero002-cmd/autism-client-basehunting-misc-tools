package com.autism.seedcracker.modules;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Spawner Protect.
 *
 * Clean-room port of the Zelith "SpawnerProtect" module against the AUTISM module API.
 * When an enemy (non-whitelisted) player comes near, the module quickly mines out the nearest
 * monster spawner with a Silk Touch pickaxe so it is collected rather than destroyed or stolen,
 * and can optionally post a notice to a Discord webhook.
 *
 * The original posted a rich Discord embed via java.net.http.HttpClient; this port keeps the
 * async webhook POST but trims the embed to a compact message with the spawner coordinates.
 */
public final class SpawnerProtectModule extends Module {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(8L);
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(HTTP_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    // ---- settings ----
    private final StringSetting whitelist = add(new StringSetting(
            "whitelist", "Whitelist", "")
        .description("Comma-separated player names to ignore (friends).")
        .group("General"));
    private final StringSetting webhook = add(new StringSetting(
            "webhook", "Webhook", "")
        .description("Optional Discord webhook URL; a notice is posted when a spawner is protected.")
        .group("General"));
    private final IntSetting enemyRadius = add(new IntSetting(
            "enemy-radius", "Enemy radius", 32, 4, 64, 1)
        .description("How close an enemy player must be before spawners are protected.")
        .group("General"));
    private final autismclient.api.module.BoolSetting sneakMine = add(new autismclient.api.module.BoolSetting(
            "sneak-mine", "Sneak mine (hold shift)", true)
        .description("Hold shift while mining the spawner (DonutSMP shift-mine drops it as an item into your inventory).")
        .group("Storage"));
    private final autismclient.api.module.BoolSetting storeShulker = add(new autismclient.api.module.BoolSetting(
            "store-shulker", "Store in shulker", true)
        .description("After collecting, put the spawners into a shulker box from your inventory.")
        .group("Storage"));
    private final autismclient.api.module.BoolSetting storeEChest = add(new autismclient.api.module.BoolSetting(
            "store-echest", "Shulker into ender chest", true)
        .description("After filling the shulker, put the shulker box into your ender chest (shift-click while the echest is open).")
        .group("Storage")
        .visibleWhen(() -> storeShulker.get()));

    // ---- state ----
    private BlockPos target;
    private boolean warnedPickaxe;
    private long cooldownUntil;
    private int minedCount;
    private boolean posting;

    public SpawnerProtectModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-spawner-protect", "Spawner Protect", category,
            "Mines out nearby spawners with a Silk Touch pickaxe when an enemy approaches.");
    }

    @Override
    public void onEnable() {
        target = null;
        warnedPickaxe = false;
        cooldownUntil = 0L;
        minedCount = 0;
        posting = false;
        rotInit = false; // re-seed the rotation smoother from the CURRENT view (no snap from stale state)
        resetStorage();
        if (findSilkTouchSlot(Minecraft.getInstance()) == -1) {
            warn("Need a Silk Touch pickaxe in hotbar");
            warnedPickaxe = true;
        }
    }

    @Override
    public void onDisable() {
        stopMining(Minecraft.getInstance());
        target = null;
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (posting) return; // don't mine while a webhook is in flight

        // Storage phase (shulker -> echest) takes priority once spawners are collected.
        if (tickStorage(mc)) return;

        int pickSlot = findSilkTouchSlot(mc);
        if (pickSlot == -1) {
            if (!warnedPickaxe) {
                warn("Need a Silk Touch pickaxe in hotbar");
                warnedPickaxe = true;
            }
            return;
        }
        warnedPickaxe = false;

        if (!enemyNear(mc)) {
            stopMining(mc);
            return;
        }

        if (System.currentTimeMillis() < cooldownUntil) return;

        // Re-acquire a target if the current one is gone or out of reach.
        if (target == null || !isSpawner(mc, target) || !inReach(mc, target)) {
            target = nearestSpawner(mc);
            if (target == null) {
                stopMining(mc);
                finish(mc);
                return;
            }
        }

        // Face the spawner with a smooth eased turn. Only dig once the crosshair is actually on it,
        // using the real ray-reported face (not a computed one) - and let continueDestroyBlock drive
        // progress (no start+continue same tick, which is a breakfrequency flag).
        com.autism.seedcracker.util.InvSync.select(mc, pickSlot);
        face(mc, target);
        // Gate the dig until the camera has converged on the spawner (within a couple degrees).
        if (!facingTarget(mc, target, 6.0f)) {
            if (mc.gameMode != null && mc.gameMode.isDestroying()) mc.gameMode.stopDestroyBlock();
            return; // still turning
        }
        // Sneak to silk-touch it (hold shift) - set once we're digging, not in the same swap tick.
        if (sneakMine.get()) mc.options.keyShift.setDown(true);
        // Dig whatever the real crosshair ray reports (its actual face), if it's the spawner.
        if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bhr
            && mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            BlockPos hitPos = bhr.getBlockPos();
            if (isSpawner(mc, hitPos) && mc.gameMode != null) {
                mc.gameMode.continueDestroyBlock(hitPos, bhr.getDirection());
                mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            }
        }

        if (!isSpawner(mc, target)) {
            minedCount++;
            BlockPos done = target;
            target = null;
            cooldownUntil = System.currentTimeMillis() + 250L;
            onSpawnerMined(mc, done);
        }
    }

    /** True when a non-whitelisted, non-spectator player is within the configured radius. */
    private boolean enemyNear(Minecraft mc) {
        double r = enemyRadius.get();
        for (Player other : mc.level.players()) {
            if (other == mc.player) continue;
            if (other.isSpectator()) continue;
            if (isWhitelisted(other)) continue;
            if (other.distanceTo(mc.player) <= r) return true;
        }
        return false;
    }

    private boolean isWhitelisted(Player player) {
        String list = whitelist.get();
        if (list == null || list.isBlank()) return false;
        String name = player.getGameProfile().name();
        return Arrays.stream(list.split("[,; ]+"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .anyMatch(s -> s.equalsIgnoreCase(name));
    }

    /** Hotbar slot (0-8) holding a Silk Touch pickaxe, or -1. */
    private int findSilkTouchSlot(Minecraft mc) {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (isSilkTouchPickaxe(mc, mc.player.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    private boolean isSilkTouchPickaxe(Minecraft mc, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
            .getKey(stack.getItem()).toString();
        if (!id.endsWith("_pickaxe")) return false;
        if (mc.level == null) return false;
        Optional<Holder.Reference<Enchantment>> holder = mc.level.registryAccess()
            .lookupOrThrow(Registries.ENCHANTMENT)
            .get(Enchantments.SILK_TOUCH.identifier());
        return holder.isPresent() && EnchantmentHelper.getItemEnchantmentLevel(holder.get(), stack) > 0;
    }

    /** Nearest spawner within the player's block interaction range, searching a small cube. */
    private BlockPos nearestSpawner(Minecraft mc) {
        List<BlockPos> found = new ArrayList<>();
        BlockPos origin = mc.player.blockPosition();
        int range = (int) Math.ceil(mc.player.blockInteractionRange()) + 1;
        int maxSq = range * range;
        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -range; dy <= range; dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    if (dx * dx + dy * dy + dz * dz > maxSq) continue;
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (isSpawner(mc, pos) && inReach(mc, pos)) {
                        found.add(pos.immutable());
                    }
                }
            }
        }
        return found.stream()
            .min(Comparator.comparingDouble(p -> mc.player.distanceToSqr(Vec3.atCenterOf(p))))
            .orElse(null);
    }

    private boolean isSpawner(Minecraft mc, BlockPos pos) {
        return mc.level != null && mc.level.getBlockState(pos).is(Blocks.SPAWNER);
    }

    private boolean inReach(Minecraft mc, BlockPos pos) {
        return mc.player.isWithinBlockInteractionRange(pos, mc.player.blockInteractionRange());
    }

    /** Smoothly rotate the player toward the block centre (bounded per-tick step, like a mouse). */
    private float smoothYaw = 0f;
    private float smoothPitch = 0f;
    private boolean rotInit = false;

    private void face(Minecraft mc, BlockPos pos) {
        if (!rotInit) {
            smoothYaw = mc.player.getYRot();
            smoothPitch = mc.player.getXRot();
            rotInit = true;
        }
        Vec3 eye = mc.player.getEyePosition();
        Vec3 centre = Vec3.atCenterOf(pos);
        double dx = centre.x - eye.x;
        double dy = centre.y - eye.y;
        double dz = centre.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, horiz)));
        smoothYaw = com.autism.seedcracker.util.tunnel.LookRotation.approachAngle(smoothYaw, targetYaw, 16.0f);
        smoothPitch = com.autism.seedcracker.util.tunnel.LookRotation.approach(smoothPitch, targetPitch, 12.0f);
        mc.player.setYRot(smoothYaw);
        mc.player.setXRot(net.minecraft.util.Mth.clamp(smoothPitch, -90f, 90f));
    }

    /** True if the camera is within {@code tolDeg} of facing the block's centre (converged). */
    private boolean facingTarget(Minecraft mc, BlockPos pos, float tolDeg) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 centre = Vec3.atCenterOf(pos);
        double dx = centre.x - eye.x, dy = centre.y - eye.y, dz = centre.z - eye.z;
        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        float yawDiff = Math.abs(net.minecraft.util.Mth.wrapDegrees(targetYaw - mc.player.getYRot()));
        float pitchDiff = Math.abs(targetPitch - mc.player.getXRot());
        return yawDiff < tolDeg && pitchDiff < tolDeg;
    }

    private Direction facingToward(Minecraft mc, BlockPos pos) {
        Vec3 delta = mc.player.getEyePosition().subtract(Vec3.atCenterOf(pos));
        double ax = Math.abs(delta.x);
        double ay = Math.abs(delta.y);
        double az = Math.abs(delta.z);
        if (ax >= ay && ax >= az) return delta.x >= 0 ? Direction.EAST : Direction.WEST;
        if (ay >= ax && ay >= az) return delta.y >= 0 ? Direction.UP : Direction.DOWN;
        return delta.z >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private void stopMining(Minecraft mc) {
        if (mc.gameMode != null && mc.gameMode.isDestroying()) {
            mc.gameMode.stopDestroyBlock();
        }
        // Always release sneak (not only when the setting is on - it may have been toggled mid-mine).
        if (mc.options != null) mc.options.keyShift.setDown(false);
    }

    /** Called when no more spawners are reachable; store the haul, then post the webhook notice. */
    private void finish(Minecraft mc) {
        if (minedCount == 0) return;
        int total = minedCount;
        minedCount = 0;
        if (storeShulker.get()) {
            // Kick off the storage phase (place shulker -> fill -> break -> store in echest).
            storageStage = StorageStage.PLACE_SHULKER;
            storageTicks = 0;
            placedShulkerPos = null;
            placedEcPos = null;
        }
        notifyLocal("All spawners collected (" + total + ")");
        sendWebhook(mc, "[SpawnerProtect] All your spawners have been collected.",
            "Mined " + total + " spawner(s) before an enemy could reach them.");
    }

    private void onSpawnerMined(Minecraft mc, BlockPos pos) {
        notifyLocal("Spawner protected at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
        sendWebhook(mc, "[SpawnerProtect] Spawner protected.",
            "Collected a spawner at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ".");
    }

    /** Unified local feedback (chat prefix + toast), matching the other finder modules. */
    private void notifyLocal(String msg) {
        autismclient.util.AutismClientMessaging.sendPrefixed("§5[SpawnerProtect] §f" + msg);
        autismclient.util.AutismNotifications.warning("SpawnerProtect: " + msg);
    }

    // ========================================================================
    // Storage phase: place a shulker in front of the player, shift-click the collected spawners
    // into it, break it (picks it up with contents), then shift-click it into the ender chest.
    // All container moves use QUICK_MOVE (shift-click) so the server sees them.
    // ========================================================================
    private enum StorageStage {
        IDLE, PLACE_SHULKER, OPEN_SHULKER_GUI, FILL_SHULKER, BREAK_SHULKER, PLACE_EC, OPEN_EC_GUI, MOVE_TO_EC, DONE
    }
    private StorageStage storageStage = StorageStage.IDLE;
    private int storageTicks = 0;
    private int storageTimeout = 0;
    private BlockPos placedShulkerPos = null;
    private BlockPos placedEcPos = null;

    /** Drive the storage state machine from tick (runs after spawners are collected). */
    private boolean tickStorage(Minecraft mc) {
        if (storageStage == StorageStage.IDLE || storageStage == StorageStage.DONE) return false;
        if (mc.player == null || mc.gameMode == null) { resetStorage(); return false; }
        // Sneak must be OFF here: sneak+use on a placed shulker places a block against it
        // instead of opening its GUI, which dead-ends the fill stage.
        if (mc.options != null) mc.options.keyShift.setDown(false);
        storageTicks++;
        int wait = 4; // ticks between actions (legit pacing)
        if (storageTicks < wait) return true;
        storageTicks = 0;

        switch (storageStage) {
            case PLACE_SHULKER -> {
                int shulkerSlot = findShulkerSlot(mc);
                BlockPos spot = placementSpot(mc);
                if (shulkerSlot == -1 || spot == null) { resetStorage(); return true; }
                com.autism.seedcracker.util.InvSync.select(mc, shulkerSlot);
                face(mc, spot);
                mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(spot),
                        net.minecraft.core.Direction.UP, spot, false));
                if (mc.level.getBlockState(spot).getBlock().toString().contains("shulker_box")) {
                    placedShulkerPos = spot;
                    storageStage = StorageStage.OPEN_SHULKER_GUI; // must OPEN it before filling
                } else {
                    // Placement failed (spot occupied/blocked): give up cleanly.
                    resetStorage();
                }
            }
            case OPEN_SHULKER_GUI -> {
                if (placedShulkerPos == null) { resetStorage(); return true; }
                // Open the placed shulker; wait for its container to actually appear next ticks.
                face(mc, placedShulkerPos);
                mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(placedShulkerPos),
                        net.minecraft.core.Direction.UP, placedShulkerPos, false));
                storageStage = StorageStage.FILL_SHULKER;
                storageTimeout = 20; // give the open-screen packet up to 20 ticks to arrive
            }
            case FILL_SHULKER -> {
                if (placedShulkerPos == null) { resetStorage(); return true; }
                // Only quick-move once the shulker container is actually open (not the inventory menu).
                boolean guiOpen = mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
                if (!guiOpen) {
                    if (--storageTimeout <= 0) { resetStorage(); }
                    return true; // keep waiting for the shulker menu to open
                }
                boolean moved = moveMatchingToContainer(mc, this::isSpawnerItem);
                if (!moved) {
                    mc.player.closeContainer();
                    storageStage = storeEChest.get() ? StorageStage.BREAK_SHULKER : StorageStage.DONE;
                }
            }
            case BREAK_SHULKER -> {
                if (placedShulkerPos == null) { resetStorage(); return true; }
                // Mine the placed shulker to pick it up (it keeps its contents as an item).
                face(mc, placedShulkerPos);
                net.minecraft.core.Direction side = facingToward(mc, placedShulkerPos);
                mc.gameMode.startDestroyBlock(placedShulkerPos, side);
                mc.gameMode.continueDestroyBlock(placedShulkerPos, side);
                mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                if (mc.level.getBlockState(placedShulkerPos).isAir()) {
                    if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
                    placedShulkerPos = null;
                    storageStage = StorageStage.PLACE_EC;
                }
            }
            case PLACE_EC -> {
                int ecSlot = findItemSlot(mc, net.minecraft.world.item.Items.ENDER_CHEST);
                BlockPos spot = placementSpot(mc);
                if (ecSlot == -1 || spot == null) { resetStorage(); return true; }
                com.autism.seedcracker.util.InvSync.select(mc, ecSlot);
                face(mc, spot);
                mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(spot),
                        net.minecraft.core.Direction.UP, spot, false));
                if (mc.level.getBlockState(spot).is(net.minecraft.world.level.block.Blocks.ENDER_CHEST)) {
                    placedEcPos = spot;
                    storageStage = StorageStage.OPEN_EC_GUI;
                } else {
                    resetStorage();
                }
            }
            case OPEN_EC_GUI -> {
                if (placedEcPos == null) { resetStorage(); return true; }
                face(mc, placedEcPos);
                mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(placedEcPos),
                        net.minecraft.core.Direction.UP, placedEcPos, false));
                storageStage = StorageStage.MOVE_TO_EC;
                storageTimeout = 20;
            }
            case MOVE_TO_EC -> {
                if (placedEcPos == null) { resetStorage(); return true; }
                // Only quick-move once the ender-chest container is actually open.
                boolean guiOpen = mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
                if (!guiOpen) {
                    if (--storageTimeout <= 0) { resetStorage(); }
                    return true;
                }
                moveMatchingToContainer(mc, this::isShulkerBox);
                mc.player.closeContainer();
                storageStage = StorageStage.DONE;
            }
            default -> storageStage = StorageStage.IDLE;
        }
        return true;
    }

    private void resetStorage() {
        storageStage = StorageStage.IDLE;
        placedShulkerPos = null;
        placedEcPos = null;
    }

    /** A free air block in front of the player at foot level to place a block on, or null. */
    private BlockPos placementSpot(Minecraft mc) {
        BlockPos base = mc.player.blockPosition();
        for (net.minecraft.core.Direction dir : new net.minecraft.core.Direction[]{
            mc.player.getDirection(), mc.player.getDirection().getClockWise(), mc.player.getDirection().getCounterClockWise()}) {
            BlockPos p = base.relative(dir);
            if (mc.level.getBlockState(p).isAir() && !mc.level.getBlockState(p.below()).isAir()) return p;
        }
        return null;
    }

    /** Shift-click every matching stack from the player's inventory into the open container. Returns true if any moved. */
    private boolean moveMatchingToContainer(Minecraft mc, java.util.function.Predicate<net.minecraft.world.item.ItemStack> match) {
        var handler = mc.player.containerMenu;
        boolean moved = false;
        for (int i = 0; i < handler.slots.size(); i++) {
            net.minecraft.world.item.ItemStack s = handler.getSlot(i).getItem();
            if (!s.isEmpty() && match.test(s)) {
                com.autism.seedcracker.util.ContainerMutex.notifyContainerAction(); mc.gameMode.handleContainerInput(handler.containerId, i, 0,
                    net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, mc.player);
                moved = true;
            }
        }
        return moved;
    }

    private boolean isSpawnerItem(net.minecraft.world.item.ItemStack s) {
        return s.getItem().toString().toLowerCase(java.util.Locale.ROOT).contains("spawner");
    }

    private boolean isShulkerBox(net.minecraft.world.item.ItemStack s) {
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
        return id.endsWith("shulker_box");
    }

    private int findShulkerSlot(Minecraft mc) {
        for (int i = 0; i < 9; i++) {
            if (isShulkerBox(mc.player.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    private int findItemSlot(Minecraft mc, net.minecraft.world.item.Item item) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(item)) return i;
        }
        return -1;
    }

    /** Post a simple Discord embed to the configured webhook, if one is set. */
    private void sendWebhook(Minecraft mc, String title, String description) {
        String url = webhook.get() == null ? "" : webhook.get().trim();
        if (url.isEmpty() || !isValidWebhook(url)) return;

        String playerName = mc.player == null ? "unknown" : mc.player.getGameProfile().name();
        String json = "{"
            + "\"username\":\"SpawnerProtect\","
            + "\"embeds\":[{"
            + "\"title\":" + quote(title) + ","
            + "\"description\":" + quote(description) + ","
            + "\"color\":5624994,"
            + "\"fields\":[{\"name\":\"Player\",\"value\":" + quote(playerName) + ",\"inline\":true}]"
            + "}]"
            + "}";

        posting = true;
        CompletableFuture.runAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(HTTP_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", "autism-SpawnerProtect")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
                HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (Exception ignored) {
                // Webhook delivery is best-effort; never crash the module over it.
            }
        }).whenComplete((v, err) -> posting = false);
    }

    private boolean isValidWebhook(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String path = uri.getPath();
            return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                && host != null && !host.isBlank()
                && path != null && path.contains("/api/webhooks/");
        } catch (Exception e) {
            return false;
        }
    }

    private static String quote(String s) {
        if (s == null) return "\"-\"";
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private void warn(String message) {
        AutismClientMessaging.sendPrefixed("§c[SpawnerProtect] §f" + message);
    }

    @Override
    public String info() {
        return minedCount > 0 ? minedCount + " protected" : "";
    }
}
