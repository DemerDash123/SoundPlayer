package com.demerdash.soundplayer.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;

import com.demerdash.soundplayer.R;
import com.demerdash.soundplayer.lyrics.LrcParser;
import com.demerdash.soundplayer.lyrics.LyricsRepository;

import java.util.ArrayList;
import java.util.List;

/** Karaoke-style lyrics: synced LRC lines are highlighted and centered automatically; plain text scrolls. */
public final class LyricsView extends View {

    public interface Listener {
        void onSeekRequested(long ms);

        void onLyricsTap();

        void onEmptyTap();
    }

    private final TextPaint paint = new TextPaint(Paint_FLAGS);
    private final TextPaint emptyPaint = new TextPaint(Paint_FLAGS);
    private final TextPaint hintPaint = new TextPaint(Paint_FLAGS);
    private static final int Paint_FLAGS = android.graphics.Paint.ANTI_ALIAS_FLAG;

    private final List<String> texts = new ArrayList<>();
    private final List<Long> times = new ArrayList<>();
    private final List<StaticLayout> layouts = new ArrayList<>();
    private final List<Float> tops = new ArrayList<>();
    private float totalHeight;
    private boolean synced;
    private boolean hasLyrics;
    private int current = -1;
    private float scroll;
    private long userUntil;
    private ValueAnimator scrollAnim;
    private final OverScroller scroller;
    private final GestureDetector gestures;
    private final float gap;
    private int builtWidth = -1;
    private Listener listener;
    private final int colorActive = 0xFFFFFFFF;
    private final int colorNormal = 0x73FFFFFF;
    private StaticLayout emptyLayout;
    private StaticLayout hintLayout;

