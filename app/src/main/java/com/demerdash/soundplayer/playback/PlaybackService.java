package com.demerdash.soundplayer.playback;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.audiofx.AudioEffect;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import com.demerdash.soundplayer.MainActivity;
import com.demerdash.soundplayer.NowPlayingActivity;
import com.demerdash.soundplayer.R;
import com.demerdash.soundplayer.data.ArtLoader;
import com.demerdash.soundplayer.data.MusicLibrary;
import com.demerdash.soundplayer.data.PlaylistStore;
import com.demerdash.soundplayer.data.Prefs;
import com.demerdash.soundplayer.model.Song;
import com.demerdash.soundplayer.ui.ColorExtractor;
import com.demerdash.soundplayer.ui.PlaceholderDrawable;
import com.demerdash.soundplayer.util.Format;
import com.demerdash.soundplayer.widget.PlayerWidget;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The playback engine: MediaPlayer with gapless hand-over, MediaSession (lock screen, Bluetooth, car,
 * headset buttons, Android 13+ media controls), a media-style notification, audio focus, fades,
 * the sleep timer and queue persistence.
 */
public final class PlaybackService extends Service implements MusicLibrary.Listener {

    private static final String TAG = "PlaybackService";

    public static final String ACTION_TOGGLE = "com.demerdash.soundplayer.action.TOGGLE";
    public static final String ACTION_PLAY = "com.demerdash.soundplayer.action.PLAY";
    public static final String ACTION_PAUSE = "com.demerdash.soundplayer.action.PAUSE";
    public static final String ACTION_NEXT = "com.demerdash.soundplayer.action.NEXT";
    public static final String ACTION_PREV = "com.demerdash.soundplayer.action.PREV";
    public static final String ACTION_FAVORITE = "com.demerdash.soundplayer.action.FAVORITE";
    public static final String ACTION_CLOSE = "com.demerdash.soundplayer.action.CLOSE";
    public static final String ACTION_DISMISSED = "com.demerdash.soundplayer.action.DISMISSED";
    public static final String ACTION_MEDIA_KEY = "com.demerdash.soundplayer.action.MEDIA_KEY";
    public static final String ACTION_OPEN_URI = "com.demerdash.soundplayer.action.OPEN_URI";
    public static final String EXTRA_KEY = "key_event";

    private static final String CA_FAVORITE = "favorite";
    private static final String CA_SHUFFLE = "shuffle";
    private static final String CA_REPEAT = "repeat";
    private static final String CHANNEL = "playback";
    private static final int NOTIF_ID = 7;
    private static final long IDLE_STOP_MS = 15 * 60 * 1000L;

    private static volatile PlaybackService sInstance;

    public static PlaybackService instance() {
        return sInstance;
    }

    public interface Listener {
        void onPlaybackChanged();

        void onQueueChanged();
    }

