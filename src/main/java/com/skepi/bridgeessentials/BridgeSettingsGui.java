package com.skepi.bridgeessentials;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.shader.Shader;
import net.minecraft.client.shader.ShaderGroup;
import net.minecraft.client.shader.ShaderUniform;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/**
 * Settings menu. Contains no vanilla buttons: every widget (cards, switches, keybind chip, close
 * icon) is drawn and hit-tested by hand with the bundled font and PNG icons. The world behind it
 * is blurred with the vanilla blur post-shader.
 */
public class BridgeSettingsGui extends GuiScreen {
    // Palette (ARGB).
    private static final int ACCENT = 0xFF3BD6E4;
    private static final int DANGER = 0xFFFF5364;
    private static final int PANEL_BORDER = 0xFF2A3446;
    private static final int PANEL_FILL = 0xF2111826;
    private static final int CARD_FILL = 0xFF182131;
    private static final int CARD_HOVER = 0xFF1F2A3F;
    private static final int CHIP_FILL = 0xFF222D42;
    private static final int SWITCH_OFF = 0xFF2C3649;
    private static final int TEXT_PRIMARY = 0xFFEAF0FA;
    private static final int TEXT_MUTED = 0xFF8593A8;
    private static final int ICON_OFF = 0xFF6F7D93;
    private static final int DISABLED_ON = 0xFF566074;

    // Layout, in GUI units before fit-scaling. Two columns: HUD switches on the left, overlay
    // size / position and the keybind on the right.
    private static final int BEST_W = 230;
    private static final int LEFT_W = 280;
    private static final int RIGHT_W = 250;
    private static final int COL_GAP = 12;
    private static final int PANEL_W = 12 + BEST_W + COL_GAP + LEFT_W + COL_GAP + RIGHT_W + 12;
    private static final int HEADER_H = 52;
    private static final int PAD = 12;
    private static final int ROW_H = 38;
    private static final int ROW_GAP = 5;
    private static final int SUB_H = 26;
    private static final int SUB_GAP = 3;
    private static final int SUB_INDENT = 16;
    private static final int SECTION_GAP = 10;
    private static final int SECTION_LABEL_H = 12;
    private static final int RESET_H = 24;
    private static final int PRESET_H = 24;
    private static final int PRESET_GAP = 8;
    /** Presets block above the switches: label + chips + gap, before the DISPLAY label. */
    private static final int LEFT_TOP = SECTION_LABEL_H + PRESET_H + SECTION_GAP;
    private static final float EXPAND_MS = 300.0F;
    private static final int FOOTER_H = 26;
    private static final int SWITCH_W = 32;
    private static final int SWITCH_H = 16;
    private static final int SUB_SWITCH_W = 26;
    private static final int SUB_SWITCH_H = 14;
    private static final int ICON_CHIP = 26;

    private static final BridgeConfig.Setting[] SETTINGS = BridgeConfig.Setting.values();

    // Row heights for the left column. LEFT_ROWS_H is the height with the Taps & Flats dropdown
    // fully open; CHILD_BLOCK_H is how much of that the dropdown accounts for.
    private static final float[] ROW_HEIGHT = new float[SETTINGS.length];
    private static final int LEFT_ROWS_H;
    private static final float CHILD_BLOCK_H;
    private static final int TAPS_INDEX = BridgeConfig.Setting.TAPS_FLATS.ordinal();

    static {
        float y = 0;
        float childBlock = 0;
        for (int i = 0; i < SETTINGS.length; i++) {
            boolean child = SETTINGS[i].parent != null;
            float gap = i > 0 ? (child ? SUB_GAP : ROW_GAP) : 0;
            ROW_HEIGHT[i] = child ? SUB_H : ROW_H;
            y += gap + ROW_HEIGHT[i];
            if (child) {
                childBlock += gap + ROW_HEIGHT[i];
            }
        }
        LEFT_ROWS_H = Math.round(y);
        CHILD_BLOCK_H = childBlock;
    }

    private static final BridgeConfig.Preset[] PRESETS = BridgeConfig.Preset.values();

    // Overlay sliders (right column).
    private static final String[] SLIDER_TITLES = {"Overlay size", "Background opacity"};
    private static final int[] SLIDER_MIN = {BridgeConfig.SCALE_MIN, 0};
    private static final int[] SLIDER_MAX = {BridgeConfig.SCALE_MAX, 100};
    private static final int[] SLIDER_STEP = {5, 1};
    private static final int SLIDERS = SLIDER_TITLES.length;

    // Position picker: five chips, laid out like the screen (bottom row has no middle chip).
    private static final BridgeConfig.Position[] POSITIONS = BridgeConfig.Position.values();
    private static final int POS_H = 71;
    private static final int POS_CHIP_H = 18;
    private static final int POS_CHIP_GAP = 6;

    // "Best coords" column (far left): 0 Best [tap/held] Gain, 1 Best Turnout, 2 Highest [tap/held]
    // Coordinate, 3 Best [#] [tap/held] after [timing] Flat, 4 Best [#]th [tap/held] after [time]
    // [5b Flat/4b Speed], 5 Best [time] [5b Flat/4b Speed], 6 Playtime, 7 Attempts.
    private static final int[] BEST_HEIGHTS = {48, 40, 48, 52, 78, 52, 40, 40};
    private static final int BEST_BOX_H = 18;
    private static final int BEST_RESET_H = 22;
    private static final int BEST_CARDS = BEST_HEIGHTS.length;
    private static final int BEST_H = SECTION_LABEL_H + 48 + 40 + 48 + 52 + 78 + 52 + 40 + 40
            + (BEST_CARDS - 1) * ROW_GAP + 8 + BEST_RESET_H;
    private static final int GOOD = 0xFF72F29A;
    private static final int BAD = 0xFFFF6677;
    private static final float RESET_CONFIRM_MS = 3000.0F;

    private static final int LEFT_H = LEFT_TOP + SECTION_LABEL_H + LEFT_ROWS_H;
    private static final int RIGHT_H = SECTION_LABEL_H + SLIDERS * (ROW_H + ROW_GAP)
            + POS_H + ROW_GAP + (ROW_H + ROW_GAP) + RESET_H
            + SECTION_GAP + SECTION_LABEL_H + 2 * ROW_H + ROW_GAP
            + SECTION_GAP + SECTION_LABEL_H + ROW_H;
    /** Tallest the panel gets (dropdown open); the real height animates between this and the shorter one. */
    private static final int PANEL_H_MAX = HEADER_H + Math.max(Math.max(LEFT_H, RIGHT_H), BEST_H)
            + PAD + FOOTER_H;

    private static final float OPEN_MS = 240.0F;
    private static final float BLUR_RADIUS = 7.0F;

    private final float[] switchAnim = new float[SETTINGS.length];
    private final float[] hoverAnim = new float[SETTINGS.length];
    private final float[] sliderHover = new float[SLIDERS];
    private final float[] positionHover = new float[POSITIONS.length];
    private final float[] positionAnim = new float[POSITIONS.length];
    private final float[] presetHover = new float[PRESETS.length];
    private final float[] presetAnim = new float[PRESETS.length];
    // Taps & Flats dropdown: opened / closed by right-clicking its row, collapsed by default.
    private boolean tapsExpanded;
    private float expandRaw;
    // Transform used for the current frame, needed to clip the dropdown with a GL scissor.
    private float drawScale = 1.0F;
    private float drawYOffset;
    private int guiScaleFactor = 2;
    private float resetHover;
    private float dividerHover;
    private float dividerAnim;
    private float keybindHover;
    private float closeHover;
    private final float[] bestChipHover = new float[6];
    private float bestResetHover;
    private long bestResetArmedUntil;
    private int draggingSlider = -1;
    // Text boxes: 0 = plate end coordinate, 1 = jump plate height, 2 = best "# after" count,
    // 3 = best "after # Flat" timing, 4 = the time / ticks of the "#th after" 5b Flat / 4b Speed
    // best, 5 = its "#th" number, 6 = the time / ticks of the "Best [time] 5b Flat / 4b Speed" best.
    private int focusedBox = -1;
    private String boxText = "";
    private boolean listeningForKey;
    private long openedAt;
    private long lastFrame;
    private boolean initialized;

    private ShaderGroup blurGroup;
    private List<Shader> blurShaders;

    @Override
    public void initGui() {
        if (!initialized) {
            initialized = true;
            openedAt = Minecraft.getSystemTime();
            lastFrame = openedAt;
            for (int i = 0; i < SETTINGS.length; i++) {
                switchAnim[i] = BridgeConfig.isEnabled(SETTINGS[i]) ? 1.0F : 0.0F;
            }
            dividerAnim = BridgeConfig.isDividersEnabled() ? 1.0F : 0.0F;
            for (int i = 0; i < PRESETS.length; i++) {
                presetAnim[i] = PRESETS[i].isApplied() ? 1.0F : 0.0F;
            }
            for (int i = 0; i < POSITIONS.length; i++) {
                positionAnim[i] = POSITIONS[i] == BridgeConfig.getOverlayPosition() ? 1.0F : 0.0F;
            }
        }
        startBlur();
    }

