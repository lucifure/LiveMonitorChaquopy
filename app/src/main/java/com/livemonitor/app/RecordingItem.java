package com.livemonitor.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Represents one recording shown in the Downloads tab.
 *
 * A recording normally starts as an MPEG-TS file for crash resilience:
 * - status RECORDING
 * - tempTsPath points to the active .ts file
 *
 * When the stream ends:
 * - status CONVERTING
 * - tempTsPath is converted to finalMp4Path
 *
 * After conversion:
 * - status COMPLETED
 * - finalMp4Path points to the saved .mp4 file
 *
 * If the app crashes before conversion:
 * - tempTsPath remains on disk
 * - status can be restored as RECOVERABLE
 * - user can manually convert/recover it later
 */
public class RecordingItem {

    public static final String STATUS_WAITING_FOR_LIVE = "WAITING_FOR_LIVE";
    public static final String STATUS_RECORDING = "RECORDING";
    public static final String STATUS_PAUSED_NETWORK = "PAUSED_NETWORK";
    public static final String STATUS_PAUSED_BY_USER = "PAUSED_BY_USER";
    public static final String STATUS_CONVERTING = "CONVERTING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_STOPPED_BY_USER = "STOPPED_BY_USER";
    public static final String STATUS_RECOVERABLE = "RECOVERABLE";

    private static final String JSON_ID = "id";
    private static final String JSON_CHANNEL_ID = "channelId";
    private static final String JSON_CHANNEL_TITLE = "channelTitle";
    private static final String JSON_CHANNEL_URL = "channelUrl";
    private static final String JSON_VIDEO_ID = "videoId";
    private static final String JSON_VIDEO_URL = "videoUrl";
    private static final String JSON_TITLE = "title";
    private static final String JSON_STATUS = "status";
    private static final String JSON_TEMP_TS_PATH = "tempTsPath";
    private static final String JSON_FINAL_MP4_PATH = "finalMp4Path";
    private static final String JSON_TEMP_CHUNK_PATHS = "tempChunkPaths";
    private static final String JSON_ERROR_MESSAGE = "errorMessage";
    private static final String JSON_QUALITY = "quality";
    private static final String JSON_BYTES_RECORDED = "bytesRecorded";
    private static final String JSON_DURATION_SECONDS = "durationSeconds";
    private static final String JSON_PROGRESS_PERCENT = "progressPercent";
    private static final String JSON_CREATED_AT = "createdAt";
    private static final String JSON_STARTED_AT = "startedAt";
    private static final String JSON_UPDATED_AT = "updatedAt";
    private static final String JSON_FINISHED_AT = "finishedAt";
    private static final String JSON_CONVERTED_AT = "convertedAt";
    private static final String JSON_HIDDEN_FROM_DOWNLOADING = "hiddenFromDownloading";

    private String id;
    private String channelId;
    private String channelTitle;
    private String channelUrl;
    private String videoId;
    private String videoUrl;
    private String title;
    private String status;
    private String tempTsPath;
    private String finalMp4Path;
    private List<String> tempChunkPaths;
    private String errorMessage;
    private String quality;
    private long bytesRecorded;
    private long durationSeconds;
    private int progressPercent;
    private long createdAt;
    private long startedAt;
    private long updatedAt;
    private long finishedAt;
    private long convertedAt;
    private boolean hiddenFromDownloading;

    public RecordingItem(
        String channelId,
        String channelTitle,
        String channelUrl,
        String videoId,
        String videoUrl,
        String title,
        String quality,
        String tempTsPath,
        String finalMp4Path
    ) {
        long now = System.currentTimeMillis();

        this.id = UUID.randomUUID().toString();
        this.channelId = nullToEmpty(channelId);
        this.channelTitle = nullToEmpty(channelTitle);
        this.channelUrl = nullToEmpty(channelUrl);
        this.videoId = normalizeVideoId(videoId);
        this.videoUrl = nullToEmpty(videoUrl);
        this.title = isBlank(title) ? buildDefaultTitle(channelTitle, this.videoId) : title.trim();
        this.status = STATUS_WAITING_FOR_LIVE;
        this.tempTsPath = nullToEmpty(tempTsPath);
        this.finalMp4Path = nullToEmpty(finalMp4Path);
        this.tempChunkPaths = new ArrayList<>();
        this.errorMessage = "";
        this.quality = isBlank(quality) ? "480p" : quality.trim();
        this.bytesRecorded = 0L;
        this.durationSeconds = 0L;
        this.progressPercent = 0;
        this.createdAt = now;
        this.startedAt = 0L;
        this.updatedAt = now;
        this.finishedAt = 0L;
        this.convertedAt = 0L;
        this.hiddenFromDownloading = false;
    }

