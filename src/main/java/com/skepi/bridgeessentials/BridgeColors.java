package com.skepi.bridgeessentials;

/**
 * HUD overlay palette (ARGB). Deliberately bright and varied - red, yellow, green, blue, purple,
 * pink - instead of the usual corporate orange. Change a value here to recolor that row.
 */
final class BridgeColors {
    private BridgeColors() {
    }

    // Panel background colour (no border); its opacity comes from the config.
    static final int PANEL = 0xFF101622;

    // Divider line between HUD groups (fades out towards both ends).
    static final int DIVIDER = 0xFFB9C6FF;

    // Timer / attempt state.
    static final int TIMER_LABEL = 0xFF4DA8FF;      // blue

    // Landings.
    static final int TAP_LABEL = 0xFF5AA9FF;        // blue
    static final int TAP_VALUE = 0xFFC08CFF;        // purple
    static final int FLAT_LABEL = 0xFFFFE14D;       // yellow
    static final int FLAT_VALUE = 0xFF7CFFA0;       // green
    static final int FAILED_TAP = 0xFFFF4D5E;       // red
    static final int FAILED_TAP_VALUE = 0xFFFF9AA5;
    static final int FAILED_FLAT = 0xFFFF5FB8;      // pink
    static final int FAILED_FLAT_VALUE = 0xFFFFA3D6;

    // Details that ride along with landings.
    static final int JUMPOVER_LABEL = 0xFFB98CFF;   // purple
    static final int EXTRA_LINE = 0xFF8FC1FF;       // light blue
    static final int NA = 0xFF9AA8BA;               // neutral grey-blue
    static final int NEUTRAL = 0xFFB9C3D0;
    static final int POSITIVE = 0xFF72F29A;         // green
    static final int NEGATIVE = 0xFFFF6677;         // red

    // Other sections.
    static final int TURNOUT_LABEL = 0xFF3DE0A8;    // teal-green
    static final int TURNOUT_VALUE = 0xFF9DFFD0;
    static final int PLATE_LABEL = 0xFFFF79C6;      // pink
    static final int PLATE_PREV_LABEL = 0xFFB69CFF; // purple
    static final int JUMP_TICKS_LABEL = 0xFF7CC4FF; // sky blue
    static final int BED_LABEL = 0xFFFF9A5B;        // orange
    static final int ATTEMPTS_LABEL = 0xFFFFE14D;   // yellow
}