    @Override
    public void onGuiClosed() {
        commitBox();
        stopBlur();
        BridgeFont.clear();
        BridgeIcons.clear();
        BridgeConfig.save();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ------------------------------------------------------------------ blur

    @SuppressWarnings("unchecked")
    private void startBlur() {
        try {
            if (!OpenGlHelper.shadersSupported || mc.entityRenderer.isShaderActive()) {
                return;
            }
            mc.entityRenderer.loadShader(new ResourceLocation("shaders/post/blur.json"));
            blurGroup = mc.entityRenderer.getShaderGroup();
            if (blurGroup != null) {
                Field field = ReflectionHelper.findField(ShaderGroup.class,
                        "listShaders", "field_148031_d");
                blurShaders = (List<Shader>) field.get(blurGroup);
            }
            setBlurRadius(0.0F);
        } catch (Throwable ignored) {
            // Blur is cosmetic; the dim overlay still works without it.
        }
    }

    private void setBlurRadius(float radius) {
        if (blurShaders == null) {
            return;
        }
        try {
            for (Shader shader : blurShaders) {
                ShaderUniform uniform = shader.getShaderManager().getShaderUniform("Radius");
                if (uniform != null) {
                    uniform.set(radius);
                }
            }
        } catch (Throwable ignored) {
            blurShaders = null;
        }
    }

    private void stopBlur() {
        try {
            if (blurGroup != null && mc.entityRenderer.getShaderGroup() == blurGroup) {
                mc.entityRenderer.stopUseShader();
            }
        } catch (Throwable ignored) {
            // nothing to clean up
        }
        blurGroup = null;
        blurShaders = null;
    }

    // ---------------------------------------------------------------- layout

    private float fitScale() {
        float byHeight = (height - 16.0F) / PANEL_H_MAX;
        float byWidth = (width - 16.0F) / PANEL_W;
        return Math.max(0.5F, Math.min(1.0F, Math.min(byHeight, byWidth)));
    }

    /** Converts a raw mouse coordinate to panel space (undoing the fit-to-screen scale). */
    private float toLocalX(int mouseX) {
        return (mouseX - width / 2.0F) / fitScale() + width / 2.0F;
    }

    private float toLocalY(int mouseY) {
        return (mouseY - height / 2.0F) / fitScale() + height / 2.0F;
    }

    private float panelX() {
        return (width - PANEL_W) / 2.0F;
    }

    /** The panel's top stays put (centred for its tallest size); only its bottom edge animates. */
    private float panelY() {
        return (height - PANEL_H_MAX) / 2.0F;
    }

    /** Dropdown progress with ease-in-out applied, 0 = closed, 1 = fully open. */
    private float expandEase() {
        float t = expandRaw;
        return t * t * (3.0F - 2.0F * t);
    }

    /** Current panel height: follows the left column while the dropdown opens and closes. */
    private float panelH() {
        float left = LEFT_TOP + SECTION_LABEL_H + LEFT_ROWS_H - (1.0F - expandEase()) * CHILD_BLOCK_H;
        return HEADER_H + Math.max(Math.max(left, RIGHT_H), BEST_H) + PAD + FOOTER_H;
    }

    private float presetY() {
        return columnTop() + SECTION_LABEL_H;
    }

    private float displayLabelY() {
        return columnTop() + SECTION_LABEL_H + PRESET_H + SECTION_GAP;
    }

    private float presetW() {
        return (LEFT_W - PRESET_GAP) / 2.0F;
    }

    private float presetX(int index) {
        return leftX() + index * (presetW() + PRESET_GAP);
    }

    private int presetAt(float mx, float my) {
        float y = presetY();
        if (my < y || my > y + PRESET_H) {
            return -1;
        }
        for (int i = 0; i < PRESETS.length; i++) {
            if (mx >= presetX(i) && mx <= presetX(i) + presetW()) {
                return i;
            }
        }
        return -1;
    }

    /** Offset of a row below the first one; children are scaled by the given dropdown progress. */
    private float rowTopOffset(int index, float expand) {
        float y = 0;
        for (int i = 0; i <= index; i++) {
            boolean child = SETTINGS[i].parent != null;
            float scale = child ? expand : 1.0F;
            if (i > 0) {
                y += (child ? SUB_GAP : ROW_GAP) * scale;
            }
            if (i == index) {
                break;
            }
            y += ROW_HEIGHT[i] * scale;
        }
        return y;
    }

    private float columnTop() {
        return panelY() + HEADER_H;
    }

    private float bestX() {
        return panelX() + PAD;
    }

    private float leftX() {
        return bestX() + BEST_W + COL_GAP;
    }

    private float rightX() {
        return leftX() + LEFT_W + COL_GAP;
    }

    private float rowY(int index) {
        float base = displayLabelY() + SECTION_LABEL_H;
        float e = expandEase();
        if (SETTINGS[index].parent != null) {
            // Dropdown rows sit at their open position and slide down out of the Taps & Flats card.
            return base + rowTopOffset(index, 1.0F) - (1.0F - e) * CHILD_BLOCK_H;
        }
        return base + rowTopOffset(index, e);
    }

    private float sliderY(int index) {
        return columnTop() + SECTION_LABEL_H + index * (ROW_H + ROW_GAP);
    }

    private float positionY() {
        return columnTop() + SECTION_LABEL_H + SLIDERS * (ROW_H + ROW_GAP);
    }

    private float dividerRowY() {
        return positionY() + POS_H + ROW_GAP;
    }

    private float positionChipW() {
        return (RIGHT_W - 24 - POS_CHIP_GAP * 2) / 3.0F;
    }

    private float positionChipX(int index) {
        return rightX() + 12 + POSITIONS[index].column * (positionChipW() + POS_CHIP_GAP);
    }

    private float positionChipY(int index) {
        return positionY() + 23 + POSITIONS[index].row * (POS_CHIP_H + 5);
    }

    private int positionChipAt(float mx, float my) {
        for (int i = 0; i < POSITIONS.length; i++) {
            float x = positionChipX(i);
            float y = positionChipY(i);
            if (mx >= x && mx <= x + positionChipW() && my >= y && my <= y + POS_CHIP_H) {
                return i;
            }
        }
        return -1;
    }

    private float resetY() {
        return dividerRowY() + ROW_H + ROW_GAP;
    }

    private float platesLabelY() {
        return resetY() + RESET_H + SECTION_GAP;
    }

    private float plateRowY(int index) {
        return platesLabelY() + SECTION_LABEL_H + index * (ROW_H + ROW_GAP);
    }

    // ---------------------------------------------------------- best coords column

    private float bestCardH(int card) {
        return BEST_HEIGHTS[card];
    }

    private float bestCardY(int card) {
        float y = columnTop() + SECTION_LABEL_H;
        for (int i = 0; i < card; i++) {
            y += bestCardH(i) + ROW_GAP;
        }
        return y;
    }

    private float bestResetY() {
        return bestCardY(BEST_CARDS - 1) + bestCardH(BEST_CARDS - 1) + 8;
    }

    /** Text boxes 2..6 as {x, y, width}. */
    private float[] bestBoxRect(int box) {
        float x = bestX();
        if (box == 2) {
            return new float[] {x + 36, bestCardY(3) + 7, 28};
        }
        if (box == 3) {
            return new float[] {x + 140, bestCardY(3) + 7, 44};
        }
        if (box == 5) {
            return new float[] {x + 40, bestCardY(4) + 7, 28};
        }
        if (box == 6) {
            return new float[] {x + 40, bestCardY(5) + 7, 44};
        }
        return new float[] {x + 12, bestCardY(4) + 7 + BEST_BOX_H + 5, 44};
    }

    private int bestBoxAt(float mx, float my) {
        for (int box = 2; box <= 6; box++) {
            float[] r = bestBoxRect(box);
            if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + BEST_BOX_H) {
                return box;
            }
        }
        return -1;
    }

    /**
     * Chips as {x, y, width}: 0 = Gain tap/held, 1 = Highest tap/held, 2 = after-flat tap/held,
     * 3 = "#th after" tap/held, 4 = "#th after" 5b Flat / 4b Speed, 5 = ON 5b Flat / 4b Speed.
     */
    private float[] bestChipRect(int chip) {
        float x = bestX();
        switch (chip) {
            case 0:
                return new float[] {x + 12 + BridgeFont.width("Best", 10.0F, true) + 5,
                        bestCardY(0) + 6, 38};
            case 1:
                return new float[] {x + 12 + BridgeFont.width("Highest", 10.0F, true) + 5,
                        bestCardY(2) + 6, 38};
            case 2:
                return new float[] {x + 68, bestCardY(3) + 7, 38};
            case 3:
                return new float[] {x + 72, bestCardY(4) + 7, 38};
            case 4:
                return new float[] {x + 62, bestCardY(4) + 7 + BEST_BOX_H + 5, 70};
            default:
                return new float[] {x + 90, bestCardY(5) + 7, 70};
        }
    }

