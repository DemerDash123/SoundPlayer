package com.demerdash.soundplayer.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.demerdash.soundplayer.BaseActivity;
import com.demerdash.soundplayer.DetailActivity;
import com.demerdash.soundplayer.R;
import com.demerdash.soundplayer.data.MusicLibrary;
import com.demerdash.soundplayer.data.PlaylistStore;
import com.demerdash.soundplayer.lyrics.LyricsRepository;
import com.demerdash.soundplayer.model.Playlist;
import com.demerdash.soundplayer.model.Song;
import com.demerdash.soundplayer.playback.PlaybackService;
import com.demerdash.soundplayer.util.Format;

import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Menus and dialogs shared by all screens. */
public final class Dialogs {

    public static final int REQ_DELETE = 4101;
    public static final int REQ_EXPORT_M3U = 4102;
    public static final int REQ_IMPORT_LYRICS = 4103;
    public static final int REQ_WRITE_PERMISSION = 4104;

    private static List<Song> pendingDelete;
    private static long pendingExportPlaylist = -1;
    private static Song pendingLyricsSong;

    private Dialogs() {
    }

    /** Called from BaseActivity.onActivityResult. */
    public static boolean onActivityResult(final BaseActivity a, int req, int res, Intent data) {
        if (req == REQ_DELETE) {
            if (res == Activity.RESULT_OK) {
                if (Build.VERSION.SDK_INT == 29 && pendingDelete != null) {
                    List<Song> retry = pendingDelete;
                    pendingDelete = null;
                    deleteSongs(a, retry, false);
                } else {
                    Toast.makeText(a, R.string.deleted, Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
        if (req == REQ_EXPORT_M3U) {
            if (res == Activity.RESULT_OK && data != null && data.getData() != null && pendingExportPlaylist >= 0) {
                writeM3u(a, pendingExportPlaylist, data.getData());
            }
            pendingExportPlaylist = -1;
            return true;
        }
        if (req == REQ_IMPORT_LYRICS) {
            final Song s = pendingLyricsSong;
            pendingLyricsSong = null;
            if (res == Activity.RESULT_OK && data != null && data.getData() != null && s != null) {
                try {
                    String text = LyricsRepository.readUri(a, data.getData());
                    LyricsRepository.save(a, s, text);
                    Toast.makeText(a, R.string.lyrics_saved, Toast.LENGTH_SHORT).show();
                    if (a instanceof LyricsHost) ((LyricsHost) a).onLyricsChanged();
                } catch (Exception e) {
                    Toast.makeText(a, R.string.export_failed, Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
        return false;
    }

    /** Screens that show lyrics get notified when the user imports or edits them. */
    public interface LyricsHost {
        void onLyricsChanged();
    }

    // ------------------------------------------------------------------ popup menus

    private static final int M_PLAY_NEXT = 1, M_QUEUE = 2, M_PLAYLIST = 3, M_ALBUM = 4, M_ARTIST = 5, M_SHARE = 6,
            M_RINGTONE = 7, M_INFO = 8, M_DELETE = 9, M_REMOVE_PL = 10, M_UP = 11, M_DOWN = 12, M_FAV = 13,
            M_PLAY = 14, M_SHUFFLE = 15, M_RENAME = 16, M_DELETE_PL = 17, M_EXPORT = 18;

    private static MenuItem add(Menu m, int id, int title, int icon, Context c) {
        MenuItem it = m.add(Menu.NONE, id, id, title);
        it.setIcon(icon);
        it.setIconTintList(ColorStateList.valueOf(ThemeUtil.color(c, R.attr.spTextDim)));
        return it;
    }

    private static void forceIcons(PopupMenu p) {
        if (Build.VERSION.SDK_INT >= 29) p.setForceShowIcon(true);
    }

    /** Options for one song. {@code playlistId} >= 0 adds playlist editing items for position {@code index}. */
    public static void songMenu(final BaseActivity a, final Song s, View anchor, final long playlistId, final int index,
                                final Runnable onChanged) {
        PopupMenu p = new PopupMenu(a, anchor);
        Menu m = p.getMenu();
        add(m, M_PLAY_NEXT, R.string.menu_play_next, R.drawable.ic_play_next, a);
        add(m, M_QUEUE, R.string.menu_add_to_queue, R.drawable.ic_queue, a);
        add(m, M_PLAYLIST, R.string.menu_add_to_playlist, R.drawable.ic_playlist_add, a);
        boolean fav = PlaylistStore.get(a).isFavorite(s.id);
        add(m, M_FAV, fav ? R.string.menu_remove_favorite : R.string.menu_add_favorite,
                fav ? R.drawable.ic_favorite : R.drawable.ic_favorite_border, a);
        if (playlistId >= 0) {
            add(m, M_REMOVE_PL, R.string.menu_remove_from_playlist, R.drawable.ic_close, a);
            add(m, M_UP, R.string.menu_move_up, R.drawable.ic_chevron_up, a);
            add(m, M_DOWN, R.string.menu_move_down, R.drawable.ic_chevron_down, a);
        }
        if (!s.isExternal()) {
            add(m, M_ALBUM, R.string.menu_go_to_album, R.drawable.ic_album, a);
            add(m, M_ARTIST, R.string.menu_go_to_artist, R.drawable.ic_person, a);
        }
        add(m, M_SHARE, R.string.menu_share, R.drawable.ic_share, a);
        if (!s.isExternal()) add(m, M_RINGTONE, R.string.menu_ringtone, R.drawable.ic_ringtone, a);
        add(m, M_INFO, R.string.menu_info, R.drawable.ic_info, a);
        if (!s.isExternal()) add(m, M_DELETE, R.string.menu_delete, R.drawable.ic_delete, a);
        forceIcons(p);
        p.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                List<Song> one = Collections.singletonList(s);
                PlaybackService svc = a.service();
                switch (item.getItemId()) {
                    case M_PLAY_NEXT:
                        if (svc != null) {
                            svc.playNext(one);
                            Toast.makeText(a, R.string.playing_next, Toast.LENGTH_SHORT).show();
                        }
                        return true;
                    case M_QUEUE:
                        if (svc != null) {
                            svc.addToQueue(one);
                            Toast.makeText(a, R.string.added_to_queue, Toast.LENGTH_SHORT).show();
                        }
                        return true;
                    case M_PLAYLIST:
                        addToPlaylist(a, one);
                        return true;
                    case M_FAV: {
                        boolean now = PlaylistStore.get(a).toggleFavorite(s.id);
                        Toast.makeText(a, now ? R.string.added_to_favorites : R.string.removed_from_favorites, Toast.LENGTH_SHORT).show();
                        if (onChanged != null) onChanged.run();
                        return true;
                    }
                    case M_REMOVE_PL:
                        PlaylistStore.get(a).removeAt(playlistId, index);
                        if (onChanged != null) onChanged.run();
                        return true;
                    case M_UP:
                        PlaylistStore.get(a).move(playlistId, index, index - 1);
                        if (onChanged != null) onChanged.run();
                        return true;
                    case M_DOWN:
                        PlaylistStore.get(a).move(playlistId, index, index + 1);
                        if (onChanged != null) onChanged.run();
                        return true;
                    case M_ALBUM:
                        a.startActivity(DetailActivity.album(a, s.albumId));
                        return true;
                    case M_ARTIST:
                        a.startActivity(DetailActivity.artist(a, s.artistId));
                        return true;
                    case M_SHARE:
                        share(a, one);
                        return true;
                    case M_RINGTONE:
                        setRingtone(a, s);
                        return true;
                    case M_INFO:
                        songInfo(a, s);
                        return true;
                    case M_DELETE:
                        confirmDelete(a, one);
                        return true;
                    default:
                        return false;
                }
            }
        });
        p.show();
    }

    /** Options for a group of songs (album, artist, folder, playlist). */
    public static void collectionMenu(final BaseActivity a, final List<Song> songs, View anchor, final Playlist userPlaylist,
                                      final Runnable onChanged) {
        PopupMenu p = new PopupMenu(a, anchor);
        Menu m = p.getMenu();
        add(m, M_PLAY, R.string.play, R.drawable.ic_play, a);
        add(m, M_SHUFFLE, R.string.shuffle, R.drawable.ic_shuffle, a);
        add(m, M_PLAY_NEXT, R.string.menu_play_next, R.drawable.ic_play_next, a);
        add(m, M_QUEUE, R.string.menu_add_to_queue, R.drawable.ic_queue, a);
        add(m, M_PLAYLIST, R.string.menu_add_to_playlist, R.drawable.ic_playlist_add, a);
        if (userPlaylist != null) {
            add(m, M_RENAME, R.string.rename, R.drawable.ic_edit, a);
            add(m, M_EXPORT, R.string.menu_export_m3u, R.drawable.ic_file, a);
            add(m, M_DELETE_PL, R.string.delete, R.drawable.ic_delete, a);
        } else {
            add(m, M_SHARE, R.string.menu_share, R.drawable.ic_share, a);
        }
        forceIcons(p);
        p.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                PlaybackService svc = a.service();
                switch (item.getItemId()) {
                    case M_PLAY:
                        if (svc != null && !songs.isEmpty()) svc.playSongs(songs, 0, false);
                        return true;
                    case M_SHUFFLE:
                        if (svc != null && !songs.isEmpty()) svc.playSongs(songs, -1, true);
                        return true;
                    case M_PLAY_NEXT:
                        if (svc != null) {
                            svc.playNext(songs);
                            Toast.makeText(a, R.string.playing_next, Toast.LENGTH_SHORT).show();
                        }
                        return true;
                    case M_QUEUE:
                        if (svc != null) {
                            svc.addToQueue(songs);
                            Toast.makeText(a, R.string.added_to_queue, Toast.LENGTH_SHORT).show();
                        }
                        return true;
                    case M_PLAYLIST:
                        addToPlaylist(a, songs);
                        return true;
                    case M_SHARE:
                        share(a, songs);
                        return true;
                    case M_RENAME:
                        renamePlaylist(a, userPlaylist, onChanged);
                        return true;
                    case M_EXPORT:
                        exportM3u(a, userPlaylist);
                        return true;
                    case M_DELETE_PL:
                        confirmDeletePlaylist(a, userPlaylist, onChanged);
                        return true;
                    default:
                        return false;
                }
            }
        });
        p.show();
    }

    // ------------------------------------------------------------------ playlists

    public static void addToPlaylist(final BaseActivity a, final List<Song> songs) {
        final List<Playlist> pls = PlaylistStore.get(a).playlists();
        List<String> names = new ArrayList<>();
        names.add("+  " + a.getString(R.string.new_playlist));
        names.add("♥  " + a.getString(R.string.favorites));
        for (Playlist p : pls) names.add(p.name);
        new AlertDialog.Builder(a)
                .setTitle(R.string.menu_add_to_playlist)
                .setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            createPlaylist(a, songs, null);
                        } else if (which == 1) {
                            PlaylistStore store = PlaylistStore.get(a);
                            for (Song s : songs) if (!s.isExternal() && !store.isFavorite(s.id)) store.toggleFavorite(s.id);
                            Toast.makeText(a, R.string.added_to_favorites, Toast.LENGTH_SHORT).show();
                        } else {
                            Playlist p = pls.get(which - 2);
                            PlaylistStore.get(a).addSongs(p.id, ids(songs));
                            Toast.makeText(a, a.getString(R.string.added_to_playlist, p.name), Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private static List<Long> ids(List<Song> songs) {
        List<Long> out = new ArrayList<>();
        for (Song s : songs) if (!s.isExternal()) out.add(s.id);
        return out;
    }

    private static EditText nameField(Context c, String initial) {
        EditText e = new EditText(c);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setHint(R.string.playlist_name);
        if (initial != null) {
            e.setText(initial);
            e.setSelection(initial.length());
        }
        return e;
    }

    private static View padded(Context c, View v) {
        FrameLayout f = new FrameLayout(c);
        int p = ThemeUtil.dp(c, 22);
        f.setPadding(p, ThemeUtil.dp(c, 8), p, 0);
        f.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return f;
    }

    public static void createPlaylist(final BaseActivity a, final List<Song> songs, final Runnable after) {
        final EditText e = nameField(a, null);
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle(R.string.new_playlist)
                .setView(padded(a, e))
                .setPositiveButton(R.string.create, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String name = e.getText().toString().trim();
                        if (name.length() == 0) name = a.getString(R.string.my_playlist_default);
                        PlaylistStore.get(a).create(name, songs == null ? null : ids(songs));
                        Toast.makeText(a, songs == null ? a.getString(R.string.playlist_created)
                                : a.getString(R.string.added_to_playlist, name), Toast.LENGTH_SHORT).show();
                        if (after != null) after.run();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        showWithKeyboard(d);
    }

    private static void showWithKeyboard(AlertDialog d) {
        d.show();
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
    }

    public static void renamePlaylist(final BaseActivity a, final Playlist p, final Runnable after) {
        final EditText e = nameField(a, p.name);
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle(R.string.rename)
                .setView(padded(a, e))
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String name = e.getText().toString().trim();
                        if (name.length() > 0) PlaylistStore.get(a).rename(p.id, name);
                        if (after != null) after.run();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        showWithKeyboard(d);
    }

    public static void confirmDeletePlaylist(final BaseActivity a, final Playlist p, final Runnable after) {
        new AlertDialog.Builder(a)
                .setMessage(a.getString(R.string.delete_playlist_q, p.name))
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        PlaylistStore.get(a).delete(p.id);
                        if (after != null) after.run();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    public static void exportM3u(BaseActivity a, Playlist p) {
        pendingExportPlaylist = p.id;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("audio/x-mpegurl")
                .putExtra(Intent.EXTRA_TITLE, p.name + ".m3u");
        try {
            a.startActivityForResult(i, REQ_EXPORT_M3U);
        } catch (Exception e) {
            Toast.makeText(a, R.string.export_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private static void writeM3u(Context c, long playlistId, Uri target) {
        Playlist p = PlaylistStore.get(c).playlist(playlistId);
        if (p == null) return;
        List<Song> songs = MusicLibrary.get(c).songsFor(p.songIds);
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        for (Song s : songs) {
            sb.append("#EXTINF:").append(s.duration / 1000).append(',').append(s.artist).append(" - ").append(s.title).append('\n');
            sb.append(s.path != null ? s.path : s.uri.toString()).append('\n');
        }
        try {
            OutputStream out = c.getContentResolver().openOutputStream(target);
            if (out == null) throw new java.io.IOException("no stream");
            try {
                out.write(sb.toString().getBytes(Charset.forName("UTF-8")));
            } finally {
                out.close();
            }
            Toast.makeText(c, R.string.exported, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(c, R.string.export_failed, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ song actions

    public static void share(Activity a, List<Song> songs) {
        if (songs.isEmpty()) return;
        Intent i;
        if (songs.size() == 1) {
            i = new Intent(Intent.ACTION_SEND).setType(songs.get(0).mime != null ? songs.get(0).mime : "audio/*")
                    .putExtra(Intent.EXTRA_STREAM, songs.get(0).uri);
            i.setClipData(ClipData.newRawUri("", songs.get(0).uri));
        } else {
            ArrayList<Uri> uris = new ArrayList<>();
            for (Song s : songs) if (uris.size() < 100) uris.add(s.uri);
            i = new Intent(Intent.ACTION_SEND_MULTIPLE).setType("audio/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            ClipData cd = ClipData.newRawUri("", uris.get(0));
            for (int k = 1; k < uris.size(); k++) cd.addItem(new ClipData.Item(uris.get(k)));
            i.setClipData(cd);
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            a.startActivity(Intent.createChooser(i, a.getString(R.string.menu_share)));
        } catch (Exception ignored) {
        }
    }

    public static void setRingtone(final Activity a, final Song s) {
        if (!Settings.System.canWrite(a)) {
            Toast.makeText(a, R.string.ringtone_permission, Toast.LENGTH_LONG).show();
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + a.getPackageName())));
            } catch (Exception ignored) {
            }
            return;
        }
        try {
            if (Build.VERSION.SDK_INT < 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Audio.Media.IS_RINGTONE, true);
                a.getContentResolver().update(s.uri, v, null, null);
            }
            RingtoneManager.setActualDefaultRingtoneUri(a, RingtoneManager.TYPE_RINGTONE, s.uri);
            Toast.makeText(a, a.getString(R.string.ringtone_set, s.title), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(a, R.string.ringtone_failed, Toast.LENGTH_SHORT).show();
        }
    }

    public static void songInfo(final Activity a, final Song s) {
        final StringBuilder sb = new StringBuilder();
        line(sb, a.getString(R.string.info_title), s.title);
        line(sb, a.getString(R.string.info_artist), s.artist);
        line(sb, a.getString(R.string.info_album), s.album);
        line(sb, a.getString(R.string.info_duration), Format.duration(s.duration));
        if (s.trackNumber() > 0) line(sb, a.getString(R.string.info_track), String.valueOf(s.trackNumber()));
        if (s.year > 0) line(sb, a.getString(R.string.info_year), String.valueOf(s.year));
        String fmt = s.mime;
        String bitrate = null;
        String rate = null;
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(a, s.uri, null);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    fmt = mime;
                    if (f.containsKey(MediaFormat.KEY_BIT_RATE)) bitrate = (f.getInteger(MediaFormat.KEY_BIT_RATE) / 1000) + " kbps";
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        String ch = f.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? " • " + f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) + " ch" : "";
                        rate = String.format(Locale.US, "%.1f kHz", f.getInteger(MediaFormat.KEY_SAMPLE_RATE) / 1000f) + ch;
                    }
                    break;
                }
            }
        } catch (Exception ignored) {
        } finally {
            ex.release();
        }
        if (fmt != null) line(sb, a.getString(R.string.info_format), fmt.replace("audio/", "").toUpperCase(Locale.US));
        if (bitrate == null && s.size > 0 && s.duration > 0) bitrate = "~" + (s.size * 8 / s.duration) + " kbps";
        if (bitrate != null) line(sb, a.getString(R.string.info_bitrate), bitrate);
        if (rate != null) line(sb, a.getString(R.string.info_sample_rate), rate);
        if (s.size > 0) line(sb, a.getString(R.string.info_size), Format.fileSize(s.size));
        if (s.path != null) line(sb, a.getString(R.string.info_path), s.path);
        if (!s.isExternal()) line(sb, a.getString(R.string.info_plays), String.valueOf(PlaylistStore.get(a).playCount(s.id)));
        TextView tv = new TextView(a);
        tv.setText(sb.toString().trim());
        tv.setTextIsSelectable(true);
        tv.setTextColor(ThemeUtil.color(a, R.attr.spText));
        tv.setTextSize(14);
        tv.setLineSpacing(0, 1.25f);
        new AlertDialog.Builder(a)
                .setTitle(R.string.menu_info)
                .setView(padded(a, tv))
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private static void line(StringBuilder sb, String k, String v) {
        if (v == null) return;
        sb.append(k).append(": ").append(v).append("\n\n");
    }

    public static void confirmDelete(final BaseActivity a, final List<Song> songs) {
        new AlertDialog.Builder(a)
                .setMessage(a.getString(R.string.delete_songs_q, songs.size()))
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        deleteSongs(a, songs, true);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static void deleteSongs(BaseActivity a, List<Song> songs, boolean firstTry) {
        ContentResolver cr = a.getContentResolver();
        List<Uri> uris = new ArrayList<>();
        for (Song s : songs) if (!s.isExternal()) uris.add(s.uri);
        if (uris.isEmpty()) return;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                PendingIntent pi = MediaStore.createDeleteRequest(cr, uris);
                a.startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE, null, 0, 0, 0);
                return;
            }
            if (Build.VERSION.SDK_INT < 29 && a.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                a.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE_PERMISSION);
                return;
            }
            int n = 0;
            for (Uri u : uris) n += cr.delete(u, null, null);
            Toast.makeText(a, n > 0 ? R.string.deleted : R.string.delete_failed, Toast.LENGTH_SHORT).show();
        } catch (SecurityException se) {
            if (Build.VERSION.SDK_INT == 29 && se instanceof RecoverableSecurityException && firstTry) {
                pendingDelete = songs;
                try {
                    a.startIntentSenderForResult(((RecoverableSecurityException) se).getUserAction().getActionIntent().getIntentSender(),
                            REQ_DELETE, null, 0, 0, 0);
                    return;
                } catch (Exception ignored) {
                }
            }
            Toast.makeText(a, R.string.delete_failed, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(a, R.string.delete_failed, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ sleep timer & speed

    public static void sleepTimer(final BaseActivity a) {
        final PlaybackService svc = a.service();
        if (svc == null) return;
        final int[] mins = {10, 15, 30, 45, 60, 90, 120};
        final List<String> items = new ArrayList<>();
        for (int m : mins) items.add(a.getString(R.string.sleep_minutes, m));
        items.add(a.getString(R.string.sleep_end_of_track));
        items.add(a.getString(R.string.sleep_custom));
        final boolean active = svc.sleepRemaining() >= 0;
        if (active) items.add(a.getString(R.string.sleep_off));
        String title = a.getString(R.string.sleep_title);
        long rem = svc.sleepRemaining();
        if (rem > 0) title += " • " + Format.duration(rem);
        else if (rem == 0) title += " • " + a.getString(R.string.sleep_after_song);
        new AlertDialog.Builder(a)
                .setTitle(title)
                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        PlaybackService s = a.service();
                        if (s == null) return;
                        if (which < mins.length) {
                            s.setSleepTimer(mins[which]);
                            Toast.makeText(a, a.getString(R.string.sleep_set, mins[which]), Toast.LENGTH_SHORT).show();
                        } else if (which == mins.length) {
                            s.setSleepAtEndOfTrack();
                            Toast.makeText(a, R.string.sleep_end_track_set, Toast.LENGTH_SHORT).show();
                        } else if (which == mins.length + 1) {
                            customSleep(a);
                        } else {
                            s.cancelSleepTimer();
                            Toast.makeText(a, R.string.sleep_cancelled, Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private static void customSleep(final BaseActivity a) {
        final NumberPicker np = new NumberPicker(a);
        np.setMinValue(1);
        np.setMaxValue(300);
        np.setValue(40);
        np.setWrapSelectorWheel(false);
        new AlertDialog.Builder(a)
                .setTitle(R.string.sleep_title)
                .setView(np)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        PlaybackService s = a.service();
                        if (s == null) return;
                        s.setSleepTimer(np.getValue());
                        Toast.makeText(a, a.getString(R.string.sleep_set, np.getValue()), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Speed 0.5x – 2.0x and pitch 0.5x – 2.0x in 0.05 steps. */
    public static void speed(final BaseActivity a) {
        final PlaybackService svc = a.service();
        if (svc == null) return;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = ThemeUtil.dp(a, 22);
        box.setPadding(p, ThemeUtil.dp(a, 12), p, 0);
        final TextView speedLabel = label(a, box);
        final SeekBar speedBar = bar(a, box);
        final TextView pitchLabel = label(a, box);
        final SeekBar pitchBar = bar(a, box);
        speedBar.setProgress(Math.round((svc.speed() - 0.5f) / 0.05f));
        pitchBar.setProgress(Math.round((svc.pitch() - 0.5f) / 0.05f));
        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                speedLabel.setText(a.getString(R.string.speed) + "  " + a.getString(R.string.speed_value, 0.5f + speedBar.getProgress() * 0.05f));
                pitchLabel.setText(a.getString(R.string.pitch) + "  " + a.getString(R.string.speed_value, 0.5f + pitchBar.getProgress() * 0.05f));
            }
        };
        refresh.run();
        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                refresh.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                PlaybackService s = a.service();
                if (s != null) s.setSpeedPitch(0.5f + speedBar.getProgress() * 0.05f, 0.5f + pitchBar.getProgress() * 0.05f);
            }
        };
        speedBar.setOnSeekBarChangeListener(l);
        pitchBar.setOnSeekBarChangeListener(l);
        new AlertDialog.Builder(a)
                .setTitle(R.string.speed_title)
                .setView(box)
                .setPositiveButton(R.string.done, null)
                .setNeutralButton(R.string.reset, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        PlaybackService s = a.service();
                        if (s != null) s.setSpeedPitch(1f, 1f);
                    }
                })
                .show();
    }

    private static TextView label(Context c, LinearLayout box) {
        TextView t = new TextView(c);
        t.setTextColor(ThemeUtil.color(c, R.attr.spText));
        t.setTextSize(15);
        t.setPadding(0, ThemeUtil.dp(c, 10), 0, ThemeUtil.dp(c, 4));
        box.addView(t);
        return t;
    }

    private static SeekBar bar(Context c, LinearLayout box) {
        SeekBar s = new SeekBar(c);
        s.setMax(30);
        box.addView(s, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeUtil.dp(c, 40)));
        return s;
    }

    /** Generic single choice dialog (sort orders, theme, …). */
    public interface Choice {
        void onChosen(int index);
    }

    public static void choose(Activity a, String title, String[] options, int selected, final Choice cb) {
        new AlertDialog.Builder(a)
                .setTitle(title)
                .setSingleChoiceItems(options, selected, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        cb.onChosen(which);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ lyrics

    public static void lyricsMenu(final BaseActivity a, final Song s, View anchor) {
        if (s == null) return;
        PopupMenu p = new PopupMenu(a, anchor);
        Menu m = p.getMenu();
        add(m, 1, R.string.lyrics_import, R.drawable.ic_file, a);
        add(m, 2, R.string.lyrics_edit, R.drawable.ic_edit, a);
        if (LyricsRepository.hasUserLyrics(a, s)) add(m, 3, R.string.lyrics_remove, R.drawable.ic_delete, a);
        forceIcons(p);
        p.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == 1) {
                    pendingLyricsSong = s;
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                            .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/*", "application/octet-stream", "application/x-subrip"});
                    try {
                        a.startActivityForResult(i, REQ_IMPORT_LYRICS);
                    } catch (Exception e) {
                        Toast.makeText(a, R.string.export_failed, Toast.LENGTH_SHORT).show();
                    }
                } else if (item.getItemId() == 2) {
                    editLyrics(a, s);
                } else if (item.getItemId() == 3) {
                    LyricsRepository.delete(a, s);
                    Toast.makeText(a, R.string.lyrics_removed, Toast.LENGTH_SHORT).show();
                    if (a instanceof LyricsHost) ((LyricsHost) a).onLyricsChanged();
                }
                return true;
            }
        });
        p.show();
    }

    private static void editLyrics(final BaseActivity a, final Song s) {
        final EditText e = new EditText(a);
        e.setHint(R.string.lyrics_paste_hint);
        e.setMinLines(6);
        e.setMaxLines(14);
        e.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final LyricsRepository.Lyrics l = LyricsRepository.load(a, s);
                if (l == null) return;
                final StringBuilder sb = new StringBuilder();
                if (l.isSynced()) {
                    for (com.demerdash.soundplayer.lyrics.LrcParser.Line line : l.lines) {
                        sb.append(com.demerdash.soundplayer.lyrics.LrcParser.tag(line.time)).append(line.text).append('\n');
                    }
                } else if (l.plain != null) {
                    sb.append(l.plain);
                }
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (e.getText().length() == 0) e.setText(sb.toString());
                    }
                });
            }
        }).start();
        new AlertDialog.Builder(a)
                .setTitle(R.string.lyrics_edit)
                .setView(padded(a, e))
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String text = e.getText().toString();
                        try {
                            if (text.trim().length() == 0) LyricsRepository.delete(a, s);
                            else LyricsRepository.save(a, s, text);
                            Toast.makeText(a, R.string.lyrics_saved, Toast.LENGTH_SHORT).show();
                        } catch (Exception ex) {
                            Toast.makeText(a, R.string.export_failed, Toast.LENGTH_SHORT).show();
                        }
                        if (a instanceof LyricsHost) ((LyricsHost) a).onLyricsChanged();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