    public LyricsView(Context c, AttributeSet attrs) {
        super(c, attrs);
        paint.setTextSize(ThemeUtil.dp(c, 21));
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        emptyPaint.setTextSize(ThemeUtil.dp(c, 18));
        emptyPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        emptyPaint.setColor(colorActive);
        hintPaint.setTextSize(ThemeUtil.dp(c, 14));
        hintPaint.setColor(0xB3FFFFFF);
        gap = ThemeUtil.dp(c, 16);
        scroller = new OverScroller(c);
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                if (scrollAnim != null) scrollAnim.cancel();
                scroller.forceFinished(true);
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (!hasLyrics) return false;
                userUntil = SystemClock.uptimeMillis() + 3000;
                scroll = clamp(scroll + dy);
                invalidate();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (!hasLyrics) return false;
                userUntil = SystemClock.uptimeMillis() + 3000;
                scroller.fling(0, (int) scroll, 0, (int) -vy, 0, 0, (int) minScroll(), (int) maxScroll());
                postInvalidateOnAnimation();
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (!hasLyrics) {
                    if (listener != null) listener.onEmptyTap();
                    return true;
                }
                if (synced) {
                    int idx = lineAt(e.getY());
                    if (idx >= 0 && listener != null) {
                        userUntil = 0;
                        listener.onSeekRequested(times.get(idx));
                        return true;
                    }
                }
                if (listener != null) listener.onLyricsTap();
                return true;
            }
        });
        setContentDescription(c.getString(R.string.lyrics));
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setLyrics(LyricsRepository.Lyrics lyrics) {
        texts.clear();
        times.clear();
        synced = false;
        hasLyrics = false;
        if (lyrics != null) {
            if (lyrics.isSynced()) {
                synced = true;
                for (LrcParser.Line l : lyrics.lines) {
                    texts.add(l.text.length() == 0 ? "♪" : l.text);
                    times.add(l.time);
                }
            } else if (lyrics.plain != null) {
                for (String s : lyrics.plain.split("\n", -1)) texts.add(s);
            }
            hasLyrics = !texts.isEmpty();
        }
        current = -1;
        builtWidth = -1;
        userUntil = 0;
        scroller.forceFinished(true);
        if (scrollAnim != null) scrollAnim.cancel();
        rebuild();
        scroll = minScroll();
        invalidate();
    }

    public void setEmptyText(String title, String hint) {
        emptyLayout = make(title, emptyPaint, Math.max(1, getWidth() - getPaddingLeft() - getPaddingRight()));
        hintLayout = make(hint, hintPaint, Math.max(1, getWidth() - getPaddingLeft() - getPaddingRight()));
        emptyTitle = title;
        emptyHint = hint;
        invalidate();
    }

    private String emptyTitle = "";
    private String emptyHint = "";

    public boolean isSynced() {
        return synced;
    }

    public boolean hasLyrics() {
        return hasLyrics;
    }

    /** Called ~4x a second with the playback position. */
    public void setPosition(long ms) {
        if (!synced || times.isEmpty()) return;
        int lo = 0, hi = times.size() - 1, idx = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (times.get(mid) <= ms) {
                idx = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (idx != current) {
            current = idx;
            if (SystemClock.uptimeMillis() > userUntil) animateTo(centerOf(Math.max(0, idx)));
            invalidate();
        } else if (SystemClock.uptimeMillis() > userUntil && userUntil != 0) {
            userUntil = 0;
            animateTo(centerOf(Math.max(0, idx)));
        }
    }

    private float centerOf(int i) {
        if (i < 0 || i >= layouts.size()) return 0;
        return tops.get(i) + layouts.get(i).getHeight() / 2f;
    }

    private void animateTo(float target) {
        target = clamp(target);
        if (scrollAnim != null) scrollAnim.cancel();
        scrollAnim = ValueAnimator.ofFloat(scroll, target);
        scrollAnim.setDuration(450);
        scrollAnim.setInterpolator(new DecelerateInterpolator());
        scrollAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                scroll = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        scrollAnim.start();
    }

    private float minScroll() {
        if (synced) return layouts.isEmpty() ? 0 : centerOf(0);
        return getHeight() / 2f - getPaddingTop();
    }

    private float maxScroll() {
        if (synced) return layouts.isEmpty() ? 0 : centerOf(layouts.size() - 1);
        return Math.max(minScroll(), totalHeight - getHeight() / 2f + getPaddingBottom());
    }

    private float clamp(float v) {
        float min = minScroll();
        float max = maxScroll();
        return v < min ? min : (v > max ? max : v);
    }

    private int lineAt(float y) {
        float contentY = y - getHeight() / 2f + scroll;
        for (int i = 0; i < layouts.size(); i++) {
            float t = tops.get(i);
            if (contentY >= t - gap / 2f && contentY <= t + layouts.get(i).getHeight() + gap / 2f) return i;
        }
        return -1;
    }

    private void rebuild() {
        int w = getWidth() - getPaddingLeft() - getPaddingRight();
        if (w <= 0) return;
        builtWidth = getWidth();
        layouts.clear();
        tops.clear();
        float y = 0;
        paint.setTextSize(ThemeUtil.dp(getContext(), synced ? 21 : 17));
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, synced ? Typeface.BOLD : Typeface.NORMAL));
        for (String t : texts) {
            StaticLayout l = make(t, paint, w);
            layouts.add(l);
            tops.add(y);
            y += l.getHeight() + (synced ? gap : gap / 3f);
        }
        totalHeight = y;
        emptyLayout = make(emptyTitle, emptyPaint, w);
        hintLayout = make(emptyHint, hintPaint, w);
    }

    private static StaticLayout make(String text, TextPaint p, int width) {
        String t = text == null ? "" : text;
        return StaticLayout.Builder.obtain(t, 0, t.length(), p, Math.max(1, width))
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.12f)
                .setIncludePad(false)
                .build();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        rebuild();
        if (synced && current >= 0) scroll = clamp(centerOf(current));
        else scroll = minScroll();
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scroll = clamp(scroller.getCurrY());
            postInvalidateOnAnimation();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && getParent() != null && hasLyrics) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        return gestures.onTouchEvent(e) || super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (builtWidth != getWidth()) rebuild();
        int left = getPaddingLeft();
        if (!hasLyrics) {
            if (emptyLayout == null) return;
            float h = emptyLayout.getHeight() + gap + (hintLayout != null ? hintLayout.getHeight() : 0);
            canvas.save();
            canvas.translate(left, (getHeight() - h) / 2f);
            emptyLayout.draw(canvas);
            if (hintLayout != null) {
                canvas.translate(0, emptyLayout.getHeight() + gap);
                hintLayout.draw(canvas);
            }
            canvas.restore();
            return;
        }
        float offset = getHeight() / 2f - scroll;
        for (int i = 0; i < layouts.size(); i++) {
            float y = tops.get(i) + offset;
            StaticLayout l = layouts.get(i);
            if (y + l.getHeight() < 0 || y > getHeight()) continue;
            if (synced) {
                if (i == current) {
                    paint.setColor(colorActive);
                } else {
                    float dist = Math.abs(i - Math.max(0, current));
                    float alpha = Math.max(0.22f, 0.5f - dist * 0.05f);
                    paint.setColor(ThemeUtil.withAlpha(colorActive, alpha));
                }
            } else {
                paint.setColor(0xE6FFFFFF);
            }
            canvas.save();
            canvas.translate(left, y);
            l.draw(canvas);
            canvas.restore();
        }
    }
}
