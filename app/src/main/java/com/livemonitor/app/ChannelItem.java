package com.livemonitor.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.UUID;

/**
 * Represents one channel/stream entry being monitored by the app.
 *
 * This model is intentionally plain Java so it can be used by:
 * - MainActivity UI list
 * - MonitorService background monitoring
 * - persistent storage helpers
 * - notification manager logic
 * - per-channel logs
 */
public class ChannelItem {

    public static final String STATUS_IDLE = "IDLE";
    public static final String STATUS_WAITING_FOR_LIVE = "WAITING_FOR_LIVE";
    public static final String STATUS_LIVE_DETECTED = "LIVE_DETECTED";
    public static final String STATUS_RECORDING = "RECORDING";
    public static final String STATUS_PAUSED_BY_USER = "PAUSED_BY_USER";
    public static final String STATUS_PAUSED_NETWORK = "PAUSED_NETWORK";
    public static final String STATUS_RETRYING = "RETRYING";
    public static final String STATUS_STOPPED = "STOPPED";
    public static final String STATUS_FAILED = "FAILED";

    private static final String JSON_ID = "id";
    private static final String JSON_URL = "url";
    private static final String JSON_NORMALIZED_URL = "normalizedUrl";
    private static final String JSON_TITLE = "title";
    private static final String JSON_STATUS = "status";
    private static final String JSON_MONITORING_ENABLED = "monitoringEnabled";
    private static final String JSON_PAUSED_BY_USER = "pausedByUser";
    private static final String JSON_RECORDING = "recording";
    private static final String JSON_RETRY_COUNT = "retryCount";
    private static final String JSON_MAX_RETRIES = "maxRetries";
    private static final String JSON_LAST_ERROR = "lastError";
    private static final String JSON_LAST_LIVE_URL = "lastLiveUrl";
    private static final String JSON_CURRENT_VIDEO_ID = "currentVideoId";
    private static final String JSON_CREATED_AT = "createdAt";
    private static final String JSON_UPDATED_AT = "updatedAt";
    private static final String JSON_LAST_CHECK_AT = "lastCheckAt";
    private static final String JSON_LAST_LIVE_AT = "lastLiveAt";
    private static final String JSON_NOTIFICATION_ID = "notificationId";

    private String id;
    private String url;
    private String normalizedUrl;
    private String title;
    private String status;
    private boolean monitoringEnabled;
    private boolean pausedByUser;
    private boolean recording;
    private int retryCount;
    private int maxRetries;
    private String lastError;
    private String lastLiveUrl;

    /**
     * YouTube video ID for the currently detected/recording live stream.
     *
     * This is separate from the channel URL because one channel can have many
     * different live video IDs over time. Tracking it prevents duplicate
     * recordings of the same active live event and helps name/log recordings.
     */
    private String currentVideoId;

    private long createdAt;
    private long updatedAt;
    private long lastCheckAt;
    private long lastLiveAt;
    private int notificationId;

    public ChannelItem(String url) {
        long now = System.currentTimeMillis();

        this.id = UUID.randomUUID().toString();
        this.url = cleanUrl(url);
        this.normalizedUrl = normalizeUrl(this.url);
        this.title = buildDefaultTitle(this.url);
        this.status = STATUS_IDLE;
        this.monitoringEnabled = true;
        this.pausedByUser = false;
        this.recording = false;
        this.retryCount = 0;
        this.maxRetries = 10;
        this.lastError = "";
        this.lastLiveUrl = "";
        this.currentVideoId = "";
        this.createdAt = now;
        this.updatedAt = now;
        this.lastCheckAt = 0L;
        this.lastLiveAt = 0L;
        this.notificationId = buildStableNotificationId(this.id);
    }

