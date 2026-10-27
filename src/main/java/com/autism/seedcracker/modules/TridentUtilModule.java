package com.autism.seedcracker.modules;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.DoubleSetting;
import autismclient.modules.Module;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.spectre.mechanics.TridentChargePolicy;
import com.autism.seedcracker.util.RelogPersistence;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;

/**
 * SPDX-License-Identifier: GPL-3.0-only
 * Adapted from Spectre misc/TridentUtilModule and MixinTridentItem, d5a7dd3.
 * Only the local client player's use is changed, never integrated-server or remote players.
 * Deliberately conservative defaults: reduced charge and dry use require explicit settings.
 * Riptide owns repeated manual releases; this module only owns vanilla item-use modifiers.
 */
public final class TridentUtilModule extends Module {
    private static volatile TridentUtilModule active;
    private final DoubleSetting chargeScale = add(new DoubleSetting("scale", "Charge Scale", 1.0, 0.0, 1.0, 0.1)
        .description("1.0 is vanilla. Lower values reduce client charge; servers may reject the use.").group("Trident"));
    private final BoolSetting noWater = add(new BoolSetting("no-water", "Dry Use (experimental)", false)
        .description("Allow local riptide use without water/rain. Server acceptance is not guaranteed.").group("Trident"));

    public TridentUtilModule() {
        super(SeedcrackerAddon.ID + ":trident-util", "Trident Util",
            "Configures trident charge and optional dry use; does not automatically use items.");
    }

    @Override public void onEnable() { active = this; }
    @Override public void onDisable() { if (active == this) active = null; }
    @Override public void onGameJoin() { if (isEnabled()) active = this; }
    @Override public void onGameLeft() {
        onDisable();
        if (RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    public static boolean allowOutOfWater(LivingEntity entity) {
        TridentUtilModule module = current(entity);
        return module != null && module.noWater.get();
    }

    public static int minChargeTicks(LivingEntity entity, int vanilla) {
        TridentUtilModule module = current(entity);
        return module == null ? vanilla : TridentChargePolicy.minimum(vanilla, module.chargeScale.get());
    }

    private static TridentUtilModule current(LivingEntity entity) {
        TridentUtilModule module = active;
        if (module == null || !module.isEnabled()) return null;
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread() || mc.player == null || mc.level == null || entity != mc.player
            || entity.level() != mc.level || !entity.isAlive() || mc.player.isSpectator()) return null;
        return module;
    }
}
