package com.demerdash.soundplayer.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.demerdash.soundplayer.model.Playlist;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Playlists, favorites, play history and play counts, saved as JSON in private app storage.
 * All methods are called on the main thread; disk writes happen on a background thread.
 */
public final class PlaylistStore {

    private static final String TAG = "PlaylistStore";
    private static final int HISTORY_MAX = 200;

    public interface Listener {
        void onPlaylistsChanged();
    }

    private static PlaylistStore instance;

    public static synchronized PlaylistStore get(Context c) {
        if (instance == null) instance = new PlaylistStore(c.getApplicationContext());
        return instance;
    }

    private final File file;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private final List<Playlist> playlists = new ArrayList<>();
    private final LinkedHashSet<Long> favorites = new LinkedHashSet<>();
    private final List<long[]> history = new ArrayList<>(); // {songId, time}
    private final Map<Long, Integer> counts = new HashMap<>();
    private long nextId = 1;

    private final Runnable saveRunnable = new Runnable() {
        @Override
        public void run() {
            final String json = toJson();
            io.execute(new Runnable() {
                @Override
                public void run() {
                    write(json);
                }
            });
        }
    };

    private PlaylistStore(Context c) {
        file = new File(c.getFilesDir(), "library.json");
        read();
    }

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void changed(boolean notify) {
        main.removeCallbacks(saveRunnable);
        main.postDelayed(saveRunnable, 400);
        if (notify) {
            for (Listener l : listeners) l.onPlaylistsChanged();
        }
    }

    // ------------------------------------------------------------------ playlists

    public List<Playlist> playlists() {
        List<Playlist> out = new ArrayList<>();
        for (Playlist p : playlists) out.add(p.copy());
        return out;
    }

    public Playlist playlist(long id) {
        for (Playlist p : playlists) if (p.id == id) return p.copy();
        return null;
    }

    public long create(String name, List<Long> songs) {
        Playlist p = new Playlist(nextId++, name, System.currentTimeMillis());
        if (songs != null) p.songIds.addAll(songs);
        playlists.add(p);
        changed(true);
        return p.id;
    }

    public void rename(long id, String name) {
        Playlist p = find(id);
        if (p == null) return;
        p.name = name;
        changed(true);
    }

    public void delete(long id) {
        Playlist p = find(id);
        if (p == null) return;
        playlists.remove(p);
        changed(true);
    }

    public void addSongs(long id, List<Long> songs) {
        Playlist p = find(id);
        if (p == null || songs == null) return;
        p.songIds.addAll(songs);
        changed(true);
    }

    public void removeAt(long id, int index) {
        Playlist p = find(id);
        if (p == null || index < 0 || index >= p.songIds.size()) return;
        p.songIds.remove(index);
        changed(true);
    }

    public void move(long id, int from, int to) {
        Playlist p = find(id);
        if (p == null || from < 0 || to < 0 || from >= p.songIds.size() || to >= p.songIds.size()) return;
        Long v = p.songIds.remove(from);
        p.songIds.add(to, v);
        changed(true);
    }

    private Playlist find(long id) {
        for (Playlist p : playlists) if (p.id == id) return p;
        return null;
    }

    // ------------------------------------------------------------------ favorites

    public boolean isFavorite(long songId) {
        return favorites.contains(songId);
    }

    /** @return the new state */
    public boolean toggleFavorite(long songId) {
        boolean now;
        if (favorites.contains(songId)) {
            favorites.remove(songId);
            now = false;
        } else {
            favorites.add(songId);
            now = true;
        }
        changed(true);
        return now;
    }

    /** Most recently liked first. */
    public List<Long> favorites() {
        List<Long> out = new ArrayList<>(favorites);
        Collections.reverse(out);
        return out;
    }

    // ------------------------------------------------------------------ history & counts

