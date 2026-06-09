package com.livemonitor.app;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Small ProcessBuilder wrapper for invoking an installed/bundled yt-dlp binary.
 *
 * The app can use this in two ways:
 * - extractor mode: yt-dlp resolves one playable URL for FFmpeg fallback.
 * - recorder mode: yt-dlp owns the download so --live-from-start can walk the
 *   YouTube DVR fragments from the earliest available fragment.
 */
public class YtDlpRunner {
    private static final String TAG = "YtDlpRunner";
    private static final Map<String, Process> recordingProcesses = new ConcurrentHashMap<>();

    public interface OnLogCallback {
        void onLog(String message);
    }

    public static String resolvePlayableUrl(
        List<String> args,
        int timeoutSeconds,
        OnLogCallback logCallback
    ) throws Exception {
        if (args == null || args.isEmpty()) {
            throw new IllegalArgumentException("yt-dlp command is empty.");
        }

        int safeTimeoutSeconds = Math.max(10, Math.min(300, timeoutSeconds));
        ProcessBuilder builder = new ProcessBuilder(args);
        builder.redirectErrorStream(true);

        Process process;

        try {
            process = builder.start();
        } catch (IOException e) {
            String executable = args.get(0);
            throw new IllegalStateException(
                "Unable to start yt-dlp. "
                    + YtDlpEnvironment.describeExecutableProblem(executable)
                    + " Original error: "
                    + e.getMessage(),
                e
            );
        }

        OutputCollector collector = new OutputCollector(process.getInputStream(), logCallback);
        Thread collectorThread = new Thread(collector, "YtDlpOutputCollector");
        collectorThread.start();

        boolean completed = process.waitFor(safeTimeoutSeconds, TimeUnit.SECONDS);

        if (!completed) {
            process.destroy();

            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }

            collectorThread.join(2_000L);
            throw new IllegalStateException(
                "yt-dlp timed out after " + safeTimeoutSeconds + " seconds. "
                    + collector.getLastOutputSummary()
            );
        }

        collectorThread.join(2_000L);

        int exitCode = process.exitValue();
        String playableUrl = collector.getFirstPlayableUrl();

        if (exitCode == 0 && !isBlank(playableUrl)) {
            return playableUrl;
        }

        throw new IllegalStateException(
            "yt-dlp failed with exit code " + exitCode + ". "
                + collector.getLastOutputSummary()
        );
    }

    public static int recordLiveStream(
        String recordingId,
        List<String> args,
        String outputPath,
        OnLogCallback logCallback
    ) throws Exception {
        if (args == null || args.isEmpty()) {
            throw new IllegalArgumentException("yt-dlp recording command is empty.");
        }

        String safeRecordingId = normalizeRecordingId(recordingId, outputPath);
        ensureParentDirectory(outputPath);
        cancelRecording(safeRecordingId);

        ProcessBuilder builder = new ProcessBuilder(args);
        builder.redirectErrorStream(true);

        Process process;

        try {
            process = builder.start();
        } catch (IOException e) {
            String executable = args.get(0);
            throw new IllegalStateException(
                "Unable to start yt-dlp recorder. "
                    + YtDlpEnvironment.describeExecutableProblem(executable)
                    + " Original error: "
                    + e.getMessage(),
                e
            );
        }

        recordingProcesses.put(safeRecordingId, process);

        OutputCollector collector = new OutputCollector(process.getInputStream(), logCallback);
        Thread collectorThread = new Thread(collector, "YtDlpRecordingOutputCollector-" + safeRecordingId);
        collectorThread.start();

        try {
            int exitCode = process.waitFor();
            collectorThread.join(2_000L);
            return exitCode;
        } finally {
            recordingProcesses.remove(safeRecordingId, process);
        }
    }

    public static boolean cancelRecording(String recordingId) {
        String safeRecordingId = normalizeRecordingId(recordingId, "");
        Process process = recordingProcesses.remove(safeRecordingId);

        if (process == null) {
            return false;
        }

        process.destroy();

        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }

        return true;
    }

    public static boolean isRecording(String recordingId) {
        return recordingProcesses.containsKey(normalizeRecordingId(recordingId, ""));
    }

    public static void cancelAllRecordings() {
        for (String recordingId : recordingProcesses.keySet()) {
            cancelRecording(recordingId);
        }
    }

    private static class OutputCollector implements Runnable {
        private static final int MAX_LOG_CHARS = 1500;

        private final InputStream inputStream;
        private final OnLogCallback logCallback;
        private final StringBuilder recentOutput = new StringBuilder();
        private String firstPlayableUrl = "";

        OutputCollector(InputStream inputStream, OnLogCallback logCallback) {
            this.inputStream = inputStream;
            this.logCallback = logCallback;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;

                while ((line = reader.readLine()) != null) {
                    String normalized = line.trim();

                    if (normalized.isEmpty()) {
                        continue;
                    }

                    appendRecentOutput(normalized);

                    if (isBlank(firstPlayableUrl) && looksLikePlayableUrl(normalized)) {
                        firstPlayableUrl = normalized;
                    }

                    if (logCallback != null && isImportantYtDlpLine(normalized)) {
                        logCallback.onLog(normalized);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to collect yt-dlp output", e);
                appendRecentOutput("Output collector error: " + e.getMessage());
            }
        }

        synchronized String getFirstPlayableUrl() {
            return firstPlayableUrl;
        }

        synchronized String getLastOutputSummary() {
            if (recentOutput.length() == 0) {
                return "No yt-dlp output.";
            }

            return recentOutput.toString();
        }

        private synchronized void appendRecentOutput(String line) {
            if (recentOutput.length() > 0) {
                recentOutput.append('\n');
            }

            recentOutput.append(line);

            if (recentOutput.length() > MAX_LOG_CHARS) {
                recentOutput.delete(0, recentOutput.length() - MAX_LOG_CHARS);
            }
        }
    }

    private static boolean looksLikePlayableUrl(String line) {
        if (isBlank(line)) {
            return false;
        }

        String lower = line.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static boolean isImportantYtDlpLine(String line) {
        if (isBlank(line)) {
            return false;
        }

        String lower = line.toLowerCase();
        return lower.contains("error")
            || lower.contains("warning")
            || lower.contains("unavailable")
            || lower.contains("sign in")
            || lower.contains("private")
            || lower.contains("members-only")
            || lower.contains("premiere")
            || lower.contains("live event")
            || lower.contains("destination")
            || lower.contains("merging formats")
            || lower.contains("po_token")
            || lower.contains("po token")
            || lower.contains("gvs")
            || lower.contains("bot")
            || lower.contains("token required")
            || lower.contains("precondition")
            || lower.contains("http error 403");
    }

    private static void ensureParentDirectory(String outputPath) {
        if (isBlank(outputPath)) {
            return;
        }

        File parent = new File(outputPath).getParentFile();

        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
    }

    private static String normalizeRecordingId(String recordingId, String fallback) {
        if (!isBlank(recordingId)) {
            return recordingId.trim();
        }

        if (!isBlank(fallback)) {
            return fallback.trim();
        }

        return "default";
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
