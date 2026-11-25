package com.demerdash.soundplayer.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import com.demerdash.soundplayer.R;
import com.demerdash.soundplayer.playback.EqPresets;

import java.util.Locale;

/** Interactive 10-band equalizer: drag the knobs, see a smooth response curve. */
public final class EqCurveView extends View {

    public interface Listener {
        void onBandChanged(int band, float db);

        void onTrackingStopped();
    }

    private static final String[] LABELS = {"31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"};

    private final float[] gains = new float[EqPresets.FREQS.length];
    private final float maxDb = EqPresets.MAX_DB;
    private final Paint curvePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zeroPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path curve = new Path();
    private final Path fill = new Path();
    private final float padH;
    private final float padTop;
    private final float padBottom;
    private final float knobR;
    private int accent;
    private boolean active = true;
    private int dragBand = -1;
    private Listener listener;
    private float lastFillTop = -1;

    public EqCurveView(Context c, AttributeSet attrs) {
        super(c, attrs);
        accent = ThemeUtil.color(c, R.attr.spAccent);
        int text = ThemeUtil.color(c, R.attr.spTextDim);
        int grid = ThemeUtil.color(c, R.attr.spDivider);
        padH = ThemeUtil.dp(c, 18);
        padTop = ThemeUtil.dp(c, 30);
        padBottom = ThemeUtil.dp(c, 28);
        knobR = ThemeUtil.dp(c, 8);

        curvePaint.setStyle(Paint.Style.STROKE);
        curvePaint.setStrokeWidth(ThemeUtil.dp(c, 3));
        curvePaint.setStrokeCap(Paint.Cap.ROUND);
        curvePaint.setColor(accent);
        fillPaint.setStyle(Paint.Style.FILL);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(ThemeUtil.dp(c, 1));
        gridPaint.setColor(grid);
        gridPaint.setPathEffect(new DashPathEffect(new float[]{ThemeUtil.dp(c, 4), ThemeUtil.dp(c, 4)}, 0));
        zeroPaint.setStyle(Paint.Style.STROKE);
        zeroPaint.setStrokeWidth(ThemeUtil.dp(c, 1));
        zeroPaint.setColor(text);
        zeroPaint.setAlpha(110);
        knobPaint.setColor(accent);
        knobStroke.setStyle(Paint.Style.STROKE);
        knobStroke.setStrokeWidth(ThemeUtil.dp(c, 2.5f));
        knobStroke.setColor(0xFFFFFFFF);
        labelPaint.setColor(text);
        labelPaint.setTextSize(ThemeUtil.dp(c, 11));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setColor(accent);
        valuePaint.setTextSize(ThemeUtil.dp(c, 13));
        valuePaint.setFakeBoldText(true);
        valuePaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setGains(float[] g) {
        if (g == null) return;
        for (int i = 0; i < gains.length && i < g.length; i++) gains[i] = g[i];
        invalidate();
    }

    public float[] getGains() {
        return gains.clone();
    }

    public void setActive(boolean a) {
        active = a;
        setAlpha(a ? 1f : 0.45f);
        invalidate();
    }

    private float bandX(int i) {
        float w = getWidth() - padH * 2;
        return padH + w * i / (gains.length - 1);
    }

    private float dbY(float db) {
        float h = getHeight() - padTop - padBottom;
        return padTop + h * (1f - (db + maxDb) / (2f * maxDb));
    }

    private float yDb(float y) {
        float h = getHeight() - padTop - padBottom;
        float db = (1f - (y - padTop) / h) * 2f * maxDb - maxDb;
        db = Math.round(db * 2f) / 2f;
        return Math.max(-maxDb, Math.min(maxDb, db));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int n = gains.length;
        float bottom = getHeight() - padBottom;
        // grid
        float[] lines = {-maxDb, -maxDb / 2f, maxDb / 2f, maxDb};
        for (float db : lines) {
            float y = dbY(db);
            canvas.drawLine(padH, y, getWidth() - padH, y, gridPaint);
        }
        canvas.drawLine(padH, dbY(0), getWidth() - padH, dbY(0), zeroPaint);
        for (int i = 0; i < n; i++) {
            float x = bandX(i);
            canvas.drawLine(x, padTop, x, bottom, gridPaint);
            canvas.drawText(LABELS[i], x, getHeight() - padBottom / 3f, labelPaint);
        }
        // smooth curve (Catmull-Rom -> cubic Bézier)
        curve.reset();
        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = bandX(i);
            ys[i] = dbY(gains[i]);
        }
        curve.moveTo(xs[0], ys[0]);
        for (int i = 0; i < n - 1; i++) {
            float x0 = i > 0 ? xs[i - 1] : xs[i];
            float y0 = i > 0 ? ys[i - 1] : ys[i];
            float x3 = i + 2 < n ? xs[i + 2] : xs[i + 1];
            float y3 = i + 2 < n ? ys[i + 2] : ys[i + 1];
            float c1x = xs[i] + (xs[i + 1] - x0) / 6f;
            float c1y = ys[i] + (ys[i + 1] - y0) / 6f;
            float c2x = xs[i + 1] - (x3 - xs[i]) / 6f;
            float c2y = ys[i + 1] - (y3 - ys[i]) / 6f;
            curve.cubicTo(c1x, c1y, c2x, c2y, xs[i + 1], ys[i + 1]);
        }
        fill.reset();
        fill.addPath(curve);
        fill.lineTo(xs[n - 1], bottom);
        fill.lineTo(xs[0], bottom);
        fill.close();
        if (lastFillTop != padTop) {
            fillPaint.setShader(new LinearGradient(0, padTop, 0, bottom,
                    ThemeUtil.withAlpha(accent, 0.45f), ThemeUtil.withAlpha(accent, 0f), Shader.TileMode.CLAMP));
            lastFillTop = padTop;
        }
        canvas.drawPath(fill, fillPaint);
        canvas.drawPath(curve, curvePaint);
        // knobs
        for (int i = 0; i < n; i++) {
            float r = i == dragBand ? knobR * 1.35f : knobR;
            canvas.drawCircle(xs[i], ys[i], r, knobPaint);
            canvas.drawCircle(xs[i], ys[i], r, knobStroke);
        }
        if (dragBand >= 0) {
            float g = gains[dragBand];
            String v = String.format(Locale.US, "%+.1f dB", g);
            canvas.drawText(v, xs[dragBand], Math.max(valuePaint.getTextSize(), ys[dragBand] - knobR * 2.2f), valuePaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int n = gains.length;
                float spacing = (getWidth() - padH * 2) / (n - 1);
                int band = Math.round((e.getX() - padH) / spacing);
                if (band < 0 || band >= n) return false;
                dragBand = band;
                ViewParent p = getParent();
                if (p != null) p.requestDisallowInterceptTouchEvent(true);
                update(e.getY());
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (dragBand >= 0) update(e.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragBand >= 0) {
                    dragBand = -1;
                    invalidate();
                    if (listener != null) listener.onTrackingStopped();
                }
                return true;
            default:
                return true;
        }
    }

    private void update(float y) {
        float db = yDb(y);
        if (gains[dragBand] != db) {
            gains[dragBand] = db;
            if (listener != null) listener.onBandChanged(dragBand, db);
        }
        invalidate();
    }
}
