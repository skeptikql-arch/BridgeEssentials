package com.skepi.bridgeessentials;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

/**
 * Renders text with the bundled Inter TrueType font. Each distinct string is rasterised once at
 * the exact pixel size it is shown at (GUI units x GUI scale) and cached as a texture, so text is
 * crisp at every GUI scale. Falls back to the vanilla font if the TTF cannot be loaded.
 */
final class BridgeFont {
    private static final int PAD = 2;
    private static final float ASCENT_EM = 0.96875F;
    private static final float VISUAL_CENTER_EM = 0.36F;
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    private static final class Entry {
        private DynamicTexture texture;
        private int widthPx;
        private int heightPx;
    }

    private static final Map<String, Entry> CACHE = new HashMap<String, Entry>();
    private static final Map<String, Font> FONTS = new HashMap<String, Font>();
    private static Font regular;
    private static Font semibold;
    private static boolean loaded;
    private static boolean failed;
    private static int scale = 1;

    private BridgeFont() {
    }

    /** Call once per frame with the current GUI scale factor. */
    static void beginFrame(int guiScale) {
        load();
        if (guiScale != scale) {
            clear();
            scale = Math.max(1, guiScale);
        }
    }

    /** Frees all cached textures. */
    static void clear() {
        for (Entry entry : CACHE.values()) {
            if (entry.texture != null) {
                entry.texture.deleteGlTexture();
            }
        }
        CACHE.clear();
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            regular = readFont("fonts/Inter-Regular.ttf");
            semibold = readFont("fonts/Inter-SemiBold.ttf");
        } catch (Throwable t) {
            failed = true;
        }
    }

    private static Font readFont(String path) throws Exception {
        InputStream stream = Minecraft.getMinecraft().getResourceManager()
                .getResource(new ResourceLocation(BridgeEssentials.MODID, path)).getInputStream();
        try {
            return Font.createFont(Font.TRUETYPE_FONT, stream);
        } finally {
            stream.close();
        }
    }

    private static int pixelSize(float size) {
        return Math.max(6, Math.round(size * scale));
    }

    private static Font font(int px, boolean bold) {
        String key = (bold ? "b" : "r") + px;
        Font font = FONTS.get(key);
        if (font == null) {
            font = (bold ? semibold : regular).deriveFont((float) px);
            FONTS.put(key, font);
        }
        return font;
    }

    /** Text width in GUI units. */
    static float width(String text, float size, boolean bold) {
        if (text == null || text.length() == 0) {
            return 0.0F;
        }
        if (failed) {
            return Minecraft.getMinecraft().fontRendererObj.getStringWidth(text) * size / 8.0F;
        }
        return (float) font(pixelSize(size), bold).getStringBounds(text, FRC).getWidth() / scale;
    }

    /** Draws text whose visual vertical centre sits on centerY. */
    static void drawMiddle(String text, float x, float centerY, float size, int argb, boolean bold) {
        draw(text, x, centerY + VISUAL_CENTER_EM * size - ASCENT_EM * size, size, argb, bold);
    }

    /** Draws text with the top of its line box at y. Size is the em height in GUI units. */
    static void draw(String text, float x, float y, float size, int argb, boolean bold) {
        if (text == null || text.length() == 0 || (argb >>> 24) < 4) {
            return;
        }
        if (failed) {
            drawFallback(text, x, y, size, argb);
            return;
        }
        Entry entry = entry(text, pixelSize(size), bold);
        if (entry == null) {
            drawFallback(text, x, y, size, argb);
            return;
        }
        BridgeRender.beginAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.bindTexture(entry.texture.getGlTextureId());
        BridgeRender.applyColor(argb);
        float pad = PAD / (float) scale;
        BridgeRender.texturedQuad(x - pad, y - pad,
                entry.widthPx / (float) scale, entry.heightPx / (float) scale);
        BridgeRender.resetColor();
    }

    private static void drawFallback(String text, float x, float y, float size, int argb) {
        float factor = size / 8.0F;
        BridgeRender.beginAlpha();
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0.0F);
        GlStateManager.scale(factor, factor, 1.0F);
        Minecraft.getMinecraft().fontRendererObj.drawString(text, 0, 0, argb, false);
        GlStateManager.popMatrix();
        BridgeRender.resetColor();
    }

    private static Entry entry(String text, int px, boolean bold) {
        String key = (bold ? "b" : "r") + px + "|" + text;
        Entry entry = CACHE.get(key);
        if (entry != null) {
            return entry;
        }
        try {
            if (CACHE.size() > 256) {
                clear();
            }
            Font font = font(px, bold);
            LineMetrics metrics = font.getLineMetrics(text, FRC);
            double advance = font.getStringBounds(text, FRC).getWidth();
            int w = (int) Math.ceil(advance) + PAD * 2;
            int h = (int) Math.ceil(metrics.getAscent() + metrics.getDescent()) + PAD * 2;

            BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                    RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setFont(font);
            g.setColor(Color.WHITE);
            g.drawString(text, PAD, PAD + metrics.getAscent());
            g.dispose();

            entry = new Entry();
            entry.widthPx = w;
            entry.heightPx = h;
            entry.texture = new DynamicTexture(image);
            GlStateManager.bindTexture(entry.texture.getGlTextureId());
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            CACHE.put(key, entry);
            return entry;
        } catch (Throwable t) {
            failed = true;
            return null;
        }
    }
}
