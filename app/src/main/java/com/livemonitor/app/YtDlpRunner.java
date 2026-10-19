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
    private static final Map<String, RecordingProcessHandle> recordingProcesses = new ConcurrentHashMap<>();
    private static final Object recordingProcessStartLock = new Object();

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
            boolean terminationConfirmed = terminateProcess(process);

            collectorThread.join(2_000L);
            throw new IllegalStateException(
                "yt-dlp timed out after " + safeTimeoutSeconds + " seconds. "
                    + (terminationConfirmed ? "Recorder process stopped. " : "Process termination could not be confirmed. ")
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
        String safeRecordingId = normalizeRecordingId(recordingId, outputPath);
        cancelRecording(safeRecordingId);
        RecordingProcessHandle handle = prepareLiveStream(safeRecordingId, args, outputPath, logCallback).start();
        try {
            return handle.awaitCompletion();
        } catch (Exception e) {
            handle.cancel();
            throw e;
        }
    }

    /** Validates command arguments and the output directory before lifecycle-gated startup. */
    public static PreparedRecording prepareLiveStream(
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
        return new PreparedRecording(safeRecordingId, args, logCallback);
    }

    public static final class PreparedRecording {
        private final String recordingId;
        private final List<String> args;
        private final OnLogCallback logCallback;

        private PreparedRecording(String recordingId, List<String> args, OnLogCallback logCallback) {
            this.recordingId = recordingId;
            this.args = new java.util.ArrayList<>(args);
            this.logCallback = logCallback;
        }

        /** Returns only after the OS process is registered and its collector is running. */
        public RecordingProcessHandle start() throws Exception {
            synchronized (recordingProcessStartLock) {
                RecordingProcessHandle existing = recordingProcesses.get(recordingId);
                if (existing != null) {
                    if (!existing.hasExited()) {
                        throw new IllegalStateException(
                            "Previous yt-dlp process is still owned for this recording."
                        );
                    }
                    recordingProcesses.remove(recordingId, existing);
                }

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

                // Construct and publish the cancellable process handle before
                // allocating the output collector or starting its thread. Any
                // later setup failure is returned through this handle so the
                // service can release its lifecycle gate before cancellation.
                RecordingProcessHandle handle = new RecordingProcessHandle(recordingId, process);
                try {
                    recordingProcesses.put(recordingId, handle);
                } catch (RuntimeException | Error registrationFailure) {
                    handle.setStartupFailure(asException(registrationFailure));
                    return handle;
                }

                try {
                    OutputCollector collector = new OutputCollector(process.getInputStream(), logCallback);
                    Thread collectorThread = new Thread(
                        collector,
                        "YtDlpRecordingOutputCollector-" + recordingId
                    );
                    handle.setCollector(collectorThread, collector);
                    collectorThread.start();
                    return handle;
                } catch (RuntimeException | Error setupFailure) {
                    handle.setStartupFailure(asException(setupFailure));
                    return handle;
                }
            }
        }
    }

    public static final class RecordingProcessHandle implements RecorderLaunchCoordinator.Handle {
        private final String recordingId;
        private final Process process;
        private volatile Thread collectorThread;
        private volatile OutputCollector collector;
        private volatile Exception startupFailure;

        private RecordingProcessHandle(String recordingId, Process process) {
            this.recordingId = recordingId;
            this.process = process;
        }

        private void setCollector(Thread collectorThread, OutputCollector collector) {
            this.collectorThread = collectorThread;
            this.collector = collector;
        }

        private void setStartupFailure(Exception failure) {
            startupFailure = failure;
        }

        public int awaitCompletion() throws Exception {
            boolean completed = false;
            try {
                if (startupFailure != null) {
                    throw startupFailure;
                }
                int exitCode = process.waitFor();
                Thread outputThread = collectorThread;
                if (outputThread != null) {
                    outputThread.join(2_000L);
                }
                completed = true;
                return exitCode;
            } finally {
                if (completed || hasExited()) {
                    recordingProcesses.remove(recordingId, this);
                }
            }
        }

        @Override
        public void cancel() {
            cancelAndConfirmExit();
        }

        /** Returns true only after Process.waitFor confirms the owned process exited. */
        boolean cancelAndConfirmExit() {
            boolean exited = terminateProcess(process);
            if (exited) {
                recordingProcesses.remove(recordingId, this);
            }
            return exited;
        }

        private boolean hasExited() {
            return hasProcessExited(process);
        }

        public String getOutputSummary() {
            OutputCollector currentCollector = collector;
            return currentCollector == null ? "" : currentCollector.getLastOutputSummary();
        }
    }

    private static Exception asException(Throwable error) {
        return error instanceof Exception
            ? (Exception) error
            : new IllegalStateException(error == null ? "yt-dlp startup failed." : error.getMessage(), error);
    }

    static boolean terminateProcess(Process process) {
        return terminateProcess(process, 2_000L, 2_000L);
    }

    static boolean terminateProcess(Process process, long gracefulWaitMillis, long forcedWaitMillis) {
        if (process == null) {
            return true;
        }
        boolean interrupted = false;
        try {
            process.destroy();
            try {
                if (process.waitFor(Math.max(0L, gracefulWaitMillis), TimeUnit.MILLISECONDS)) {
                    return true;
                }
            } catch (InterruptedException e) {
                interrupted = true;
            }

            process.destroyForcibly();
            try {
                return process.waitFor(Math.max(0L, forcedWaitMillis), TimeUnit.MILLISECONDS)
                    || hasProcessExited(process);
            } catch (InterruptedException e) {
                interrupted = true;
                return hasProcessExited(process);
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean hasProcessExited(Process process) {
        try {
            process.exitValue();
            return true;
        } catch (IllegalThreadStateException stillRunning) {
            return false;
        }
    }

    public static boolean cancelRecording(String recordingId) {
        String safeRecordingId = normalizeRecordingId(recordingId, "");
        RecordingProcessHandle handle = recordingProcesses.get(safeRecordingId);
        if (handle == null) {
            return false;
        }
        handle.cancelAndConfirmExit();
        return true;
    }

    public static boolean isRecording(String recordingId) {
        String safeRecordingId = normalizeRecordingId(recordingId, "");
        RecordingProcessHandle handle = recordingProcesses.get(safeRecordingId);
        if (handle == null) {
            return false;
        }
        if (handle.hasExited()) {
            recordingProcesses.remove(safeRecordingId, handle);
            return false;
        }
        return true;
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

    /** Public wrapper used by callers that have their own output loop (youtubedl-android). */
    public static boolean isImportantYtDlpLinePublic(String line) {
        return isImportantYtDlpLine(line);
    }

    /**
     * Returns true for lines that should be forwarded to the in-app log.
     *
     * We pass through everything EXCEPT pure progress/download-speed lines
     * that would flood the log with hundreds of identical fragment lines.
     * All errors, warnings, status changes, and po-token related lines are
     * always forwarded regardless.
     */
    private static boolean isImportantYtDlpLine(String line) {
        if (isBlank(line)) {
            return false;
        }

        String lower = line.toLowerCase();

        // Always pass through errors, warnings and key status lines.
        if (lower.contains("error")
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
                || lower.contains("http error 403")) {
            return true;
        }

        // Pass through yt-dlp status/info lines (not bare download-speed spam).
        if (lower.startsWith("[youtube]")
                || lower.startsWith("[info]")
                || lower.startsWith("[debug]")
                || lower.startsWith("[generic]")
                || lower.startsWith("[hlsnative]")
                || lower.startsWith("[dashsegfrag]")
                || lower.startsWith("[fragmentedmp4]")
                || lower.startsWith("[merger]")
                || lower.startsWith("[ffmpeg]")) {
            return true;
        }

        // Pass through first fragment download lines and completion markers.
        if (lower.contains("downloading webpage")
                || lower.contains("downloading player")
                || lower.contains("downloading m3u8")
                || lower.contains("downloading mpd")
                || lower.contains("downloading manifest")
                || lower.contains("downloading fragment")
                || lower.contains("total fragments")
                || lower.contains("live event")
                || lower.contains("writing")
                || lower.contains("has already been recorded")
                || lower.contains("100%")) {
            return true;
        }

        // Skip bare [download] progress lines — they repeat every few seconds
        // and are already visible in the diagnostic message on the recording card.
        return false;
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
