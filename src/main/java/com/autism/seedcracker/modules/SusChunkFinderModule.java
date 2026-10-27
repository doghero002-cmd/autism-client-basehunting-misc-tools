// SPDX-License-Identifier: GPL-3.0-only
// Native port of Spectre SusChunkFinderModule, d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da.
package com.autism.seedcracker.modules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.finder.ChunkScanHelper;
import com.autism.seedcracker.finder.FinderNotify;
import com.autism.seedcracker.finder.FinderReport;
import com.autism.seedcracker.finder.StoneStackScanner;
import com.autism.seedcracker.render.BlockEspRenderer;
import com.autism.seedcracker.spectre.chunks.ActivityClues;
import com.autism.seedcracker.spectre.chunks.ChunkSignals;
import com.autism.seedcracker.spectre.chunks.SusChunkScan;
import com.autism.seedcracker.spectre.chunks.SuspectObjects;
import com.autism.seedcracker.spectre.chunks.SuspectObjects.Category;
import com.autism.seedcracker.spectre.chunks.SuspectPanel;
import com.autism.seedcracker.util.pure.SusScore;
import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class SusChunkFinderModule extends Module {
    public enum Mode {
        CLASSIC, ANBS_PLUS, ACTIVITY_CLUES;
        @Override public String toString() { return switch (this) { case CLASSIC -> "Classic"; case ANBS_PLUS -> "Dog+"; case ACTIVITY_CLUES -> "Activity clues"; }; }
        public static String migrate(String token, Enum<?>[] choices) {
            return "ANBS+ Scan".equals(token) && choices.length > 0 && choices[0].getDeclaringClass() == Mode.class ? "ANBS_PLUS" : token;
        }
    }
    private static final int MAX_DETAILS = 8192, MAX_MARKERS = 2048;
    private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(SeedcrackerAddon.ID, "sus-observations");
    private final EnumSetting<Mode> mode = add(new EnumSetting<>("mode", "Detection mode", Mode.CLASSIC, Mode.values(), Mode::toString).group("Detection"));
    private final IntSetting threshold = add(new IntSetting("geode-heat", "Heat to flag", 6, 1, 64, 1).group("Detection"));
    private final IntSetting spread = add(new IntSetting("geode-spread", "Simulation distance", 5, 0, 16, 1).group("Detection"));
    private final IntSetting radius = add(new IntSetting("scan-radius", "Scan radius", 12, 2, 32, 1).group("Performance"));
    private final IntSetting budget = add(new IntSetting("chunks-per-tick", "Chunks per tick", 2, 1, 128, 1).group("Performance"));
    private final BoolSetting notify = add(new BoolSetting("notify", "Notification", true).group("Alerts"));
    private final BoolSetting detectMined = add(new BoolSetting("detect-mined-clusters", "Bare formation hypotheses", false)
        .description("Large budding formations without visible crystals; natural generation or hidden blocks can look the same.").group("Detection"));
    private final ColorSetting minedColor = add(new ColorSetting("mined-color", "Bare formation colour", 0x60A64AFF).group("Render").visibleWhen(detectMined::get));
    private final ColorSetting color = add(new ColorSetting("color", "Chunk colour", 0x96FF4444).group("Render"));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Tracer", false).group("Render"));
    private final IntSetting clueThreshold = add(new IntSetting("clue-threshold", "Bee clue points", 2, 1, 8, 1).group("Activity clues"));
    private final BoolSetting naturalContext = add(new BoolSetting("natural-context", "Include short / immature growth", false).group("Activity clues"));
    private final IntSetting kelpLength = add(new IntSetting("kelp-length", "Minimum kelp length", 16, 2, 64, 1).group("Activity clues"));
    private final IntSetting vineLength = add(new IntSetting("vine-length", "Minimum vine length", 16, 2, 64, 1).group("Activity clues"));
    private final IntSetting caveVineLength = add(new IntSetting("cave-vine-length", "Minimum cave vine length", 20, 2, 64, 1).group("Activity clues"));
    private final IntSetting bambooLength = add(new IntSetting("bamboo-length", "Minimum bamboo height", 14, 2, 32, 1).group("Activity clues"));
    private final IntSetting matureClusters = add(new IntSetting("mature-clusters", "Minimum mature crystals", 12, 2, 128, 1).group("Activity clues"));
    private final IntSetting maturePercent = add(new IntSetting("mature-percent", "Minimum mature crystal %", 75, 10, 100, 5).group("Activity clues"));
    private final IntSetting objectRange = add(new IntSetting("object-range", "Object / inspect range", 128, 16, 256, 16).group("Render"));
    private final BoolSetting inspectPanel = add(new BoolSetting("inspect-panel", "Look-to-inspect panel", true).group("Render"));
    private final EnumMap<ActivityClues.Kind, BoolSetting> clueKinds = new EnumMap<>(ActivityClues.Kind.class);
    private final EnumMap<Category, BoolSetting> objectKinds = new EnumMap<>(Category.class);
    private final EnumMap<Category, ColorSetting> objectColors = new EnumMap<>(Category.class);
    private final Map<ChunkPos, SusChunkScan.Sample> samples = new LinkedHashMap<>();
    private final Map<ChunkPos, Integer> scannedAt = new HashMap<>();
    private final LinkedHashSet<ChunkPos> queue = new LinkedHashSet<>();
    private final Set<ChunkPos> flagged = new HashSet<>(), alerted = new HashSet<>();
    private final FinderReport reporter = new FinderReport("Sus", 45);
    private ClientLevel world;
    private SusChunkScan active;
    private ChunkPos activePos;
    private int ticks, storedDetails;
    private boolean hudRegistered, detailLimited;
    private List<SuspectObjects.Marker> markers = List.of();
    private final EnumMap<Category, List<AABB>> boxes = new EnumMap<>(Category.class);

    public SusChunkFinderModule() {
        super(SeedcrackerAddon.ID + ":z-sus-chunk-finder", "Sus Chunk Finder",
            "Spectre object outlines, grouped observations and amethyst/growth clues. Not proof of a base.");
        for (var kind : ActivityClues.Kind.values()) clueKinds.put(kind, add(new BoolSetting("clue-" + kind.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'), kind.label, true).group("Activity clues")));
        for (var category : Category.values()) {
            if (category == Category.BARE_GEODES) { objectKinds.put(category, detectMined); objectColors.put(category, minedColor); continue; }
            objectKinds.put(category, add(new BoolSetting("objects-" + category.id(), category.label, true).group("Objects")));
            objectColors.put(category, add(new ColorSetting("color-" + category.id(), category.label + " colour", category.color).group("Object colours")));
        }
    }

    private void clear(ClientLevel level) {
        samples.clear(); scannedAt.clear(); queue.clear(); flagged.clear(); alerted.clear(); reporter.clear();
        markers = List.of(); boxes.clear(); active = null; activePos = null; storedDetails = 0; ticks = 0; detailLimited = false; world = level;
        ChunkFlagRenderer.clear(id()); ChunkFlagRenderer.clear(id() + ":mined");
        for (var category : Category.values()) BlockEspRenderer.clear(id() + ":" + category.id());
    }
    @Override public void onEnable() {
        clear(Minecraft.getInstance().level); BlockEspRenderer.init(); ChunkFlagRenderer.init();
        if (!hudRegistered) {
            HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, HUD_ID, (g, delta) -> renderHud(g));
            hudRegistered = true;
        }
    }
    @Override public void onDisable() { clear(null); HudElementRegistry.removeElement(HUD_ID); hudRegistered = false; }
    @Override public void onGameLeft() {
        clear(null);
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) { onDisable(); setEnabledSilently(false); }
    }
    @Override protected void onSettingsReset() { clear(isEnabled() ? Minecraft.getInstance().level : null); }
    @Override protected void onOptionValueChanged(String id) {
        if ("mode".equals(id) || "detect-mined-clusters".equals(id) || "object-range".equals(id)
            || id.startsWith("clue-") && !"clue-threshold".equals(id) || id.startsWith("objects-")) clear(isEnabled() ? Minecraft.getInstance().level : null);
    }

    /** Call from ClientPacketListener TAIL hooks on the client thread, never Netty. */
    public void onPacketApplied(ClientLevel level, Packet<?> packet) {
        Minecraft mc = Minecraft.getInstance();
        if (!isEnabled() || !mc.isSameThread() || level == null || level != mc.level) return;
        if (world != level) clear(level);
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) enqueue(new ChunkPos(chunk.getX() + dx, chunk.getZ() + dz));
        } else if (packet instanceof ClientboundLightUpdatePacket light && mode.get() == Mode.ANBS_PLUS) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) enqueue(new ChunkPos(light.getX() + dx, light.getZ() + dz));
        } else if (packet instanceof ClientboundForgetLevelChunkPacket forget) forget(forget.pos());
        else if (packet instanceof ClientboundBlockUpdatePacket block) blockChanged(block.getPos());
        else if (packet instanceof ClientboundSectionBlocksUpdatePacket section) section.runUpdates((pos, state) -> blockChanged(pos));
    }
    private void blockChanged(BlockPos pos) {
        for (int cx = (pos.getX() - 1) >> 4; cx <= (pos.getX() + 1) >> 4; cx++)
            for (int cz = (pos.getZ() - 1) >> 4; cz <= (pos.getZ() + 1) >> 4; cz++) enqueue(new ChunkPos(cx, cz));
    }
    private void enqueue(ChunkPos pos) { if (nearby(pos) && queue.size() < 4225) queue.add(pos); }
    private boolean nearby(ChunkPos pos) {
        var mc = Minecraft.getInstance();
        return world != null && mc.player != null && distance(mc.player.chunkPosition(), pos) <= radius.get()
            && world.getChunkSource().getChunkNow(pos.x(), pos.z()) != null;
    }
    private static int distance(ChunkPos a, ChunkPos b) { return Math.max(Math.abs(a.x() - b.x()), Math.abs(a.z() - b.z())); }
    private void forget(ChunkPos pos) {
        var old = samples.remove(pos); if (old != null) storedDetails -= details(old);
        scannedAt.remove(pos); queue.remove(pos); flagged.remove(pos); alerted.remove(pos);
        if (pos.equals(activePos)) { active = null; activePos = null; }
    }
    private static int details(SusChunkScan.Sample sample) { return sample.objects().size() + sample.lightEdges().size(); }
    private static SusChunkScan.Sample limit(SusChunkScan.Sample s, int capacity) {
        int objects = Math.min(capacity, s.objects().size()), edges = Math.min(capacity - objects, s.lightEdges().size());
        return new SusChunkScan.Sample(s.heat(), s.veto(), s.terrain(), s.complete(), s.activity(),
            List.copyOf(s.objects().subList(0, objects)), List.copyOf(s.lightEdges().subList(0, edges)), s.limited() || objects + edges < details(s));
    }
    private void store(ChunkPos pos, SusChunkScan.Sample sample) {
        var old = samples.remove(pos); if (old != null) storedDetails -= details(old);
        if (storedDetails + details(sample) > MAX_DETAILS) {
            ChunkPos center = Minecraft.getInstance().player.chunkPosition();
            var distant = new ArrayList<>(samples.keySet());
            distant.sort(Comparator.comparingInt((ChunkPos p) -> distance(center, p)).reversed());
            for (ChunkPos other : distant) {
                if (storedDetails + details(sample) <= MAX_DETAILS || distance(center, other) <= distance(center, pos)) break;
                var prior = samples.get(other); storedDetails -= details(prior); samples.put(other, limit(prior, 0));
            }
        }
        sample = limit(sample, Math.max(0, MAX_DETAILS - storedDetails));
        samples.put(pos, sample); storedDetails += details(sample); scannedAt.put(pos, ticks);
    }

    @Override public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (world != mc.level) clear(mc.level);
        if (world == null || mc.player == null) return;
        ticks++;
        if (ticks % 20 == 1) for (ChunkPos pos : List.copyOf(samples.keySet())) if (!nearby(pos)) forget(pos);
        if (ticks % 100 == 1) {
            queue.removeIf(pos -> !nearby(pos));
            var loaded = ChunkScanHelper.loadedChunksAround(mc, radius.get());
            loaded.sort(Comparator.comparingInt(chunk -> distance(mc.player.chunkPosition(), chunk.getPos())));
            for (var chunk : loaded) {
                var sample = samples.get(chunk.getPos());
                if (sample == null || !sample.complete() || ticks - scannedAt.getOrDefault(chunk.getPos(), 0) >= 200) enqueue(chunk.getPos());
            }
        }
        long deadline = System.nanoTime() + 3_000_000L;
        int completed = 0;
        while (completed < budget.get() && System.nanoTime() < deadline) {
            if (active == null) {
                if (queue.isEmpty()) break;
                activePos = queue.removeFirst(); if (!nearby(activePos)) continue;
                var clues = EnumSet.noneOf(ActivityClues.Kind.class); clueKinds.forEach((kind, setting) -> { if (setting.get()) clues.add(kind); });
                var categories = EnumSet.noneOf(Category.class); objectKinds.forEach((kind, setting) -> { if (setting.get()) categories.add(kind); });
                if (detectMined.get()) categories.add(Category.GEODES);
                String brand = mc.getConnection() == null ? null : mc.getConnection().serverBrand();
                active = new SusChunkScan(world, world.getChunkSource().getChunkNow(activePos.x(), activePos.z()),
                    brand != null && brand.contains("DonutFolia"), mode.get() == Mode.ANBS_PLUS, clues, categories, mc.player.getEyePosition(), objectRange.get());
            }
            if (!nearby(activePos)) { active = null; activePos = null; continue; }
            if (!active.step(deadline)) break;
            store(activePos, active.result()); active = null; activePos = null; completed++;
        }
        if (ticks % 20 == 1) rebuild(mc);
        if (ticks % 5 == 1) refreshObjects(mc);
        flagged.removeIf(pos -> !nearby(pos));
        ChunkFlagRenderer.feed(id(), flagged, color.get(), tracer.get()); reporter.tick(mc, flagged);
        for (var category : Category.values()) BlockEspRenderer.feedBoxes(id() + ":" + category.id(), boxes.getOrDefault(category, List.of()), objectColors.get(category).get() | 0xFF000000, 0);
    }

    private void rebuild(Minecraft mc) {
        flagged.clear(); ChunkPos center = mc.player.chunkPosition();
        if (mode.get() == Mode.ACTIVITY_CLUES) {
            samples.forEach((pos, sample) -> { if (nearby(pos) && (sample.activity().score() >= clueThreshold.get() || naturalContext.get() && !sample.activity().empty())) flagged.add(pos); });
        } else if (world.dimensionType().hasSkyLight()) {
            var scores = new HashMap<ChunkPos, Integer>(); var vetoes = new HashMap<ChunkPos, Integer>();
            samples.forEach((pos, sample) -> { if (sample.complete() && nearby(pos)) { scores.put(pos, sample.heat()); if (sample.veto()) vetoes.put(pos, 1); } });
            var heat = StoneStackScanner.spreadHeat(scores, center, radius.get(), spread.get());
            var veto = StoneStackScanner.spreadHeat(vetoes, center, radius.get(), spread.get());
            heat.keySet().removeIf(pos -> veto.containsKey(pos) || !nearby(pos) || scannedNeighbours(pos) < 3);
            int side = radius.get() * 2 + 1, minX = center.x() - radius.get(), minZ = center.z() - radius.get();
            int[] field = new int[side * side];
            heat.forEach((pos, value) -> field[(pos.x() - minX) * side + pos.z() - minZ] = value);
            int[] maximum = ChunkSignals.neighborhoodMax(field, side, spread.get());
            heat.forEach((pos, value) -> {
                var sample = samples.get(pos); boolean terrain = sample == null || sample.terrain();
                if (SusScore.geodePeakEligible(value, terrain, threshold.get(), maximum[(pos.x() - minX) * side + pos.z() - minZ])) flagged.add(pos);
            });
        }
        alerted.removeIf(pos -> !nearby(pos));
        for (ChunkPos pos : flagged) if (alerted.add(pos) && notify.get())
            FinderNotify.flag("[Sus Finder]", mode.get() + " hypothesis at X:" + pos.getMinBlockX() + " Z:" + pos.getMinBlockZ(), false);
    }
    private int scannedNeighbours(ChunkPos pos) {
        int count = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            var other = new ChunkPos(pos.x() + dx, pos.z() + dz); var sample = samples.get(other);
            if (sample != null && sample.complete() && nearby(other)) count++;
        }
        return count;
    }

    private void refreshObjects(Minecraft mc) {
        Vec3 eye = mc.player.getEyePosition(); double rangeSq = (double) objectRange.get() * objectRange.get();
        var candidates = new ArrayList<SuspectObjects.Found>(); var geodes = new HashMap<BlockPos, SuspectObjects.Found>(); var plants = new HashMap<BlockPos, SuspectObjects.Found>();
        detailLimited = false;
        for (var entry : samples.entrySet()) {
            if (!nearby(entry.getKey())) continue;
            var sample = entry.getValue(); detailLimited |= sample.limited();
            for (var found : sample.objects()) {
                if (Vec3.atCenterOf(found.pos()).distanceToSqr(eye) > rangeSq
                    || !objectKinds.get(found.category()).get() && !(found.category() == Category.GEODES && detectMined.get())) continue;
                if (found.category() == Category.GEODES) { geodes.put(found.pos(), found); continue; }
                if (found.category() == Category.GROWTH) {
                    var kind = ActivityClues.kind(found.state());
                    if (kind == ActivityClues.Kind.KELP || kind == ActivityClues.Kind.VINES || kind == ActivityClues.Kind.CAVE_VINES || kind == ActivityClues.Kind.BAMBOO) { plants.put(found.pos(), found); continue; }
                    var clue = new ActivityClues.Summary(); clue.add(found.state());
                    if (!naturalContext.get() && (clue.score() == 0 || sample.activity().score() < clueThreshold.get())) continue;
                }
                candidates.add(found);
            }
        }
        candidates.sort(Comparator.comparingDouble(found -> Vec3.atCenterOf(found.pos()).distanceToSqr(eye)));
        var next = new ArrayList<SuspectObjects.Marker>();
        for (int i = 0; i < Math.min(MAX_MARKERS, candidates.size()); i++) next.add(SuspectObjects.block(candidates.get(i), world));
        detailLimited |= candidates.size() > MAX_MARKERS;
        for (var formation : SuspectObjects.geodes(geodes, matureClusters.get(), maturePercent.get(), naturalContext.get(), detectMined.get() && !detailLimited))
            if (objectKinds.get(formation.category()).get()) next.add(formation);
        next.addAll(SuspectObjects.plants(plants, kind -> switch (kind) { case KELP -> kelpLength.get(); case VINES -> vineLength.get(); case CAVE_VINES -> caveVineLength.get(); case BAMBOO -> bambooLength.get(); default -> Integer.MAX_VALUE; }, naturalContext.get()));
        if (objectKinds.get(Category.DISPLAYS).get()) {
            int examined = 0;
            for (var entity : world.entitiesForRendering()) {
                if (++examined > 512) { detailLimited = true; break; }
                var item = entity instanceof net.minecraft.world.entity.decoration.ArmorStand ? Items.ARMOR_STAND : entity instanceof net.minecraft.world.entity.decoration.ItemFrame ? Items.ITEM_FRAME : null;
                if (item != null && entity.isAlive() && entity.distanceToSqr(eye) <= rangeSq) {
                    var box = entity.getBoundingBox(); var icon = SuspectObjects.Icon.item(item);
                    next.add(new SuspectObjects.Marker(box, List.of(box), Category.DISPLAYS, Map.of(icon, 1), icon.label()));
                }
            }
        }
        if (mode.get() == Mode.ANBS_PLUS && objectKinds.get(Category.ANOMALIES).get()) for (var entry : samples.entrySet()) {
            if (!flagged.contains(entry.getKey())) continue;
            for (BlockPos pos : entry.getValue().lightEdges()) if (Vec3.atCenterOf(pos).distanceToSqr(eye) <= rangeSq) {
                var box = new AABB(pos); next.add(new SuspectObjects.Marker(box, List.of(box), Category.ANOMALIES, Map.of(SuspectObjects.Icon.item(Items.AMETHYST_SHARD), 1), "Observed light edge"));
            }
        }
        next.sort(Comparator.comparingDouble(marker -> marker.bounds().distanceToSqr(eye)));
        if (next.size() > MAX_MARKERS) { detailLimited = true; next.subList(MAX_MARKERS, next.size()).clear(); }
        markers = List.copyOf(next); boxes.clear();
        for (var marker : markers) boxes.computeIfAbsent(marker.category(), ignored -> new ArrayList<>()).addAll(marker.shapes());
    }

    private void renderHud(GuiGraphicsExtractor g) {
        var mc = Minecraft.getInstance();
        if (!isEnabled() || world == null || world != mc.level || mc.player == null || mc.gui.screen() != null || mc.gui.hud.isHidden() || !inspectPanel.get()) return;
        var camera = mc.gameRenderer.mainCamera(); var direction = camera.forwardVector();
        var focus = SuspectObjects.focus(markers, camera.position(), new Vec3(direction.x(), direction.y(), direction.z()), objectRange.get());
        if (focus != null) SuspectPanel.draw(g, focus, category -> objectColors.get(category).get(), detailLimited);
    }
    @Override public String info() { return mode.get() + " / " + flagged.size() + " clues / " + markers.size() + " objects"; }
}
