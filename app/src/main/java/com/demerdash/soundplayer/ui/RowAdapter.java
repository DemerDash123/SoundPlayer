package com.demerdash.soundplayer.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SectionIndexer;
import android.widget.TextView;

import com.demerdash.soundplayer.R;
import com.demerdash.soundplayer.data.ArtLoader;
import com.demerdash.soundplayer.model.Album;
import com.demerdash.soundplayer.model.Artist;
import com.demerdash.soundplayer.model.Song;

import java.util.ArrayList;
import java.util.List;

/** Adapter for every list in the app (songs, artists, folders, playlists, search results, headers). */
public final class RowAdapter extends BaseAdapter implements SectionIndexer {

    public interface Callback {
        void onRowClick(Row row, int position);

        void onRowMore(Row row, View anchor, int position);
    }

    private final Context ctx;
    private final LayoutInflater inflater;
    private final ArtLoader art;
    private final Callback callback;
    private final int thumbPx;
    private final int accent;
    private final int text;
    private List<Row> rows = new ArrayList<>();
    private long playingId = Long.MIN_VALUE;
    private boolean isPlaying;
    private boolean showMore = true;
    private String[] sections = new String[0];
    private int[] sectionPos = new int[0];
    private boolean sectionsEnabled;
    private SelectionController selection;
    private final int accentSoft;

    public RowAdapter(Context c, Callback cb) {
        ctx = c;
        inflater = LayoutInflater.from(c);
        art = ArtLoader.get(c);
        callback = cb;
        thumbPx = ThemeUtil.dp(c, 48);
        accent = ThemeUtil.color(c, R.attr.spAccent);
        text = ThemeUtil.color(c, R.attr.spText);
        accentSoft = ThemeUtil.color(c, R.attr.spAccentSoft);
    }

    void setSelection(SelectionController s) {
        selection = s;
    }

    public void setRows(List<Row> r, boolean withSections) {
        rows = r;
        sectionsEnabled = withSections;
        buildSections();
        notifyDataSetChanged();
    }

    public List<Row> rows() {
        return rows;
    }

    public void setShowMore(boolean v) {
        showMore = v;
    }

    /** Highlights the song that is currently playing. */
    public void setPlaying(long songId, boolean playing) {
        if (songId == playingId && playing == isPlaying) return;
        playingId = songId;
        isPlaying = playing;
        notifyDataSetChanged();
    }

    private void buildSections() {
        List<String> secs = new ArrayList<>();
        List<Integer> pos = new ArrayList<>();
        if (sectionsEnabled) {
            String last = null;
            for (int i = 0; i < rows.size(); i++) {
                String s = rows.get(i).section;
                if (s == null) continue;
                if (!s.equals(last)) {
                    secs.add(s);
                    pos.add(i);
                    last = s;
                }
            }
        }
        sections = secs.toArray(new String[0]);
        sectionPos = new int[pos.size()];
        for (int i = 0; i < pos.size(); i++) sectionPos[i] = pos.get(i);
    }

    @Override
    public int getCount() {
        return rows.size();
    }

