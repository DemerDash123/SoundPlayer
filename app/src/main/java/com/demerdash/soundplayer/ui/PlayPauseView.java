package com.demerdash.soundplayer.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.demerdash.soundplayer.R;

/** Play/pause button whose icon morphs smoothly between the two shapes. */
public final class PlayPauseView extends View {

    // 24x24 icon box: pause bars and the two halves of the play triangle, 4 points each.
    private static final float[] PAUSE_L = {6, 5, 10, 5, 10, 19, 6, 19};
    private static final float[] PAUSE_R = {14, 5, 18, 5, 18, 19, 14, 19};
    private static final float[] PLAY_L = {8, 5, 13.5f, 8.5f, 13.5f, 15.5f, 8, 19};
    private static final float[] PLAY_R = {13.5f, 8.5f, 19, 12, 19, 12, 13.5f, 15.5f};

    private final Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path left = new Path();
    private final Path right = new Path();
    private float progress = 1f; // 1 = play triangle (paused), 0 = pause bars (playing)
    private boolean playing;
    private boolean showCircle = true;
    private ValueAnimator anim;

    public PlayPauseView(Context context) {
        this(context, null);
    }

    public PlayPauseView(Context context, AttributeSet attrs) {
        super(context, attrs);
        circle.setColor(ThemeUtil.color(context, R.attr.spAccent));
        icon.setColor(ThemeUtil.color(context, R.attr.spOnAccent));
        icon.setStyle(Paint.Style.FILL);
        setClickable(true);
        setFocusable(true);
        setContentDescription(context.getString(R.string.play));
        setBackgroundResource(resolveRipple(context));
    }

    private static int resolveRipple(Context c) {
        android.util.TypedValue tv = new android.util.TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        return tv.resourceId;
    }

    public void setColors(int circleColor, int iconColor) {
        circle.setColor(circleColor);
        icon.setColor(iconColor);
        invalidate();
    }

    public void setCircleVisible(boolean v) {
        showCircle = v;
        invalidate();
    }

    public boolean isPlaying() {
        return playing;
    }

    public void setPlaying(boolean p, boolean animate) {
        if (p == playing && anim == null) {
            float target = p ? 0f : 1f;
            if (progress == target) return;
        }
        playing = p;
        setContentDescription(getContext().getString(p ? R.string.pause : R.string.play));
        final float target = p ? 0f : 1f;
        if (anim != null) anim.cancel();
        anim = null;
        if (!animate || !isAttachedToWindow()) {
            progress = target;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(progress, target);
        anim.setDuration(220);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                progress = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                anim = null;
            }
        });
        anim.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float size = Math.min(w, h);
        float cx = w / 2f;
        float cy = h / 2f;
        if (showCircle) canvas.drawCircle(cx, cy, size / 2f, circle);
        float box = size * (showCircle ? 0.46f : 0.7f);
        float scale = box / 24f;
        float ox = cx - box / 2f;
        float oy = cy - box / 2f;
        build(left, PAUSE_L, PLAY_L, scale, ox, oy);
        build(right, PAUSE_R, PLAY_R, scale, ox, oy);
        canvas.drawPath(left, icon);
        canvas.drawPath(right, icon);
    }

    private void build(Path p, float[] a, float[] b, float scale, float ox, float oy) {
        p.reset();
        float t = progress;
        for (int i = 0; i < 4; i++) {
            float x = a[i * 2] + (b[i * 2] - a[i * 2]) * t;
            float y = a[i * 2 + 1] + (b[i * 2 + 1] - a[i * 2 + 1]) * t;
            x = ox + x * scale;
            y = oy + y * scale;
            if (i == 0) p.moveTo(x, y);
            else p.lineTo(x, y);
        }
        p.close();
    }
}
