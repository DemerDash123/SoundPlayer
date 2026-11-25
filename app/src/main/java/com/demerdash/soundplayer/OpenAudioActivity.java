package com.demerdash.soundplayer;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.demerdash.soundplayer.playback.PlaybackService;

/** Handles "Open with SoundPlayer" for audio files shared from file managers, chats and browsers. */
public final class OpenAudioActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri uri = getIntent() != null ? getIntent().getData() : null;
        if (uri != null) {
            Intent svc = new Intent(this, PlaybackService.class)
                    .setAction(PlaybackService.ACTION_OPEN_URI)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startForegroundService(svc);
            } catch (Exception e) {
                Log.w("OpenAudio", "start service", e);
                try {
                    startService(svc);
                } catch (Exception ignored) {
                }
            }
            Intent main = new Intent(this, MainActivity.class).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Intent np = new Intent(this, NowPlayingActivity.class);
            startActivities(new Intent[]{main, np});
        }
        finish();
    }
}
