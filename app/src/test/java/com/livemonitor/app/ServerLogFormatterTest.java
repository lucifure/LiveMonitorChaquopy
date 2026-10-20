package com.livemonitor.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.TimeZone;

import org.junit.Test;

public class ServerLogFormatterTest {
    @Test public void convertsServerOffsetToTargetAndLeavesUntimestampedLinesAlone() {
        TimeZone utc = TimeZone.getTimeZone("UTC");
        assertEquals("[2026-10-20 00:00:28] message", ServerLogFormatter.convertTime("[2026-10-20 05:30:28] message", 19800, utc));
        assertEquals("[2026-10-20 05:10:28] message", ServerLogFormatter.convertTime("[2026-10-20 05:10:28] message", 0, utc));
        assertEquals("Server log cleared from the app.", ServerLogFormatter.convertTime("Server log cleared from the app.", 19800, utc));
    }

    @Test public void levelsUseServerValueThenOrderedKeywordFallbacks() {
        assertEquals("WARNING", ServerLogFormatter.levelFor("recording saved but waiting", null));
        assertEquals("ERROR", ServerLogFormatter.levelFor("Traceback: recording saved", null));
        assertEquals("SUCCESS", ServerLogFormatter.levelFor("Recording saved", null));
        assertEquals("INFO", ServerLogFormatter.levelFor("started", null));
        assertEquals("ERROR", ServerLogFormatter.levelFor("ordinary message", "ERROR"));
    }

    @Test public void filtersMatchOnlyTheirLevel() {
        assertTrue(ServerLogFormatter.matchesFilter("ERROR", "ALL"));
        assertTrue(ServerLogFormatter.matchesFilter("ERROR", "ERRORS"));
        assertFalse(ServerLogFormatter.matchesFilter("INFO", "ERRORS"));
        assertTrue(ServerLogFormatter.matchesFilter("WARNING", "WARNINGS"));
        assertTrue(ServerLogFormatter.matchesFilter("SUCCESS", "SUCCESS"));
        assertFalse(ServerLogFormatter.matchesFilter("INFO", "SUCCESS"));
    }
}
