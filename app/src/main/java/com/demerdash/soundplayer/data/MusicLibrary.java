package com.demerdash.soundplayer.data;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import com.demerdash.soundplayer.R;
import com.demerdash.soundplayer.model.Album;
import com.demerdash.soundplayer.model.Artist;
import com.demerdash.soundplayer.model.Folder;
import com.demerdash.soundplayer.model.Song;
import com.demerdash.soundplayer.util.Format;
import com.demerdash.soundplayer.util.TextNorm;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads the device music library from MediaStore and keeps it fresh with a ContentObserver. */
public final class MusicLibrary {

    private static final String TAG = "MusicLibrary";

    public interface Listener {
        void onLibraryChanged();
    }

    /** Immutable result of one MediaStore scan. */
    private static final class Snapshot {
        List<Song> songs = new ArrayList<>();
        List<Album> albums = new ArrayList<>();
        List<Artist> artists = new ArrayList<>();
        List<Folder> folders = new ArrayList<>();
        Map<Long, Song> songById = new HashMap<>();
        Map<Long, Album> albumById = new HashMap<>();
        Map<Long, Artist> artistById = new HashMap<>();
        Map<String, Folder> folderByPath = new HashMap<>();
        Map<Long, String> searchKeys = new HashMap<>();
    }

    private static MusicLibrary instance;

    public static synchronized MusicLibrary get(Context c) {
        if (instance == null) instance = new MusicLibrary(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile Snapshot snap = new Snapshot();
    private volatile boolean loaded;
    private boolean loading;
    private boolean reloadPending;
    private boolean observerRegistered;

    private final Runnable reloadRunnable = new Runnable() {
        @Override
        public void run() {
            load(true);
        }
    };

    private final ContentObserver observer = new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange) {
            main.removeCallbacks(reloadRunnable);
            main.postDelayed(reloadRunnable, 1500);
        }
    };

    private MusicLibrary(Context app) {
        this.app = app;
    }

