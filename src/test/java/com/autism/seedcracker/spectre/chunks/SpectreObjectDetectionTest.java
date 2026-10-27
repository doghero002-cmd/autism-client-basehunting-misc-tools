package com.autism.seedcracker.spectre.chunks;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.autism.seedcracker.spectre.chunks.seedscan.BlobJudge;
import com.autism.seedcracker.spectre.chunks.seedscan.VBlock;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpectreObjectDetectionTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void observedCategoriesAndPrimeSourcesKeepTheirDistinctMeaning() {
        assertEquals(SuspectObjects.Category.ENDER_STORAGE, SuspectObjects.category(Blocks.ENDER_CHEST.defaultBlockState()));
        assertEquals(SuspectObjects.Category.CONTAINERS, SuspectObjects.category(Blocks.CHEST.defaultBlockState()));
        assertEquals(SuspectObjects.Category.HOPPERS, SuspectObjects.category(Blocks.HOPPER.defaultBlockState()));
        assertEquals(SuspectObjects.Category.MACHINES, SuspectObjects.category(Blocks.CRAFTER.defaultBlockState()));
        assertNull(SuspectObjects.category(Blocks.STONE.defaultBlockState()));
        assertTrue(PrimeEvidence.redstone(Blocks.NOTE_BLOCK.defaultBlockState()));
        assertFalse(PrimeEvidence.redstone(Blocks.STONE.defaultBlockState()));
        assertTrue(PrimeEvidence.lamp(Blocks.COPPER_TORCH.defaultBlockState()));
        assertTrue(PrimeEvidence.lamp(Blocks.CAVE_VINES.defaultBlockState().setValue(BlockStateProperties.BERRIES, true)));
        assertFalse(PrimeEvidence.lamp(Blocks.CAVE_VINES.defaultBlockState().setValue(BlockStateProperties.BERRIES, false)));
    }

    @Test void formationsJoinAcrossChunkBordersAndRequireObservedMaturity() {
        var cells = new HashMap<BlockPos, SuspectObjects.Found>();
        add(cells, new BlockPos(15, 10, 0), Blocks.BUDDING_AMETHYST.defaultBlockState());
        add(cells, new BlockPos(16, 10, 0), Blocks.AMETHYST_CLUSTER.defaultBlockState());
        add(cells, new BlockPos(16, 11, 0), Blocks.AMETHYST_CLUSTER.defaultBlockState());
        add(cells, new BlockPos(15, 11, 0), Blocks.SMALL_AMETHYST_BUD.defaultBlockState());
        assertTrue(SuspectObjects.geodes(cells, 2, 67, false).isEmpty());
        var groups = SuspectObjects.geodes(cells, 2, 66, false);
        assertEquals(1, groups.size()); assertEquals(17, groups.getFirst().bounds().maxX);
        assertEquals(4, groups.getFirst().icons().values().stream().mapToInt(Integer::intValue).sum());
        assertTrue(groups.getFirst().detail().contains("2 mature / 1 buds"));
    }

    @Test void bareFormationMigrationIsOptInAndNeverClaimsMiningAsFact() {
        var cells = new HashMap<BlockPos, SuspectObjects.Found>();
        for (int x = 0; x < 10; x++) add(cells, new BlockPos(x, 10, 0), Blocks.BUDDING_AMETHYST.defaultBlockState());
        assertTrue(SuspectObjects.geodes(cells, 12, 75, false).isEmpty());
        var groups = SuspectObjects.geodes(cells, 12, 75, false, true);
        assertEquals(1, groups.size()); assertEquals(SuspectObjects.Category.BARE_GEODES, groups.getFirst().category());
        assertTrue(groups.getFirst().detail().contains("hypothesis"));
        add(cells, new BlockPos(10, 10, 0), Blocks.SMALL_AMETHYST_BUD.defaultBlockState());
        assertTrue(SuspectObjects.geodes(cells, 12, 75, false, true).isEmpty());
    }

    @Test void plantLengthUsesContiguousGeometryNotAgeOrUnrelatedStems() {
        var cells = new HashMap<BlockPos, SuspectObjects.Found>();
        add(cells, new BlockPos(0, 10, 0), Blocks.KELP.defaultBlockState());
        add(cells, new BlockPos(0, 11, 0), Blocks.KELP_PLANT.defaultBlockState());
        add(cells, new BlockPos(1, 10, 0), Blocks.KELP.defaultBlockState());
        assertTrue(SuspectObjects.plants(cells, kind -> 3, false).isEmpty());
        add(cells, new BlockPos(0, 12, 0), Blocks.KELP.defaultBlockState());
        var groups = SuspectObjects.plants(cells, kind -> 3, false);
        assertEquals(1, groups.size()); assertEquals(13, groups.getFirst().bounds().maxY);
        assertEquals(3, groups.getFirst().icons().values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test void exactLookTargetBeatsANearerInflatedNearMissAndGroupsNearbyEvidence() {
        var near = marker(new AABB(.1, -.5, 2, 1.1, .5, 3));
        var exact = marker(new AABB(-.5, -.5, 8, .5, .5, 9));
        var focus = SuspectObjects.focus(List.of(near, exact), Vec3.ZERO, new Vec3(0, 0, 1), 16);
        assertNotNull(focus); assertSame(exact, focus.target()); assertEquals(2, focus.nearby().size());
        assertEquals(8.0, focus.distance(), .001);
    }

    @Test void seedBlobJudgeRejectsMissingOpenOrMismatchingTerrainBeforeFlagging() {
        long[] cells = new long[8]; for (int x = 0; x < 8; x++) cells[x] = BlockPos.asLong(x, 10, 0);
        var cluster = new BlobJudge.Cluster(VBlock.DIORITE, cells[0], null, cells, cells, new long[0], new byte[0]);
        assertEquals(BlobJudge.Status.ALERT, BlobJudge.judge(cluster, (x, y, z) -> (x == 0 ? Blocks.STONE : Blocks.DIORITE).defaultBlockState(), 1).status());
        assertEquals(BlobJudge.Status.INCOMPLETE, BlobJudge.judge(cluster, (x, y, z) -> x == 0 ? null : Blocks.DIORITE.defaultBlockState(), 1).status());
        assertEquals(BlobJudge.Status.OPEN, BlobJudge.judge(cluster, (x, y, z) -> (x == 0 ? Blocks.AIR : Blocks.DIORITE).defaultBlockState(), 1).status());
        assertEquals(BlobJudge.Status.MISPREDICTED, BlobJudge.judge(cluster, (x, y, z) -> (x < 4 ? Blocks.STONE : Blocks.DIORITE).defaultBlockState(), 1).status());
        assertEquals(BlobJudge.Status.HIDDEN, BlobJudge.judge(cluster, (x, y, z) -> (x < 3 ? Blocks.REDSTONE_ORE : Blocks.DIORITE).defaultBlockState(), 1).status());
    }

    private static SuspectObjects.Marker marker(AABB box) { return new SuspectObjects.Marker(box, List.of(box), SuspectObjects.Category.MACHINES, Map.of(), "Observed machine"); }
    private static void add(Map<BlockPos, SuspectObjects.Found> cells, BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        cells.put(pos, new SuspectObjects.Found(pos, state, SuspectObjects.category(state)));
    }
}
