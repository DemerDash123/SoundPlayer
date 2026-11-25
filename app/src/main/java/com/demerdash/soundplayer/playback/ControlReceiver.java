package com.demerdash.soundplayer.playback;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Private (not exported) receiver for notification and widget buttons. */
public final class ControlReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        PlaybackService svc = PlaybackService.instance();
        if (svc != null) {
            svc.handleAction(action);
            return;
        }
        if (PlaybackService.ACTION_DISMISSED.equals(action) || PlaybackService.ACTION_CLOSE.equals(action)) return;
        try {
            context.startForegroundService(new Intent(context, PlaybackService.class).setAction(action));
        } catch (Exception e) {
            Log.w("ControlReceiver", "Cannot start playback service", e);
        }
    }
}
