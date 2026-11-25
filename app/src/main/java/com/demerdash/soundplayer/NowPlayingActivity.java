package com.demerdash.soundplayer;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.demerdash.soundplayer.data.PlaylistStore;
import com.demerdash.soundplayer.data.Prefs;
import com.demerdash.soundplayer.lyrics.LyricsRepository;
import com.demerdash.soundplayer.model.Song;
import com.demerdash.soundplayer.playback.QueueManager;
import com.demerdash.soundplayer.ui.Blur;
import com.demerdash.soundplayer.ui.ColorExtractor;
import com.demerdash.soundplayer.ui.Dialogs;
import com.demerdash.soundplayer.ui.LyricsView;
import com.demerdash.soundplayer.ui.PlaceholderDrawable;
import com.demerdash.soundplayer.ui.PlayPauseView;
import com.demerdash.soundplayer.ui.ThemeUtil;
import com.demerdash.soundplayer.util.Format;

import java.util.Locale;

/** Full-screen player with blurred artwork, dynamic colors, swipe gestures and synced lyrics. */
public final class NowPlayingActivity extends BaseActivity implements Dialogs.LyricsHost {

    private ImageView bg;
    private ImageView art;
    private View tint;
    private LyricsView lyrics;
    private TextView title;
    private TextView artist;
    private TextView from;
    private TextView pos;
    private TextView dur;
    private TextView speedLabel;
    private TextView sleepLabel;
    private ImageView lyricsIcon;
    private ImageView sleepIcon;
    private SeekBar seek;
    private PlayPauseView play;
    private ImageButton shuffle;
    private ImageButton repeat;
    private ImageButton fav;
    private boolean seeking;
    private long songId = Long.MIN_VALUE;
    private Bitmap lastArt;
    private boolean artSet;
    private boolean showLyrics;
    private int accent = 0xFFFFFFFF;
    private int lyricsToken;

    @Override
    protected boolean lightSystemIcons() {
        return true;
    }

