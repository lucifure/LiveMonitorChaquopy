package com.livemonitor.app;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * App-wide settings for monitoring, recording, storage, scheduling,
 * remote config, logging, and stability behavior.
 */
public class AppSettings {

    public static final String QUALITY_360P = "360p";
    public static final String QUALITY_480P = "480p";
    public static final String QUALITY_720P = "720p";
    public static final String QUALITY_1080P = "1080p";
    public static final String QUALITY_BEST = "best";

    private static final String JSON_POLL_INTERVAL_SECONDS = "pollIntervalSeconds";
    private static final String JSON_DOWNLOAD_QUALITY = "downloadQuality";
    private static final String JSON_SAVE_LOCATION_URI = "saveLocationUri";
    private static final String JSON_SAVE_LOCATION_DISPLAY_NAME = "saveLocationDisplayName";
    private static final String JSON_SCHEDULED_MONITORING_ENABLED = "scheduledMonitoringEnabled";
    private static final String JSON_SCHEDULE_START_MINUTES = "scheduleStartMinutes";
    private static final String JSON_SCHEDULE_END_MINUTES = "scheduleEndMinutes";
    private static final String JSON_ALLOW_CURRENT_RECORDING_OUTSIDE_SCHEDULE =
        "allowCurrentRecordingOutsideSchedule";
    private static final String JSON_RESTORE_MONITORING_ON_BOOT = "restoreMonitoringOnBoot";
    private static final String JSON_REQUEST_BATTERY_OPTIMIZATION_EXEMPTION =
        "requestBatteryOptimizationExemption";
    private static final String JSON_REMOTE_CONFIG_ENABLED = "remoteConfigEnabled";
    private static final String JSON_REMOTE_CONFIG_URL = "remoteConfigUrl";
    private static final String JSON_REMOTE_CONFIG_CACHE_TTL_MINUTES = "remoteConfigCacheTtlMinutes";
    private static final String JSON_MAX_RETRIES = "maxRetries";
    private static final String JSON_WAIT_FOR_VIDEO_ENABLED = "waitForVideoEnabled";
    private static final String JSON_LIVE_FROM_START_ENABLED = "liveFromStartEnabled";
    private static final String JSON_SKIP_UNAVAILABLE_FRAGMENTS_ENABLED =
        "skipUnavailableFragmentsEnabled";
    private static final String JSON_YTDLP_COOKIES_PATH = "ytDlpCookiesPath";
    private static final String JSON_YTDLP_COOKIE_HEADER = "ytDlpCookieHeader";
    private static final String JSON_YTDLP_EXTRACTOR_ARGS = "ytDlpExtractorArgs";
    private static final String JSON_YTDLP_PO_TOKEN_CLIENT = "ytDlpPoTokenClient";
    private static final String JSON_YTDLP_PO_TOKEN_VALUE = "ytDlpPoTokenValue";
    private static final String JSON_YTDLP_PO_TOKEN_TYPE = "ytDlpPoTokenType";
    private static final String JSON_YTDLP_PO_TOKEN_UPDATED_AT = "ytDlpPoTokenUpdatedAt";
    private static final String JSON_YTDLP_PO_TOKEN_SOURCE = "ytDlpPoTokenSource";
    private static final String JSON_YTDLP_PO_TOKEN_SESSION_BINDING = "ytDlpPoTokenSessionBinding";
    private static final String JSON_YTDLP_PO_TOKEN_VIDEO_ID = "ytDlpPoTokenVideoId";
    private static final String JSON_YTDLP_PO_TOKEN_PLAYER_URL = "ytDlpPoTokenPlayerUrl";
    private static final String JSON_TEMP_CLEANUP_BEFORE_RECORDING = "tempCleanupBeforeRecording";
    private static final String JSON_CONVERT_TS_TO_MP4 = "convertTsToMp4";
    private static final String JSON_RECOVER_ORPHAN_TS_FILES = "recoverOrphanTsFiles";
    private static final String JSON_LOG_RETENTION_LINES = "logRetentionLines";
    private static final String JSON_LOG_RETENTION_BYTES = "logRetentionBytes";
    private static final String JSON_LOG_UI_ENABLED = "logUiEnabled";
    private static final String JSON_LOG_SERVICE_ENABLED = "logServiceEnabled";
    private static final String JSON_LOG_RECORDER_ENABLED = "logRecorderEnabled";
    private static final String JSON_LOG_FFMPEG_ENABLED = "logFfmpegEnabled";
    private static final String JSON_LOG_NETWORK_ENABLED = "logNetworkEnabled";
    private static final String JSON_LOG_REMOTE_CONFIG_ENABLED = "logRemoteConfigEnabled";
    private static final String JSON_LOG_BOOT_ENABLED = "logBootEnabled";
    private static final String JSON_LOG_APP_ENABLED = "logAppEnabled";
    private static final String JSON_LOG_DEBUG_ENABLED = "logDebugEnabled";
    private static final String JSON_CREATED_AT = "createdAt";
    private static final String JSON_UPDATED_AT = "updatedAt";

    private int pollIntervalSeconds;
    private String downloadQuality;
    private String saveLocationUri;
    private String saveLocationDisplayName;

    /**
     * Scheduled monitoring uses minutes from midnight:
     * - 00:00 = 0
     * - 01:30 = 90
     * - 23:59 = 1439
     */
    private boolean scheduledMonitoringEnabled;
    private int scheduleStartMinutes;
    private int scheduleEndMinutes;
    private boolean allowCurrentRecordingOutsideSchedule;

    private boolean restoreMonitoringOnBoot;
    private boolean requestBatteryOptimizationExemption;