    public RecordingItem(
        String id,
        String channelId,
        String channelTitle,
        String channelUrl,
        String videoId,
        String videoUrl,
        String title,
        String status,
        String tempTsPath,
        String finalMp4Path,
        List<String> tempChunkPaths,
        String errorMessage,
        String quality,
        long bytesRecorded,
        long durationSeconds,
        int progressPercent,
        long createdAt,
        long startedAt,
        long updatedAt,
        long finishedAt,
        long convertedAt,
        boolean hiddenFromDownloading
    ) {
        this.id = isBlank(id) ? UUID.randomUUID().toString() : id;
        this.channelId = nullToEmpty(channelId);
        this.channelTitle = nullToEmpty(channelTitle);
        this.channelUrl = nullToEmpty(channelUrl);
        this.videoId = normalizeVideoId(videoId);
        this.videoUrl = nullToEmpty(videoUrl);
        this.title = isBlank(title) ? buildDefaultTitle(channelTitle, this.videoId) : title.trim();
        this.status = isBlank(status) ? STATUS_WAITING_FOR_LIVE : status;
        this.tempTsPath = nullToEmpty(tempTsPath);
        this.finalMp4Path = nullToEmpty(finalMp4Path);
        this.tempChunkPaths = normalizeTempChunkPaths(tempChunkPaths);
        this.errorMessage = nullToEmpty(errorMessage);
        this.quality = isBlank(quality) ? "480p" : quality.trim();
        this.bytesRecorded = Math.max(0L, bytesRecorded);
        this.durationSeconds = Math.max(0L, durationSeconds);
        this.progressPercent = clampProgress(progressPercent);
        this.createdAt = createdAt <= 0 ? System.currentTimeMillis() : createdAt;
        this.startedAt = Math.max(0L, startedAt);
        this.updatedAt = updatedAt <= 0 ? System.currentTimeMillis() : updatedAt;
        this.finishedAt = Math.max(0L, finishedAt);
        this.convertedAt = Math.max(0L, convertedAt);
        this.hiddenFromDownloading = hiddenFromDownloading;
    }

    public static RecordingItem fromJson(JSONObject json) throws JSONException {
        if (json == null) {
            throw new JSONException("Recording JSON is null");
        }

        return new RecordingItem(
            json.optString(JSON_ID, ""),
            json.optString(JSON_CHANNEL_ID, ""),
            json.optString(JSON_CHANNEL_TITLE, ""),
            json.optString(JSON_CHANNEL_URL, ""),
            json.optString(JSON_VIDEO_ID, ""),
            json.optString(JSON_VIDEO_URL, ""),
            json.optString(JSON_TITLE, ""),
            json.optString(JSON_STATUS, STATUS_WAITING_FOR_LIVE),
            json.optString(JSON_TEMP_TS_PATH, ""),
            json.optString(JSON_FINAL_MP4_PATH, ""),
            parseTempChunkPaths(json.optJSONArray(JSON_TEMP_CHUNK_PATHS)),
            json.optString(JSON_ERROR_MESSAGE, ""),
            json.optString(JSON_QUALITY, "480p"),
            json.optLong(JSON_BYTES_RECORDED, 0L),
            json.optLong(JSON_DURATION_SECONDS, 0L),
            json.optInt(JSON_PROGRESS_PERCENT, 0),
            json.optLong(JSON_CREATED_AT, System.currentTimeMillis()),
            json.optLong(JSON_STARTED_AT, 0L),
            json.optLong(JSON_UPDATED_AT, System.currentTimeMillis()),
            json.optLong(JSON_FINISHED_AT, 0L),
            json.optLong(JSON_CONVERTED_AT, 0L),
            json.optBoolean(JSON_HIDDEN_FROM_DOWNLOADING, false)
        );
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();

        json.put(JSON_ID, id);
        json.put(JSON_CHANNEL_ID, channelId);
        json.put(JSON_CHANNEL_TITLE, channelTitle);
        json.put(JSON_CHANNEL_URL, channelUrl);
        json.put(JSON_VIDEO_ID, videoId);
        json.put(JSON_VIDEO_URL, videoUrl);
        json.put(JSON_TITLE, title);
        json.put(JSON_STATUS, status);
        json.put(JSON_TEMP_TS_PATH, tempTsPath);
        json.put(JSON_FINAL_MP4_PATH, finalMp4Path);
        JSONArray chunkArray = new JSONArray();
        for (String chunkPath : getTempSegmentPaths()) {
            chunkArray.put(chunkPath);
        }
        json.put(JSON_TEMP_CHUNK_PATHS, chunkArray);
        json.put(JSON_ERROR_MESSAGE, errorMessage);
        json.put(JSON_QUALITY, quality);
        json.put(JSON_BYTES_RECORDED, bytesRecorded);
        json.put(JSON_DURATION_SECONDS, durationSeconds);
        json.put(JSON_PROGRESS_PERCENT, progressPercent);
        json.put(JSON_CREATED_AT, createdAt);
        json.put(JSON_STARTED_AT, startedAt);
        json.put(JSON_UPDATED_AT, updatedAt);
        json.put(JSON_FINISHED_AT, finishedAt);
        json.put(JSON_CONVERTED_AT, convertedAt);
        json.put(JSON_HIDDEN_FROM_DOWNLOADING, hiddenFromDownloading);

        return json;
    }

