package com.livemonitor.app;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Moves completed server downloads from private app storage into the selected save folder. */
public final class ServerDownloadCompleteReceiver extends BroadcastReceiver {
    private static final String TAG = "ServerDownload";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
        long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
        if (id < 0L) return;
        PendingResult pending = goAsync();
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            String message;
            Uri attemptedDestination = null;
            try {
                DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
                Uri localUri = null;
                int status = -1;
                try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
                    if (cursor != null && cursor.moveToFirst()) {
                        status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        String value = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
                        if (value != null) localUri = Uri.parse(value);
                    }
                }
                if (status != DownloadManager.STATUS_SUCCESSFUL || localUri == null || !"file".equals(localUri.getScheme())) {
                    String result = "Download failed or has no local file (status=" + status + ")";
                    String privatePath = privateFilePath(context, id);
                    Log.e(TAG, "id=" + id + " final=" + privatePath + " result=" + result);
                    message = result + ". File location: " + privatePath;
                } else {
                    File source = new File(localUri.getPath());
                    RecordingFileManager files = new RecordingFileManager(context);
                    if (!files.hasCustomSaveLocation()) {
                        message = "No save folder selected. File kept at " + source.getAbsolutePath();
                        Log.w(TAG, "id=" + id + " final=" + source.getAbsolutePath() + " result=no selected folder");
                    } else {
                        Uri tree = files.getCustomSaveLocationUri();
                        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
                        Uri destination = DocumentsContract.createDocument(context.getContentResolver(), parent,
                            mimeType(source.getName()), source.getName());
                        if (destination == null) throw new IllegalStateException("Could not create destination file");
                        attemptedDestination = destination;
                        try (InputStream input = new FileInputStream(source);
                             OutputStream output = context.getContentResolver().openOutputStream(destination, "w")) {
                            if (output == null) throw new IllegalStateException("Could not open destination file");
                            byte[] buffer = new byte[64 * 1024];
                            int count;
                            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                        } catch (Exception copyError) {
                            try { DocumentsContract.deleteDocument(context.getContentResolver(), destination); }
                            catch (Exception ignored) { }
                            throw copyError;
                        }
                        if (!source.delete()) Log.w(TAG, "id=" + id + " copied but private file cleanup failed: " + source.getAbsolutePath());
                        message = "Saved to selected folder: " + source.getName();
                        Log.i(TAG, "id=" + id + " final=" + destination + " result=success");
                    }
                }
            } catch (Exception error) {
                String privatePath = privateFilePath(context, id);
                message = "Could not save server download. Private file kept at " + privatePath + " (" + error.getMessage() + ")";
                Log.e(TAG, "id=" + id + " destination=" + (attemptedDestination == null ? "selected folder" : attemptedDestination)
                    + " private=" + privatePath + " result=copy failed", error);
            }
            final String notice = message;
            new android.os.Handler(context.getMainLooper()).post(() -> Toast.makeText(context, notice, Toast.LENGTH_LONG).show());
            pending.finish();
        });
    }

    private static String privateFilePath(Context context, long id) {
        try {
            DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
                if (cursor != null && cursor.moveToFirst()) {
                    String uri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
                    if (uri != null) return Uri.parse(uri).getPath();
                }
            }
        } catch (Exception ignored) { }
        return context.getFilesDir().getAbsolutePath();
    }

    private static String mimeType(String name) {
        String lower = name == null ? "" : name.toLowerCase(java.util.Locale.US);
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".ts")) return "video/mp2t";
        return "video/mp4";
    }
}