    private boolean remoteConfigEnabled;
    private String remoteConfigUrl;
    private int remoteConfigCacheTtlMinutes;

    private int maxRetries;
    private boolean waitForVideoEnabled;
    private boolean liveFromStartEnabled;
    private boolean skipUnavailableFragmentsEnabled;
    private String ytDlpCookiesPath;
    private String ytDlpCookieHeader;
    private String ytDlpExtractorArgs;
    private String ytDlpPoTokenClient;
    private String ytDlpPoTokenValue;
    private String ytDlpPoTokenType;
    private long ytDlpPoTokenUpdatedAt;
    private String ytDlpPoTokenSource;
    private String ytDlpPoTokenSessionBinding;
    private String ytDlpPoTokenVideoId;
    private String ytDlpPoTokenPlayerUrl;

    private boolean tempCleanupBeforeRecording;
    private boolean convertTsToMp4;
    private boolean recoverOrphanTsFiles;

    private int logRetentionLines;
    private int logRetentionBytes;
    private boolean logUiEnabled;
    private boolean logServiceEnabled;
    private boolean logRecorderEnabled;
    private boolean logFfmpegEnabled;
    private boolean logNetworkEnabled;
    private boolean logRemoteConfigEnabled;
    private boolean logBootEnabled;
    private boolean logAppEnabled;
    private boolean logDebugEnabled;

    private long createdAt;
    private long updatedAt;

    public AppSettings() {
        long now = System.currentTimeMillis();

        this.pollIntervalSeconds = 60;
        this.downloadQuality = QUALITY_480P;
        this.saveLocationUri = "";
        this.saveLocationDisplayName = "Default app recordings folder";

        this.scheduledMonitoringEnabled = false;
        this.scheduleStartMinutes = 0;
        this.scheduleEndMinutes = 1439;
        this.allowCurrentRecordingOutsideSchedule = true;

        this.restoreMonitoringOnBoot = true;
        this.requestBatteryOptimizationExemption = false;

        this.remoteConfigEnabled = true;
        this.remoteConfigUrl = "";
        this.remoteConfigCacheTtlMinutes = 360;

        this.maxRetries = 10;
        this.waitForVideoEnabled = true;
        this.liveFromStartEnabled = true;
        this.skipUnavailableFragmentsEnabled = true;
        this.ytDlpCookiesPath = "";
        this.ytDlpCookieHeader = "";
        this.ytDlpExtractorArgs = "";
        this.ytDlpPoTokenClient = "mweb";
        this.ytDlpPoTokenValue = "";
        this.ytDlpPoTokenType = "gvs";
        this.ytDlpPoTokenUpdatedAt = 0L;
        this.ytDlpPoTokenSource = "";
        this.ytDlpPoTokenSessionBinding = "";
        this.ytDlpPoTokenVideoId = "";
        this.ytDlpPoTokenPlayerUrl = "";

        this.tempCleanupBeforeRecording = true;
        this.convertTsToMp4 = true;
        this.recoverOrphanTsFiles = true;

        this.logRetentionLines = 2_000;
        this.logRetentionBytes = 2 * 1024 * 1024;
        this.logUiEnabled = true;
        this.logServiceEnabled = true;
        this.logRecorderEnabled = true;
        this.logFfmpegEnabled = false;
        this.logNetworkEnabled = true;
        this.logRemoteConfigEnabled = true;
        this.logBootEnabled = true;
        this.logAppEnabled = true;
        this.logDebugEnabled = false;

        this.createdAt = now;
        this.updatedAt = now;
    }

