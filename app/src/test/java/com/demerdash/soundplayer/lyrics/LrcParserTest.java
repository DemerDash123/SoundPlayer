package com.demerdash.soundplayer.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class LrcParserTest {

    @Test
    public void parsesTimestampsFormatsAndSorts() {
        String lrc = "[ti:Test]\n[ar:Someone]\n[00:12.34]Second\n[00:01.5]First\n[01:02.345][01:05]Chorus <00:01.00>word\n\n plain line";
        List<LrcParser.Line> l = LrcParser.parse(lrc);
        assertEquals(4, l.size());
        assertEquals(1500, l.get(0).time);
        assertEquals("First", l.get(0).text);
        assertEquals(12340, l.get(1).time);
        assertEquals(62345, l.get(2).time);
        assertEquals("Chorus word", l.get(2).text);
        assertEquals(65000, l.get(3).time);
    }

    @Test
    public void appliesOffset() {
        List<LrcParser.Line> l = LrcParser.parse("[offset:+500]\n[00:02.00]a");
        assertEquals(1500, l.get(0).time);
    }

    @Test
    public void plainTextIsNotSynced() {
        assertTrue(LrcParser.parse("just\nwords").isEmpty());
    }

    @Test
    public void indexAtAndTag() {
        List<LrcParser.Line> l = LrcParser.parse("[00:01.00]a\n[00:05.00]b\n[00:09.00]c");
        assertEquals(-1, LrcParser.indexAt(l, 500));
        assertEquals(0, LrcParser.indexAt(l, 1000));
        assertEquals(1, LrcParser.indexAt(l, 8999));
        assertEquals(2, LrcParser.indexAt(l, 100000));
        assertEquals("[01:02.34]", LrcParser.tag(62345));
    }

    @Test
    public void arabicLyrics() {
        List<LrcParser.Line> l = LrcParser.parse("[00:03.00]يا ليل");
        assertEquals("يا ليل", l.get(0).text);
    }
}
