package com.livemonitor.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Imports/relinks completed recordings found in the user-selected SAF save folder.
 *
 * The recorder writes to app-private file paths first because yt-dlp/ffmpeg need
 * real filesystem paths. Once files are copied/exported to a selected folder,
 * this importer keeps the app's Past Recordings list in sync with that folder.
 */
public class SelectedFolderRecordingImporter {

    public static class Result {
        private int importedCount;
        private int relinkedCount;
        private int scannedCount;
        private String message = "";

        public int getImportedCount() {
            return importedCount;
        }

        public int getRelinkedCount() {
            return relinkedCount;
        }

        public int getScannedCount() {
            return scannedCount;
        }

        public String getMessage() {
            return message;
        }

        public boolean changed() {
            return importedCount > 0 || relinkedCount > 0;
        }

        private void setMessage(String message) {
            this.message = message == null ? "" : message;
        }
    }

    private static class FolderFile {
        String name;
        String uri;
        String mimeType;
        long size;
        long lastModified;
    }

    private final Context context;
    private final AppStorage storage;

    public SelectedFolderRecordingImporter(Context context) {
        this.context = context.getApplicationContext();
        this.storage = new AppStorage(this.context);
    }

    public Result importFromSelectedFolder() {
        Result result = new Result();
        AppSettings settings = storage.loadSettings();
        String folderUriText = settings.getSaveLocationUri();

        if (isBlank(folderUriText)) {
            result.setMessage("No selected save folder is configured.");
            return result;
        }

        Uri folderUri;
        try {
            folderUri = Uri.parse(folderUriText);
        } catch (Exception e) {
            result.setMessage("Selected save folder is invalid.");
            return result;
        }

        List<FolderFile> folderFiles;
        try {
            folderFiles = listRecordingFiles(folderUri);
        } catch (Exception e) {
            result.setMessage("Could not read selected save folder: " + e.getMessage());
            return result;
        }

        result.scannedCount = folderFiles.size();
        if (folderFiles.isEmpty()) {
            result.setMessage("No MP4/TS recordings were found in the selected save folder.");
            return result;
        }

        List<RecordingItem> recordings = storage.loadRecordings();
        Set<String> knownPlayableUris = new HashSet<>();
        Set<String> knownPlayableNames = new HashSet<>();

        for (RecordingItem recording : recordings) {
            if (recording == null) continue;
            String playablePath = recording.getBestPlayablePath();
            if (!isBlank(playablePath)) {
                knownPlayableUris.add(playablePath.trim());
                knownPlayableNames.add(normalizedNameKey(displayNameFromPath(playablePath)));
            }
        }

        for (FolderFile folderFile : folderFiles) {
            if (folderFile == null || isBlank(folderFile.uri)) {
                continue;
            }

            RecordingItem relinked = relinkMissingRecording(recordings, folderFile);
            if (relinked != null) {
                result.relinkedCount++;
                knownPlayableUris.add(folderFile.uri);
                knownPlayableNames.add(normalizedNameKey(folderFile.name));
                continue;
            }

            if (knownPlayableUris.contains(folderFile.uri) || knownPlayableNames.contains(normalizedNameKey(folderFile.name))) {
                continue;
            }

            recordings.add(buildImportedRecording(folderFile, settings));
            knownPlayableUris.add(folderFile.uri);
            knownPlayableNames.add(normalizedNameKey(folderFile.name));
            result.importedCount++;
        }

        if (result.changed()) {
            storage.saveRecordings(recordings);
        }

        result.setMessage(buildResultMessage(result));
        return result;
    }

    private List<FolderFile> listRecordingFiles(Uri treeUri) {
        List<FolderFile> files = new ArrayList<>();
        String treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId);
        String[] projection = new String[] {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };

        try (Cursor cursor = context.getContentResolver().query(childrenUri, projection, null, null, null)) {
            if (cursor == null) {
                return files;
            }

            int documentIdIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            int modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);