    public AppSettings(
        int pollIntervalSeconds,
        String downloadQuality,
        String saveLocationUri,
        String saveLocationDisplayName,
        boolean scheduledMonitoringEnabled,
        int scheduleStartMinutes,
        int scheduleEndMinutes,
        boolean allowCurrentRecordingOutsideSchedule,
        boolean restoreMonitoringOnBoot,
        boolean requestBatteryOptimizationExemption,
        boolean remoteConfigEnabled,
        String remoteConfigUrl,
        int remoteConfigCacheTtlMinutes,
        int maxRetries,
        boolean waitForVideoEnabled,
        boolean liveFromStartEnabled,
        boolean skipUnavailableFragmentsEnabled,
        String ytDlpCookiesPath,
        String ytDlpCookieHeader,
        String ytDlpExtractorArgs,
        String ytDlpPoTokenClient,
        String ytDlpPoTokenValue,
        String ytDlpPoTokenType,
        long ytDlpPoTokenUpdatedAt,
        String ytDlpPoTokenSource,
        String ytDlpPoTokenSessionBinding,
        String ytDlpPoTokenVideoId,
        String ytDlpPoTokenPlayerUrl,
        boolean tempCleanupBeforeRecording,
        boolean convertTsToMp4,
        boolean recoverOrphanTsFiles,
        int logRetentionLines,
        int logRetentionBytes,
        boolean logUiEnabled,
        boolean logServiceEnabled,
        boolean logRecorderEnabled,
        boolean logFfmpegEnabled,
        boolean logNetworkEnabled,
        boolean logRemoteConfigEnabled,
        boolean logBootEnabled,
        boolean logAppEnabled,
        boolean logDebugEnabled,
        long createdAt,
        long updatedAt
    ) {
        this.pollIntervalSeconds = clampPollInterval(pollIntervalSeconds);
        this.downloadQuality = normalizeQuality(downloadQuality);
        this.saveLocationUri = nullToEmpty(saveLocationUri);
        this.saveLocationDisplayName = isBlank(saveLocationDisplayName)
            ? "Default app recordings folder"
            : saveLocationDisplayName.trim();

        this.scheduledMonitoringEnabled = scheduledMonitoringEnabled;
        this.scheduleStartMinutes = clampMinuteOfDay(scheduleStartMinutes);
        this.scheduleEndMinutes = clampMinuteOfDay(scheduleEndMinutes);
        this.allowCurrentRecordingOutsideSchedule = allowCurrentRecordingOutsideSchedule;

        this.restoreMonitoringOnBoot = restoreMonitoringOnBoot;
        this.requestBatteryOptimizationExemption = requestBatteryOptimizationExemption;

        this.remoteConfigEnabled = remoteConfigEnabled;
        this.remoteConfigUrl = nullToEmpty(remoteConfigUrl);
        this.remoteConfigCacheTtlMinutes = Math.max(5, remoteConfigCacheTtlMinutes);

        this.maxRetries = clampMaxRetries(maxRetries);
        this.waitForVideoEnabled = waitForVideoEnabled;
        this.liveFromStartEnabled = liveFromStartEnabled;
        this.skipUnavailableFragmentsEnabled = skipUnavailableFragmentsEnabled;
        this.ytDlpCookiesPath = nullToEmpty(ytDlpCookiesPath).trim();
        this.ytDlpCookieHeader = nullToEmpty(ytDlpCookieHeader).trim();
        this.ytDlpExtractorArgs = nullToEmpty(ytDlpExtractorArgs).trim();
        this.ytDlpPoTokenClient = normalizePoTokenClient(ytDlpPoTokenClient);
        this.ytDlpPoTokenValue = normalizePoTokenValue(ytDlpPoTokenValue);
        this.ytDlpPoTokenType = normalizePoTokenType(ytDlpPoTokenType);
        this.ytDlpPoTokenUpdatedAt = Math.max(0L, ytDlpPoTokenUpdatedAt);
        this.ytDlpPoTokenSource = nullToEmpty(ytDlpPoTokenSource).trim();
        this.ytDlpPoTokenSessionBinding = nullToEmpty(ytDlpPoTokenSessionBinding).trim();
        this.ytDlpPoTokenVideoId = normalizeVideoId(ytDlpPoTokenVideoId);
        this.ytDlpPoTokenPlayerUrl = nullToEmpty(ytDlpPoTokenPlayerUrl).trim();

        this.tempCleanupBeforeRecording = tempCleanupBeforeRecording;
        this.convertTsToMp4 = convertTsToMp4;
        this.recoverOrphanTsFiles = recoverOrphanTsFiles;

        this.logRetentionLines = Math.max(100, logRetentionLines);
        this.logRetentionBytes = Math.max(64 * 1024, logRetentionBytes);
        this.logUiEnabled = logUiEnabled;
        this.logServiceEnabled = logServiceEnabled;
        this.logRecorderEnabled = logRecorderEnabled;
        this.logFfmpegEnabled = logFfmpegEnabled;
        this.logNetworkEnabled = logNetworkEnabled;
        this.logRemoteConfigEnabled = logRemoteConfigEnabled;
        this.logBootEnabled = logBootEnabled;
        this.logAppEnabled = logAppEnabled;
        this.logDebugEnabled = logDebugEnabled;

        this.createdAt = createdAt <= 0L ? System.currentTimeMillis() : createdAt;
        this.updatedAt = updatedAt <= 0L ? System.currentTimeMillis() : updatedAt;
    }

