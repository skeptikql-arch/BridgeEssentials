package com.skepi.bridgeessentials;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Personal bests shown in the settings menu's "Best Coords" column. Everything is kept in whole
 * thousandths so values match the three decimals of the HUD exactly, and is saved to
 * {@code <minecraft>/config/bridgeessentials-bests.properties} so it survives relaunches. The
 * lifetime totals (playtime in game, attempts) live in the same file.
 *
 * <p>This class contains no Minecraft code on purpose: the tracker feeds it plain {@link Row}
 * records and it can be tested on its own.
 */
public final class BridgeBests {
    /** A "tap" row is a normal Tap / Flat; a "held" row is a Speed (a tap held on the ground). */
    public enum Kind {
        TAP("Tap", "tap"),
        HELD("Held", "held");

        public final String title;
        final String key;

        Kind(String title, String key) {
            this.title = title;
            this.key = key;
        }

        Kind other() {
            return this == TAP ? HELD : TAP;
        }
    }

    public enum ComboType {
        FIVE_B("5b Flat", "5b"),
        FOUR_B_SPEED("4b Speed", "4bSpeed");

        public final String title;
        final String key;

        ComboType(String title, String key) {
            this.title = title;
            this.key = key;
        }
    }

    /**
     * A stored best. {@code net} is only used by the AFTER stats (the sum of the tap gains);
     * {@code offset} is the jumpover offset of the row that set a Gain / Highest Coordinate best.
     */
    public static final class Entry {
        public final long value;
        public final long net;
        public final boolean hasOffset;
        public final long offset;

        Entry(long value, long net, boolean hasOffset, long offset) {
            this.value = value;
            this.net = net;
            this.hasOffset = hasOffset;
            this.offset = offset;
        }
    }

    /** One finished Tap / Flat / Speed row (or a failed row) as the tracker saw it. */
    public static final class Row {
        /** Own-block landing counter of this row; 0 or less for a failed row (never a landing). */
        public int landingNumber;
        public boolean failed;
        public boolean tap;
        /** True when the row is a Speed ("held"). */
        public boolean held;
        /** The coordinate shown for the row (last ground tick for speeds / 5b flats). */
        public double coordinate;
        /** Gain vs the previous row (3 blocks subtracted for a Tap, 4 for a Flat), thousandths. */
        public boolean hasGain;
        public long gain;
        /** Coordinate of the previous ground tick (previous row), when there is a usable one. */
        public boolean hasPrev;
        public double prevCoordinate;
        public double jumpoverOffset;
        /** Flat time in seconds when this row is a 5b flat, otherwise NaN. */
        public double flatTimingSeconds = Double.NaN;
        /** Grounded ticks when this row is a 4b Speed (1 = "4b Speed", 2 = "2t 4b Speed"), else 0. */
        public int speedTicks;
    }

    private static final String KEY_GAIN = "gain.";
    // The turnout best is the turnout gain (the +/- shown on the HUD); the old "turnout" key held the
    // coordinate and is ignored.
    private static final String KEY_TURNOUT = "turnoutGain";
    private static final String KEY_HIGHEST = "highest.";
    private static final int MAX_CHAIN = 40;
    private static final long SAVE_EVERY_MS = 30000L;

    private static final Map<String, Entry> ENTRIES = new TreeMap<String, Entry>();
    private static File file;
    private static boolean hookAdded;

    // Lifetime totals.
    private static long playtimeMs;
    private static long attemptTotal;
    private static long lastTickMs;
    private static long lastSaveMs;
    private static boolean playtimeDirty;

    // Remembered inputs.
    private static Kind gainKind = Kind.TAP;
    private static Kind highestKind = Kind.TAP;
    private static Kind afterFlatKind = Kind.TAP;
    private static Kind comboKind = Kind.TAP;
    private static String afterCount = "4";
    private static String afterTiming = "1.05";
    // "Best [#]th [tap/held] after [time] [5b Flat / 4b Speed]".
    private static String comboTap = "1";
    private static ComboType comboType = ComboType.FIVE_B;
    private static final String[] COMBO_TIME = {"1.15", "1"};
    // "Best [time] [5b Flat / 4b Speed]" (the 5b flat / 4b Speed row itself).
    private static ComboType onType = ComboType.FIVE_B;
    private static final String[] ON_TIME = {"1.15", "1"};

