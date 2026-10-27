// SPDX-License-Identifier: GPL-3.0-only
package com.autism.seedcracker.modules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.compat.ClientNotify;
import com.autism.seedcracker.netherite.HiddenDebrisScanner;
import com.autism.seedcracker.render.BlockEspRenderer;
import com.autism.seedcracker.spectre.palette.DebrisProbe;
import com.autism.seedcracker.spectre.palette.PaletteOverlay;
import com.autism.seedcracker.util.RelogPersistence;
import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Native port of Spectre NetheriteFinder/AdvancedFinder/NetheriteVisuals (d5a7dd3de0be). */
public final class NetheriteFinderModule extends Module {
    private static final String ID = SeedcrackerAddon.ID + ":netherite-finder";
    private enum ProbeStyle { DOTS, CUBES, PULSES }
    private final IntSetting range = add(new IntSetting("range", "Range (chunks)", 32, 4, 64, 1).group("General"));
    private final BoolSetting notify = add(new BoolSetting("notify", "Notifications", false).group("General"));
    private final ColorSetting color = add(new ColorSetting("color", "Near color", -34304).group("Render"));
    private final ColorSetting farColor = add(new ColorSetting("far-color", "Far color", -11688961).group("Render"));
    private final IntSetting fade = add(new IntSetting("fade-distance", "Color fade distance", 192, 32, 512, 8).group("Render"));
    private final BoolSetting shellFill = add(new BoolSetting("shell-fill", "Section fill", true).group("Render"));
    private final IntSetting opacity = add(new IntSetting("opacity", "Fill opacity (%)", 7, 0, 100, 1).group("Render"));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Chunk tracers", true).group("Render"));
    private final IntSetting tracerCount = add(new IntSetting("tracer-count", "Tracer count", 3, 1, 8, 1).group("Render").visibleWhen(tracer::get));
    private final BoolSetting advanced = add(new BoolSetting("advanced-finder", "Advanced finder (probe)", false)
        .description("Opt-in coarse-first START/ABORT probing with a non-tool slot. Sends mining actions; never continues a break.").group("Probe"));
    private final BoolSetting showProbes = add(new BoolSetting("show-probes", "Show probes", true).group("Probe").visibleWhen(advanced::get));
    private final EnumSetting<ProbeStyle> probeStyle = add(new EnumSetting<>("probe-style", "Probe style", ProbeStyle.DOTS, ProbeStyle.values())
        .group("Probe").visibleWhen(() -> advanced.get() && showProbes.get()));
    private final IntSetting missingFrom = add(new IntSetting("missing-from", "Mark missing from (%)", 60, 30, 100, 5)
        .group("Probe").visibleWhen(() -> advanced.get() && showProbes.get()));
    private final Set<DebrisProbe.Section> candidates = new LinkedHashSet<>();
    private final DebrisProbe probes = new DebrisProbe();
    private Set<String> renderIds = Set.of();
    private ClientLevel level;
    private boolean unloadHook;
    private long lastNotify;
    private int pending;
    private BlockPos nearestPending;

    public NetheriteFinderModule() {
        super(ID, "Netherite Finder", "Spectre palette leaks, exact debris reveals and optional coarse-first probing.");
    }