    public void markWaitingForLive() {
        status = STATUS_WAITING_FOR_LIVE;
        progressPercent = 0;
        errorMessage = "";
        touch();
    }

    public void markRecording() {
        status = STATUS_RECORDING;

        if (startedAt <= 0L) {
            startedAt = System.currentTimeMillis();
        }

        errorMessage = "";
        touch();
    }

    public void markPausedNetwork(String message) {
        status = STATUS_PAUSED_NETWORK;
        errorMessage = nullToEmpty(message);
        touch();
    }

    public void markPausedByUser() {
        status = STATUS_PAUSED_BY_USER;
        errorMessage = "Paused by user.";
        touch();
    }

    public void markConverting() {
        status = STATUS_CONVERTING;
        progressPercent = 95;
        errorMessage = "";
        finishedAt = finishedAt <= 0L ? System.currentTimeMillis() : finishedAt;
        touch();
    }

    public void markCompleted(String finalMp4Path) {
        status = STATUS_COMPLETED;
        this.finalMp4Path = nullToEmpty(finalMp4Path);
        progressPercent = 100;
        errorMessage = "";

        long now = System.currentTimeMillis();
        finishedAt = finishedAt <= 0L ? now : finishedAt;
        convertedAt = now;
        touch();
    }

    public void markFailed(String message) {
        status = STATUS_FAILED;
        errorMessage = nullToEmpty(message);
        finishedAt = finishedAt <= 0L ? System.currentTimeMillis() : finishedAt;
        touch();
    }

    public void markStoppedByUser() {
        status = STATUS_STOPPED_BY_USER;
        finishedAt = finishedAt <= 0L ? System.currentTimeMillis() : finishedAt;
        touch();
    }

    public void markRecoverable(String message) {
        status = STATUS_RECOVERABLE;
        errorMessage = nullToEmpty(message);
        finishedAt = finishedAt <= 0L ? System.currentTimeMillis() : finishedAt;
        touch();
    }

    public void updateProgress(long bytesRecorded, long durationSeconds) {
        this.bytesRecorded = Math.max(0L, bytesRecorded);
        this.durationSeconds = Math.max(0L, durationSeconds);

        /*
         * Live streams usually do not have a fixed final duration, so true
         * percentage is not always knowable. For active recordings, keep the
         * progress below conversion/completion range and use bytes/duration
         * for the actual UI display.
         */
        if (STATUS_RECORDING.equals(status)) {
            progressPercent = Math.max(progressPercent, 1);
            progressPercent = Math.min(progressPercent, 90);
        }

        touch();
    }

    public boolean isActive() {
        return STATUS_WAITING_FOR_LIVE.equals(status)
            || STATUS_RECORDING.equals(status)
            || STATUS_PAUSED_NETWORK.equals(status)
            || STATUS_PAUSED_BY_USER.equals(status)
            || STATUS_CONVERTING.equals(status);
    }

