package com.autism.seedcracker.modules;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.DoubleSetting;
import autismclient.modules.Module;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.spectre.mechanics.SpeedMinePolicy;
import com.autism.seedcracker.util.ContainerMutex;
import com.autism.seedcracker.util.RelogPersistence;
import net.minecraft.client.Minecraft;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** SPDX-License-Identifier: GPL-3.0-only. Adapted from Spectre misc/SpeedMineModule, d5a7dd3. */
public final class SpeedMineModule extends Module {
    private static volatile SpeedMineModule active;
    private final DoubleSetting breakAt = add(new DoubleSetting("break-at", "Break At", 0.7, 0.3, 1.0, 0.01)
        .description("Fraction of the normal mining time. Server acceptance is not guaranteed.").group("Mining"));
    private final BoolSetting skipDelay = add(new BoolSetting("skip-delay", "Skip Break Delay", true).group("Mining"));
    private final BoolSetting keepInstantDelay = add(new BoolSetting("keep-instant-delay", "Keep Instant Delay", true)
        .description("Keep the vanilla delay for blocks the held tool already breaks instantly.")
        .visibleWhen(skipDelay::get).group("Mining"));

    public SpeedMineModule() {
        super(SeedcrackerAddon.ID + ":speed-mine", "Donut Speed Mine",
            "Speeds up eligible block mining; excludes spawners and axe/shovel blocks except gravel.");
    }

    @Override public void onEnable() { active = this; }
    @Override public void onDisable() { if (active == this) active = null; }
    @Override public void onGameJoin() { if (isEnabled()) active = this; }
    @Override public void onGameLeft() {
        onDisable();
        if (RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    public static float scaleProgress(BlockState state, float progress) {
        SpeedMineModule module = current();
        return module == null ? progress : SpeedMinePolicy.scale(affects(state), progress, module.breakAt.get());
    }

    public static boolean dropsDelay(BlockState state, float progress) {
        SpeedMineModule module = current();
        return module != null && SpeedMinePolicy.dropsDelay(affects(state), progress,
            module.skipDelay.get(), module.keepInstantDelay.get());
    }

    private static SpeedMineModule current() {
        SpeedMineModule module = active;
        if (module == null || !module.isEnabled()) return null;
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread() || mc.level == null || mc.player == null || !mc.player.isAlive()
            || mc.player.isSpectator() || mc.player.getAbilities().instabuild || mc.gui.screen() != null
            || ContainerMutex.containerBusy(mc)) return null;
        return module;
    }

    private static boolean affects(BlockState state) {
        return SpeedMinePolicy.affects(state.isAir(), state.is(Blocks.SPAWNER), state.is(Blocks.GRAVEL),
            state.is(BlockTags.MINEABLE_WITH_SHOVEL), state.is(BlockTags.MINEABLE_WITH_AXE));
    }
}
