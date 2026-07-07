package com.livemonitor.app;

import java.io.File;
import java.net.URLDecoder;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Small text formatting helpers for UI screens/adapters.
 */
public final class UiTextUtils {

    private UiTextUtils() {
        // Utility class.
    }

    public static String formatTimestamp(long timestamp) {
        if (timestamp <= 0L) {
            return "";
        }

        SimpleDateFormat format = new SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.US
        );

        return format.format(new Date(timestamp));
    }

    public static String formatTimeOnly(long timestamp) {
        if (timestamp <= 0L) {
            return "";
        }

        SimpleDateFormat format = new SimpleDateFormat(
            "HH:mm:ss",
            Locale.US
        );

        return format.format(new Date(timestamp));
    }

    public static String formatChannelStatus(ChannelItem channel) {
        if (channel == null) {
            return "Unknown";
        }

        String status = channel.getStatus();

        if (ChannelItem.STATUS_IDLE.equals(status)) {
            return "Idle";
        }

        if (ChannelItem.STATUS_WAITING_FOR_LIVE.equals(status)) {
            return "Waiting for live";
        }

        if (ChannelItem.STATUS_LIVE_DETECTED.equals(status)) {
            return "Live detected";
        }

        if (ChannelItem.STATUS_RECORDING.equals(status)) {
            return "Recording";
        }

        if (ChannelItem.STATUS_PAUSED_BY_USER.equals(status)) {
            return "Paused by user";
        }

        if (ChannelItem.STATUS_PAUSED_NETWORK.equals(status)) {
            return "Paused: network";
        }

        if (ChannelItem.STATUS_RETRYING.equals(status)) {
            return "Retrying";
        }

        if (ChannelItem.STATUS_STOPPED.equals(status)) {
            return "Stopped";
        }

        if (ChannelItem.STATUS_FAILED.equals(status)) {
            return "Failed";
        }

        return status;
    }

    public static String formatRecordingStatus(RecordingItem recording) {
        if (recording == null) {
            return "Unknown";
        }

        String status = recording.getStatus();

        if (RecordingItem.STATUS_WAITING_FOR_LIVE.equals(status)) {
            return "Waiting for live";
        }

        if (RecordingItem.STATUS_RECORDING.equals(status)) {
            return "Recording";
        }

        if (RecordingItem.STATUS_PAUSED_NETWORK.equals(status)) {
            return "Paused: network";
        }

        if (RecordingItem.STATUS_CONVERTING.equals(status)) {
            return "Converting";
        }

        if (RecordingItem.STATUS_COMPLETED.equals(status)) {
            return "Completed";
        }

        if (RecordingItem.STATUS_FAILED.equals(status)) {
            return "Failed";
        }

        if (RecordingItem.STATUS_STOPPED_BY_USER.equals(status)) {
            return "Stopped by user";
        }
        if (RecordingItem.STATUS_STOPPED_BY_SYSTEM.equals(status)) {
            return "Stopped by system";
        }

        if (RecordingItem.STATUS_RECOVERABLE.equals(status)) {
            return "Recoverable";
        }

        return status;
    }

    public static String formatChannelDetails(ChannelItem channel) {
        if (channel == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();

        builder.append(formatChannelStatus(channel));

        if (channel.getRetryCount() > 0) {
            builder.append(" • Retry ");
            builder.append(channel.getRetryCount());
            builder.append("/");
            builder.append(channel.getMaxRetries());
        }

        if (channel.hasCurrentVideoId()) {
            builder.append(" • videoId=");
            builder.append(channel.getCurrentVideoId());
        }

        if (channel.getLastCheckAt() > 0L) {
            builder.append(" • Checked ");
            builder.append(formatTimeOnly(channel.getLastCheckAt()));
        }

        return builder.toString();
    }

    public static String formatRecordingDetails(RecordingItem recording) {
        if (recording == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();

        builder.append(formatRecordingStatus(recording));

        builder.append(" • ");
        builder.append(recording.getQuality());

        builder.append(" • ");
        builder.append(RecordingProgressTracker.formatDuration(recording.getDurationSeconds()));

        builder.append(" • ");
        builder.append(RecordingProgressTracker.formatBytes(recording.getBytesRecorded()));

        if (recording.getVideoId() != null && !recording.getVideoId().trim().isEmpty()) {
            builder.append(" • videoId=");
            builder.append(recording.getVideoId());
        }

        return builder.toString();
    }

    public static String formatRecordingDetailsLikeMx(RecordingItem recording) {
        if (recording == null) {
            return "";
        }

        String path = defaultIfBlank(recording.getFinalMp4Path(), recording.getBestPlayablePath());
        boolean contentUri = isContentUri(path);
        File file = path.trim().isEmpty() || contentUri ? null : new File(path);
        String fileName = resolveRecordingFileName(recording, path, file, contentUri);
        String location = contentUri ? recording.getSavedToDisplay() : file == null || file.getParent() == null ? "Unknown" : file.getParent();
        if (location == null || location.trim().isEmpty()) {
            location = contentUri ? path : "Unknown";
        }
        long bytes = file != null && file.exists() ? file.length() : recording.getBytesRecorded();
        long date = file != null && file.exists() ? file.lastModified() : recording.getFinishedAt();
        if (date <= 0L) {
            date = recording.getUpdatedAt();
        }

        StringBuilder builder = new StringBuilder();
        builder.append(shortenMiddle(fileName, 34));
        builder.append("\n\nFile\n");
        builder.append("File        ").append(fileName).append("\n");
        builder.append("Location    ").append(location).append("\n");
        builder.append("Size        ").append(RecordingProgressTracker.formatBytes(bytes));
        builder.append(" (").append(NumberFormat.getInstance(Locale.US).format(bytes)).append(" bytes)\n");
        builder.append("Date        ").append(formatLongDate(date)).append("\n\n");
        builder.append("Media\n");
        builder.append("Format      ").append(formatMediaType(path));
        return builder.toString();
    }

    private static String resolveRecordingFileName(
        RecordingItem recording,
        String path,
        File file,
        boolean contentUri
    ) {
        if (file != null && file.getName() != null && !file.getName().trim().isEmpty()) {
            return file.getName();
        }

        if (contentUri) {
            String uriName = extractFileNameFromContentUri(path);
            if (uriName != null && !uriName.trim().isEmpty()) {
                return uriName;
            }
        }

        return recording == null ? "" : recording.getDisplayTitle();
    }

    private static String extractFileNameFromContentUri(String uriText) {
        if (uriText == null || uriText.trim().isEmpty()) {
            return "";
        }

        String value = uriText.trim();
        int slashIndex = value.lastIndexOf('/');
        if (slashIndex >= 0 && slashIndex + 1 < value.length()) {
            value = value.substring(slashIndex + 1);
        }

        try {
            value = URLDecoder.decode(value, "UTF-8");
        } catch (Exception ignored) {
        }

        slashIndex = value.lastIndexOf('/');
        if (slashIndex >= 0 && slashIndex + 1 < value.length()) {
            value = value.substring(slashIndex + 1);
        }

        int colonIndex = value.lastIndexOf(':');
        if (colonIndex >= 0 && colonIndex + 1 < value.length()) {
            value = value.substring(colonIndex + 1);
        }

        return value.trim();
    }

    private static boolean isContentUri(String path) {
        return path != null && path.trim().toLowerCase(Locale.US).startsWith("content://");
    }

    private static String formatLongDate(long timestamp) {
        if (timestamp <= 0L) {
            return "Unknown";
        }
        SimpleDateFormat format = new SimpleDateFormat("d MMMM yyyy 'at' h:mm a", Locale.US);
        return format.format(new Date(timestamp)).replace("AM", "am").replace("PM", "pm");
    }

    private static String formatMediaType(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.US);
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v")) return "MPEG-4";
        if (lower.endsWith(".ts")) return "MPEG-TS";
        if (lower.endsWith(".mkv")) return "Matroska";
        return "Video";
    }

    public static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    public static String defaultIfBlank(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback == null ? "" : fallback;
        }

        return value;
    }

    public static String shortenMiddle(String value, int maxLength) {
        if (value == null) {
            return "";
        }

        if (maxLength <= 0 || value.length() <= maxLength) {
            return value;
        }

        if (maxLength <= 3) {
            return value.substring(0, maxLength);
        }

        int keep = maxLength - 3;
        int left = keep / 2;
        int right = keep - left;

        return value.substring(0, left)
            + "..."
            + value.substring(value.length() - right);
    }
}