    public boolean isFinished() {
        return STATUS_COMPLETED.equals(status)
            || STATUS_FAILED.equals(status)
            || STATUS_STOPPED_BY_USER.equals(status)
            || STATUS_RECOVERABLE.equals(status);
    }

    public boolean isCompleted() {
        return STATUS_COMPLETED.equals(status);
    }

    public boolean isPausedByUser() {
        return STATUS_PAUSED_BY_USER.equals(status);
    }

    public boolean isPlayableCompletedFile() {
        return isCompleted() && (hasExistingFinalMp4File() || hasExistingTempTsFile());
    }

    public boolean isRecoverable() {
        return STATUS_RECOVERABLE.equals(status)
            || hasExistingTempTsFile() && !hasExistingFinalMp4File() && !STATUS_RECORDING.equals(status);
    }

    public boolean hasExistingTempTsFile() {
        for (String segmentPath : getTempSegmentPaths()) {
            if (isExistingFile(segmentPath)) {
                return true;
            }
        }

        return false;
    }

    public boolean hasExistingFinalMp4File() {
        if (isBlank(finalMp4Path)) {
            return false;
        }

        File file = new File(finalMp4Path);
        return file.exists() && file.isFile() && file.length() > 0L;
    }

    public boolean matchesChannel(String channelId) {
        return !isBlank(this.channelId) && this.channelId.equals(channelId);
    }

    public boolean matchesVideo(String videoId) {
        String normalizedVideoId = normalizeVideoId(videoId);

        return !normalizedVideoId.isEmpty() && normalizedVideoId.equals(this.videoId);
    }

    public String getBestPlayablePath() {
        if (hasExistingFinalMp4File()) {
            return finalMp4Path;
        }

        if (hasExistingTempTsFile()) {
            return tempTsPath;
        }

        return "";
    }

    public List<String> getTempSegmentPaths() {
        List<String> paths = normalizeTempChunkPaths(tempChunkPaths);

        if (!paths.isEmpty()) {
            return Collections.unmodifiableList(paths);
        }

        if (isBlank(tempTsPath)) {
            return Collections.emptyList();
        }

        List<String> singlePath = new ArrayList<>();
        singlePath.add(tempTsPath);
        return Collections.unmodifiableList(singlePath);
    }

    public boolean hasMultipleTempSegments() {
        return getTempSegmentPaths().size() > 1;
    }

    public void addTempChunkPath(String chunkPath) {
        if (isBlank(chunkPath)) {
            return;
        }

        List<String> paths = new ArrayList<>(getTempSegmentPaths());
        String normalizedChunkPath = chunkPath.trim();

        if (!paths.contains(normalizedChunkPath)) {
            paths.add(normalizedChunkPath);
        }

        tempChunkPaths = normalizeTempChunkPaths(paths);
        touch();
    }

    public String getCurrentTempSegmentPath() {
        List<String> paths = getTempSegmentPaths();

        if (paths.isEmpty()) {
            return tempTsPath;
        }

        return paths.get(paths.size() - 1);
    }

    public String getDisplayTitle() {
        if (!isBlank(title)) {
            return title;
        }

        return buildDefaultTitle(channelTitle, videoId);
    }

    public String getDisplaySubtitle() {
        StringBuilder builder = new StringBuilder();

        if (!isBlank(channelTitle)) {
            builder.append(channelTitle);
        }

        if (!isBlank(quality)) {
            if (builder.length() > 0) {
                builder.append(" • ");
            }

            builder.append(quality);
        }

        if (!isBlank(videoId)) {
            if (builder.length() > 0) {
                builder.append(" • ");
            }

            builder.append(videoId);
        }

        return builder.toString();
    }

    public String getLogTag() {
        String label = !isBlank(channelTitle) ? channelTitle : getDisplayTitle();
        return "[" + label + "]";
    }

    private void touch() {
        updatedAt = System.currentTimeMillis();
    }

    private static String buildDefaultTitle(String channelTitle, String videoId) {
        String safeChannelTitle = nullToEmpty(channelTitle).trim();
        String safeVideoId = normalizeVideoId(videoId);

        if (!safeChannelTitle.isEmpty() && !safeVideoId.isEmpty()) {
            return safeChannelTitle + " - " + safeVideoId;
        }

        if (!safeChannelTitle.isEmpty()) {
            return safeChannelTitle;
        }

        if (!safeVideoId.isEmpty()) {
            return "Recording " + safeVideoId;
        }

        return "Untitled Recording";
    }

