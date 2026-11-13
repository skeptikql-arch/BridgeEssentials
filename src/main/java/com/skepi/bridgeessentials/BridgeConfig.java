package com.skepi.bridgeessentials;

import java.io.File;

import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

/**
 * Persistent on/off switches for each HUD section plus the overlay size / position. Tracking always
 * runs; these only gate display. Some switches are children of another switch (the extra details
 * that ride along with Taps & Flats) and only take effect while their parent is on.
 */
public final class BridgeConfig {
    private static final String CATEGORY = "hud";
    private static final String DEBUG_CATEGORY = "debug";

    /** The toggleable settings, in the order they appear in the menu (children follow their parent). */
    public enum Setting {
        TIMER(null, "Timer", "Attempt timer and completed / failed status", "timer", "timer"),
        TAPS_FLATS(null, "Taps & Flats", "Opening flat plus tap and flat landings", "tapsAndFlats", "steps"),
        TAP_DIFFERENCE(TAPS_FLATS, "Difference", "+/- vs the previous tap or flat", "tapsDifference", null),
        JUMPOVER_OFFSET(TAPS_FLATS, "Jumpover offset", "Offset row under each landing", "jumpoverOffset", null),
        SPRINT_STATUS(TAPS_FLATS, "Sprint status", "Sprint / No Sprint on speed rows", "sprintStatus", null),
        PREVIOUS_TICK(TAPS_FLATS, "Previous-tick line", "-1t line under speeds and flats", "previousTickLine", null),
        TURNOUT(null, "Turnout", "Landing forecast 14 ticks after takeoff", "turnout", "turnout"),
        PLATE_COORDINATES(null, "Plate Coordinates", "Plate and Plate (-1t) on completion", "plateCoordinates", "plate"),
        JUMP_TICKS(null, "Jump Ticks", "Ticks spent on the ground before each jump", "jumpTicks", "steps"),
        BED_CLICK_TIMING(null, "Bed Click Timing", "How many ticks late (or early) you clicked a bed", "bedClickTiming", "timer"),
        ATTEMPTS(null, "Session Attempts", "Running count of attempts this session", "attempts", "attempts");

        public final Setting parent;
        public final String title;
        public final String description;
        public final String icon;
        private final String key;

        Setting(Setting parent, String title, String description, String key, String icon) {
            this.parent = parent;
            this.icon = icon;
            this.title = title;
            this.description = description;
            this.key = key;
        }
    }


    /** One-click bundles of the top-level HUD switches (the Taps & Flats details are left alone). */
    public enum Preset {
        BRIDGING("Bridging", Setting.TIMER, Setting.TAPS_FLATS, Setting.TURNOUT,
                Setting.PLATE_COORDINATES, Setting.ATTEMPTS),
        MOVEMENT("Movement", Setting.TIMER, Setting.PLATE_COORDINATES, Setting.JUMP_TICKS,
                Setting.BED_CLICK_TIMING, Setting.ATTEMPTS);

        public final String title;
        private final java.util.EnumSet<Setting> enabled;

        Preset(String title, Setting first, Setting... rest) {
            this.title = title;
            this.enabled = java.util.EnumSet.of(first, rest);
        }

        /** True when every top-level switch currently matches this preset exactly. */
        public boolean isApplied() {
            for (Setting setting : Setting.values()) {
                if (setting.parent == null && isEnabled(setting) != enabled.contains(setting)) {
                    return false;
                }
            }
            return true;
        }

        /** Switches every top-level setting on or off to match this preset. */
        public void apply() {
            for (Setting setting : Setting.values()) {
                if (setting.parent == null) {
                    set(setting, enabled.contains(setting));
                }
            }
        }
    }

    // Overlay size / position limits. Position is a percentage of the free screen space, so 0 is
    // the left / top edge, 100 is the right / bottom edge, and the panel can never leave the screen.
    public static final int SCALE_MIN = 50;
    public static final int SCALE_MAX = 200;
    public static final int SCALE_DEFAULT = 100;
    /** Where the overlay sits on the screen. Lines are aligned to the nearest screen border. */
    public enum Position {
        TOP_LEFT("Top Left", 0, 0),
        TOP_MIDDLE("Top Middle", 1, 0),
        TOP_RIGHT("Top Right", 2, 0),
        BOTTOM_LEFT("Bottom Left", 0, 1),
        BOTTOM_RIGHT("Bottom Right", 2, 1);

        public final String title;
        /** 0 = left, 1 = middle, 2 = right. */
        public final int column;
        /** 0 = top, 1 = bottom. */
        public final int row;

