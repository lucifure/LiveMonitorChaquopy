package com.livemonitor.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * Represents one structured log line.
 *
 * Supports:
 * - Global log screen
 * - Per-channel log screen
 * - Per-recording log filtering
 * - Tagged logs such as [Channel Name]
 * - Copy log / clear log behavior
 * - JSON persistence
 */
public class LogItem {

    public static final String LEVEL_DEBUG = "DEBUG";
    public static final String LEVEL_INFO = "INFO";
    public static final String LEVEL_SUCCESS = "SUCCESS";
    public static final String LEVEL_WARNING = "WARNING";
    public static final String LEVEL_ERROR = "ERROR";

    public static final String SOURCE_APP = "APP";
    public static final String SOURCE_UI = "UI";
    public static final String SOURCE_SERVICE = "SERVICE";
    public static final String SOURCE_RECORDER = "RECORDER";
    public static final String SOURCE_NETWORK = "NETWORK";
    public static final String SOURCE_REMOTE_CONFIG = "REMOTE_CONFIG";
    public static final String SOURCE_FFMPEG = "FFMPEG";
    public static final String SOURCE_BOOT = "BOOT";
    public static final String SOURCE_STORAGE = "STORAGE";

    private static final String JSON_ID = "id";
    private static final String JSON_TIMESTAMP = "timestamp";
    private static final String JSON_LEVEL = "level";
    private static final String JSON_SOURCE = "source";
    private static final String JSON_CHANNEL_ID = "channelId";
    private static final String JSON_CHANNEL_TITLE = "channelTitle";
    private static final String JSON_RECORDING_ID = "recordingId";
    private static final String JSON_VIDEO_ID = "videoId";
    private static final String JSON_MESSAGE = "message";
    private static final String JSON_DETAILS = "details";
    private static final String JSON_COPYABLE = "copyable";

    private String id;
    private long timestamp;
    private String level;
    private String source;
    private String channelId;
    private String channelTitle;
    private String recordingId;
    private String videoId;
    private String message;
    private String details;
    private boolean copyable;

    public LogItem(
        String level,
        String source,
        String channelId,
        String channelTitle,
        String recordingId,
        String videoId,
        String message,
        String details
    ) {
        this.id = UUID.randomUUID().toString();
        this.timestamp = System.currentTimeMillis();
        this.level = normalizeLevel(level);
        this.source = normalizeSource(source);
        this.channelId = nullToEmpty(channelId);
        this.channelTitle = nullToEmpty(channelTitle);
        this.recordingId = nullToEmpty(recordingId);
        this.videoId = nullToEmpty(videoId);
        this.message = nullToEmpty(message);
        this.details = nullToEmpty(details);
        this.copyable = true;
    }

    public LogItem(
        String id,
        long timestamp,
        String level,
        String source,
        String channelId,
        String channelTitle,
        String recordingId,
        String videoId,
        String message,
        String details,
        boolean copyable
    ) {
        this.id = isBlank(id) ? UUID.randomUUID().toString() : id;
        this.timestamp = timestamp <= 0L ? System.currentTimeMillis() : timestamp;
        this.level = normalizeLevel(level);
        this.source = normalizeSource(source);
        this.channelId = nullToEmpty(channelId);
        this.channelTitle = nullToEmpty(channelTitle);
        this.recordingId = nullToEmpty(recordingId);
        this.videoId = nullToEmpty(videoId);
        this.message = nullToEmpty(message);
        this.details = nullToEmpty(details);
        this.copyable = copyable;
    }

    public static LogItem debug(String source, String message) {
        return new LogItem(
            LEVEL_DEBUG,
            source,
            "",
            "",
            "",
            "",
            message,
            ""
        );
    }

    public static LogItem info(String source, String message) {
        return new LogItem(
            LEVEL_INFO,
            source,
            "",
            "",
            "",
            "",
            message,
            ""
        );
    }

