package com.autism.seedcracker.krypton;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Key Pearl.
 *
 * On enable: swaps to an ender pearl, throws it, then swaps back to your previous slot. Bind the
 * module toggle to a key for a one-press panic-pearl.
 *
 * Port of the Krypton "KeyPearl" module to the AUTISM API (Mojang 26.2). The original used a
 * separate activate bind; here enabling the module performs one throw (rebind the toggle key).
 */
public final class KeyPearlModule extends Module {

    private final IntSetting throwDelay = add(new IntSetting("throw-delay", "Throw delay", 2, 1, 20, 1)
        .description("Ticks to wait after swapping before throwing (min 1 so the carried-item packet lands first - never 0).").group("General"));
    private final BoolSetting switchBack = add(new BoolSetting("switch-back", "Switch back", true)
        .description("Swap back to the previous slot after throwing.").group("General"));
    private final IntSetting switchDelay = add(new IntSetting("switch-delay", "Switch delay", 0, 0, 20, 1)
        .description("Ticks to wait after throwing before swapping back.").group("General"));

    private int prevSlot = -1;
    private int pearlSlot = -1;
    private int stage = 0; // 0=swap+throw, 1=wait, 2=swapback
    private int counter = 0;

    public KeyPearlModule() {
        super(SeedcrackerAddon.ID + ":key-pearl", "Key Pearl",
            "Enable to throw an ender pearl and swap back. Bind the toggle to a key.");
    }

    @Override
    public void onEnable() {
        prevSlot = -1;
        pearlSlot = -1;
        stage = 0;
        counter = 0;
    }

    @Override public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && switchBack.get() && com.autism.seedcracker.util.pure.InventoryPolicy.shouldRestoreSlot(
            prevSlot, pearlSlot, mc.player.getInventory().getSelectedSlot())) com.autism.seedcracker.util.InvSync.select(mc, prevSlot);
        prevSlot = -1;
        pearlSlot = -1;
    }

    @Override public void onGameLeft() {
        prevSlot = -1;
        pearlSlot = -1;
        stage = 0;
        setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null || mc.gui.screen() != null) { setEnabled(false); return; }

        if (stage == 0) {
            prevSlot = mc.player.getInventory().getSelectedSlot();
            int pearl = findItem(mc, Items.ENDER_PEARL);
            if (pearl == -1) { setEnabledSilently(false); return; }
            com.autism.seedcracker.util.InvSync.select(mc, pearl);
            pearlSlot = pearl;
            stage = 1;
            counter = 0;
        } else if (stage == 1) {
            if (mc.player.getInventory().getSelectedSlot() != pearlSlot || !mc.player.getMainHandItem().is(Items.ENDER_PEARL)) {
                setEnabled(false);
                return;
            }
            if (counter++ < throwDelay.get()) return;
            if (!com.autism.seedcracker.util.ActionPacer.tryAction()) return;
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            mc.player.swing(InteractionHand.MAIN_HAND);
            stage = 2;
            counter = 0;
            if (!switchBack.get()) setEnabledSilently(false);
        } else if (stage == 2) {
            if (counter++ < switchDelay.get()) return;
            setEnabled(false);
        }
    }

    private static int findItem(Minecraft mc, Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s != null && s.is(item)) return i;
        }
        return -1;
    }
}
