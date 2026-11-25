package com.demerdash.soundplayer.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import com.demerdash.soundplayer.R;

/** Thin decorative progress line for the mini player (silent for accessibility, unlike ProgressBar). */
public final class ProgressLineView extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float progress;

    public ProgressLineView(Context c, AttributeSet attrs) {
        super(c, attrs);
        track.setColor(ThemeUtil.color(c, R.attr.spSurface));
        fill.setColor(ThemeUtil.color(c, R.attr.spAccent));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** 0..1 */
    public void setProgress(float p) {
        p = Math.max(0f, Math.min(1f, p));
        if (Math.abs(p - progress) < 0.0005f) return;
        progress = p;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float h = getHeight();
        float r = h / 2f;
        canvas.drawRoundRect(0, 0, getWidth(), h, r, r, track);
        if (progress > 0) canvas.drawRoundRect(0, 0, Math.max(h, getWidth() * progress), h, r, r, fill);
    }
}
