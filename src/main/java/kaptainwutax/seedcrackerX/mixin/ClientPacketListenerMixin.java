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
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Shadow
    private ClientLevel level;

    @Shadow public abstract Connection getConnection();

    @Inject(method = "handleLevelChunkWithLight", at = @At(value = "TAIL"))
    private void onChunkData(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        int chunkX = packet.getX();
        int chunkZ = packet.getZ();
        FinderQueue.get().onChunkData(this.level, new ChunkPos(chunkX, chunkZ));
        // Netherite Finder: hand the raw chunk buffer to the palette scanner so it can reveal
        // ancient debris the server hid from the client's block view.
        com.autism.seedcracker.modules.NetheriteFinderModule module =
            (com.autism.seedcracker.modules.NetheriteFinderModule) com.autism.seedcracker.compat.ModuleLookup.get(
                com.autism.seedcracker.SeedcrackerAddon.ID + ":netherite-finder");
        if (module != null && module.isEnabled() && this.level != null) {
            try {
                module.onChunkData(packet.getChunkData().getReadBuffer(), chunkX, chunkZ,
                    this.level.getSectionsCount(), this.level.getMinSectionY());
            } catch (Throwable ignored) {}
        }
        // Amethyst bypass: same palette-leak trick for hidden geodes.
        com.autism.seedcracker.modules.AmethystEspModule amethyst =
            (com.autism.seedcracker.modules.AmethystEspModule) com.autism.seedcracker.compat.ModuleLookup.get(
                com.autism.seedcracker.SeedcrackerAddon.ID + ":amethyst-esp");
        if (amethyst != null && amethyst.isEnabled() && this.level != null) {
            try {
                amethyst.onChunkData(packet.getChunkData().getReadBuffer(), chunkX, chunkZ,
                    this.level.getSectionsCount(), this.level.getMinSectionY());
            } catch (Throwable ignored) {}
        }
    }

    /** Single block update: when a block actually BECOMES ancient debris, the server reveals its
     * exact position here. Hand it to NetheriteFinder so it renders the precise block (Anubis
     * AdvancedFinder reveal behaviour), not just the 16x16x16 section. */
    @Inject(method = "handleBlockUpdate", at = @At(value = "TAIL"))
    private void onBlockUpdate(net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        com.autism.seedcracker.modules.NetheriteFinderModule module =
            (com.autism.seedcracker.modules.NetheriteFinderModule) com.autism.seedcracker.compat.ModuleLookup.get(
                com.autism.seedcracker.SeedcrackerAddon.ID + ":netherite-finder");
        if (module == null || !module.isEnabled() || this.level == null) return;
        try {
            module.onBlockUpdate(packet.getPos(), packet.getBlockState());
        } catch (Throwable ignored) {}
    }

    /** Multi block update (section blocks update): same reveal path for batched changes. */
    @Inject(method = "handleChunkBlocksUpdate", at = @At(value = "TAIL"))
    private void onChunkBlocksUpdate(net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        com.autism.seedcracker.modules.NetheriteFinderModule module =
            (com.autism.seedcracker.modules.NetheriteFinderModule) com.autism.seedcracker.compat.ModuleLookup.get(
                com.autism.seedcracker.SeedcrackerAddon.ID + ":netherite-finder");
        if (module == null || !module.isEnabled() || this.level == null) return;
        try {
            packet.runUpdates((pos, state) -> module.onBlockUpdate(pos, state));
        } catch (Throwable ignored) {}
    }

    @Inject(method = "handleLogin", at = @At(value = "TAIL"))
    public void onGameJoin(ClientboundLoginPacket packet, CallbackInfo ci) {
        newDimension(new HashedSeedData(packet.commonPlayerSpawnInfo().seed()), false);
        tryDatabase();
        var preloaded = StructureSave.loadStructures();
        if (!preloaded.isEmpty()) {
            Log.warn("foundRestorableStructures", preloaded.size());
        }
    }

    @Inject(method = "handleRespawn", at = @At(value = "TAIL"))
    public void onPlayerRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        newDimension(new HashedSeedData(packet.commonPlayerSpawnInfo().seed()), true);
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
