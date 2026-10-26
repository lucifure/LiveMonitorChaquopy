package com.livemonitor.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LogHistoryPolicyTest {
    @Test
    public void dayFileNameUsesLocalCalendarDate() throws Exception {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd", Locale.US);
        Date date = format.parse("20261026");
        assertEquals("LiveMonitor_AppLog_20261026.txt", LogHistoryPolicy.dayFileName(date));
    }

    @Test
    public void pruneRemovesDaysOlderThanRetentionBoundary() throws Exception {
        long now = day("20261026");
        List<String> deleted = LogHistoryPolicy.filesToDelete(Arrays.asList(
            history("20261011", 1),
            history("20261012", 1),
            history("20261013", 1)
        ), now, 14, 1_000L);
        assertEquals(Arrays.asList("LiveMonitor_AppLog_20261011.txt"), deleted);
    }

    @Test
    public void pruneRemovesOldestFilesFirstUntilUnderSizeLimit() throws Exception {
        long now = day("20261026");
        List<String> deleted = LogHistoryPolicy.filesToDelete(Arrays.asList(
            history("20261020", 60),
            history("20261021", 60),
            history("20261022", 20)
        ), now, 14, 100L);
        assertEquals(Arrays.asList("LiveMonitor_AppLog_20261020.txt"), deleted);
    }

    @Test
    public void pruneContinuesWhenDeletingTheOldestWouldLeaveExactlyAtTheLimit() throws Exception {
        long now = day("20261026");
        List<String> deleted = LogHistoryPolicy.filesToDelete(Arrays.asList(
            history("20261020", 60),
            history("20261021", 100)
        ), now, 14, 100L);
        assertEquals(Arrays.asList(
            "LiveMonitor_AppLog_20261020.txt",
            "LiveMonitor_AppLog_20261021.txt"
        ), deleted);
    }

    @Test
    public void agePruningHappensBeforeSizePruning() throws Exception {
        long now = day("20261026");
        List<String> deleted = LogHistoryPolicy.filesToDelete(Arrays.asList(
            history("20261011", 50),
            history("20261020", 60),
            history("20261021", 60)
        ), now, 14, 100L);
        assertEquals(Arrays.asList(
            "LiveMonitor_AppLog_20261011.txt",
            "LiveMonitor_AppLog_20261020.txt"
        ), deleted);
    }

    private static LogHistoryPolicy.HistoryFile history(String day, long length) throws Exception {
        return LogHistoryPolicy.historyFile("LiveMonitor_AppLog_" + day + ".txt", length);
    }

    private static long day(String value) throws Exception {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(new SimpleDateFormat("yyyyMMdd", Locale.US).parse(value));
        return calendar.getTimeInMillis();
    }
}
