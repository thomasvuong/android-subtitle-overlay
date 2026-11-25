package com.example.subtitles.view.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class SubtitleHistoryTest {
    @Test
    public void remainderDropsOverlap() {
        assertEquals("anh ơi",
                SubtitleHistory.liveRemainder("Hôm nay trời đẹp quá", "trời đẹp quá anh ơi"));
    }

    @Test
    public void remainderEmptyWhenFullyOverlapping() {
        assertEquals("", SubtitleHistory.liveRemainder("Hôm nay trời đẹp quá", "trời đẹp quá"));
    }

    @Test
    public void remainderUnchangedWhenNoOverlap() {
        assertEquals("xin chào", SubtitleHistory.liveRemainder("Hôm nay trời đẹp quá", "xin chào"));
    }

    @Test
    public void remainderEmptyHistoryReturnsLive() {
        assertEquals("xin chào", SubtitleHistory.liveRemainder("", "xin chào"));
    }

    @Test
    public void capKeepsNewest300() {
        SubtitleHistory h = new SubtitleHistory();
        for (int i = 1; i <= 305; i++) h.append("câu " + i);
        assertEquals(300, h.lines().size());
        assertEquals("câu 6", h.lines().get(0));
        assertEquals("câu 305", h.lines().get(299));
    }

    @Test
    public void blankIgnored() {
        SubtitleHistory h = new SubtitleHistory();
        h.append(null);
        h.append("   ");
        h.append("");
        assertTrue(h.lines().isEmpty());
        h.append("a");
        h.append("b");
        assertEquals("a\nb", h.joined());
    }

    @Test
    public void overlapSpansTwoLines() {
        assertEquals("f", SubtitleHistory.liveRemainderAgainst(
                Arrays.asList("a b c", "d e"), "c d e f"));
    }

    @Test
    public void overlapFullGivesEmpty() {
        assertEquals("", SubtitleHistory.liveRemainderAgainst(
                Arrays.asList("a b c", "d e"), "b c, d e."));
    }

    @Test
    public void overlapNoneUnchanged() {
        assertEquals("x y", SubtitleHistory.liveRemainderAgainst(
                Arrays.asList("a b c", "d e"), "x y"));
    }

    @Test
    public void overlapEmptyHistoryReturnsLive() {
        assertEquals("x y", SubtitleHistory.liveRemainderAgainst(Collections.<String>emptyList(), "x y"));
    }

    @Test
    public void overlapIgnoresCaseAndPunctuation() {
        assertEquals("anh", SubtitleHistory.liveRemainderAgainst(
                Arrays.asList("Hôm nay trời đẹp."), "TRỜI ĐẸP anh"));
    }
}
