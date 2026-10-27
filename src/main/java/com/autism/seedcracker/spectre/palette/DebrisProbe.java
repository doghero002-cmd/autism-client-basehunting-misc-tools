// SPDX-License-Identifier: GPL-3.0-only
package com.autism.seedcracker.spectre.palette;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.autism.seedcracker.compat.ClientNotify;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Spectre AdvancedFinder's coarse-first, budgeted START/ABORT sweep, with native prediction. */
public final class DebrisProbe {
    public record Section(int chunkX, int chunkZ, int sectionY) {
        public AABB box() { return new AABB(chunkX * 16.0, sectionY * 16.0, chunkZ * 16.0, chunkX * 16.0 + 16, sectionY * 16.0 + 16, chunkZ * 16.0 + 16); }
        public static Section at(BlockPos pos) { return new Section(pos.getX() >> 4, pos.getZ() >> 4, pos.getY() >> 4); }
    }
    public record Mark(BlockPos pos, int age, boolean missing) {}
    private record Probe(Scan scan, int index, BlockPos pos, double distance) {}
    private static final class Scan {
        final Section section;
        final BitSet probed = new BitSet(4096);
        final int[] stamp = new int[4096];
        final int total;
        int sent;
        boolean dirty;
        Scan(Section section, int total) { this.section = section; this.total = total; }
        float fraction() { return total == 0 ? 1 : Math.min(1, (float) sent / total); }
    }
    private final Map<Section, Scan> scans = new HashMap<>();
    private final Set<Section> done = new HashSet<>();
    private final Set<BlockPos> revealed = new LinkedHashSet<>();
    private ClientLevel level;
    private boolean armed;
    private int ticks;
    private int warningTicks;
    private double budget;

    public void reset() {
        scans.clear();
        done.clear();
        revealed.clear();
        level = null;
        armed = false;
        ticks = 0;
        budget = 0;
        warningTicks = 0;
    }

    public void maintain(ClientLevel current, Set<Section> candidates) {
        if (current != level) {
            reset();
            level = current;
        }
        scans.keySet().retainAll(candidates);
        done.retainAll(candidates);
        if (current != null) revealed.removeIf(pos -> !SpawnerScan.loaded(current, pos) || !current.getBlockState(pos).is(Blocks.ANCIENT_DEBRIS));
    }

    public Collection<BlockPos> revealed() { return List.copyOf(revealed); }
    public boolean done(Section section) { return done.contains(section); }
    public float progress(Section section) {
        Scan scan = armed ? scans.get(section) : null;
        return scan == null ? -1 : scan.fraction();
    }
    public boolean reveal(BlockPos pos) {
        if (!revealed.add(pos.immutable())) return false;
        if (revealed.size() > 256) revealed.remove(revealed.iterator().next());
        scans.remove(Section.at(pos));
        return true;
    }
    public void removeReveal(BlockPos pos) { revealed.remove(pos); }

    public void tick(Minecraft mc, Set<Section> candidates, boolean enabled) {
        armed = enabled;
        if (!enabled) { budget = 0; return; }
        if (mc.level == null || mc.player == null || mc.gameMode == null) return;
        ticks++;
        budget = Math.min(600, budget + 18);
        if (warningTicks > 0) warningTicks--;
        if (mc.gui.screen() != null || mc.player.isUsingItem() || mc.gameMode.isDestroying()
            || mc.gameMode.isSpectator() || mc.player.getAbilities().instabuild) return;
        Vec3 eye = mc.player.getEyePosition();
        double reach = Math.min(5, mc.player.blockInteractionRange());
        for (Section section : candidates) {
            if (!done.contains(section) && !scans.containsKey(section) && distanceSq(section.box(), eye) <= reach * reach) {
                scans.put(section, new Scan(section, countProbeable(mc.level, section)));
            }
        }
        if (ticks % 10 == 0) {
            var iterator = scans.values().iterator();
            while (iterator.hasNext()) {
                Scan scan = iterator.next();
                if (!scan.dirty) continue;
                scan.dirty = false;
                if (!hasUnprobed(mc.level, scan)) {
                    done.add(scan.section);
                    iterator.remove();
                }
            }
        }
        List<Probe> probes = collect(mc.level, eye, reach);
        if (probes.isEmpty() || budget < 1) return;
        int selected = mc.player.getInventory().getSelectedSlot();
        int slot = slot(mc, selected);
        if (slot < 0) {
            if (warningTicks == 0) {
                ClientNotify.warning("[NetheriteFinder] Probing needs an empty or non-tool hotbar slot.");
                warningTicks = 200;
            }
            return;
        }
        try {
            if (slot != selected) {
                mc.player.getInventory().setSelectedSlot(slot);
                mc.player.connection.send(new ServerboundSetCarriedItemPacket(slot));
            }
            for (Probe probe : probes) {
                if (budget < 1) break;
                var state = mc.level.getBlockState(probe.pos());
                // Native startDestroyBlock supplies the correct prediction sequence. Reject instant
                // breaks; always abort immediately, never continue or send STOP_DESTROY_BLOCK.
                if (state.getDestroyProgress(mc.player, mc.level, probe.pos()) >= 1) continue;
                budget--;
                boolean started;
                try {
                    started = mc.gameMode.startDestroyBlock(probe.pos(), Direction.UP);
                } finally {
                    mc.gameMode.stopDestroyBlock();
                }
                if (started) {
                    probe.scan().probed.set(probe.index());
                    probe.scan().sent++;
                    probe.scan().stamp[probe.index()] = ticks;
                    probe.scan().dirty = true;
                }
            }
        } finally {
            if (slot != selected) {
                mc.player.getInventory().setSelectedSlot(selected);
                mc.player.connection.send(new ServerboundSetCarriedItemPacket(selected));
            }
        }
    }

