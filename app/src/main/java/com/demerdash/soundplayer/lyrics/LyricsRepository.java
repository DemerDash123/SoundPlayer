package com.demerdash.soundplayer.lyrics;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.demerdash.soundplayer.model.Song;
import com.demerdash.soundplayer.util.Format;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Finds lyrics for a song, fully offline: lyrics the user saved in the app, lyrics embedded in the file's tags,
 * then a matching .lrc / .txt file next to the song (where storage permissions allow it).
 */
public final class LyricsRepository {

    private static final String TAG = "Lyrics";
    private static final int MAX_FILE = 512 * 1024;

    public static final class Lyrics {
        public final List<LrcParser.Line> lines;
        public final String plain;
        public final boolean userSaved;

        Lyrics(List<LrcParser.Line> lines, String plain, boolean userSaved) {
            this.lines = lines;
            this.plain = plain;
            this.userSaved = userSaved;
        }

        public boolean isSynced() {
            return !lines.isEmpty();
        }
    }

    private LyricsRepository() {
    }

    /** Blocking – call from a background thread. Returns null when no lyrics exist. */
    public static Lyrics load(Context c, Song s) {
        if (s == null) return null;
        String text = null;
        boolean user = false;
        File uf = userFile(c, s);
        if (uf.exists()) {
            text = readFile(uf);
            user = text != null;
        }
        if (text == null) {
            InputStream in = null;
            try {
                in = c.getContentResolver().openInputStream(s.uri);
                if (in != null) text = TagLyricsReader.read(in);
            } catch (Throwable t) {
                Log.d(TAG, "embedded lyrics: " + t);
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        if (text == null && s.path != null) {
            int dot = s.path.lastIndexOf('.');
            int slash = s.path.lastIndexOf('/');
            String base = dot > slash ? s.path.substring(0, dot) : s.path;
            String[] exts = {".lrc", ".LRC", ".Lrc", ".txt"};
            for (String e : exts) {
                File f = new File(base + e);
                try {
                    if (f.isFile() && f.canRead()) {
                        text = readFile(f);
                        if (text != null) break;
                    }
                } catch (SecurityException ignored) {
                }
            }
        }
        if (text == null || text.trim().length() == 0) return null;
        return parse(text, user);
    }

    public static Lyrics parse(String text, boolean user) {
        List<LrcParser.Line> lines = LrcParser.parse(text);
        if (!lines.isEmpty()) return new Lyrics(lines, null, user);
        return new Lyrics(Collections.<LrcParser.Line>emptyList(), text.trim(), user);
    }

    public static boolean hasUserLyrics(Context c, Song s) {
        return s != null && userFile(c, s).exists();
    }

    public static void save(Context c, Song s, String text) throws IOException {
        File f = userFile(c, s);
        File dir = f.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) throw new IOException("mkdirs");
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(text.getBytes(Charset.forName("UTF-8")));
        } finally {
            out.close();
        }
    }

    public static boolean delete(Context c, Song s) {
        return userFile(c, s).delete();
    }

    /** Reads an .lrc/.txt document picked by the user. */
    public static String readUri(Context c, Uri uri) throws IOException {
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("no stream");
        try {
            return decode(readAll(in));
        } finally {
            in.close();
        }
    }

    private static File userFile(Context c, Song s) {
        String key = s.id >= 0 ? "id" + s.id : "u" + Integer.toHexString(s.uri.toString().hashCode());
        return new File(new File(c.getFilesDir(), "lyrics"), key + ".lrc");
    }

    private static String readFile(File f) {
        if (f.length() > MAX_FILE) return null;
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            return decode(readAll(in));
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) {
            out.write(buf, 0, r);
            if (out.size() > MAX_FILE) throw new IOException("too large");
        }
        return out.toByteArray();
    }

    /** UTF-8 / UTF-16 (BOM) first; falls back to Windows-1256 for Arabic locales, else Windows-1252. */
    static String decode(byte[] b) {
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) return new String(b, 2, b.length - 2, Charset.forName("UTF-16LE"));
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) return new String(b, 2, b.length - 2, Charset.forName("UTF-16BE"));
        int start = (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) ? 3 : 0;
        try {
            return Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b, start, b.length - start)).toString();
        } catch (CharacterCodingException e) {
            String cs = "ar".equals(Locale.getDefault().getLanguage()) ? "windows-1256" : "windows-1252";
            try {
                return new String(b, cs);
            } catch (Exception e2) {
                return new String(b, Charset.forName("ISO-8859-1"));
            }
        }
    }

    /** "song.mp3" in a folder -> display helper */
    public static String baseName(Song s) {
        return Format.stripExtension(s.path);
    }
}
