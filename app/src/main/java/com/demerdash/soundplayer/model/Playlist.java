package com.demerdash.soundplayer.model;

import java.util.ArrayList;
import java.util.List;

/** User playlist stored in the app's private storage (never in MediaStore). */
public final class Playlist {
    public final long id;
    public String name;
    public final long created;
    public final List<Long> songIds = new ArrayList<>();

    public Playlist(long id, String name, long created) {
        this.id = id;
        this.name = name;
        this.created = created;
    }

    public Playlist copy() {
        Playlist p = new Playlist(id, name, created);
        p.songIds.addAll(songIds);
        return p;
    }
}