    public final class LocalBinder extends Binder {
        public PlaybackService service() {
            return PlaybackService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final QueueManager<Song> queue = new QueueManager<>();
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private Handler handler;
    private NotificationManager nm;
    private AudioManager am;
    private AudioAttributes attrs;
    private AudioFocusRequest focusRequest;
    private MediaSession session;
    private EffectsManager effects;
    private MusicLibrary library;
    private int sessionId;

    private MediaPlayer player;
    private boolean prepared;
    private boolean playWhenReady;
    private long pendingSeek = -1;
    private MediaPlayer nextPlayer;
    private Song nextSong;
    private boolean nextPrepared;
    private boolean gaplessLinked;

    private boolean isPlaying;
    private boolean hasFocus;
    private boolean resumeOnFocusGain;
    private boolean foreground;
    private boolean noisyRegistered;
    private boolean restored;
    private String pendingAction;
    private int consecutiveErrors;
    private int boundClients;
    private long pausedAt;

    private float speed = 1f;
    private float pitch = 1f;
    private float balance = 0f;
    private float fadeLevel = 1f;
    private float fadeTarget = 1f;
    private float fadeStep;
    private Runnable fadeEnd;

    private long sleepAt;
    private boolean sleepEndOfTrack;

    private Bitmap currentArt;
    private ColorExtractor.Colors currentColors;
    private int artToken;
    private long countedSongId = -1;
    private int ticks;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        handler = new Handler(Looper.getMainLooper());
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        sessionId = am.generateAudioSessionId();
        speed = Prefs.speed(this);
        pitch = Prefs.pitch(this);
        balance = Prefs.balance(this) / 100f;

        NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.channel_playback_desc));
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);

        session = new MediaSession(this, "SoundPlayer");
        session.setCallback(new SessionCallback(), handler);
        Intent mb = new Intent(Intent.ACTION_MEDIA_BUTTON).setClass(this, MediaButtonReceiver.class);
        if (Build.VERSION.SDK_INT >= 33) {
            session.setMediaButtonBroadcastReceiver(new ComponentName(this, MediaButtonReceiver.class));
        } else {
            session.setMediaButtonReceiver(PendingIntent.getBroadcast(this, 0, mb, PendingIntent.FLAG_IMMUTABLE));
        }
        session.setSessionActivity(contentIntent());

        try {
            effects = new EffectsManager(this, sessionId);
        } catch (Throwable t) {
            Log.w(TAG, "effects", t);
        }
        try {
            Intent fx = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
            fx.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId);
            fx.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
            fx.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC);
            sendBroadcast(fx);
        } catch (Exception ignored) {
        }

        library = MusicLibrary.get(this);
        library.addListener(this);
        if (library.isLoaded()) restore();
        else library.load(false);
        publishState();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Every start may come from startForegroundService(): always satisfy that contract first.
        if (!foreground) goForeground(buildNotification());
        if (!restored && !MusicLibrary.hasPermission(this)) {
            foreground = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_MEDIA_KEY.equals(action)) {
            KeyEvent ke;
            if (Build.VERSION.SDK_INT >= 33) ke = intent.getParcelableExtra(EXTRA_KEY, KeyEvent.class);
            else ke = intent.getParcelableExtra(EXTRA_KEY);
            if (ke != null) {
                if (restored) handleMediaKey(ke);
                else pendingAction = keyToAction(ke.getKeyCode());
            }
        } else if (ACTION_OPEN_URI.equals(action) && intent.getData() != null) {
            openUri(intent.getData());
        } else if (action != null) {
            if (restored) handleAction(action);
            else pendingAction = action;
        }
        // Until the saved queue is restored we keep the placeholder foreground notification.
        if (restored) updateForeground();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        boundClients++;
        handler.removeCallbacks(idleStop);
        return binder;
    }

    @Override
    public void onRebind(Intent intent) {
        boundClients++;
        handler.removeCallbacks(idleStop);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        boundClients = Math.max(0, boundClients - 1);
        scheduleIdleStop();
        return true;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (!isPlaying()) {
            stopEverything();
        }
    }

    @Override
    public void onDestroy() {
        saveState();
        sInstance = null;
        handler.removeCallbacksAndMessages(null);
        library.removeListener(this);
        unregisterNoisy();
        abandonFocus();
        releaseNext();
        releasePlayer();
        if (effects != null) effects.release();
        try {
            Intent fx = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
            fx.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId);
            fx.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
            sendBroadcast(fx);
        } catch (Exception ignored) {
        }
        session.setActive(false);
        session.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        nm.cancel(NOTIF_ID);
        PlayerWidget.update(this, null, false, null);
        bg.shutdown();
        super.onDestroy();
    }

    private final Runnable idleStop = new Runnable() {
        @Override
        public void run() {
            if (!isPlaying() && boundClients == 0) stopEverything();
        }
    };

    private void scheduleIdleStop() {
        handler.removeCallbacks(idleStop);
        if (!isPlaying()) handler.postDelayed(idleStop, boundClients == 0 ? IDLE_STOP_MS : IDLE_STOP_MS * 4);
    }

    private void stopEverything() {
        pausePlayback(false);
        saveState();
        foreground = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        nm.cancel(NOTIF_ID);
        stopSelf();
    }

    // ------------------------------------------------------------------ listeners

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyChanged() {
        for (Listener l : listeners) l.onPlaybackChanged();
    }

    private void notifyQueue() {
        publishQueue();
        for (Listener l : listeners) l.onQueueChanged();
    }

    @Override
    public void onLibraryChanged() {
        if (!restored) restore();
    }

    // ------------------------------------------------------------------ public API (main thread)

    public Song current() {
        return queue.current();
    }

    public int queueIndex() {
        return queue.index();
    }

    public int queueSize() {
        return queue.size();
    }

    public List<Song> queueItems() {
        return queue.items();
    }

    public boolean isShuffle() {
        return queue.isShuffle();
    }

    public int repeatMode() {
        return queue.repeatMode();
    }

    public boolean isPlaying() {
        return isPlaying || (playWhenReady && player != null && !prepared);
    }

    public long position() {
        if (player != null && prepared) {
            try {
                return player.getCurrentPosition();
            } catch (Exception ignored) {
            }
        }
        return pendingSeek > 0 ? pendingSeek : 0;
    }

    public long duration() {
        if (player != null && prepared) {
            try {
                int d = player.getDuration();
                if (d > 0) return d;
            } catch (Exception ignored) {
            }
        }
        Song s = queue.current();
        return s != null ? s.duration : 0;
    }

    public int audioSessionId() {
        return sessionId;
    }

    public EffectsManager effects() {
        return effects;
    }

    public Bitmap currentArt() {
        return currentArt;
    }

    public ColorExtractor.Colors currentColors() {
        return currentColors;
    }

    public float speed() {
        return speed;
    }

    public float pitch() {
        return pitch;
    }

    public void playSongs(List<Song> songs, int start, boolean shuffle) {
        if (songs == null || songs.isEmpty()) return;
        queue.setQueue(new ArrayList<>(songs), start, shuffle);
        consecutiveErrors = 0;
        openCurrent(true, 0);
        notifyQueue();
        saveStateSoon();
    }

    public void toggle() {
        if (isPlaying()) pausePlayback(false);
        else play();
    }

    public void play() {
        if (queue.current() == null) {
            List<Song> all = library.songs();
            if (!all.isEmpty()) playSongs(all, -1, true);
            return;
        }
        if (player == null) {
            openCurrent(true, pendingSeek > 0 ? pendingSeek : 0);
            return;
        }
        startPlayback();
    }

    public void pause() {
        pausePlayback(false);
    }

    public void next() {
        if (queue.moveToNext(true)) {
            openCurrent(true, 0);
            saveStateSoon();
        }
    }

    public void previous() {
        if (position() > 3000) {
            seekTo(0);
            return;
        }
        if (queue.moveToPrev()) {
            openCurrent(true, 0);
            saveStateSoon();
        }
    }

    public void seekTo(long ms) {
        if (ms < 0) ms = 0;
        if (player != null && prepared) {
            try {
                player.seekTo((int) ms);
            } catch (Exception ignored) {
            }
        } else {
            pendingSeek = ms;
        }
        publishState();
        notifyChanged();
    }

    public void jumpTo(int index) {
        if (queue.jumpTo(index)) {
            openCurrent(true, 0);
            notifyQueue();
            saveStateSoon();
        }
    }

    public void toggleShuffle() {
        queue.setShuffle(!queue.isShuffle());
        prepareNext();
        publishState();
        notifyChanged();
        notifyQueue();
        saveStateSoon();
    }

    public void cycleRepeat() {
        queue.cycleRepeat();
        if (player != null) {
            try {
                player.setLooping(queue.repeatMode() == QueueManager.REPEAT_ONE);
            } catch (Exception ignored) {
            }
        }
        prepareNext();
        publishState();
        notifyChanged();
        saveStateSoon();
    }

    public void playNext(List<Song> songs) {
        boolean wasEmpty = queue.isEmpty();
        queue.playNext(songs);
        if (wasEmpty) openCurrent(true, 0);
        else prepareNext();
        notifyQueue();
        saveStateSoon();
    }

    public void addToQueue(List<Song> songs) {
        boolean wasEmpty = queue.isEmpty();
        queue.addToEnd(songs);
        if (wasEmpty) openCurrent(true, 0);
        else prepareNext();
        notifyQueue();
        saveStateSoon();
    }

    public void removeFromQueue(int index) {
        boolean currentRemoved = queue.removeAt(index);
        if (currentRemoved) {
            if (queue.isEmpty()) {
                stopAndClear();
            } else {
                openCurrent(isPlaying(), 0);
            }
        } else {
            prepareNext();
        }
        notifyQueue();
        saveStateSoon();
    }

    public void moveQueueItem(int from, int to) {
        queue.move(from, to);
        prepareNext();
        notifyQueue();
        saveStateSoon();
    }

    public void clearQueue() {
        stopAndClear();
        notifyQueue();
        saveStateSoon();
    }

    private void stopAndClear() {
        pausePlayback(false);
        releaseNext();
        releasePlayer();
        queue.clear();
        currentArt = null;
        currentColors = null;
        session.setMetadata(null);
        publishState();
        notifyChanged();
        foreground = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        nm.cancel(NOTIF_ID);
        PlayerWidget.update(this, null, false, null);
    }

    public boolean toggleFavoriteCurrent() {
        Song s = queue.current();
        if (s == null || s.isExternal()) return false;
        boolean fav = PlaylistStore.get(this).toggleFavorite(s.id);
        publishState();
        updateNotification();
        notifyChanged();
        return fav;
    }

    public void setSpeedPitch(float newSpeed, float newPitch) {
        speed = Format.clamp(newSpeed, 0.25f, 3f);
        pitch = Format.clamp(newPitch, 0.5f, 2f);
        Prefs.setSpeedPitch(this, speed, pitch);
        if (player != null && prepared && isPlaying) {
            try {
                player.setPlaybackParams(params());
            } catch (Exception e) {
                Log.w(TAG, "speed", e);
            }
        }
        prepareNext();
        publishState();
        notifyChanged();
    }

    /** -1 (left) .. 1 (right) */
    public void setBalance(float b) {
        balance = Format.clamp(b, -1f, 1f);
        applyVolume();
    }

    // ---- sleep timer

    public void setSleepTimer(int minutes) {
        cancelSleepInternal();
        sleepAt = SystemClock.elapsedRealtime() + minutes * 60000L;
        handler.postDelayed(sleepRunnable, minutes * 60000L);
        updateNotification();
        notifyChanged();
    }

    public void setSleepAtEndOfTrack() {
        cancelSleepInternal();
        sleepEndOfTrack = true;
        releaseNext();
        updateNotification();
        notifyChanged();
    }

    public void cancelSleepTimer() {
        cancelSleepInternal();
        prepareNext();
        updateNotification();
        notifyChanged();
    }

    private void cancelSleepInternal() {
        handler.removeCallbacks(sleepRunnable);
        sleepAt = 0;
        sleepEndOfTrack = false;
    }

    /** Remaining ms, 0 if "end of track", -1 when off. */
    public long sleepRemaining() {
        if (sleepEndOfTrack) return 0;
        if (sleepAt <= 0) return -1;
        return Math.max(0, sleepAt - SystemClock.elapsedRealtime());
    }

    private final Runnable sleepRunnable = new Runnable() {
        @Override
        public void run() {
            sleepAt = 0;
            if (!isPlaying) {
                notifyChanged();
                return;
            }
            cancelFade();
            fadeTo(0f, 5000, new Runnable() {
                @Override
                public void run() {
                    pausePlayback(false);
                }
            });
            notifyChanged();
        }
    };

    // ---- external files ("Open with")

    public void openUri(final Uri uri) {
        Song known = null;
        if ("content".equals(uri.getScheme()) && "media".equals(uri.getAuthority())) {
            try {
                known = library.song(ContentUris.parseId(uri));
            } catch (Exception ignored) {
            }
        }
        if (known != null) {
            playSongs(Collections.singletonList(known), 0, false);
            return;
        }
        bg.execute(new Runnable() {
            @Override
            public void run() {
                final Song s = externalSong(uri);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        playSongs(Collections.singletonList(s), 0, false);
                    }
                });
            }
        });
    }

    private Song externalSong(Uri uri) {
        String title = null, artist = null, album = null;
        long dur = 0;
        String name = null;
        try {
            Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) name = c.getString(0);
                } finally {
                    c.close();
                }
            }
        } catch (Exception ignored) {
        }
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(this, uri);
            title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) dur = Long.parseLong(d);
        } catch (Exception ignored) {
        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {
            }
        }
        if (TextUtils.isEmpty(title)) title = Format.stripExtension(name != null ? name : uri.getLastPathSegment());
        if (TextUtils.isEmpty(title)) title = getString(R.string.app_name);
        if (TextUtils.isEmpty(artist)) artist = getString(R.string.unknown_artist);
        if (TextUtils.isEmpty(album)) album = getString(R.string.unknown_album);
        long id = -1L - (uri.toString().hashCode() & 0x7fffffffL);
        return new Song(id, title, artist, album, 0, 0, dur, 0, 0, null, "", "", 0, 0, null, uri);
    }

    // ------------------------------------------------------------------ actions

    public void handleAction(String action) {
        if (ACTION_TOGGLE.equals(action)) toggle();
        else if (ACTION_PLAY.equals(action)) play();
        else if (ACTION_PAUSE.equals(action)) pause();
        else if (ACTION_NEXT.equals(action)) next();
        else if (ACTION_PREV.equals(action)) previous();
        else if (ACTION_FAVORITE.equals(action)) toggleFavoriteCurrent();
        else if (ACTION_CLOSE.equals(action)) stopEverything();
        else if (ACTION_DISMISSED.equals(action)) {
            if (!isPlaying()) {
                foreground = false;
                stopForeground(STOP_FOREGROUND_REMOVE);
                if (boundClients == 0) stopSelf();
            }
        }
    }

    public void handleMediaKey(KeyEvent ke) {
        if (ke == null || ke.getAction() != KeyEvent.ACTION_DOWN || ke.getRepeatCount() > 0) return;
        String a = keyToAction(ke.getKeyCode());
        if (a != null) handleAction(a);
    }

    private static String keyToAction(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                return ACTION_PLAY;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                return ACTION_PAUSE;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                return ACTION_TOGGLE;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                return ACTION_NEXT;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return ACTION_PREV;
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ player management

    private MediaPlayer createPlayer(Song s) {
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(attrs);
            mp.setAudioSessionId(sessionId);
            mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mp.setDataSource(getApplicationContext(), s.uri);
            mp.setOnPreparedListener(preparedListener);
            mp.setOnCompletionListener(completionListener);
            mp.setOnErrorListener(errorListener);
            return mp;
        } catch (Exception e) {
            Log.w(TAG, "createPlayer " + s.uri, e);
            try {
                mp.release();
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    private void openCurrent(boolean play, long startPos) {
        cancelFade();
        releaseNext();
        releasePlayer();
        handler.removeCallbacks(countRunnable);
        countedSongId = -1;
        Song s = queue.current();
        if (s == null) {
            isPlaying = false;
            playWhenReady = false;
            publishState();
            notifyChanged();
            updateForeground();
            return;
        }
        playWhenReady = play;
        pendingSeek = startPos > 0 ? startPos : -1;
        MediaPlayer mp = createPlayer(s);
        if (mp == null) {
            handleError(s);
            return;
        }
        player = mp;
        prepared = false;
        try {
            player.setLooping(queue.repeatMode() == QueueManager.REPEAT_ONE);
            player.prepareAsync();
        } catch (Exception e) {
            handleError(s);
            return;
        }
        if (play) {
            session.setActive(true);
            updateForeground();
        }
        loadArt(s);
        publishState();
        notifyChanged();
        Prefs.setLastSong(this, s.title, s.artist);
    }

    private final MediaPlayer.OnPreparedListener preparedListener = new MediaPlayer.OnPreparedListener() {
        @Override
        public void onPrepared(MediaPlayer mp) {
            if (mp == player) {
                prepared = true;
                consecutiveErrors = 0;
                if (pendingSeek > 0) {
                    try {
                        mp.seekTo((int) pendingSeek);
                    } catch (Exception ignored) {
                    }
                }
                pendingSeek = -1;
                if (playWhenReady) {
                    startPlayback();
                } else {
                    isPlaying = false;
                    fadeLevel = 1f;
                    applyVolume();
                    publishState();
                    notifyChanged();
                }
                prepareNext();
                publishMetadata(queue.current(), currentArt);
            } else if (mp == nextPlayer) {
                nextPrepared = true;
                applyVolume();
                if (player != null && prepared) {
                    try {
                        player.setNextMediaPlayer(nextPlayer);
                        gaplessLinked = true;
                    } catch (Exception e) {
                        gaplessLinked = false;
                    }
                }
            }
        }
    };

    private final MediaPlayer.OnCompletionListener completionListener = new MediaPlayer.OnCompletionListener() {
        @Override
        public void onCompletion(MediaPlayer mp) {
            if (mp != player) return;
            countNow();
            if (sleepEndOfTrack) {
                sleepEndOfTrack = false;
                isPlaying = false;
                playWhenReady = false;
                if (queue.moveToNext(false)) openCurrent(false, 0);
                else seekTo(0);
                unregisterNoisy();
                abandonFocus();
                publishState();
                notifyChanged();
                updateForeground();
                return;
            }
            if (nextPlayer != null && nextPrepared && gaplessLinked) {
                // MediaPlayer already started the next track without a gap.
                queue.moveToNext(false);
                MediaPlayer old = player;
                player = nextPlayer;
                prepared = true;
                nextPlayer = null;
                nextSong = null;
                nextPrepared = false;
                gaplessLinked = false;
                releaseQuietly(old);
                try {
                    player.setLooping(queue.repeatMode() == QueueManager.REPEAT_ONE);
                } catch (Exception ignored) {
                }
                isPlaying = true;
                fadeLevel = 1f;
                applyVolume();
                scheduleCount();
                loadArt(queue.current());
                publishState();
                notifyChanged();
                notifyQueue();
                saveStateSoon();
                prepareNext();
                if (queue.current() != null) Prefs.setLastSong(PlaybackService.this, queue.current().title, queue.current().artist);
                return;
            }
            if (queue.moveToNext(false)) {
                openCurrent(true, 0);
                notifyQueue();
            } else {
                // End of the queue: rewind to the first song, paused.
                isPlaying = false;
                playWhenReady = false;
                queue.jumpTo(0);
                openCurrent(false, 0);
                unregisterNoisy();
                abandonFocus();
                notifyQueue();
                updateForeground();
            }
            saveStateSoon();
        }
    };

    private final MediaPlayer.OnErrorListener errorListener = new MediaPlayer.OnErrorListener() {
        @Override
        public boolean onError(MediaPlayer mp, int what, int extra) {
            Log.w(TAG, "MediaPlayer error " + what + "/" + extra);
            if (mp == nextPlayer) {
                releaseNext();
                return true;
            }
            if (mp == player) handleError(queue.current());
            else releaseQuietly(mp);
            return true;
        }
    };

    private void handleError(Song bad) {
        boolean wanted = playWhenReady;
        releasePlayer();
        consecutiveErrors++;
        if (wanted && bad != null) {
            Toast.makeText(this, getString(R.string.cannot_play, bad.title), Toast.LENGTH_SHORT).show();
        }
        boolean keepGoing = wanted && consecutiveErrors < Math.min(10, queue.size());
        if (keepGoing && queue.moveToNext(true)) {
            openCurrent(true, 0);
            notifyQueue();
            return;
        }
        isPlaying = false;
        playWhenReady = false;
        unregisterNoisy();
        publishState();
        notifyChanged();
        if (wanted || foreground) updateForeground();
    }

    private void prepareNext() {
        releaseNext();
        if (player == null || !prepared) return;
        if (!Prefs.gapless(this) || sleepEndOfTrack) return;
        if (speed != 1f || pitch != 1f) return;
        if (queue.repeatMode() == QueueManager.REPEAT_ONE) return;
        int ni = queue.peekNextIndex(false);
        if (ni < 0) return;
        Song ns = queue.get(ni);
        if (ns == null) return;
        MediaPlayer np = createPlayer(ns);
        if (np == null) return;
        nextPlayer = np;
        nextSong = ns;
        nextPrepared = false;
        try {
            np.prepareAsync();
        } catch (Exception e) {
            releaseNext();
        }
    }

    private void releaseNext() {
        if (nextPlayer == null) return;
        if (player != null && gaplessLinked) {
            try {
                player.setNextMediaPlayer(null);
            } catch (Exception ignored) {
            }
        }
        releaseQuietly(nextPlayer);
        nextPlayer = null;
        nextSong = null;
        nextPrepared = false;
        gaplessLinked = false;
    }

    private void releasePlayer() {
        if (player != null) {
            releaseQuietly(player);
            player = null;
        }
        prepared = false;
        isPlaying = false;
        stopTicker();
    }

    private static void releaseQuietly(MediaPlayer mp) {
        if (mp == null) return;
        try {
            mp.setOnCompletionListener(null);
            mp.setOnErrorListener(null);
            mp.setOnPreparedListener(null);
            mp.reset();
        } catch (Exception ignored) {
        }
        try {
            mp.release();
        } catch (Exception ignored) {
        }
    }

    private PlaybackParams params() {
        return new PlaybackParams().allowDefaults().setSpeed(speed).setPitch(pitch);
    }

    private void startPlayback() {
        if (player == null) {
            if (queue.current() != null) openCurrent(true, 0);
            return;
        }
        if (!prepared) {
            playWhenReady = true;
            updateForeground();
            publishState();
            notifyChanged();
            return;
        }
        if (!requestFocus()) {
            playWhenReady = false;
            publishState();
            notifyChanged();
            return;
        }
        playWhenReady = true;
        handler.removeCallbacks(idleStop);
        cancelFade();
        if (Prefs.rewindOnResume(this) && pausedAt > 0 && SystemClock.elapsedRealtime() - pausedAt > 60000L) {
            try {
                player.seekTo(Math.max(0, player.getCurrentPosition() - 2000));
            } catch (Exception ignored) {
            }
        }
        pausedAt = 0;
        boolean fade = Prefs.fade(this);
        fadeLevel = fade ? 0f : 1f;
        applyVolume();
        try {
            if (speed != 1f || pitch != 1f) player.setPlaybackParams(params());
            else player.start();
        } catch (Exception e) {
            try {
                player.start();
            } catch (Exception e2) {
                handleError(queue.current());
                return;
            }
        }
        isPlaying = true;
        if (fade) fadeTo(1f, 350, null);
        registerNoisy();
        session.setActive(true);
        scheduleCount();
        startTicker();
        updateForeground();
        publishState();
        notifyChanged();
        saveStateSoon();
    }

    private void pausePlayback(boolean transientLoss) {
        boolean wasPlaying = isPlaying;
        playWhenReady = false;
        isPlaying = false;
        resumeOnFocusGain = transientLoss && wasPlaying;
        unregisterNoisy();
        if (!transientLoss) abandonFocus();
        if (wasPlaying && player != null && prepared && Prefs.fade(this) && fadeLevel > 0f) {
            fadeTo(0f, 220, new Runnable() {
                @Override
                public void run() {
                    doPause();
                }
            });
        } else {
            doPause();
        }
        publishState();
        notifyChanged();
        updateForeground();
        scheduleIdleStop();
        saveStateSoon();
    }

    private void doPause() {
        try {
            if (player != null && prepared && player.isPlaying()) player.pause();
        } catch (Exception ignored) {
        }
        pausedAt = SystemClock.elapsedRealtime();
        stopTicker();
        publishState();
    }

    // ---- volume / fades

    private void applyVolume() {
        float l = fadeLevel * (balance > 0 ? 1f - balance : 1f);
        float r = fadeLevel * (balance < 0 ? 1f + balance : 1f);
        try {
            if (player != null) player.setVolume(l, r);
        } catch (Exception ignored) {
        }
        float nl = balance > 0 ? 1f - balance : 1f;
        float nr = balance < 0 ? 1f + balance : 1f;
        try {
            if (nextPlayer != null) nextPlayer.setVolume(nl, nr);
        } catch (Exception ignored) {
        }
    }

    private final Runnable fadeTick = new Runnable() {
        @Override
        public void run() {
            boolean done;
            if (fadeStep > 0) {
                fadeLevel = Math.min(fadeTarget, fadeLevel + fadeStep);
                done = fadeLevel >= fadeTarget;
            } else {
                fadeLevel = Math.max(fadeTarget, fadeLevel + fadeStep);
                done = fadeLevel <= fadeTarget;
            }
            applyVolume();
            if (done) {
                Runnable end = fadeEnd;
                fadeEnd = null;
                if (end != null) end.run();
            } else {
                handler.postDelayed(this, 16);
            }
        }
    };

    private void fadeTo(float target, long durationMs, Runnable onEnd) {
        handler.removeCallbacks(fadeTick);
        fadeTarget = target;
        fadeEnd = onEnd;
        float steps = Math.max(1f, durationMs / 16f);
        fadeStep = (target - fadeLevel) / steps;
        if (fadeStep == 0f) {
            fadeLevel = target;
            applyVolume();
            fadeEnd = null;
            if (onEnd != null) onEnd.run();
            return;
        }
        handler.post(fadeTick);
    }

    private void cancelFade() {
        handler.removeCallbacks(fadeTick);
        fadeEnd = null;
    }

    // ---- play counting & ticker

    private final Runnable countRunnable = new Runnable() {
        @Override
        public void run() {
            countNow();
        }
    };

    private void scheduleCount() {
        Song s = queue.current();
        handler.removeCallbacks(countRunnable);
        if (s == null || s.id == countedSongId) return;
        long d = duration();
        long delay = d > 0 ? Math.min(30000L, d / 2) : 30000L;
        long remaining = Math.max(1000L, delay - position());
        handler.postDelayed(countRunnable, remaining);
    }

    private void countNow() {
        Song s = queue.current();
        handler.removeCallbacks(countRunnable);
        if (s == null || s.id == countedSongId) return;
        countedSongId = s.id;
        PlaylistStore.get(this).recordPlay(s.id);
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            ticks++;
            if (ticks % 15 == 0) saveState();
            handler.postDelayed(this, 1000);
        }
    };

    private void startTicker() {
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, 1000);
    }

    private void stopTicker() {
        handler.removeCallbacks(ticker);
    }

    // ------------------------------------------------------------------ audio focus & noisy

    private final AudioManager.OnAudioFocusChangeListener focusListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int change) {
            switch (change) {
                case AudioManager.AUDIOFOCUS_GAIN:
                    hasFocus = true;
                    if (resumeOnFocusGain) {
                        resumeOnFocusGain = false;
                        startPlayback();
                    }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS:
                    hasFocus = false;
                    resumeOnFocusGain = false;
                    pausePlayback(false);
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    hasFocus = false;
                    if (isPlaying) pausePlayback(true);
                    break;
                default:
                    // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: the system lowers our volume automatically.
                    break;
            }
        }
    };

    private boolean requestFocus() {
        if (hasFocus) return true;
        if (focusRequest == null) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(focusListener, handler)
                    .setWillPauseWhenDucked(false)
                    .build();
        }
        hasFocus = am.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return hasFocus;
    }

    private void abandonFocus() {
        if (focusRequest != null && hasFocus) am.abandonAudioFocusRequest(focusRequest);
        hasFocus = false;
    }

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) pausePlayback(false);
        }
    };

    private void registerNoisy() {
        if (noisyRegistered) return;
        IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisyReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisyReceiver, f);
        noisyRegistered = true;
    }

    private void unregisterNoisy() {
        if (!noisyRegistered) return;
        try {
            unregisterReceiver(noisyReceiver);
        } catch (Exception ignored) {
        }
        noisyRegistered = false;
    }

    // ------------------------------------------------------------------ session, notification, widget

    private void loadArt(final Song s) {
        final int token = ++artToken;
        currentArt = null;
        currentColors = null;
        publishMetadata(s, null);
        updateNotification();
        if (s == null) return;
        bg.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap art = ArtLoader.get(PlaybackService.this).loadSync(s, 640);
                final ColorExtractor.Colors colors = ColorExtractor.extract(art, PlaceholderDrawable.colorFor(s.albumId != 0 ? s.albumId : s.id));
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (token != artToken) return;
                        currentArt = art;
                        currentColors = colors;
                        publishMetadata(s, art);
                        updateNotification();
                        notifyChanged();
                    }
                });
            }
        });
    }

    private void publishMetadata(Song s, Bitmap art) {
        if (s == null) {
            session.setMetadata(null);
            return;
        }
        MediaMetadata.Builder b = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, String.valueOf(s.id))
                .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, s.album)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, s.title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, s.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration())
                .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, queue.index() + 1)
                .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, queue.size());
        if (art != null) b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        try {
            session.setMetadata(b.build());
        } catch (Exception e) {
            Log.w(TAG, "metadata", e);
        }
    }

    private void publishState() {
        Song s = queue.current();
        PlaybackState.Builder b = new PlaybackState.Builder();
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_STOP | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID | PlaybackState.ACTION_PLAY_FROM_SEARCH
                | PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM;
        int state = s == null ? PlaybackState.STATE_NONE : (isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED);
        b.setState(state, position(), isPlaying ? speed : 0f, SystemClock.elapsedRealtime());
        b.setActions(actions);
        if (s != null) {
            boolean fav = PlaylistStore.get(this).isFavorite(s.id);
            b.addCustomAction(new PlaybackState.CustomAction.Builder(CA_FAVORITE,
                    getString(fav ? R.string.menu_remove_favorite : R.string.menu_add_favorite),
                    fav ? R.drawable.ic_favorite : R.drawable.ic_favorite_border).build());
            b.addCustomAction(new PlaybackState.CustomAction.Builder(CA_SHUFFLE, getString(R.string.shuffle),
                    R.drawable.ic_shuffle).build());
            int rm = queue.repeatMode();
            b.addCustomAction(new PlaybackState.CustomAction.Builder(CA_REPEAT, getString(R.string.repeat),
                    rm == QueueManager.REPEAT_ONE ? R.drawable.ic_repeat_one : R.drawable.ic_repeat).build());
            b.setActiveQueueItemId(queue.index());
        }
        try {
            session.setPlaybackState(b.build());
        } catch (Exception e) {
            Log.w(TAG, "state", e);
        }
        PlayerWidget.update(this, s, isPlaying(), currentArt);
    }

    private void publishQueue() {
        List<Song> win = queue.window(60);
        List<MediaSession.QueueItem> items = new ArrayList<>();
        int base = queue.index();
        for (int i = 0; i < win.size(); i++) {
            Song s = win.get(i);
            MediaDescription d = new MediaDescription.Builder()
                    .setMediaId(String.valueOf(s.id))
                    .setTitle(s.title)
                    .setSubtitle(s.artist)
                    .build();
            items.add(new MediaSession.QueueItem(d, base + i));
        }
        try {
            session.setQueue(items.isEmpty() ? null : items);
        } catch (Exception e) {
            Log.w(TAG, "queue", e);
        }
    }

    private PendingIntent contentIntent() {
        Intent main = new Intent(this, MainActivity.class).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent np = new Intent(this, NowPlayingActivity.class);
        return PendingIntent.getActivities(this, 1, new Intent[]{main, np},
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent controlIntent(String action) {
        Intent i = new Intent(action).setClass(this, ControlReceiver.class);
        return PendingIntent.getBroadcast(this, action.hashCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification.Action action(int icon, int title, String action) {
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), getString(title), controlIntent(action)).build();
    }

    private Notification buildNotification() {
        Song s = queue.current();
        boolean playing = isPlaying();
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_music)
                .setContentTitle(s != null ? s.title : getString(R.string.app_name))
                .setContentText(s != null ? s.artist : getString(R.string.nothing_playing))
                .setContentIntent(contentIntent())
                .setDeleteIntent(controlIntent(ACTION_DISMISSED))
                .setOngoing(playing)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT);
        if (currentArt != null) b.setLargeIcon(currentArt);
        if (currentColors != null) {
            b.setColor(currentColors.vibrant);
            b.setColorized(true);
        }
        long sleep = sleepRemaining();
        if (sleep == 0) b.setSubText(getString(R.string.sleep_after_song));
        else if (sleep > 0) b.setSubText(getString(R.string.sleep_remaining, Format.duration(sleep)));
        boolean fav = s != null && PlaylistStore.get(this).isFavorite(s.id);
        b.addAction(action(R.drawable.ic_skip_prev, R.string.previous, ACTION_PREV));
        b.addAction(action(playing ? R.drawable.ic_pause : R.drawable.ic_play, playing ? R.string.pause : R.string.play, ACTION_TOGGLE));
        b.addAction(action(R.drawable.ic_skip_next, R.string.next, ACTION_NEXT));
        b.addAction(action(fav ? R.drawable.ic_favorite : R.drawable.ic_favorite_border, R.string.favorite, ACTION_FAVORITE));
        b.addAction(action(R.drawable.ic_close, R.string.close, ACTION_CLOSE));
        b.setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    private void goForeground(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIF_ID, n);
            }
            foreground = true;
        } catch (Exception e) {
            // e.g. ForegroundServiceStartNotAllowedException: keep playing, just show the notification.
            Log.w(TAG, "startForeground", e);
            try {
                nm.notify(NOTIF_ID, n);
            } catch (Exception ignored) {
            }
        }
    }

    /** Foreground while playing; a dismissible notification while paused; nothing without a song. */
    private void updateForeground() {
        Song s = queue.current();
        if (s == null) {
            if (foreground) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                foreground = false;
            }
            nm.cancel(NOTIF_ID);
            return;
        }
        Notification n = buildNotification();
        if (isPlaying()) {
            if (!foreground) {
                try {
                    startService(new Intent(this, PlaybackService.class));
                } catch (Exception ignored) {
                }
                goForeground(n);
            } else {
                nm.notify(NOTIF_ID, n);
            }
        } else {
            if (foreground) {
                stopForeground(STOP_FOREGROUND_DETACH);
                foreground = false;
            }
            nm.notify(NOTIF_ID, n);
        }
    }

    private void updateNotification() {
        if (queue.current() == null) return;
        if (foreground || isPlaying()) {
            updateForeground();
        } else if (hasPostedNotification()) {
            nm.notify(NOTIF_ID, buildNotification());
        }
    }

    private boolean hasPostedNotification() {
        try {
            for (android.service.notification.StatusBarNotification sbn : nm.getActiveNotifications()) {
                if (sbn.getId() == NOTIF_ID) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    // ------------------------------------------------------------------ persistence

    private final Runnable saveSoon = new Runnable() {
        @Override
        public void run() {
            saveState();
        }
    };

    private void saveStateSoon() {
        handler.removeCallbacks(saveSoon);
        handler.postDelayed(saveSoon, 800);
    }

    private File stateFile() {
        return new File(getFilesDir(), "queue_state.txt");
    }

    private void saveState() {
        if (!restored) return;
        StringBuilder sb = new StringBuilder();
        List<Song> items = queue.items();
        List<Song> orig = queue.originalItems();
        int idx = queue.index();
        // External ("Open with") files are not persisted.
        int persistedIdx = 0;
        StringBuilder play = new StringBuilder();
        int n = 0;
        for (int i = 0; i < items.size(); i++) {
            Song s = items.get(i);
            if (s.isExternal()) continue;
            if (i == idx) persistedIdx = n;
            if (n > 0) play.append(',');
            play.append(s.id);
            n++;
        }
        StringBuilder origSb = new StringBuilder();
        if (orig != null) {
            int m = 0;
            for (Song s : orig) {
                if (s.isExternal()) continue;
                if (m > 0) origSb.append(',');
                origSb.append(s.id);
                m++;
            }
        }
        Song cur = queue.current();
        long pos = cur != null && !cur.isExternal() ? position() : 0;
        sb.append("v1\n").append(persistedIdx).append('\n').append(pos).append('\n')
                .append(queue.isShuffle() ? 1 : 0).append('\n').append(queue.repeatMode()).append('\n')
                .append(play).append('\n').append(origSb).append('\n');
        final String data = sb.toString();
        final File f = stateFile();
        bg.execute(new Runnable() {
            @Override
            public void run() {
                FileOutputStream out = null;
                try {
                    File tmp = new File(f.getPath() + ".tmp");
                    out = new FileOutputStream(tmp);
                    out.write(data.getBytes(Charset.forName("UTF-8")));
                    out.close();
                    out = null;
                    if (!tmp.renameTo(f)) Log.w(TAG, "state rename failed");
                } catch (IOException e) {
                    Log.w(TAG, "save state", e);
                } finally {
                    if (out != null) {
                        try {
                            out.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        });
    }

    private void restore() {
        if (restored || !library.isLoaded()) return;
        restored = true;
        try {
            File f = stateFile();
            if (f.exists()) {
                byte[] buf = new byte[(int) f.length()];
                FileInputStream in = new FileInputStream(f);
                int off = 0;
                try {
                    while (off < buf.length) {
                        int r = in.read(buf, off, buf.length - off);
                        if (r < 0) break;
                        off += r;
                    }
                } finally {
                    in.close();
                }
                String[] lines = new String(buf, 0, off, Charset.forName("UTF-8")).split("\n", -1);
                if (lines.length >= 7 && "v1".equals(lines[0])) {
                    int idx = Integer.parseInt(lines[1].trim());
                    long pos = Long.parseLong(lines[2].trim());
                    boolean shuffled = "1".equals(lines[3].trim());
                    int repeat = Integer.parseInt(lines[4].trim());
                    List<Long> playIds = parseIds(lines[5]);
                    List<Long> origIds = parseIds(lines[6]);
                    List<Song> play = new ArrayList<>();
                    int newIdx = -1;
                    for (int i = 0; i < playIds.size(); i++) {
                        Song s = library.song(playIds.get(i));
                        if (s == null) continue;
                        if (i <= idx) newIdx = play.size();
                        play.add(s);
                        if (i == idx) newIdx = play.size() - 1;
                    }
                    Song savedCurrent = idx >= 0 && idx < playIds.size() ? library.song(playIds.get(idx)) : null;
                    List<Song> orig = shuffled ? library.songsFor(origIds) : null;
                    queue.restore(play, orig, Math.max(0, newIdx), shuffled, repeat);
                    if (queue.current() != null) {
                        openCurrent(false, savedCurrent != null && savedCurrent.equals(queue.current()) ? pos : 0);
                        notifyQueue();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "restore", e);
        }
        if (pendingAction != null) {
            String a = pendingAction;
            pendingAction = null;
            handleAction(a);
        }
        publishState();
        notifyChanged();
        if (foreground) updateForeground();
    }

    private static List<Long> parseIds(String line) {
        List<Long> out = new ArrayList<>();
        if (line == null) return out;
        for (String p : line.split(",")) {
            p = p.trim();
            if (p.length() == 0) continue;
            try {
                out.add(Long.parseLong(p));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ MediaSession callback

    private final class SessionCallback extends MediaSession.Callback {
        @Override
        public void onPlay() {
            play();
        }

        @Override
        public void onPause() {
            pause();
        }

        @Override
        public void onStop() {
            pause();
        }

        @Override
        public void onSkipToNext() {
            next();
        }

        @Override
        public void onSkipToPrevious() {
            previous();
        }

        @Override
        public void onSeekTo(long pos) {
            seekTo(pos);
        }

        @Override
        public void onSkipToQueueItem(long id) {
            jumpTo((int) id);
        }

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            try {
                long id = Long.parseLong(mediaId);
                List<Song> all = library.songs();
                for (int i = 0; i < all.size(); i++) {
                    if (all.get(i).id == id) {
                        playSongs(all, i, false);
                        return;
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        }

        @Override
        public void onPlayFromSearch(String query, Bundle extras) {
            if (TextUtils.isEmpty(query)) {
                List<Song> all = library.songs();
                if (!all.isEmpty()) playSongs(all, -1, true);
                return;
            }
            List<Song> found = library.searchSongs(query, 200);
            if (!found.isEmpty()) playSongs(found, 0, false);
        }

        @Override
        public void onCustomAction(String action, Bundle extras) {
            if (CA_FAVORITE.equals(action)) toggleFavoriteCurrent();
            else if (CA_SHUFFLE.equals(action)) toggleShuffle();
            else if (CA_REPEAT.equals(action)) cycleRepeat();
        }
    }
}
