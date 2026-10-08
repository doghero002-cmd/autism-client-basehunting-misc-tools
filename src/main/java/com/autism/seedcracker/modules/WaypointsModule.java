package com.autism.seedcracker.modules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.render.BlockEspRenderer;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/**
 * Waypoints - the three position-marker tools merged into one module so they stop appearing as
 * three separate menu entries:
 *
 *  - Death waypoints : box + chat coords at every spot you die (last few kept).
 *  - Logout spots    : ghost box where each visible player disconnects (repeated spot = base).
 *  - Finder marker Y : pin every finder's chunk markers at a fixed Y instead of camera height.
 *
 * Each is an independent sub-toggle. The module is one on/off entry; a feature only runs when both
 * the module and its toggle are on. (Merge of the old Death Waypoint, Logout Spot and Chunk
 * Waypoints modules.)
 */
public final class WaypointsModule extends Module {

    private static WaypointsModule instance;

    // --- Feature toggles -----------------------------------------------------------------------
    private final BoolSetting deathEnabled = add(new BoolSetting("death", "Death waypoints", true)
        .description("Mark where you die with a box + chat coords (last few deaths kept).")
        .group("Features"));
    private final BoolSetting logoutEnabled = add(new BoolSetting("logout", "Logout spots", false)
        .description("Mark where players disconnect; repeated logout spots = their base.")
        .group("Features"));
    private final BoolSetting markerYEnabled = add(new BoolSetting("marker-y", "Pin finder markers at Y", false)
        .description("Draw all finder chunk markers at a fixed Y level instead of your camera height.")
        .group("Features"));

    // --- Death waypoints -----------------------------------------------------------------------
    private final IntSetting deathKeep = add(new IntSetting("death-keep", "Deaths kept", 3, 1, 10, 1)
        .description("How many recent death spots stay marked.")
        .group("Death waypoints").visibleWhen(deathEnabled::get));
    private final BoolSetting deathChat = add(new BoolSetting("death-chat", "Coords in chat", true)
        .description("Print the death position in chat.")
        .group("Death waypoints").visibleWhen(deathEnabled::get));
    private final ColorSetting deathColor = add(new ColorSetting("death-color", "Death colour", 0xC0FF3030)
        .description("Death marker colour.")
        .group("Death waypoints").visibleWhen(deathEnabled::get));

    // --- Logout spots --------------------------------------------------------------------------
    private final ColorSetting logoutColor = add(new ColorSetting("logout-color", "Logout box colour", 0xA0FF4D4D)
        .description("Ghost box colour.")
        .group("Logout spots").visibleWhen(logoutEnabled::get));
    private final IntSetting logoutMaxAgeMin = add(new IntSetting("logout-max-age", "Max age (min)", 60, 5, 480, 5)
        .description("Forget logout spots older than this.")
        .group("Logout spots").visibleWhen(logoutEnabled::get));
    private final BoolSetting logoutNotify = add(new BoolSetting("logout-notify", "Notification", true)
        .description("Chat ping when a player logs out in range.")
        .group("Logout spots").visibleWhen(logoutEnabled::get));

    // --- Finder marker Y -----------------------------------------------------------------------
    private final IntSetting markerY = add(new IntSetting("marker-y-level", "Marker Y level", 16, -64, 320, 1)
        .description("World Y where all finder chunk markers are drawn when pinning is on.")
        .group("Finder markers").visibleWhen(markerYEnabled::get));

    // --- Death state ---------------------------------------------------------------------------
    private record Death(double x, double y, double z, String dim, long atMs) {}
    private final List<Death> deaths = new ArrayList<>();
    private boolean wasDead = false;

    // --- Logout state --------------------------------------------------------------------------
    private record Spot(String name, UUID uuid, double x, double y, double z, long atMs) {}
    private final Map<Integer, Spot> live = new ConcurrentHashMap<>();
    private final Map<Integer, Spot> scratch = new java.util.HashMap<>();
    private final Map<UUID, Spot> spots = new ConcurrentHashMap<>();

    private static final String DEATH_BOX_ID = SeedcrackerAddon.ID + ":waypoints-death";
    private static final String LOGOUT_BOX_ID = SeedcrackerAddon.ID + ":waypoints-logout";

    public WaypointsModule() {
        super(SeedcrackerAddon.ID + ":waypoints", "Waypoints",
            "Death markers, player logout spots and fixed-Y finder markers - toggle the parts you want.");
        instance = this;
    }

    /** Most recent death in {@code dim}, or null (used by the .goto death command). */
    public static BlockPos lastDeath(String dim) {
        WaypointsModule m = instance;
        if (m == null) return null;
        for (int i = m.deaths.size() - 1; i >= 0; i--) {
            Death d = m.deaths.get(i);
            if (d.dim().equals(dim)) return BlockPos.containing(d.x(), d.y(), d.z());
        }
        return null;
    }