    private BridgeBests() {
    }

    // ------------------------------------------------------------------ persistence

    public static synchronized void load(File target) {
        file = target;
        ENTRIES.clear();
        Properties props = new Properties();
        if (target != null && target.isFile()) {
            InputStream in = null;
            try {
                in = new FileInputStream(target);
                props.load(in);
            } catch (IOException ignored) {
                // A broken file just starts fresh.
            } finally {
                close(in);
            }
        }
        for (String name : props.stringPropertyNames()) {
            if (name.startsWith("best.")) {
                Entry entry = parseEntry(props.getProperty(name));
                if (entry != null) {
                    ENTRIES.put(name.substring(5), entry);
                }
            }
        }
        migrateOldKeys();
        afterCount = orDefault(normalizeCount(props.getProperty("input.afterCount")), afterCount);
        afterTiming = orDefault(normalizeTiming(props.getProperty("input.afterTiming")), afterTiming);
        comboTap = orDefault(normalizeCount(props.getProperty("input.comboTap")), comboTap);
        gainKind = kindOf(props.getProperty("input.gainKind"));
        highestKind = kindOf(props.getProperty("input.highestKind"));
        afterFlatKind = kindOf(props.getProperty("input.afterFlatKind"));
        comboKind = kindOf(props.getProperty("input.comboKind"));
        comboType = typeOf(props.getProperty("input.comboType"));
        onType = typeOf(props.getProperty("input.onType"));
        COMBO_TIME[0] = orDefault(normalizeTiming(props.getProperty("input.comboTime.FIVE_B")),
                COMBO_TIME[0]);
        COMBO_TIME[1] = orDefault(normalizeCount(props.getProperty("input.comboTime.FOUR_B_SPEED")),
                COMBO_TIME[1]);
        ON_TIME[0] = orDefault(normalizeTiming(props.getProperty("input.onTime.FIVE_B")), ON_TIME[0]);
        ON_TIME[1] = orDefault(normalizeCount(props.getProperty("input.onTime.FOUR_B_SPEED")),
                ON_TIME[1]);
        playtimeMs = parseLong(props.getProperty("total.playtimeMs"));
        attemptTotal = parseLong(props.getProperty("total.attempts"));
        lastTickMs = 0L;
        lastSaveMs = System.currentTimeMillis();
        if (!hookAdded) {
            hookAdded = true;
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    save();
                }
            }, "BridgeEssentials-bests-save"));
        }
    }

    /** Old files had one Gain / Highest Tap / "after flat" best with no tap-or-held split. */
    private static void migrateOldKeys() {
        Map<String, Entry> moved = new TreeMap<String, Entry>();
        for (Map.Entry<String, Entry> e : ENTRIES.entrySet()) {
            String key = e.getKey();
            if (key.equals("gain")) {
                moved.put("gain.tap", e.getValue());
            } else if (key.equals("highestTap")) {
                moved.put("highest.tap", e.getValue());
            } else if (key.startsWith("afterFlat.") && !key.endsWith(".tap") && !key.endsWith(".held")) {
                moved.put(key + ".tap", e.getValue());
            }
        }
        ENTRIES.remove("gain");
        ENTRIES.remove("highestTap");
        for (String key : new ArrayList<String>(ENTRIES.keySet())) {
            if (key.startsWith("afterFlat.") && !key.endsWith(".tap") && !key.endsWith(".held")) {
                ENTRIES.remove(key);
            }
        }
        for (Map.Entry<String, Entry> e : moved.entrySet()) {
            if (!ENTRIES.containsKey(e.getKey())) {
                ENTRIES.put(e.getKey(), e.getValue());
            }
        }
    }

    private static Kind kindOf(String text) {
        return "held".equalsIgnoreCase(text) ? Kind.HELD : Kind.TAP;
    }

    private static ComboType typeOf(String text) {
        return text != null && text.equalsIgnoreCase("4b Speed")
                ? ComboType.FOUR_B_SPEED : ComboType.FIVE_B;
    }

    private static String orDefault(String value, String fallback) {
        return value != null ? value : fallback;
    }

    private static long parseLong(String text) {
        try {
            return text == null ? 0L : Math.max(0L, Long.parseLong(text.trim()));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public static synchronized void save() {
        if (file == null) {
            return;
        }
        Properties props = new Properties();
        for (Map.Entry<String, Entry> e : ENTRIES.entrySet()) {
            Entry v = e.getValue();
            props.setProperty("best." + e.getKey(), v.value + "," + v.net
                    + (v.hasOffset ? "," + v.offset : ""));
        }
        props.setProperty("input.afterCount", afterCount);
        props.setProperty("input.afterTiming", afterTiming);
        props.setProperty("input.comboTap", comboTap);
        props.setProperty("input.gainKind", gainKind.key);
        props.setProperty("input.highestKind", highestKind.key);
        props.setProperty("input.afterFlatKind", afterFlatKind.key);
        props.setProperty("input.comboKind", comboKind.key);
        props.setProperty("input.comboType", comboType.title);
        props.setProperty("input.onType", onType.title);
        props.setProperty("input.comboTime.FIVE_B", COMBO_TIME[0]);
        props.setProperty("input.comboTime.FOUR_B_SPEED", COMBO_TIME[1]);
        props.setProperty("input.onTime.FIVE_B", ON_TIME[0]);
        props.setProperty("input.onTime.FOUR_B_SPEED", ON_TIME[1]);
        props.setProperty("total.playtimeMs", Long.toString(playtimeMs));
        props.setProperty("total.attempts", Long.toString(attemptTotal));
        OutputStream out = null;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            out = new FileOutputStream(file);
            props.store(out, "BridgeEssentials personal bests (values are thousandths)");
            playtimeDirty = false;
            lastSaveMs = System.currentTimeMillis();
        } catch (IOException ignored) {
            // Not being able to save must never break gameplay.
        } finally {
            close(out);
        }
    }

    private static void close(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    private static Entry parseEntry(String text) {
        if (text == null) {
            return null;
        }
        String[] parts = text.split(",");
        try {
            long value = Long.parseLong(parts[0].trim());
            long net = parts.length > 1 ? Long.parseLong(parts[1].trim()) : 0L;
            boolean hasOffset = parts.length > 2;
            long offset = hasOffset ? Long.parseLong(parts[2].trim()) : 0L;
            return new Entry(value, net, hasOffset, offset);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Clears every stored best (the typed inputs and the lifetime totals are kept). */
    public static synchronized void resetAll() {
        ENTRIES.clear();
        save();
    }

    // ------------------------------------------------------------------- totals

    /** Called every client tick; time only counts while a world is loaded. */
    public static synchronized void playtimeTick(boolean inGame) {
        long now = System.currentTimeMillis();
        if (inGame) {
            if (lastTickMs > 0L && now > lastTickMs) {
                // A long gap (the game froze) must not count as playtime.
                playtimeMs += Math.min(now - lastTickMs, 1000L);
                playtimeDirty = true;
            }
            lastTickMs = now;
        } else {
            lastTickMs = 0L;
        }
        if (playtimeDirty && now - lastSaveMs >= SAVE_EVERY_MS) {
            save();
        }
    }

    public static synchronized void addAttempt() {
        attemptTotal++;
        save();
    }

    public static synchronized long totalPlaytimeMs() {
        return playtimeMs;
    }

    public static synchronized long totalAttempts() {
        return attemptTotal;
    }

    /** "0d 0h 0m 0s"; the day count has no upper limit. */
    public static String formatPlaytime(long millis) {
        long total = Math.max(0L, millis) / 1000L;
        return (total / 86400L) + "d " + ((total % 86400L) / 3600L) + "h "
                + ((total % 3600L) / 60L) + "m " + (total % 60L) + "s";
    }

    // ------------------------------------------------------------------ recording

    private static synchronized void offer(String key, long value, long net, boolean hasOffset,
                                           long offset) {
        Entry old = ENTRIES.get(key);
        if (old == null || value > old.value) {
            ENTRIES.put(key, new Entry(value, net, hasOffset, offset));
            save();
        }
    }

    private static void offer(String key, long value, long net) {
        offer(key, value, net, false, 0L);
    }

    public static void offerTurnout(long thousandths) {
        offer(KEY_TURNOUT, thousandths, 0L);
    }

    // ------------------------------------------------------------------- reading

    public static synchronized Entry gain() {
        return ENTRIES.get(KEY_GAIN + gainKind.key);
    }

    public static synchronized Entry turnout() {
        return ENTRIES.get(KEY_TURNOUT);
    }

    public static synchronized Entry highestTap() {
        return ENTRIES.get(KEY_HIGHEST + highestKind.key);
    }

    /** Best Nth tap/held after a flat of the typed timing, or null when none yet / bad input. */
    public static synchronized Entry afterFlat() {
        return ENTRIES.get(afterFlatKey(afterTiming, Integer.parseInt(afterCount), afterFlatKind));
    }

    /** "Best [#]th [tap/held] after [time] [5b Flat / 4b Speed]". */
    public static synchronized Entry combo() {
        return ENTRIES.get("after" + comboType.key + "." + comboTime() + "." + comboTap + "."
                + comboKind.key);
    }

    /** "Best [time] [5b Flat / 4b Speed]": the 5b Flat / 4b Speed row itself. */
    public static synchronized Entry onStat() {
        return ENTRIES.get("on" + onType.key + "." + onTime());
    }

    private static String afterFlatKey(String timing, int count, Kind kind) {
        return "afterFlat." + timing + "." + count + "." + kind.key;
    }

    // -------------------------------------------------------------------- inputs

    public static Kind getGainKind() {
        return gainKind;
    }

    public static Kind getHighestKind() {
        return highestKind;
    }

    public static Kind getAfterFlatKind() {
        return afterFlatKind;
    }

    public static Kind getComboKind() {
        return comboKind;
    }

    public static void toggleGainKind() {
        gainKind = gainKind.other();
        save();
    }

    public static void toggleHighestKind() {
        highestKind = highestKind.other();
        save();
    }

    public static void toggleAfterFlatKind() {
        afterFlatKind = afterFlatKind.other();
        save();
    }

    public static void toggleComboKind() {
        comboKind = comboKind.other();
        save();
    }

    public static String getAfterCount() {
        return afterCount;
    }

    public static String getAfterTiming() {
        return afterTiming;
    }

    public static ComboType getComboType() {
        return comboType;
    }

    public static ComboType getOnType() {
        return onType;
    }

    /** The combo card's time box: a flat time (1.15) for 5b Flat, grounded ticks for 4b Speed. */
    public static String comboTime() {
        return COMBO_TIME[comboType == ComboType.FIVE_B ? 0 : 1];
    }

    /** The "#th" number of the combo card. */
    public static String comboTap() {
        return comboTap;
    }

    public static boolean comboTimeIsTiming() {
        return comboType == ComboType.FIVE_B;
    }

    public static String onTime() {
        return ON_TIME[onType == ComboType.FIVE_B ? 0 : 1];
    }

    public static boolean onTimeIsTiming() {
        return onType == ComboType.FIVE_B;
    }

    public static void setAfterCount(String text) {
        String clean = normalizeCount(text);
        if (clean != null) {
            afterCount = clean;
            save();
        }
    }

    public static void setAfterTiming(String text) {
        String clean = normalizeTiming(text);
        if (clean != null) {
            afterTiming = clean;
            save();
        }
    }

    public static void setComboTime(String text) {
        String clean = comboTimeIsTiming() ? normalizeTiming(text) : normalizeCount(text);
        if (clean != null) {
            COMBO_TIME[comboType == ComboType.FIVE_B ? 0 : 1] = clean;
            save();
        }
    }

    public static void setComboTap(String text) {
        String clean = normalizeCount(text);
        if (clean != null) {
            comboTap = clean;
            save();
        }
    }

    public static void setOnTime(String text) {
        String clean = onTimeIsTiming() ? normalizeTiming(text) : normalizeCount(text);
        if (clean != null) {
            ON_TIME[onType == ComboType.FIVE_B ? 0 : 1] = clean;
            save();
        }
    }

    public static void toggleComboType() {
        comboType = comboType == ComboType.FIVE_B ? ComboType.FOUR_B_SPEED : ComboType.FIVE_B;
        save();
    }

    public static void toggleOnType() {
        onType = onType == ComboType.FIVE_B ? ComboType.FOUR_B_SPEED : ComboType.FIVE_B;
        save();
    }

    /** A whole number 1-99 ("4", "04", "4t" all give "4"), or null. */
    static String normalizeCount(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim().toLowerCase(Locale.US);
        if (trimmed.endsWith("t")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        try {
            int value = Integer.parseInt(trimmed);
            return value >= 1 && value <= 99 ? Integer.toString(value) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A flat time such as "1.05", "1.1" ("1.10" and "1.100" give "1.1"), or null. */
    static String normalizeTiming(String text) {
        if (text == null) {
            return null;
        }
        try {
            double value = Double.parseDouble(text.trim());
            if (value < 0.05D || value > 99.0D) {
                return null;
            }
            return formatTiming(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Flat time as the HUD writes it: two decimals, trailing zeros removed. */
    public static String formatTiming(double seconds) {
        String formatted = String.format(Locale.US, "%.2f", seconds);
        while (formatted.indexOf('.') >= 0 && formatted.endsWith("0")) {
            formatted = formatted.substring(0, formatted.length() - 1);
        }
        if (formatted.endsWith(".")) {
            formatted = formatted.substring(0, formatted.length() - 1);
        }
        return formatted;
    }

    // ---------------------------------------------------------------- formatting

    /** ".492", "1.012", "-.044"; with {@code plus} a positive value is written "+.492". */
    public static String format(long thousandths, boolean plus) {
        long abs = Math.abs(thousandths);
        String body = abs >= 1000
                ? String.format(Locale.US, "%d.%03d", abs / 1000, abs % 1000)
                : String.format(Locale.US, ".%03d", abs);
        if (thousandths < 0) {
            return "-" + body;
        }
        return plus && thousandths > 0 ? "+" + body : body;
    }

    /** "1st", "2nd", "3rd", "4th"... */
    public static String ordinal(int n) {
        int mod100 = n % 100;
        if (mod100 >= 11 && mod100 <= 13) {
            return n + "th";
        }
        switch (n % 10) {
            case 1:
                return n + "st";
            case 2:
                return n + "nd";
            case 3:
                return n + "rd";
            default:
                return n + "th";
        }
    }

    // ------------------------------------------------------------ attempt tracking

    private static final class Chain {
        final String prefix;
        final long base;
        long sum;
        int count;
        int lastLanding;
        boolean alive = true;
        // Kinds of the rows counted so far: a chain stat is only updated while all were taps or
        // all were helds (speeds); one of each makes it ineligible.
        boolean sawTap;
        boolean sawHeld;

        Chain(String prefix, long base, int lastLanding) {
            this.prefix = prefix;
            this.base = base;
            this.lastLanding = lastLanding;
        }
    }

    /**
     * Per-attempt state. A "chain" starts at an opening flat, a 5b flat or a 4b Speed. Its start
     * value is that row's displayed decimal (.230). Every later Tap/Flat/Speed adds its gain, so
     * the Nth row after it is start + gain1 + ... + gainN (.230 - .083 - .004 + .045 = .188),
     * written as a decimal. The value is stored under (type, time, N, tap-or-held of the Nth row).
     * A landing that produced no row, or a row without a gain, breaks the chain; a failed row
     * counts as the last row of the chain.
     */
    public static final class AttemptTracker {
        private final List<Chain> chains = new ArrayList<Chain>();

        public void reset() {
            chains.clear();
        }

        /**
         * The attempt's opening flat. {@code plain} = an ordinary "1.05 Flat" (no 5b), otherwise it
         * is a 5b flat of the given timing.
         */
        public void openingFlat(boolean plain, double timingSeconds, double coordinate,
                                int landingNumber) {
            long base = fraction(coordinate);
            if (plain) {
                chains.add(new Chain("afterFlat." + formatTiming(timingSeconds), base, landingNumber));
            } else if (!Double.isNaN(timingSeconds)) {
                // The opening 5b flat (e.g. 1.15 5b) is a 5b row too: ON stat, then an AFTER chain.
                String time = formatTiming(timingSeconds);
                offer("on5b." + time, base, 0L);
                chains.add(new Chain("after5b." + time, base, landingNumber));
            }
        }

        public void row(Row row) {
            Kind kind = row.held ? Kind.HELD : Kind.TAP;
            long offset = Math.round(row.jumpoverOffset * 1000.0D);

            if (row.failed || row.landingNumber <= 0) {
                // A failed Tap/Flat still counts as the next row of every running chain (it ends
                // the attempt, so the chains stop after it) and as a Gain / Highest candidate.
                for (Chain chain : chains) {
                    if (chain.alive && row.hasGain && chain.count < MAX_CHAIN) {
                        extend(chain, row, kind);
                    }
                    chain.alive = false;
                }
                offerPlainStats(row, kind, offset);
                return;
            }

            // 1. Extend the chains that started earlier.
            for (Chain chain : chains) {
                if (!chain.alive) {
                    continue;
                }
                if (!row.hasGain || row.landingNumber != chain.lastLanding + 1
                        || chain.count >= MAX_CHAIN) {
                    chain.alive = false;
                    continue;
                }
                extend(chain, row, kind);
                chain.lastLanding = row.landingNumber;
            }

            // 2. Plain stats.
            offerPlainStats(row, kind, offset);

            // 3. This row may itself be a 5b flat / 4b Speed: ON stat, then start an AFTER chain.
            // The ON value is simply the row's displayed decimal.
            if (!row.tap && !Double.isNaN(row.flatTimingSeconds)) {
                long on = fraction(row.coordinate);
                String time = formatTiming(row.flatTimingSeconds);
                offer("on5b." + time, on, 0L);
                chains.add(new Chain("after5b." + time, on, row.landingNumber));
            } else if (row.tap && row.speedTicks > 0) {
                long on = fraction(row.coordinate);
                offer("on4bSpeed." + row.speedTicks, on, 0L);
                chains.add(new Chain("after4bSpeed." + row.speedTicks, on, row.landingNumber));
            }
        }

        private static void extend(Chain chain, Row row, Kind kind) {
            chain.sum += row.gain;
            chain.count++;
            if (kind == Kind.HELD) {
                chain.sawHeld = true;
            } else {
                chain.sawTap = true;
            }
            if (chain.sawTap && chain.sawHeld) {
                return;
            }
            // After a plain flat, a running value below 0 (momentum lost past the flat's own
            // decimal) is not a real high: wrapping it would show e.g. -.091 as .909. 5b chains
            // keep their existing wrap behaviour.
            if (chain.prefix.startsWith("afterFlat.") && chain.base + chain.sum < 0L) {
                return;
            }
            offer(chain.prefix + "." + chain.count + "." + (chain.sawHeld ? Kind.HELD : Kind.TAP).key, wrap(chain.base + chain.sum),
                    chain.sum);
        }

        private static void offerPlainStats(Row row, Kind kind, long offset) {
            if (!row.hasGain) {
                return;
            }
            offer(KEY_GAIN + kind.key, row.gain, 0L, true, offset);
            if (row.gain > 0 && row.jumpoverOffset < 0.0D) {
                offer(KEY_HIGHEST + kind.key, fraction(row.coordinate), 0L, true, offset);
            }
        }

        /** A running value as a decimal: -.047 is .953 (the tap's displayed coordinate). */
        private static long wrap(long value) {
            return ((value % 1000L) + 1000L) % 1000L;
        }

        /** The displayed decimal of a coordinate in thousandths, 0 to 999. */
        private static long fraction(double coordinate) {
            return Math.round(Math.abs(coordinate) * 1000.0D) % 1000L;
        }
    }

    /** Package-private view for tests. */
    static Map<String, Entry> entries() {
        return new HashMap<String, Entry>(ENTRIES);
    }
}
