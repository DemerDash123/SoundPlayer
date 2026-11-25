package com.demerdash.soundplayer.model;

import java.util.ArrayList;
import java.util.List;

public final class Album {
    public final long id;
    public final String title;
    public String artist;
    public long artistId;
    public int year;
    public final List<Song> songs = new ArrayList<>();

    public Album(long id, String title) {
        this.id = id;
        this.title = title;
    }

    public long totalDuration() {
        long d = 0;
        for (Song s : songs) d += s.duration;
        return d;
    }

    public Song first() {
        return songs.isEmpty() ? null : songs.get(0);
    }
}
