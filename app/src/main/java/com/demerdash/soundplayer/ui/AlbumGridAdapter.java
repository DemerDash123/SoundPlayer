package com.demerdash.soundplayer.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.demerdash.soundplayer.R;
import com.demerdash.soundplayer.data.ArtLoader;
import com.demerdash.soundplayer.model.Album;

import java.util.ArrayList;
import java.util.List;

/** Album cover grid. */
public final class AlbumGridAdapter extends BaseAdapter {

    public interface Callback {
        void onAlbumClick(Album album);

        void onAlbumLongClick(Album album, View anchor);
    }

    private final LayoutInflater inflater;
    private final ArtLoader art;
    private final Callback callback;
    private final int px;
    private List<Album> albums = new ArrayList<>();

    public AlbumGridAdapter(Context c, Callback cb) {
        inflater = LayoutInflater.from(c);
        art = ArtLoader.get(c);
        callback = cb;
        px = ThemeUtil.dp(c, 180);
    }

    public void setAlbums(List<Album> a) {
        albums = a;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return albums.size();
    }

    @Override
    public Album getItem(int position) {
        return albums.get(position);
    }

    @Override
    public long getItemId(int position) {
        return albums.get(position).id;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView != null ? convertView : inflater.inflate(R.layout.item_album, parent, false);
        final Album a = albums.get(position);
        ImageView iv = v.findViewById(R.id.album_art);
        iv.setClipToOutline(true);
        art.bindAlbum(iv, a, px);
        ((TextView) v.findViewById(R.id.album_title)).setText(a.title);
        ((TextView) v.findViewById(R.id.album_artist)).setText(a.artist);
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (callback != null) callback.onAlbumClick(a);
            }
        });
        v.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                if (callback != null) callback.onAlbumLongClick(a, view);
                return true;
            }
        });
        return v;
    }
}
