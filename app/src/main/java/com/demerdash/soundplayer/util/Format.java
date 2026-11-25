package com.demerdash.soundplayer.util;

import java.util.Locale;

/** Small, dependency-free formatting helpers (unit tested on the JVM). */
public final class Format {

    private Format() {
    }

    /** 3:05, 12:00, 1:02:03 */
    public static String duration(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000L;
        long h = totalSec / 3600L;
        long m = (totalSec % 3600L) / 60L;
        long s = totalSec % 60L;
        if (h > 0) {
            return h + ":" + two(m) + ":" + two(s);
        }
        return m + ":" + two(s);
    }

    private static String two(long v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    /** Human readable size: 512 B, 3.4 KB, 4.2 MB, 1.1 GB */
    public static String fileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    /** Hours/minutes split used for "1 h 20 min" texts. Returns {hours, minutes}. */
    public static int[] hoursMinutes(long ms) {
        long totalMin = Math.max(0, ms) / 60000L;
        return new int[]{(int) (totalMin / 60L), (int) (totalMin % 60L)};
    }

    /** Clamp helper. */
    public static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    public static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    /** Null/blank safe string. */
    public static boolean isBlank(String s) {
        return s == null || s.trim().length() == 0;
    }

    /** Strips the extension of a file name: "song.mp3" -> "song". */
    public static String stripExtension(String name) {
        if (name == null) return "";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
