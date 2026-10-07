package com.autism.seedcracker.modules;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.render.BlockEspRenderer;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/**
 * Logout Spot.
 *
 * Remembers the exact position where each visible player disconnected and renders a ghost box
 * there until they rejoin (or the timer expires). A player who logs at the same spot repeatedly
 * is standing on their base - camp the box, mine down, profit. Rejoin detection uses the tab
 * list so a spot clears the moment its player comes back anywhere.
 */
public final class LogoutSpotModule extends Module {

    private final ColorSetting color = add(new ColorSetting("color", "Box colour", 0xA0FF4D4D)
        .description("Ghost box colour.").group("Render"));
    private final IntSetting maxAgeMin = add(new IntSetting("max-age", "Max age (min)", 60, 5, 480, 5)
        .description("Forget spots older than this.").group("General"));
    private final BoolSetting notify = add(new BoolSetting("notification", "Notification", true)
        .description("Chat ping when a player logs out in range.").group("General"));

    private record Spot(String name, UUID uuid, double x, double y, double z, long atMs) {}

    /** entity id -> live player identity+position (refreshed per tick while visible). */
    private final Map<Integer, Spot> live = new ConcurrentHashMap<>();
    private final Map<Integer, Spot> scratch = new java.util.HashMap<>();
    /** Logout spots by player UUID (one per player - a relog replaces the old spot). */
    private final Map<UUID, Spot> spots = new ConcurrentHashMap<>();

    public LogoutSpotModule() {
        super(SeedcrackerAddon.ID + ":logout-spot", "Logout Spot",
            "Marks where players disconnect; repeated logout spots = their base.");
    }

    @Override
    public void onDisable() {
        BlockEspRenderer.clearBox(id());
        live.clear();
    }

    @Override
    public void onGameLeft() {
        live.clear();
        spots.clear();
        BlockEspRenderer.clearBox(id());
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;

        // Track visible players (scratch map reused across ticks - this runs 20x/s).
        scratch.clear();
        Map<Integer, Spot> current = scratch;
        for (Player p : mc.level.players()) {
            if (p == mc.player) continue;
            current.put(p.getId(), new Spot(p.getPlainTextName(), p.getUUID(),
                p.getX(), p.getY(), p.getZ(), System.currentTimeMillis()));
        }

        // Entities that vanished: distinguish LOGOUT (gone from tab list) from render-distance
        // exit (still online) - a plain despawn is not a logout spot.
        for (Map.Entry<Integer, Spot> e : live.entrySet()) {
            if (current.containsKey(e.getKey())) continue;
            Spot s = e.getValue();
            boolean online = mc.getConnection().getPlayerInfo(s.uuid()) != null;
            if (online) continue;
            spots.put(s.uuid(), s);
            if (notify.get()) {
                AutismClientMessaging.sendPrefixed("§c[LogoutSpot] §f" + s.name() + " logged out at "
                    + (int) s.x() + " " + (int) s.y() + " " + (int) s.z());
            }
        }
        live.clear();
        live.putAll(current);

        // Expire old spots + clear spots whose player rejoined (they moved; the spot is stale).
        long cutoff = System.currentTimeMillis() - maxAgeMin.get() * 60_000L;
        spots.values().removeIf(s -> s.atMs() < cutoff
            || mc.getConnection().getPlayerInfo(s.uuid()) != null);

        // Render.
        java.util.List<AABB> boxes = new java.util.ArrayList<>();
        for (Spot s : spots.values()) {
            boxes.add(new AABB(s.x() - 0.4, s.y(), s.z() - 0.4, s.x() + 0.4, s.y() + 1.9, s.z() + 0.4));
        }
        BlockEspRenderer.feedBoxes(id(), boxes, color.get());
    }

    @Override
    public String info() {
        return spots.isEmpty() ? "" : spots.size() + " spots";
    }
}