    public ChannelItem(
        String id,
        String url,
        String normalizedUrl,
        String title,
        String status,
        boolean monitoringEnabled,
        boolean pausedByUser,
        boolean recording,
        int retryCount,
        int maxRetries,
        String lastError,
        String lastLiveUrl,
        String currentVideoId,
        long createdAt,
        long updatedAt,
        long lastCheckAt,
        long lastLiveAt,
        int notificationId
    ) {
        this.id = isBlank(id) ? UUID.randomUUID().toString() : id;
        this.url = cleanUrl(url);
        this.normalizedUrl = isBlank(normalizedUrl) ? normalizeUrl(this.url) : normalizedUrl;
        this.title = isBlank(title) ? buildDefaultTitle(this.url) : title;
        this.status = isBlank(status) ? STATUS_IDLE : status;
        this.monitoringEnabled = monitoringEnabled;
        this.pausedByUser = pausedByUser;
        this.recording = recording;
        this.retryCount = Math.max(0, retryCount);
        this.maxRetries = maxRetries <= 0 ? 10 : maxRetries;
        this.lastError = nullToEmpty(lastError);
        this.lastLiveUrl = nullToEmpty(lastLiveUrl);
        this.currentVideoId = normalizeVideoId(currentVideoId);
        this.createdAt = createdAt <= 0 ? System.currentTimeMillis() : createdAt;
        this.updatedAt = updatedAt <= 0 ? System.currentTimeMillis() : updatedAt;
        this.lastCheckAt = Math.max(0L, lastCheckAt);
        this.lastLiveAt = Math.max(0L, lastLiveAt);
        this.notificationId = notificationId == 0
            ? buildStableNotificationId(this.id)
            : notificationId;
    }

    public static ChannelItem fromJson(JSONObject json) throws JSONException {
        if (json == null) {
            throw new JSONException("Channel JSON is null");
        }

        return new ChannelItem(
            json.optString(JSON_ID, ""),
            json.optString(JSON_URL, ""),
            json.optString(JSON_NORMALIZED_URL, ""),
            json.optString(JSON_TITLE, ""),
            json.optString(JSON_STATUS, STATUS_IDLE),
            json.optBoolean(JSON_MONITORING_ENABLED, true),
            json.optBoolean(JSON_PAUSED_BY_USER, false),
            json.optBoolean(JSON_RECORDING, false),
            json.optInt(JSON_RETRY_COUNT, 0),
            json.optInt(JSON_MAX_RETRIES, 10),
            json.optString(JSON_LAST_ERROR, ""),
            json.optString(JSON_LAST_LIVE_URL, ""),
            json.optString(JSON_CURRENT_VIDEO_ID, ""),
            json.optLong(JSON_CREATED_AT, System.currentTimeMillis()),
            json.optLong(JSON_UPDATED_AT, System.currentTimeMillis()),
            json.optLong(JSON_LAST_CHECK_AT, 0L),
            json.optLong(JSON_LAST_LIVE_AT, 0L),
            json.optInt(JSON_NOTIFICATION_ID, 0)
        );
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();

        json.put(JSON_ID, id);
        json.put(JSON_URL, url);
        json.put(JSON_NORMALIZED_URL, normalizedUrl);
        json.put(JSON_TITLE, title);
        json.put(JSON_STATUS, status);
        json.put(JSON_MONITORING_ENABLED, monitoringEnabled);
        json.put(JSON_PAUSED_BY_USER, pausedByUser);
        json.put(JSON_RECORDING, recording);
        json.put(JSON_RETRY_COUNT, retryCount);
        json.put(JSON_MAX_RETRIES, maxRetries);
        json.put(JSON_LAST_ERROR, lastError);
        json.put(JSON_LAST_LIVE_URL, lastLiveUrl);
        json.put(JSON_CURRENT_VIDEO_ID, currentVideoId);
        json.put(JSON_CREATED_AT, createdAt);
        json.put(JSON_UPDATED_AT, updatedAt);
        json.put(JSON_LAST_CHECK_AT, lastCheckAt);
        json.put(JSON_LAST_LIVE_AT, lastLiveAt);
        json.put(JSON_NOTIFICATION_ID, notificationId);

        return json;
    }

