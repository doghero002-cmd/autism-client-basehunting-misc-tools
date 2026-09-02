package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.DoubleSetting;
import autismclient.modules.Module;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/**
 * Free Look.
 *
 * Lets you look around freely without changing your movement direction. While enabled your view
 * is switched to third person and mouse movement rotates an independent camera yaw/pitch instead
 * of your player, so you keep walking in the original direction. On disable your previous camera
 * perspective is restored.
 *
 * Clean-room port of the obfuscated Zelith "FreeLook" module.
 */
public final class FreeLookModule extends Module {

    private final DoubleSetting sensitivity = add(new DoubleSetting("sensitivity", "Sensitivity", 1.0, 0.1, 3.0, 0.1)
        .description("How fast the free-look camera rotates.")
        .group("General"));
    private final BoolSetting invertY = add(new BoolSetting("invert-y", "Invert Y", false)
        .description("Invert vertical mouse movement.")
        .group("General"));
    private final BoolSetting frontView = add(new BoolSetting("front-view", "Front view", false)
        .description("Look from the front of your player instead of behind.")
        .group("General"));
    private final BoolSetting noPitchLimit = add(new BoolSetting("no-pitch-limit", "No pitch limit", false)
        .description("Allow the camera to look straight up/down past 90 degrees.")
        .group("General"));

    private float cameraYaw;
    private float cameraPitch;
    private CameraType previousPerspective;

    public FreeLookModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-free-look", "Free Look", category,
            "Look around freely without changing your movement direction.");
    }

    @Override
    public void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            cameraYaw = mc.player.getYRot();
            cameraPitch = mc.player.getXRot();
        }
        if (mc.options != null) {
            previousPerspective = mc.options.getCameraType();
            mc.options.setCameraType(desiredPerspective());
        }
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null && previousPerspective != null) {
            mc.options.setCameraType(previousPerspective);
        }
        previousPerspective = null;
    }

    @Override
    public void onGameLeft() {
        if (isEnabled()) {
            setEnabledSilently(false);
        }
    }

    @Override
    public void preMovementTick() {
        // Keep the forced perspective if something else resets it while active.
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null && mc.options.getCameraType() != desiredPerspective()) {
            mc.options.setCameraType(desiredPerspective());
        }
    }

    @Override
    public void onMouseRotation(double deltaYaw, double deltaPitch) {
        float sens = (float) (decimal("sensitivity") * 0.15);
        float yMul = invertY.get() ? -1.0f : 1.0f;
        cameraYaw = Mth.wrapDegrees(cameraYaw + (float) deltaYaw * sens);
        cameraPitch = cameraPitch + (float) deltaPitch * sens * yMul;
        if (!noPitchLimit.get()) {
            cameraPitch = Mth.clamp(cameraPitch, -90.0f, 90.0f);
        }
    }

    private CameraType desiredPerspective() {
        return frontView.get() ? CameraType.THIRD_PERSON_FRONT : CameraType.THIRD_PERSON_BACK;
    }

    /** Current free-look camera yaw, used by the renderer hook. */
    public float cameraYaw() {
        return cameraYaw;
    }

    /** Current free-look camera pitch, used by the renderer hook. */
    public float cameraPitch() {
        return cameraPitch;
    }
}