    public static AppSettings fromJson(JSONObject json) throws JSONException {
        if (json == null) {
            return new AppSettings();
        }

        AppSettings defaults = new AppSettings();

        return new AppSettings(
            json.optInt(JSON_POLL_INTERVAL_SECONDS, defaults.getPollIntervalSeconds()),
            json.optString(JSON_DOWNLOAD_QUALITY, defaults.getDownloadQuality()),
            json.optString(JSON_SAVE_LOCATION_URI, defaults.getSaveLocationUri()),
            json.optString(JSON_SAVE_LOCATION_DISPLAY_NAME, defaults.getSaveLocationDisplayName()),
            json.optBoolean(
                JSON_SCHEDULED_MONITORING_ENABLED,
                defaults.isScheduledMonitoringEnabled()
            ),
            json.optInt(JSON_SCHEDULE_START_MINUTES, defaults.getScheduleStartMinutes()),
            json.optInt(JSON_SCHEDULE_END_MINUTES, defaults.getScheduleEndMinutes()),
            json.optBoolean(
                JSON_ALLOW_CURRENT_RECORDING_OUTSIDE_SCHEDULE,
                defaults.isAllowCurrentRecordingOutsideSchedule()
            ),
            json.optBoolean(JSON_RESTORE_MONITORING_ON_BOOT, defaults.isRestoreMonitoringOnBoot()),
            json.optBoolean(
                JSON_REQUEST_BATTERY_OPTIMIZATION_EXEMPTION,
                defaults.isRequestBatteryOptimizationExemption()
            ),
            json.optBoolean(JSON_REMOTE_CONFIG_ENABLED, defaults.isRemoteConfigEnabled()),
            json.optString(JSON_REMOTE_CONFIG_URL, defaults.getRemoteConfigUrl()),
            json.optInt(
                JSON_REMOTE_CONFIG_CACHE_TTL_MINUTES,
                defaults.getRemoteConfigCacheTtlMinutes()
            ),
            json.optInt(JSON_MAX_RETRIES, defaults.getMaxRetries()),
            json.optBoolean(JSON_WAIT_FOR_VIDEO_ENABLED, defaults.isWaitForVideoEnabled()),
            json.optBoolean(JSON_LIVE_FROM_START_ENABLED, defaults.isLiveFromStartEnabled()),
            json.optBoolean(
                JSON_SKIP_UNAVAILABLE_FRAGMENTS_ENABLED,
                defaults.isSkipUnavailableFragmentsEnabled()
            ),
            json.optString(JSON_YTDLP_COOKIES_PATH, defaults.getYtDlpCookiesPath()),
            json.optString(JSON_YTDLP_COOKIE_HEADER, defaults.getYtDlpCookieHeader()),
            json.optString(JSON_YTDLP_EXTRACTOR_ARGS, defaults.getYtDlpExtractorArgs()),
            json.optString(JSON_YTDLP_PO_TOKEN_CLIENT, defaults.getYtDlpPoTokenClient()),
            json.optString(JSON_YTDLP_PO_TOKEN_VALUE, defaults.getYtDlpPoTokenValue()),
            json.optString(JSON_YTDLP_PO_TOKEN_TYPE, defaults.getYtDlpPoTokenType()),
            json.optLong(JSON_YTDLP_PO_TOKEN_UPDATED_AT, defaults.getYtDlpPoTokenUpdatedAt()),
            json.optString(JSON_YTDLP_PO_TOKEN_SOURCE, defaults.getYtDlpPoTokenSource()),
            json.optString(
                JSON_YTDLP_PO_TOKEN_SESSION_BINDING,
                defaults.getYtDlpPoTokenSessionBinding()
            ),
            json.optString(JSON_YTDLP_PO_TOKEN_VIDEO_ID, defaults.getYtDlpPoTokenVideoId()),
            json.optString(JSON_YTDLP_PO_TOKEN_PLAYER_URL, defaults.getYtDlpPoTokenPlayerUrl()),
            json.optBoolean(
                JSON_TEMP_CLEANUP_BEFORE_RECORDING,
                defaults.isTempCleanupBeforeRecording()
            ),
            json.optBoolean(JSON_CONVERT_TS_TO_MP4, defaults.isConvertTsToMp4()),
            json.optBoolean(JSON_RECOVER_ORPHAN_TS_FILES, defaults.isRecoverOrphanTsFiles()),
            json.optInt(JSON_LOG_RETENTION_LINES, defaults.getLogRetentionLines()),
            json.optInt(JSON_LOG_RETENTION_BYTES, defaults.getLogRetentionBytes()),
            json.optBoolean(JSON_LOG_UI_ENABLED, defaults.isLogUiEnabled()),
            json.optBoolean(JSON_LOG_SERVICE_ENABLED, defaults.isLogServiceEnabled()),
            json.optBoolean(JSON_LOG_RECORDER_ENABLED, defaults.isLogRecorderEnabled()),
            json.optBoolean(JSON_LOG_FFMPEG_ENABLED, defaults.isLogFfmpegEnabled()),
            json.optBoolean(JSON_LOG_NETWORK_ENABLED, defaults.isLogNetworkEnabled()),
            json.optBoolean(JSON_LOG_REMOTE_CONFIG_ENABLED, defaults.isLogRemoteConfigEnabled()),
            json.optBoolean(JSON_LOG_BOOT_ENABLED, defaults.isLogBootEnabled()),
            json.optBoolean(JSON_LOG_APP_ENABLED, defaults.isLogAppEnabled()),
            json.optBoolean(JSON_LOG_DEBUG_ENABLED, defaults.isLogDebugEnabled()),
            json.optLong(JSON_CREATED_AT, System.currentTimeMillis()),
            json.optLong(JSON_UPDATED_AT, System.currentTimeMillis())
        );
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();

        json.put(JSON_POLL_INTERVAL_SECONDS, pollIntervalSeconds);
        json.put(JSON_DOWNLOAD_QUALITY, downloadQuality);
        json.put(JSON_SAVE_LOCATION_URI, saveLocationUri);
        json.put(JSON_SAVE_LOCATION_DISPLAY_NAME, saveLocationDisplayName);
        json.put(JSON_SCHEDULED_MONITORING_ENABLED, scheduledMonitoringEnabled);
        json.put(JSON_SCHEDULE_START_MINUTES, scheduleStartMinutes);
        json.put(JSON_SCHEDULE_END_MINUTES, scheduleEndMinutes);
        json.put(
            JSON_ALLOW_CURRENT_RECORDING_OUTSIDE_SCHEDULE,
            allowCurrentRecordingOutsideSchedule
        );
        json.put(JSON_RESTORE_MONITORING_ON_BOOT, restoreMonitoringOnBoot);
        json.put(
            JSON_REQUEST_BATTERY_OPTIMIZATION_EXEMPTION,
            requestBatteryOptimizationExemption
        );
        json.put(JSON_REMOTE_CONFIG_ENABLED, remoteConfigEnabled);
        json.put(JSON_REMOTE_CONFIG_URL, remoteConfigUrl);
        json.put(JSON_REMOTE_CONFIG_CACHE_TTL_MINUTES, remoteConfigCacheTtlMinutes);
        json.put(JSON_MAX_RETRIES, maxRetries);
        json.put(JSON_WAIT_FOR_VIDEO_ENABLED, waitForVideoEnabled);
        json.put(JSON_LIVE_FROM_START_ENABLED, liveFromStartEnabled);
        json.put(JSON_SKIP_UNAVAILABLE_FRAGMENTS_ENABLED, skipUnavailableFragmentsEnabled);
        json.put(JSON_YTDLP_COOKIES_PATH, ytDlpCookiesPath);
        json.put(JSON_YTDLP_COOKIE_HEADER, ytDlpCookieHeader);
        json.put(JSON_YTDLP_EXTRACTOR_ARGS, ytDlpExtractorArgs);
        json.put(JSON_YTDLP_PO_TOKEN_CLIENT, ytDlpPoTokenClient);
        json.put(JSON_YTDLP_PO_TOKEN_VALUE, ytDlpPoTokenValue);
        json.put(JSON_YTDLP_PO_TOKEN_TYPE, ytDlpPoTokenType);
        json.put(JSON_YTDLP_PO_TOKEN_UPDATED_AT, ytDlpPoTokenUpdatedAt);
        json.put(JSON_YTDLP_PO_TOKEN_SOURCE, ytDlpPoTokenSource);
        json.put(JSON_YTDLP_PO_TOKEN_SESSION_BINDING, ytDlpPoTokenSessionBinding);
        json.put(JSON_YTDLP_PO_TOKEN_VIDEO_ID, ytDlpPoTokenVideoId);
        json.put(JSON_YTDLP_PO_TOKEN_PLAYER_URL, ytDlpPoTokenPlayerUrl);
        json.put(JSON_TEMP_CLEANUP_BEFORE_RECORDING, tempCleanupBeforeRecording);
        json.put(JSON_CONVERT_TS_TO_MP4, convertTsToMp4);
        json.put(JSON_RECOVER_ORPHAN_TS_FILES, recoverOrphanTsFiles);
        json.put(JSON_LOG_RETENTION_LINES, logRetentionLines);
        json.put(JSON_LOG_RETENTION_BYTES, logRetentionBytes);
        json.put(JSON_LOG_UI_ENABLED, logUiEnabled);
        json.put(JSON_LOG_SERVICE_ENABLED, logServiceEnabled);
        json.put(JSON_LOG_RECORDER_ENABLED, logRecorderEnabled);
        json.put(JSON_LOG_FFMPEG_ENABLED, logFfmpegEnabled);
        json.put(JSON_LOG_NETWORK_ENABLED, logNetworkEnabled);
        json.put(JSON_LOG_REMOTE_CONFIG_ENABLED, logRemoteConfigEnabled);
        json.put(JSON_LOG_BOOT_ENABLED, logBootEnabled);
        json.put(JSON_LOG_APP_ENABLED, logAppEnabled);
        json.put(JSON_LOG_DEBUG_ENABLED, logDebugEnabled);
        json.put(JSON_CREATED_AT, createdAt);
        json.put(JSON_UPDATED_AT, updatedAt);

        return json;
    }

