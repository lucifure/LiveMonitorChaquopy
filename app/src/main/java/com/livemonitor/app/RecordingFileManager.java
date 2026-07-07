package com.livemonitor.app;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Handles recording file/folder paths.
 *
 * Recording strategy:
 * - record into MPEG-TS first for crash resilience
 * - convert .ts to .mp4 when stream ends
 * - keep orphan .ts files recoverable after crashes
 *
 * Storage strategy:
 * - default app-specific external files directory
 * - optional Storage Access Framework URI saved in settings
 *
 * Note:
 * Direct filesystem paths are easiest for FFmpeg.
 * If user selects a SAF folder, later recorder integration can copy/move
 * the completed MP4 into that folder after conversion.
 */
public class RecordingFileManager {

    private static final String RECORDINGS_DIR_NAME = "recordings";
    private static final String TEMP_DIR_NAME = ".temp_cache";
    private static final String COMPLETED_DIR_NAME = "completed";
    private static final String RECOVERABLE_DIR_NAME = "recoverable";

    private final Context appContext;
    private final AppStorage storage;

    public RecordingFileManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.storage = new AppStorage(appContext);
    }

    public File getBaseRecordingDirectory() {
        File externalDir = appContext.getExternalFilesDir(null);

        if (externalDir != null) {
            return ensureDirectory(new File(externalDir, RECORDINGS_DIR_NAME));
        }

        return ensureDirectory(new File(appContext.getFilesDir(), RECORDINGS_DIR_NAME));
    }

    public File getTempDirectory() {
        return ensureDirectory(new File(getBaseRecordingDirectory(), TEMP_DIR_NAME));
    }

    public File getCompletedDirectory() {
        return ensureDirectory(new File(getBaseRecordingDirectory(), COMPLETED_DIR_NAME));
    }

    public File getRecoverableDirectory() {
        return ensureDirectory(new File(getBaseRecordingDirectory(), RECOVERABLE_DIR_NAME));
    }

    public File createTempTsFile(ChannelItem channel, String videoId) {
        return createTempTsFile(channel, videoId, "");
    }

    public File createTempTsFile(ChannelItem channel, String videoId, String liveTitle) {
        return createTempTsFile(channel, videoId, liveTitle, System.currentTimeMillis());
    }

    public File createTempTsFile(ChannelItem channel, String videoId, String liveTitle, long startedAt) {
        String fileName = buildBaseFileName(channel, videoId, liveTitle, startedAt) + ".ts";
        return new File(getTempDirectory(), fileName);
    }

    public File createFinalMp4File(ChannelItem channel, String videoId) {
        return createFinalMp4File(channel, videoId, "");
    }

    public File createFinalMp4File(ChannelItem channel, String videoId, String liveTitle) {
        return createFinalMp4File(channel, videoId, liveTitle, System.currentTimeMillis());
    }

    public File createFinalMp4File(ChannelItem channel, String videoId, String liveTitle, long startedAt) {
        String fileName = buildBaseFileName(channel, videoId, liveTitle, startedAt) + ".mp4";
        return new File(getCompletedDirectory(), fileName);
    }

    public File createRecoverableTsFile(ChannelItem channel, String videoId) {
        String fileName = buildBaseFileName(channel, videoId) + ".ts";
        return new File(getRecoverableDirectory(), fileName);
    }

    public RecordingItem createRecordingItem(
        ChannelItem channel,
        String videoId,
        String videoUrl,
        AppSettings settings
    ) {
        if (settings == null) {
            settings = storage.loadSettings();
        }

        return createRecordingItem(channel, videoId, videoUrl, "", settings);
    }

    public RecordingItem createRecordingItem(
        ChannelItem channel,
        String videoId,
        String videoUrl,
        String liveTitle,
        AppSettings settings
    ) {
        if (settings == null) {
            settings = storage.loadSettings();
        }

        long startedAt = System.currentTimeMillis();
        File tempTsFile = createTempTsFile(channel, videoId, liveTitle, startedAt);
        File finalMp4File = createFinalMp4File(channel, videoId, liveTitle, startedAt);

        String channelId = channel == null ? "" : channel.getId();
        String channelTitle = channel == null ? "" : channel.getDisplayTitle();
        String channelUrl = channel == null ? "" : channel.getUrl();
        String title = isBlank(liveTitle) ? buildHumanTitle(channel, videoId) : liveTitle.trim();

        RecordingItem recording = new RecordingItem(
            channelId,
            channelTitle,
            channelUrl,
            videoId,
            videoUrl,
            title,
            settings.getDownloadQuality(),
            tempTsFile.getAbsolutePath(),
            finalMp4File.getAbsolutePath()
        );
        recording.setStartedAt(startedAt);
        return recording;
    }

    public void cleanupTempFolderBeforeRecording() {
        AppSettings settings = storage.loadSettings();

        if (!settings.isTempCleanupBeforeRecording()) {
            return;
        }

        File tempDir = getTempDirectory();
        File[] files = tempDir.listFiles();

        if (files == null || files.length == 0) {
            return;
        }

        for (File file : files) {
            if (file == null || !file.exists()) {
                continue;
            }

            /*
             * Match the external recorder script's cleanup for interrupted
             * fragments, but keep non-empty TS files because those may be
             * recoverable after a crash.
             */
            if (file.isFile() && isSafeTemporaryLeftover(file)) {
                safeDelete(file);
            }
        }
    }

    public List<RecordingItem> scanRecoverableTsFiles() {
        List<RecordingItem> recoverable = new ArrayList<>();

        AppSettings settings = storage.loadSettings();

        if (!settings.isRecoverOrphanTsFiles()) {
            return recoverable;
        }

        scanRecoverableTsFilesInDirectory(getTempDirectory(), recoverable, settings);
        scanRecoverableTsFilesInDirectory(getRecoverableDirectory(), recoverable, settings);

        return recoverable;
    }

    public void registerRecoverableTsFilesInStorage() {
        List<RecordingItem> recoverableItems = scanRecoverableTsFiles();

        if (recoverableItems.isEmpty()) {
            return;
        }

        List<RecordingItem> existingRecordings = storage.loadRecordings();

        for (RecordingItem item : recoverableItems) {
            if (!hasExistingRecordingForFile(existingRecordings, item)) {
                storage.upsertRecording(item);
                existingRecordings.add(item);
            }
        }

        storage.appendLog(new LogItem(
            LogItem.LEVEL_INFO,
            LogItem.SOURCE_RECORDER,
            "",
            "",
            "",
            "",
            "Recoverable TS scan completed.",
            "recoverableCount=" + recoverableItems.size()
        ));
    }


    private boolean hasExistingRecordingForFile(List<RecordingItem> existingRecordings, RecordingItem candidate) {
        if (candidate == null || existingRecordings == null) {
            return false;
        }

        String candidateTempPath = candidate.getTempTsPath();
        String candidateVideoId = candidate.getVideoId();

        for (RecordingItem existing : existingRecordings) {
            if (existing == null) {
                continue;
            }

            if (!isBlank(candidateTempPath) && candidateTempPath.equals(existing.getTempTsPath())) {
                return true;
            }

            if (!isBlank(candidateVideoId) && candidate.matchesVideo(existing.getVideoId())) {
                return true;
            }
        }

        return false;
    }

    public boolean moveTempToRecoverable(RecordingItem recording) {
        if (recording == null || isBlank(recording.getTempTsPath())) {
            return false;
        }

        File source = new File(recording.getTempTsPath());

        if (!source.exists() || !source.isFile()) {
            return false;
        }

        File destination = new File(getRecoverableDirectory(), source.getName());

        if (safeRename(source, destination)) {
            recording.setTempTsPath(destination.getAbsolutePath());
            recording.markRecoverable("Moved unfinished TS file to recoverable folder.");
            storage.upsertRecording(recording);
            return true;
        }

        return false;
    }

    public boolean hasEnoughUsableSpace(long minimumBytes) {
        File baseDir = getBaseRecordingDirectory();

        try {
            return baseDir.getUsableSpace() >= minimumBytes;
        } catch (Exception ignored) {
            return true;
        }
    }

    public String getStorageSummary() {
        File baseDir = getBaseRecordingDirectory();
        AppSettings settings = storage.loadSettings();

        StringBuilder builder = new StringBuilder();
        builder.append("basePath=");
        builder.append(baseDir.getAbsolutePath());

        if (!isBlank(settings.getSaveLocationUri())) {
            builder.append(", selectedSaveLocation=");
            builder.append(settings.getSaveLocationDisplayName());
        }

        try {
            builder.append(", usableBytes=");
            builder.append(baseDir.getUsableSpace());
        } catch (Exception ignored) {
            builder.append(", usableBytes=unknown");
        }

        return builder.toString();
    }

    public boolean hasCustomSaveLocation() {
        AppSettings settings = storage.loadSettings();
        return !isBlank(settings.getSaveLocationUri());
    }

    public Uri getCustomSaveLocationUri() {
        AppSettings settings = storage.loadSettings();

        if (isBlank(settings.getSaveLocationUri())) {
            return null;
        }

        try {
            return Uri.parse(settings.getSaveLocationUri());
        } catch (Exception ignored) {
            return null;
        }
    }

    public String getCustomSaveLocationDisplayName() {
        AppSettings settings = storage.loadSettings();

        if (!isBlank(settings.getSaveLocationDisplayName())) {
            return settings.getSaveLocationDisplayName();
        }

        Uri uri = getCustomSaveLocationUri();

        if (uri == null) {
            return "Default app recordings folder";
        }

        try {
            String documentId = DocumentsContract.getTreeDocumentId(uri);
            return isBlank(documentId) ? uri.toString() : documentId;
        } catch (Exception ignored) {
            return uri.toString();
        }
    }

    public static String buildBaseFileName(ChannelItem channel, String videoId) {
        return buildBaseFileName(channel, videoId, "");
    }

    public static String buildBaseFileName(ChannelItem channel, String videoId, String liveTitle) {
        return buildBaseFileName(channel, videoId, liveTitle, System.currentTimeMillis());
    }

    public static String buildBaseFileName(
        ChannelItem channel,
        String videoId,
        String liveTitle,
        long startedAt
    ) {
        Date startedDate = new Date(startedAt > 0L ? startedAt : System.currentTimeMillis());
        String date = new SimpleDateFormat("ddMMyyyy", Locale.getDefault()).format(startedDate);
        String time = new SimpleDateFormat("HH-mm-ss", Locale.getDefault()).format(startedDate);
        String suffix = "_" + date + "_" + time;

        String title = !isBlank(liveTitle)
            ? liveTitle.trim()
            : buildChannelHandleFallback(channel);
        String safeTitle = sanitizeFileName(title, Math.max(1, 100 - suffix.length()));

        if (isBlank(safeTitle)) {
            safeTitle = isBlank(videoId) ? "Live_Stream" : sanitizeFileName(videoId, Math.max(1, 100 - suffix.length()));
        }

        return safeTitle + suffix;
    }

    public static String buildHumanTitle(ChannelItem channel, String videoId) {
        String channelLabel = channel == null
            ? "Channel"
            : channel.getDisplayTitle();

        if (isBlank(videoId)) {
            return channelLabel + " Live Recording";
        }

        return channelLabel + " - " + videoId;
    }

    public static String sanitizeFileName(String value) {
        return sanitizeFileName(value, 80);
    }

    public static String sanitizeFileName(String value, int maxLength) {
        if (value == null) {
            return "";
        }

        String sanitized = value
            .trim()
            .replaceAll("[/:*?\"<>|\\\n\r]", "_")
            .replaceAll("[ _]+", "_");

        sanitized = stripHiddenTrashPrefix(sanitized)
            .replaceAll("^[ _]+", "")
            .replaceAll("[ _]+$", "");

        int safeMaxLength = Math.max(1, maxLength);
        if (sanitized.length() > safeMaxLength) {
            sanitized = sanitized.substring(0, safeMaxLength)
                .replaceAll("[ _]+$", "");
        }

        return sanitized;
    }

    private static String buildChannelHandleFallback(ChannelItem channel) {
        if (channel == null) {
            return "";
        }

        String url = channel.getUrl();
        if (!isBlank(url) && url.contains("/@")) {
            String handle = url.substring(url.indexOf("/@") + 2).replaceAll("[/?#].*", "");
            if (!isBlank(handle)) {
                return handle;
            }
        }

        return channel.getDisplayTitle();
    }

    public static String stripHiddenTrashPrefix(String value) {
        if (value == null) {
            return "";
        }

        String cleaned = value.trim();
        boolean changed;
        do {
            changed = false;
            while (cleaned.startsWith("_") || cleaned.startsWith(".")) {
                cleaned = cleaned.substring(1);
                changed = true;
            }
            String lower = cleaned.toLowerCase(Locale.US);
            if (lower.startsWith("trashed-")) {
                cleaned = cleaned.substring("trashed-".length());
                changed = true;
                int nextDash = cleaned.indexOf('-');
                if (nextDash > 0) {
                    String maybeTimestamp = cleaned.substring(0, nextDash);
                    if (maybeTimestamp.matches("\\d{6,}")) {
                        cleaned = cleaned.substring(nextDash + 1);
                    }
                }
            }
        } while (changed);

        return cleaned;
    }

    private void scanRecoverableTsFilesInDirectory(
        File directory,
        List<RecordingItem> output,
        AppSettings settings
    ) {
        if (directory == null || output == null || !directory.exists()) {
            return;
        }

        File[] files = directory.listFiles();

        if (files == null || files.length == 0) {
            return;
        }

        for (File file : files) {
            if (file == null || !file.isFile()) {
                continue;
            }

            String name = file.getName();

            if (!name.toLowerCase(Locale.US).endsWith(".ts")) {
                continue;
            }

            if (file.length() <= 0L) {
                continue;
            }

            RecordingItem item = new RecordingItem(
                "",
                "Recovered Recording",
                "",
                extractVideoIdFromFileName(name),
                "",
                name,
                settings.getDownloadQuality(),
                file.getAbsolutePath(),
                buildRecoveredMp4Path(name)
            );

            item.markRecoverable("Recovered unfinished TS file from storage scan.");
            output.add(item);
        }
    }

    private String buildRecoveredMp4Path(String tsFileName) {
        String baseName = tsFileName;

        if (baseName.toLowerCase(Locale.US).endsWith(".ts")) {
            baseName = baseName.substring(0, baseName.length() - 3);
        }

        return new File(getCompletedDirectory(), baseName + ".mp4").getAbsolutePath();
    }

    private static String extractVideoIdFromFileName(String fileName) {
        if (isBlank(fileName)) {
            return "";
        }

        String name = fileName;

        if (name.toLowerCase(Locale.US).endsWith(".ts")) {
            name = name.substring(0, name.length() - 3);
        }

        int lastUnderscore = name.lastIndexOf("_");

        if (lastUnderscore < 0 || lastUnderscore >= name.length() - 1) {
            return "";
        }

        return name.substring(lastUnderscore + 1);
    }

    private static boolean isSafeTemporaryLeftover(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }

        if (file.length() == 0L) {
            return true;
        }

        String name = file.getName().toLowerCase(Locale.US);

        return name.endsWith(".part")
            || name.endsWith(".ytdl")
            || name.endsWith(".m4s");
    }

    private static File ensureDirectory(File directory) {
        if (directory != null && !directory.exists()) {
            directory.mkdirs();
        }

        return directory;
    }

    private static boolean safeDelete(File file) {
        try {
            return file != null && (!file.exists() || file.delete());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean safeRename(File source, File destination) {
        try {
            if (source == null || destination == null || !source.exists()) {
                return false;
            }

            File parent = destination.getParentFile();

            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            if (destination.exists()) {
                safeDelete(destination);
            }

            return source.renameTo(destination);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
  }
