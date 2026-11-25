package com.demerdash.soundplayer;

import android.app.Application;
import android.util.Log;

/** Application entry point. Keeps a last-chance crash log in private storage for bug reports. */
public final class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    java.io.File f = new java.io.File(getFilesDir(), "last_crash.txt");
                    java.io.PrintWriter w = new java.io.PrintWriter(new java.io.FileWriter(f));
                    w.println(new java.util.Date() + " on " + t.getName());
                    e.printStackTrace(w);
                    w.close();
                } catch (Throwable ignored) {
                    Log.e("SoundPlayer", "crash log failed");
                }
                if (previous != null) previous.uncaughtException(t, e);
            }
        });
    }
}