            while (cursor.moveToNext()) {
                String documentId = documentIdIndex >= 0 ? cursor.getString(documentIdIndex) : "";
                String name = nameIndex >= 0 ? cursor.getString(nameIndex) : "";
                String mimeType = mimeIndex >= 0 ? cursor.getString(mimeIndex) : "";

                if (isBlank(documentId) || !isRecordingFileName(name, mimeType)) {
                    continue;
                }

                FolderFile file = new FolderFile();
                file.name = RecordingFileManager.stripHiddenTrashPrefix(name);
                file.mimeType = mimeType == null ? "" : mimeType;
                file.uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId).toString();
                file.size = sizeIndex >= 0 ? Math.max(0L, cursor.getLong(sizeIndex)) : 0L;
                file.lastModified = modifiedIndex >= 0 ? Math.max(0L, cursor.getLong(modifiedIndex)) : 0L;
                files.add(file);
            }
        }

        return files;
    }

    private RecordingItem relinkMissingRecording(List<RecordingItem> recordings, FolderFile folderFile) {
        if (recordings == null || folderFile == null) {
            return null;
        }

        String folderNameKey = normalizedNameKey(folderFile.name);
        if (isBlank(folderNameKey)) {
            return null;
        }

        for (RecordingItem recording : recordings) {
            if (recording == null || recording.isPlayableCompletedFile()) {
                continue;
            }

            if (!recording.isFinished()) {
                continue;
            }

            if (matchesRecordingFile(recording, folderNameKey)) {
                markRecordingAsSelectedFolderFile(recording, folderFile, "Relinked selected folder file.");
                return recording;
            }
        }

        return null;
    }

    private boolean matchesRecordingFile(RecordingItem recording, String folderNameKey) {
        if (recording == null || isBlank(folderNameKey)) {
            return false;
        }

        List<String> candidatePaths = new ArrayList<>();
        candidatePaths.add(recording.getFinalMp4Path());
        candidatePaths.add(recording.getTempTsPath());
        candidatePaths.addAll(recording.getTempSegmentPaths());

        String displayTitleKey = normalizedNameKey(recording.getDisplayTitle());
        for (String path : candidatePaths) {
            String pathNameKey = normalizedNameKey(displayNameFromPath(path));
            if (!isBlank(pathNameKey) && (pathNameKey.equals(folderNameKey) || namesLookRelated(pathNameKey, folderNameKey))) {
                return true;
            }
        }

        return !isBlank(displayTitleKey) && namesLookRelated(displayTitleKey, folderNameKey);
    }

    private boolean namesLookRelated(String left, String right) {
        if (isBlank(left) || isBlank(right)) {
            return false;
        }
        return left.contains(right) || right.contains(left);
    }

    private RecordingItem buildImportedRecording(FolderFile folderFile, AppSettings settings) {
        String title = titleFromFileName(folderFile.name);
        RecordingItem recording = new RecordingItem(
            "",
            "Imported recordings",
            "",
            "",
            "",
            title,
            settings == null ? "480p" : settings.getDownloadQuality(),
            "",
            ""
        );
        markRecordingAsSelectedFolderFile(recording, folderFile, "Imported from selected save folder.");
        return recording;
    }

    private void markRecordingAsSelectedFolderFile(RecordingItem recording, FolderFile folderFile, String message) {
        recording.updateProgress(folderFile.size, recording.getDurationSeconds());
        recording.markCompleted(folderFile.uri);
        recording.markCopiedToSelectedFolder(storage.loadSettings().getSaveLocationDisplayName());
        recording.hideFromDownloading();
        recording.setErrorMessage(message);
    }

    private boolean isRecordingFileName(String name, String mimeType) {
        String lowerName = name == null ? "" : name.trim().toLowerCase(Locale.US);
        String lowerMime = mimeType == null ? "" : mimeType.trim().toLowerCase(Locale.US);
        return lowerName.endsWith(".mp4")
            || lowerName.endsWith(".ts")
            || "video/mp4".equals(lowerMime)
            || "video/mp2t".equals(lowerMime);
    }

    private String titleFromFileName(String fileName) {
        String cleaned = RecordingFileManager.stripHiddenTrashPrefix(fileName);
        if (isBlank(cleaned)) {
            return "Imported recording";
        }
        int dot = cleaned.lastIndexOf('.');
        if (dot > 0) {
            cleaned = cleaned.substring(0, dot);
        }
        cleaned = cleaned.replace('_', ' ').trim();
        return isBlank(cleaned) ? "Imported recording" : cleaned;
    }

    private String displayNameFromPath(String path) {
        if (isBlank(path)) {
            return "";
        }
        String trimmed = path.trim();
        if (trimmed.toLowerCase(Locale.US).startsWith("content://")) {
            int slash = trimmed.lastIndexOf('/');
            return slash >= 0 && slash + 1 < trimmed.length() ? Uri.decode(trimmed.substring(slash + 1)) : trimmed;
        }
        return new File(trimmed).getName();
    }

    private String normalizedNameKey(String name) {
        if (isBlank(name)) {
            return "";
        }
        String cleaned = RecordingFileManager.stripHiddenTrashPrefix(name);
        cleaned = cleaned.toLowerCase(Locale.US).trim();
        int dot = cleaned.lastIndexOf('.');
        if (dot > 0) {
            cleaned = cleaned.substring(0, dot);
        }
        cleaned = cleaned.replaceAll("\\s*\\(\\d+\\)$", "");
        cleaned = cleaned.replaceAll("[^a-z0-9]+", "_");
        cleaned = cleaned.replaceAll("_+", "_");
        while (cleaned.startsWith("_")) cleaned = cleaned.substring(1);
        while (cleaned.endsWith("_")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        return cleaned;
    }

    private String buildResultMessage(Result result) {
        if (result.importedCount <= 0 && result.relinkedCount <= 0) {
            return "Selected folder refreshed. No new recordings found.";
        }
        return "Selected folder refreshed. Imported "
            + result.importedCount
            + ", relinked "
            + result.relinkedCount
            + ".";
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
