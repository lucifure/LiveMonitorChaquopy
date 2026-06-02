package com.livemonitor.app;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks active recording progress for the Downloads tab.
 *
 * Live recordings usually do not have a fixed total duration,
 * so progress is represented using:
 * - bytes recorded
 * - elapsed recording time
 * - rough progressPercent from RecordingItem
 *
 * This class does not update notifications.
 */
public class RecordingProgressTracker {

    public interface Listener {
        void onRecordingProgressUpdated(RecordingItem recording);
        void onRecordingStalled(RecordingItem recording);
    }

    private static final long DEFAULT_UPDATE_INTERVAL_MS = 2_000L;
    private static final long STALL_WARNING_AFTER_MS = 90_000L;
    private static final long STALL_RECOVERY_NOTIFY_INTERVAL_MS = 120_000L;

    private final AppStorage storage;
    private final Map<String, TrackedRecording> trackedRecordings;

    private Listener listener;
    private Thread workerThread;
    private volatile boolean running;
    private long updateIntervalMs;

    public RecordingProgressTracker(AppStorage storage) {
        this.storage = storage;
        this.trackedRecordings = new ConcurrentHashMap<>();
        this.updateIntervalMs = DEFAULT_UPDATE_INTERVAL_MS;
        this.running = false;
    }

    public synchronized void setListener(Listener listener) {
        this.listener = listener;
    }

    public synchronized void setUpdateIntervalMs(long updateIntervalMs) {
        this.updateIntervalMs = Math.max(500L, updateIntervalMs);
    }

    public synchronized void start() {
        if (running) {
            return;
        }

        running = true;

        workerThread = new Thread(this::runLoop, "RecordingProgressTracker");
        workerThread.start();
    }

    public synchronized void stop() {
        running = false;

        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }

        trackedRecordings.clear();
    }

    public void track(RecordingItem recording) {
        if (recording == null || isBlank(recording.getId())) {
            return;
        }

        long startTime = recording.getStartedAt() > 0L
            ? recording.getStartedAt()
            : System.currentTimeMillis();

        trackedRecordings.put(
            recording.getId(),
            new TrackedRecording(recording, startTime)
        );
    }

    public void untrack(String recordingId) {
        if (isBlank(recordingId)) {
            return;
        }

        trackedRecordings.remove(recordingId);
    }

    public void untrack(RecordingItem recording) {
        if (recording == null) {
            return;
        }

        untrack(recording.getId());
    }

    public boolean isTracking(String recordingId) {
        return !isBlank(recordingId) && trackedRecordings.containsKey(recordingId);
    }

    public int getTrackedCount() {
        return trackedRecordings.size();
    }

    public void refreshNow() {
        for (TrackedRecording tracked : trackedRecordings.values()) {
            updateTrackedRecording(tracked);
        }
    }

    private void runLoop() {
        while (running) {
            try {
                refreshNow();
                Thread.sleep(updateIntervalMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ignored) {
                // Progress tracking should never crash monitoring/recording.
            }
        }
    }

    private void updateTrackedRecording(TrackedRecording tracked) {
        if (tracked == null || tracked.recording == null) {
            return;
        }

        RecordingItem recording = tracked.recording;

        if (!recording.isActive()) {
            untrack(recording.getId());
            return;
        }

        long bytesRecorded = calculateBytesRecorded(recording);
        long durationSeconds = calculateDurationSeconds(tracked);
        long now = System.currentTimeMillis();
        boolean stalled = false;

        if (bytesRecorded > tracked.lastBytesRecorded) {
            tracked.lastBytesRecorded = bytesRecorded;
            tracked.lastGrowthAtMillis = now;

            if (recording.getErrorMessage() != null
                && recording.getErrorMessage().startsWith("No file growth")) {
                recording.clearDiagnosticMessage();
            }
        } else if (RecordingItem.STATUS_RECORDING.equals(recording.getStatus())
            && durationSeconds > 60L
            && now - tracked.lastGrowthAtMillis > STALL_WARNING_AFTER_MS) {
            stalled = true;
            recording.setDiagnosticMessage(
                "No file growth for "
                    + formatDuration((now - tracked.lastGrowthAtMillis) / 1_000L)
                    + "; restarting recorder if the stream is still live."
            );
        }

        recording.updateProgress(bytesRecorded, durationSeconds);
        storage.upsertRecording(recording);

        Listener currentListener;

        synchronized (this) {
            currentListener = listener;
        }

        if (currentListener != null) {
            currentListener.onRecordingProgressUpdated(recording);

            if (stalled && now - tracked.lastStallNotificationAtMillis > STALL_RECOVERY_NOTIFY_INTERVAL_MS) {
                tracked.lastStallNotificationAtMillis = now;
                currentListener.onRecordingStalled(recording);
            }
        }
    }

    private long calculateBytesRecorded(RecordingItem recording) {
        if (recording == null || isBlank(recording.getTempTsPath())) {
            return 0L;
        }

        try {
            File file = new File(recording.getTempTsPath());

            if (file.exists() && file.isFile()) {
                return Math.max(0L, file.length());
            }
        } catch (Exception ignored) {
            return 0L;
        }

        return 0L;
    }

    private long calculateDurationSeconds(TrackedRecording tracked) {
        long startedAt = tracked.startTimeMillis;

        if (startedAt <= 0L) {
            return 0L;
        }

        long elapsedMillis = System.currentTimeMillis() - startedAt;

        if (elapsedMillis <= 0L) {
            return 0L;
        }

        return elapsedMillis / 1_000L;
    }

    public static String formatBytes(long bytes) {
        if (bytes < 0L) {
            bytes = 0L;
        }

        if (bytes < 1024L) {
            return bytes + " B";
        }

        double kb = bytes / 1024.0;

        if (kb < 1024.0) {
            return String.format(java.util.Locale.US, "%.1f KB", kb);
        }

        double mb = kb / 1024.0;

        if (mb < 1024.0) {
            return String.format(java.util.Locale.US, "%.1f MB", mb);
        }

        double gb = mb / 1024.0;

        return String.format(java.util.Locale.US, "%.2f GB", gb);
    }

    public static String formatDuration(long seconds) {
        if (seconds < 0L) {
            seconds = 0L;
        }

        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;

        if (hours > 0L) {
            return String.format(
                java.util.Locale.US,
                "%d:%02d:%02d",
                hours,
                minutes,
                secs
            );
        }

        return String.format(
            java.util.Locale.US,
            "%02d:%02d",
            minutes,
            secs
        );
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static class TrackedRecording {

        private final RecordingItem recording;
        private final long startTimeMillis;
        private long lastBytesRecorded;
        private long lastGrowthAtMillis;
        private long lastStallNotificationAtMillis;

        private TrackedRecording(RecordingItem recording, long startTimeMillis) {
            this.recording = recording;
            this.startTimeMillis = startTimeMillis;
            this.lastBytesRecorded = Math.max(0L, recording.getBytesRecorded());
            this.lastGrowthAtMillis = System.currentTimeMillis();
            this.lastStallNotificationAtMillis = 0L;
        }
    }
}
