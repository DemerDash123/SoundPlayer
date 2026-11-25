package com.demerdash.soundplayer.lyrics;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Locale;

/**
 * Reads embedded lyrics straight from audio file bytes – no Android APIs, so it is unit tested on the JVM.
 * Supports ID3v2.2/2.3/2.4 (USLT + SYLT), FLAC and Ogg Vorbis/Opus comments (LYRICS / UNSYNCEDLYRICS)
 * and MP4/M4A (©lyr).
 */
public final class TagLyricsReader {

    private static final int MAX_BLOCK = 24 * 1024 * 1024;
    private static final Charset ISO = Charset.forName("ISO-8859-1");
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset UTF16 = Charset.forName("UTF-16");
    private static final Charset UTF16BE = Charset.forName("UTF-16BE");
    private static final Charset UTF16LE = Charset.forName("UTF-16LE");

    private TagLyricsReader() {
    }

    /** Returns embedded lyrics (plain or LRC text) or null. The stream is not closed. */
    public static String read(InputStream raw) throws IOException {
        BufferedInputStream in = raw instanceof BufferedInputStream ? (BufferedInputStream) raw
                : new BufferedInputStream(raw, 64 * 1024);
        byte[] head = new byte[12];
        in.mark(16);
        int n = readFully(in, head, 0, 12);
        in.reset();
        if (n < 4) return null;
        if (head[0] == 'I' && head[1] == 'D' && head[2] == '3') return readId3(in);
        if (head[0] == 'f' && head[1] == 'L' && head[2] == 'a' && head[3] == 'C') return readFlac(in);
        if (head[0] == 'O' && head[1] == 'g' && head[2] == 'g' && head[3] == 'S') return readOgg(in);
        if (n >= 8 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
            CountingInputStream cin = new CountingInputStream(in);
            return mp4Walk(cin, Long.MAX_VALUE, 0);
        }
        return null;
    }

    // ------------------------------------------------------------------ ID3v2

    static String readId3(InputStream in) throws IOException {
        byte[] header = new byte[10];
        if (readFully(in, header, 0, 10) < 10) return null;
        int major = header[3] & 0xFF;
        int flags = header[5] & 0xFF;
        int size = syncsafe(header, 6);
        if (size <= 0 || size > MAX_BLOCK || major < 2 || major > 4) return null;
        byte[] tag = new byte[size];
        int got = readFully(in, tag, 0, size);
        if (got < size) tag = Arrays.copyOf(tag, got);
        if ((flags & 0x80) != 0 && major < 4) tag = unsync(tag);
        int pos = 0;
        if ((flags & 0x40) != 0 && major >= 3 && tag.length >= 4) {
            pos = major == 3 ? 4 + int32(tag, 0) : syncsafe(tag, 0);
        }
        String uslt = null;
        String sylt = null;
        int hdr = major == 2 ? 6 : 10;
        while (pos >= 0 && pos + hdr <= tag.length) {
            if (tag[pos] == 0) break;
            String id;
            int fsize;
            int fflags = 0;
            if (major == 2) {
                id = new String(tag, pos, 3, ISO);
                fsize = ((tag[pos + 3] & 0xFF) << 16) | ((tag[pos + 4] & 0xFF) << 8) | (tag[pos + 5] & 0xFF);
            } else {
                id = new String(tag, pos, 4, ISO);
                fsize = major == 4 ? syncsafe(tag, pos + 4) : int32(tag, pos + 4);
                fflags = ((tag[pos + 8] & 0xFF) << 8) | (tag[pos + 9] & 0xFF);
            }
            int start = pos + hdr;
            if (fsize <= 0 || start + fsize > tag.length) break;
            boolean isUslt = id.equals("USLT") || id.equals("ULT");
            boolean isSylt = id.equals("SYLT") || id.equals("SLT");
            boolean isTxxx = id.equals("TXXX") || id.equals("TXX");
            if (isUslt || isSylt || isTxxx) {
                byte[] data = Arrays.copyOfRange(tag, start, start + fsize);
                boolean skip = false;
                if (major == 4) {
                    if ((fflags & 0x000C) != 0) skip = true;
                    int off = 0;
                    if ((fflags & 0x0040) != 0) off += 1;
                    if ((fflags & 0x0001) != 0) off += 4;
                    if (off > 0 && off < data.length) data = Arrays.copyOfRange(data, off, data.length);
                    if ((fflags & 0x0002) != 0) data = unsync(data);
                } else if (major == 3) {
                    if ((fflags & 0x00C0) != 0) skip = true;
                    if ((fflags & 0x0020) != 0 && data.length > 1) data = Arrays.copyOfRange(data, 1, data.length);
                }
                if (!skip) {
                    if (isTxxx) {
                        String t = parseTxxxLyrics(data);
                        if (t != null && t.trim().length() > 0 && uslt == null) uslt = t;
                    } else if (isUslt) {
                        String t = parseUslt(data);
                        if (t != null && t.trim().length() > 0 && uslt == null) uslt = t;
                    } else {
                        String t = parseSylt(data);
                        if (t != null && sylt == null) sylt = t;
                    }
                }
            }
            pos = start + fsize;
        }
        if (sylt != null && (uslt == null || !LrcParser.isSynced(uslt))) return sylt;
        return uslt;
    }

