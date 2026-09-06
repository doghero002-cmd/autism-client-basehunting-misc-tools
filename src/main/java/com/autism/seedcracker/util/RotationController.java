package com.autism.seedcracker.util;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

import java.util.Random;

/**
 * Human-like smooth rotation controller.
 *
 * Instead of snapping the player's view instantly to a target yaw/pitch (the classic head-flick
 * that anti-cheats flag), this eases toward the target each tick with:
 *  - an acceleration curve (slow start, faster mid-way, slow-down on approach),
 *  - distance-based speed scaling (faster for big turns, gentler for small adjustments),
 *  - human jitter + speed variation so the motion isn't perfectly linear,
 *  - occasional micro-pauses to mimic a real hand,
 *  - precise-landing clamping so it doesn't oscillate around the target.
 *
 * Call {@link #rotateTo(float, float)} once with the target, then {@link #update()} every tick
 * until {@link #isRotating()} is false.
 *
 * Port of the Krypton AI RotationController (dev.FORE.AI) to Mojang 26.2 mappings.
 */
public final class RotationController {
    private final Minecraft mc = Minecraft.getInstance();
    private final Random random = new Random();

    private float currentYaw;
    private float targetYaw;
    private float currentPitch;
    private float targetPitch;
    private float currentYawSpeed;
    private float currentPitchSpeed;
    private boolean isRotating = false;
    private Runnable callback;

    // Tunables (human-like defaults).
    private boolean smoothRotation = true;
    private double baseSpeed = 4.5;
    private double acceleration = 0.8;
    private boolean humanLike = true;
    private double overshootChance = 0.3;
    private boolean preciseLanding = true;
    private double randomVariation = 0.0;
    private double effectiveSpeed;
    private double effectiveAcceleration;

    /** Configure the rotation behaviour. */
    public void settings(boolean smooth, double speed, double accel, boolean human, double overshoot) {
        this.smoothRotation = smooth;
        this.baseSpeed = speed;
        this.acceleration = accel;
        this.humanLike = human;
        this.overshootChance = overshoot;
    }

    public void setRandomVariation(double variation) {
        this.randomVariation = variation;
    }

    public void setPreciseLanding(boolean precise) {
        this.preciseLanding = precise;
    }

    /** Start rotating toward a yaw (pitch unchanged). */
    public void rotateTo(float yaw) {
        rotateTo(yaw, mc.player != null ? mc.player.getXRot() : 0.0f, null);
    }

    /** Start rotating toward a yaw + pitch, with an optional completion callback. */
    public void rotateTo(float yaw, float pitch, Runnable onComplete) {
        this.targetYaw = yaw;
        this.targetPitch = pitch;
        this.callback = onComplete;
        calculateEffectiveValues();
        if (!smoothRotation || mc.player == null) {
            setYaw(yaw);
            setPitch(pitch);
            if (callback != null) callback.run();
            return;
        }
        this.isRotating = true;
        this.currentYaw = mc.player.getYRot();
        this.currentPitch = mc.player.getXRot();
        this.currentYawSpeed = 0.0f;
        this.currentPitchSpeed = 0.0f;
    }

    private void calculateEffectiveValues() {
        if (randomVariation <= 0.0) {
            this.effectiveSpeed = baseSpeed;
            this.effectiveAcceleration = acceleration;
        } else {
            double speedMult = random.nextDouble() * 2.0 - 1.0;
            double accelMult = random.nextDouble() * 2.0 - 1.0;
            this.effectiveSpeed = baseSpeed + speedMult * randomVariation;
            double accelRatio = acceleration / baseSpeed;
            this.effectiveAcceleration = acceleration + accelMult * (randomVariation * accelRatio);
            this.effectiveSpeed = Mth.clamp(effectiveSpeed, 0.5, baseSpeed * 2.0);
            this.effectiveAcceleration = Mth.clamp(effectiveAcceleration, 0.1, acceleration * 2.0);
        }
    }

    /** Advance the rotation one tick. Call every tick while active. */
    public void update() {
        if (!isRotating || mc.player == null) return;
        boolean yawDone = updateYaw();
        boolean pitchDone = updatePitch();
        if (yawDone && pitchDone) {
            isRotating = false;
            if (preciseLanding) {
                setYaw(targetYaw);
                setPitch(targetPitch);
            }
            if (callback != null) callback.run();
        }
    }