    public boolean isInsideScheduleNow() {
        return isMinuteInsideSchedule(getCurrentMinuteOfDay());
    }

    public boolean isMinuteInsideSchedule(int minuteOfDay) {
        int minute = clampMinuteOfDay(minuteOfDay);
        int start = clampMinuteOfDay(scheduleStartMinutes);
        int end = clampMinuteOfDay(scheduleEndMinutes);

        if (!scheduledMonitoringEnabled) {
            return true;
        }

        if (start == end) {
            return true;
        }

        if (start < end) {
            return minute >= start && minute <= end;
        }

        /*
         * Overnight schedule, for example:
         * start = 22:00, end = 06:00
         */
        return minute >= start || minute <= end;
    }

    public boolean canStartNewRecordingNow() {
        return !scheduledMonitoringEnabled || isInsideScheduleNow();
    }

    public boolean canContinueExistingRecordingNow() {
        return !scheduledMonitoringEnabled
            || allowCurrentRecordingOutsideSchedule
            || isInsideScheduleNow();
    }

    public long getPollIntervalMillis() {
        return pollIntervalSeconds * 1_000L;
    }

    public String getQualityHeightOnly() {
        if (QUALITY_360P.equals(downloadQuality)) {
            return "360";
        }

        if (QUALITY_480P.equals(downloadQuality)) {
            return "480";
        }

        if (QUALITY_720P.equals(downloadQuality)) {
            return "720";
        }

        if (QUALITY_1080P.equals(downloadQuality)) {
            return "1080";
        }

        return "";
    }

    public String buildYtDlpFormatSelector() {
        if (QUALITY_BEST.equals(downloadQuality)) {
            /*
             * Prefer a single muxed stream. Avoid separate video/audio formats.
             */
            return "best";
        }

        String height = getQualityHeightOnly();

        if (height.isEmpty()) {
            return "best";
        }

        /*
         * Prefer one complete stream up to the selected height.
         * Avoid bestvideo+bestaudio because user requested no separate streams.
         */
        return "best[height<=" + height + "]/best";
    }

    public String buildRetrySummary() {
        return "maxRetries=" + maxRetries
            + ", waitForVideo=" + waitForVideoEnabled
            + ", liveFromStart=" + liveFromStartEnabled
            + ", skipUnavailableFragments=" + skipUnavailableFragmentsEnabled;
    }

    public boolean isLogItemEnabled(LogItem log) {
        if (log == null) {
            return false;
        }

        if (LogItem.LEVEL_DEBUG.equals(log.getLevel()) && !logDebugEnabled) {
            return false;
        }

        String source = log.getSource();

        if (LogItem.SOURCE_UI.equals(source)) {
            return logUiEnabled;
        }

        if (LogItem.SOURCE_SERVICE.equals(source)) {
            return logServiceEnabled;
        }

        if (LogItem.SOURCE_RECORDER.equals(source)) {
            return logRecorderEnabled;
        }

        if (LogItem.SOURCE_FFMPEG.equals(source)) {
            return logFfmpegEnabled;
        }

        if (LogItem.SOURCE_NETWORK.equals(source)) {
            return logNetworkEnabled;
        }

        if (LogItem.SOURCE_REMOTE_CONFIG.equals(source)) {
            return logRemoteConfigEnabled;
        }

        if (LogItem.SOURCE_BOOT.equals(source)) {
            return logBootEnabled;
        }

        if (LogItem.SOURCE_APP.equals(source)) {
            return logAppEnabled;
        }

        return true;
    }