    public void recordPlay(long songId) {
        if (songId < 0) return;
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i)[0] == songId) history.remove(i);
        }
        history.add(new long[]{songId, System.currentTimeMillis()});
        while (history.size() > HISTORY_MAX) history.remove(0);
        Integer c = counts.get(songId);
        counts.put(songId, c == null ? 1 : c + 1);
        changed(true);
    }

    public int playCount(long songId) {
        Integer c = counts.get(songId);
        return c == null ? 0 : c;
    }

    /** Most recent first. */
    public List<Long> recentlyPlayed(int limit) {
        List<Long> out = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && out.size() < limit; i--) out.add(history.get(i)[0]);
        return out;
    }

    public List<Long> mostPlayed(int limit) {
        List<Map.Entry<Long, Integer>> entries = new ArrayList<>(counts.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<Long, Integer>>() {
            @Override
            public int compare(Map.Entry<Long, Integer> a, Map.Entry<Long, Integer> b) {
                return b.getValue().compareTo(a.getValue());
            }
        });
        List<Long> out = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : entries) {
            out.add(e.getKey());
            if (out.size() >= limit) break;
        }
        return out;
    }

    // ------------------------------------------------------------------ persistence

    private String toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("nextId", nextId);
            JSONArray pls = new JSONArray();
            for (Playlist p : playlists) {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.name);
                o.put("created", p.created);
                JSONArray songs = new JSONArray();
                for (Long id : p.songIds) songs.put(id.longValue());
                o.put("songs", songs);
                pls.put(o);
            }
            root.put("playlists", pls);
            JSONArray fav = new JSONArray();
            for (Long id : favorites) fav.put(id.longValue());
            root.put("favorites", fav);
            JSONArray hist = new JSONArray();
            for (long[] h : history) {
                JSONArray e = new JSONArray();
                e.put(h[0]);
                e.put(h[1]);
                hist.put(e);
            }
            root.put("history", hist);
            JSONObject cnt = new JSONObject();
            for (Map.Entry<Long, Integer> e : counts.entrySet()) cnt.put(String.valueOf(e.getKey()), e.getValue().intValue());
            root.put("counts", cnt);
            return root.toString();
        } catch (Exception e) {
            Log.e(TAG, "toJson", e);
            return null;
        }
    }

    private void write(String json) {
        if (json == null) return;
        File tmp = new File(file.getPath() + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(json.getBytes(Charset.forName("UTF-8")));
            out.getFD().sync();
            out.close();
            out = null;
            if (!tmp.renameTo(file)) Log.w(TAG, "rename failed");
        } catch (IOException e) {
            Log.e(TAG, "write", e);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void read() {
        if (!file.exists()) return;
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] buf = new byte[(int) file.length()];
            int off = 0;
            while (off < buf.length) {
                int r = in.read(buf, off, buf.length - off);
                if (r < 0) break;
                off += r;
            }
            JSONObject root = new JSONObject(new String(buf, 0, off, Charset.forName("UTF-8")));
            nextId = root.optLong("nextId", 1);
            JSONArray pls = root.optJSONArray("playlists");
            if (pls != null) {
                for (int i = 0; i < pls.length(); i++) {
                    JSONObject o = pls.getJSONObject(i);
                    Playlist p = new Playlist(o.getLong("id"), o.optString("name", "Playlist"), o.optLong("created", 0));
                    JSONArray songs = o.optJSONArray("songs");
                    if (songs != null) for (int j = 0; j < songs.length(); j++) p.songIds.add(songs.getLong(j));
                    playlists.add(p);
                    if (p.id >= nextId) nextId = p.id + 1;
                }
            }
            JSONArray fav = root.optJSONArray("favorites");
            if (fav != null) for (int i = 0; i < fav.length(); i++) favorites.add(fav.getLong(i));
            JSONArray hist = root.optJSONArray("history");
            if (hist != null) {
                for (int i = 0; i < hist.length(); i++) {
                    JSONArray e = hist.getJSONArray(i);
                    history.add(new long[]{e.getLong(0), e.getLong(1)});
                }
            }
            JSONObject cnt = root.optJSONObject("counts");
            if (cnt != null) {
                JSONArray names = cnt.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String k = names.getString(i);
                        counts.put(Long.parseLong(k), cnt.getInt(k));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "read", e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