    static String parseUslt(byte[] d) {
        if (d.length < 5) return null;
        int enc = d[0] & 0xFF;
        int p = 4; // encoding + 3 byte language
        int term = findTerminator(d, p, enc);
        if (term < 0) return null;
        p = term + terminatorLength(enc);
        if (p > d.length) return null;
        return clean(decode(d, p, d.length, enc));
    }

    /** TXXX user frames named LYRICS / UNSYNCEDLYRICS / USLT (written by ffmpeg, foobar2000, some taggers). */
    static String parseTxxxLyrics(byte[] d) {
        if (d.length < 3) return null;
        int enc = d[0] & 0xFF;
        int term = findTerminator(d, 1, enc);
        if (term < 0) return null;
        String desc = clean(decode(d, 1, term, enc));
        if (desc == null) return null;
        String k = desc.trim().toUpperCase(Locale.ROOT);
        if (!(k.equals("LYRICS") || k.equals("UNSYNCEDLYRICS") || k.equals("UNSYNCED LYRICS") || k.equals("USLT")
                || k.equals("SYNCEDLYRICS") || k.equals("LYRICS_SYNCED"))) return null;
        int p = term + terminatorLength(enc);
        if (p > d.length) return null;
        return clean(decode(d, p, d.length, enc));
    }

