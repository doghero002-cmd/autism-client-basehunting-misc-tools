package com.autism.seedcracker.modules;

import java.util.concurrent.ThreadLocalRandom;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.util.ActionPacer;
import com.autism.seedcracker.util.Humanizer;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;

/**
 * Auto Fish.
 *
 * Recasts and reels automatically. The bite is detected from the bobber's sharp downward jerk
 * (the same cue a human watches for); the reel then fires after a humanized reaction delay -
 * instant sub-tick reeling is the classic autofish flag, and real fishing plugins measure
 * exactly that. Won't fight open screens, won't act without a rod in hand, and pauses while
 * the global ActionPacer is holding.
 */
public final class AutoFishModule extends Module {

    private final IntSetting reactionMs = add(new IntSetting("reaction", "Reaction time (ms)", 450, 150, 1500, 50)
        .description("Base human reaction delay between bite and reel (jittered ±35%).").group("General"));
    private final IntSetting recastTicks = add(new IntSetting("recast", "Recast delay (ticks)", 20, 5, 100, 5)
        .description("Base delay before recasting after a reel (jittered).").group("General"));
    private final BoolSetting antiBreak = add(new BoolSetting("anti-break", "Anti break", true)
        .description("Stop when the rod is about to break.").group("General"));

    /** 0 = waiting for bite; >0 = reel scheduled at this ms timestamp. */
    private long reelAtMs = 0;
    private int recastCooldown = 0;
    /** Bobber Y velocity from the previous tick (bite = sharp downward spike). */
    private double lastHookDy = 0;

    public AutoFishModule() {
        super(SeedcrackerAddon.ID + ":auto-fish", "Auto Fish",
            "Fishes for you with human reaction times (bite-jerk detection, jittered reel + recast).");
    }

    @Override
    public void onEnable() {
        reelAtMs = 0;
        recastCooldown = 0;
        lastHookDy = 0;
    }

    @Override
    public void onGameLeft() {
        reelAtMs = 0; // a scheduled reel must not fire on the next server
        recastCooldown = 0;
        lastHookDy = 0;
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (mc.gui.screen() != null) return;

        ItemStack held = mc.player.getMainHandItem();
        if (!(held.getItem() instanceof FishingRodItem)) return;
        if (antiBreak.get() && held.getMaxDamage() > 0
            && held.getMaxDamage() - held.getDamageValue() < 5) {
            return; // rod nearly dead - stop silently, user sees info()
        }

        FishingHook hook = mc.player.fishing;

        // No bobber out: recast after the cooldown.
        if (hook == null) {
            reelAtMs = 0;
            if (recastCooldown > 0) { recastCooldown--; return; }
            if (!ActionPacer.tryAction()) return;
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            mc.player.swing(InteractionHand.MAIN_HAND);
            recastCooldown = Humanizer.delay(recastTicks.get());
            return;
        }

        // Reel scheduled: fire once the reaction delay elapses.
        if (reelAtMs > 0) {
            if (System.currentTimeMillis() >= reelAtMs && ActionPacer.tryAction()) {
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                mc.player.swing(InteractionHand.MAIN_HAND);
                reelAtMs = 0;
                recastCooldown = Humanizer.delay(recastTicks.get());
            }
            return;
        }

        // Bite detection: the bobber drops sharply after floating (pure logic in BiteDetect).
        double dy = hook.getDeltaMovement().y;
        boolean bite = !hook.onGround() && com.autism.seedcracker.util.pure.BiteDetect.isBite(
            dy, lastHookDy, hook.getDeltaMovement().horizontalDistanceSqr());
        lastHookDy = dy;
        if (bite) {
            long base = reactionMs.get();
            long jitter = (long) (base * 0.35);
            reelAtMs = System.currentTimeMillis() + base
                + ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
        }
    }

    @Override
    public String info() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return "";
        ItemStack held = mc.player.getMainHandItem();
        if (!(held.getItem() instanceof FishingRodItem)) return "no rod";
        if (antiBreak.get() && held.getMaxDamage() > 0
            && held.getMaxDamage() - held.getDamageValue() < 5) return "rod low";
        if (reelAtMs > 0) return "reeling";
        return mc.player.fishing != null ? "waiting" : "casting";
    }
}
