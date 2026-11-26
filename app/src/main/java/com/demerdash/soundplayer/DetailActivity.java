package com.demerdash.soundplayer;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;
import android.widget.AbsListView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.demerdash.soundplayer.data.ArtLoader;
import com.demerdash.soundplayer.data.MusicLibrary;
import com.demerdash.soundplayer.data.PlaylistStore;
import com.demerdash.soundplayer.model.Album;
import com.demerdash.soundplayer.model.Artist;
import com.demerdash.soundplayer.model.Folder;
import com.demerdash.soundplayer.model.Playlist;
import com.demerdash.soundplayer.model.Song;
import com.demerdash.soundplayer.ui.Dialogs;
import com.demerdash.soundplayer.ui.PlaceholderDrawable;
import com.demerdash.soundplayer.ui.Row;
import com.demerdash.soundplayer.ui.RowAdapter;
import com.demerdash.soundplayer.ui.ThemeUtil;
import com.demerdash.soundplayer.util.Format;

import java.util.ArrayList;
import java.util.List;

/** Album, artist, folder, playlist and smart-list (favorites, recent, most played) screens. */
public final class DetailActivity extends BaseActivity implements RowAdapter.Callback, MusicLibrary.Listener,
        PlaylistStore.Listener {

    public static final int TYPE_ALBUM = 1;
    public static final int TYPE_ARTIST = 2;
    public static final int TYPE_FOLDER = 3;
    public static final int TYPE_PLAYLIST = 4;
    public static final int TYPE_FAVORITES = 5;
    public static final int TYPE_RECENT_ADDED = 6;
    public static final int TYPE_RECENT_PLAYED = 7;
    public static final int TYPE_MOST_PLAYED = 8;

    public static Intent album(Context c, long id) {
        return new Intent(c, DetailActivity.class).putExtra("type", TYPE_ALBUM).putExtra("id", id);
    }

    public static Intent artist(Context c, long id) {
        return new Intent(c, DetailActivity.class).putExtra("type", TYPE_ARTIST).putExtra("id", id);
    }

    public static Intent folder(Context c, String path) {
        return new Intent(c, DetailActivity.class).putExtra("type", TYPE_FOLDER).putExtra("path", path);
    }

    public static Intent playlist(Context c, long id) {
        return new Intent(c, DetailActivity.class).putExtra("type", TYPE_PLAYLIST).putExtra("id", id);
    }

    public static Intent smart(Context c, int type) {
        return new Intent(c, DetailActivity.class).putExtra("type", type);
    }

    public static List<Song> smartSongs(Context c, int type) {
        MusicLibrary lib = MusicLibrary.get(c);
        PlaylistStore st = PlaylistStore.get(c);
        switch (type) {
            case TYPE_FAVORITES:
                return lib.songsFor(st.favorites());
            case TYPE_RECENT_ADDED:
                return lib.recentlyAdded(100);
            case TYPE_RECENT_PLAYED:
                return lib.songsFor(st.recentlyPlayed(100));
            case TYPE_MOST_PLAYED:
                return lib.songsFor(st.mostPlayed(100));
            default:
                return new ArrayList<>();
        }
    }

    private int type;
    private long id;
    private String path;
    private MusicLibrary library;
    private PlaylistStore store;
    private RowAdapter adapter;
    private com.demerdash.soundplayer.ui.SelectionController selection;
    private ListView list;
    private View header;
    private TextView barTitle;
    private List<Song> songs = new ArrayList<>();
    private final List<Integer> playlistIndex = new ArrayList<>();
    private Playlist userPlaylist;

    private final Runnable reload = new Runnable() {
        @Override
        public void run() {
            load();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        type = getIntent().getIntExtra("type", TYPE_ALBUM);
        id = getIntent().getLongExtra("id", 0);
        path = getIntent().getStringExtra("path");
        library = MusicLibrary.get(this);
        store = PlaylistStore.get(this);

        list = findViewById(R.id.detail_list);
        barTitle = findViewById(R.id.detail_bar_title);
        final View top = findViewById(R.id.detail_top);
        ThemeUtil.padInsets(top, true, false, false);
        final int baseBottom = list.getPaddingBottom();
        list.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                int[] s = ThemeUtil.insets(in, false);
                v.setPadding(s[0], s[1] + dp(56), s[2], baseBottom + s[3]);
                return in;
            }
        });

        header = LayoutInflater.from(this).inflate(R.layout.detail_header, list, false);
        list.addHeaderView(header, null, false);
        adapter = new RowAdapter(this, this);
        list.setAdapter(adapter);
        selection = new com.demerdash.soundplayer.ui.SelectionController(this);
        selection.attach(adapter);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                float a;
                if (first > 0) a = 1f;
                else {
                    View h = view.getChildAt(0);
                    a = h == null ? 0f : Math.min(1f, Math.max(0f, (list.getPaddingTop() - h.getBottom() + dp(140)) / (float) dp(80)));
                }
                barTitle.setAlpha(a);
            }
        });

        findViewById(R.id.detail_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.detail_more).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Dialogs.collectionMenu(DetailActivity.this, songs, v, userPlaylist, reload);
            }
        });
        header.findViewById(R.id.detail_play).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (service != null && !songs.isEmpty()) {
                    service.playSongs(songs, 0, false);
                    openNowPlaying();
                }
            }
        });
        header.findViewById(R.id.detail_shuffle).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (service != null && !songs.isEmpty()) {
                    service.playSongs(songs, -1, true);
                    openNowPlaying();
                }
            }
        });

        setupMiniPlayer();
        library.addListener(this);
        store.addListener(this);
        load();
    }

    @Override
    public void onBackPressed() {
        if (selection != null && selection.isActive()) {
            selection.finish();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        library.removeListener(this);
        store.removeListener(this);
        super.onDestroy();
    }

    @Override
    public void onLibraryChanged() {
        load();
    }

    @Override
    public void onPlaylistsChanged() {
        if (type >= TYPE_PLAYLIST) load();
    }

    @Override
    public void onPlaybackChanged() {
        super.onPlaybackChanged();
        Song cur = service != null ? service.current() : null;
        adapter.setPlaying(cur != null ? cur.id : Long.MIN_VALUE, service != null && service.isPlaying());
    }

    private void load() {
        ImageView artView = header.findViewById(R.id.detail_art);
        ImageView icon = header.findViewById(R.id.detail_icon);
        TextView titleView = header.findViewById(R.id.detail_title);
        TextView subView = header.findViewById(R.id.detail_subtitle);
        artView.setClipToOutline(true);
        artView.setBackgroundResource(R.drawable.bg_card_high);
        icon.setVisibility(View.GONE);
        userPlaylist = null;
        playlistIndex.clear();
        String title;
        String sub;
        ArtLoader art = ArtLoader.get(this);
        int big = dp(210);
        switch (type) {
            case TYPE_ALBUM: {
                Album a = library.album(id);
                if (a == null) {
                    if (library.isLoaded()) finish();
                    return;
                }
                songs = new ArrayList<>(a.songs);
                title = a.title;
                sub = a.artist + (a.year > 0 ? " • " + a.year : "") + " • " + countText(songs);
                art.bindAlbum(artView, a, big);
                break;
            }
            case TYPE_ARTIST: {
                Artist ar = library.artist(id);
                if (ar == null) {
                    if (library.isLoaded()) finish();
                    return;
                }
                songs = new ArrayList<>(ar.songs);
                title = ar.name;
                sub = getResources().getQuantityString(R.plurals.albums_count, ar.albumIds.size(), ar.albumIds.size()) + " • " + countText(songs);
                artView.setBackgroundResource(R.drawable.bg_circle);
                artView.setClipToOutline(true);
                art.bindSong(artView, ar.first(), big);
                break;
            }
            case TYPE_FOLDER: {
                Folder f = path != null ? library.folder(path) : null;
                if (f == null) {
                    if (library.isLoaded()) finish();
                    return;
                }
                songs = new ArrayList<>(f.songs);
                title = f.name;
                sub = f.path + "\n" + countText(songs);
                artView.setImageDrawable(new PlaceholderDrawable(f.path.hashCode()));
                icon.setImageResource(R.drawable.ic_folder);
                icon.setVisibility(View.VISIBLE);
                break;
            }
            case TYPE_PLAYLIST: {
                Playlist p = store.playlist(id);
                if (p == null) {
                    finish();
                    return;
                }
                userPlaylist = p;
                selection.setPlaylist(p.id);
                songs = new ArrayList<>();
                for (int i = 0; i < p.songIds.size(); i++) {
                    Song s = library.song(p.songIds.get(i));
                    if (s != null) {
                        songs.add(s);
                        playlistIndex.add(i);
                    }
                }
                title = p.name;
                sub = countText(songs);
                if (!songs.isEmpty()) art.bindSong(artView, songs.get(0), big);
                else artView.setImageDrawable(new PlaceholderDrawable(p.id));
                if (songs.isEmpty()) {
                    icon.setImageResource(R.drawable.ic_playlist);
                    icon.setVisibility(View.VISIBLE);
                }
                break;
            }
            default: {
                songs = smartSongs(this, type);
                int iconRes;
                int titleRes;
                if (type == TYPE_FAVORITES) {
                    iconRes = R.drawable.ic_favorite;
                    titleRes = R.string.favorites;
                } else if (type == TYPE_RECENT_ADDED) {
                    iconRes = R.drawable.ic_new;
                    titleRes = R.string.recently_added;
                } else if (type == TYPE_RECENT_PLAYED) {
                    iconRes = R.drawable.ic_history;
                    titleRes = R.string.recently_played;
                } else {
                    iconRes = R.drawable.ic_trending;
                    titleRes = R.string.most_played;
                }
                title = getString(titleRes);
                sub = countText(songs);
                artView.setImageDrawable(new PlaceholderDrawable(type * 7919L));
                icon.setImageResource(iconRes);
                icon.setVisibility(View.VISIBLE);
                break;
            }
        }
        titleView.setText(title);
        subView.setText(sub);
        barTitle.setText(title);
        header.findViewById(R.id.detail_play).setEnabled(!songs.isEmpty());
        header.findViewById(R.id.detail_shuffle).setEnabled(!songs.isEmpty());

        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < songs.size(); i++) {
            Song s = songs.get(i);
            Row r = new Row(Row.SONG, s);
            r.title = (type == TYPE_ALBUM && s.trackNumber() > 0 ? s.trackNumber() + ".  " : "") + s.title;
            r.subtitle = type == TYPE_ARTIST ? s.album : s.artist;
            r.trailing = Format.duration(s.duration);
            r.songIndex = i;
            rows.add(r);
        }
        if (rows.isEmpty()) {
            Row r = Row.header(getString(R.string.empty_list));
            rows.add(r);
        }
        adapter.setRows(rows, false);
        onPlaybackChanged();
    }

    private String countText(List<Song> list) {
        long total = 0;
        for (Song s : list) total += s.duration;
        int[] hm = Format.hoursMinutes(total);
        String d = hm[0] > 0 ? getString(R.string.duration_hours_minutes, hm[0], hm[1]) : getString(R.string.duration_minutes, hm[1]);
        return getResources().getQuantityString(R.plurals.songs_count, list.size(), list.size()) + " • " + d;
    }

    @Override
    public void onRowClick(Row row, int position) {
        if (row.type != Row.SONG || service == null) return;
        service.playSongs(songs, row.songIndex, false);
    }

    @Override
    public void onRowMore(Row row, View anchor, int position) {
        if (row.type != Row.SONG) return;
        long plId = userPlaylist != null ? userPlaylist.id : -1;
        int plIdx = userPlaylist != null && row.songIndex < playlistIndex.size() ? playlistIndex.get(row.songIndex) : -1;
        Dialogs.songMenu(this, (Song) row.data, anchor, plId, plIdx, reload);
    }
}
