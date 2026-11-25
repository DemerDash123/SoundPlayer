package com.demerdash.soundplayer.playback;

/** 10-band equalizer presets (dB at 31 Hz … 16 kHz) and band math. Pure Java, unit tested. */
public final class EqPresets {

    public static final int[] FREQS = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    public static final float MAX_DB = 12f;

    public static final String[] KEYS = {
            "flat", "bass", "bass_reducer", "treble", "treble_reducer", "vocal", "rock", "pop", "jazz",
            "classical", "hiphop", "electronic", "dance", "acoustic", "rnb", "latin", "piano", "lounge",
            "deep", "loudness", "small_speakers", "arabic"
    };

    public static final float[][] GAINS = {
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0},            // flat
            {7, 6, 5, 3, 1, 0, 0, 0, 0, 0},            // bass boost
            {-6, -5, -4, -2, 0, 0, 0, 0, 0, 0},        // bass reducer
            {0, 0, 0, 0, 0, 1, 2, 4, 5, 6},            // treble boost
            {0, 0, 0, 0, 0, -1, -2, -4, -5, -6},       // treble reducer
            {-2, -2, -1, 1, 3, 4, 4, 3, 1, 0},         // vocal
            {5, 4, 3, 1, -1, -1, 1, 3, 4, 5},          // rock
            {-1, 0, 2, 4, 5, 4, 2, 0, -1, -1},         // pop
            {4, 3, 1, 2, -1, -1, 0, 1, 3, 4},          // jazz
            {4, 3, 2, 1, -1, -1, 0, 2, 3, 4},          // classical
            {5, 4, 1, 3, -1, -1, 1, 0, 2, 3},          // hip-hop
            {4, 3, 1, 0, -2, 2, 1, 1, 4, 5},           // electronic
            {4, 6, 5, 0, 2, 3, 5, 4, 3, 0},            // dance
            {4, 4, 3, 1, 2, 2, 3, 3, 3, 2},            // acoustic
            {3, 7, 6, 1, -2, -1, 2, 3, 3, 4},          // r&b
            {4, 3, 0, 0, -1, -1, -1, 0, 3, 4},         // latin
            {2, 1, 0, 2, 3, 1, 3, 4, 2, 3},            // piano
            {-3, -1, 0, 1, 4, 1, 0, -1, 2, 1},         // lounge
            {5, 4, 2, 1, 3, 2, 1, -2, -4, -5},         // deep
            {6, 4, 0, 0, -2, 0, -1, -5, 5, 1},         // loudness
            {6, 5, 4, 3, 1, 0, -1, -2, -3, -4},        // small speakers
            {2, 3, 4, 2, 0, 1, 3, 3, 2, 1},            // arabic / oud (warm low-mids, clear upper mids)
    };

    private EqPresets() {
    }

    public static int indexOf(String key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return i;
        return -1;
    }

    /**
     * Gain at {@code freq} for a curve defined at {@code freqs}: linear interpolation in log-frequency,
     * clamped at the ends. Used to map our 10 bands onto a device equalizer with fewer bands.
     */
    public static float gainAt(int[] freqs, float[] gains, float freq) {
        if (freqs.length == 0) return 0f;
        if (freq <= freqs[0]) return gains[0];
        int last = freqs.length - 1;
        if (freq >= freqs[last]) return gains[last];
        double lf = Math.log(freq);
        for (int i = 0; i < last; i++) {
            if (freq >= freqs[i] && freq <= freqs[i + 1]) {
                double a = Math.log(freqs[i]);
                double b = Math.log(freqs[i + 1]);
                double t = (lf - a) / (b - a);
                return (float) (gains[i] + (gains[i + 1] - gains[i]) * t);
            }
        }
        return gains[last];
    }

    /** Upper cut-off frequencies (geometric midpoints) for each of the 10 bands. */
    public static float[] cutoffs() {
        float[] c = new float[FREQS.length];
        for (int i = 0; i < FREQS.length; i++) {
            if (i == FREQS.length - 1) c[i] = 20000f;
            else c[i] = (float) Math.sqrt((double) FREQS[i] * FREQS[i + 1]);
        }
        return c;
    }

    public static String serialize(float[] gains) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < gains.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(gains[i]);
        }
        return sb.toString();
    }

    public static float[] parse(String s, int count) {
        float[] out = new float[count];
        if (s == null || s.length() == 0) return out;
        String[] parts = s.split(",");
        for (int i = 0; i < count && i < parts.length; i++) {
            try {
                float v = Float.parseFloat(parts[i].trim());
                if (Float.isNaN(v) || Float.isInfinite(v)) v = 0f;
                out[i] = Math.max(-MAX_DB, Math.min(MAX_DB, v));
            } catch (NumberFormatException e) {
                out[i] = 0f;
            }
        }
        return out;
    }
}
