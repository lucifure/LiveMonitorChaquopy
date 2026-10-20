package com.livemonitor.app;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.SimpleTimeZone;
import java.util.TimeZone;

/** Formatting and filtering helpers for server log entries. */
public final class ServerLogFormatter {
    private static final String TIME_PATTERN = "[yyyy-MM-dd HH:mm:ss]";
    private ServerLogFormatter() { }

    public static String convertTime(String line, int serverOffsetSeconds, TimeZone target) {
        if (line == null || line.length() < TIME_PATTERN.length() || line.charAt(0) != '[') return line;
        SimpleDateFormat parser = new SimpleDateFormat(TIME_PATTERN, Locale.US);
        parser.setLenient(false);
        parser.setTimeZone(new SimpleTimeZone(serverOffsetSeconds * 1000, "server"));
        ParsePosition position = new ParsePosition(0);
        Date date = parser.parse(line.substring(0, TIME_PATTERN.length()), position);
        if (date == null || position.getIndex() != TIME_PATTERN.length()) return line;
        SimpleDateFormat output = new SimpleDateFormat(TIME_PATTERN, Locale.US);
        output.setTimeZone(target);
        return output.format(date) + line.substring(TIME_PATTERN.length());
    }

    public static String levelFor(String line, String serverLevelOrNull) {
        if (serverLevelOrNull != null && !serverLevelOrNull.isEmpty()) return serverLevelOrNull.toUpperCase(Locale.US);
        String lower = line == null ? "" : line.toLowerCase(Locale.US);
        if (containsAny(lower, "error", "failed", "could not", "exited with code", "no output file", "traceback")) return "ERROR";
        if (containsAny(lower, "waiting", "backoff", "skipped", "only a video part", "only an audio part", "low disk", "under 120s")) return "WARNING";
        if (containsAny(lower, "recording saved", "finalized", "merged")) return "SUCCESS";
        return "INFO";
    }

    public static boolean matchesFilter(String level, String filter) {
        if (filter == null || "ALL".equalsIgnoreCase(filter)) return true;
        if ("ERRORS".equalsIgnoreCase(filter)) return "ERROR".equalsIgnoreCase(level);
        if ("WARNINGS".equalsIgnoreCase(filter)) return "WARNING".equalsIgnoreCase(level);
        if ("SUCCESS".equalsIgnoreCase(filter)) return "SUCCESS".equalsIgnoreCase(level);
        return false;
    }

    private static boolean containsAny(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }
}