    private List<Probe> collect(ClientLevel world, Vec3 eye, double reach) {
        List<Probe> coarse = new ArrayList<>(), fine = new ArrayList<>();
        int span = (int) Math.ceil(reach), cx = (int) Math.floor(eye.x), cy = (int) Math.floor(eye.y), cz = (int) Math.floor(eye.z);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Scan scan : scans.values()) {
            int bx = scan.section.chunkX() * 16, by = scan.section.sectionY() * 16, bz = scan.section.chunkZ() * 16;
            if (distanceSq(scan.section.box(), eye) > reach * reach) continue;
            for (int x = Math.max(bx, cx - span); x <= Math.min(bx + 15, cx + span); x++)
                for (int y = Math.max(by, cy - span); y <= Math.min(by + 15, cy + span); y++)
                    for (int z = Math.max(bz, cz - span); z <= Math.min(bz + 15, cz + span); z++) {
                        int index = (y & 15) << 8 | (z & 15) << 4 | (x & 15);
                        if (scan.probed.get(index)) continue;
                        pos.set(x, y, z);
                        double distance = distanceSq(new AABB(pos), eye);
                        if (distance > reach * reach) continue;
                        if (!worthProbing(world, pos)) { scan.probed.set(index); continue; }
                        Probe probe = new Probe(scan, index, pos.immutable(), distance);
                        if ((x & 15) % 3 == 1 && (y & 15) % 3 == 1 && (z & 15) % 3 == 1) coarse.add(probe);
                        else fine.add(probe);
                    }
        }
        coarse.sort(Comparator.comparingDouble(Probe::distance));
        fine.sort(Comparator.comparingDouble(Probe::distance));
        coarse.addAll(fine);
        return coarse;
    }

    public List<Mark> marks(Vec3 eye, float missingFrom, int probeLimit) {
        if (!armed || level == null) return List.of();
        List<Mark> result = new ArrayList<>();
        int probes = 0, missing = 0;
        for (Scan scan : scans.values()) {
            boolean showMissing = scan.fraction() >= missingFrom;
            for (int index = 0; index < 4096; index++) {
                if (showMissing ? scan.probed.get(index) || missing >= 1600 : scan.stamp[index] == 0 || probes >= probeLimit) continue;
                BlockPos pos = cell(scan.section, index);
                if (pos.distToCenterSqr(eye) > (showMissing ? 32 * 32 : 12 * 12)) continue;
                if (showMissing && !worthProbing(level, pos)) continue;
                result.add(new Mark(pos, ticks - scan.stamp[index] + 1, showMissing));
                if (showMissing) missing++; else probes++;
            }
        }
        return result;
    }

    private static int countProbeable(ClientLevel world, Section section) {
        int count = 0;
        for (int i = 0; i < 4096; i++) if (worthProbing(world, cell(section, i))) count++;
        return count;
    }
    private static boolean hasUnprobed(ClientLevel world, Scan scan) {
        for (int i = scan.probed.nextClearBit(0); i < 4096; i = scan.probed.nextClearBit(i + 1))
            if (worthProbing(world, cell(scan.section, i))) return true;
        return false;
    }
    private static BlockPos cell(Section section, int index) {
        return new BlockPos(section.chunkX() * 16 + (index & 15), section.sectionY() * 16 + (index >> 8), section.chunkZ() * 16 + ((index >> 4) & 15));
    }
    private static boolean worthProbing(ClientLevel world, BlockPos pos) {
        if (!SpawnerScan.loaded(world, pos)) return false;
        var state = world.getBlockState(pos);
        return !state.isAir() && state.getFluidState().isEmpty() && !state.is(Blocks.ANCIENT_DEBRIS)
            && state.isSolidRender() && state.getDestroySpeed(world, pos) > 0;
    }
    private static int slot(Minecraft mc, int selected) {
        if (!mc.player.getInventory().getItem(selected).has(DataComponents.TOOL)) return selected;
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getItem(i).isEmpty()) return i;
        for (int i = 0; i < 9; i++) if (!mc.player.getInventory().getItem(i).has(DataComponents.TOOL)) return i;
        return -1;
    }
    public static double distanceSq(AABB box, Vec3 point) {
        double x = Math.max(0, Math.max(box.minX - point.x, point.x - box.maxX));
        double y = Math.max(0, Math.max(box.minY - point.y, point.y - box.maxY));
        double z = Math.max(0, Math.max(box.minZ - point.z, point.z - box.maxZ));
        return x * x + y * y + z * z;
    }
}