    private int bestChipAt(float mx, float my) {
        for (int chip = 0; chip < 6; chip++) {
            float[] r = bestChipRect(chip);
            if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + BEST_BOX_H) {
                return chip;
            }
        }
        return -1;
    }

    private boolean overBestReset(float mx, float my) {
        float y = bestResetY();
        return inColumn(mx, bestX(), BEST_W) && my >= y && my <= y + BEST_RESET_H;
    }

    private static final float BOX_W = 62.0F;
    private static final float BOX_H = 20.0F;

    private float boxX() {
        return rightX() + RIGHT_W - 12 - BOX_W;
    }

    private float boxY(int index) {
        return plateRowY(index) + (ROW_H - BOX_H) / 2.0F;
    }

    private int textBoxAt(float mx, float my) {
        for (int i = 0; i < 2; i++) {
            if (mx >= boxX() && mx <= boxX() + BOX_W && my >= boxY(i) && my <= boxY(i) + BOX_H) {
                return i;
            }
        }
        return bestBoxAt(mx, my);
    }

    private String boxValueText(int box) {
        if (box == 2) {
            return BridgeBests.getAfterCount();
        }
        if (box == 3) {
            return BridgeBests.getAfterTiming();
        }
        if (box == 4) {
            return BridgeBests.comboTime();
        }
        if (box == 6) {
            return BridgeBests.onTime();
        }
        if (box == 5) {
            return BridgeBests.comboTap();
        }
        if (box == 0) {
            return String.format(java.util.Locale.US, "%03d", BridgeConfig.getPlateEndThousandths());
        }
        String text = String.format(java.util.Locale.US, "%.3f", BridgeConfig.getJumpPlateHeight());
        text = text.replaceAll("0+$", "");
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    /** Applies the typed text of the focused box (an empty box keeps the old value) and unfocuses. */
    private void commitBox() {
        if (focusedBox < 0) {
            return;
        }
        String text = boxText.trim();
        if (text.length() > 0) {
            try {
                if (focusedBox == 0) {
                    // Typed after a decimal point: "5" is .500, "82" is .820.
                    StringBuilder digits = new StringBuilder(text);
                    while (digits.length() < 3) {
                        digits.append('0');
                    }
                    BridgeConfig.setPlateEndThousandths(Integer.parseInt(digits.toString()));
                } else if (focusedBox == 1) {
                    BridgeConfig.setJumpPlateHeight(Double.parseDouble(text.equals(".") ? "0" : text));
                } else if (focusedBox == 2) {
                    BridgeBests.setAfterCount(text);
                } else if (focusedBox == 3) {
                    BridgeBests.setAfterTiming(text);
                } else if (focusedBox == 4) {
                    BridgeBests.setComboTime(text);
                } else if (focusedBox == 6) {
                    BridgeBests.setOnTime(text);
                } else {
                    BridgeBests.setComboTap(text);
                }
            } catch (NumberFormatException ignored) {
                // keep the old value
            }
        }
        focusedBox = -1;
        boxText = "";
        BridgeConfig.save();
    }

    private float controlsLabelY() {
        return plateRowY(2) - ROW_GAP + SECTION_GAP;
    }

    private float keybindRowY() {
        return controlsLabelY() + SECTION_LABEL_H;
    }

    /** Clips drawing to a panel-space rectangle with a GL scissor (undoing the fit / open transform). */
    private void beginClip(float lx, float ly, float lw, float lh) {
        float x0 = (lx - width / 2.0F) * drawScale + width / 2.0F;
        float y0 = (ly - height / 2.0F) * drawScale + height / 2.0F + drawYOffset;
        float x1 = x0 + lw * drawScale;
        float y1 = y0 + lh * drawScale;
        int px = (int) Math.floor(x0 * guiScaleFactor);
        int pxEnd = (int) Math.ceil(x1 * guiScaleFactor);
        int pyTop = (int) Math.floor(y0 * guiScaleFactor);
        int pyBottom = (int) Math.ceil(y1 * guiScaleFactor);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(Math.max(0, px), Math.max(0, mc.displayHeight - pyBottom),
                Math.max(0, pxEnd - px), Math.max(0, pyBottom - pyTop));
    }

    private void endClip() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    private boolean overClose(float mx, float my) {
        float cx = panelX() + PANEL_W - PAD - 20;
        float cy = panelY() + 14;
        return mx >= cx && mx <= cx + 20 && my >= cy && my <= cy + 20;
    }

    private boolean inColumn(float mx, float columnX, float columnW) {
        return mx >= columnX && mx <= columnX + columnW;
    }

    /** True when the row's switch can be used (a child switch needs its parent switched on). */
    private boolean rowEnabled(int index) {
        BridgeConfig.Setting parent = SETTINGS[index].parent;
        return parent == null || BridgeConfig.isEnabled(parent);
    }

    private int rowAt(float mx, float my) {
        if (!inColumn(mx, leftX(), LEFT_W)) {
            return -1;
        }
        for (int i = 0; i < SETTINGS.length; i++) {
            if (SETTINGS[i].parent != null && (!tapsExpanded || expandEase() < 0.98F)) {
                continue;
            }
            float y = rowY(i);
            if (my >= y && my <= y + ROW_HEIGHT[i]) {
                return i;
            }
        }
        return -1;
    }

    private int sliderAt(float mx, float my) {
        if (!inColumn(mx, rightX(), RIGHT_W)) {
            return -1;
        }
        for (int i = 0; i < SLIDERS; i++) {
            float y = sliderY(i);
            if (my >= y && my <= y + ROW_H) {
                return i;
            }
        }
        return -1;
    }

    private boolean overDividerRow(float mx, float my) {
        float y = dividerRowY();
        return inColumn(mx, rightX(), RIGHT_W) && my >= y && my <= y + ROW_H;
    }

    private boolean overReset(float mx, float my) {
        float y = resetY();
        return inColumn(mx, rightX(), RIGHT_W) && my >= y && my <= y + RESET_H;
    }

    private boolean overKeybindRow(float mx, float my) {
        float y = keybindRowY();
        return inColumn(mx, rightX(), RIGHT_W) && my >= y && my <= y + ROW_H;
    }

    private boolean overPanel(float mx, float my) {
        return mx >= panelX() && mx <= panelX() + PANEL_W
                && my >= panelY() && my <= panelY() + panelH();
    }

    // ---------------------------------------------------------------- sliders

    private int sliderValue(int slider) {
        return slider == 0 ? BridgeConfig.getOverlayScalePercent() : BridgeConfig.getOverlayOpacity();
    }

    private void setSliderValue(int slider, int value) {
        if (slider == 0) {
            BridgeConfig.setOverlayScalePercent(value);
        } else {
            BridgeConfig.setOverlayOpacity(value);
        }
    }

    private String sliderText(int slider) {
        return sliderValue(slider) + "%";
    }

    private float trackX() {
        return rightX() + 12;
    }

    private float trackW() {
        return RIGHT_W - 24;
    }

    /** Sets a slider from a local mouse x, snapped to the slider's step. */
    private void dragSlider(int slider, float mx) {
        float fraction = Math.max(0.0F, Math.min(1.0F, (mx - trackX()) / trackW()));
        int min = SLIDER_MIN[slider];
        int max = SLIDER_MAX[slider];
        int step = SLIDER_STEP[slider];
        int value = min + Math.round(fraction * (max - min) / step) * step;
        setSliderValue(slider, Math.max(min, Math.min(max, value)));
    }

    // ----------------------------------------------------------------- input

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        float mx = toLocalX(mouseX);
        float my = toLocalY(mouseY);

        if (listeningForKey) {
            // Middle / side mouse buttons can be bound; left and right clicks just cancel.
            if (button >= 2) {
                applyKey(-100 + button);
            }
            listeningForKey = false;
            return;
        }
        if (button == 1) {
            // Right-clicking Taps & Flats opens / closes its dropdown of detail switches.
            if (rowAt(mx, my) == TAPS_INDEX) {
                tapsExpanded = !tapsExpanded;
                playClick();
            }
            return;
        }
        if (button != 0) {
            return;
        }
        int box = textBoxAt(mx, my);
        if (focusedBox >= 0 && box != focusedBox) {
            commitBox();
        }
        if (box >= 0) {
            if (focusedBox != box) {
                focusedBox = box;
                boxText = "";
                playClick();
            }
            return;
        }
        if (overClose(mx, my)) {
            mc.displayGuiScreen(null);
            return;
        }
        int bestChip = bestChipAt(mx, my);
        if (bestChip >= 0) {
            switch (bestChip) {
                case 0:
                    BridgeBests.toggleGainKind();
                    break;
                case 1:
                    BridgeBests.toggleHighestKind();
                    break;
                case 2:
                    BridgeBests.toggleAfterFlatKind();
                    break;
                case 3:
                    BridgeBests.toggleComboKind();
                    break;
                case 4:
                    BridgeBests.toggleComboType();
                    break;
                default:
                    BridgeBests.toggleOnType();
                    break;
            }
            playClick();
            return;
        }
        if (overBestReset(mx, my)) {
            long clickedAt = Minecraft.getSystemTime();
            if (clickedAt < bestResetArmedUntil) {
                BridgeBests.resetAll();
                bestResetArmedUntil = 0L;
            } else {
                bestResetArmedUntil = clickedAt + (long) RESET_CONFIRM_MS;
            }
            playClick();
            return;
        }
        int preset = presetAt(mx, my);
        if (preset >= 0) {
            PRESETS[preset].apply();
            BridgeConfig.save();
            playClick();
            return;
        }
        int row = rowAt(mx, my);
        if (row >= 0) {
            // A greyed-out child row ignores clicks until its parent is switched back on.
            if (rowEnabled(row)) {
                BridgeConfig.toggle(SETTINGS[row]);
                BridgeConfig.save();
                playClick();
            }
            return;
        }
        int chip = positionChipAt(mx, my);
        if (chip >= 0) {
            BridgeConfig.setOverlayPosition(POSITIONS[chip]);
            BridgeConfig.save();
            playClick();
            return;
        }
        int slider = sliderAt(mx, my);
        if (slider >= 0) {
            draggingSlider = slider;
            dragSlider(slider, mx);
            return;
        }
        if (overDividerRow(mx, my)) {
            BridgeConfig.setDividersEnabled(!BridgeConfig.isDividersEnabled());
            BridgeConfig.save();
            playClick();
            return;
        }
        if (overReset(mx, my)) {
            BridgeConfig.resetOverlay();
            BridgeConfig.save();
            playClick();
            return;
        }
        if (overKeybindRow(mx, my)) {
            listeningForKey = true;
            playClick();
            return;
        }
        if (!overPanel(mx, my)) {
            mc.displayGuiScreen(null);
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton,
                                  long timeSinceLastClick) {
        if (draggingSlider >= 0) {
            dragSlider(draggingSlider, toLocalX(mouseX));
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (draggingSlider >= 0) {
            draggingSlider = -1;
            BridgeConfig.save();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (focusedBox >= 0) {
            if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_RETURN
                    || keyCode == Keyboard.KEY_NUMPADENTER) {
                commitBox();
            } else if (keyCode == Keyboard.KEY_BACK) {
                if (boxText.length() > 0) {
                    boxText = boxText.substring(0, boxText.length() - 1);
                }
            } else if (focusedBox == 0) {
                if (typedChar >= '0' && typedChar <= '9' && boxText.length() < 3) {
                    boxText += typedChar;
                }
            } else if (focusedBox == 2 || focusedBox == 5
                    || (focusedBox == 4 && !BridgeBests.comboTimeIsTiming())
                    || (focusedBox == 6 && !BridgeBests.onTimeIsTiming())) {
                // Whole numbers: a tap number or a tick count.
                if (typedChar >= '0' && typedChar <= '9' && boxText.length() < 2) {
                    boxText += typedChar;
                }
            } else if (focusedBox == 3 || focusedBox == 4 || focusedBox == 6) {
                // A flat time such as 1.05 / 1.15.
                if (((typedChar >= '0' && typedChar <= '9')
                        || (typedChar == '.' && boxText.indexOf('.') < 0)) && boxText.length() < 5) {
                    boxText += typedChar;
                }
            } else if (((typedChar >= '0' && typedChar <= '9')
                    || (typedChar == '.' && boxText.indexOf('.') < 0)) && boxText.length() < 6) {
                boxText += typedChar;
            }
            return;
        }
        if (listeningForKey) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                // cancel, keep the old binding
            } else if (keyCode == Keyboard.KEY_BACK || keyCode == Keyboard.KEY_DELETE) {
                applyKey(Keyboard.KEY_NONE);
            } else {
                applyKey(keyCode);
            }
            listeningForKey = false;
            return;
        }
        int bound = BridgeEssentials.openSettingsKey.getKeyCode();
        if (keyCode == Keyboard.KEY_ESCAPE || (bound > 0 && keyCode == bound)) {
            mc.displayGuiScreen(null);
        }
    }

    private void applyKey(int code) {
        BridgeEssentials.openSettingsKey.setKeyCode(code);
        KeyBinding.resetKeyBindingArrayAndHash();
        mc.gameSettings.saveOptions();
    }

    private void playClick() {
        mc.getSoundHandler().playSound(
                net.minecraft.client.audio.PositionedSoundRecord.create(
                        new ResourceLocation("gui.button.press"), 1.0F));
    }

    /** Description of another keybind using the same key, or null. */
    private String conflictingBinding() {
        int code = BridgeEssentials.openSettingsKey.getKeyCode();
        if (code == Keyboard.KEY_NONE) {
            return null;
        }
        for (KeyBinding other : mc.gameSettings.keyBindings) {
            if (other != BridgeEssentials.openSettingsKey && other.getKeyCode() == code) {
                return I18n.format(other.getKeyDescription());
            }
        }
        return null;
    }

    // ------------------------------------------------------------- rendering

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        long now = Minecraft.getSystemTime();
        float dt = Math.min(0.1F, (now - lastFrame) / 1000.0F);
        lastFrame = now;
        float open = BridgeRender.easeOutCubic(Math.min(1.0F, (now - openedAt) / OPEN_MS));
        BridgeFont.beginFrame(new ScaledResolution(mc).getScaleFactor());

        setBlurRadius(BLUR_RADIUS * open);
        Gui.drawRect(0, 0, width, height, BridgeRender.withAlpha(0xFF070B12, Math.round(150 * open)));

        // Live HUD preview, drawn above the dim layer so size / position / colours can be judged.
        if (BridgeEssentials.tracker != null) {
            BridgeEssentials.tracker.renderHudPreview();
        }

        float mx = toLocalX(mouseX);
        float my = toLocalY(mouseY);
        int hoveredRow = rowAt(mx, my);
        if (hoveredRow >= 0 && !rowEnabled(hoveredRow)) {
            hoveredRow = -1;
        }
        int hoveredSlider = draggingSlider >= 0 ? draggingSlider : sliderAt(mx, my);
        float smoothing = 1.0F - (float) Math.exp(-dt * 18.0F);
        for (int i = 0; i < SETTINGS.length; i++) {
            float target = BridgeConfig.isEnabled(SETTINGS[i]) ? 1.0F : 0.0F;
            switchAnim[i] += (target - switchAnim[i]) * smoothing;
            hoverAnim[i] += ((i == hoveredRow ? 1.0F : 0.0F) - hoverAnim[i]) * smoothing;
        }
        for (int i = 0; i < SLIDERS; i++) {
            sliderHover[i] += ((i == hoveredSlider ? 1.0F : 0.0F) - sliderHover[i]) * smoothing;
        }
        int hoveredChip = positionChipAt(mx, my);
        for (int i = 0; i < POSITIONS.length; i++) {
            float selected = POSITIONS[i] == BridgeConfig.getOverlayPosition() ? 1.0F : 0.0F;
            positionAnim[i] += (selected - positionAnim[i]) * smoothing;
            positionHover[i] += ((i == hoveredChip ? 1.0F : 0.0F) - positionHover[i]) * smoothing;
        }
        for (int i = 0; i < PRESETS.length; i++) {
            presetAnim[i] += ((PRESETS[i].isApplied() ? 1.0F : 0.0F) - presetAnim[i]) * smoothing;
            presetHover[i] += ((i == presetAt(mx, my) ? 1.0F : 0.0F) - presetHover[i]) * smoothing;
        }
        float expandStep = dt * 1000.0F / EXPAND_MS;
        expandRaw = Math.max(0.0F, Math.min(1.0F, expandRaw + (tapsExpanded ? expandStep : -expandStep)));
        dividerAnim += ((BridgeConfig.isDividersEnabled() ? 1.0F : 0.0F) - dividerAnim) * smoothing;
        dividerHover += ((overDividerRow(mx, my) ? 1.0F : 0.0F) - dividerHover) * smoothing;
        resetHover += ((overReset(mx, my) ? 1.0F : 0.0F) - resetHover) * smoothing;
        keybindHover += ((overKeybindRow(mx, my) ? 1.0F : 0.0F) - keybindHover) * smoothing;
        closeHover += ((overClose(mx, my) ? 1.0F : 0.0F) - closeHover) * smoothing;
        int hoveredBestChip = bestChipAt(mx, my);
        for (int i = 0; i < 6; i++) {
            bestChipHover[i] += ((i == hoveredBestChip ? 1.0F : 0.0F) - bestChipHover[i]) * smoothing;
        }
        bestResetHover += ((overBestReset(mx, my) ? 1.0F : 0.0F) - bestResetHover) * smoothing;

        float fit = fitScale();
        float grow = 0.94F + 0.06F * open;
        drawScale = fit * grow;
        drawYOffset = (1.0F - open) * 10.0F;
        guiScaleFactor = new ScaledResolution(mc).getScaleFactor();
        GlStateManager.pushMatrix();
        GlStateManager.translate(width / 2.0F, height / 2.0F + (1.0F - open) * 10.0F, 0.0F);
        GlStateManager.scale(fit * grow, fit * grow, 1.0F);
        GlStateManager.translate(-width / 2.0F, -height / 2.0F, 0.0F);

        drawPanel(open, now);

        GlStateManager.popMatrix();
        BridgeRender.resetColor();
    }

    private void drawPanel(float open, long now) {
        float px = panelX();
        float py = panelY();

        // Shadow, border, body.
        float panelH = panelH();
        BridgeRender.roundedRect(px - 4, py - 1, PANEL_W + 8, panelH + 10, 14,
                BridgeRender.scaleAlpha(0x40000000, open));
        BridgeRender.roundedRect(px, py, PANEL_W, panelH, 10,
                BridgeRender.scaleAlpha(PANEL_BORDER, open));
        BridgeRender.roundedRect(px + 1, py + 1, PANEL_W - 2, panelH - 2, 9,
                BridgeRender.scaleAlpha(PANEL_FILL, open));

        // Header.
        BridgeRender.roundedRect(px + PAD, py + 14, 3, 26, 1.5F,
                BridgeRender.scaleAlpha(ACCENT, open));
        BridgeFont.draw("BridgeEssentials", px + PAD + 11, py + 13, 15.0F,
                BridgeRender.scaleAlpha(TEXT_PRIMARY, open), true);
        BridgeFont.draw("HUD settings", px + PAD + 11, py + 32, 8.5F,
                BridgeRender.scaleAlpha(TEXT_MUTED, open), false);
        drawCloseIcon(px + PANEL_W - PAD - 20, py + 14, open);
        // Credits, right-aligned next to the close icon.
        String creditPrefix = "Made by ";
        String creditName = "skepi";
        float nameW = BridgeFont.width(creditName, 8.5F, true);
        float prefixW = BridgeFont.width(creditPrefix, 8.5F, false);
        float creditX = px + PANEL_W - PAD - 20 - 12 - nameW - prefixW;
        BridgeFont.draw(creditPrefix, creditX, py + 20, 8.5F,
                BridgeRender.scaleAlpha(TEXT_MUTED, open), false);
        BridgeFont.draw(creditName, creditX + prefixW, py + 20, 8.5F,
                BridgeRender.scaleAlpha(ACCENT, open), true);
        BridgeRender.roundedRect(px + PAD, py + HEADER_H - 6, PANEL_W - PAD * 2, 1, 0.5F,
                BridgeRender.scaleAlpha(PANEL_BORDER, open));

        // Far-left column: personal bests.
        drawBestColumn(open, now);

        // Middle column: presets, then the HUD switches (Taps & Flats details drop down under it).
        BridgeFont.draw("PRESETS", leftX() + 2, columnTop(), 7.5F,
                BridgeRender.scaleAlpha(TEXT_MUTED, open), true);
        for (int i = 0; i < PRESETS.length; i++) {
            drawPresetChip(i, open);
        }
        BridgeFont.draw("DISPLAY", leftX() + 2, displayLabelY(), 7.5F,
                BridgeRender.scaleAlpha(TEXT_MUTED, open), true);
        for (int i = 0; i < SETTINGS.length; i++) {
            if (SETTINGS[i].parent == null) {
                drawSettingRow(i, open);
            }
        }
        float expand = expandEase();
        if (expand > 0.003F) {
            // The dropdown rows are clipped to the strip that has opened so far, so they appear
            // to slide out from behind the Taps & Flats card while the rows below move down.
            float bandTop = rowY(TAPS_INDEX) + ROW_H;
            beginClip(leftX() - 2, bandTop, LEFT_W + 4, expand * CHILD_BLOCK_H);
            float childOpen = open * Math.min(1.0F, expand * 1.6F);
            for (int i = 0; i < SETTINGS.length; i++) {
                if (SETTINGS[i].parent != null) {
                    drawSubSettingRow(i, childOpen);
                }
            }
            endClip();
        }

        // Right column: overlay size / position, then controls.
        BridgeFont.draw("OVERLAY", rightX() + 2, columnTop(), 7.5F,
                BridgeRender.scaleAlpha(TEXT_MUTED, open), true);
        for (int i = 0; i < SLIDERS; i++) {
            drawSliderRow(i, open);
        }
        drawPositionRow(open);
        drawDividerRow(open);
        drawResetButton(open);

        BridgeFont.draw("PLATES", rightX() + 2, platesLabelY(), 7.5F,
                BridgeRender.scaleAlpha(TEXT_MUTED, open), true);
        drawPlateRow(0, "Plate end coordinate", "Where on the plate it ends", open, now);
        drawPlateRow(1, "Jump plate height", "Blocks above the plate that trigger", open, now);

        BridgeFont.draw("CONTROLS", rightX() + 2, controlsLabelY(), 7.5F,
                BridgeRender.scaleAlpha(TEXT_MUTED, open), true);
        drawKeybindRow(open, now);

        // Footer.
        String key = GameSettings.getKeyDisplayString(BridgeEssentials.openSettingsKey.getKeyCode());
        String hint = (listeningForKey ? "Esc cancels  -  Backspace clears the keybind"
                : "Press " + key + " or Esc to close  -  changes save automatically");
        float hintWidth = BridgeFont.width(hint, 7.5F, false);
        BridgeFont.draw(hint, px + (PANEL_W - hintWidth) / 2.0F, py + panelH - FOOTER_H + 8, 7.5F,
                BridgeRender.scaleAlpha(TEXT_MUTED, open), false);
    }

    // ------------------------------------------------------- best coords drawing

    private void drawBestColumn(float open, long now) {
        BridgeFont.draw("BEST COORDS", bestX() + 2, columnTop(), 7.5F,
                BridgeRender.scaleAlpha(TEXT_MUTED, open), true);
        drawKindTitleCard(0, "Best", 0, BridgeBests.getGainKind().title, "Gain",
                "Best difference between taps", BridgeBests.gain(), true, true, false, open);
        drawBestSimpleCard(1, "Best Turnout Gain", "Best turnout difference",
                BridgeBests.turnout(), true, true, open);
        drawKindTitleCard(2, "Highest", 1, BridgeBests.getHighestKind().title, "Coordinate",
                "Negative offset, positive gain", BridgeBests.highestTap(), false, false, true, open);
        drawAfterFlatCard(open, now);
        drawComboCard(open, now);
        drawOnCard(open, now);
        drawBestTextCard(6, "Playtime", "Time spent in game",
                BridgeBests.formatPlaytime(BridgeBests.totalPlaytimeMs()), open);
        drawBestTextCard(7, "Attempts", "Every attempt ever made",
                Long.toString(BridgeBests.totalAttempts()), open);
        drawBestResetButton(open, now);
    }

    private void drawBestCardBackground(int card, float open) {
        BridgeRender.roundedRect(bestX(), bestCardY(card), BEST_W, bestCardH(card), 8,
                BridgeRender.scaleAlpha(CARD_FILL, open));
    }

    private void drawBestSimpleCard(int card, String title, String description,
                                    BridgeBests.Entry entry, boolean signed, boolean colored,
                                    float open) {
        float x = bestX();
        float y = bestCardY(card);
        drawBestCardBackground(card, open);
        BridgeFont.draw(title, x + 12, y + 7, 10.0F, BridgeRender.scaleAlpha(TEXT_PRIMARY, open), true);
        BridgeFont.draw(description, x + 12, y + 21.5F, 7.5F,
                BridgeRender.scaleAlpha(TEXT_MUTED, open), false);
        drawBestValue(x + BEST_W - 12, y + bestCardH(card) / 2.0F, entry, signed, colored, false,
                false, open);
    }

    /** A card whose title reads "[word] [Tap/Held chip] [word2]", e.g. "Best Tap Gain". */
    private void drawKindTitleCard(int card, String word, int chip, String kindTitle, String word2,
                                   String description, BridgeBests.Entry entry, boolean signed,
                                   boolean colored, boolean showOffset, float open) {
        float x = bestX();
        float y = bestCardY(card);
        drawBestCardBackground(card, open);
        drawBestWord(word, x + 12, y + 6, open);
        drawBestChip(chip, kindTitle, open);
        float[] r = bestChipRect(chip);
        drawBestWord(word2, r[0] + r[2] + 5, y + 6, open);
        BridgeFont.draw(description, x + 12, y + 30, 7.5F,
                BridgeRender.scaleAlpha(TEXT_MUTED, open), false);
        drawBestValue(x + BEST_W - 12, y + 36, entry, signed, colored, false, showOffset, open);
    }

    /** A plain stat (title, description, value text on the right). */
    private void drawBestTextCard(int card, String title, String description, String value,
                                  float open) {
        float x = bestX();
        float y = bestCardY(card);
        drawBestCardBackground(card, open);
        BridgeFont.draw(title, x + 12, y + 7, 10.0F, BridgeRender.scaleAlpha(TEXT_PRIMARY, open), true);
        BridgeFont.draw(description, x + 12, y + 21.5F, 7.5F,
                BridgeRender.scaleAlpha(TEXT_MUTED, open), false);
        float valueW = BridgeFont.width(value, 11.0F, true);
        BridgeFont.drawMiddle(value, x + BEST_W - 12 - valueW, y + bestCardH(card) / 2.0F, 11.0F,
                BridgeRender.scaleAlpha(ACCENT, open), true);
    }

    /**
     * Right-aligned value: the number, then for AFTER stats the net change in brackets, and for
     * the Gain stat the jumpover offset of the row that set it.
     */
    private void drawBestValue(float right, float centerY, BridgeBests.Entry entry, boolean signed,
                               boolean colored, boolean showNet, boolean showOffset, float open) {
        if (entry == null) {
            String none = "--";
            float noneW = BridgeFont.width(none, 11.0F, true);
            BridgeFont.drawMiddle(none, right - noneW, centerY, 11.0F,
                    BridgeRender.scaleAlpha(TEXT_MUTED, open), true);
            return;
        }
        String main = BridgeBests.format(entry.value, signed);
        int mainColor = entry.value < 0 ? BAD
                : (colored && entry.value > 0) || (signed && entry.value > 0) ? GOOD : ACCENT;
        float cursor = right;
        if (showNet) {
            String net = "(" + BridgeBests.format(entry.net, true) + ")";
            float netW = BridgeFont.width(net, 8.5F, true);
            cursor -= netW;
            BridgeFont.drawMiddle(net, cursor, centerY + 0.5F, 8.5F, BridgeRender.scaleAlpha(
                    entry.net > 0 ? GOOD : entry.net < 0 ? BAD : TEXT_MUTED, open), true);
            cursor -= 5;
        }
        if (showOffset && entry.hasOffset) {
            String offset = BridgeBests.format(entry.offset, true) + " Jumpover";
            float offsetW = BridgeFont.width(offset, 8.5F, true);
            cursor -= offsetW;
            BridgeFont.drawMiddle(offset, cursor, centerY + 0.5F, 8.5F, BridgeRender.scaleAlpha(
                    entry.offset > 0 ? GOOD : entry.offset < 0 ? BAD : TEXT_MUTED, open), true);
            cursor -= 6;
        }
        float mainW = BridgeFont.width(main, 11.0F, true);
        BridgeFont.drawMiddle(main, cursor - mainW, centerY, 11.0F,
                BridgeRender.scaleAlpha(mainColor, open), true);
    }

    /** A small bordered text box (same look as the plate boxes). */
    private void drawBestBox(int box, float open, long now) {
        float[] r = bestBoxRect(box);
        boolean focused = focusedBox == box;
        BridgeRender.roundedRect(r[0], r[1], r[2], BEST_BOX_H, 6,
                BridgeRender.scaleAlpha(focused ? ACCENT : 0xFF34405A, open));
        BridgeRender.roundedRect(r[0] + 1, r[1] + 1, r[2] - 2, BEST_BOX_H - 2, 5,
                BridgeRender.scaleAlpha(focused ? 0xFF16313A : CHIP_FILL, open));
        String shown;
        int color;
        if (focused) {
            boolean caret = (now / 450) % 2 == 0;
            shown = boxText.length() == 0 ? boxValueText(box) : boxText + (caret ? "|" : "");
            color = boxText.length() == 0 ? TEXT_MUTED : TEXT_PRIMARY;
        } else {
            shown = boxValueText(box);
            color = TEXT_PRIMARY;
        }
        float textW = BridgeFont.width(shown, 9.0F, true);
        BridgeFont.drawMiddle(shown, r[0] + Math.max(4.0F, (r[2] - textW) / 2.0F),
                r[1] + BEST_BOX_H / 2.0F, 9.0F, BridgeRender.scaleAlpha(color, open), true);
    }

    private void drawBestWord(String word, float x, float y, float open) {
        BridgeFont.drawMiddle(word, x, y + BEST_BOX_H / 2.0F, 10.0F,
                BridgeRender.scaleAlpha(TEXT_PRIMARY, open), true);
    }

    /** Best [count] [tap/held] after [timing] Flat. */
    private void drawAfterFlatCard(float open, long now) {
        float x = bestX();
        float y = bestCardY(3);
        drawBestCardBackground(3, open);
        float lineY = y + 7;
        drawBestWord("Best", x + 12, lineY, open);
        drawBestBox(2, open, now);
        drawBestChip(2, BridgeBests.getAfterFlatKind().title, open);
        drawBestWord("after", x + 112, lineY, open);
        drawBestBox(3, open, now);
        drawBestWord("Flat", x + 188, lineY, open);

        int count = Integer.parseInt(BridgeBests.getAfterCount());
        BridgeFont.drawMiddle(BridgeBests.ordinal(count) + " "
                + BridgeBests.getAfterFlatKind().title.toLowerCase(java.util.Locale.US) + " after "
                + BridgeBests.getAfterTiming() + " flat", x + 12, y + 40, 7.5F,
                BridgeRender.scaleAlpha(TEXT_MUTED, open), false);
        drawBestValue(x + BEST_W - 12, y + 40, BridgeBests.afterFlat(), true, true, true, false, open);
    }

    /** Best [#]th [tap/held] after [time] [5b Flat / 4b Speed]. */
    private void drawComboCard(float open, long now) {
        float x = bestX();
        float y = bestCardY(4);
        drawBestCardBackground(4, open);
        drawBestWord("Best", x + 12, y + 7, open);
        drawBestBox(5, open, now);
        drawBestChip(3, BridgeBests.getComboKind().title, open);
        drawBestWord("after", x + 116, y + 7, open);
        drawBestBox(4, open, now);
        drawBestChip(4, BridgeBests.getComboType().title, open);

        BridgeBests.ComboType type = BridgeBests.getComboType();
        String time = BridgeBests.comboTime();
        String what = type == BridgeBests.ComboType.FIVE_B ? time + " 5b flat" : time + "t 4b Speed";
        String description = BridgeBests.ordinal(Integer.parseInt(BridgeBests.comboTap())) + " "
                + BridgeBests.getComboKind().title.toLowerCase(java.util.Locale.US) + " after "
                + what;
        float valueY = y + BEST_HEIGHTS[4] - 14;
        BridgeFont.drawMiddle(description, x + 12, valueY, 7.5F,
                BridgeRender.scaleAlpha(TEXT_MUTED, open), false);
        drawBestValue(x + BEST_W - 12, valueY, BridgeBests.combo(), true, true, true, false, open);
    }

    /** Best [time] [5b Flat / 4b Speed]: the 5b flat / 4b Speed row itself. */
    private void drawOnCard(float open, long now) {
        float x = bestX();
        float y = bestCardY(5);
        drawBestCardBackground(5, open);
        drawBestWord("Best", x + 12, y + 7, open);
        drawBestBox(6, open, now);
        drawBestChip(5, BridgeBests.getOnType().title, open);

        String time = BridgeBests.onTime();
        String what = BridgeBests.getOnType() == BridgeBests.ComboType.FIVE_B
                ? time + " 5b flat" : time + "t 4b Speed";
        BridgeFont.drawMiddle(what, x + 12, y + 40, 7.5F,
                BridgeRender.scaleAlpha(TEXT_MUTED, open), false);
        drawBestValue(x + BEST_W - 12, y + 40, BridgeBests.onStat(), false, false, false, false, open);
    }

    private void drawBestChip(int chip, String label, float open) {
        float[] r = bestChipRect(chip);
        float hover = bestChipHover[chip];
        int border = BridgeRender.lerpColor(0xFF34405A, ACCENT, 0.35F + 0.65F * hover);
        int fill = BridgeRender.lerpColor(0xFF16313A, CARD_HOVER, hover * 0.4F);
        BridgeRender.roundedRect(r[0], r[1], r[2], BEST_BOX_H, 6, BridgeRender.scaleAlpha(border, open));
        BridgeRender.roundedRect(r[0] + 1, r[1] + 1, r[2] - 2, BEST_BOX_H - 2, 5,
                BridgeRender.scaleAlpha(fill, open));
        float labelW = BridgeFont.width(label, 8.5F, true);
        BridgeFont.drawMiddle(label, r[0] + (r[2] - labelW) / 2.0F, r[1] + BEST_BOX_H / 2.0F, 8.5F,
                BridgeRender.scaleAlpha(ACCENT, open), true);
    }

    private void drawBestResetButton(float open, long now) {
        float x = bestX();
        float y = bestResetY();
        boolean armed = now < bestResetArmedUntil;
        int fill = BridgeRender.lerpColor(CARD_FILL, CARD_HOVER, bestResetHover);
        BridgeRender.roundedRect(x, y, BEST_W, BEST_RESET_H, 7, BridgeRender.scaleAlpha(
                armed ? BridgeRender.withAlpha(DANGER, 0x55) : fill, open));
        String label = armed ? "Click again to erase all bests" : "Reset bests";
        float labelW = BridgeFont.width(label, 8.5F, true);
        BridgeFont.drawMiddle(label, x + (BEST_W - labelW) / 2.0F, y + BEST_RESET_H / 2.0F, 8.5F,
                BridgeRender.scaleAlpha(armed ? DANGER : BridgeRender.lerpColor(TEXT_MUTED,
                        TEXT_PRIMARY, bestResetHover), open), true);
    }

    private void drawIconChip(String icon, float x, float y, float on, boolean highlight, float open) {
        int chip = BridgeRender.lerpColor(CHIP_FILL, BridgeRender.withAlpha(ACCENT, 0x33), on);
        BridgeRender.roundedRect(x, y, ICON_CHIP, ICON_CHIP, 7, BridgeRender.scaleAlpha(chip, open));
        int tint = BridgeRender.lerpColor(ICON_OFF, ACCENT, on);
        float iconSize = 16;
        BridgeIcons.draw(icon, x + (ICON_CHIP - iconSize) / 2.0F, y + (ICON_CHIP - iconSize) / 2.0F,
                iconSize, BridgeRender.scaleAlpha(tint, open));
    }

    private void drawPresetChip(int index, float open) {
        float on = presetAnim[index];
        float hover = presetHover[index];
        float x = presetX(index);
        float y = presetY();
        float w = presetW();
        int border = BridgeRender.lerpColor(0xFF34405A, ACCENT, on);
        int fill = BridgeRender.lerpColor(
                BridgeRender.lerpColor(CHIP_FILL, CARD_HOVER, hover), 0xFF16313A, on);
        BridgeRender.roundedRect(x, y, w, PRESET_H, 7, BridgeRender.scaleAlpha(border, open));
        BridgeRender.roundedRect(x + 1, y + 1, w - 2, PRESET_H - 2, 6, BridgeRender.scaleAlpha(fill, open));
        String label = PRESETS[index].title;
        float labelW = BridgeFont.width(label, 9.0F, true);
        BridgeFont.drawMiddle(label, x + (w - labelW) / 2.0F, y + PRESET_H / 2.0F, 9.0F,
                BridgeRender.scaleAlpha(BridgeRender.lerpColor(
                        BridgeRender.lerpColor(TEXT_MUTED, TEXT_PRIMARY, hover), ACCENT, on), open), true);
    }

    private void drawSettingRow(int index, float open) {
        BridgeConfig.Setting setting = SETTINGS[index];
        float x = leftX();
        float y = rowY(index);
        float w = LEFT_W;
        float on = switchAnim[index];
        float hover = hoverAnim[index];

        int fill = BridgeRender.lerpColor(CARD_FILL, CARD_HOVER, hover);
        BridgeRender.roundedRect(x, y, w, ROW_H, 8, BridgeRender.scaleAlpha(fill, open));

        drawIconChip(setting.icon, x + 8, y + (ROW_H - ICON_CHIP) / 2.0F, on, false, open);

        float textX = x + 8 + ICON_CHIP + 10;
        BridgeFont.draw(setting.title, textX, y + 7, 10.0F,
                BridgeRender.scaleAlpha(TEXT_PRIMARY, open), true);
        BridgeFont.draw(setting.description, textX, y + 21.5F, 7.5F,
                BridgeRender.scaleAlpha(TEXT_MUTED, open), false);

        drawSwitch(x + w - SWITCH_W - 12, y + (ROW_H - SWITCH_H) / 2.0F, SWITCH_W, SWITCH_H,
                on, ACCENT, open);

        if (index == TAPS_INDEX) {
            // Dropdown indicator: a caret chip beside the switch that flips when open.
            float e = expandEase();
            float chip = 18.0F;
            float chipX = x + w - SWITCH_W - 12 - 8 - chip;
            float chipY = y + (ROW_H - chip) / 2.0F;
            int chipFill = BridgeRender.lerpColor(
                    BridgeRender.lerpColor(CHIP_FILL, CARD_HOVER, hover), BridgeRender.withAlpha(ACCENT, 0x33), e);
            BridgeRender.roundedRect(chipX, chipY, chip, chip, 5, BridgeRender.scaleAlpha(chipFill, open));
            BridgeRender.caret(chipX + chip / 2.0F, chipY + chip / 2.0F, 3.8F, 180.0F * e,
                    BridgeRender.scaleAlpha(BridgeRender.lerpColor(TEXT_MUTED, ACCENT, e), open));
        }
    }

    /**
     * A detail switch nested under a parent (Taps & Flats). While the parent is off the whole row
     * fades to grey and ignores clicks; its own on/off state is kept and shown dimly.
     */
    private void drawSubSettingRow(int index, float open) {
        BridgeConfig.Setting setting = SETTINGS[index];
        float parentOn = switchAnim[setting.parent.ordinal()];
        float alpha = open * (0.42F + 0.58F * parentOn);
        float x = leftX() + SUB_INDENT;
        float y = rowY(index);
        float w = LEFT_W - SUB_INDENT;
        float on = switchAnim[index];

        // Connector line back to the parent card.
        float lineX = leftX() + 7;
        BridgeRender.roundedRect(lineX, y - SUB_GAP, 2, SUB_H / 2.0F + SUB_GAP + 1, 1,
                BridgeRender.scaleAlpha(PANEL_BORDER, open));
        BridgeRender.roundedRect(lineX, y + SUB_H / 2.0F - 1, SUB_INDENT - 8, 2, 1,
                BridgeRender.scaleAlpha(PANEL_BORDER, open));

        int fill = BridgeRender.lerpColor(CARD_FILL, CARD_HOVER, hoverAnim[index]);
        BridgeRender.roundedRect(x, y, w, SUB_H, 7, BridgeRender.scaleAlpha(fill, alpha));

        float textX = x + 10;
        BridgeFont.draw(setting.title, textX, y + 4.5F, 9.0F,
                BridgeRender.scaleAlpha(TEXT_PRIMARY, alpha), true);
        BridgeFont.draw(setting.description, textX, y + 15.5F, 7.0F,
                BridgeRender.scaleAlpha(TEXT_MUTED, alpha), false);

        int onColor = BridgeRender.lerpColor(DISABLED_ON, ACCENT, parentOn);
        drawSwitch(x + w - SUB_SWITCH_W - 10, y + (SUB_H - SUB_SWITCH_H) / 2.0F,
                SUB_SWITCH_W, SUB_SWITCH_H, on, onColor, alpha);
    }

    private void drawSwitch(float sx, float sy, float w, float h, float on, int onColor, float alpha) {
        BridgeRender.roundedRect(sx, sy, w, h, h / 2.0F,
                BridgeRender.scaleAlpha(BridgeRender.lerpColor(SWITCH_OFF, onColor, on), alpha));
        float knob = h - 4;
        float knobX = sx + 2 + (w - 4 - knob) * on;
        BridgeRender.roundedRect(knobX, sy + 2, knob, knob, knob / 2.0F,
                BridgeRender.scaleAlpha(BridgeRender.lerpColor(0xFFB9C4D6, 0xFFFFFFFF, on), alpha));
    }

    private void drawSliderRow(int slider, float open) {
        float x = rightX();
        float y = sliderY(slider);
        float w = RIGHT_W;
        float hover = sliderHover[slider];

        int fill = BridgeRender.lerpColor(CARD_FILL, CARD_HOVER, hover);
        BridgeRender.roundedRect(x, y, w, ROW_H, 8, BridgeRender.scaleAlpha(fill, open));

        BridgeFont.draw(SLIDER_TITLES[slider], x + 12, y + 6, 9.5F,
                BridgeRender.scaleAlpha(TEXT_PRIMARY, open), true);
        String valueText = sliderText(slider);
        float valueW = BridgeFont.width(valueText, 9.0F, true);
        BridgeFont.draw(valueText, x + w - 12 - valueW, y + 6.5F, 9.0F,
                BridgeRender.scaleAlpha(ACCENT, open), true);

        float min = SLIDER_MIN[slider];
        float fraction = (sliderValue(slider) - min) / (SLIDER_MAX[slider] - min);
        float tx = trackX();
        float tw = trackW();
        float ty = y + 26;
        BridgeRender.roundedRect(tx, ty, tw, 4, 2, BridgeRender.scaleAlpha(SWITCH_OFF, open));
        BridgeRender.roundedRect(tx, ty, Math.max(4.0F, tw * fraction), 4, 2,
                BridgeRender.scaleAlpha(ACCENT, open));
        float knob = 10.0F + 2.0F * hover;
        BridgeRender.roundedRect(tx + tw * fraction - knob / 2.0F, ty + 2 - knob / 2.0F, knob, knob,
                knob / 2.0F, BridgeRender.scaleAlpha(0xFFFFFFFF, open));
    }

    private void drawPositionRow(float open) {
        float x = rightX();
        float y = positionY();
        BridgeRender.roundedRect(x, y, RIGHT_W, POS_H, 8, BridgeRender.scaleAlpha(CARD_FILL, open));
        BridgeFont.draw("Position", x + 12, y + 6, 9.5F,
                BridgeRender.scaleAlpha(TEXT_PRIMARY, open), true);
        String current = BridgeConfig.getOverlayPosition().title;
        float currentW = BridgeFont.width(current, 9.0F, true);
        BridgeFont.draw(current, x + RIGHT_W - 12 - currentW, y + 6.5F, 9.0F,
                BridgeRender.scaleAlpha(ACCENT, open), true);

        float chipW = positionChipW();
        for (int i = 0; i < POSITIONS.length; i++) {
            float on = positionAnim[i];
            float hover = positionHover[i];
            float cx = positionChipX(i);
            float cy = positionChipY(i);
            int border = BridgeRender.lerpColor(0xFF34405A, ACCENT, on);
            int fill = BridgeRender.lerpColor(
                    BridgeRender.lerpColor(CHIP_FILL, CARD_HOVER, hover), 0xFF16313A, on);
            BridgeRender.roundedRect(cx, cy, chipW, POS_CHIP_H, 6, BridgeRender.scaleAlpha(border, open));
            BridgeRender.roundedRect(cx + 1, cy + 1, chipW - 2, POS_CHIP_H - 2, 5,
                    BridgeRender.scaleAlpha(fill, open));
            String label = POSITIONS[i].title;
            float labelW = BridgeFont.width(label, 8.0F, true);
            BridgeFont.drawMiddle(label, cx + (chipW - labelW) / 2.0F, cy + POS_CHIP_H / 2.0F, 8.0F,
                    BridgeRender.scaleAlpha(BridgeRender.lerpColor(TEXT_MUTED, ACCENT, on), open), true);
        }
    }

    private void drawPlateRow(int box, String title, String description, float open, long now) {
        float x = rightX();
        float y = plateRowY(box);
        BridgeRender.roundedRect(x, y, RIGHT_W, ROW_H, 8, BridgeRender.scaleAlpha(CARD_FILL, open));
        BridgeFont.draw(title, x + 12, y + 7, 10.0F, BridgeRender.scaleAlpha(TEXT_PRIMARY, open), true);
        BridgeFont.draw(description, x + 12, y + 21.5F, 7.5F,
                BridgeRender.scaleAlpha(TEXT_MUTED, open), false);

        boolean focused = focusedBox == box;
        float bx = boxX();
        float by = boxY(box);
        BridgeRender.roundedRect(bx, by, BOX_W, BOX_H, 6,
                BridgeRender.scaleAlpha(focused ? ACCENT : 0xFF34405A, open));
        BridgeRender.roundedRect(bx + 1, by + 1, BOX_W - 2, BOX_H - 2, 5,
                BridgeRender.scaleAlpha(focused ? 0xFF16313A : CHIP_FILL, open));

        String shown;
        int color;
        if (focused) {
            boolean caret = (now / 450) % 2 == 0;
            shown = boxText.length() == 0 ? boxValueText(box) : boxText + (caret ? "|" : "");
            color = boxText.length() == 0 ? TEXT_MUTED : TEXT_PRIMARY;
        } else {
            shown = boxValueText(box);
            color = TEXT_PRIMARY;
        }
        BridgeFont.drawMiddle(shown, bx + 8, by + BOX_H / 2.0F, 9.0F,
                BridgeRender.scaleAlpha(color, open), true);
        if (box == 0) {
            // The plate end coordinate is a decimal: the point sits right before the text box.
            BridgeFont.drawMiddle(".", bx - 7, by + BOX_H / 2.0F, 11.0F,
                    BridgeRender.scaleAlpha(ACCENT, open), true);
        }
    }

    private void drawDividerRow(float open) {
        float x = rightX();
        float y = dividerRowY();
        int fill = BridgeRender.lerpColor(CARD_FILL, CARD_HOVER, dividerHover);
        BridgeRender.roundedRect(x, y, RIGHT_W, ROW_H, 8, BridgeRender.scaleAlpha(fill, open));
        BridgeFont.draw("Divider lines", x + 12, y + 7, 10.0F,
                BridgeRender.scaleAlpha(TEXT_PRIMARY, open), true);
        BridgeFont.draw("Between timer, taps & flats, turnout", x + 12, y + 21.5F, 7.5F,
                BridgeRender.scaleAlpha(TEXT_MUTED, open), false);
        drawSwitch(x + RIGHT_W - SWITCH_W - 12, y + (ROW_H - SWITCH_H) / 2.0F, SWITCH_W, SWITCH_H,
                dividerAnim, ACCENT, open);
    }

    private void drawResetButton(float open) {
        float x = rightX();
        float y = resetY();
        int fill = BridgeRender.lerpColor(CARD_FILL, CARD_HOVER, resetHover);
        BridgeRender.roundedRect(x, y, RIGHT_W, RESET_H, 7, BridgeRender.scaleAlpha(fill, open));
        String label = "Reset overlay";
        float labelW = BridgeFont.width(label, 8.5F, true);
        BridgeFont.drawMiddle(label, x + (RIGHT_W - labelW) / 2.0F, y + RESET_H / 2.0F, 8.5F,
                BridgeRender.scaleAlpha(BridgeRender.lerpColor(TEXT_MUTED, TEXT_PRIMARY, resetHover),
                        open), true);
    }

    private void drawKeybindRow(float open, long now) {
        float x = rightX();
        float y = keybindRowY();
        float w = RIGHT_W;
        float hover = keybindHover;
        float pulse = listeningForKey ? 0.5F + 0.5F * (float) Math.sin(now / 160.0D) : 0.0F;

        int fill = BridgeRender.lerpColor(CARD_FILL, CARD_HOVER, hover);
        if (listeningForKey) {
            BridgeRender.roundedRect(x, y, w, ROW_H, 8, BridgeRender.scaleAlpha(
                    BridgeRender.lerpColor(0xFF1E5E68, ACCENT, pulse * 0.6F), open));
            BridgeRender.roundedRect(x + 1, y + 1, w - 2, ROW_H - 2, 7,
                    BridgeRender.scaleAlpha(fill, open));
        } else {
            BridgeRender.roundedRect(x, y, w, ROW_H, 8, BridgeRender.scaleAlpha(fill, open));
        }

        drawIconChip("keyboard", x + 8, y + (ROW_H - ICON_CHIP) / 2.0F,
                listeningForKey ? 1.0F : 0.0F, false, open);

        float textX = x + 8 + ICON_CHIP + 10;
        BridgeFont.draw("Open menu keybind", textX, y + 7, 10.0F,
                BridgeRender.scaleAlpha(TEXT_PRIMARY, open), true);
        String conflict = conflictingBinding();
        if (conflict != null) {
            BridgeFont.draw("Also used by: " + conflict, textX, y + 21.5F, 7.5F,
                    BridgeRender.scaleAlpha(DANGER, open), false);
        } else {
            BridgeFont.draw("Click, then press a key", textX, y + 21.5F, 7.5F,
                    BridgeRender.scaleAlpha(TEXT_MUTED, open), false);
        }

        // Key chip.
        String label = listeningForKey ? "Press..."
                : GameSettings.getKeyDisplayString(BridgeEssentials.openSettingsKey.getKeyCode());
        float labelSize = 8.5F;
        float chipW = Math.max(40.0F, BridgeFont.width(label, labelSize, true) + 20.0F);
        float chipH = 20.0F;
        float cx = x + w - chipW - 12;
        float cy = y + (ROW_H - chipH) / 2.0F;
        int chipBorder = listeningForKey ? ACCENT : 0xFF34405A;
        int chipFill = listeningForKey ? 0xFF16313A : CHIP_FILL;
        BridgeRender.roundedRect(cx, cy, chipW, chipH, 6, BridgeRender.scaleAlpha(chipBorder, open));
        BridgeRender.roundedRect(cx + 1, cy + 1, chipW - 2, chipH - 2, 5,
                BridgeRender.scaleAlpha(chipFill, open));
        float labelW = BridgeFont.width(label, labelSize, true);
        BridgeFont.drawMiddle(label, cx + (chipW - labelW) / 2.0F, cy + chipH / 2.0F, labelSize,
                BridgeRender.scaleAlpha(listeningForKey ? ACCENT : TEXT_PRIMARY, open), true);
    }

    private void drawCloseIcon(float x, float y, float open) {
        float hover = closeHover;
        if (hover > 0.01F) {
            BridgeRender.roundedRect(x, y, 20, 20, 6, BridgeRender.scaleAlpha(
                    BridgeRender.withAlpha(0xFFFFFFFF, Math.round(26 * hover)), open));
        }
        int tint = BridgeRender.lerpColor(TEXT_MUTED, DANGER, hover);
        BridgeIcons.draw("close", x + 4, y + 4, 12, BridgeRender.scaleAlpha(tint, open));
    }
}
