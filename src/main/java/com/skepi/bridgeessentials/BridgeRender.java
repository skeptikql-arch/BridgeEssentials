package com.skepi.bridgeessentials;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;

/** Small immediate-mode drawing helpers shared by the settings screen. */
final class BridgeRender {
    private BridgeRender() {
    }

    static void beginAlpha() {
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
    }

    static void applyColor(int argb) {
        GlStateManager.color(
                (argb >> 16 & 0xFF) / 255.0F,
                (argb >> 8 & 0xFF) / 255.0F,
                (argb & 0xFF) / 255.0F,
                (argb >>> 24) / 255.0F);
    }

    static void resetColor() {
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * Filled rounded rectangle from a triangle fan, so corners stay smooth at any GUI scale.
     * Vertices run counter-clockwise on screen like vanilla quads, and culling is switched off
     * for the draw so the shape can never be discarded as back-facing.
     */
    static void roundedRect(float x, float y, float w, float h, float radius, int argb) {
        if ((argb >>> 24) == 0 || w <= 0 || h <= 0) {
            return;
        }
        float r = Math.max(0.0F, Math.min(radius, Math.min(w, h) / 2.0F));
        beginAlpha();
        GlStateManager.disableTexture2D();
        GlStateManager.disableCull();
        applyColor(argb);

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        buffer.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION);
        buffer.pos(x + w / 2.0F, y + h / 2.0F, 0).endVertex();

        final int segments = 10;
        // Start angle of each corner arc; arcs sweep -90 degrees (counter-clockwise on screen).
        float[][] corners = {
                {x + w - r, y + r, 0.0F},        // top-right
                {x + r, y + r, -90.0F},          // top-left
                {x + r, y + h - r, -180.0F},     // bottom-left
                {x + w - r, y + h - r, -270.0F}  // bottom-right
        };
        for (float[] corner : corners) {
            for (int s = 0; s <= segments; s++) {
                double angle = Math.toRadians(corner[2] - 90.0D * s / segments);
                buffer.pos(corner[0] + Math.cos(angle) * r, corner[1] + Math.sin(angle) * r, 0)
                        .endVertex();
            }
        }
        buffer.pos(corners[0][0] + r, corners[0][1], 0).endVertex();
        tessellator.draw();

        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
        resetColor();
    }


    /** Filled triangle (a chevron / caret) centred on cx, cy; angleDeg 0 points down, 180 points up. */
    static void caret(float cx, float cy, float size, float angleDeg, int argb) {
        if ((argb >>> 24) == 0) {
            return;
        }
        beginAlpha();
        GlStateManager.disableTexture2D();
        GlStateManager.disableCull();
        applyColor(argb);
        double a = Math.toRadians(angleDeg);
        double cos = Math.cos(a);
        double sin = Math.sin(a);
        // Down-pointing triangle, rotated around its centre.
        float[][] pts = {{-size, -size * 0.55F}, {size, -size * 0.55F}, {0, size * 0.65F}};
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        buffer.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION);
        // Counter-clockwise on screen after rotation (rotation preserves winding).
        int[] order = {0, 2, 1};
        for (int i : order) {
            double x = pts[i][0] * cos - pts[i][1] * sin;
            double y = pts[i][0] * sin + pts[i][1] * cos;
            buffer.pos(cx + x, cy + y, 0).endVertex();
        }
        tessellator.draw();
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
        resetColor();
    }

    /** Draws the currently bound texture over the given rectangle, tinted by the current colour. */
    static void texturedQuad(float x, float y, float w, float h) {
        GlStateManager.disableCull();
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        buffer.pos(x, y + h, 0).tex(0.0D, 1.0D).endVertex();
        buffer.pos(x + w, y + h, 0).tex(1.0D, 1.0D).endVertex();
        buffer.pos(x + w, y, 0).tex(1.0D, 0.0D).endVertex();
        buffer.pos(x, y, 0).tex(0.0D, 0.0D).endVertex();
        tessellator.draw();
        GlStateManager.enableCull();
    }

    static float easeOutCubic(float t) {
        float inv = 1.0F - t;
        return 1.0F - inv * inv * inv;
    }

    static int withAlpha(int argb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (argb & 0x00FFFFFF);
    }

    static int scaleAlpha(int argb, float factor) {
        return withAlpha(argb, Math.round((argb >>> 24) * factor));
    }

    static int lerpColor(int from, int to, float t) {
        int a = lerp(from >>> 24, to >>> 24, t);
        int r = lerp(from >> 16 & 0xFF, to >> 16 & 0xFF, t);
        int g = lerp(from >> 8 & 0xFF, to >> 8 & 0xFF, t);
        int b = lerp(from & 0xFF, to & 0xFF, t);
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static int lerp(int a, int b, float t) {
        return Math.round(a + (b - a) * Math.max(0.0F, Math.min(1.0F, t)));
    }
}