    static String parseSylt(byte[] d) {
        if (d.length < 7) return null;
        int enc = d[0] & 0xFF;
        int format = d[4] & 0xFF;
        if (format != 2) return null; // only millisecond timestamps
        int p = 6;
        int term = findTerminator(d, p, enc);
        if (term < 0) return null;
        p = term + terminatorLength(enc);
        StringBuilder sb = new StringBuilder();
        int lines = 0;
        while (p < d.length) {
            int t = findTerminator(d, p, enc);
            if (t < 0 || t + terminatorLength(enc) + 4 > d.length) break;
            String text = clean(decode(d, p, t, enc));
            p = t + terminatorLength(enc);
            long ms = ((long) (d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16) | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
            p += 4;
            sb.append(LrcParser.tag(ms)).append(text == null ? "" : text.replace('\n', ' ').trim()).append('\n');
            lines++;
        }
        return lines > 0 ? sb.toString() : null;
    }

    private static int terminatorLength(int enc) {
        return (enc == 1 || enc == 2) ? 2 : 1;
    }

    private static int findTerminator(byte[] d, int from, int enc) {
        if (enc == 1 || enc == 2) {
            for (int i = from; i + 1 < d.length; i += 2) {
                if (d[i] == 0 && d[i + 1] == 0) return i;
            }
            return -1;
        }
        for (int i = from; i < d.length; i++) {
            if (d[i] == 0) return i;
        }
        return -1;
    }

    private static String decode(byte[] d, int from, int to, int enc) {
        if (to <= from) return "";
        switch (enc) {
            case 1: {
                if (to - from >= 2) {
                    int b0 = d[from] & 0xFF, b1 = d[from + 1] & 0xFF;
                    if (b0 == 0xFF && b1 == 0xFE) return new String(d, from + 2, to - from - 2, UTF16LE);
                    if (b0 == 0xFE && b1 == 0xFF) return new String(d, from + 2, to - from - 2, UTF16BE);
                }
                return new String(d, from, to - from, UTF16);
            }
            case 2:
                return new String(d, from, to - from, UTF16BE);
            case 3:
                return new String(d, from, to - from, UTF8);
            default:
                return new String(d, from, to - from, ISO);
        }
    }

    private static String clean(String s) {
        if (s == null) return null;
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\u0000' || s.charAt(end - 1) == '﻿')) end--;
        int start = 0;
        while (start < end && s.charAt(start) == '﻿') start++;
        return s.substring(start, end).replace("\r\n", "\n").replace('\r', '\n');
    }

    static byte[] unsync(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        for (int i = 0; i < in.length; i++) {
            out.write(in[i]);
            if ((in[i] & 0xFF) == 0xFF && i + 1 < in.length && in[i + 1] == 0) i++;
        }
        return out.toByteArray();
    }

    static int syncsafe(byte[] b, int o) {
        return ((b[o] & 0x7F) << 21) | ((b[o + 1] & 0x7F) << 14) | ((b[o + 2] & 0x7F) << 7) | (b[o + 3] & 0x7F);
    }

    static int int32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    // ------------------------------------------------------------------ FLAC / Vorbis comments

    static String readFlac(InputStream in) throws IOException {
        byte[] magic = new byte[4];
        if (readFully(in, magic, 0, 4) < 4) return null;
        for (int blocks = 0; blocks < 128; blocks++) {
            byte[] h = new byte[4];
            if (readFully(in, h, 0, 4) < 4) return null;
            boolean last = (h[0] & 0x80) != 0;
            int type = h[0] & 0x7F;
            int len = ((h[1] & 0xFF) << 16) | ((h[2] & 0xFF) << 8) | (h[3] & 0xFF);
            if (type == 4) {
                if (len > MAX_BLOCK) return null;
                byte[] b = new byte[len];
                if (readFully(in, b, 0, len) < len) return null;
                return vorbisLyrics(b, 0);
            }
            skipFully(in, len);
            if (last) return null;
        }
        return null;
    }

    static String readOgg(InputStream in) throws IOException {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        int packetIndex = 0;
        for (int pages = 0; pages < 512; pages++) {
            byte[] h = new byte[27];
            if (readFully(in, h, 0, 27) < 27) return null;
            if (h[0] != 'O' || h[1] != 'g' || h[2] != 'g' || h[3] != 'S') return null;
            int segs = h[26] & 0xFF;
            byte[] table = new byte[segs];
            if (readFully(in, table, 0, segs) < segs) return null;
            for (int s = 0; s < segs; s++) {
                int lace = table[s] & 0xFF;
                byte[] seg = new byte[lace];
                if (readFully(in, seg, 0, lace) < lace) return null;
                if (packetIndex == 1) {
                    packet.write(seg, 0, lace);
                    if (packet.size() > MAX_BLOCK) return null;
                }
                if (lace < 255) {
                    if (packetIndex == 1) return oggComment(packet.toByteArray());
                    packetIndex++;
                }
            }
        }
        return null;
    }

    private static String oggComment(byte[] p) {
        if (p.length > 7 && p[0] == 3 && p[1] == 'v' && p[2] == 'o' && p[3] == 'r' && p[4] == 'b' && p[5] == 'i' && p[6] == 's') {
            return vorbisLyrics(p, 7);
        }
        if (p.length > 8 && new String(p, 0, 8, ISO).equals("OpusTags")) {
            return vorbisLyrics(p, 8);
        }
        return null;
    }

    static String vorbisLyrics(byte[] b, int off) {
        if (off + 8 > b.length) return null;
        int vendorLen = le32(b, off);
        int p = off + 4 + vendorLen;
        if (vendorLen < 0 || p + 4 > b.length) return null;
        int count = le32(b, p);
        p += 4;
        String plain = null;
        String synced = null;
        for (int i = 0; i < count && p + 4 <= b.length; i++) {
            int len = le32(b, p);
            p += 4;
            if (len < 0 || p + len > b.length) break;
            String c = new String(b, p, len, UTF8);
            p += len;
            int eq = c.indexOf('=');
            if (eq <= 0) continue;
            String key = c.substring(0, eq).toUpperCase(Locale.ROOT);
            String val = c.substring(eq + 1);
            if (val.trim().length() == 0) continue;
            if (key.equals("LYRICS") || key.equals("UNSYNCEDLYRICS") || key.equals("UNSYNCED LYRICS")
                    || key.equals("SYNCEDLYRICS") || key.equals("LYRICS_SYNCED")) {
                if (LrcParser.isSynced(val)) {
                    if (synced == null) synced = val;
                } else if (plain == null) {
                    plain = val;
                }
            }
        }
        return synced != null ? synced : plain;
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    // ------------------------------------------------------------------ MP4 / M4A

    static String mp4Walk(CountingInputStream in, long limit, int depth) throws IOException {
        if (depth > 6) return null;
        long begin = in.count;
        while (in.count - begin + 8 <= limit) {
            long atomStart = in.count;
            byte[] h = new byte[8];
            if (readFully(in, h, 0, 8) < 8) return null;
            long size = ((long) (h[0] & 0xFF) << 24) | ((h[1] & 0xFF) << 16) | ((h[2] & 0xFF) << 8) | (h[3] & 0xFF);
            String type = new String(h, 4, 4, ISO);
            if (size == 1) {
                byte[] ext = new byte[8];
                if (readFully(in, ext, 0, 8) < 8) return null;
                size = 0;
                for (int i = 0; i < 8; i++) size = (size << 8) | (ext[i] & 0xFF);
            } else if (size == 0) {
                if (limit == Long.MAX_VALUE) size = Long.MAX_VALUE / 2;
                else size = limit - (atomStart - begin);
            }
            long headerLen = in.count - atomStart;
            long body = size - headerLen;
            if (body < 0) return null;
            long atomEnd = atomStart + size;
            if (type.equals("moov") || type.equals("udta") || type.equals("ilst")) {
                String r = mp4Walk(in, body, depth + 1);
                if (r != null) return r;
            } else if (type.equals("meta")) {
                byte[] vf = new byte[4];
                if (readFully(in, vf, 0, 4) < 4) return null;
                String r = mp4Walk(in, body - 4, depth + 1);
                if (r != null) return r;
            } else if (type.equals("©lyr")) {
                if (body > MAX_BLOCK) return null;
                byte[] b = new byte[(int) body];
                if (readFully(in, b, 0, b.length) < b.length) return null;
                if (b.length >= 16 && b[4] == 'd' && b[5] == 'a' && b[6] == 't' && b[7] == 'a') {
                    int dsize = (int) Math.min(b.length, (((long) (b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF)));
                    if (dsize > 16) return clean(new String(b, 16, dsize - 16, UTF8));
                }
                return null;
            }
            long remaining = atomEnd - in.count;
            if (remaining > 0) {
                if (skipFully(in, remaining) < remaining) return null;
            }
        }
        return null;
    }

    static final class CountingInputStream extends FilterInputStream {
        long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int r = super.read();
            if (r >= 0) count++;
            return r;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int r = super.read(b, off, len);
            if (r > 0) count += r;
            return r;
        }

        @Override
        public long skip(long n) throws IOException {
            long r = super.skip(n);
            if (r > 0) count += r;
            return r;
        }

        @Override
        public boolean markSupported() {
            return false;
        }
    }

    // ------------------------------------------------------------------ IO helpers

    static int readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int r = in.read(b, off + total, len - total);
            if (r < 0) break;
            total += r;
        }
        return total;
    }

    static long skipFully(InputStream in, long n) throws IOException {
        long total = 0;
        while (total < n) {
            long s = in.skip(n - total);
            if (s <= 0) {
                if (in.read() < 0) break;
                s = 1;
            }
            total += s;
        }
        return total;
    }
}