    private boolean updateYaw() {
        float delta = Mth.wrapDegrees(targetYaw - currentYaw);
        float distance = Math.abs(delta);
        float snap = preciseLanding ? 1.0f : 0.5f;
        if (distance < snap) {
            if (preciseLanding) {
                currentYaw = targetYaw;
                setYaw(targetYaw);
            }
            return true;
        }

        double speedToUse = effectiveSpeed;
        if (preciseLanding && distance < 5.0 && randomVariation > 0.0) {
            double blend = distance / 5.0;
            speedToUse = baseSpeed + (effectiveSpeed - baseSpeed) * blend;
        }

        float targetSpeed;
        if (distance > 45.0f) targetSpeed = (float) (speedToUse * 1.5);
        else if (distance > 15.0f) targetSpeed = (float) speedToUse;
        else {
            targetSpeed = (float) (speedToUse * (distance / 15.0));
            targetSpeed = Math.max(targetSpeed, preciseLanding ? 0.3f : 0.5f);
        }

        float accel = (float) effectiveAcceleration;
        currentYawSpeed = currentYawSpeed < targetSpeed
            ? Math.min(currentYawSpeed + accel, targetSpeed)
            : Math.max(currentYawSpeed - accel, targetSpeed);

        float jitter = 0.0f;
        float speedVar = 1.0f;
        if (humanLike && (!preciseLanding || distance > 5.0f)) {
            jitter = (random.nextFloat() - 0.5f) * 0.2f;
            speedVar = 0.9f + random.nextFloat() * 0.2f;
            if (random.nextFloat() < 0.02 && distance > 10.0f) currentYawSpeed *= 0.3f; // micro-pause
        }

        float step = Math.min(distance, currentYawSpeed * speedVar);
        if (delta < 0.0f) step = -step;
        currentYaw += step + jitter;
        if (preciseLanding) {
            float newDelta = Mth.wrapDegrees(targetYaw - currentYaw);
            if (Math.signum(newDelta) != Math.signum(delta)) currentYaw = targetYaw;
        }
        setYaw(currentYaw);
        return false;
    }

    private boolean updatePitch() {
        float delta = targetPitch - currentPitch;
        float distance = Math.abs(delta);
        float snap = preciseLanding ? 1.0f : 0.5f;
        if (distance < snap) {
            if (preciseLanding) {
                currentPitch = targetPitch;
                setPitch(targetPitch);
            }
            return true;
        }

        double speedToUse = effectiveSpeed;
        if (preciseLanding && distance < 3.0 && randomVariation > 0.0) {
            double blend = distance / 3.0;
            speedToUse = baseSpeed + (effectiveSpeed - baseSpeed) * blend;
        }

        float targetSpeed;
        if (distance > 30.0f) targetSpeed = (float) (speedToUse * 1.2);
        else if (distance > 10.0f) targetSpeed = (float) (speedToUse * 0.8);
        else {
            targetSpeed = (float) (speedToUse * 0.8 * (distance / 10.0));
            targetSpeed = Math.max(targetSpeed, preciseLanding ? 0.25f : 0.4f);
        }

        float accel = (float) (effectiveAcceleration * 0.8);
        currentPitchSpeed = currentPitchSpeed < targetSpeed
            ? Math.min(currentPitchSpeed + accel, targetSpeed)
            : Math.max(currentPitchSpeed - accel, targetSpeed);

        float jitter = 0.0f;
        float speedVar = 1.0f;
        if (humanLike && (!preciseLanding || distance > 3.0f)) {
            jitter = (random.nextFloat() - 0.5f) * 0.15f;
            speedVar = 0.92f + random.nextFloat() * 0.16f;
        }

        float step = Math.min(distance, currentPitchSpeed * speedVar);
        if (delta < 0.0f) step = -step;
        currentPitch += step + jitter;
        if (preciseLanding) {
            float newDelta = targetPitch - currentPitch;
            if (Math.signum(newDelta) != Math.signum(delta)) currentPitch = targetPitch;
        }
        setPitch(currentPitch);
        return false;
    }

    private void setYaw(float yaw) {
        if (mc.player == null) return;
        mc.player.setYRot(yaw);
        mc.player.yRotO = yaw;
        mc.player.yHeadRot = yaw;
        mc.player.yHeadRotO = yaw;
    }

    private void setPitch(float pitch) {
        if (mc.player == null) return;
        mc.player.setXRot(Mth.clamp(pitch, -90.0f, 90.0f));
    }

    public boolean isRotating() {
        return isRotating;
    }

    public float getTargetYaw() {
        return targetYaw;
    }

    public float getTargetPitch() {
        return targetPitch;
    }

    /** Smoothly return pitch to level (0) while keeping yaw. */
    public void resetPitch(Runnable onComplete) {
        if (mc.player != null && Math.abs(mc.player.getXRot()) > 1.0f) {
            rotateTo(mc.player.getYRot(), 0.0f, onComplete);
        } else {
            setPitch(0.0f);
            if (onComplete != null) onComplete.run();
        }
    }
}
