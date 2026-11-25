package com.demerdash.soundplayer.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class Artist {
    public final long id;
    public final String name;
    public final List<Song> songs = new ArrayList<>();
    public final Set<Long> albumIds = new HashSet<>();

    public Artist(long id, String name) {
        this.id = id;
        this.name = name;
    }

    public Song first() {
        return songs.isEmpty() ? null : songs.get(0);
    }
}
