package com.autism.seedcracker.compat;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/**
 * Obfuscation-proof ESP render types + line helper. The client's AutismRenderTypes
 * (storageEspLinesSeeThrough / storageEspFillSeeThrough) and AutismWorldGeometry.line were renamed
 * in later (obfuscated) builds. These are self-contained equivalents built purely on the stable
 * net.minecraft renderer API (same construction NoDepthLayer already uses): a see-through LINES
 * type, a see-through QUADS fill type, and a world-space line emitter. All "always pass" depth so
 * ESP shows through walls, matching the original see-through behaviour.
 */
public final class EspRenderTypes {
    private EspRenderTypes() {}

    private static final RenderPipeline LINES_PIPELINE = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("seedcrackerx", "pipeline/esp_lines_see_through"))
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false, 0, 0))
            .build()
    );

    private static final RenderPipeline QUADS_PIPELINE = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("seedcrackerx", "pipeline/esp_fill_see_through"))
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false, 0, 0))
            .build()
    );

    /** See-through line render type (was AutismRenderTypes.storageEspLinesSeeThrough). */
    public static final RenderType LINES_SEE_THROUGH = RenderType.create("seedcrackerx_esp_lines",
        RenderSetup.builder(LINES_PIPELINE)
            .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
            .setOutputTarget(OutputTarget.ITEM_ENTITY_TARGET)
            .createRenderSetup()
    );

    /** See-through quad fill render type (was AutismRenderTypes.storageEspFillSeeThrough). */
    public static final RenderType FILL_SEE_THROUGH = RenderType.create("seedcrackerx_esp_fill",
        RenderSetup.builder(QUADS_PIPELINE)
            .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
            .setOutputTarget(OutputTarget.ITEM_ENTITY_TARGET)
            .createRenderSetup()
    );

    /** Emit a world-space line into a LINES buffer (was AutismWorldGeometry.line). The LINES
     * pipeline requires position + color + normal + lineWidth on every vertex - omitting any of
     * them crashes with "Missing elements in vertex". The normal is the line's direction. */
    public static void line(PoseStack.Pose pose, VertexConsumer buffer,
                            double x1, double y1, double z1, double x2, double y2, double z2,
                            int color, float width) {
        float nx = (float) (x2 - x1), ny = (float) (y2 - y1), nz = (float) (z2 - z1);
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 1.0e-6) { nx /= len; ny /= len; nz /= len; } else { nx = 0; ny = 1; nz = 0; }
        final float fnx = nx, fny = ny, fnz = nz;
        org.joml.Vector3f normal = new org.joml.Vector3f(fnx, fny, fnz);
        buffer.addVertex(pose, (float) x1, (float) y1, (float) z1).setColor(color).setNormal(pose, normal).setLineWidth(width);
        buffer.addVertex(pose, (float) x2, (float) y2, (float) z2).setColor(color).setNormal(pose, normal).setLineWidth(width);
    }
}