    @Override
    public void onEnable() {
        instance = this;
        if (markerYEnabled.get()) ChunkFlagRenderer.configureDisplayY(true, markerY.get());
    }

    @Override
    public void onDisable() {
        BlockEspRenderer.clearBox(DEATH_BOX_ID);
        BlockEspRenderer.clearBox(LOGOUT_BOX_ID);
        live.clear();
        ChunkFlagRenderer.configureDisplayY(false, 0);
    }

    @Override
    public void onGameLeft() {
        wasDead = false;
        live.clear();
        spots.clear();
        BlockEspRenderer.clearBox(LOGOUT_BOX_ID);
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    protected void onOptionValueChanged(String settingId) {
        if (!isEnabled()) return;
        // Keep the finder marker renderer in sync whenever its toggle or Y changes.
        if (markerYEnabled.get()) ChunkFlagRenderer.configureDisplayY(true, markerY.get());
        else ChunkFlagRenderer.configureDisplayY(false, 0);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        if (markerYEnabled.get()) {
            // Re-assert every tick so nothing can flip it back to camera-follow.
            ChunkFlagRenderer.configureDisplayY(true, markerY.get());
        }

        if (deathEnabled.get()) tickDeath(mc);
        else BlockEspRenderer.clearBox(DEATH_BOX_ID);

        if (logoutEnabled.get()) tickLogout(mc);
        else BlockEspRenderer.clearBox(LOGOUT_BOX_ID);
    }

    private void tickDeath(Minecraft mc) {
        boolean dead = mc.player.isDeadOrDying();
        if (dead && !wasDead) {
            Death d = new Death(mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                mc.level.dimension().identifier().toString(), System.currentTimeMillis());
            deaths.add(d);
            while (deaths.size() > deathKeep.get()) deaths.remove(0);
            if (deathChat.get()) {
                AutismClientMessaging.sendPrefixed("§c[Death] §fYou died at §e"
                    + (int) d.x() + " " + (int) d.y() + " " + (int) d.z() + "§f (" + d.dim() + ")");
            }
        }
        wasDead = dead;

        String dim = mc.level.dimension().identifier().toString();
        List<AABB> boxes = new ArrayList<>();
        for (Death d : deaths) {
            if (!d.dim().equals(dim)) continue;
            boxes.add(new AABB(d.x() - 0.5, d.y(), d.z() - 0.5, d.x() + 0.5, d.y() + 2.0, d.z() + 0.5));
        }
        BlockEspRenderer.feedBoxes(DEATH_BOX_ID, boxes, deathColor.get());
    }

    private void tickLogout(Minecraft mc) {
        if (mc.getConnection() == null) return;

        scratch.clear();
        Map<Integer, Spot> current = scratch;
        for (Player p : mc.level.players()) {
            if (p == mc.player) continue;
            current.put(p.getId(), new Spot(p.getPlainTextName(), p.getUUID(),
                p.getX(), p.getY(), p.getZ(), System.currentTimeMillis()));
        }

        for (Map.Entry<Integer, Spot> e : live.entrySet()) {
            if (current.containsKey(e.getKey())) continue;
            Spot s = e.getValue();
            boolean online = mc.getConnection().getPlayerInfo(s.uuid()) != null;
            if (online) continue;
            spots.put(s.uuid(), s);
            if (logoutNotify.get()) {
                AutismClientMessaging.sendPrefixed("§c[LogoutSpot] §f" + s.name() + " logged out at "
                    + (int) s.x() + " " + (int) s.y() + " " + (int) s.z());
            }
        }
        live.clear();
        live.putAll(current);

        long cutoff = System.currentTimeMillis() - logoutMaxAgeMin.get() * 60_000L;
        spots.values().removeIf(s -> s.atMs() < cutoff
            || mc.getConnection().getPlayerInfo(s.uuid()) != null);

        List<AABB> boxes = new ArrayList<>();
        for (Spot s : spots.values()) {
            boxes.add(new AABB(s.x() - 0.4, s.y(), s.z() - 0.4, s.x() + 0.4, s.y() + 1.9, s.z() + 0.4));
        }
        BlockEspRenderer.feedBoxes(LOGOUT_BOX_ID, boxes, logoutColor.get());
    }

    @Override
    public String info() {
        List<String> parts = new ArrayList<>();
        if (deathEnabled.get() && !deaths.isEmpty()) parts.add(deaths.size() + " deaths");
        if (logoutEnabled.get() && !spots.isEmpty()) parts.add(spots.size() + " spots");
        if (markerYEnabled.get()) parts.add("Y=" + markerY.get());
        return String.join(" ", parts);
    }
}
