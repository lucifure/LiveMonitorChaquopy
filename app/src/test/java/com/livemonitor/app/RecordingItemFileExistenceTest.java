package com.livemonitor.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;

public class RecordingItemFileExistenceTest {
    @Test
    public void finalFileMustExistAndContainData() throws Exception {
        File file = File.createTempFile("recording-existence", ".mp4");
        try {
            RecordingItem recording = itemAt(file.getAbsolutePath());
            assertFalse(recording.hasExistingFinalMp4File());
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(new byte[] { 1 });
            }
            assertTrue(recording.hasExistingFinalMp4File());
            assertTrue(file.delete());
            assertFalse(recording.hasExistingFinalMp4File());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @Test
    public void contentUriIsNotAssumedToExistWithoutAResolverCheck() {
        assertFalse(itemAt("content://example/document/42").hasExistingFinalMp4File());
    }

    private RecordingItem itemAt(String path) {
        RecordingItem recording = new RecordingItem("", "", "", "", "", "", "480p", "", path);
        recording.markCompleted(path);
        return recording;
    }
}
