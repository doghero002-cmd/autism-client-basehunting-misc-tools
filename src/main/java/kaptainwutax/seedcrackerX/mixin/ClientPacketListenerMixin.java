package kaptainwutax.seedcrackerX.mixin;

import kaptainwutax.seedcrackerX.SeedCracker;
import kaptainwutax.seedcrackerX.config.Config;
import kaptainwutax.seedcrackerX.config.StructureSave;
import kaptainwutax.seedcrackerX.cracker.DataAddedEvent;
import kaptainwutax.seedcrackerX.cracker.HashedSeedData;
import kaptainwutax.seedcrackerX.finder.FinderQueue;
import kaptainwutax.seedcrackerX.finder.ReloadFinders;
import kaptainwutax.seedcrackerX.util.Database;
import kaptainwutax.seedcrackerX.util.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Shadow
    private ClientLevel level;

    @Shadow public abstract Connection getConnection();

    @Unique private boolean spectre$captureFailed;

    @Inject(method = {"handleLevelChunkWithLight", "handleBlockEntityData", "handleBlockUpdate",
        "handleChunkBlocksUpdate", "handleForgetLevelChunk", "handleLogin", "handleRespawn"}, at = @At("RETURN"))
    private void spectre$captureBlockEntities(@Coerce Packet<?> packet, CallbackInfo ci) {
        if (spectre$captureFailed || this.level != Minecraft.getInstance().level) return;
        try {
            com.autism.seedcracker.spectre.palette.BlockEntityCapture.onPacketApplied(this.level, packet);
        } catch (RuntimeException error) {
            spectre$captureFailed = true;
            com.autism.seedcracker.spectre.palette.BlockEntityCapture.clear();
            org.slf4j.LoggerFactory.getLogger("DogsTools/Spectre").warn("Block entity capture stopped until reconnect", error);
        }
    }

    @Inject(method = "handleLevelChunkWithLight", at = @At(value = "TAIL"))
    private void onChunkData(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        spectre$packetApplied(packet);
        int chunkX = packet.getX();
        int chunkZ = packet.getZ();
        FinderQueue.get().onChunkData(this.level, new ChunkPos(chunkX, chunkZ));
        if (this.level == null || this.level != Minecraft.getInstance().level) return;
        // Netherite Finder: hand the raw chunk buffer to the palette scanner so it can reveal
        // ancient debris the server hid from the client's block view.
        com.autism.seedcracker.modules.NetheriteFinderModule module =
            (com.autism.seedcracker.modules.NetheriteFinderModule) com.autism.seedcracker.compat.ModuleLookup.get(
                com.autism.seedcracker.SeedcrackerAddon.ID + ":netherite-finder");
        if (module != null && module.isEnabled() && this.level != null) {
            try {
                module.onChunkData(packet.getChunkData().getReadBuffer(), chunkX, chunkZ,
                    this.level.getSectionsCount(), this.level.getMinSectionY());
            } catch (RuntimeException error) { spectre$detectorFailed(module, error); }
        }
        // Amethyst bypass: same palette-leak trick for hidden geodes.
        com.autism.seedcracker.modules.AmethystEspModule amethyst =
            (com.autism.seedcracker.modules.AmethystEspModule) com.autism.seedcracker.compat.ModuleLookup.get(
                com.autism.seedcracker.SeedcrackerAddon.ID + ":amethyst-esp");
        if (amethyst != null && amethyst.isEnabled() && this.level != null) {
            try {
                amethyst.onChunkData(packet.getChunkData().getReadBuffer(), chunkX, chunkZ,
                    this.level.getSectionsCount(), this.level.getMinSectionY());
            } catch (RuntimeException error) { spectre$detectorFailed(amethyst, error); }
        }
    }

    @Inject(method = "handleBlockUpdate", at = @At("TAIL"))
    private void onBlockUpdate(net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        spectre$packetApplied(packet);
        spectre$revealedBlock(packet.getPos(), packet.getBlockState());
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
    private void onChunkBlocksUpdate(net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        spectre$packetApplied(packet);
        packet.runUpdates(this::spectre$revealedBlock);
    }

    @Unique
    private void spectre$revealedBlock(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        if (this.level == null || this.level != Minecraft.getInstance().level) return;
        var debris = com.autism.seedcracker.compat.ModuleLookup.get(com.autism.seedcracker.SeedcrackerAddon.ID + ":netherite-finder");
        if (debris instanceof com.autism.seedcracker.modules.NetheriteFinderModule finder && finder.isEnabled()) {
            try { finder.onBlockUpdate(pos, state); }
            catch (RuntimeException error) { spectre$detectorFailed(finder, error); }
        }
        var amethyst = com.autism.seedcracker.compat.ModuleLookup.get(com.autism.seedcracker.SeedcrackerAddon.ID + ":amethyst-esp");
        if (amethyst instanceof com.autism.seedcracker.modules.AmethystEspModule finder && finder.isEnabled()) {
            try { finder.onBlockUpdate(pos, state); }
            catch (RuntimeException error) { spectre$detectorFailed(finder, error); }
        }
    }

    @Inject(method = "handleLightUpdatePacket", at = @At("TAIL"))
    private void spectre$lightApplied(ClientboundLightUpdatePacket packet, CallbackInfo ci) {
        spectre$packetApplied(packet);
    }

    @Inject(method = "handleForgetLevelChunk", at = @At("TAIL"))
    private void spectre$chunkUnloaded(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
        spectre$packetApplied(packet);
    }

    @Unique
    private void spectre$packetApplied(Packet<?> packet) {
        if (this.level == null || this.level != Minecraft.getInstance().level) return;
        if (!(packet instanceof ClientboundLightUpdatePacket)) {
            var chunk = com.autism.seedcracker.compat.ModuleLookup.get(com.autism.seedcracker.SeedcrackerAddon.ID + ":z-chunk-finder");
            if (chunk instanceof com.autism.seedcracker.modules.ChunkFinderModule finder && finder.isEnabled()) {
                try { finder.onPacketApplied(this.level, packet); }
                catch (RuntimeException error) { spectre$detectorFailed(finder, error); }
            }
        }
        var sus = com.autism.seedcracker.compat.ModuleLookup.get(com.autism.seedcracker.SeedcrackerAddon.ID + ":z-sus-chunk-finder");
        if (sus instanceof com.autism.seedcracker.modules.SusChunkFinderModule finder && finder.isEnabled()) {
            try { finder.onPacketApplied(this.level, packet); }
            catch (RuntimeException error) { spectre$detectorFailed(finder, error); }
        }
        if (packet instanceof ClientboundLevelChunkWithLightPacket || packet instanceof ClientboundLightUpdatePacket
            || packet instanceof ClientboundForgetLevelChunkPacket) {
            var prime = com.autism.seedcracker.compat.ModuleLookup.get(com.autism.seedcracker.SeedcrackerAddon.ID + ":prime-chunk-finder");
            if (prime instanceof com.autism.seedcracker.modules.PrimeChunkFinderModule finder && finder.isEnabled()) {
                try { finder.onPacketApplied(this.level, packet); }
                catch (RuntimeException error) { spectre$detectorFailed(finder, error); }
            }
        }
        var player = com.autism.seedcracker.compat.ModuleLookup.get(com.autism.seedcracker.SeedcrackerAddon.ID + ":player-chunks");
        if (player instanceof com.autism.seedcracker.modules.PlayerChunksModule finder && finder.isEnabled()) {
            try { finder.onPacketApplied(packet); }
            catch (RuntimeException error) { spectre$detectorFailed(finder, error); }
        }
        var quantum = com.autism.seedcracker.compat.ModuleLookup.get(com.autism.seedcracker.SeedcrackerAddon.ID + ":quantum");
        if (quantum instanceof com.autism.seedcracker.modules.QuantumModule finder && finder.isEnabled()) {
            try { finder.onPacketApplied(packet); }
            catch (RuntimeException error) { spectre$detectorFailed(finder, error); }
        }
        var light = com.autism.seedcracker.compat.ModuleLookup.get(com.autism.seedcracker.SeedcrackerAddon.ID + ":light-finder");
        if (light instanceof com.autism.seedcracker.modules.LightSourceFinderModule finder && finder.isEnabled()) {
            try { finder.onPacketApplied(packet); }
            catch (RuntimeException error) { spectre$detectorFailed(finder, error); }
        }
    }

    @Unique
    private static void spectre$detectorFailed(autismclient.modules.Module module, RuntimeException error) {
        try { module.setEnabled(false); }
        catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
        org.slf4j.LoggerFactory.getLogger("DogsTools/Spectre").warn("Packet detector {} failed", module.id(), error);
    }

    @Inject(method = "handleLogin", at = @At(value = "TAIL"))
    public void onGameJoin(ClientboundLoginPacket packet, CallbackInfo ci) {
        newDimension(new HashedSeedData(packet.commonPlayerSpawnInfo().seed()), false);
        com.autism.seedcracker.util.ActionPacer.resetSession(); // fresh fatigue/backoff per session
        com.autism.seedcracker.finder.BaseTracker.clear();
        com.autism.seedcracker.finder.BaseAlerts.reset();
        tryDatabase();
        var preloaded = StructureSave.loadStructures();
        if (!preloaded.isEmpty()) {
            Log.warn("foundRestorableStructures", preloaded.size());
        }
    }

    @Inject(method = "handleRespawn", at = @At(value = "TAIL"))
    public void onPlayerRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        newDimension(new HashedSeedData(packet.commonPlayerSpawnInfo().seed()), true);
        com.autism.seedcracker.finder.BaseTracker.clear();
        com.autism.seedcracker.finder.BaseAlerts.reset();
        tryDatabase();
    }

    @Unique
    private void newDimension(HashedSeedData hashedSeedData, boolean dimensionChange) {
        DimensionType dimension = Minecraft.getInstance().level.dimensionType();
        ReloadFinders.reloadHeight(dimension.minY(), dimension.minY() + dimension.logicalHeight());

        if (SeedCracker.get().getDataStorage().addHashedSeedData(hashedSeedData, DataAddedEvent.POKE_BIOMES) && Config.get().active && dimensionChange) {
            Log.error(Log.translate("fetchedHashedSeed"));
            if (Config.get().debug) {
                Log.error("Hashed seed [" + hashedSeedData.getHashedSeed() + "]");
            }
        }
    }

    @Unique
    private void tryDatabase() {
        Long seed = Database.getSeed(this.getConnection().getRemoteAddress().toString(), SeedCracker.get().getDataStorage().hashedSeedData.getHashedSeed());
        if (seed == null) {
            return;
        }
        Log.printSeed("tmachine.foundWorldSeedFromDatabase", seed);
    }
}
