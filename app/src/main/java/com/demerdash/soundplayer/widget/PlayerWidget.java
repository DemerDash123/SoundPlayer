package com.demerdash.soundplayer.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.widget.RemoteViews;

import com.demerdash.soundplayer.MainActivity;
import com.demerdash.soundplayer.R;
import com.demerdash.soundplayer.data.Prefs;
import com.demerdash.soundplayer.model.Song;
import com.demerdash.soundplayer.playback.ControlReceiver;
import com.demerdash.soundplayer.playback.PlaybackService;

/** Home-screen widget with album art and previous / play-pause / next buttons. */
public final class PlayerWidget extends AppWidgetProvider {

    private static Bitmap lastSource;
    private static Bitmap lastScaled;
    private static String lastKey;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        PlaybackService s = PlaybackService.instance();
        if (s != null) {
            push(context, manager, ids, s.current(), s.isPlaying(), scaled(s.currentArt()));
        } else {
            push(context, manager, ids, null, false, null);
        }
    }

    /** Called by the playback service whenever the song, state or art changes. */
    public static void update(Context c, Song song, boolean playing, Bitmap art) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            if (m == null) return;
            int[] ids = m.getAppWidgetIds(new ComponentName(c, PlayerWidget.class));
            if (ids == null || ids.length == 0) return;
            String key = (song != null ? song.id : -1) + "|" + playing + "|" + System.identityHashCode(art);
            if (key.equals(lastKey)) return;
            lastKey = key;
            push(c, m, ids, song, playing, scaled(art));
        } catch (Exception ignored) {
        }
    }

    private static synchronized Bitmap scaled(Bitmap art) {
        if (art == null) return null;
        if (art == lastSource && lastScaled != null) return lastScaled;
        try {
            int size = 200;
            lastScaled = art.getWidth() > size ? Bitmap.createScaledBitmap(art, size, size * art.getHeight() / Math.max(1, art.getWidth()), true) : art;
            lastSource = art;
            return lastScaled;
        } catch (Exception e) {
            return null;
        }
    }

    private static void push(Context c, AppWidgetManager m, int[] ids, Song song, boolean playing, Bitmap art) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_player);
        String title = song != null ? song.title : Prefs.lastTitle(c);
        String artist = song != null ? song.artist : Prefs.lastArtist(c);
        v.setTextViewText(R.id.widget_title, title != null ? title : c.getString(R.string.app_name));
        v.setTextViewText(R.id.widget_artist, artist != null ? artist : c.getString(R.string.tap_song_to_play));
        if (art != null) v.setImageViewBitmap(R.id.widget_art, art);
        else v.setImageViewResource(R.id.widget_art, R.drawable.ic_stat_music);
        v.setImageViewResource(R.id.widget_play, playing ? R.drawable.ic_pause : R.drawable.ic_play);
        v.setContentDescription(R.id.widget_play, c.getString(playing ? R.string.pause : R.string.play));
        v.setOnClickPendingIntent(R.id.widget_play, control(c, PlaybackService.ACTION_TOGGLE));
        v.setOnClickPendingIntent(R.id.widget_prev, control(c, PlaybackService.ACTION_PREV));
        v.setOnClickPendingIntent(R.id.widget_next, control(c, PlaybackService.ACTION_NEXT));
        Intent open = new Intent(c, MainActivity.class).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(c, 3, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        m.updateAppWidget(ids, v);
    }

    private static PendingIntent control(Context c, String action) {
        Intent i = new Intent(action).setClass(c, ControlReceiver.class);
        return PendingIntent.getBroadcast(c, 100 + action.hashCode() % 1000, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
