package com.demerdash.soundplayer;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import com.demerdash.soundplayer.playback.PlaybackService;
import com.demerdash.soundplayer.ui.MiniPlayer;
import com.demerdash.soundplayer.ui.ThemeUtil;

/** Common base: theme, edge-to-edge, binding to the playback service and the floating mini player. */
public abstract class BaseActivity extends Activity implements PlaybackService.Listener {

    protected PlaybackService service;
    protected MiniPlayer mini;
    protected final Handler ui = new Handler(Looper.getMainLooper());
    private boolean bound;
    private boolean resumed;
    private int themeVersion;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PlaybackService.LocalBinder) binder).service();
            service.addListener(BaseActivity.this);
            onServiceReady();
            onPlaybackChanged();
            onQueueChanged();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            onProgressTick();
            ui.postDelayed(this, tickInterval());
        }
    };

    /** Now Playing uses white status bar icons on its dark artwork background. */
    protected boolean lightSystemIcons() {
        return false;
    }

    protected long tickInterval() {
        return 500;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeUtil.apply(this);
        super.onCreate(savedInstanceState);
        ThemeUtil.edgeToEdge(this, lightSystemIcons());
        themeVersion = ThemeUtil.version();
    }

    protected void setupMiniPlayer() {
        View v = findViewById(R.id.mini_player);
        if (v != null) mini = new MiniPlayer(this, v);
    }

    @Override
    protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, PlaybackService.class), conn, BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        if (service != null) service.removeListener(this);
        if (bound) {
            try {
                unbindService(conn);
            } catch (Exception ignored) {
            }
            bound = false;
        }
        service = null;
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (themeVersion != ThemeUtil.version()) {
            recreate();
            return;
        }
        resumed = true;
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        resumed = false;
        ui.removeCallbacks(tick);
        super.onPause();
    }

    protected void onServiceReady() {
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (!com.demerdash.soundplayer.ui.Dialogs.onActivityResult(this, requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onPlaybackChanged() {
        if (mini != null) mini.update(service);
    }

    @Override
    public void onQueueChanged() {
    }

    protected void onProgressTick() {
        if (mini != null) mini.updateProgress(service);
    }

    protected void toast(int res) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }

    protected void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    protected int dp(float v) {
        return ThemeUtil.dp(this, v);
    }

    public PlaybackService service() {
        return service;
    }

    /** Opens Now Playing with a slide-up transition. */
    public void openNowPlaying() {
        startActivity(new Intent(this, NowPlayingActivity.class));
        if (android.os.Build.VERSION.SDK_INT < 34) {
            overridePendingTransition(R.anim.slide_up, R.anim.stay);
        }
    }
}
