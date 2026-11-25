package com.demerdash.soundplayer.data;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import com.demerdash.soundplayer.model.Album;
import com.demerdash.soundplayer.model.Song;
import com.demerdash.soundplayer.ui.PlaceholderDrawable;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Asynchronous album-art loader with a memory cache. Newest requests are served first (LIFO). */
public final class ArtLoader {

    private static ArtLoader instance;

    public static synchronized ArtLoader get(Context c) {
        if (instance == null) instance = new ArtLoader(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final ContentResolver cr;
    private final LruCache<String, Bitmap> cache;
    private final Set<String> misses = Collections.synchronizedSet(new HashSet<String>());
    private final Map<ImageView, String> wanted = Collections.synchronizedMap(new WeakHashMap<ImageView, String>());
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor pool;

    private ArtLoader(Context c) {
        app = c;
        cr = c.getContentResolver();
        int maxKb = (int) Math.min(Runtime.getRuntime().maxMemory() / 1024L / 7L, 96L * 1024L);
        cache = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
        LinkedBlockingDeque<Runnable> lifo = new LinkedBlockingDeque<Runnable>() {
            @Override
            public boolean offer(Runnable r) {
                return super.offerFirst(r);
            }
        };
        pool = new ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS, lifo, new ThreadFactory() {
            @Override
            public Thread newThread(final Runnable r) {
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                        r.run();
                    }
                }, "art-loader");
                t.setDaemon(true);
                return t;
            }
        });
        pool.allowCoreThreadTimeOut(true);
    }

    public void clearCache() {
        cache.evictAll();
        misses.clear();
    }

    // ------------------------------------------------------------------ binding

    public void bindSong(ImageView iv, Song song, int sizePx) {
        if (song == null) {
            wanted.remove(iv);
            iv.setImageDrawable(new PlaceholderDrawable(0));
            return;
        }
        bind(iv, "s" + song.id + "_" + bucket(sizePx), song.albumId != 0 ? song.albumId : song.id, song, null, sizePx);
    }

    public void bindAlbum(ImageView iv, Album album, int sizePx) {
        if (album == null) {
            wanted.remove(iv);
            iv.setImageDrawable(new PlaceholderDrawable(0));
            return;
        }
        bind(iv, "a" + album.id + "_" + bucket(sizePx), album.id, null, album, sizePx);
    }

    private void bind(final ImageView iv, final String key, long seed, final Song song, final Album album, int sizePx) {
        final int size = bucket(sizePx);
        wanted.put(iv, key);
        Bitmap cached = cache.get(key);
        if (cached != null) {
            iv.animate().cancel();
            iv.setAlpha(1f);
            iv.setImageBitmap(cached);
            return;
        }
        iv.animate().cancel();
        iv.setAlpha(1f);
        iv.setImageDrawable(new PlaceholderDrawable(seed));
        if (misses.contains(key)) return;
        pool.execute(new Runnable() {
            @Override
            public void run() {
                if (!key.equals(wanted.get(iv))) return; // view was recycled meanwhile
                Bitmap b = cache.get(key);
                if (b == null) {
                    b = song != null ? decodeSong(song, size) : decodeAlbum(album, size);
                    if (b == null) {
                        misses.add(key);
                        return;
                    }
                    cache.put(key, b);
                }
                final Bitmap result = b;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!key.equals(wanted.get(iv))) return;
                        iv.setImageBitmap(result);
                        iv.setAlpha(0f);
                        iv.animate().alpha(1f).setDuration(160).start();
                    }
                });
            }
        });
    }

    /** Blocking load for background threads (notification, now playing, widget). */
    public Bitmap loadSync(Song song, int sizePx) {
        if (song == null) return null;
        int size = bucket(sizePx);
        String key = "s" + song.id + "_" + size;
        Bitmap b = cache.get(key);
        if (b != null) return b;
        if (misses.contains(key)) return null;
        b = decodeSong(song, size);
        if (b != null) cache.put(key, b);
        else misses.add(key);
        return b;
    }

    private static int bucket(int px) {
        if (px <= 160) return 160;
        if (px <= 320) return 320;
        if (px <= 640) return 640;
        return 1024;
    }

    // ------------------------------------------------------------------ decoding

    private Bitmap decodeSong(Song song, int size) {
        Bitmap b = null;
        if (!song.isExternal() && Build.VERSION.SDK_INT >= 29) {
            try {
                b = cr.loadThumbnail(song.uri, new Size(size, size), null);
            } catch (Throwable ignored) {
                b = null;
            }
        } else if (!song.isExternal()) {
            b = legacyAlbumArt(song.albumId, size);
        }
        if (b == null) b = embedded(song.uri, size);
        return b;
    }

    private Bitmap decodeAlbum(Album album, int size) {
        Bitmap b = null;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Uri u = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, album.id);
                b = cr.loadThumbnail(u, new Size(size, size), null);
            } catch (Throwable ignored) {
                b = null;
            }
        } else {
            b = legacyAlbumArt(album.id, size);
        }
        if (b == null) {
            for (int i = 0; i < album.songs.size() && i < 3 && b == null; i++) {
                b = decodeSong(album.songs.get(i), size);
            }
        }
        return b;
    }

    private Bitmap legacyAlbumArt(long albumId, int size) {
        Uri u = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId);
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = cr.openInputStream(u);
            if (in == null) return null;
            try {
                BitmapFactory.decodeStream(in, null, o);
            } finally {
                in.close();
            }
            if (o.outWidth <= 0) return null;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample(o.outWidth, o.outHeight, size);
            in = cr.openInputStream(u);
            if (in == null) return null;
            try {
                return BitmapFactory.decodeStream(in, null, o);
            } finally {
                in.close();
            }
        } catch (Throwable e) {
            return null;
        }
    }

    private Bitmap embedded(Uri uri, int size) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(app, uri);
            byte[] pic = mmr.getEmbeddedPicture();
            if (pic == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
            if (o.outWidth <= 0) return null;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample(o.outWidth, o.outHeight, size);
            return BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
        } catch (Throwable e) {
            return null;
        } finally {
            try {
                mmr.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static int sample(int w, int h, int target) {
        int s = 1;
        while (w / (s * 2) >= target && h / (s * 2) >= target) s *= 2;
        return s;
    }
}
