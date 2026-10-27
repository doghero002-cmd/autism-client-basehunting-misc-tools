// SPDX-License-Identifier: GPL-3.0-only
// Light colour interpolation adapted from Spectre LightEspModule, d5a7dd3 (Cody / cody-raves).
package com.autism.seedcracker.spectre.activity;

import com.autism.seedcracker.render.BlockEspRenderer;
import java.util.List;
import net.minecraft.world.phys.AABB;

public final class ActivityRender {
    private ActivityRender() {}

    public static void feed(String id, List<AABB> boxes, int line, int fill) {
        if (boxes.isEmpty()) { clear(id); return; }
        // The shared collector couples line/fill RGB; two private streams preserve both source colours.
        BlockEspRenderer.feedBoxes(id + ":fill", boxes, fill & 0x00ffffff, fill >>> 24);
        BlockEspRenderer.feedBoxes(id + ":line", boxes, line, 0);
    }

    public static void clear(String id) {
        BlockEspRenderer.clear(id + ":fill");
        BlockEspRenderer.clear(id + ":line");
    }

    public static int lightFill(int base, int light) {
        float t = Math.clamp(light / 15.0f, 0.0f, 1.0f);
        int r = lerp((base >>> 16) & 255, t);
        int g = lerp((base >>> 8) & 255, t);
        int b = lerp(base & 255, t);
        int a = Math.round((base >>> 24) * (0.5f + 0.5f * t));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int lightLine(int fill) {
        return 0xff000000 | (Math.round(((fill >>> 16) & 255) * 0.45f) << 16)
            | (Math.round(((fill >>> 8) & 255) * 0.45f) << 8) | Math.round((fill & 255) * 0.45f);
    }

    private static int lerp(int value, float t) { return Math.round(value + (255 - value) * t); }
}