    @Override
    public Row getItem(int position) {
        return rows.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).type == Row.HEADER ? 0 : 1;
    }

    @Override
    public boolean isEnabled(int position) {
        return rows.get(position).type != Row.HEADER;
    }

    @Override
    public View getView(final int position, View convertView, ViewGroup parent) {
        final Row row = rows.get(position);
        if (row.type == Row.HEADER) {
            TextView tv = (TextView) (convertView != null ? convertView : inflater.inflate(R.layout.item_header, parent, false));
            tv.setText(row.title);
            return tv;
        }
        View v = convertView;
        Holder h;
        if (v == null) {
            v = inflater.inflate(R.layout.item_row, parent, false);
            h = new Holder(v);
            v.setTag(h);
        } else {
            h = (Holder) v.getTag();
        }
        h.title.setText(row.title);
        h.subtitle.setText(row.subtitle);
        h.subtitle.setVisibility(row.subtitle == null || row.subtitle.length() == 0 ? View.GONE : View.VISIBLE);
        h.trailing.setText(row.trailing);
        h.trailing.setVisibility(row.trailing == null ? View.GONE : View.VISIBLE);
        h.icon.setVisibility(View.GONE);
        h.art.setVisibility(View.VISIBLE);
        h.art.setBackgroundResource(R.drawable.bg_art);
        h.art.setClipToOutline(true);
        boolean playingRow = false;
        switch (row.type) {
            case Row.SONG: {
                Song s = (Song) row.data;
                art.bindSong(h.art, s, thumbPx);
                playingRow = s.id == playingId;
                break;
            }
            case Row.ALBUM:
                art.bindAlbum(h.art, (Album) row.data, thumbPx);
                break;
            case Row.ARTIST: {
                Artist a = (Artist) row.data;
                h.art.setBackgroundResource(R.drawable.bg_circle);
                h.art.setClipToOutline(true);
                art.bindSong(h.art, a.first(), thumbPx);
                break;
            }
            default:
                // FOLDER, PLAYLIST, ACTION: tinted icon on a soft accent tile
                h.art.setImageDrawable(null);
                h.art.setBackgroundResource(row.type == Row.ACTION ? R.drawable.bg_circle_soft : R.drawable.bg_card_high);
                h.icon.setVisibility(View.VISIBLE);
                h.icon.setImageResource(row.icon != 0 ? row.icon : R.drawable.ic_music_note);
                h.icon.setImageTintList(ColorStateList.valueOf(accent));
                break;
        }
        h.title.setTextColor(playingRow ? accent : text);
        h.playing.setVisibility(playingRow ? View.VISIBLE : View.GONE);
        h.playing.setAnimating(playingRow && isPlaying);
        final boolean selecting = selection != null && selection.isActive();
        final boolean isSong = row.type == Row.SONG;
        boolean checked = selecting && isSong && selection.isSelected((Song) row.data);
        h.check.setVisibility(checked ? View.VISIBLE : View.GONE);
        if (checked) v.setBackgroundColor(accentSoft);
        else v.setBackgroundResource(h.ripple);
        v.setAlpha(selecting && !isSong ? 0.4f : 1f);
        boolean more = showMore && row.type != Row.ACTION && !selecting;
        h.more.setVisibility(more ? View.VISIBLE : View.GONE);
        h.more.setOnClickListener(more ? new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (callback != null) callback.onRowMore(row, view, position);
            }
        } : null);
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (selection != null && selection.isActive()) {
                    if (isSong) selection.toggle((Song) row.data);
                    return;
                }
                if (callback != null) callback.onRowClick(row, position);
            }
        });
        final boolean canLong = isSong && selection != null || (showMore && row.type != Row.ACTION);
        v.setOnLongClickListener(canLong ? new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                if (isSong && selection != null) {
                    if (selection.isActive()) selection.toggle((Song) row.data);
                    else selection.start((Song) row.data);
                    return true;
                }
                if (selection != null && selection.isActive()) return true;
                if (callback != null) callback.onRowMore(row, h(view).more, position);
                return true;
            }
        } : null);
        return v;
    }

    private static Holder h(View v) {
        return (Holder) v.getTag();
    }

    private static final class Holder {
        final ImageView art;
        final ImageView icon;
        final TextView title;
        final TextView subtitle;
        final TextView trailing;
        final ImageButton more;
        final PlayingBarsView playing;
        final ImageView check;
        final int ripple;

        Holder(View v) {
            check = v.findViewById(R.id.row_check);
            android.util.TypedValue tv = new android.util.TypedValue();
            v.getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            ripple = tv.resourceId;
            art = v.findViewById(R.id.row_art);
            icon = v.findViewById(R.id.row_icon);
            title = v.findViewById(R.id.row_title);
            subtitle = v.findViewById(R.id.row_subtitle);
            trailing = v.findViewById(R.id.row_trailing);
            more = v.findViewById(R.id.row_more);
            playing = v.findViewById(R.id.row_playing);
        }
    }

    // ------------------------------------------------------------------ fast scroll

    @Override
    public Object[] getSections() {
        return sections;
    }

    @Override
    public int getPositionForSection(int sectionIndex) {
        if (sectionPos.length == 0) return 0;
        if (sectionIndex < 0) sectionIndex = 0;
        if (sectionIndex >= sectionPos.length) sectionIndex = sectionPos.length - 1;
        return sectionPos[sectionIndex];
    }

    @Override
    public int getSectionForPosition(int position) {
        int s = 0;
        for (int i = 0; i < sectionPos.length; i++) {
            if (sectionPos[i] <= position) s = i;
            else break;
        }
        return s;
    }
}
