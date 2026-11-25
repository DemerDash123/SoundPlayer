package com.demerdash.soundplayer.playback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Pure-Java play queue with shuffle and repeat. Kept free of Android classes so it is unit tested on the JVM.
 * <p>
 * {@code items} is always the actual play order. While shuffled, {@code original} remembers the order the
 * user picked so that turning shuffle off restores it.
 */
public final class QueueManager<T> {

    public static final int REPEAT_OFF = 0;
    public static final int REPEAT_ALL = 1;
    public static final int REPEAT_ONE = 2;

    private final ArrayList<T> items = new ArrayList<>();
    private ArrayList<T> original;
    private int index = -1;
    private boolean shuffle;
    private int repeat = REPEAT_OFF;
    private final Random random;

    public QueueManager() {
        this(new Random());
    }

    public QueueManager(Random random) {
        this.random = random;
    }

    /**
     * Replaces the queue.
     *
     * @param startIndex song to start with; a negative value means "random" when shuffling, otherwise 0
     */
    public void setQueue(List<T> list, int startIndex, boolean shuffleOn) {
        items.clear();
        original = null;
        index = -1;
        shuffle = false;
        if (list == null || list.isEmpty()) {
            shuffle = shuffleOn;
            return;
        }
        items.addAll(list);
        if (startIndex < 0) {
            startIndex = shuffleOn ? random.nextInt(items.size()) : 0;
        }
        index = Math.min(startIndex, items.size() - 1);
        if (shuffleOn) setShuffle(true);
    }

    /** Restores a saved queue exactly as it was. */
    public void restore(List<T> playOrder, List<T> originalOrder, int savedIndex, boolean shuffled, int repeatMode) {
        items.clear();
        items.addAll(playOrder);
        original = (shuffled && originalOrder != null) ? new ArrayList<>(originalOrder) : null;
        shuffle = shuffled && original != null;
        repeat = (repeatMode >= REPEAT_OFF && repeatMode <= REPEAT_ONE) ? repeatMode : REPEAT_OFF;
        if (items.isEmpty()) {
            index = -1;
        } else {
            index = Math.max(0, Math.min(savedIndex, items.size() - 1));
        }
    }

    public void clear() {
        items.clear();
        original = null;
        index = -1;
    }

    public T current() {
        return (index >= 0 && index < items.size()) ? items.get(index) : null;
    }

    public T get(int i) {
        return (i >= 0 && i < items.size()) ? items.get(i) : null;
    }

    public int index() {
        return index;
    }

    public int size() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** Snapshot of the play order. */
    public List<T> items() {
        return new ArrayList<>(items);
    }

    /** Snapshot of the un-shuffled order (null when not shuffled). */
    public List<T> originalItems() {
        return original == null ? null : new ArrayList<>(original);
    }

    public boolean isShuffle() {
        return shuffle;
    }

    public int repeatMode() {
        return repeat;
    }

    public void setRepeat(int mode) {
        if (mode < REPEAT_OFF || mode > REPEAT_ONE) mode = REPEAT_OFF;
        repeat = mode;
    }

    public int cycleRepeat() {
        repeat = repeat == REPEAT_OFF ? REPEAT_ALL : (repeat == REPEAT_ALL ? REPEAT_ONE : REPEAT_OFF);
        return repeat;
    }

    public void setShuffle(boolean on) {
        if (on == shuffle && (on == (original != null))) return;
        T cur = current();
        if (on) {
            original = new ArrayList<>(items);
            ArrayList<T> rest = new ArrayList<>(items);
            if (index >= 0 && index < rest.size()) rest.remove(index);
            Collections.shuffle(rest, random);
            items.clear();
            if (cur != null) items.add(cur);
            items.addAll(rest);
            index = items.isEmpty() ? -1 : 0;
        } else {
            if (original != null) {
                items.clear();
                items.addAll(original);
            }
            original = null;
            index = cur == null ? (items.isEmpty() ? -1 : 0) : Math.max(0, items.indexOf(cur));
        }
        shuffle = on;
    }

    /** Next index, or -1 when playback should stop. {@code user} = the user pressed "next". */
    public int peekNextIndex(boolean user) {
        if (items.isEmpty()) return -1;
        if (repeat == REPEAT_ONE && !user) return index;
        if (index + 1 < items.size()) return index + 1;
        if (repeat != REPEAT_OFF) return 0;
        return -1;
    }

    public int peekPrevIndex() {
        if (items.isEmpty()) return -1;
        if (index - 1 >= 0) return index - 1;
        if (repeat != REPEAT_OFF) return items.size() - 1;
        return 0;
    }

    public boolean moveToNext(boolean user) {
        int n = peekNextIndex(user);
        if (n < 0) return false;
        index = n;
        return true;
    }

    public boolean moveToPrev() {
        int p = peekPrevIndex();
        if (p < 0) return false;
        index = p;
        return true;
    }

    public boolean jumpTo(int i) {
        if (i < 0 || i >= items.size()) return false;
        index = i;
        return true;
    }

    /** Inserts songs right after the current one. */
    public void playNext(List<T> songs) {
        if (songs == null || songs.isEmpty()) return;
        if (items.isEmpty()) {
            setQueue(songs, 0, false);
            return;
        }
        items.addAll(index + 1, songs);
        if (original != null) {
            T cur = current();
            int oi = cur == null ? -1 : original.indexOf(cur);
            original.addAll(oi + 1, songs);
        }
    }

    /** Appends songs at the end of the queue. */
    public void addToEnd(List<T> songs) {
        if (songs == null || songs.isEmpty()) return;
        boolean wasEmpty = items.isEmpty();
        items.addAll(songs);
        if (original != null) original.addAll(songs);
        if (wasEmpty) index = 0;
    }

    /**
     * Removes the item at {@code i}.
     *
     * @return true when the current song was removed (the caller should start the new current song)
     */
    public boolean removeAt(int i) {
        if (i < 0 || i >= items.size()) return false;
        T removed = items.remove(i);
        if (original != null) original.remove((Object) removed);
        if (items.isEmpty()) {
            index = -1;
            return true;
        }
        if (i < index) {
            index--;
            return false;
        }
        if (i == index) {
            if (index >= items.size()) index = repeat == REPEAT_ALL ? 0 : items.size() - 1;
            return true;
        }
        return false;
    }

    /** Moves an item in the play order (drag & drop). */
    public void move(int from, int to) {
        if (from < 0 || from >= items.size() || to < 0 || to >= items.size() || from == to) return;
        T it = items.remove(from);
        items.add(to, it);
        if (from == index) {
            index = to;
        } else if (from < index && to >= index) {
            index--;
        } else if (from > index && to <= index) {
            index++;
        }
    }

    /** Up to {@code max} items starting at the current one (for the system media queue). */
    public List<T> window(int max) {
        ArrayList<T> out = new ArrayList<>();
        if (index < 0) return out;
        for (int i = index; i < items.size() && out.size() < max; i++) out.add(items.get(i));
        return out;
    }
}
