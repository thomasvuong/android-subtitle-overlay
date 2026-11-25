package com.example.subtitles.view.overlay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Pure-Java rolling history of finalized subtitle sentences.
 */
public class SubtitleHistory {
    public static final int MAX_LINES = 300;

    private final LinkedList<String> items = new LinkedList<>();

    /** Appends a sentence; blank input is ignored; oldest lines are dropped past the cap. */
    public void append(String sentence) {
        if (sentence == null) return;
        String s = sentence.trim();
        if (s.isEmpty()) return;
        items.addLast(s);
        while (items.size() > MAX_LINES) items.removeFirst();
    }

    public void clear() {
        items.clear();
    }

    public List<String> lines() {
        return Collections.unmodifiableList(new ArrayList<>(items));
    }

    public String joined() {
        return String.join("\n", items);
    }

    public String last() {
        return items.isEmpty() ? "" : items.getLast();
    }

    /** Number of most recent history lines compared against the live text. */
    public static final int OVERLAP_LINES = 3;

    /** Single-line variant kept for compatibility; delegates to {@link #liveRemainderAgainst}. */
    public static String liveRemainder(String lastHistoryLine, String live) {
        if (lastHistoryLine == null) return live == null ? "" : live;
        return liveRemainderAgainst(Collections.singletonList(lastHistoryLine), live);
    }

    private static String normWord(String w) {
        return w.replaceAll("[.,!?\u2026;:]", "").toLowerCase();
    }

    /**
     * Removes from the start of {@code live} the longest run of words that is also a suffix of the
     * last {@link #OVERLAP_LINES} history lines joined with spaces (word-level, case-insensitive,
     * punctuation .,!?... ignored). Returns the remaining words (original spelling) joined by a space,
     * or {@code live} unchanged when nothing overlaps.
     */
    public static String liveRemainderAgainst(List<String> historyLines, String live) {
        if (live == null) return "";
        String trimmedLive = live.trim();
        if (historyLines == null || historyLines.isEmpty() || trimmedLive.isEmpty()) return live;
        int from = Math.max(0, historyLines.size() - OVERLAP_LINES);
        StringBuilder joined = new StringBuilder();
        for (int i = from; i < historyLines.size(); i++) {
            String line = historyLines.get(i);
            if (line == null || line.trim().isEmpty()) continue;
            if (joined.length() > 0) joined.append(' ');
            joined.append(line.trim());
        }
        if (joined.length() == 0) return live;
        String[] h = joined.toString().split("\\s+");
        String[] l = trimmedLive.split("\\s+");
        int max = Math.min(h.length, l.length);
        for (int n = max; n >= 1; n--) {
            boolean match = true;
            for (int i = 0; i < n; i++) {
                String a = normWord(h[h.length - n + i]);
                String b = normWord(l[i]);
                if (a.isEmpty() || !a.equals(b)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                StringBuilder sb = new StringBuilder();
                for (int i = n; i < l.length; i++) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(l[i]);
                }
                return sb.toString();
            }
        }
        return live;
    }
}
