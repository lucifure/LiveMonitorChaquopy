package com.livemonitor.app;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Saves full log text as a user-shareable .txt file. */
final class LogFileSaver {
    private LogFileSaver() {}

    static String save(Context context, AppStorage storage, String text) throws Exception {
        return save(context, storage, text, "LiveMonitor_Log_");
    }

    static String save(Context context, AppStorage storage, String text, String filePrefix) throws Exception {
        String fileName = createFileName(filePrefix);
        try (OutputStream output = openDestination(context, storage, fileName)) {
            output.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
        }
        return fileName;
    }

    static String saveFiles(
        Context context,
        AppStorage storage,
        List<File> filesOldestFirst,
        String filePrefix
    ) throws Exception {
        String fileName = createFileName(filePrefix);
        try (BufferedOutputStream output = new BufferedOutputStream(
            openDestination(context, storage, fileName)
        )) {
            byte[] buffer = new byte[8192];
            if (filesOldestFirst != null) {
                for (File file : filesOldestFirst) {
                    if (file == null || !file.isFile()) continue;
                    try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            output.write(buffer, 0, count);
                        }
                    }
                }
            }
            output.flush();
        }
        return fileName;
    }

    private static String createFileName(String filePrefix) {
        return filePrefix
            + new SimpleDateFormat("ddMMyyyy_HHmmss", Locale.US).format(new Date())
            + ".txt";
    }

    private static OutputStream openDestination(Context context, AppStorage storage, String fileName) throws Exception {
        String saveLocationUri = storage.loadSettings().getSaveLocationUri();
        if (saveLocationUri != null && !saveLocationUri.trim().isEmpty()) {
            Uri folderUri = Uri.parse(saveLocationUri);
            Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(
                folderUri,
                DocumentsContract.getTreeDocumentId(folderUri)
            );
            Uri destination = DocumentsContract.createDocument(
                context.getContentResolver(), parentUri, "text/plain", fileName
            );
            if (destination == null) throw new IllegalStateException("Could not create log file.");
            OutputStream output = context.getContentResolver().openOutputStream(destination, "w");
            if (output == null) throw new IllegalStateException("Could not open log file.");
            return output;
        }

        File directory = new RecordingFileManager(context).getBaseRecordingDirectory();
        File outputFile = new File(directory, fileName);
        return new FileOutputStream(outputFile);
    }
}
