package com.demerdash.soundplayer;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import com.demerdash.soundplayer.data.MusicLibrary;
import com.demerdash.soundplayer.model.Album;
import com.demerdash.soundplayer.model.Artist;
import com.demerdash.soundplayer.model.Song;
import com.demerdash.soundplayer.ui.Dialogs;
import com.demerdash.soundplayer.ui.Row;
import com.demerdash.soundplayer.ui.RowAdapter;
import com.demerdash.soundplayer.ui.ThemeUtil;
import com.demerdash.soundplayer.util.Format;
import com.demerdash.soundplayer.util.TextNorm;

import java.util.ArrayList;
import java.util.List;

/** Instant search across songs, albums and artists (accent- and Arabic-letter-insensitive). */
public final class SearchActivity extends BaseActivity implements RowAdapter.Callback {

    private EditText input;
    private TextView empty;
    private View clear;
    private RowAdapter adapter;
    private com.demerdash.soundplayer.ui.SelectionController selection;
    private List<Song> songResults = new ArrayList<>();
    private String lastQuery = "";

    private final Runnable searchRunnable = new Runnable() {
        @Override
        public void run() {
            search(input.getText().toString());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);
        input = findViewById(R.id.search_input);
        empty = findViewById(R.id.search_empty);
        clear = findViewById(R.id.search_clear);
        ListView list = findViewById(R.id.search_list);
        ThemeUtil.padInsets(findViewById(R.id.search_top), true, false, false);
        ThemeUtil.padInsets(list, false, true, true);
        adapter = new RowAdapter(this, this);
        list.setAdapter(adapter);
        selection = new com.demerdash.soundplayer.ui.SelectionController(this);
        selection.attach(adapter);

        findViewById(R.id.search_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                input.setText("");
                input.requestFocus();
                showKeyboard();
            }
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                clear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                ui.removeCallbacks(searchRunnable);
                ui.postDelayed(searchRunnable, 120);
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    hideKeyboard();
                    return true;
                }
                return false;
            }
        });
        list.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(android.widget.AbsListView view, int state) {
                if (state == SCROLL_STATE_TOUCH_SCROLL) hideKeyboard();
            }

            @Override
            public void onScroll(android.widget.AbsListView view, int a, int b, int c) {
            }
        });
        setupMiniPlayer();
        input.requestFocus();
        String initial = getIntent().getStringExtra("query");
        if (initial != null) input.setText(initial);
        if (!MusicLibrary.get(this).isLoaded()) MusicLibrary.get(this).load(false);
    }

    @Override
    public void onBackPressed() {
        if (selection != null && selection.isActive()) {
            selection.finish();
            return;
        }
        super.onBackPressed();
    }

    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    private void search(String raw) {
        if (selection != null) selection.finish();
        String q = TextNorm.normalize(raw.trim());
        lastQuery = q;
        List<Row> rows = new ArrayList<>();
        songResults = new ArrayList<>();
        if (q.length() == 0) {
            adapter.setRows(rows, false);
            empty.setText(R.string.search_hint);
            empty.setVisibility(View.VISIBLE);
            return;
        }
        MusicLibrary lib = MusicLibrary.get(this);
        for (Song s : lib.songs()) {
            if (TextNorm.matches(lib.searchKey(s), q)) {
                songResults.add(s);
                if (songResults.size() >= 150) break;
            }
        }
        List<Album> albums = new ArrayList<>();
        for (Album a : lib.albums()) {
            if (TextNorm.matches(TextNorm.normalize(a.title + " " + a.artist), q)) {
                albums.add(a);
                if (albums.size() >= 20) break;
            }
        }
        List<Artist> artists = new ArrayList<>();
        for (Artist a : lib.artists()) {
            if (TextNorm.matches(TextNorm.normalize(a.name), q)) {
                artists.add(a);
                if (artists.size() >= 20) break;
            }
        }
        if (!artists.isEmpty()) {
            rows.add(Row.header(getString(R.string.tab_artists)));
            for (Artist a : artists) {
                Row r = new Row(Row.ARTIST, a);
                r.title = a.name;
                r.subtitle = getResources().getQuantityString(R.plurals.songs_count, a.songs.size(), a.songs.size());
                rows.add(r);
            }
        }
        if (!albums.isEmpty()) {
            rows.add(Row.header(getString(R.string.tab_albums)));
            for (Album a : albums) {
                Row r = new Row(Row.ALBUM, a);
                r.title = a.title;
                r.subtitle = a.artist + " • " + getResources().getQuantityString(R.plurals.songs_count, a.songs.size(), a.songs.size());
                rows.add(r);
            }
        }
        if (!songResults.isEmpty()) {
            rows.add(Row.header(getString(R.string.tab_songs)));
            for (int i = 0; i < songResults.size(); i++) {
                Song s = songResults.get(i);
                Row r = new Row(Row.SONG, s);
                r.title = s.title;
                r.subtitle = s.artist + " • " + s.album;
                r.trailing = Format.duration(s.duration);
                r.songIndex = i;
                rows.add(r);
            }
        }
        adapter.setRows(rows, false);
        empty.setText(R.string.no_results);
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        onPlaybackChanged();
    }

    @Override
    public void onPlaybackChanged() {
        super.onPlaybackChanged();
        Song cur = service != null ? service.current() : null;
        adapter.setPlaying(cur != null ? cur.id : Long.MIN_VALUE, service != null && service.isPlaying());
    }

    @Override
    public void onRowClick(Row row, int position) {
        hideKeyboard();
        if (row.type == Row.SONG) {
            if (service != null) service.playSongs(songResults, row.songIndex, false);
        } else if (row.type == Row.ALBUM) {
            startActivity(DetailActivity.album(this, ((Album) row.data).id));
        } else if (row.type == Row.ARTIST) {
            startActivity(DetailActivity.artist(this, ((Artist) row.data).id));
        }
    }

    @Override
    public void onRowMore(Row row, View anchor, int position) {
        if (row.type == Row.SONG) {
            Dialogs.songMenu(this, (Song) row.data, anchor, -1, -1, null);
        } else if (row.type == Row.ALBUM) {
            Dialogs.collectionMenu(this, ((Album) row.data).songs, anchor, null, null);
        } else if (row.type == Row.ARTIST) {
            Dialogs.collectionMenu(this, ((Artist) row.data).songs, anchor, null, null);
        }
    }
}