    public void markWaitingForLive() {
        status = STATUS_WAITING_FOR_LIVE;
        recording = false;
        lastCheckAt = System.currentTimeMillis();
        touch();
    }

    public void markLiveDetected(String videoId, String liveUrl) {
        status = STATUS_LIVE_DETECTED;
        currentVideoId = normalizeVideoId(videoId);
        lastLiveUrl = nullToEmpty(liveUrl);
        lastLiveAt = System.currentTimeMillis();
        retryCount = 0;
        lastError = "";
        touch();
    }

    public void markRecording(String videoId, String liveUrl) {
        status = STATUS_RECORDING;
        recording = true;
        currentVideoId = normalizeVideoId(videoId);
        lastLiveUrl = nullToEmpty(liveUrl);
        lastLiveAt = System.currentTimeMillis();
        retryCount = 0;
        lastError = "";
        touch();
    }

    public void markRetrying(String errorMessage) {
        status = STATUS_RETRYING;
        recording = false;
        retryCount++;
        lastError = nullToEmpty(errorMessage);
        touch();
    }

    public void markPausedByUser() {
        status = STATUS_PAUSED_BY_USER;
        pausedByUser = true;
        monitoringEnabled = false;
        recording = false;
        touch();
    }

    public void markPausedByNetwork(String errorMessage) {
        status = STATUS_PAUSED_NETWORK;
        recording = false;
        lastError = nullToEmpty(errorMessage);
        touch();
    }

    public void markStopped() {
        status = STATUS_STOPPED;
        monitoringEnabled = false;
        pausedByUser = false;
        recording = false;
        currentVideoId = "";
        touch();
    }

    public void markFailed(String errorMessage) {
        status = STATUS_FAILED;
        recording = false;
        lastError = nullToEmpty(errorMessage);
        touch();
    }

    public void markRecordingFinished() {
        status = STATUS_IDLE;
        recording = false;
        currentVideoId = "";
        retryCount = 0;
        lastError = "";
        touch();
    }

    public void resumeMonitoring() {
        monitoringEnabled = true;
        pausedByUser = false;
        recording = false;
        status = STATUS_IDLE;
        touch();
    }

    public void resetRetries() {
        retryCount = 0;
        lastError = "";
        touch();
    }

    public boolean canRetry() {
        return retryCount < maxRetries;
    }

    public long getNextRetryDelayMillis() {
        int safeRetryCount = Math.max(0, retryCount);

        if (safeRetryCount <= 0) {
            return 5_000L;
        }

        long delaySeconds = 5L * (1L << Math.min(safeRetryCount, 5));

        if (delaySeconds > 300L) {
            delaySeconds = 300L;
        }

        return delaySeconds * 1_000L;
    }

    public boolean hasSameNormalizedUrl(ChannelItem other) {
        if (other == null) {
            return false;
        }

        return normalizedUrl.equals(other.normalizedUrl);
    }

    public boolean isSameCurrentVideo(String videoId) {
        String normalizedVideoId = normalizeVideoId(videoId);

        return !normalizedVideoId.isEmpty()
            && normalizedVideoId.equals(currentVideoId);
    }

    public boolean hasCurrentVideoId() {
        return !isBlank(currentVideoId);
    }

    public boolean shouldMonitor() {
        return monitoringEnabled && !pausedByUser;
    }

    public String getDisplayTitle() {
        if (!isBlank(title)) {
            return title;
        }

        return buildDefaultTitle(url);
    }

    public String getLogTag() {
        return "[" + getDisplayTitle() + "]";
    }

    private void touch() {
        updatedAt = System.currentTimeMillis();
    }

    private static String cleanUrl(String value) {
        if (value == null) {
            return "";
        }

        return value
            .trim()
            .replaceAll("\\s+", "")
            .replaceAll("/+$", "");
    }

