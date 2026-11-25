package com.demerdash.soundplayer.data;

import android.content.Context;
import android.content.SharedPreferences;

/** All user settings in one place. */
public final class Prefs {

    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_SYSTEM = "system";

    private static SharedPreferences sp;

    private Prefs() {
    }

    public static synchronized SharedPreferences get(Context c) {
        if (sp == null) sp = c.getApplicationContext().getSharedPreferences("soundplayer", Context.MODE_PRIVATE);
        return sp;
    }

    // ---- appearance
    public static String theme(Context c) {
        return get(c).getString("theme", THEME_DARK);
    }

    public static void setTheme(Context c, String v) {
        get(c).edit().putString("theme", v).apply();
    }

    public static int accent(Context c) {
        return get(c).getInt("accent", 0);
    }

    public static void setAccent(Context c, int v) {
        get(c).edit().putInt("accent", v).apply();
    }

    // ---- library
    public static int minDurationSec(Context c) {
        return get(c).getInt("min_duration", 30);
    }

    public static void setMinDurationSec(Context c, int v) {
        get(c).edit().putInt("min_duration", v).apply();
    }

    public static int sort(Context c, String key, int def) {
        return get(c).getInt("sort_" + key, def);
    }

    public static void setSort(Context c, String key, int v) {
        get(c).edit().putInt("sort_" + key, v).apply();
    }

    public static int lastTab(Context c) {
        return get(c).getInt("last_tab", 0);
    }

    public static void setLastTab(Context c, int v) {
        get(c).edit().putInt("last_tab", v).apply();
    }

    // ---- playback
    public static boolean gapless(Context c) {
        return get(c).getBoolean("gapless", true);
    }

    public static boolean fade(Context c) {
        return get(c).getBoolean("fade", true);
    }

    public static boolean keepScreenOn(Context c) {
        return get(c).getBoolean("keep_screen_on", false);
    }

    public static boolean rewindOnResume(Context c) {
        return get(c).getBoolean("rewind_resume", false);
    }

    public static void setBool(Context c, String key, boolean v) {
        get(c).edit().putBoolean(key, v).apply();
    }

    public static float speed(Context c) {
        return get(c).getFloat("speed", 1f);
    }

    public static float pitch(Context c) {
        return get(c).getFloat("pitch", 1f);
    }

    public static void setSpeedPitch(Context c, float speed, float pitch) {
        get(c).edit().putFloat("speed", speed).putFloat("pitch", pitch).apply();
    }

    // ---- equalizer
    public static boolean eqEnabled(Context c) {
        return get(c).getBoolean("eq_enabled", false);
    }

    public static void setEqEnabled(Context c, boolean v) {
        get(c).edit().putBoolean("eq_enabled", v).apply();
    }

    /** Preset key, or "custom". */
    public static String eqPreset(Context c) {
        return get(c).getString("eq_preset", "flat");
    }

    public static String eqGains(Context c) {
        return get(c).getString("eq_gains", "");
    }

    public static void setEq(Context c, String preset, String gains) {
        get(c).edit().putString("eq_preset", preset).putString("eq_gains", gains).apply();
    }

    public static int bass(Context c) {
        return get(c).getInt("eq_bass", 0);
    }

    public static int virtualizer(Context c) {
        return get(c).getInt("eq_virt", 0);
    }

    public static int loudness(Context c) {
        return get(c).getInt("eq_loud", 0);
    }

    /** -100 (left) .. 100 (right) */
    public static int balance(Context c) {
        return get(c).getInt("eq_balance", 0);
    }

    public static void setInt(Context c, String key, int v) {
        get(c).edit().putInt(key, v).apply();
    }

    // ---- last played (for the widget when the service is not running)
    public static void setLastSong(Context c, String title, String artist) {
        get(c).edit().putString("last_title", title).putString("last_artist", artist).apply();
    }

    public static String lastTitle(Context c) {
        return get(c).getString("last_title", null);
    }

    public static String lastArtist(Context c) {
        return get(c).getString("last_artist", null);
    }
}