        Position(String title, int column, int row) {
            this.title = title;
            this.column = column;
            this.row = row;
        }
    }

    public static final Position POSITION_DEFAULT = Position.TOP_RIGHT;
    public static final int OPACITY_DEFAULT = 72;
    public static final boolean DIVIDERS_DEFAULT = true;

    private static final Property[] PROPERTIES = new Property[Setting.values().length];
    private static final boolean[] VALUES = new boolean[Setting.values().length];
    private static final String OVERLAY_CATEGORY = "overlay";
    private static Property scaleProperty;
    private static Property positionProperty;
    private static int overlayScale = SCALE_DEFAULT;
    private static Position overlayPosition = POSITION_DEFAULT;
    private static Property opacityProperty;
    private static Property dividersProperty;
    private static int overlayOpacity = OPACITY_DEFAULT;
    private static boolean dividers = DIVIDERS_DEFAULT;
    // Plates: where along the plate block the attempt ends, and how far above the plate it can.
    public static final int PLATE_END_DEFAULT = 825;
    public static final double JUMP_PLATE_MAX = 50.0D;
    private static final String PLATE_CATEGORY = "plates";
    private static Property plateEndProperty;
    private static Property jumpPlateProperty;
    private static int plateEnd = PLATE_END_DEFAULT;
    private static double jumpPlateHeight = 0.0D;
    private static Configuration configuration;
    private static boolean debugLog = true;
    private static boolean debugLogPerTick = true;

    static {
        java.util.Arrays.fill(VALUES, true);
    }

    private BridgeConfig() {
    }

    /** Loads the config file; call once during pre-initialization. */
    public static void load(File file) {
        configuration = new Configuration(file);
        configuration.load();
        for (Setting setting : Setting.values()) {
            Property property = configuration.get(CATEGORY, setting.key, true,
                    "Show: " + (setting.parent == null ? "" : setting.parent.title + " > ")
                            + setting.title);
            PROPERTIES[setting.ordinal()] = property;
            VALUES[setting.ordinal()] = property.getBoolean(true);
        }
        scaleProperty = configuration.get(OVERLAY_CATEGORY, "sizePercent", SCALE_DEFAULT,
                "HUD overlay size in percent (" + SCALE_MIN + "-" + SCALE_MAX + ")");
        positionProperty = configuration.get(OVERLAY_CATEGORY, "position", POSITION_DEFAULT.name(),
                "HUD overlay position: TOP_LEFT, TOP_MIDDLE, TOP_RIGHT, BOTTOM_LEFT or BOTTOM_RIGHT");
        overlayScale = clamp(scaleProperty.getInt(SCALE_DEFAULT), SCALE_MIN, SCALE_MAX);
        overlayPosition = parsePosition(positionProperty.getString());
        opacityProperty = configuration.get(OVERLAY_CATEGORY, "backgroundOpacity", OPACITY_DEFAULT,
                "Opacity of the panel behind the HUD text in percent, 0 = invisible, 100 = solid");
        dividersProperty = configuration.get(OVERLAY_CATEGORY, "dividerLines", DIVIDERS_DEFAULT,
                "Draw divider lines between the timer / taps & flats / turnout & plate groups");
        overlayOpacity = clamp(opacityProperty.getInt(OPACITY_DEFAULT), 0, 100);
        dividers = dividersProperty.getBoolean(DIVIDERS_DEFAULT);
        plateEndProperty = configuration.get(PLATE_CATEGORY, "endCoordinateThousandths",
                PLATE_END_DEFAULT,
                "Plate end coordinate as three decimals (825 = .825). The attempt ends when the player's "
                        + "centre reaches it on the plate block: 000 = the plate block's exact edge, "
                        + "500 = half a block into it, values above 500 wrap to the previous block "
                        + "(825 = .175 before the edge, the vanilla plate edge)");
        jumpPlateProperty = configuration.get(PLATE_CATEGORY, "jumpPlateHeight", 0.0D,
                "How many blocks ABOVE the plate's hitbox still end the attempt (0 = normal plate)");
        plateEnd = clamp(plateEndProperty.getInt(PLATE_END_DEFAULT), 0, 999);
        jumpPlateHeight = Math.max(0.0D, Math.min(JUMP_PLATE_MAX, jumpPlateProperty.getDouble(0.0D)));
        // Diagnostic log switches live only in the config file (not in the menu).
        debugLog = configuration.get(DEBUG_CATEGORY, "debugLog", true,
                "Write the advanced diagnostic log to <minecraft>/logs/bridgeessentials-debug.log")
                .getBoolean(true);
        debugLogPerTick = configuration.get(DEBUG_CATEGORY, "debugLogPerTick", true,
                "Also write one line per tick while an attempt is running (larger file, but the "
                        + "most useful data when a row is wrong or missing)")
                .getBoolean(true);
        configuration.save();
    }