    @Override
    protected long tickInterval() {
        return 250;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_now_playing);
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.slide_up, R.anim.stay);
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.stay, R.anim.slide_down);
        }
        if (Prefs.keepScreenOn(this)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        bg = findViewById(R.id.np_bg);
        art = findViewById(R.id.np_art);
        tint = findViewById(R.id.np_tint);
        lyrics = findViewById(R.id.np_lyrics);
        title = findViewById(R.id.np_title);
        artist = findViewById(R.id.np_artist);
        from = findViewById(R.id.np_from);
        pos = findViewById(R.id.np_pos);
        dur = findViewById(R.id.np_dur);
        seek = findViewById(R.id.np_seek);
        play = findViewById(R.id.np_play);
        shuffle = findViewById(R.id.np_shuffle);
        repeat = findViewById(R.id.np_repeat);
        fav = findViewById(R.id.np_fav);
        speedLabel = findViewById(R.id.np_speed_label);
        sleepLabel = findViewById(R.id.np_sleep_label);
        lyricsIcon = findViewById(R.id.np_lyrics_icon);
        sleepIcon = findViewById(R.id.np_sleep_icon);

        ThemeUtil.padInsets(findViewById(R.id.np_content), true, true, false);
        art.setClipToOutline(true);
        title.setSelected(true);
        play.setColors(0xFFFFFFFF, 0xFF111111);
        lyrics.setEmptyText(getString(R.string.no_lyrics), getString(R.string.no_lyrics_hint));

        final View frame = findViewById(R.id.np_art_frame);
        frame.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                int size = Math.min(r - l, b - t);
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) art.getLayoutParams();
                if (size > 0 && (lp.width != size || lp.height != size)) {
                    lp.width = size;
                    lp.height = size;
                    art.post(new Runnable() {
                        @Override
                        public void run() {
                            art.requestLayout();
                        }
                    });
                }
            }
        });

        click(R.id.np_close, new Runnable() {
            @Override
            public void run() {
                finish();
            }
        });
        click(R.id.np_play, new Runnable() {
            @Override
            public void run() {
                if (service != null) service.toggle();
            }
        });
        click(R.id.np_next, new Runnable() {
            @Override
            public void run() {
                if (service != null) service.next();
            }
        });
        click(R.id.np_prev, new Runnable() {
            @Override
            public void run() {
                if (service != null) service.previous();
            }
        });
        click(R.id.np_shuffle, new Runnable() {
            @Override
            public void run() {
                if (service != null) service.toggleShuffle();
            }
        });
        click(R.id.np_repeat, new Runnable() {
            @Override
            public void run() {
                if (service != null) service.cycleRepeat();
            }
        });
        click(R.id.np_fav, new Runnable() {
            @Override
            public void run() {
                if (service == null) return;
                boolean now = service.toggleFavoriteCurrent();
                fav.animate().scaleX(1.3f).scaleY(1.3f).setDuration(120).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        fav.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    }
                }).start();
                toast(now ? R.string.added_to_favorites : R.string.removed_from_favorites);
            }
        });
        click(R.id.np_btn_lyrics, new Runnable() {
            @Override
            public void run() {
                toggleLyrics();
            }
        });
        findViewById(R.id.np_btn_lyrics).setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (service != null) Dialogs.lyricsMenu(NowPlayingActivity.this, service.current(), v);
                return true;
            }
        });
        click(R.id.np_btn_speed, new Runnable() {
            @Override
            public void run() {
                Dialogs.speed(NowPlayingActivity.this);
            }
        });
        click(R.id.np_btn_sleep, new Runnable() {
            @Override
            public void run() {
                Dialogs.sleepTimer(NowPlayingActivity.this);
            }
        });
        click(R.id.np_btn_eq, new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(NowPlayingActivity.this, EqualizerActivity.class));
            }
        });
        click(R.id.np_btn_queue, new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(NowPlayingActivity.this, QueueActivity.class));
            }
        });
        findViewById(R.id.np_more).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (service != null && service.current() != null) {
                    Dialogs.songMenu(NowPlayingActivity.this, service.current(), v, -1, -1, new Runnable() {
                        @Override
                        public void run() {
                            onPlaybackChanged();
                        }
                    });
                }
            }
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) pos.setText(Format.duration(progress * 1000L));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                seeking = false;
                if (service != null) service.seekTo(s.getProgress() * 1000L);
            }
        });

        lyrics.setListener(new LyricsView.Listener() {
            @Override
            public void onSeekRequested(long ms) {
                if (service != null) service.seekTo(ms);
            }

            @Override
            public void onLyricsTap() {
                toggleLyrics();
            }

            @Override
            public void onEmptyTap() {
                if (service != null) Dialogs.lyricsMenu(NowPlayingActivity.this, service.current(), findViewById(R.id.np_btn_lyrics));
            }
        });

        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                toggleLyrics();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (service != null && service.current() != null && !PlaylistStore.get(NowPlayingActivity.this).isFavorite(service.current().id)) {
                    findViewById(R.id.np_fav).performClick();
                }
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || service == null) return false;
                if (Math.abs(vx) > Math.abs(vy) * 1.5f && Math.abs(vx) > 700) {
                    boolean rtl = art.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                    if ((vx < 0) != rtl) swipeArt(-1);
                    else swipeArt(1);
                    return true;
                }
                if (vy > 1500 && Math.abs(vy) > Math.abs(vx) * 1.5f) {
                    finish();
                    return true;
                }
                return false;
            }
        });
        art.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                return gd.onTouchEvent(e);
            }
        });
    }

    private void click(int id, final Runnable r) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
    }

    /** dir -1 = next (art leaves to the left), +1 = previous. */
    private void swipeArt(final int dir) {
        final float w = art.getWidth();
        art.animate().translationX(dir * w * 0.35f).alpha(0f).setDuration(140).withEndAction(new Runnable() {
            @Override
            public void run() {
                if (service != null) {
                    if (dir < 0) service.next();
                    else service.previous();
                }
                art.setTranslationX(-dir * w * 0.35f);
                art.animate().translationX(0).alpha(1f).setDuration(200).start();
            }
        }).start();
    }

    private void toggleLyrics() {
        showLyrics = !showLyrics;
        final View show = showLyrics ? lyrics : art;
        final View hide = showLyrics ? art : lyrics;
        show.setAlpha(0f);
        show.setVisibility(View.VISIBLE);
        show.animate().alpha(1f).setDuration(200).start();
        hide.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() {
            @Override
            public void run() {
                hide.setVisibility(View.GONE);
                hide.setAlpha(1f);
            }
        }).start();
        lyricsIcon.setImageTintList(ColorStateList.valueOf(showLyrics ? accent : 0xFFFFFFFF));
    }

    @Override
    public void finish() {
        super.finish();
        if (Build.VERSION.SDK_INT < 34) overridePendingTransition(R.anim.stay, R.anim.slide_down);
    }

    @Override
    public void onPlaybackChanged() {
        if (service == null) return;
        Song cur = service.current();
        if (cur == null) {
            finish();
            return;
        }
        if (cur.id != songId) {
            songId = cur.id;
            title.setText(cur.title);
            artist.setText(cur.artist);
            from.setText(cur.album);
            loadLyrics(cur);
            artSet = false;
        }
        Bitmap b = service.currentArt();
        if (!artSet || b != lastArt) {
            applyArt(cur, b, service.currentColors());
            lastArt = b;
            artSet = true;
        }
        play.setPlaying(service.isPlaying(), true);
        shuffle.setImageTintList(ColorStateList.valueOf(service.isShuffle() ? accent : 0x99FFFFFF));
        int rm = service.repeatMode();
        repeat.setImageResource(rm == QueueManager.REPEAT_ONE ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        repeat.setImageTintList(ColorStateList.valueOf(rm != QueueManager.REPEAT_OFF ? accent : 0x99FFFFFF));
        boolean isFav = !cur.isExternal() && PlaylistStore.get(this).isFavorite(cur.id);
        fav.setImageResource(isFav ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
        fav.setImageTintList(ColorStateList.valueOf(isFav ? accent : 0xFFFFFFFF));
        float sp = service.speed();
        speedLabel.setText(sp == 1f && service.pitch() == 1f ? getString(R.string.speed)
                : String.format(Locale.US, "%.2fx", sp));
        updateProgress();
    }

    private void applyArt(Song cur, Bitmap b, ColorExtractor.Colors colors) {
        long seed = cur.albumId != 0 ? cur.albumId : cur.id;
        Drawable next = b != null ? new BitmapDrawable(getResources(), b) : new PlaceholderDrawable(seed);
        Drawable prev = art.getDrawable();
        if (prev instanceof TransitionDrawable) prev = ((TransitionDrawable) prev).getDrawable(1);
        if (prev != null && artSet) {
            TransitionDrawable td = new TransitionDrawable(new Drawable[]{prev, next});
            td.setCrossFadeEnabled(true);
            art.setImageDrawable(td);
            td.startTransition(250);
        } else {
            art.setImageDrawable(next);
        }
        if (b != null) {
            Bitmap blurred = Blur.blur(b, 48, 6);
            bg.setImageBitmap(blurred);
        } else {
            bg.setImageDrawable(new PlaceholderDrawable(seed));
        }
        if (colors == null) colors = ColorExtractor.fromColor(PlaceholderDrawable.colorFor(seed));
        tint.setBackgroundColor(ThemeUtil.withAlpha(colors.dark, 0.62f));
        accent = colors.vibrant;
        seek.setProgressTintList(ColorStateList.valueOf(accent));
        seek.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        play.setColors(0xFFFFFFFF, ThemeUtil.blend(colors.dark, 0xFF000000, 0.3f));
        lyricsIcon.setImageTintList(ColorStateList.valueOf(showLyrics ? accent : 0xFFFFFFFF));
    }

    @Override
    protected void onProgressTick() {
        updateProgress();
    }

    private void updateProgress() {
        if (service == null || service.current() == null) return;
        long p = service.position();
        long d = service.duration();
        // One-second resolution: smooth enough, and keeps accessibility events (TalkBack) calm.
        int maxSec = (int) Math.max(1, d / 1000);
        if (seek.getMax() != maxSec) seek.setMax(maxSec);
        if (!seeking) {
            int sec = (int) (p / 1000);
            if (seek.getProgress() != sec) seek.setProgress(sec);
            pos.setText(Format.duration(p));
        }
        dur.setText(Format.duration(d));
        lyrics.setPosition(p + 150);
        long rem = service.sleepRemaining();
        if (rem > 0) {
            sleepLabel.setText(Format.duration(rem));
            sleepIcon.setImageTintList(ColorStateList.valueOf(accent));
        } else if (rem == 0) {
            sleepLabel.setText(R.string.sleep_end_of_track);
            sleepIcon.setImageTintList(ColorStateList.valueOf(accent));
        } else {
            sleepLabel.setText(R.string.sleep_title);
            sleepIcon.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        }
    }

    private void loadLyrics(final Song s) {
        final int token = ++lyricsToken;
        lyrics.setLyrics(null);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final LyricsRepository.Lyrics l = LyricsRepository.load(NowPlayingActivity.this, s);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (token != lyricsToken || isFinishing()) return;
                        lyrics.setLyrics(l);
                        findViewById(R.id.np_lyrics_label).setAlpha(l != null ? 1f : 0.6f);
                    }
                });
            }
        }, "lyrics").start();
    }

    @Override
    public void onLyricsChanged() {
        if (service != null && service.current() != null) loadLyrics(service.current());
    }
}
