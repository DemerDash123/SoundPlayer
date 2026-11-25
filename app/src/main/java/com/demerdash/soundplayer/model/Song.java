package com.demerdash.soundplayer.model;

import android.net.Uri;

/** A playable track. Songs from MediaStore have id >= 0; external files opened via "Open with" use negative ids. */
public final class Song {
    public final long id;
    public final String title;
    public final String artist;
    public final String album;
    public final long albumId;
    public final long artistId;
    public final long duration;
    public final int track;
    public final int year;
    public final String path;
    public final String folderPath;
    public final String folderName;
    public final long size;
    public final long dateAdded;
    public final String mime;
    public final Uri uri;

    public Song(long id, String title, String artist, String album, long albumId, long artistId,
                long duration, int track, int year, String path, String folderPath, String folderName,
                long size, long dateAdded, String mime, Uri uri) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.albumId = albumId;
        this.artistId = artistId;
        this.duration = duration;
        this.track = track;
        this.year = year;
        this.path = path;
        this.folderPath = folderPath;
        this.folderName = folderName;
        this.size = size;
        this.dateAdded = dateAdded;
        this.mime = mime;
        this.uri = uri;
    }

    public boolean isExternal() {
        return id < 0;
    }

    /** Disc-aware track number: MediaStore stores disc * 1000 + track. */
    public int trackNumber() {
        return track % 1000;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Song)) return false;
        return id == ((Song) o).id;
    }

    @Override
    public int hashCode() {
        return (int) (id ^ (id >>> 32));
    }

    @Override
    public String toString() {
        return title;
    }
}