    private void touch() {
        updatedAt = System.currentTimeMillis();
    }

    private static int getCurrentMinuteOfDay() {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        int hour = calendar.get(java.util.Calendar.HOUR_OF_DAY);
        int minute = calendar.get(java.util.Calendar.MINUTE);
        return hour * 60 + minute;
    }

    private static int clampPollInterval(int value) {
        if (value < 15) {
            return 15;
        }

        if (value > 3600) {
            return 3600;
        }

        return value;
    }

    private static int clampMinuteOfDay(int value) {
        if (value < 0) {
            return 0;
        }

        if (value > 1439) {
            return 1439;
        }

        return value;
    }

    private static int clampMaxRetries(int value) {
        if (value < 0) {
            return 0;
        }

        if (value > 50) {
            return 50;
        }

        return value;
    }

    public static String normalizeQuality(String value) {
        if (isBlank(value)) {
            return QUALITY_480P;
        }

        String quality = value.trim().toLowerCase();

        if (QUALITY_360P.equals(quality)) {
            return QUALITY_360P;
        }

        if (QUALITY_480P.equals(quality)) {
            return QUALITY_480P;
        }

        if (QUALITY_720P.equals(quality)) {
            return QUALITY_720P;
        }

        if (QUALITY_1080P.equals(quality)) {
            return QUALITY_1080P;
        }

        if (QUALITY_BEST.equals(quality)) {
            return QUALITY_BEST;
        }

        return QUALITY_480P;
    }

    public static String minutesToTimeLabel(int minutesFromMidnight) {
        int safeMinutes = clampMinuteOfDay(minutesFromMidnight);
        int hour = safeMinutes / 60;
        int minute = safeMinutes % 60;

        return String.format(java.util.Locale.US, "%02d:%02d", hour, minute);
    }

