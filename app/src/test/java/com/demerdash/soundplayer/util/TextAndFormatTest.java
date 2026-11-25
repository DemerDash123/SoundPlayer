package com.demerdash.soundplayer.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TextAndFormatTest {

    @Test
    public void latinAccentsAndCase() {
        assertTrue(TextNorm.matches(TextNorm.normalize("Beyoncé – Halo"), TextNorm.normalize("beyonce halo")));
        assertFalse(TextNorm.matches(TextNorm.normalize("Adele"), TextNorm.normalize("halo")));
    }

    @Test
    public void arabicVariantsMatch() {
        // "أحمد" with hamza should match "احمد"; "مدرسة" matches "مدرسه"; harakat ignored
        assertEquals(TextNorm.normalize("احمد"), TextNorm.normalize("أحمد"));
        assertEquals(TextNorm.normalize("مدرسه"), TextNorm.normalize("مدرسة"));
        assertEquals(TextNorm.normalize("محمد"), TextNorm.normalize("مُحَمَّد"));
        assertEquals(TextNorm.normalize("علي"), TextNorm.normalize("على"));
    }

    @Test
    public void sectionLetters() {
        assertEquals("A", TextNorm.sectionLetter("abc"));
        assertEquals("E", TextNorm.sectionLetter("Élan"));
        assertEquals("#", TextNorm.sectionLetter("123"));
        assertEquals("ا", TextNorm.sectionLetter("أغنية"));
    }

    @Test
    public void durations() {
        assertEquals("0:00", Format.duration(-5));
        assertEquals("3:05", Format.duration(185000));
        assertEquals("1:02:03", Format.duration(3723000));
        assertEquals("4.0 MB", Format.fileSize(4L * 1024 * 1024));
        assertEquals("song", Format.stripExtension("/a/b/song.mp3"));
    }
}
