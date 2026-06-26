package com.livemonitor.app;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Saves full log text as a user-shareable .txt file. */
final class LogFileSaver {
    private LogFileSaver() {}

    static String save(Context context, AppStorage storage, String text) throws Exception {
        String fileName = "LiveMonitor_Log_"
            + new SimpleDateFormat("ddMMyyyy_HHmmss", Locale.US).format(new Date())
            + ".txt";
        byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        AppSettings settings = storage.loadSettings();
        String saveLocationUri = settings.getSaveLocationUri();

        if (saveLocationUri != null && !saveLocationUri.trim().isEmpty()) {
            Uri folderUri = Uri.parse(saveLocationUri);
            Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(
                folderUri,
                DocumentsContract.getTreeDocumentId(folderUri)
            );
            Uri destination = DocumentsContract.createDocument(
                context.getContentResolver(),
                parentUri,
                "text/plain",
                fileName
            );

            if (destination == null) {
                throw new IllegalStateException("Could not create log file.");
            }

            try (OutputStream output = context.getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) {
                    throw new IllegalStateException("Could not open log file.");
                }
                output.write(bytes);
            }
            return fileName;
        }

        RecordingFileManager fileManager = new RecordingFileManager(context);
        File directory = fileManager.getBaseRecordingDirectory();
        File outputFile = new File(directory, fileName);
        try (OutputStream output = new FileOutputStream(outputFile)) {
            output.write(bytes);
        }
        return fileName;
    }
}
