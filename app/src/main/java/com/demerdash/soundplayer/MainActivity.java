package com.demerdash.soundplayer;

import android.Manifest;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AbsListView;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.demerdash.soundplayer.data.MusicLibrary;
import com.demerdash.soundplayer.data.PlaylistStore;
import com.demerdash.soundplayer.data.Prefs;
import com.demerdash.soundplayer.model.Album;
import com.demerdash.soundplayer.model.Artist;
import com.demerdash.soundplayer.model.Folder;
import com.demerdash.soundplayer.model.Playlist;
import com.demerdash.soundplayer.model.Song;
import com.demerdash.soundplayer.ui.AlbumGridAdapter;
import com.demerdash.soundplayer.ui.Dialogs;
import com.demerdash.soundplayer.ui.Row;
import com.demerdash.soundplayer.ui.RowAdapter;
import com.demerdash.soundplayer.ui.ThemeUtil;
import com.demerdash.soundplayer.util.Format;
import com.demerdash.soundplayer.util.TextNorm;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Library: Songs / Albums / Artists / Folders / Playlists. */
public final class MainActivity extends BaseActivity implements MusicLibrary.Listener, PlaylistStore.Listener,
        RowAdapter.Callback, AlbumGridAdapter.Callback {

    public static final String ACTION_SHUFFLE_ALL = "com.demerdash.soundplayer.action.SHUFFLE_ALL";
    public static final String ACTION_OPEN_FAVORITES = "com.demerdash.soundplayer.action.OPEN_FAVORITES";
    public static final String ACTION_SEARCH = "com.demerdash.soundplayer.action.SEARCH";

    private static final int REQ_PERMS = 10;
    private static final int TAB_SONGS = 0;
    private static final int TAB_ALBUMS = 1;
    private static final int TAB_ARTISTS = 2;
    private static final int TAB_FOLDERS = 3;
    private static final int TAB_PLAYLISTS = 4;

    private static final long ID_SHUFFLE = -10;
    private static final long ID_NEW_PLAYLIST = -11;

    private MusicLibrary library;
    private PlaylistStore store;
    private ListView list;
    private GridView grid;
    private View empty;
    private View perm;
    private View loading;
    private TextView permButton;
    private HorizontalScrollView tabsScroll;
    private final TextView[] tabViews = new TextView[5];
    private View sortButton;
    private int tab;
    private RowAdapter adapter;
    private AlbumGridAdapter albumAdapter;
    private List<Song> visibleSongs = new ArrayList<>();
    private final int[][] scrollPos = new int[5][2];
    private String pendingAction;
    private boolean permDeniedForever;
    private final Collator collator = Collator.getInstance();

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        collator.setStrength(Collator.PRIMARY);
        library = MusicLibrary.get(this);
        store = PlaylistStore.get(this);

        list = findViewById(R.id.list);
        grid = findViewById(R.id.grid);
        empty = findViewById(R.id.empty);
        perm = findViewById(R.id.perm_card);
        loading = findViewById(R.id.loading);
        permButton = findViewById(R.id.perm_button);
        tabsScroll = findViewById(R.id.tabs_scroll);
        sortButton = findViewById(R.id.btn_sort);

        ThemeUtil.padInsets(findViewById(R.id.top_bar), true, false, false);
        ThemeUtil.padInsets(list, false, true, false);
        ThemeUtil.padInsets(grid, false, true, false);

        adapter = new RowAdapter(this, this);
        list.setAdapter(adapter);
        list.setFastScrollEnabled(true);
        albumAdapter = new AlbumGridAdapter(this, this);
        grid.setAdapter(albumAdapter);
        touchSlop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        pages = findViewById(R.id.pages);
        buildTabs();

        findViewById(R.id.btn_search).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SearchActivity.class));
            }
        });
        sortButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSort();
            }
        });
        findViewById(R.id.btn_menu).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(v);
            }
        });
        permButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestAccess();
            }
        });
        findViewById(R.id.empty_action).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toast(R.string.rescan_started);
                library.load(true);
            }
        });

        setupMiniPlayer();
        library.addListener(this);
        store.addListener(this);
        tab = Format.clamp(Prefs.lastTab(this), 0, 4);
        selectTab(tab, false);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkPermission();
    }

    @Override
    protected void onDestroy() {
        library.removeListener(this);
        store.removeListener(this);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ permissions

    private void checkPermission() {
        boolean ok = MusicLibrary.hasPermission(this);
        perm.setVisibility(ok ? View.GONE : View.VISIBLE);
        tabsScroll.setVisibility(ok ? View.VISIBLE : View.INVISIBLE);
        if (ok) {
            if (!library.isLoaded()) {
                loading.setVisibility(View.VISIBLE);
                library.load(false);
            }
        } else {
            list.setVisibility(View.GONE);
            grid.setVisibility(View.GONE);
            empty.setVisibility(View.GONE);
            loading.setVisibility(View.GONE);
            permButton.setText(permDeniedForever ? R.string.perm_settings_button : R.string.perm_button);
        }
    }

    private void requestAccess() {
        if (permDeniedForever) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {
            }
            return;
        }
        List<String> perms = new ArrayList<>();
        perms.add(MusicLibrary.audioPermission());
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS);
        requestPermissions(perms.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) return;
        if (MusicLibrary.hasPermission(this)) {
            perm.setVisibility(View.GONE);
            tabsScroll.setVisibility(View.VISIBLE);
            loading.setVisibility(View.VISIBLE);
            library.load(true);
        } else {
            permDeniedForever = !shouldShowRequestPermissionRationale(MusicLibrary.audioPermission());
            permButton.setText(permDeniedForever ? R.string.perm_settings_button : R.string.perm_button);
            toast(R.string.perm_denied);
        }
    }

    // ------------------------------------------------------------------ intents (shortcuts)

    private void handleIntent(Intent i) {
        if (i == null || i.getAction() == null) return;
        String a = i.getAction();
        if (ACTION_SHUFFLE_ALL.equals(a) || ACTION_OPEN_FAVORITES.equals(a) || ACTION_SEARCH.equals(a)) {
            pendingAction = a;
            i.setAction(Intent.ACTION_MAIN);
            handlePendingAction();
        }
    }

    private void handlePendingAction() {
        if (pendingAction == null) return;
        if (ACTION_SEARCH.equals(pendingAction)) {
            pendingAction = null;
            startActivity(new Intent(this, SearchActivity.class));
        } else if (ACTION_OPEN_FAVORITES.equals(pendingAction)) {
            pendingAction = null;
            startActivity(DetailActivity.smart(this, DetailActivity.TYPE_FAVORITES));
        } else if (ACTION_SHUFFLE_ALL.equals(pendingAction)) {
            if (!library.isLoaded() || service == null) return;
            pendingAction = null;
            List<Song> all = library.songs();
            if (!all.isEmpty()) {
                service.playSongs(all, -1, true);
                openNowPlaying();
            }
        }
    }

    @Override
    protected void onServiceReady() {
        handlePendingAction();
    }

    // ------------------------------------------------------------------ data callbacks

    @Override
    public void onLibraryChanged() {
        loading.setVisibility(View.GONE);
        refresh();
        handlePendingAction();
    }

    @Override
    public void onPlaylistsChanged() {
        if (tab == TAB_PLAYLISTS) refresh();
    }

    @Override
    public void onPlaybackChanged() {
        super.onPlaybackChanged();
        Song cur = service != null ? service.current() : null;
        adapter.setPlaying(cur != null ? cur.id : Long.MIN_VALUE, service != null && service.isPlaying());
    }

    // ------------------------------------------------------------------ tabs

    private void buildTabs() {
        LinearLayout tabs = findViewById(R.id.tabs);
        int[] labels = {R.string.tab_songs, R.string.tab_albums, R.string.tab_artists, R.string.tab_folders, R.string.tab_playlists};
        int[][] states = {{android.R.attr.state_selected}, {}};
        int[] colors = {ThemeUtil.color(this, R.attr.spOnAccent), ThemeUtil.color(this, R.attr.spText)};
        ColorStateList csl = new ColorStateList(states, colors);
        for (int i = 0; i < labels.length; i++) {
            TextView t = new TextView(this);
            t.setText(labels[i]);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
            t.setTextColor(csl);
            t.setBackgroundResource(R.drawable.bg_tab);
            t.setPadding(dp(18), dp(9), dp(18), dp(9));
            t.setGravity(android.view.Gravity.CENTER);
            final int index = i;
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(index, true);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            tabs.addView(t, lp);
            tabViews[i] = t;
        }
    }

    private void selectTab(int t, boolean animate) {
        saveScroll();
        tab = t;
        Prefs.setLastTab(this, t);
        for (int i = 0; i < tabViews.length; i++) tabViews[i].setSelected(i == t);
        final TextView sel = tabViews[t];
        tabsScroll.post(new Runnable() {
            @Override
            public void run() {
                int x = sel.getLeft() - (tabsScroll.getWidth() - sel.getWidth()) / 2;
                tabsScroll.smoothScrollTo(Math.max(0, x), 0);
            }
        });
        sortButton.setVisibility(t == TAB_PLAYLISTS ? View.GONE : View.VISIBLE);
        refresh();
        restoreScroll();
        if (animate) {
            View v = t == TAB_ALBUMS ? grid : list;
            v.setAlpha(0f);
            v.setTranslationY(dp(12));
            v.animate().alpha(1f).translationY(0).setDuration(180).start();
        }
    }

    private void saveScroll() {
        AbsListView v = tab == TAB_ALBUMS ? grid : list;
        int first = v.getFirstVisiblePosition();
        View c = v.getChildAt(0);
        scrollPos[tab][0] = first;
        scrollPos[tab][1] = c == null ? 0 : c.getTop() - v.getPaddingTop();
    }

    private void restoreScroll() {
        if (tab == TAB_ALBUMS) grid.setSelection(scrollPos[tab][0]);
        else list.setSelectionFromTop(scrollPos[tab][0], scrollPos[tab][1] + list.getPaddingTop());
    }

    // Horizontal swipe anywhere on the list switches tabs (rows get ACTION_CANCEL so they don't click).
    private View pages;
    private int touchSlop;
    private float downX;
    private float downY;
    private boolean tracking;
    private boolean swiping;

    private boolean inside(View v, MotionEvent ev) {
        if (v == null || v.getVisibility() != View.VISIBLE) return false;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        float x = ev.getRawX();
        float y = ev.getRawY();
        return x >= loc[0] && x < loc[0] + v.getWidth() && y >= loc[1] && y < loc[1] + v.getHeight();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tracking = perm.getVisibility() != View.VISIBLE && inside(pages, ev) && !inside(findViewById(R.id.mini_player), ev);
                swiping = false;
                downX = ev.getRawX();
                downY = ev.getRawY();
                break;
            case MotionEvent.ACTION_MOVE:
                if (tracking && !swiping) {
                    float dx = ev.getRawX() - downX;
                    float dy = ev.getRawY() - downY;
                    if (Math.abs(dx) > touchSlop * 2 && Math.abs(dx) > Math.abs(dy) * 2f) {
                        swiping = true;
                        MotionEvent cancel = MotionEvent.obtain(ev);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        super.dispatchTouchEvent(cancel);
                        cancel.recycle();
                    } else if (Math.abs(dy) > touchSlop * 2) {
                        tracking = false;
                    }
                }
                if (swiping) {
                    pages.setTranslationX((ev.getRawX() - downX) * 0.25f);
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (swiping) {
                    float dx = ev.getRawX() - downX;
                    swiping = false;
                    tracking = false;
                    pages.animate().translationX(0).setDuration(150).start();
                    if (ev.getActionMasked() == MotionEvent.ACTION_UP && Math.abs(dx) > dp(70)) {
                        boolean rtl = getWindow().getDecorView().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                        boolean forward = (dx < 0) != rtl;
                        int t = tab + (forward ? 1 : -1);
                        if (t >= 0 && t < tabViews.length) selectTab(t, true);
                    }
                    return true;
                }
                tracking = false;
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    // ------------------------------------------------------------------ content

    private void refresh() {
        if (!MusicLibrary.hasPermission(this)) return;
        boolean loaded = library.isLoaded();
        boolean noMusic = loaded && library.songs().isEmpty();
        if (tab == TAB_ALBUMS) {
            list.setVisibility(View.GONE);
            grid.setVisibility(noMusic ? View.GONE : View.VISIBLE);
            albumAdapter.setAlbums(sortedAlbums());
        } else {
            grid.setVisibility(View.GONE);
            boolean showEmpty = noMusic && tab != TAB_PLAYLISTS;
            list.setVisibility(showEmpty ? View.GONE : View.VISIBLE);
            List<Row> rows = new ArrayList<>();
            boolean sections = false;
            switch (tab) {
                case TAB_SONGS:
                    sections = buildSongRows(rows);
                    break;
                case TAB_ARTISTS:
                    sections = buildArtistRows(rows);
                    break;
                case TAB_FOLDERS:
                    buildFolderRows(rows);
                    break;
                default:
                    buildPlaylistRows(rows);
                    break;
            }
            adapter.setRows(rows, sections);
            list.setFastScrollEnabled(sections && rows.size() > 40);
        }
        empty.setVisibility(noMusic && tab != TAB_PLAYLISTS ? View.VISIBLE : View.GONE);
        onPlaybackChanged();
    }

    private String countSongs(int n) {
        return getResources().getQuantityString(R.plurals.songs_count, n, n);
    }

    private boolean buildSongRows(List<Row> rows) {
        final int sort = Prefs.sort(this, "songs", 0);
        List<Song> songs = new ArrayList<>(library.songs());
        Comparator<Song> cmp = null;
        switch (sort) {
            case 1:
                cmp = new Comparator<Song>() {
                    @Override
                    public int compare(Song a, Song b) {
                        int r = collator.compare(a.artist, b.artist);
                        return r != 0 ? r : collator.compare(a.title, b.title);
                    }
                };
                break;
            case 2:
                cmp = new Comparator<Song>() {
                    @Override
                    public int compare(Song a, Song b) {
                        int r = collator.compare(a.album, b.album);
                        if (r != 0) return r;
                        return a.track != b.track ? (a.track < b.track ? -1 : 1) : collator.compare(a.title, b.title);
                    }
                };
                break;
            case 3:
                cmp = new Comparator<Song>() {
                    @Override
                    public int compare(Song a, Song b) {
                        return a.duration > b.duration ? -1 : (a.duration == b.duration ? 0 : 1);
                    }
                };
                break;
            case 4:
                cmp = new Comparator<Song>() {
                    @Override
                    public int compare(Song a, Song b) {
                        return a.dateAdded > b.dateAdded ? -1 : (a.dateAdded == b.dateAdded ? 0 : 1);
                    }
                };
                break;
            case 5:
                cmp = new Comparator<Song>() {
                    @Override
                    public int compare(Song a, Song b) {
                        return a.year > b.year ? -1 : (a.year == b.year ? collator.compare(a.title, b.title) : 1);
                    }
                };
                break;
            default:
                break;
        }
        if (cmp != null) Collections.sort(songs, cmp);
        visibleSongs = songs;
        if (!songs.isEmpty()) {
            rows.add(Row.action(ID_SHUFFLE, R.drawable.ic_shuffle, getString(R.string.shuffle_all), countSongs(songs.size())));
        }
        for (int i = 0; i < songs.size(); i++) {
            Song s = songs.get(i);
            Row r = new Row(Row.SONG, s);
            r.title = s.title;
            r.subtitle = s.artist + " • " + s.album;
            r.trailing = Format.duration(s.duration);
            r.songIndex = i;
            if (sort == 0) r.section = TextNorm.sectionLetter(s.title);
            else if (sort == 1) r.section = TextNorm.sectionLetter(s.artist);
            else if (sort == 2) r.section = TextNorm.sectionLetter(s.album);
            rows.add(r);
        }
        return sort <= 2;
    }

    private boolean buildArtistRows(List<Row> rows) {
        int sort = Prefs.sort(this, "artists", 0);
        List<Artist> artists = new ArrayList<>(library.artists());
        if (sort == 1) {
            Collections.sort(artists, new Comparator<Artist>() {
                @Override
                public int compare(Artist a, Artist b) {
                    return b.songs.size() - a.songs.size();
                }
            });
        }
        for (Artist a : artists) {
            Row r = new Row(Row.ARTIST, a);
            r.title = a.name;
            r.subtitle = getResources().getQuantityString(R.plurals.albums_count, a.albumIds.size(), a.albumIds.size())
                    + " • " + countSongs(a.songs.size());
            if (sort == 0) r.section = TextNorm.sectionLetter(a.name);
            rows.add(r);
        }
        return sort == 0;
    }

    private void buildFolderRows(List<Row> rows) {
        int sort = Prefs.sort(this, "folders", 0);
        List<Folder> folders = new ArrayList<>(library.folders());
        if (sort == 1) {
            Collections.sort(folders, new Comparator<Folder>() {
                @Override
                public int compare(Folder a, Folder b) {
                    return b.songs.size() - a.songs.size();
                }
            });
        }
        for (Folder f : folders) {
            Row r = new Row(Row.FOLDER, f);
            r.icon = R.drawable.ic_folder;
            r.title = f.name;
            r.subtitle = countSongs(f.songs.size()) + " • " + f.path;
            rows.add(r);
        }
    }

    private void buildPlaylistRows(List<Row> rows) {
        rows.add(Row.action(ID_NEW_PLAYLIST, R.drawable.ic_add, getString(R.string.new_playlist), null));
        int favs = library.songsFor(store.favorites()).size();
        rows.add(smartRow(DetailActivity.TYPE_FAVORITES, R.drawable.ic_favorite, R.string.favorites, countSongs(favs)));
        rows.add(smartRow(DetailActivity.TYPE_RECENT_ADDED, R.drawable.ic_new, R.string.recently_added,
                countSongs(Math.min(100, library.songs().size()))));
        rows.add(smartRow(DetailActivity.TYPE_RECENT_PLAYED, R.drawable.ic_history, R.string.recently_played,
                countSongs(library.songsFor(store.recentlyPlayed(100)).size())));
        rows.add(smartRow(DetailActivity.TYPE_MOST_PLAYED, R.drawable.ic_trending, R.string.most_played,
                countSongs(library.songsFor(store.mostPlayed(100)).size())));
        List<Playlist> pls = store.playlists();
        if (!pls.isEmpty()) rows.add(Row.header(getString(R.string.tab_playlists)));
        for (Playlist p : pls) {
            Row r = new Row(Row.PLAYLIST, p);
            r.id = p.id;
            r.icon = R.drawable.ic_playlist;
            r.title = p.name;
            r.subtitle = countSongs(library.songsFor(p.songIds).size());
            rows.add(r);
        }
    }

    private Row smartRow(int type, int icon, int title, String sub) {
        Row r = new Row(Row.PLAYLIST, null);
        r.id = type;
        r.icon = icon;
        r.title = getString(title);
        r.subtitle = sub;
        return r;
    }

    private List<Album> sortedAlbums() {
        int sort = Prefs.sort(this, "albums", 0);
        List<Album> albums = new ArrayList<>(library.albums());
        Comparator<Album> cmp = null;
        if (sort == 1) {
            cmp = new Comparator<Album>() {
                @Override
                public int compare(Album a, Album b) {
                    int r = collator.compare(a.artist, b.artist);
                    return r != 0 ? r : collator.compare(a.title, b.title);
                }
            };
        } else if (sort == 2) {
            cmp = new Comparator<Album>() {
                @Override
                public int compare(Album a, Album b) {
                    return b.year - a.year;
                }
            };
        } else if (sort == 3) {
            cmp = new Comparator<Album>() {
                @Override
                public int compare(Album a, Album b) {
                    return b.songs.size() - a.songs.size();
                }
            };
        }
        if (cmp != null) Collections.sort(albums, cmp);
        return albums;
    }

    private List<Song> songsOf(Row r) {
        switch (r.type) {
            case Row.SONG:
                return Collections.singletonList((Song) r.data);
            case Row.ARTIST:
                return ((Artist) r.data).songs;
            case Row.FOLDER:
                return ((Folder) r.data).songs;
            case Row.PLAYLIST:
                if (r.data instanceof Playlist) return library.songsFor(((Playlist) r.data).songIds);
                return DetailActivity.smartSongs(this, (int) r.id);
            default:
                return new ArrayList<>();
        }
    }

    // ------------------------------------------------------------------ clicks

    @Override
    public void onRowClick(Row r, int position) {
        switch (r.type) {
            case Row.ACTION:
                if (r.id == ID_SHUFFLE) {
                    if (service != null && !visibleSongs.isEmpty()) service.playSongs(visibleSongs, -1, true);
                } else if (r.id == ID_NEW_PLAYLIST) {
                    Dialogs.createPlaylist(this, null, refreshRunnable);
                }
                break;
            case Row.SONG:
                if (service != null) service.playSongs(visibleSongs, r.songIndex, false);
                break;
            case Row.ARTIST:
                startActivity(DetailActivity.artist(this, ((Artist) r.data).id));
                break;
            case Row.FOLDER:
                startActivity(DetailActivity.folder(this, ((Folder) r.data).path));
                break;
            case Row.PLAYLIST:
                if (r.data instanceof Playlist) startActivity(DetailActivity.playlist(this, ((Playlist) r.data).id));
                else startActivity(DetailActivity.smart(this, (int) r.id));
                break;
            default:
                break;
        }
    }

    @Override
    public void onRowMore(Row r, View anchor, int position) {
        if (r.type == Row.SONG) {
            Dialogs.songMenu(this, (Song) r.data, anchor, -1, -1, refreshRunnable);
        } else {
            Dialogs.collectionMenu(this, songsOf(r), anchor, r.data instanceof Playlist ? (Playlist) r.data : null, refreshRunnable);
        }
    }

    @Override
    public void onAlbumClick(Album album) {
        startActivity(DetailActivity.album(this, album.id));
    }

    @Override
    public void onAlbumLongClick(Album album, View anchor) {
        Dialogs.collectionMenu(this, album.songs, anchor, null, null);
    }

    // ------------------------------------------------------------------ menus

    private void showSort() {
        String[] options;
        final String key;
        switch (tab) {
            case TAB_SONGS:
                key = "songs";
                options = new String[]{getString(R.string.sort_title), getString(R.string.sort_artist), getString(R.string.sort_album),
                        getString(R.string.sort_duration), getString(R.string.sort_date_added), getString(R.string.sort_year)};
                break;
            case TAB_ALBUMS:
                key = "albums";
                options = new String[]{getString(R.string.sort_title), getString(R.string.sort_artist), getString(R.string.sort_year),
                        getString(R.string.sort_song_count)};
                break;
            case TAB_ARTISTS:
                key = "artists";
                options = new String[]{getString(R.string.sort_name), getString(R.string.sort_song_count)};
                break;
            case TAB_FOLDERS:
                key = "folders";
                options = new String[]{getString(R.string.sort_name), getString(R.string.sort_song_count)};
                break;
            default:
                return;
        }
        Dialogs.choose(this, getString(R.string.sort_by), options, Prefs.sort(this, key, 0), new Dialogs.Choice() {
            @Override
            public void onChosen(int index) {
                Prefs.setSort(MainActivity.this, key, index);
                scrollPos[tab][0] = 0;
                scrollPos[tab][1] = 0;
                refresh();
                restoreScroll();
            }
        });
    }

    private void showMenu(View anchor) {
        PopupMenu p = new PopupMenu(this, anchor);
        Menu m = p.getMenu();
        m.add(Menu.NONE, 1, 1, R.string.menu_equalizer).setIcon(R.drawable.ic_equalizer);
        m.add(Menu.NONE, 2, 2, R.string.menu_sleep_timer).setIcon(R.drawable.ic_sleep);
        m.add(Menu.NONE, 3, 3, R.string.menu_rescan).setIcon(R.drawable.ic_refresh);
        m.add(Menu.NONE, 4, 4, R.string.menu_settings).setIcon(R.drawable.ic_settings);
        ColorStateList tint = ColorStateList.valueOf(ThemeUtil.color(this, R.attr.spTextDim));
        for (int i = 0; i < m.size(); i++) m.getItem(i).setIconTintList(tint);
        if (Build.VERSION.SDK_INT >= 29) p.setForceShowIcon(true);
        p.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        startActivity(new Intent(MainActivity.this, EqualizerActivity.class));
                        return true;
                    case 2:
                        Dialogs.sleepTimer(MainActivity.this);
                        return true;
                    case 3:
                        toast(R.string.rescan_started);
                        library.load(true);
                        return true;
                    case 4:
                        startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                        return true;
                    default:
                        return false;
                }
            }
        });
        p.show();
    }
}