    public static LogItem success(String source, String message) {
        return new LogItem(
            LEVEL_SUCCESS,
            source,
            "",
            "",
            "",
            "",
            message,
            ""
        );
    }

    public static LogItem warning(String source, String message) {
        return new LogItem(
            LEVEL_WARNING,
            source,
            "",
            "",
            "",
            "",
            message,
            ""
        );
    }

    public static LogItem error(String source, String message, String details) {
        return new LogItem(
            LEVEL_ERROR,
            source,
            "",
            "",
            "",
            "",
            message,
            details
        );
    }

    public static LogItem channel(
        String level,
        String source,
        ChannelItem channel,
        String message
    ) {
        String channelId = channel == null ? "" : channel.getId();
        String channelTitle = channel == null ? "" : channel.getDisplayTitle();
        String videoId = channel == null ? "" : channel.getCurrentVideoId();

        return new LogItem(
            level,
            source,
            channelId,
            channelTitle,
            "",
            videoId,
            message,
            ""
        );
    }

    public static LogItem recording(
        String level,
        String source,
        RecordingItem recording,
        String message
    ) {
        String channelId = recording == null ? "" : recording.getChannelId();
        String channelTitle = recording == null ? "" : recording.getChannelTitle();
        String recordingId = recording == null ? "" : recording.getId();
        String videoId = recording == null ? "" : recording.getVideoId();

        return new LogItem(
            level,
            source,
            channelId,
            channelTitle,
            recordingId,
            videoId,
            message,
            ""
        );
    }

    public static LogItem fromJson(JSONObject json) throws JSONException {
        if (json == null) {
            throw new JSONException("Log JSON is null");
        }

        return new LogItem(
            json.optString(JSON_ID, ""),
            json.optLong(JSON_TIMESTAMP, System.currentTimeMillis()),
            json.optString(JSON_LEVEL, LEVEL_INFO),
            json.optString(JSON_SOURCE, SOURCE_APP),
            json.optString(JSON_CHANNEL_ID, ""),
            json.optString(JSON_CHANNEL_TITLE, ""),
            json.optString(JSON_RECORDING_ID, ""),
            json.optString(JSON_VIDEO_ID, ""),
            json.optString(JSON_MESSAGE, ""),
            json.optString(JSON_DETAILS, ""),
            json.optBoolean(JSON_COPYABLE, true)
        );
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();

        json.put(JSON_ID, id);
        json.put(JSON_TIMESTAMP, timestamp);
        json.put(JSON_LEVEL, level);
        json.put(JSON_SOURCE, source);
        json.put(JSON_CHANNEL_ID, channelId);
        json.put(JSON_CHANNEL_TITLE, channelTitle);
        json.put(JSON_RECORDING_ID, recordingId);
        json.put(JSON_VIDEO_ID, videoId);
        json.put(JSON_MESSAGE, message);
        json.put(JSON_DETAILS, details);
        json.put(JSON_COPYABLE, copyable);

        return json;
    }

    public boolean belongsToChannel(String channelId) {
        return !isBlank(this.channelId)
            && !isBlank(channelId)
            && this.channelId.equals(channelId);
    }

    public boolean belongsToRecording(String recordingId) {
        return !isBlank(this.recordingId)
            && !isBlank(recordingId)
            && this.recordingId.equals(recordingId);
    }

    public boolean belongsToVideo(String videoId) {
        return !isBlank(this.videoId)
            && !isBlank(videoId)
            && this.videoId.equals(videoId);
    }

    public boolean hasChannel() {
        return !isBlank(channelId) || !isBlank(channelTitle);
    }

    public boolean hasRecording() {
        return !isBlank(recordingId);
    }

    public boolean hasVideo() {
        return !isBlank(videoId);
    }

    public boolean isError() {
        return LEVEL_ERROR.equals(level);
    }

    public boolean isWarning() {
        return LEVEL_WARNING.equals(level);
    }

    public boolean isSuccess() {
        return LEVEL_SUCCESS.equals(level);
    }

