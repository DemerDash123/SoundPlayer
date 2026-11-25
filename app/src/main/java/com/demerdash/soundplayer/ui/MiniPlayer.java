package com.demerdash.soundplayer.ui;

import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.demerdash.soundplayer.BaseActivity;
import com.demerdash.soundplayer.R;
import com.demerdash.soundplayer.data.ArtLoader;
import com.demerdash.soundplayer.model.Song;
import com.demerdash.soundplayer.playback.PlaybackService;

/** The floating "now playing" bar. Tap to open, swipe left/right to change song. */
public final class MiniPlayer {

    private final BaseActivity activity;
    private final View root;
    private final ImageView art;
    private final TextView title;
    private final TextView artist;
    private final PlayPauseView play;
    private final ProgressLineView progress;
    private long lastSongId = Long.MIN_VALUE;
    private boolean shown;

    public MiniPlayer(final BaseActivity a, View r) {
        activity = a;
        root = r;
        art = r.findViewById(R.id.mini_art);
        title = r.findViewById(R.id.mini_title);
        artist = r.findViewById(R.id.mini_artist);
        play = r.findViewById(R.id.mini_play);
        progress = r.findViewById(R.id.mini_progress);
        art.setClipToOutline(true);
        play.setCircleVisible(false);
        play.setColors(0, ThemeUtil.color(a, R.attr.spText));
        title.setSelected(true);
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PlaybackService s = activity.service();
                if (s != null) s.toggle();
            }
        });
        r.findViewById(R.id.mini_next).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PlaybackService s = activity.service();
                if (s != null) s.next();
            }
        });
        root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activity.openNowPlaying();
            }
        });
        final GestureDetector gd = new GestureDetector(a, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || Math.abs(vx) < Math.abs(vy) * 1.5f || Math.abs(vx) < 600) return false;
                PlaybackService s = activity.service();
                if (s == null) return false;
                boolean rtl = root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                boolean toLeft = vx < 0;
                if (toLeft != rtl) s.next();
                else s.previous();
                return true;
            }
        });
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                boolean fling = gd.onTouchEvent(e);
                if (fling) {
                    v.setPressed(false);
                    v.cancelPendingInputEvents();
                    return true;
                }
                return false;
            }
        });
        ThemeUtil.marginBottomInset(root);
    }

    public void update(PlaybackService s) {
        Song cur = s == null ? null : s.current();
        if (cur == null) {
            if (shown || root.getVisibility() == View.VISIBLE) {
                shown = false;
                root.setVisibility(View.GONE);
            }
            lastSongId = Long.MIN_VALUE;
            return;
        }
        if (!shown) {
            shown = true;
            root.setVisibility(View.VISIBLE);
            root.setTranslationY(ThemeUtil.dp(activity, 40));
            root.setAlpha(0f);
            root.animate().translationY(0).alpha(1f).setDuration(220).start();
        }
        if (cur.id != lastSongId) {
            lastSongId = cur.id;
            title.setText(cur.title);
            artist.setText(cur.artist);
            ArtLoader.get(activity).bindSong(art, cur, ThemeUtil.dp(activity, 48));
        }
        play.setPlaying(s.isPlaying(), true);
        updateProgress(s);
    }

    public void updateProgress(PlaybackService s) {
        if (s == null || s.current() == null) return;
        long d = s.duration();
        progress.setProgress(d > 0 ? (float) s.position() / d : 0f);
    }
}
