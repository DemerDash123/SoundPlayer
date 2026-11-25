package com.demerdash.soundplayer.ui;

import android.graphics.Bitmap;
import android.graphics.Color;

/** Tiny palette extractor: finds a vibrant accent and a dark background color from album art. */
public final class ColorExtractor {

    public static final class Colors {
        public final int vibrant;
        public final int dark;

        Colors(int vibrant, int dark) {
            this.vibrant = vibrant;
            this.dark = dark;
        }
    }

    private ColorExtractor() {
    }

    public static Colors extract(Bitmap src, int fallback) {
        if (src == null) return fromColor(fallback);
        Bitmap small;
        try {
            small = Bitmap.createScaledBitmap(src, 32, 32, true);
        } catch (Throwable t) {
            return fromColor(fallback);
        }
        int[] px = new int[32 * 32];
        small.getPixels(px, 0, 32, 0, 0, 32, 32);
        if (small != src) small.recycle();

        // 12 hue buckets x 2 saturation bands
        float[] hsv = new float[3];
        float[] score = new float[24];
        float[] sumH = new float[24];
        float[] sumS = new float[24];
        float[] sumV = new float[24];
        int[] count = new int[24];
        for (int c : px) {
            if (Color.alpha(c) < 128) continue;
            Color.colorToHSV(c, hsv);
            if (hsv[2] < 0.12f) continue;
            int hb = Math.min(11, (int) (hsv[0] / 30f));
            int sb = hsv[1] > 0.45f ? 1 : 0;
            int b = hb * 2 + sb;
            count[b]++;
            sumH[b] += hsv[0];
            sumS[b] += hsv[1];
            sumV[b] += hsv[2];
            score[b] += hsv[1] * (1f - Math.abs(hsv[2] - 0.65f));
        }
        int best = -1;
        float bestScore = 0;
        for (int i = 0; i < 24; i++) {
            if (count[i] < 6) continue;
            float s = score[i];
            if (s > bestScore) {
                bestScore = s;
                best = i;
            }
        }
        if (best < 0) {
            // greyscale art: use the average luminance tint of the fallback
            return fromColor(fallback);
        }
        float h = sumH[best] / count[best];
        float s = Math.max(0.35f, Math.min(0.85f, sumS[best] / count[best]));
        float v = Math.max(0.65f, Math.min(0.95f, sumV[best] / count[best] + 0.15f));
        int vibrant = Color.HSVToColor(new float[]{h, s, v});
        int dark = Color.HSVToColor(new float[]{h, Math.min(0.7f, s), 0.22f});
        return new Colors(vibrant, dark);
    }

    public static Colors fromColor(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        int dark = Color.HSVToColor(new float[]{hsv[0], Math.min(0.7f, hsv[1]), 0.22f});
        return new Colors(c, dark);
    }
}