    /** True when the advanced diagnostic log should be written. */
    public static boolean isDebugLogEnabled() {
        return debugLog;
    }

    /** True when the diagnostic log should also contain one line per tick during an attempt. */
    public static boolean isDebugLogPerTickEnabled() {
        return debugLogPerTick;
    }

    public static boolean isEnabled(Setting setting) {
        return VALUES[setting.ordinal()];
    }

    /** True when the setting is on AND (for child settings) its parent is on too. */
    public static boolean isActive(Setting setting) {
        return isEnabled(setting) && (setting.parent == null || isActive(setting.parent));
    }

    public static void set(Setting setting, boolean enabled) {
        VALUES[setting.ordinal()] = enabled;
        Property property = PROPERTIES[setting.ordinal()];
        if (property != null) {
            property.set(enabled);
        }
    }

    public static void toggle(Setting setting) {
        set(setting, !isEnabled(setting));
    }

    // ------------------------------------------------------------------ overlay size / position

    /** Overlay size in percent, {@link #SCALE_MIN} to {@link #SCALE_MAX}. */
    public static int getOverlayScalePercent() {
        return overlayScale;
    }

    public static float getOverlayScale() {
        return overlayScale / 100.0F;
    }

    public static Position getOverlayPosition() {
        return overlayPosition;
    }

    public static void setOverlayPosition(Position position) {
        overlayPosition = position == null ? POSITION_DEFAULT : position;
        if (positionProperty != null) {
            positionProperty.set(overlayPosition.name());
        }
    }

    private static Position parsePosition(String name) {
        for (Position position : Position.values()) {
            if (position.name().equalsIgnoreCase(name)) {
                return position;
            }
        }
        return POSITION_DEFAULT;
    }

    public static void setOverlayScalePercent(int percent) {
        overlayScale = clamp(percent, SCALE_MIN, SCALE_MAX);
        if (scaleProperty != null) {
            scaleProperty.set(overlayScale);
        }
    }

    /** Opacity of the panel behind the HUD text in percent, 0 to 100. */
    public static int getOverlayOpacity() {
        return overlayOpacity;
    }

    public static void setOverlayOpacity(int percent) {
        overlayOpacity = clamp(percent, 0, 100);
        if (opacityProperty != null) {
            opacityProperty.set(overlayOpacity);
        }
    }

    /** True when divider lines are drawn between the HUD groups. */
    public static boolean isDividersEnabled() {
        return dividers;
    }

    public static void setDividersEnabled(boolean enabled) {
        dividers = enabled;
        if (dividersProperty != null) {
            dividersProperty.set(enabled);
        }
    }

    /** Plate end coordinate in thousandths, 0 to 999 (default 825 = .825). */
    public static int getPlateEndThousandths() {
        return plateEnd;
    }

    public static void setPlateEndThousandths(int thousandths) {
        plateEnd = clamp(thousandths, 0, 999);
        if (plateEndProperty != null) {
            plateEndProperty.set(plateEnd);
        }
    }

    /** The end coordinate as an offset from the plate block's edge, wrapped to [-.499, +.500]. */
    public static double getPlateEndOffset() {
        return (plateEnd > 500 ? plateEnd - 1000 : plateEnd) / 1000.0D;
    }

    /** Extra blocks above the plate's hitbox that still trigger it; 0 = the normal plate. */
    public static double getJumpPlateHeight() {
        return jumpPlateHeight;
    }

    public static void setJumpPlateHeight(double blocks) {
        jumpPlateHeight = Math.max(0.0D, Math.min(JUMP_PLATE_MAX, blocks));
        if (jumpPlateProperty != null) {
            jumpPlateProperty.set(jumpPlateHeight);
        }
    }

    public static void resetOverlay() {
        setOverlayOpacity(OPACITY_DEFAULT);
        setDividersEnabled(DIVIDERS_DEFAULT);
        setOverlayScalePercent(SCALE_DEFAULT);
        setOverlayPosition(POSITION_DEFAULT);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Writes the current values to disk. */
    public static void save() {
        if (configuration != null) {
            configuration.save();
        }
    }
}
