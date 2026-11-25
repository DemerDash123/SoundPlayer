package com.demerdash.soundplayer.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

import org.junit.Test;

public class TagLyricsReaderTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    private static byte[] be32(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static byte[] le32(int v) {
        return new byte[]{(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)};
    }

    private static byte[] syncsafe(int v) {
        return new byte[]{(byte) ((v >> 21) & 0x7F), (byte) ((v >> 14) & 0x7F), (byte) ((v >> 7) & 0x7F), (byte) (v & 0x7F)};
    }

    private static byte[] id3(int major, byte[] frames) {
        return cat(new byte[]{'I', 'D', '3', (byte) major, 0, 0}, syncsafe(frames.length), frames, new byte[200]);
    }

    private static String read(byte[] data) throws Exception {
        return TagLyricsReader.read(new ByteArrayInputStream(data));
    }

    @Test
    public void id3v24Utf8Uslt() throws Exception {
        byte[] body = cat(new byte[]{3, 'e', 'n', 'g', 0}, "[00:01.00]Hello مرحبا".getBytes(UTF8));
        byte[] frame = cat("USLT".getBytes(UTF8), syncsafe(body.length), new byte[]{0, 0}, body);
        byte[] other = cat("TIT2".getBytes(UTF8), syncsafe(4), new byte[]{0, 0}, new byte[]{3, 'a', 'b', 'c'});
        assertEquals("[00:01.00]Hello مرحبا", read(id3(4, cat(other, frame))));
    }

    @Test
    public void id3v23Utf16WithBom() throws Exception {
        byte[] text = "Line one\nLine two".getBytes(Charset.forName("UTF-16LE"));
        byte[] body = cat(new byte[]{1, 'e', 'n', 'g', (byte) 0xFF, (byte) 0xFE, 0, 0, (byte) 0xFF, (byte) 0xFE}, text);
        byte[] frame = cat("USLT".getBytes(UTF8), be32(body.length), new byte[]{0, 0}, body);
        assertEquals("Line one\nLine two", read(id3(3, frame)));
    }

    @Test
    public void id3SyltMillisecondsBecomesLrc() throws Exception {
        byte[] body = cat(new byte[]{3, 'e', 'n', 'g', 2, 1, 0},
                "Hi".getBytes(UTF8), new byte[]{0}, be32(1500),
                "There".getBytes(UTF8), new byte[]{0}, be32(62000));
        byte[] frame = cat("SYLT".getBytes(UTF8), syncsafe(body.length), new byte[]{0, 0}, body);
        String r = read(id3(4, frame));
        assertEquals("[00:01.50]Hi\n[01:02.00]There\n", r);
    }

    @Test
    public void id3TxxxLyrics() throws Exception {
        byte[] body = cat(new byte[]{3}, "UNSYNCEDLYRICS".getBytes(UTF8), new byte[]{0}, "From TXXX".getBytes(UTF8));
        byte[] frame = cat("TXXX".getBytes(UTF8), be32(body.length), new byte[]{0, 0}, body);
        byte[] other = cat(new byte[]{3}, "MOOD".getBytes(UTF8), new byte[]{0}, "happy".getBytes(UTF8));
        byte[] otherFrame = cat("TXXX".getBytes(UTF8), be32(other.length), new byte[]{0, 0}, other);
        assertEquals("From TXXX", read(id3(3, cat(otherFrame, frame))));
    }

    @Test
    public void noLyricsReturnsNull() throws Exception {
        byte[] other = cat("TIT2".getBytes(UTF8), syncsafe(4), new byte[]{0, 0}, new byte[]{3, 'a', 'b', 'c'});
        assertNull(read(id3(4, other)));
        assertNull(read(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9}));
    }

    private static byte[] vorbisComments(String... comments) {
        byte[] vendor = "test".getBytes(UTF8);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] v = cat(le32(vendor.length), vendor, le32(comments.length));
        o.write(v, 0, v.length);
        for (String c : comments) {
            byte[] b = c.getBytes(UTF8);
            byte[] e = cat(le32(b.length), b);
            o.write(e, 0, e.length);
        }
        return o.toByteArray();
    }

    @Test
    public void flacVorbisComment() throws Exception {
        byte[] streaminfo = new byte[34];
        byte[] vc = vorbisComments("TITLE=x", "lyrics=Plain words");
        byte[] data = cat("fLaC".getBytes(UTF8),
                new byte[]{0, 0, 0, 34}, streaminfo,
                new byte[]{(byte) 0x84, (byte) (vc.length >> 16), (byte) (vc.length >> 8), (byte) vc.length}, vc);
        assertEquals("Plain words", read(data));
    }

    @Test
    public void oggVorbisCommentAcrossPages() throws Exception {
        byte[] id = cat(new byte[]{1}, "vorbis".getBytes(UTF8), new byte[23]);
        StringBuilder big = new StringBuilder("UNSYNCEDLYRICS=");
        for (int i = 0; i < 400; i++) big.append('a');
        byte[] comment = cat(new byte[]{3}, "vorbis".getBytes(UTF8), vorbisComments(big.toString()), new byte[]{1});
        byte[] page1 = oggPage(id);
        // split the comment packet over two pages: 255 bytes then the rest
        byte[] first = java.util.Arrays.copyOfRange(comment, 0, 255);
        byte[] rest = java.util.Arrays.copyOfRange(comment, 255, comment.length);
        byte[] page2 = cat(oggHeader(1), new byte[]{(byte) 255}, first);
        byte[] page3 = cat(oggHeader(1), new byte[]{(byte) rest.length}, rest);
        String r = read(cat(page1, page2, page3));
        assertEquals(400, r.length());
    }

    private static byte[] oggHeader(int segs) {
        byte[] h = new byte[26];
        h[0] = 'O';
        h[1] = 'g';
        h[2] = 'g';
        h[3] = 'S';
        return cat(h, new byte[]{(byte) segs});
    }

    private static byte[] oggPage(byte[] packet) {
        return cat(oggHeader(1), new byte[]{(byte) packet.length}, packet);
    }

    private static byte[] atom(String type, byte[]... body) {
        byte[] b = cat(body);
        return cat(be32(b.length + 8), type.getBytes(Charset.forName("ISO-8859-1")), b);
    }

    @Test
    public void mp4Lyrics() throws Exception {
        byte[] data = atom("data", new byte[]{0, 0, 0, 1, 0, 0, 0, 0}, "M4A lyrics".getBytes(UTF8));
        byte[] ilst = atom("ilst", atom("©nam", atom("data", new byte[8], "t".getBytes(UTF8))), atom("©lyr", data));
        byte[] meta = atom("meta", new byte[4], atom("hdlr", new byte[25]), ilst);
        byte[] file = cat(atom("ftyp", "M4A ".getBytes(UTF8), new byte[8]), atom("mdat", new byte[5000]),
                atom("moov", atom("mvhd", new byte[100]), atom("udta", meta)));
        assertEquals("M4A lyrics", read(file));
    }

    @Test
    public void unsyncHelper() {
        byte[] r = TagLyricsReader.unsync(new byte[]{(byte) 0xFF, 0, (byte) 0xE0, 5});
        assertTrue(java.util.Arrays.equals(new byte[]{(byte) 0xFF, (byte) 0xE0, 5}, r));
    }
}
