package com.autism.seedcracker.krypton;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Auto Firework.
 *
 * On enable (while gliding / wearing an elytra): swaps to a firework rocket, uses it to boost,
 * then swaps back. Bind the module toggle to a key for a one-press boost.
 *
 * Port of the Krypton "AutoFirework" module to the AUTISM API (Mojang 26.2).
 */
public final class AutoFireworkModule extends Module {

    private final IntSetting useDelay = add(new IntSetting("use-delay", "Use delay", 0, 0, 20, 1)
        .description("Ticks to wait after swapping before using the firework.").group("General"));
    private final BoolSetting switchBack = add(new BoolSetting("switch-back", "Switch back", true)
        .description("Swap back to the previous slot after boosting.").group("General"));
    private final IntSetting switchDelay = add(new IntSetting("switch-delay", "Switch delay", 0, 0, 20, 1)
        .description("Ticks to wait after boosting before swapping back.").group("General"));

    private int prevSlot = -1;
    private int stage = 0;
    private int counter = 0;

    public AutoFireworkModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":auto-firework", "Auto Firework", category,
            "Enable to use a firework boost (while gliding) and swap back. Bind the toggle to a key.");
    }

    @Override
    public void onEnable() {
        prevSlot = -1;
        stage = 0;
        counter = 0;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.screen() != null) { setEnabledSilently(false); return; }

        if (stage == 0) {
            prevSlot = mc.player.getInventory().getSelectedSlot();
            int rocket = findRocket(mc);
            if (rocket == -1) { setEnabledSilently(false); return; }
            mc.player.getInventory().setSelectedSlot(rocket);
            stage = 1;
            counter = 0;
        } else if (stage == 1) {
            if (counter++ < useDelay.get()) return;
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            mc.player.swing(InteractionHand.MAIN_HAND);
            stage = 2;
            counter = 0;
            if (!switchBack.get()) setEnabledSilently(false);
        } else if (stage == 2) {
            if (counter++ < switchDelay.get()) return;
            if (prevSlot >= 0) mc.player.getInventory().setSelectedSlot(prevSlot);
            setEnabledSilently(false);
        }
    }

    private static int findRocket(Minecraft mc) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s != null && s.is(Items.FIREWORK_ROCKET)) return i;
        }
        return -1;
    }
}