    @Override public void onEnable() {
        reset();
        BlockEspRenderer.init();
        PaletteOverlay.init();
        if (!unloadHook) {
            unloadHook = true;
            ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
                if (world != level) return;
                candidates.removeIf(s -> s.chunkX() == chunk.getPos().x() && s.chunkZ() == chunk.getPos().z());
                for (BlockPos pos : probes.revealed()) {
                    if ((pos.getX() >> 4) == chunk.getPos().x() && (pos.getZ() >> 4) == chunk.getPos().z()) probes.removeReveal(pos);
                }
                clearRender();
            });
        }
    }
    @Override public void onDisable() { reset(); }
    @Override public void onGameLeft() {
        reset();
        if (RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    private void reset() {
        candidates.clear();
        probes.reset();
        pending = 0;
        nearestPending = null;
        lastNotify = 0;
        level = null;
        clearRender();
    }
    private void clearRender() {
        for (String id : renderIds) BlockEspRenderer.clear(id);
        renderIds = Set.of();
        PaletteOverlay.clear(ID);
    }
    private void sync(Minecraft mc) {
        if (mc.level != level) {
            reset();
            level = mc.level;
            probes.maintain(level, candidates);
        }
    }

    /** Takes ownership of the fresh packet getReadBuffer() wrapper, including all early-return paths. */
    public void onChunkData(FriendlyByteBuf buffer, int chunkX, int chunkZ, int sectionCount, int minSectionY) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (!mc.isSameThread()) throw new IllegalStateException("Palette packets must be applied on the client thread");
            if (!isEnabled()) return;
            sync(mc);
            if (!isNether(level) || !com.autism.seedcracker.spectre.palette.PaletteScan.validChunk(chunkX, chunkZ)
                || !level.getChunkSource().hasChunk(chunkX, chunkZ)) return;
            List<Integer> hidden = HiddenDebrisScanner.findHiddenSections(buffer, sectionCount, minSectionY, HiddenDebrisScanner.ancientStateId());
            Set<DebrisProbe.Section> previous = new HashSet<>(candidates);
            candidates.removeIf(s -> s.chunkX() == chunkX && s.chunkZ() == chunkZ);
            for (int y : hidden) {
                DebrisProbe.Section section = new DebrisProbe.Section(chunkX, chunkZ, y);
                candidates.add(section);
                if (candidates.size() > 512) candidates.remove(candidates.iterator().next());
                if (!previous.contains(section) && notify.get()) {
                    pending++;
                    BlockPos pos = new BlockPos(chunkX * 16 + 8, y * 16 + 8, chunkZ * 16 + 8);
                    if (nearestPending == null || (mc.player != null && pos.distToCenterSqr(mc.player.position()) < nearestPending.distToCenterSqr(mc.player.position()))) nearestPending = pos;
                }
            }
        } finally {
            buffer.release();
        }
    }

    public void onBlockUpdate(BlockPos pos, BlockState state) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) throw new IllegalStateException("Block updates must be applied on the client thread");
        if (!isEnabled()) return;
        sync(mc);
        if (!isNether(level)) return;
        if (state.is(Blocks.ANCIENT_DEBRIS)) {
            boolean fresh = probes.reveal(pos);
            candidates.remove(DebrisProbe.Section.at(pos));
            if (fresh && notify.get()) ClientNotify.success("[NetheriteFinder] Ancient debris at " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
        } else probes.removeReveal(pos);
    }

    private static boolean isNether(ClientLevel level) {
        return level != null && (level.dimension().equals(Level.NETHER)
            || level.dimensionTypeRegistration().is(BuiltinDimensionTypes.NETHER)
            || level.dimension().equals(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.Identifier.fromNamespaceAndPath("worlds", "smp_nether"))));
    }

    @Override public void tick() {
        Minecraft mc = Minecraft.getInstance();
        sync(mc);
        if (mc.player == null || !isNether(level)) { clearRender(); return; }
        candidates.removeIf(s -> !level.getChunkSource().hasChunk(s.chunkX(), s.chunkZ()));
        probes.maintain(level, candidates);
        probes.tick(mc, candidates, advanced.get());
        long now = System.currentTimeMillis();
        if (!notify.get()) { pending = 0; nearestPending = null; }
        if (pending > 0 && now - lastNotify >= 2000) {
            ClientNotify.warning("[NetheriteFinder] " + pending + " hidden-debris sections; nearest "
                + nearestPending.getX() + " " + nearestPending.getY() + " " + nearestPending.getZ());
            pending = 0;
            nearestPending = null;
            lastNotify = now;
        }
        render(mc);
    }

    private void render(Minecraft mc) {
        Vec3 eye = mc.player.getEyePosition();
        double max = range.get() * 16.0;
        List<DebrisProbe.Section> shown = candidates.stream().filter(s -> !probes.done(s))
            .filter(s -> DebrisProbe.distanceSq(s.box(), eye) <= max * max)
            .sorted(Comparator.comparingDouble(s -> DebrisProbe.distanceSq(s.box(), eye))).limit(128).toList();
        Map<Integer, List<AABB>> boxes = new HashMap<>();
        List<PaletteOverlay.Line> lines = new ArrayList<>();
        List<PaletteOverlay.Label> labels = new ArrayList<>();
        Set<String> nextIds = new HashSet<>();
        int traced = 0;
        for (var section : shown) {
            double distance = Math.sqrt(DebrisProbe.distanceSq(section.box(), eye));
            int bucket = Math.min(15, (int) (distance / fade.get() * 15));
            boxes.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(section.box());
            float progress = probes.progress(section);
            if (progress >= 0) {
                AABB b = section.box();
                double y = b.minY + 16 * progress;
                feed(nextIds, ID + ":progress:" + section, List.of(new AABB(b.minX, y, b.minZ, b.maxX, y + 0.03, b.maxZ)), color.get(), 36);
            }
            if (tracer.get() && distance >= 1 && traced < tracerCount.get()) {
                int tint = PaletteOverlay.mix(color.get(), farColor.get(), distance / fade.get());
                lines.add(new PaletteOverlay.Line(null, section.box().getCenter(), tint, 1.5f));
                labels.add(new PaletteOverlay.Label(section.box().getCenter(), null, Math.round(distance) + "m",
                    progress < 0 ? "Hidden-debris section" : Math.round(progress * 100) + "% probed", tint, 1));
                traced++;
            }
        }
        for (var group : boxes.entrySet()) feed(nextIds, ID + ":section:" + group.getKey(), group.getValue(),
            PaletteOverlay.mix(color.get(), farColor.get(), group.getKey() / 15.0), shellFill.get() ? Math.round(opacity.get() * 2.55f) : 0);
        List<BlockPos> revealed = probes.revealed().stream().filter(p -> p.distToCenterSqr(eye) <= max * max)
            .sorted(Comparator.comparingDouble(p -> p.distToCenterSqr(eye))).toList();
        feed(nextIds, ID + ":exact", revealed.stream().map(AABB::new).toList(), color.get(), 100);
        for (int i = 0; i < Math.min(8, revealed.size()); i++) {
            BlockPos pos = revealed.get(i);
            double distance = Math.sqrt(pos.distToCenterSqr(eye));
            if (tracer.get() && i < 3) lines.add(new PaletteOverlay.Line(null, Vec3.atCenterOf(pos), color.get(), 1.5f));
            labels.add(new PaletteOverlay.Label(Vec3.atCenterOf(pos).add(0, 0.9, 0), pos, "Ancient debris", Math.round(distance) + "m", color.get(), 1));
        }
        if (advanced.get() && showProbes.get()) {
            List<AABB> sent = new ArrayList<>(), missing = new ArrayList<>();
            for (var mark : probes.marks(eye, missingFrom.get() / 100f, probeStyle.get() == ProbeStyle.CUBES ? 450 : 1800)) {
                double age = Math.min(1, mark.age() / 14.0);
                double half = mark.missing() ? 0.08 : switch (probeStyle.get()) {
                    case DOTS -> 0.065 - 0.03 * age;
                    case CUBES -> 0.2 - 0.1 * age;
                    case PULSES -> 0.08 + 0.25 * age;
                };
                Vec3 p = Vec3.atCenterOf(mark.pos());
                (mark.missing() ? missing : sent).add(new AABB(p.x - half, p.y - half, p.z - half, p.x + half, p.y + half, p.z + half));
            }
            feed(nextIds, ID + ":probes", sent, color.get(), 110);
            feed(nextIds, ID + ":missing", missing, 0xffeeeeff, 100);
        }
        for (String id : renderIds) if (!nextIds.contains(id)) BlockEspRenderer.clear(id);
        renderIds = nextIds;
        PaletteOverlay.feed(ID, level, lines, labels);
    }

    private static void feed(Set<String> ids, String id, List<AABB> boxes, int color, int alpha) {
        if (boxes.isEmpty()) return;
        ids.add(id);
        BlockEspRenderer.feedBoxes(id, boxes, color, alpha);
    }

    @Override public String info() { return candidates.isEmpty() ? "watching" : candidates.size() + " sections"; }
}
