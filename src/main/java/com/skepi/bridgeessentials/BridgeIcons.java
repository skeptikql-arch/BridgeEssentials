package com.skepi.bridgeessentials;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

/** Loads the white-on-transparent PNG icons from assets and draws them tinted. */
final class BridgeIcons {
    private static final Map<String, DynamicTexture> TEXTURES = new HashMap<String, DynamicTexture>();

    private BridgeIcons() {
    }

    /** Draws icon `name` (textures/gui/name.png) as a size x size square tinted by argb. */
    static void draw(String name, float x, float y, float size, int argb) {
        if ((argb >>> 24) < 4) {
            return;
        }
        DynamicTexture texture = TEXTURES.get(name);
        if (texture == null) {
            texture = load(name);
            if (texture == null) {
                return;
            }
        }
        BridgeRender.beginAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.bindTexture(texture.getGlTextureId());
        BridgeRender.applyColor(argb);
        BridgeRender.texturedQuad(x, y, size, size);
        BridgeRender.resetColor();
    }

    private static DynamicTexture load(String name) {
        try {
            InputStream stream = Minecraft.getMinecraft().getResourceManager().getResource(
                    new ResourceLocation(BridgeEssentials.MODID, "textures/gui/" + name + ".png"))
                    .getInputStream();
            BufferedImage image;
            try {
                image = ImageIO.read(stream);
            } finally {
                stream.close();
            }
            DynamicTexture texture = new DynamicTexture(image);
            GlStateManager.bindTexture(texture.getGlTextureId());
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            TEXTURES.put(name, texture);
            return texture;
        } catch (Throwable t) {
            return null;
        }
    }

    static void clear() {
        for (DynamicTexture texture : TEXTURES.values()) {
            texture.deleteGlTexture();
        }
        TEXTURES.clear();
    }
}
