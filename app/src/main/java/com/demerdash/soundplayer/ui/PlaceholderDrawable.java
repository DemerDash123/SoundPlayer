package com.demerdash.soundplayer.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Colorful gradient + sound-bars glyph shown for songs without album art. Colors are stable per album. */
public final class PlaceholderDrawable extends Drawable {

    private static final int[][] PALETTE = {
            {0xFF7C3AED, 0xFF2563EB},
            {0xFFDB2777, 0xFF7C3AED},
            {0xFF059669, 0xFF0EA5E9},
            {0xFFEA580C, 0xFFDB2777},
            {0xFF0891B2, 0xFF4F46E5},
            {0xFF65A30D, 0xFF059669},
            {0xFFDC2626, 0xFFEA580C},
            {0xFF9333EA, 0xFFC026D3},
            {0xFF0D9488, 0xFF65A30D},
            {0xFF4F46E5, 0xFF0891B2},
    };
    private static final float[] BARS = {0.32f, 0.62f, 0.9f, 0.6f, 0.36f};

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] colors;

    public PlaceholderDrawable(long seed) {
        int idx = (int) (((seed * 2654435761L) >>> 1) % PALETTE.length);
        colors = PALETTE[idx];
        bar.setColor(0x99FFFFFF);
        bar.setStrokeCap(Paint.Cap.ROUND);
        bar.setStyle(Paint.Style.STROKE);
    }

    public static int colorFor(long seed) {
        int idx = (int) (((seed * 2654435761L) >>> 1) % PALETTE.length);
        return PALETTE[idx][0];
    }

    @Override
    protected void onBoundsChange(Rect b) {
        bg.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom, colors[0], colors[1], Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        canvas.drawRect(b, bg);
        float w = b.width();
        float h = b.height();
        float unit = Math.min(w, h);
        float barW = unit * 0.075f;
        bar.setStrokeWidth(barW);
        float gap = unit * 0.11f;
        float total = gap * (BARS.length - 1);
        float startX = b.left + (w - total) / 2f;
        float cy = b.top + h / 2f;
        float maxH = unit * 0.42f;
        for (int i = 0; i < BARS.length; i++) {
            float x = startX + gap * i;
            float half = maxH * BARS[i] / 2f;
            canvas.drawLine(x, cy - half, x, cy + half, bar);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        bg.setAlpha(alpha);
        bar.setAlpha(Math.min(alpha, 0x99));
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        bg.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
