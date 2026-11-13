package com.skepi.bridgeessentials;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import net.minecraft.util.BlockPos;

/**
 * Advanced diagnostic log for the tap/flat tracker.
 *
 * The log is written to {@code <minecraft dir>/logs/bridgeessentials-debug.log}. The previous
 * game session's log is kept as {@code bridgeessentials-debug-previous.log}, so a log is never
 * lost by simply relaunching the game. Every line carries a wall-clock time, the client tick
 * counter, and the attempt number shown in the HUD's {@code Attempts:} row, which makes it easy
 * to point at "attempt 37, the third landing" when reporting a problem.
 *
 * Line tags (grep for these):
 * <pre>
 *   SESSION / CONFIG   one-off header
 *   ATTEMPT-START      new attempt, direction selection
 *   ATTEMPT-END        result, reason, plus the landing table and the exact HUD rows
 *   INTERACT           raw right-click seen by the mod
 *   PLACE              a block registered (or duplicate / expired / dropped)
 *   TICK               per-tick player snapshot while an attempt is active
 *   TAKEOFF            ground to air transition
 *   LAND-SCAN          airborne-to-ground transition and the full 3x3 support grid
 *   LAND-WAIT          a landing snapshot retained while a same-tick placement is verified
 *   LAND-RESOLVE       the exact support block selected when a deferred landing is resolved
 *   LAND               the complete Tap/Flat decision for a landing
 *   BASELINE           block / height baseline updates
 *   WALK               support block changed while grounded (no jump)
 *   ADV                grounded-tick (Speed / 5b Flat) resolution
 *   FAILED             failed tap / flat sequence
 *   TURNOUT            turnout forecast
 *   ANOMALY            something that is usually the cause of a wrong or missing row
 * </pre>
 */
final class BridgeLog {
    private static final long MAX_BYTES = 16L * 1024L * 1024L;
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static boolean enabled;
    private static boolean perTick;
    private static File file;
    private static File previousFile;
    private static Writer writer;
    private static long bytesWritten;
    private static int clientTick;
    private static int attempt;
    private static int anomalyCount;
    private static boolean failureReported;

    private BridgeLog() {
    }

    /** Opens the log file. Called once from pre-initialization; failures never affect the mod. */
    static void init(File minecraftDirectory, boolean logEnabled, boolean logPerTick,
                     String modVersion) {
        enabled = logEnabled && minecraftDirectory != null;
        perTick = logPerTick;
        if (!enabled) {
            return;
        }
        synchronized (LOCK) {
            try {
                File directory = new File(minecraftDirectory, "logs");
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IOException("could not create " + directory);
                }
                file = new File(directory, "bridgeessentials-debug.log");
                previousFile = new File(directory, "bridgeessentials-debug-previous.log");
                if (file.exists()) {
                    if (previousFile.exists()) {
                        previousFile.delete();
                    }
                    file.renameTo(previousFile);
                }
                openWriter(false);
                writeRaw(timestamp() + " T0 A0 [SESSION] BridgeEssentials " + modVersion
                        + " | java " + System.getProperty("java.version")
                        + " | os " + System.getProperty("os.name") + "\n");
                writeRaw(timestamp() + " T0 A0 [SESSION] log file: " + file.getAbsolutePath()
                        + " (perTick=" + perTick + ")\n");
                System.out.println("[BridgeEssentials] Debug log: " + file.getAbsolutePath());
            } catch (Throwable t) {
                disable(t);
            }
        }
    }

    static boolean isEnabled() {
        return enabled;
    }

    static boolean isPerTick() {
        return enabled && perTick;
    }

    /** Called every client tick so every line is stamped with the tick and attempt number. */
    static void setContext(int tick, int attemptNumber) {
        clientTick = tick;
        attempt = attemptNumber;
    }

    static void log(String tag, String message) {
        if (!enabled) {
            return;
        }
        synchronized (LOCK) {
            if (writer == null) {
                return;
            }
            try {
                if (bytesWritten > MAX_BYTES) {
                    rotate();
                }
                writeRaw(timestamp() + " T" + clientTick + " A" + attempt + " [" + tag + "] "
                        + message + "\n");
            } catch (Throwable t) {
                disable(t);
            }
        }
    }

    /** An anomaly is a log line that is almost always the explanation for a wrong/missing row. */
    static void anomaly(String code, String message) {
        anomalyCount++;
        log("ANOMALY", "!! " + code + " -- " + message);
    }

    static int getAnomalyCount() {
        return anomalyCount;
    }

    /** Anomalies recorded since the last call; used for the per-attempt summary. */
    static int takeAnomalyCount() {
        int count = anomalyCount;
        anomalyCount = 0;
        return count;
    }

    static void flush() {
        if (!enabled) {
            return;
        }
        synchronized (LOCK) {
            try {
                if (writer != null) {
                    writer.flush();
                }
            } catch (Throwable ignored) {
                // Logging must never disturb tracking.
            }
        }
    }

    // ------------------------------------------------------------------ formatting helpers

    /** Full-precision number so offsets can be recomputed by hand from the log. */
    static String f(double value) {
        return String.format(Locale.US, "%.6f", value);
    }

    /** Shorter forms for the per-tick lines (keeps long sessions small). */
    static String f5(double value) {
        return String.format(Locale.US, "%.5f", value);
    }

    static String f2(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    static String pos(BlockPos position) {
        if (position == null) {
            return "null";
        }
        return "(" + position.getX() + "," + position.getY() + "," + position.getZ() + ")";
    }

    // ------------------------------------------------------------------ internals

    private static void openWriter(boolean append) throws IOException {
        writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file, append), "UTF-8"), 1 << 16);
        bytesWritten = 0L;
    }

    private static void rotate() throws IOException {
        writer.flush();
        writer.close();
        if (previousFile.exists()) {
            previousFile.delete();
        }
        file.renameTo(previousFile);
        openWriter(false);
        writeRaw(timestamp() + " T" + clientTick + " A" + attempt
                + " [SESSION] log rotated (size cap reached); earlier lines are in "
                + previousFile.getName() + "\n");
    }

    private static void writeRaw(String line) throws IOException {
        writer.write(line);
        bytesWritten += line.length();
        // Flushing every line keeps the file complete even if the game crashes or is killed.
        writer.flush();
    }

    private static String timestamp() {
        return TIME.format(new Date());
    }

    private static void disable(Throwable t) {
        enabled = false;
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (Throwable ignored) {
            // Nothing else to do.
        }
        writer = null;
        if (!failureReported) {
            failureReported = true;
            System.out.println("[BridgeEssentials] Debug log disabled: " + t);
        }
    }
}
