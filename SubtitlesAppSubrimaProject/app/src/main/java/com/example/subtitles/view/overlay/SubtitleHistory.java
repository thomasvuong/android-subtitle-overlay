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

    /**
     * Removes from the start of {@code live} the longest run of words that is also
     * a suffix of {@code lastHistoryLine} (word-level, case-insensitive).
     */
    public static String liveRemainder(String lastHistoryLine, String live) {
        if (live == null) return "";
        if (lastHistoryLine == null || lastHistoryLine.trim().isEmpty()) return live;
        String[] h = lastHistoryLine.trim().split("\\s+");
        String trimmedLive = live.trim();
        if (trimmedLive.isEmpty()) return live;
        String[] l = trimmedLive.split("\\s+");
        int max = Math.min(h.length, l.length);
        for (int n = max; n >= 1; n--) {
            boolean match = true;
            for (int i = 0; i < n; i++) {
                if (!h[h.length - n + i].equalsIgnoreCase(l[i])) {
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