    public static int timeToMinutes(String value) {
        if (isBlank(value)) {
            return 0;
        }

        String[] parts = value.trim().split(":");

        if (parts.length != 2) {
            return 0;
        }

        try {
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            return clampMinuteOfDay(hour * 60 + minute);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String normalizePoTokenClient(String client) {
        String normalized = nullToEmpty(client).trim().toLowerCase(java.util.Locale.US);

        if (normalized.isEmpty()) {
            return "mweb";
        }

        int dotIndex = normalized.indexOf('.');

        if (dotIndex > 0) {
            normalized = normalized.substring(0, dotIndex);
        }

        return normalized.replaceAll("[^a-z0-9_ -]", "").replace(' ', '_');
    }

    private static String normalizePoTokenType(String tokenType) {
        String normalized = nullToEmpty(tokenType).trim().toLowerCase(java.util.Locale.US);
        return normalized.isEmpty() ? "gvs" : normalized.replaceAll("[^a-z0-9_ -]", "").replace(' ', '_');
    }

    private static String normalizeVideoId(String videoId) {
        String normalized = nullToEmpty(videoId).trim();
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }

    private static String normalizePoTokenValue(String token) {
        String normalized = nullToEmpty(token).trim();

        if (normalized.toLowerCase(java.util.Locale.US).startsWith("po_token=")) {
            normalized = normalized.substring("po_token=".length()).trim();
        }

        int plusIndex = normalized.indexOf('+');

        if (plusIndex >= 0 && plusIndex + 1 < normalized.length()) {
            normalized = normalized.substring(plusIndex + 1).trim();
        }

        if ("TOKEN".equalsIgnoreCase(normalized) || normalized.contains("...")) {
            return "";
        }

        return normalized;
    }

    private static String buildPoTokenSpec(String client, String token) {
        String normalizedClient = normalizePoTokenClient(client);
        String normalizedToken = normalizePoTokenValue(token);
        return normalizedClient + ".gvs+" + normalizedToken;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public int getPollIntervalSeconds() {
        return pollIntervalSeconds;
    }

    public String getDownloadQuality() {
        return downloadQuality;
    }

    public String getSaveLocationUri() {
        return saveLocationUri;
    }

    public String getSaveLocationDisplayName() {
        return saveLocationDisplayName;
    }

    public boolean isScheduledMonitoringEnabled() {
        return scheduledMonitoringEnabled;
    }

    public int getScheduleStartMinutes() {
        return scheduleStartMinutes;
    }

    public int getScheduleEndMinutes() {
        return scheduleEndMinutes;
    }

    public boolean isAllowCurrentRecordingOutsideSchedule() {
        return allowCurrentRecordingOutsideSchedule;
    }

    public boolean isRestoreMonitoringOnBoot() {
        return restoreMonitoringOnBoot;
    }

    public boolean isRequestBatteryOptimizationExemption() {
        return requestBatteryOptimizationExemption;
    }

    public boolean isRemoteConfigEnabled() {
        return remoteConfigEnabled;
    }

    public String getRemoteConfigUrl() {
        return remoteConfigUrl;
    }

    public int getRemoteConfigCacheTtlMinutes() {
        return remoteConfigCacheTtlMinutes;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public boolean isWaitForVideoEnabled() {
        return waitForVideoEnabled;
    }

    public boolean isLiveFromStartEnabled() {
        return liveFromStartEnabled;
    }

    public boolean isSkipUnavailableFragmentsEnabled() {
        return skipUnavailableFragmentsEnabled;
    }

    public String getYtDlpCookiesPath() {
        return ytDlpCookiesPath;
    }

    public String getYtDlpCookieHeader() {
        return ytDlpCookieHeader;
    }

    public String getYtDlpExtractorArgs() {
        return ytDlpExtractorArgs;
    }

    public String getYtDlpPoTokenClient() {
        return ytDlpPoTokenClient;
    }

    public String getYtDlpPoTokenValue() {
        return ytDlpPoTokenValue;
    }

    public String getYtDlpPoTokenType() {
        return ytDlpPoTokenType;
    }

    public long getYtDlpPoTokenUpdatedAt() {
        return ytDlpPoTokenUpdatedAt;
    }

    public String getYtDlpPoTokenSource() {
        return ytDlpPoTokenSource;
    }

    public String getYtDlpPoTokenSessionBinding() {
        return ytDlpPoTokenSessionBinding;
    }

    public String getYtDlpPoTokenVideoId() {
        return ytDlpPoTokenVideoId;
    }

    public String getYtDlpPoTokenPlayerUrl() {
        return ytDlpPoTokenPlayerUrl;
    }

    public boolean hasYtDlpPoToken() {
        return !isBlank(ytDlpPoTokenClient) && !isBlank(ytDlpPoTokenValue);
    }

    public boolean isYtDlpPoTokenRefreshNeeded(long nowMillis) {
        if (!hasYtDlpPoToken() || ytDlpPoTokenUpdatedAt <= 0L) {
            return true;
        }

        return nowMillis - ytDlpPoTokenUpdatedAt >= getYtDlpPoTokenRefreshIntervalMillis();
    }

    public long getYtDlpPoTokenRefreshIntervalMillis() {
        return 6L * 60L * 60L * 1000L;
    }

    public String buildYtDlpPoTokenExtractorArgs() {
        if (!hasYtDlpPoToken()) {
            return "";
        }

        String client = normalizePoTokenClient(ytDlpPoTokenClient);
        String token = normalizePoTokenValue(ytDlpPoTokenValue);
        String tokenType = normalizePoTokenType(ytDlpPoTokenType);

        if (isBlank(client) || isBlank(token) || isBlank(tokenType)) {
            return "";
        }

        return "youtube:player_client="
            + client
            + ";po_token="
            + client
            + "."
            + tokenType
            + "+"
            + token;
    }

    public boolean hasYtDlpCookies() {
        return !isBlank(ytDlpCookiesPath) || !isBlank(ytDlpCookieHeader);
    }

    public boolean isTempCleanupBeforeRecording() {
        return tempCleanupBeforeRecording;
    }

    public boolean isConvertTsToMp4() {
        return convertTsToMp4;
    }

    public boolean isRecoverOrphanTsFiles() {
        return recoverOrphanTsFiles;
    }

    public int getLogRetentionLines() {
        return logRetentionLines;
    }

    public int getLogRetentionBytes() {
        return logRetentionBytes;
    }

    public boolean isLogUiEnabled() {
        return logUiEnabled;
    }

    public boolean isLogServiceEnabled() {
        return logServiceEnabled;
    }

    public boolean isLogRecorderEnabled() {
        return logRecorderEnabled;
    }

    public boolean isLogFfmpegEnabled() {
        return logFfmpegEnabled;
    }

    public boolean isLogNetworkEnabled() {
        return logNetworkEnabled;
    }

    public boolean isLogRemoteConfigEnabled() {
        return logRemoteConfigEnabled;
    }

    public boolean isLogBootEnabled() {
        return logBootEnabled;
    }

    public boolean isLogAppEnabled() {
        return logAppEnabled;
    }

    public boolean isLogDebugEnabled() {
        return logDebugEnabled;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setPollIntervalSeconds(int pollIntervalSeconds) {
        this.pollIntervalSeconds = clampPollInterval(pollIntervalSeconds);
        touch();
    }

    public void setDownloadQuality(String downloadQuality) {
        this.downloadQuality = normalizeQuality(downloadQuality);
        touch();
    }

    public void setSaveLocation(String saveLocationUri, String saveLocationDisplayName) {
        this.saveLocationUri = nullToEmpty(saveLocationUri);
        this.saveLocationDisplayName = isBlank(saveLocationDisplayName)
            ? "Default app recordings folder"
            : saveLocationDisplayName.trim();
        touch();
    }

    public void setScheduledMonitoringEnabled(boolean scheduledMonitoringEnabled) {
        this.scheduledMonitoringEnabled = scheduledMonitoringEnabled;
        touch();
    }

    public void setScheduleWindow(int scheduleStartMinutes, int scheduleEndMinutes) {
        this.scheduleStartMinutes = clampMinuteOfDay(scheduleStartMinutes);
        this.scheduleEndMinutes = clampMinuteOfDay(scheduleEndMinutes);
        touch();
    }

    public void setAllowCurrentRecordingOutsideSchedule(boolean allowCurrentRecordingOutsideSchedule) {
        this.allowCurrentRecordingOutsideSchedule = allowCurrentRecordingOutsideSchedule;
        touch();
    }

    public void setRestoreMonitoringOnBoot(boolean restoreMonitoringOnBoot) {
        this.restoreMonitoringOnBoot = restoreMonitoringOnBoot;
        touch();
    }

    public void setRequestBatteryOptimizationExemption(
        boolean requestBatteryOptimizationExemption
    ) {
        this.requestBatteryOptimizationExemption = requestBatteryOptimizationExemption;
        touch();
    }

    public void setRemoteConfigEnabled(boolean remoteConfigEnabled) {
        this.remoteConfigEnabled = remoteConfigEnabled;
        touch();
    }

    public void setRemoteConfigUrl(String remoteConfigUrl) {
        this.remoteConfigUrl = nullToEmpty(remoteConfigUrl);
        touch();
    }

    public void setRemoteConfigCacheTtlMinutes(int remoteConfigCacheTtlMinutes) {
        this.remoteConfigCacheTtlMinutes = Math.max(5, remoteConfigCacheTtlMinutes);
        touch();
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = clampMaxRetries(maxRetries);
        touch();
    }

    public void setWaitForVideoEnabled(boolean waitForVideoEnabled) {
        this.waitForVideoEnabled = waitForVideoEnabled;
        touch();
    }

    public void setLiveFromStartEnabled(boolean liveFromStartEnabled) {
        this.liveFromStartEnabled = liveFromStartEnabled;
        touch();
    }

    public void setSkipUnavailableFragmentsEnabled(boolean skipUnavailableFragmentsEnabled) {
        this.skipUnavailableFragmentsEnabled = skipUnavailableFragmentsEnabled;
        touch();
    }

    public void setYtDlpCookiesPath(String ytDlpCookiesPath) {
        this.ytDlpCookiesPath = nullToEmpty(ytDlpCookiesPath).trim();
        touch();
    }

    public void setYtDlpCookieHeader(String ytDlpCookieHeader) {
        this.ytDlpCookieHeader = nullToEmpty(ytDlpCookieHeader).trim();
        touch();
    }

    public void setYtDlpExtractorArgs(String ytDlpExtractorArgs) {
        this.ytDlpExtractorArgs = nullToEmpty(ytDlpExtractorArgs).trim();
        touch();
    }

    public void setYtDlpPoTokenClient(String ytDlpPoTokenClient) {
        this.ytDlpPoTokenClient = normalizePoTokenClient(ytDlpPoTokenClient);
        touch();
    }

    public void setYtDlpPoTokenValue(String ytDlpPoTokenValue) {
        this.ytDlpPoTokenValue = normalizePoTokenValue(ytDlpPoTokenValue);
        touch();
    }

    public void setYtDlpPoTokenType(String ytDlpPoTokenType) {
        this.ytDlpPoTokenType = normalizePoTokenType(ytDlpPoTokenType);
        touch();
    }

    public void setYtDlpPoTokenMetadata(
        String tokenType,
        long updatedAt,
        String source,
        String sessionBinding,
        String videoId,
        String playerUrl
    ) {
        this.ytDlpPoTokenType = normalizePoTokenType(tokenType);
        this.ytDlpPoTokenUpdatedAt = Math.max(0L, updatedAt);
        this.ytDlpPoTokenSource = nullToEmpty(source).trim();
        this.ytDlpPoTokenSessionBinding = nullToEmpty(sessionBinding).trim();
        this.ytDlpPoTokenVideoId = normalizeVideoId(videoId);
        this.ytDlpPoTokenPlayerUrl = nullToEmpty(playerUrl).trim();
        touch();
    }

    public void clearYtDlpPoToken() {
        this.ytDlpPoTokenValue = "";
        setYtDlpPoTokenMetadata("gvs", 0L, "", "", "", "");
    }

    public void setTempCleanupBeforeRecording(boolean tempCleanupBeforeRecording) {
        this.tempCleanupBeforeRecording = tempCleanupBeforeRecording;
        touch();
    }

    public void setConvertTsToMp4(boolean convertTsToMp4) {
        this.convertTsToMp4 = convertTsToMp4;
        touch();
    }

    public void setRecoverOrphanTsFiles(boolean recoverOrphanTsFiles) {
        this.recoverOrphanTsFiles = recoverOrphanTsFiles;
        touch();
    }

    public void setLogRetentionLines(int logRetentionLines) {
        this.logRetentionLines = Math.max(100, logRetentionLines);
        touch();
    }

    public void setLogRetentionBytes(int logRetentionBytes) {
        this.logRetentionBytes = Math.max(64 * 1024, logRetentionBytes);
        touch();
    }

    public void setLogUiEnabled(boolean logUiEnabled) {
        this.logUiEnabled = logUiEnabled;
        touch();
    }

    public void setLogServiceEnabled(boolean logServiceEnabled) {
        this.logServiceEnabled = logServiceEnabled;
        touch();
    }

    public void setLogRecorderEnabled(boolean logRecorderEnabled) {
        this.logRecorderEnabled = logRecorderEnabled;
        touch();
    }

    public void setLogFfmpegEnabled(boolean logFfmpegEnabled) {
        this.logFfmpegEnabled = logFfmpegEnabled;
        touch();
    }

    public void setLogNetworkEnabled(boolean logNetworkEnabled) {
        this.logNetworkEnabled = logNetworkEnabled;
        touch();
    }

    public void setLogRemoteConfigEnabled(boolean logRemoteConfigEnabled) {
        this.logRemoteConfigEnabled = logRemoteConfigEnabled;
        touch();
    }

    public void setLogBootEnabled(boolean logBootEnabled) {
        this.logBootEnabled = logBootEnabled;
        touch();
    }

    public void setLogAppEnabled(boolean logAppEnabled) {
        this.logAppEnabled = logAppEnabled;
        touch();
    }

    public void setLogDebugEnabled(boolean logDebugEnabled) {
        this.logDebugEnabled = logDebugEnabled;
        touch();
    }
}