    public static String audioPermission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    public static boolean hasPermission(Context c) {
        return c.checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public boolean isLoaded() {
        return loaded;
    }

    /** Must be called on the main thread. */
    public void load(boolean force) {
        if (!hasPermission(app)) return;
        if (loading) {
            if (force) reloadPending = true;
            return;
        }
        if (loaded && !force) return;
        loading = true;
        registerObserver();
        final int minDur = Prefs.minDurationSec(app);
        final String unknownArtist = app.getString(R.string.unknown_artist);
        final String unknownAlbum = app.getString(R.string.unknown_album);
        exec.execute(new Runnable() {
            @Override
            public void run() {
                Snapshot s = null;
                try {
                    s = query(minDur, unknownArtist, unknownAlbum);
                } catch (Throwable t) {
                    Log.e(TAG, "Library scan failed", t);
                }
                final Snapshot result = s;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        if (result != null) snap = result;
                        loaded = true;
                        for (Listener l : listeners) l.onLibraryChanged();
                        if (reloadPending) {
                            reloadPending = false;
                            load(true);
                        }
                    }
                });
            }
        });
    }

    private void registerObserver() {
        if (observerRegistered) return;
        try {
            app.getContentResolver().registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer);
            observerRegistered = true;
        } catch (Exception e) {
            Log.w(TAG, "observer", e);
        }
    }

    private Snapshot query(int minDurSec, String unknownArtist, String unknownAlbum) {
        Snapshot s = new Snapshot();
        ContentResolver cr = app.getContentResolver();
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        List<String> proj = new ArrayList<>();
        proj.add(MediaStore.Audio.Media._ID);
        proj.add(MediaStore.Audio.Media.TITLE);
        proj.add(MediaStore.Audio.Media.ARTIST);
        proj.add(MediaStore.Audio.Media.ALBUM);
        proj.add(MediaStore.Audio.Media.ALBUM_ID);
        proj.add(MediaStore.Audio.Media.ARTIST_ID);
        proj.add(MediaStore.Audio.Media.DURATION);
        proj.add(MediaStore.Audio.Media.TRACK);
        proj.add(MediaStore.Audio.Media.YEAR);
        proj.add(MediaStore.Audio.Media.DATA);
        proj.add(MediaStore.Audio.Media.SIZE);
        proj.add(MediaStore.Audio.Media.DATE_ADDED);
        proj.add(MediaStore.Audio.Media.MIME_TYPE);
        proj.add(MediaStore.Audio.Media.DISPLAY_NAME);
        if (Build.VERSION.SDK_INT >= 29) proj.add(MediaStore.Audio.Media.RELATIVE_PATH);
        if (Build.VERSION.SDK_INT >= 30) proj.add(MediaStore.Audio.Media.ALBUM_ARTIST);

        String sel = MediaStore.Audio.Media.IS_MUSIC + " != 0";
        String[] args = null;
        if (minDurSec > 0) {
            sel += " AND " + MediaStore.Audio.Media.DURATION + " >= ?";
            args = new String[]{String.valueOf(minDurSec * 1000L)};
        }

        Map<Long, String> albumArtists = new HashMap<>();
        Cursor c = cr.query(uri, proj.toArray(new String[0]), sel, args, null);
        if (c == null) return s;
        try {
            int iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int iTitle = c.getColumnIndex(MediaStore.Audio.Media.TITLE);
            int iArtist = c.getColumnIndex(MediaStore.Audio.Media.ARTIST);
            int iAlbum = c.getColumnIndex(MediaStore.Audio.Media.ALBUM);
            int iAlbumId = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID);
            int iArtistId = c.getColumnIndex(MediaStore.Audio.Media.ARTIST_ID);
            int iDur = c.getColumnIndex(MediaStore.Audio.Media.DURATION);
            int iTrack = c.getColumnIndex(MediaStore.Audio.Media.TRACK);
            int iYear = c.getColumnIndex(MediaStore.Audio.Media.YEAR);
            int iData = c.getColumnIndex(MediaStore.Audio.Media.DATA);
            int iSize = c.getColumnIndex(MediaStore.Audio.Media.SIZE);
            int iAdded = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED);
            int iMime = c.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE);
            int iDisplay = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME);
            int iRel = Build.VERSION.SDK_INT >= 29 ? c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH) : -1;
            int iAlbumArtist = Build.VERSION.SDK_INT >= 30 ? c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST) : -1;

            while (c.moveToNext()) {
                long id = c.getLong(iId);
                String path = str(c, iData);
                String display = str(c, iDisplay);
                String title = str(c, iTitle);
                if (Format.isBlank(title)) title = Format.stripExtension(display != null ? display : path);
                if (Format.isBlank(title)) title = "#" + id;
                String artist = str(c, iArtist);
                if (Format.isBlank(artist) || MediaStore.UNKNOWN_STRING.equals(artist)) artist = unknownArtist;
                String album = str(c, iAlbum);
                if (Format.isBlank(album) || MediaStore.UNKNOWN_STRING.equals(album)) album = unknownAlbum;
                long albumId = iAlbumId >= 0 ? c.getLong(iAlbumId) : 0;
                long artistId = iArtistId >= 0 ? c.getLong(iArtistId) : 0;
                long dur = iDur >= 0 ? c.getLong(iDur) : 0;
                int track = iTrack >= 0 ? c.getInt(iTrack) : 0;
                int year = iYear >= 0 ? c.getInt(iYear) : 0;
                long size = iSize >= 0 ? c.getLong(iSize) : 0;
                long added = iAdded >= 0 ? c.getLong(iAdded) : 0;
                String mime = str(c, iMime);

                String folderPath;
                String folderName;
                if (path != null && path.lastIndexOf('/') > 0) {
                    folderPath = path.substring(0, path.lastIndexOf('/'));
                    folderName = folderPath.substring(folderPath.lastIndexOf('/') + 1);
                } else {
                    String rel = str(c, iRel);
                    if (rel != null) {
                        while (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
                        folderPath = rel;
                        folderName = rel.substring(rel.lastIndexOf('/') + 1);
                    } else {
                        folderPath = "/";
                        folderName = "/";
                    }
                }
                if (folderName.length() == 0) folderName = folderPath;

                String aa = str(c, iAlbumArtist);
                if (!Format.isBlank(aa) && !MediaStore.UNKNOWN_STRING.equals(aa)) albumArtists.put(albumId, aa);

                Song song = new Song(id, title, artist, album, albumId, artistId, dur, track, year, path,
                        folderPath, folderName, size, added, mime, ContentUris.withAppendedId(uri, id));
                s.songs.add(song);
            }
        } finally {
            c.close();
        }

        final Collator collator = Collator.getInstance();
        collator.setStrength(Collator.PRIMARY);
        Collections.sort(s.songs, new Comparator<Song>() {
            @Override
            public int compare(Song a, Song b) {
                return collator.compare(a.title, b.title);
            }
        });

        LinkedHashMap<Long, Album> albums = new LinkedHashMap<>();
        LinkedHashMap<Long, Artist> artists = new LinkedHashMap<>();
        LinkedHashMap<String, Folder> folders = new LinkedHashMap<>();
        for (Song song : s.songs) {
            s.songById.put(song.id, song);
            s.searchKeys.put(song.id, TextNorm.normalize(song.title + " " + song.artist + " " + song.album));

            Album a = albums.get(song.albumId);
            if (a == null) {
                a = new Album(song.albumId, song.album);
                String aa = albumArtists.get(song.albumId);
                a.artist = aa != null ? aa : song.artist;
                a.artistId = song.artistId;
                albums.put(song.albumId, a);
            }
            a.songs.add(song);
            if (song.year > a.year) a.year = song.year;

            Artist ar = artists.get(song.artistId);
            if (ar == null) {
                ar = new Artist(song.artistId, song.artist);
                artists.put(song.artistId, ar);
            }
            ar.songs.add(song);
            ar.albumIds.add(song.albumId);

            Folder f = folders.get(song.folderPath);
            if (f == null) {
                f = new Folder(song.folderPath, song.folderName);
                folders.put(song.folderPath, f);
            }
            f.songs.add(song);
        }

        Comparator<Song> byTrack = new Comparator<Song>() {
            @Override
            public int compare(Song a, Song b) {
                if (a.track != b.track) return a.track < b.track ? -1 : 1;
                return collator.compare(a.title, b.title);
            }
        };
        for (Album a : albums.values()) Collections.sort(a.songs, byTrack);
        final Comparator<Song> byAlbumThenTrack = new Comparator<Song>() {
            @Override
            public int compare(Song a, Song b) {
                int r = collator.compare(a.album, b.album);
                if (r != 0) return r;
                if (a.track != b.track) return a.track < b.track ? -1 : 1;
                return collator.compare(a.title, b.title);
            }
        };
        for (Artist ar : artists.values()) Collections.sort(ar.songs, byAlbumThenTrack);

        s.albums.addAll(albums.values());
        Collections.sort(s.albums, new Comparator<Album>() {
            @Override
            public int compare(Album x, Album y) {
                return collator.compare(x.title, y.title);
            }
        });
        s.artists.addAll(artists.values());
        Collections.sort(s.artists, new Comparator<Artist>() {
            @Override
            public int compare(Artist x, Artist y) {
                return collator.compare(x.name, y.name);
            }
        });
        s.folders.addAll(folders.values());
        Collections.sort(s.folders, new Comparator<Folder>() {
            @Override
            public int compare(Folder x, Folder y) {
                return collator.compare(x.name, y.name);
            }
        });
        for (Album a : s.albums) s.albumById.put(a.id, a);
        for (Artist a : s.artists) s.artistById.put(a.id, a);
        for (Folder f : s.folders) s.folderByPath.put(f.path, f);
        return s;
    }

    private static String str(Cursor c, int i) {
        if (i < 0) return null;
        try {
            return c.getString(i);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ accessors (main thread)

    public List<Song> songs() {
        return Collections.unmodifiableList(snap.songs);
    }

    public List<Album> albums() {
        return Collections.unmodifiableList(snap.albums);
    }

    public List<Artist> artists() {
        return Collections.unmodifiableList(snap.artists);
    }

    public List<Folder> folders() {
        return Collections.unmodifiableList(snap.folders);
    }

    public Song song(long id) {
        return snap.songById.get(id);
    }

    public Album album(long id) {
        return snap.albumById.get(id);
    }

    public Artist artist(long id) {
        return snap.artistById.get(id);
    }

    public Folder folder(String path) {
        return snap.folderByPath.get(path);
    }

    public String searchKey(Song s) {
        String k = snap.searchKeys.get(s.id);
        return k != null ? k : TextNorm.normalize(s.title + " " + s.artist + " " + s.album);
    }

    public List<Song> songsFor(List<Long> ids) {
        List<Song> out = new ArrayList<>(ids.size());
        Map<Long, Song> map = snap.songById;
        for (Long id : ids) {
            Song s = map.get(id);
            if (s != null) out.add(s);
        }
        return out;
    }

    public List<Song> recentlyAdded(int limit) {
        List<Song> list = new ArrayList<>(snap.songs);
        Collections.sort(list, new Comparator<Song>() {
            @Override
            public int compare(Song a, Song b) {
                return a.dateAdded > b.dateAdded ? -1 : (a.dateAdded == b.dateAdded ? 0 : 1);
            }
        });
        return list.size() > limit ? new ArrayList<>(list.subList(0, limit)) : list;
    }

    /** Simple full-text search used by voice search ("play X on SoundPlayer") and the search screen. */
    public List<Song> searchSongs(String query, int limit) {
        String q = TextNorm.normalize(query);
        List<Song> out = new ArrayList<>();
        for (Song s : snap.songs) {
            if (TextNorm.matches(searchKey(s), q)) {
                out.add(s);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }
}
