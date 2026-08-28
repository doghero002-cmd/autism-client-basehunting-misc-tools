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
    public void onGameLeft() {
        setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (posting) return; // don't mine while a webhook is in flight

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

        // Face the spawner, hold the pickaxe, and mine it.
        mc.player.getInventory().setSelectedSlot(pickSlot);
        face(mc, target);
        Direction side = facingToward(mc, target);
        mc.gameMode.startDestroyBlock(target, side);
        mc.gameMode.continueDestroyBlock(target, side);
        mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);

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

    /** Rotate the player to look at the centre of the block. */
    private void face(Minecraft mc, BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 centre = Vec3.atCenterOf(pos);
        double dx = centre.x - eye.x;
        double dy = centre.y - eye.y;
        double dz = centre.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horiz)));
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
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
    }

    /** Called when no more spawners are reachable; post the optional webhook notice. */
    private void finish(Minecraft mc) {
        if (minedCount == 0) return;
        int total = minedCount;
        minedCount = 0;
        sendWebhook(mc, "[SpawnerProtect] All your spawners have been collected.",
            "Mined " + total + " spawner(s) before an enemy could reach them.");
    }

    private void onSpawnerMined(Minecraft mc, BlockPos pos) {
        sendWebhook(mc, "[SpawnerProtect] Spawner protected.",
            "Collected a spawner at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ".");
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
