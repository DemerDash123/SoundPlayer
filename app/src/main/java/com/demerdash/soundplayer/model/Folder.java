package com.demerdash.soundplayer.model;

import java.util.ArrayList;
import java.util.List;

public final class Folder {
    public final String path;
    public final String name;
    public final List<Song> songs = new ArrayList<>();

    public Folder(String path, String name) {
        this.path = path;
        this.name = name;
    }
}
