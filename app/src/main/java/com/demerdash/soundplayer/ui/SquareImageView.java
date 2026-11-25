package com.demerdash.soundplayer.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;

/** ImageView that is always as tall as it is wide (album grid). */
public final class SquareImageView extends ImageView {

    public SquareImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec);
        int w = getMeasuredWidth();
        setMeasuredDimension(w, w);
    }
}
