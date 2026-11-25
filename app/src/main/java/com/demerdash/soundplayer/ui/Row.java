package com.demerdash.soundplayer.ui;

/** One line in a list: a song, album, artist, folder, playlist, section header or action. */
public final class Row {
    public static final int HEADER = 0;
    public static final int SONG = 1;
    public static final int ALBUM = 2;
    public static final int ARTIST = 3;
    public static final int FOLDER = 4;
    public static final int PLAYLIST = 5;
    public static final int ACTION = 6;

    public final int type;
    public final Object data;
    public String title;
    public String subtitle;
    public String trailing;
    public int icon;
    public long id;
    /** For songs: index inside the list of songs that will be played when the row is tapped. */
    public int songIndex = -1;
    public String section;

    public Row(int type, Object data) {
        this.type = type;
        this.data = data;
    }

    public static Row header(String title) {
        Row r = new Row(HEADER, null);
        r.title = title;
        return r;
    }

    public static Row action(long id, int icon, String title, String subtitle) {
        Row r = new Row(ACTION, null);
        r.id = id;
        r.icon = icon;
        r.title = title;
        r.subtitle = subtitle;
        return r;
    }
}
