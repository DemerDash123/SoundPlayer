package com.demerdash.soundplayer.lyrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parser for LRC synced lyrics ([mm:ss.xx]text), including multi-timestamp lines, [offset:] and enhanced word tags. */
public final class LrcParser {

    public static final class Line {
        public final long time;
        public final String text;

        public Line(long time, String text) {
            this.time = time;
            this.text = text;
        }
    }

    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD_TIME = Pattern.compile("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>");

    private LrcParser() {
    }

    /** Returns the timed lines sorted by time; empty when the text has no LRC timestamps. */
    public static List<Line> parse(String content) {
        final List<Line> out = new ArrayList<>();
        if (content == null || content.length() == 0) return out;
        long offset = 0;
        Matcher om = OFFSET.matcher(content);
        if (om.find()) {
            try {
                offset = Long.parseLong(om.group(1).replace("+", ""));
            } catch (NumberFormatException ignored) {
                offset = 0;
            }
        }
        String[] lines = content.split("\\r\\n|\\r|\\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.length() == 0) continue;
            Matcher m = TIME.matcher(line);
            int pos = 0;
            List<Long> times = new ArrayList<>();
            while (m.find() && m.start() == pos) {
                times.add(toMs(m));
                pos = m.end();
                while (pos < line.length() && line.charAt(pos) == ' ') pos++;
            }
            if (times.isEmpty()) continue;
            String text = WORD_TIME.matcher(line.substring(Math.min(pos, line.length()))).replaceAll("").trim();
            for (Long t : times) {
                out.add(new Line(Math.max(0, t - offset), text));
            }
        }
        Collections.sort(out, new Comparator<Line>() {
            @Override
            public int compare(Line a, Line b) {
                return a.time < b.time ? -1 : (a.time == b.time ? 0 : 1);
            }
        });
        return out;
    }

    private static long toMs(Matcher m) {
        long min = Long.parseLong(m.group(1));
        long sec = Long.parseLong(m.group(2));
        long frac = 0;
        String f = m.group(3);
        if (f != null) {
            long v = Long.parseLong(f);
            if (f.length() == 1) frac = v * 100;
            else if (f.length() == 2) frac = v * 10;
            else frac = v;
        }
        return min * 60000L + sec * 1000L + frac;
    }

    public static boolean isSynced(String content) {
        return !parse(content).isEmpty();
    }

    /** Index of the line that should be highlighted at {@code posMs}, or -1 before the first line. */
    public static int indexAt(List<Line> lines, long posMs) {
        int lo = 0, hi = lines.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).time <= posMs) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    /** Formats a millisecond time as an LRC tag: [01:02.34] */
    public static String tag(long ms) {
        if (ms < 0) ms = 0;
        long min = ms / 60000L;
        long sec = (ms / 1000L) % 60L;
        long cs = (ms % 1000L) / 10L;
        return "[" + (min < 10 ? "0" : "") + min + ":" + (sec < 10 ? "0" : "") + sec + "." + (cs < 10 ? "0" : "") + cs + "]";
    }
}
