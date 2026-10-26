package com.livemonitor.app;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Pure naming and retention decisions for the append-only app log history. */
final class LogHistoryPolicy {
    static final String FILE_PREFIX = "LiveMonitor_AppLog_";
    static final String FILE_SUFFIX = ".txt";

    private LogHistoryPolicy() {}

    static String dayFileName(Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd", Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        return FILE_PREFIX + format.format(date == null ? new Date() : date) + FILE_SUFFIX;
    }

    static List<String> filesToDelete(
        List<HistoryFile> files,
        long nowMillis,
        int retentionDays,
        long maxBytes
    ) {
        if (files == null || files.isEmpty()) return Collections.emptyList();

        Calendar cutoff = Calendar.getInstance();
        cutoff.setTimeInMillis(nowMillis);
        cutoff.set(Calendar.HOUR_OF_DAY, 0);
        cutoff.set(Calendar.MINUTE, 0);
        cutoff.set(Calendar.SECOND, 0);
        cutoff.set(Calendar.MILLISECOND, 0);
        cutoff.add(Calendar.DAY_OF_YEAR, -Math.max(0, retentionDays));
        long cutoffMillis = cutoff.getTimeInMillis();

        List<HistoryFile> ordered = new ArrayList<>(files);
        ordered.sort(Comparator.comparingLong(file -> file.dayStartMillis));
        List<String> deleted = new ArrayList<>();
        List<HistoryFile> remaining = new ArrayList<>();
        long totalBytes = 0L;

        for (HistoryFile file : ordered) {
            if (file.dayStartMillis < cutoffMillis) {
                deleted.add(file.name);
            } else {
                remaining.add(file);
                totalBytes += Math.max(0L, file.length);
            }
        }

        if (totalBytes > maxBytes) {
            for (HistoryFile file : remaining) {
                deleted.add(file.name);
                totalBytes -= Math.max(0L, file.length);
                if (totalBytes < maxBytes) break;
            }
        }
        return deleted;
    }

    static HistoryFile historyFile(String name, long length) {
        if (name == null || !name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX)) return null;
        String dateText = name.substring(FILE_PREFIX.length(), name.length() - FILE_SUFFIX.length());
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd", Locale.US);
        format.setLenient(false);
        format.setTimeZone(TimeZone.getDefault());
        ParsePosition position = new ParsePosition(0);
        Date date = format.parse(dateText, position);
        if (date == null || position.getIndex() != dateText.length()) return null;
        return new HistoryFile(name, date.getTime(), Math.max(0L, length));
    }

    static final class HistoryFile {
        final String name;
        final long dayStartMillis;
        final long length;

        HistoryFile(String name, long dayStartMillis, long length) {
            this.name = name;
            this.dayStartMillis = dayStartMillis;
            this.length = length;
        }
    }
}
