package com.demerdash.soundplayer.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import org.junit.Test;

public class QueueManagerTest {

    private static List<String> songs(int n) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add("s" + i);
        return l;
    }

    @Test
    public void playsInOrderAndStopsAtEnd() {
        QueueManager<String> q = new QueueManager<>();
        q.setQueue(songs(3), 1, false);
        assertEquals("s1", q.current());
        assertTrue(q.moveToNext(false));
        assertEquals("s2", q.current());
        assertFalse(q.moveToNext(false));
        assertEquals("s2", q.current());
    }

    @Test
    public void repeatAllWrapsAndRepeatOneStays() {
        QueueManager<String> q = new QueueManager<>();
        q.setQueue(songs(2), 1, false);
        q.setRepeat(QueueManager.REPEAT_ALL);
        assertTrue(q.moveToNext(false));
        assertEquals("s0", q.current());
        q.setRepeat(QueueManager.REPEAT_ONE);
        assertEquals(0, q.peekNextIndex(false));
        assertEquals(1, q.peekNextIndex(true));
    }

    @Test
    public void shuffleKeepsCurrentFirstAndRestoresOrder() {
        QueueManager<String> q = new QueueManager<>(new Random(42));
        q.setQueue(songs(20), 7, false);
        q.setShuffle(true);
        assertEquals("s7", q.current());
        assertEquals(0, q.index());
        assertEquals(20, new HashSet<>(q.items()).size());
        q.moveToNext(false);
        String now = q.current();
        q.setShuffle(false);
        assertEquals(now, q.current());
        assertEquals(songs(20), q.items());
        assertEquals(Integer.parseInt(now.substring(1)), q.index());
    }

    @Test
    public void shuffleAllWithRandomStart() {
        QueueManager<String> q = new QueueManager<>(new Random(1));
        q.setQueue(songs(10), -1, true);
        assertTrue(q.isShuffle());
        assertEquals(0, q.index());
        assertEquals(10, q.size());
    }

    @Test
    public void playNextAndAddToEnd() {
        QueueManager<String> q = new QueueManager<>();
        q.setQueue(songs(3), 0, false);
        q.playNext(Arrays.asList("x"));
        q.addToEnd(Arrays.asList("y"));
        assertEquals(Arrays.asList("s0", "x", "s1", "s2", "y"), q.items());
        assertEquals("s0", q.current());
    }

    @Test
    public void playNextWhileShuffledSurvivesUnshuffle() {
        QueueManager<String> q = new QueueManager<>(new Random(3));
        q.setQueue(songs(5), 2, true);
        q.playNext(Arrays.asList("x"));
        assertEquals("x", q.get(1));
        q.setShuffle(false);
        assertEquals(Arrays.asList("s0", "s1", "s2", "x", "s3", "s4"), q.items());
        assertEquals("s2", q.current());
    }

    @Test
    public void removeAdjustsIndex() {
        QueueManager<String> q = new QueueManager<>();
        q.setQueue(songs(5), 2, false);
        assertFalse(q.removeAt(0));
        assertEquals("s2", q.current());
        assertEquals(1, q.index());
        assertTrue(q.removeAt(1));
        assertEquals("s3", q.current());
        q.removeAt(3); // last
        q.jumpTo(2);
        assertTrue(q.removeAt(2));
        assertEquals("s3", q.current());
        q.clear();
        assertNull(q.current());
    }

    @Test
    public void moveKeepsCurrentSong() {
        QueueManager<String> q = new QueueManager<>();
        q.setQueue(songs(5), 2, false);
        q.move(2, 4);
        assertEquals("s2", q.current());
        assertEquals(4, q.index());
        q.move(0, 4);
        assertEquals("s2", q.current());
        assertEquals(3, q.index());
        q.move(4, 0);
        assertEquals("s2", q.current());
        assertEquals(4, q.index());
    }

    @Test
    public void previousAtStart() {
        QueueManager<String> q = new QueueManager<>();
        q.setQueue(songs(3), 0, false);
        assertEquals(0, q.peekPrevIndex());
        q.setRepeat(QueueManager.REPEAT_ALL);
        assertEquals(2, q.peekPrevIndex());
    }

    @Test
    public void restoreAndWindow() {
        QueueManager<String> q = new QueueManager<>();
        q.restore(songs(4), null, 9, false, 1);
        assertEquals(3, q.index());
        assertEquals(QueueManager.REPEAT_ALL, q.repeatMode());
        assertEquals(Arrays.asList("s3"), q.window(10));
    }
}
