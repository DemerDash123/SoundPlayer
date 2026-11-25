package com.demerdash.soundplayer.playback;

import android.content.Context;
import android.media.audiofx.BassBoost;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.Virtualizer;
import android.os.Build;
import android.util.Log;

import com.demerdash.soundplayer.data.Prefs;

/**
 * Audio effects attached to the player's audio session.
 * Uses a true 10-band DynamicsProcessing EQ (with a soft limiter) on Android 9+, and falls back to the
 * device's own Equalizer (usually 5 bands) elsewhere – our 10-band curve is mapped onto it.
 */
public final class EffectsManager {

    private static final String TAG = "Effects";

    private final Context ctx;
    private final int session;
    private DynamicsProcessing dp;
    private Equalizer eq;
    private BassBoost bass;
    private Virtualizer virt;
    private LoudnessEnhancer loud;
    private int[] sysFreqs = new int[0];
    private short sysMin = -1500;
    private short sysMax = 1500;
    private final float[] cutoffs = EqPresets.cutoffs();
    private float[] gains = new float[EqPresets.FREQS.length];
    private boolean enabled;
    private int bassStrength;
    private int virtStrength;
    private int loudStrength;

    public EffectsManager(Context context, int audioSession) {
        ctx = context.getApplicationContext();
        session = audioSession;
        createEq();
        try {
            bass = new BassBoost(0, session);
        } catch (Throwable t) {
            Log.w(TAG, "BassBoost unavailable", t);
            bass = null;
        }
        try {
            virt = new Virtualizer(0, session);
        } catch (Throwable t) {
            Log.w(TAG, "Virtualizer unavailable", t);
            virt = null;
        }
        try {
            loud = new LoudnessEnhancer(session);
        } catch (Throwable t) {
            Log.w(TAG, "LoudnessEnhancer unavailable", t);
            loud = null;
        }
        applyFromPrefs();
    }

    private void createEq() {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                int n = EqPresets.FREQS.length;
                DynamicsProcessing.Config.Builder b = new DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                        true, n, false, 0, false, 0, true);
                DynamicsProcessing.Eq e = new DynamicsProcessing.Eq(true, true, n);
                for (int i = 0; i < n; i++) e.setBand(i, new DynamicsProcessing.EqBand(true, cutoffs[i], 0f));
                b.setPreEqAllChannelsTo(e);
                b.setLimiterAllChannelsTo(new DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f));
                dp = new DynamicsProcessing(0, session, b.build());
                return;
            } catch (Throwable t) {
                Log.w(TAG, "DynamicsProcessing unavailable, using device equalizer", t);
                dp = null;
            }
        }
        try {
            eq = new Equalizer(0, session);
            short n = eq.getNumberOfBands();
            sysFreqs = new int[n];
            for (short i = 0; i < n; i++) sysFreqs[i] = eq.getCenterFreq(i) / 1000;
            short[] r = eq.getBandLevelRange();
            sysMin = r[0];
            sysMax = r[1];
        } catch (Throwable t) {
            Log.w(TAG, "Equalizer unavailable", t);
            eq = null;
        }
    }

    // ------------------------------------------------------------------ info

    public boolean isAvailable() {
        return dp != null || eq != null;
    }

    public boolean isTenBand() {
        return dp != null;
    }

    public int deviceBandCount() {
        return dp != null ? EqPresets.FREQS.length : sysFreqs.length;
    }

    public boolean hasBass() {
        return bass != null;
    }

    public boolean hasVirtualizer() {
        return virt != null;
    }

    public boolean hasLoudness() {
        return loud != null;
    }

    // ------------------------------------------------------------------ control

    public void applyFromPrefs() {
        String preset = Prefs.eqPreset(ctx);
        int pi = EqPresets.indexOf(preset);
        float[] g = pi >= 0 ? EqPresets.GAINS[pi].clone() : EqPresets.parse(Prefs.eqGains(ctx), EqPresets.FREQS.length);
        bassStrength = Prefs.bass(ctx);
        virtStrength = Prefs.virtualizer(ctx);
        loudStrength = Prefs.loudness(ctx);
        enabled = Prefs.eqEnabled(ctx);
        setGains(g);
        applyEnabled();
        setBass(bassStrength);
        setVirtualizer(virtStrength);
        setLoudness(loudStrength);
    }

    public void setEnabled(boolean on) {
        enabled = on;
        applyEnabled();
        setBass(bassStrength);
        setVirtualizer(virtStrength);
        setLoudness(loudStrength);
    }

    private void applyEnabled() {
        try {
            if (dp != null) dp.setEnabled(enabled);
            if (eq != null) eq.setEnabled(enabled);
        } catch (Throwable t) {
            Log.w(TAG, "enable", t);
        }
    }

    public void setGains(float[] g) {
        if (g == null || g.length != EqPresets.FREQS.length) return;
        gains = g.clone();
        try {
            if (dp != null) {
                for (int i = 0; i < gains.length; i++) {
                    dp.setPreEqBandAllChannelsTo(i, new DynamicsProcessing.EqBand(true, cutoffs[i], gains[i]));
                }
            } else if (eq != null) {
                for (int i = 0; i < sysFreqs.length; i++) {
                    float db = EqPresets.gainAt(EqPresets.FREQS, gains, sysFreqs[i]);
                    int mb = Math.round(db * 100f);
                    mb = Math.max(sysMin, Math.min(sysMax, mb));
                    eq.setBandLevel((short) i, (short) mb);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "gains", t);
        }
    }

    public void setBand(int band, float db) {
        if (band < 0 || band >= gains.length) return;
        float[] g = gains.clone();
        g[band] = db;
        setGains(g);
    }

    /** 0..1000 */
    public void setBass(int strength) {
        bassStrength = strength;
        if (bass == null) return;
        try {
            if (bass.getStrengthSupported()) bass.setStrength((short) Math.max(0, Math.min(1000, strength)));
            bass.setEnabled(enabled && strength > 0);
        } catch (Throwable t) {
            Log.w(TAG, "bass", t);
        }
    }

    /** 0..1000 */
    public void setVirtualizer(int strength) {
        virtStrength = strength;
        if (virt == null) return;
        try {
            if (virt.getStrengthSupported()) virt.setStrength((short) Math.max(0, Math.min(1000, strength)));
            virt.setEnabled(enabled && strength > 0);
        } catch (Throwable t) {
            Log.w(TAG, "virtualizer", t);
        }
    }

    /** 0..1000 = up to +10 dB of extra loudness. */
    public void setLoudness(int strength) {
        loudStrength = strength;
        if (loud == null) return;
        try {
            loud.setTargetGain(Math.max(0, Math.min(1000, strength)));
            loud.setEnabled(enabled && strength > 0);
        } catch (Throwable t) {
            Log.w(TAG, "loudness", t);
        }
    }

    public void release() {
        try {
            if (dp != null) dp.release();
        } catch (Throwable ignored) {
        }
        try {
            if (eq != null) eq.release();
        } catch (Throwable ignored) {
        }
        try {
            if (bass != null) bass.release();
        } catch (Throwable ignored) {
        }
        try {
            if (virt != null) virt.release();
        } catch (Throwable ignored) {
        }
        try {
            if (loud != null) loud.release();
        } catch (Throwable ignored) {
        }
        dp = null;
        eq = null;
        bass = null;
        virt = null;
        loud = null;
    }
}
