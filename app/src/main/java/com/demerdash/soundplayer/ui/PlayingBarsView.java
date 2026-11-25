package com.demerdash.soundplayer.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import com.demerdash.soundplayer.R;

/** Three little bouncing bars marking the song that is playing right now. */
public final class PlayingBarsView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean animating;

    public PlayingBarsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(ThemeUtil.color(context, R.attr.spAccent));
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setAnimating(boolean a) {
        if (animating == a) return;
        animating = a;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float barW = w / 5f;
        paint.setStrokeWidth(barW);
        long t = SystemClock.uptimeMillis();
        float[] phases = {0f, 1.7f, 3.1f};
        for (int i = 0; i < 3; i++) {
            float level = animating ? (0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(t / 140.0 + phases[i]))) : (i == 1 ? 0.8f : 0.5f);
            float x = barW / 2f + i * (barW * 2f);
            float top = h - (h - barW) * level;
            canvas.drawLine(x, h - barW / 2f, x, top + barW / 2f, paint);
        }
        if (animating && isShown()) postInvalidateOnAnimation();
    }
}
