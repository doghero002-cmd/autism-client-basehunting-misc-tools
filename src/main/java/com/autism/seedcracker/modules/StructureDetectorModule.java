package com.autism.seedcracker.modules;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.render.BlockEspRenderer;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.world.phys.AABB;

/**
 * Structure Detector.
 *
 * Locates natural structures via the server's {@code /locate structure} command so you can tell a
 * natural structure (village, temple, dungeon, trial chamber, bastion, fortress, etc.) apart from
 * a player base. On enable it queries the chosen structure type, parses the coordinates from the
 * server reply, and marks the spot in-world + reports the region to the heatmap as a NATURAL
 * structure (so the base-finders can skip it instead of false-flagging).
 *
 * Client-side structure starts aren't exposed on 26.2, so this uses /locate (the only reliable
 * client route) - one query per toggle-on, no packet spam.
 */
public final class StructureDetectorModule extends Module {

    public enum StructureType {
        VILLAGE("village"), PILLAGER_OUTPOST("pillager_outpost"), DESERT_PYRAMID("desert_pyramid"),
        JUNGLE_TEMPLE("jungle_pyramid"), IGLOO("igloo"), SWAMP_HUT("swamp_hut"),
        OCEAN_MONUMENT("monument"), SHIPWRECK("shipwreck"), RUINED_PORTAL("ruined_portal"),
        DUNGEON("dungeon"), TRIAL_CHAMBERS("trial_chambers"), STRONGHOLD("stronghold"),
        MINESHAFT("mineshaft"), ANCIENT_CITY("ancient_city"), WOODLAND_MANSION("mansion"),
        NETHER_FORTRESS("fortress"), BASTION("bastion_remnant"), END_CITY("end_city");

        public final String id;
        StructureType(String id) { this.id = id; }
    }

    private final EnumSetting<StructureType> structure = add(new EnumSetting<>(
            "structure", "Structure", StructureType.TRIAL_CHAMBERS, StructureType.values())
        .description("Which structure type to locate.").group("General"));
    private final BoolSetting autoScan = add(new BoolSetting("auto-scan", "Scan on enable", true)
        .description("Run a /locate query as soon as the module is enabled.").group("General"));
    private final IntSetting rescanTicks = add(new IntSetting("rescan", "Re-scan (ticks)", 200, 20, 2400, 20)
        .description("How often to re-locate the structure (0 = only once).").group("General"));
    private final BoolSetting markInWorld = add(new BoolSetting("mark-in-world", "Mark in world", true)
        .description("Draw an ESP box around the located structure.").group("Render"));
    private final ColorSetting markColor = add(new ColorSetting("mark-colour", "Mark colour", 0x8050C8FF).group("Render"));
    private final BoolSetting notify = add(new BoolSetting("notify", "Notify", true)
        .description("Chat the located coordinates.").group("General"));
    private final BoolSetting heatFilter = add(new BoolSetting("heat-filter", "Mark as natural (heatmap)", true)
        .description("Report the located structure to the base-heatmap as NATURAL so base-finders skip it.")
        .group("General"));

    private int cooldown = 0;
    private BlockPos located = null;
    private String locatedName = null;

    // Parses "The nearest minecraft:trial_chambers is at [123, ~, -456]" / "... is at 123 ~ -456".
    private static final Pattern COORDS = Pattern.compile("\\[?(-?\\d+)\\s*[,\\s]+~?\\s*[,\\s]+(-?\\d+)\\]?");
    private static final Pattern COORDS3 = Pattern.compile("\\[?(-?\\d+)\\s*[,\\s]+(-?\\d+)\\s*[,\\s]+(-?\\d+)\\]?");

    public StructureDetectorModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":structure-detector", "Structure Detector", category,
            "Locates natural structures via /locate so they aren't mistaken for player bases.");
    }

    @Override
    public void onEnable() {
        cooldown = autoScan.get() ? 0 : -1;
        located = null;
        locatedName = null;
    }

    @Override
    public void onDisable() {
        BlockEspRenderer.clearBox(id() + ":s");
        located = null;
        locatedName = null;
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;

        // Draw the located marker.
        if (markInWorld.get() && located != null) {
            List<AABB> boxes = new ArrayList<>();
            boxes.add(new AABB(located).inflate(2.0, 4.0, 2.0));
            BlockEspRenderer.feedBoxes(id() + ":s", boxes, markColor.get());
        }

        if (cooldown < 0) return; // no auto rescan
        if (cooldown > 0) { cooldown--; return; }
        mc.getConnection().sendCommand("locate structure minecraft:" + structure.get().id);
        cooldown = rescanTicks.get() <= 0 ? -1 : rescanTicks.get();
    }

    @Override
    public boolean onPacketReceive(Packet<?> packet) {
        if (!(packet instanceof ClientboundSystemChatPacket chat)) return false;
        var content = chat.content();
        if (content == null) return false;
        String raw = content.getString();
        if (raw == null || !raw.toLowerCase(java.util.Locale.ROOT).contains("nearest")) return false;
        if (!raw.toLowerCase(java.util.Locale.ROOT).contains(structure.get().id.replace('_', ' '))
            && !raw.contains(structure.get().id) && !raw.contains("minecraft:")) {
            // Accept any locate reply while a query is pending.
        }
        BlockPos found = parseCoords(raw);
        if (found == null) return false;

        Minecraft mc = Minecraft.getInstance();
        located = found;
        locatedName = structure.get().id;
        if (notify.get()) {
            AutismClientMessaging.sendPrefixed("§b[Structure] §fNearest §d" + structure.get().id
                + "§f at §e" + found.getX() + " " + found.getY() + " " + found.getZ());
        }
        if (heatFilter.get()) {
            // Negative heat = natural structure: the base-finders / heatmap skip this region.
            com.autism.seedcracker.finder.BaseHeatTracker.recordNatural(found.getX(), found.getZ(),
                RegionMapModule.cellBlocks());
        }
        return false; // never block the message
    }

    /** Parse X/Z (and optional Y) out of a /locate reply. */
    private static BlockPos parseCoords(String raw) {
        Matcher m3 = COORDS3.matcher(raw);
        if (m3.find()) {
            try {
                int x = Integer.parseInt(m3.group(1));
                int z = Integer.parseInt(m3.group(3));
                return new BlockPos(x, 64, z);
            } catch (NumberFormatException ignored) {}
        }
        Matcher m = COORDS.matcher(raw);
        if (m.find()) {
            try {
                int x = Integer.parseInt(m.group(1));
                int z = Integer.parseInt(m.group(2));
                return new BlockPos(x, 64, z);
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    @Override public String info() {
        if (located == null) return "none found";
        return locatedName + " @ " + located.getX() + "," + located.getZ();
    }
}
