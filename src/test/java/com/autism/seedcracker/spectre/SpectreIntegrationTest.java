package com.autism.seedcracker.spectre;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpectreIntegrationTest {
    private static final Set<String> PORTS = Set.of(
        "SpectatorDetectorModule", "VisualRangeModule", "StaffListModule", "AntiTrapModule",
        "SpawnerProtectModule", "NetheriteFinderModule", "SpawnerFinderModule", "AmethystEspModule",
        "BlockEntityDebugModule", "SpawnerNametagsModule", "ChunkFinderModule", "SusChunkFinderModule",
        "PrimeChunkFinderModule", "PlayerChunksModule", "LightSourceFinderModule", "QuantumModule",
        "RenderUtilModule", "AutoRenderModule", "HomeSetterModule", "AutoRelogModule", "AirStuckModule",
        "DonutNoFallModule", "SpeedMineModule", "TridentUtilModule", "RiptideModule", "FakeStatsModule",
        "RegionMapModule");

    @Test
    void allTwentySevenPortsAreRegisteredExactlyOnce() throws Exception {
        var entrypoint = load("com/autism/seedcracker/SeedcrackerAddon");
        var constructors = new HashMap<String, Integer>();
        var initialize = entrypoint.methods().stream().filter(m -> m.methodName().equalsString("onInitialize")).findFirst().orElseThrow();
        for (var code : initialize.code().orElseThrow()) {
            if (code instanceof InvokeInstruction invoke && invoke.name().equalsString("<init>")) {
                String owner = invoke.owner().name().stringValue();
                constructors.merge(owner.substring(owner.lastIndexOf('/') + 1), 1, Integer::sum);
            }
        }
        assertEquals(27, PORTS.size());
        for (String port : PORTS) assertEquals(1, constructors.getOrDefault(port, 0), port);
        assertFalse(constructors.containsKey("FakeScoreboardHud"));
    }

    @Test
    void clientStoppingClosesTheLocalBridge() throws Exception {
        Set<String> calls = calls(load("com/autism/seedcracker/SeedcrackerAddon"));
        assertTrue(calls.contains("net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientLifecycleEvents.CLIENT_STOPPING"));
        assertTrue(calls.contains("com/autism/seedcracker/bridge/GameBridge.stop"));
    }

    @Test
    void appliedPacketHooksReachEveryPacketDrivenFinder() throws Exception {
        Set<String> calls = calls(load("kaptainwutax/seedcrackerX/mixin/ClientPacketListenerMixin"));
        for (String module : Set.of("ChunkFinderModule", "SusChunkFinderModule", "PrimeChunkFinderModule",
                "PlayerChunksModule", "QuantumModule", "LightSourceFinderModule")) {
            assertTrue(calls.contains("com/autism/seedcracker/modules/" + module + ".onPacketApplied"), module);
        }
        assertTrue(calls.contains("com/autism/seedcracker/spectre/palette/BlockEntityCapture.onPacketApplied"));
        assertTrue(calls.contains("com/autism/seedcracker/modules/NetheriteFinderModule.onBlockUpdate"));
        assertTrue(calls.contains("com/autism/seedcracker/modules/AmethystEspModule.onBlockUpdate"));
        Set<String> worldCalls = calls(load("kaptainwutax/seedcrackerX/mixin/ClientLevelMixin"));
        assertTrue(worldCalls.contains("com/autism/seedcracker/modules/PrimeChunkFinderModule.onBlockChange"));
        assertTrue(worldCalls.contains("com/autism/seedcracker/modules/QuantumModule.onBlockUpdate"));
    }

    private static Set<String> calls(ClassModel model) {
        Set<String> calls = new HashSet<>();
        for (var entry : model.constantPool()) if (entry instanceof MemberRefEntry ref) {
            calls.add(ref.owner().name().stringValue() + "." + ref.name().stringValue());
        }
        return calls;
    }

    private static ClassModel load(String name) throws Exception {
        try (var input = SpectreIntegrationTest.class.getResourceAsStream("/" + name + ".class")) {
            assertNotNull(input, name);
            return ClassFile.of().parse(input.readAllBytes());
        }
    }
}