    public static String normalizeUrl(String value) {
        String cleaned = cleanUrl(value).toLowerCase(Locale.US);

        if (cleaned.startsWith("https://")) {
            cleaned = cleaned.substring("https://".length());
        } else if (cleaned.startsWith("http://")) {
            cleaned = cleaned.substring("http://".length());
        }

        if (cleaned.startsWith("www.")) {
            cleaned = cleaned.substring("www.".length());
        }

        return cleaned.replaceAll("/+$", "");
    }

    public static String normalizeVideoId(String value) {
        if (value == null) {
            return "";
        }

        return value.trim();
    }

    private static String buildDefaultTitle(String value) {
        String cleaned = cleanUrl(value);

        if (cleaned.isEmpty()) {
            return "Untitled Channel";
        }

        String normalized = normalizeUrl(cleaned);

        if (normalized.length() <= 36) {
            return normalized;
        }

        return normalized.substring(0, 33) + "...";
    }

    private static int buildStableNotificationId(String value) {
        int hash = value == null ? 0 : value.hashCode();

        if (hash == Integer.MIN_VALUE) {
            hash = 1;
        }

        hash = Math.abs(hash);

        /*
         * Keep IDs away from very low fixed notification IDs.
         * This helps avoid collisions with service/global notifications.
         */
        return 10_000 + (hash % 1_000_000);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public String getId() {
        return id;
    }

    public String getUrl() {
        return url;
    }

    public String getNormalizedUrl() {
        return normalizedUrl;
    }

    public String getTitle() {
        return title;
    }

    public String getStatus() {
        return status;
    }

    public boolean isMonitoringEnabled() {
        return monitoringEnabled;
    }

    public boolean isPausedByUser() {
        return pausedByUser;
    }

    public boolean isRecording() {
        return recording;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public String getLastError() {
        return lastError;
    }

    public String getLastLiveUrl() {
        return lastLiveUrl;
    }

    public String getCurrentVideoId() {
        return currentVideoId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public long getLastCheckAt() {
        return lastCheckAt;
    }

    public long getLastLiveAt() {
        return lastLiveAt;
    }

    public int getNotificationId() {
        return notificationId;
    }

    public void setUrl(String url) {
        this.url = cleanUrl(url);
        this.normalizedUrl = normalizeUrl(this.url);
        touch();
    }

    public void setTitle(String title) {
        this.title = isBlank(title) ? buildDefaultTitle(url) : title.trim();
        touch();
    }

    public void setStatus(String status) {
        this.status = isBlank(status) ? STATUS_IDLE : status;
        touch();
    }

    public void setMonitoringEnabled(boolean monitoringEnabled) {
        this.monitoringEnabled = monitoringEnabled;
        touch();
    }

    public void setPausedByUser(boolean pausedByUser) {
        this.pausedByUser = pausedByUser;
        touch();
    }

    public void setRecording(boolean recording) {
        this.recording = recording;
        touch();
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = Math.max(0, retryCount);
        touch();
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries <= 0 ? 10 : maxRetries;
        touch();
    }

    public void setLastError(String lastError) {
        this.lastError = nullToEmpty(lastError);
        touch();
    }

    public void setLastLiveUrl(String lastLiveUrl) {
        this.lastLiveUrl = nullToEmpty(lastLiveUrl);
        touch();
    }

    public void setCurrentVideoId(String currentVideoId) {
        this.currentVideoId = normalizeVideoId(currentVideoId);
        touch();
    }

    public void clearCurrentVideoId() {
        this.currentVideoId = "";
        touch();
    }

    public void setLastCheckAt(long lastCheckAt) {
        this.lastCheckAt = Math.max(0L, lastCheckAt);
        touch();
    }

    public void setLastLiveAt(long lastLiveAt) {
        this.lastLiveAt = Math.max(0L, lastLiveAt);
        touch();
    }

    public void setNotificationId(int notificationId) {
        this.notificationId = notificationId == 0
            ? buildStableNotificationId(id)
            : notificationId;
        touch();
    }