    private static boolean isExistingFile(String path) {
        if (isBlank(path)) {
            return false;
        }

        File file = new File(path);
        return file.exists() && file.isFile() && file.length() > 0L;
    }

    private static List<String> parseTempChunkPaths(JSONArray array) {
        List<String> paths = new ArrayList<>();

        if (array == null) {
            return paths;
        }

        for (int i = 0; i < array.length(); i++) {
            String path = array.optString(i, "");

            if (!isBlank(path)) {
                paths.add(path.trim());
            }
        }

        return normalizeTempChunkPaths(paths);
    }

    private static List<String> normalizeTempChunkPaths(List<String> paths) {
        List<String> normalized = new ArrayList<>();

        if (paths == null) {
            return normalized;
        }

        for (String path : paths) {
            if (!isBlank(path)) {
                String trimmed = path.trim();

                if (!normalized.contains(trimmed)) {
                    normalized.add(trimmed);
                }
            }
        }

        return normalized;
    }

    private static String normalizeVideoId(String value) {
        if (value == null) {
            return "";
        }

        return value.trim();
    }

    private static int clampProgress(int value) {
        if (value < 0) {
            return 0;
        }

        if (value > 100) {
            return 100;
        }

        return value;
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

    public String getChannelId() {
        return channelId;
    }

    public String getChannelTitle() {
        return channelTitle;
    }

    public String getChannelUrl() {
        return channelUrl;
    }

    public String getVideoId() {
        return videoId;
    }

    public String getVideoUrl() {
        return videoUrl;
    }

    public String getTitle() {
        return title;
    }

    public String getStatus() {
        return status;
    }

    public String getTempTsPath() {
        return tempTsPath;
    }

    public String getFinalMp4Path() {
        return finalMp4Path;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getQuality() {
        return quality;
    }

    public long getBytesRecorded() {
        return bytesRecorded;
    }

    public long getDurationSeconds() {
        return durationSeconds;
    }

    public int getProgressPercent() {
        return progressPercent;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public long getFinishedAt() {
        return finishedAt;
    }

    public long getConvertedAt() {
        return convertedAt;
    }

    public boolean isHiddenFromDownloading() {
        return hiddenFromDownloading;
    }

    public void hideFromDownloading() {
        hiddenFromDownloading = true;
        touch();
    }

    public void showInDownloading() {
        hiddenFromDownloading = false;
        touch();
    }

    public void setChannelTitle(String channelTitle) {
        this.channelTitle = nullToEmpty(channelTitle);
        touch();
    }

    public void setChannelUrl(String channelUrl) {
        this.channelUrl = nullToEmpty(channelUrl);
        touch();
    }

    public void setVideoId(String videoId) {
        this.videoId = normalizeVideoId(videoId);
        touch();
    }

    public void setVideoUrl(String videoUrl) {
        this.videoUrl = nullToEmpty(videoUrl);
        touch();
    }

    public void setTitle(String title) {
        this.title = isBlank(title) ? buildDefaultTitle(channelTitle, videoId) : title.trim();
        touch();
    }

    public void setStatus(String status) {
        this.status = isBlank(status) ? STATUS_WAITING_FOR_LIVE : status;
        touch();
    }

    public void setTempTsPath(String tempTsPath) {
        this.tempTsPath = nullToEmpty(tempTsPath);
        touch();
    }

    public void setFinalMp4Path(String finalMp4Path) {
        this.finalMp4Path = nullToEmpty(finalMp4Path);
        touch();
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = nullToEmpty(errorMessage);
        touch();
    }

    public void setDiagnosticMessage(String diagnosticMessage) {
        this.errorMessage = nullToEmpty(diagnosticMessage);
        touch();
    }

    public void clearDiagnosticMessage() {
        this.errorMessage = "";
        touch();
    }

    public void setQuality(String quality) {
        this.quality = isBlank(quality) ? "480p" : quality.trim();
        touch();
    }

    public void setProgressPercent(int progressPercent) {
        this.progressPercent = clampProgress(progressPercent);
        touch();
    }
}