    public String getTag() {
        if (!isBlank(channelTitle)) {
            return "[" + channelTitle + "]";
        }

        if (!isBlank(channelId)) {
            return "[" + channelId + "]";
        }

        if (!isBlank(source)) {
            return "[" + source + "]";
        }

        return "[APP]";
    }

    public String getFormattedTime() {
        SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss", Locale.US);
        return format.format(new Date(timestamp));
    }

    public String getFormattedDateTime() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        return format.format(new Date(timestamp));
    }

    public String toDisplayLine() {
        StringBuilder builder = new StringBuilder();

        builder.append(getFormattedTime());
        builder.append(" ");
        builder.append(level);
        builder.append(" ");
        builder.append(getTag());
        builder.append(" ");
        builder.append(message);

        if (!isBlank(videoId)) {
            builder.append(" ");
            builder.append("(videoId=");
            builder.append(videoId);
            builder.append(")");
        }

        if (!isBlank(details)) {
            builder.append("\n");
            builder.append(details);
        }

        return builder.toString();
    }

    public String toCopyLine() {
        StringBuilder builder = new StringBuilder();

        builder.append(getFormattedDateTime());
        builder.append(" ");
        builder.append(level);
        builder.append(" ");
        builder.append(source);
        builder.append(" ");
        builder.append(getTag());
        builder.append(" ");
        builder.append(message);

        if (!isBlank(recordingId)) {
            builder.append(" recordingId=");
            builder.append(recordingId);
        }

        if (!isBlank(videoId)) {
            builder.append(" videoId=");
            builder.append(videoId);
        }

        if (!isBlank(details)) {
            builder.append("\n");
            builder.append(details);
        }

        return builder.toString();
    }

    public String toCompactLine() {
        return getFormattedTime() + " " + getTag() + " " + message;
    }

    private static String normalizeLevel(String value) {
        if (isBlank(value)) {
            return LEVEL_INFO;
        }

        String level = value.trim().toUpperCase(Locale.US);

        if (LEVEL_DEBUG.equals(level)) {
            return LEVEL_DEBUG;
        }

        if (LEVEL_INFO.equals(level)) {
            return LEVEL_INFO;
        }

        if (LEVEL_SUCCESS.equals(level)) {
            return LEVEL_SUCCESS;
        }

        if (LEVEL_WARNING.equals(level)) {
            return LEVEL_WARNING;
        }

        if (LEVEL_ERROR.equals(level)) {
            return LEVEL_ERROR;
        }

        return LEVEL_INFO;
    }

    private static String normalizeSource(String value) {
        if (isBlank(value)) {
            return SOURCE_APP;
        }

        return value.trim().toUpperCase(Locale.US);
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

    public long getTimestamp() {
        return timestamp;
    }

    public String getLevel() {
        return level;
    }

    public String getSource() {
        return source;
    }

    public String getChannelId() {
        return channelId;
    }

    public String getChannelTitle() {
        return channelTitle;
    }

    public String getRecordingId() {
        return recordingId;
    }

    public String getVideoId() {
        return videoId;
    }

    public String getMessage() {
        return message;
    }

    public String getDetails() {
        return details;
    }

    public boolean isCopyable() {
        return copyable;
    }

    public void setLevel(String level) {
        this.level = normalizeLevel(level);
    }

    public void setSource(String source) {
        this.source = normalizeSource(source);
    }

    public void setChannel(String channelId, String channelTitle) {
        this.channelId = nullToEmpty(channelId);
        this.channelTitle = nullToEmpty(channelTitle);
    }

    public void setRecordingId(String recordingId) {
        this.recordingId = nullToEmpty(recordingId);
    }

    public void setVideoId(String videoId) {
        this.videoId = nullToEmpty(videoId);
    }

    public void setMessage(String message) {
        this.message = nullToEmpty(message);
    }

    public void setDetails(String details) {
        this.details = nullToEmpty(details);
    }

    public void setCopyable(boolean copyable) {
        this.copyable = copyable;
    }
}
