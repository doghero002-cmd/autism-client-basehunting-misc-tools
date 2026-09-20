package com.autism.seedcracker.modules;

import java.util.HashSet;
import java.util.Set;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.render.BlockEspRenderer;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Eye Finder (MeteorPlus EyeFinder port).
 *
 * Raycasts along every OTHER player's look direction and highlights the block each one is
 * looking at. Great intel: players stare at their base entrance, their stash, the block they're
 * about to mine - following their gaze tells you what they care about.
 */
public final class EyeFinderModule extends Module {

    private final IntSetting range = add(new IntSetting("range", "Ray range", 64, 8, 256, 8)
        .description("Max raycast distance along each player's view line.").group("General"));
    private final ColorSetting color = add(new ColorSetting("color", "Target colour", 0xC0FF2080)
        .description("Colour of the looked-at block markers.").group("Render"));
    private final BoolSetting tracers = add(new BoolSetting("tracer", "Tracer", true)
        .description("Tracer line to each looked-at block.").group("Render"));
    private final BoolSetting chatOnStare = add(new BoolSetting("chat-on-stare", "Chat on long stare", false)
        .description("Chat ping when a player stares at the same block for 5+ seconds (a base entrance?).").group("General"));

    private final java.util.Map<String, BlockPos> lastTarget = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> stareTicks = new java.util.HashMap<>();

    public EyeFinderModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":eye-finder", "Eye Finder", category,
            "Highlights the block every other player is looking at (gaze intel).");
    }

    @Override
    public void onDisable() {
        BlockEspRenderer.clear(id());
        lastTarget.clear();
        stareTicks.clear();
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        Set<BlockPos> targets = new HashSet<>();
        for (Player p : mc.level.players()) {
            if (p == mc.player || p.isSpectator()) continue;

            Vec3 eye = p.getEyePosition();
            Vec3 look = p.getViewVector(1.0f);
            Vec3 end = eye.add(look.scale(range.get()));
            BlockHitResult hit = mc.level.clip(new ClipContext(eye, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
            if (hit.getType() != HitResult.Type.BLOCK) {
                stareTicks.remove(p.getPlainTextName());
                continue;
            }

            BlockPos pos = hit.getBlockPos();
            targets.add(pos);

            if (chatOnStare.get()) {
                String name = p.getPlainTextName();
                BlockPos prev = lastTarget.put(name, pos);
                if (pos.equals(prev)) {
                    int t = stareTicks.merge(name, 1, Integer::sum);
                    if (t == 100) { // 5s
                        AutismClientMessaging.sendPrefixed("§d[EyeFinder] §f" + name
                            + " has stared at " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                            + " for 5s.");
                    }
                } else {
                    stareTicks.put(name, 0);
                }
            }
        }

        BlockEspRenderer.feed(id(), targets, color.get(), tracers.get(), true);
    }
}
