package com.demerdash.soundplayer.ui;

import android.content.res.ColorStateList;
import android.os.Build;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.demerdash.soundplayer.BaseActivity;
import com.demerdash.soundplayer.R;
import com.demerdash.soundplayer.model.Song;
import com.demerdash.soundplayer.playback.PlaybackService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Multi-select for song lists: long-press a song to start, tap songs to add/remove them, then
 * add them all to a playlist (or queue / play / share) in one go.
 */
public final class SelectionController {

    private final BaseActivity activity;
    private final View bar;
    private final TextView count;
    private final Set<Long> selected = new HashSet<>();
    private RowAdapter adapter;
    private boolean active;
    private long playlistId = -1;

    public SelectionController(BaseActivity a) {
        activity = a;
        bar = a.findViewById(R.id.selection_bar);
        count = a.findViewById(R.id.sel_count);
        ThemeUtil.padInsets(bar, true, false, false);
        a.findViewById(R.id.sel_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        a.findViewById(R.id.sel_all).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectAll();
            }
        });
        a.findViewById(R.id.sel_add).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<Song> songs = selectedSongs();
                if (songs.isEmpty()) return;
                Dialogs.addToPlaylist(activity, songs);
                finish();
            }
        });
        a.findViewById(R.id.sel_delete).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                delete();
            }
        });
        a.findViewById(R.id.sel_more).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMore(v);
            }
        });
    }

    public void attach(RowAdapter a) {
        adapter = a;
        a.setSelection(this);
    }

    /** On a user playlist page, Delete offers "Remove from playlist" too. */
    public void setPlaylist(long id) {
        playlistId = id;
    }

    private void delete() {
        final List<Song> songs = selectedSongs();
        if (songs.isEmpty()) return;
        if (playlistId < 0) {
            Dialogs.confirmDelete(activity, songs);
            finish();
            return;
        }
        String[] options = {activity.getString(R.string.menu_remove_from_playlist), activity.getString(R.string.menu_delete)};
        new android.app.AlertDialog.Builder(activity)
                .setTitle(activity.getResources().getQuantityString(R.plurals.selected_count, songs.size(), songs.size()))
                .setItems(options, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) {
                            Set<Long> ids = new HashSet<>();
                            for (Song s : songs) ids.add(s.id);
                            int n = com.demerdash.soundplayer.data.PlaylistStore.get(activity).removeSongs(playlistId, ids);
                            Toast.makeText(activity, activity.getResources().getQuantityString(R.plurals.removed_count, n, n), Toast.LENGTH_SHORT).show();
                        } else {
                            Dialogs.confirmDelete(activity, songs);
                        }
                        finish();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    public boolean isActive() {
        return active;
    }

    public boolean isSelected(Song s) {
        return selected.contains(s.id);
    }

    /** Long-press on a song. */
    public void start(Song s) {
        if (!active) {
            active = true;
            selected.clear();
            bar.setVisibility(View.VISIBLE);
            bar.setAlpha(0f);
            bar.setTranslationY(-ThemeUtil.dp(activity, 24));
            bar.animate().alpha(1f).translationY(0).setDuration(180).start();
        }
        selected.add(s.id);
        changed();
    }

    public void toggle(Song s) {
        if (!selected.remove(s.id)) selected.add(s.id);
        if (selected.isEmpty()) finish();
        else changed();
    }

    public void finish() {
        if (!active) return;
        active = false;
        selected.clear();
        bar.animate().alpha(0f).setDuration(150).withEndAction(new Runnable() {
            @Override
            public void run() {
                if (!active) bar.setVisibility(View.GONE);
            }
        }).start();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void changed() {
        count.setText(activity.getResources().getQuantityString(R.plurals.selected_count, selected.size(), selected.size()));
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void selectAll() {
        if (adapter == null) return;
        boolean all = true;
        for (Song s : listedSongs()) {
            if (!selected.contains(s.id)) {
                all = false;
                break;
            }
        }
        if (all) {
            finish();
            return;
        }
        for (Song s : listedSongs()) selected.add(s.id);
        changed();
    }

    private List<Song> listedSongs() {
        List<Song> out = new ArrayList<>();
        if (adapter == null) return out;
        for (Row r : adapter.rows()) if (r.type == Row.SONG) out.add((Song) r.data);
        return out;
    }

    /** Selected songs in the order they appear in the list. */
    public List<Song> selectedSongs() {
        List<Song> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Song s : listedSongs()) {
            if (selected.contains(s.id) && seen.add(s.id)) out.add(s);
        }
        return out;
    }

    private void showMore(View anchor) {
        PopupMenu p = new PopupMenu(activity, anchor);
        Menu m = p.getMenu();
        m.add(Menu.NONE, 1, 1, R.string.play).setIcon(R.drawable.ic_play);
        m.add(Menu.NONE, 2, 2, R.string.menu_play_next).setIcon(R.drawable.ic_play_next);
        m.add(Menu.NONE, 3, 3, R.string.menu_add_to_queue).setIcon(R.drawable.ic_queue);
        m.add(Menu.NONE, 4, 4, R.string.menu_add_favorite).setIcon(R.drawable.ic_favorite_border);
        m.add(Menu.NONE, 5, 5, R.string.menu_share).setIcon(R.drawable.ic_share);
        ColorStateList tint = ColorStateList.valueOf(ThemeUtil.color(activity, R.attr.spTextDim));
        for (int i = 0; i < m.size(); i++) m.getItem(i).setIconTintList(tint);
        if (Build.VERSION.SDK_INT >= 29) p.setForceShowIcon(true);
        p.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                List<Song> songs = selectedSongs();
                PlaybackService svc = activity.service();
                if (songs.isEmpty()) return true;
                switch (item.getItemId()) {
                    case 1:
                        if (svc != null) svc.playSongs(songs, 0, false);
                        break;
                    case 2:
                        if (svc != null) svc.playNext(songs);
                        Toast.makeText(activity, R.string.playing_next, Toast.LENGTH_SHORT).show();
                        break;
                    case 3:
                        if (svc != null) svc.addToQueue(songs);
                        Toast.makeText(activity, R.string.added_to_queue, Toast.LENGTH_SHORT).show();
                        break;
                    case 4: {
                        com.demerdash.soundplayer.data.PlaylistStore store = com.demerdash.soundplayer.data.PlaylistStore.get(activity);
                        for (Song s : songs) if (!s.isExternal() && !store.isFavorite(s.id)) store.toggleFavorite(s.id);
                        Toast.makeText(activity, R.string.added_to_favorites, Toast.LENGTH_SHORT).show();
                        break;
                    }
                    default:
                        Dialogs.share(activity, songs);
                        break;
                }
                finish();
                return true;
            }
        });
        p.show();
    }
}
