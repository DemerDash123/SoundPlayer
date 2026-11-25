package com.demerdash.soundplayer.playback;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.view.KeyEvent;

/**
 * Receives hardware media buttons (headset / Bluetooth / car) while the app is not running,
 * so pressing "play" resumes the last queue.
 */
public final class MediaButtonReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) return;
        KeyEvent ke;
        if (Build.VERSION.SDK_INT >= 33) {
            ke = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent.class);
        } else {
            ke = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        }
        if (ke == null) return;
        PlaybackService svc = PlaybackService.instance();
        if (svc != null) {
            svc.handleMediaKey(ke);
            return;
        }
        if (ke.getAction() != KeyEvent.ACTION_DOWN) return;
        Intent i = new Intent(context, PlaybackService.class)
                .setAction(PlaybackService.ACTION_MEDIA_KEY)
                .putExtra(PlaybackService.EXTRA_KEY, ke);
        try {
            context.startForegroundService(i);
        } catch (Exception e) {
            Log.w("MediaButtonReceiver", "Cannot start playback service", e);
        }
    }
}
