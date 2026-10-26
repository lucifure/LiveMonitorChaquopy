package com.livemonitor.app;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/** Append-only daily text history, separate from the recordings directory. */
final class LogHistoryWriter {
    private static final Object WRITE_LOCK = new Object();
    private static final Object PRUNE_LOCK = new Object();
    private static final long PRUNE_INTERVAL_MS = 60L * 60L * 1000L;
    private static final int RETENTION_DAYS = 14;
    private static final long MAX_HISTORY_BYTES = 50L * 1024L * 1024L;
    private static volatile long lastPruneAtMillis;

    private LogHistoryWriter() {}

    static void append(Context context, String text) {
        if (context == null || text == null || text.isEmpty()) return;
        try {
            File directory = getHistoryDirectory(context);
            if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) return;
            synchronized (WRITE_LOCK) {
                File outputFile = new File(directory, LogHistoryPolicy.dayFileName(new Date()));
                try (FileOutputStream output = new FileOutputStream(outputFile, true)) {
                    output.write(text.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
            }
        } catch (Throwable ignored) {
            // History is best-effort and must never break the structured log path.
        }
        try {
            prune(context);
        } catch (Throwable ignored) {
            // A maintenance failure must not escape into the caller's log path.
        }
    }

    static void prune(Context context) {
        if (context == null) return;
        synchronized (PRUNE_LOCK) {
            long now = System.currentTimeMillis();
            if (lastPruneAtMillis > 0L && now >= lastPruneAtMillis
                && now - lastPruneAtMillis < PRUNE_INTERVAL_MS) {
                return;
            }
            lastPruneAtMillis = now;
            try {
                File directory = getHistoryDirectory(context);
                synchronized (WRITE_LOCK) {
                    File[] dayFiles = directory.listFiles(file -> file.isFile()
                        && LogHistoryPolicy.historyFile(file.getName(), file.length()) != null);
                    if (dayFiles == null || dayFiles.length == 0) return;
                    List<LogHistoryPolicy.HistoryFile> candidates = new ArrayList<>();
                    for (File file : dayFiles) {
                        candidates.add(LogHistoryPolicy.historyFile(file.getName(), file.length()));
                    }
                    for (String name : LogHistoryPolicy.filesToDelete(
                        candidates, now, RETENTION_DAYS, MAX_HISTORY_BYTES
                    )) {
                        try {
                            new File(directory, name).delete();
                        } catch (Throwable ignored) {
                            // Continue pruning other files.
                        }
                    }
                }
            } catch (Throwable ignored) {
                // Pruning is best-effort.
            }
        }
    }

    static List<File> listHistoryFiles(Context context) {
        try {
            File[] files = getHistoryDirectory(context).listFiles(file -> file.isFile()
                && LogHistoryPolicy.historyFile(file.getName(), file.length()) != null);
            if (files == null || files.length == 0) return new ArrayList<>();
            Arrays.sort(files, Comparator.comparing(File::getName));
            return new ArrayList<>(Arrays.asList(files));
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    static int deleteAll(Context context) {
        int deleted = 0;
        synchronized (WRITE_LOCK) {
            for (File file : listHistoryFiles(context)) {
                try {
                    if (file.delete()) deleted++;
                } catch (Throwable ignored) {
                    // Continue deleting remaining daily files.
                }
            }
        }
        return deleted;
    }

    private static File getHistoryDirectory(Context context) {
        Context appContext = context.getApplicationContext();
        if (appContext == null) appContext = context;
        File externalFiles = null;
        try {
            externalFiles = appContext.getExternalFilesDir(null);
        } catch (Throwable ignored) {
            // Fall back to the internal app files directory.
        }
        File base = externalFiles != null ? externalFiles : appContext.getFilesDir();
        return new File(base, "logs");
    }
}
