package com.livemonitor.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class ServerRecordingGrouperTest {
    @Test
    public void groupsFragmentsMetadataFinishedFilesAndLeftoverParts() {
        List<ServerRecordingGrouper.RemoteFile> files = Arrays.asList(
            new ServerRecordingGrouper.RemoteFile("Show_21102026.f395.mp4", 100, 300, false),
            new ServerRecordingGrouper.RemoteFile("Show_21102026.f140.mp4", 200, 300, false),
            new ServerRecordingGrouper.RemoteFile("Show_21102026.f395.mp4.ytdl", 5000, 300, true),
            new ServerRecordingGrouper.RemoteFile("Finished.mp4", 700, 200, false),
            new ServerRecordingGrouper.RemoteFile("Failed.mp4.part", 40, 100, false),
            new ServerRecordingGrouper.RemoteFile("Failed.mkv.part", 60, 100, false),
            new ServerRecordingGrouper.RemoteFile("Failed.mp4.ytdl", 900, 100, false),
            new ServerRecordingGrouper.RemoteFile("Orphan.mp4.ytdl", 1000, 50, false)
        );

        List<ServerRecordingGrouper.Group> groups = ServerRecordingGrouper.group(files);

        assertEquals(3, groups.size());
        ServerRecordingGrouper.Group active = groups.get(0);
        assertEquals("Show_21102026", active.baseName);
        assertEquals(ServerRecordingGrouper.Type.IN_PROGRESS, active.type);
        assertEquals(2, active.mediaFiles.size());
        assertEquals(3, active.files.size());
        assertEquals(300L, active.totalSize);

        ServerRecordingGrouper.Group finished = groups.get(1);
        assertEquals(ServerRecordingGrouper.Type.SINGLE_FILE, finished.type);
        assertEquals(700L, finished.totalSize);
        assertTrue(finished.isSingleFile());

        ServerRecordingGrouper.Group partial = groups.get(2);
        assertEquals("Failed", partial.baseName);
        assertEquals(ServerRecordingGrouper.Type.PARTIAL, partial.type);
        assertEquals(2, partial.mediaFiles.size());
        assertEquals(3, partial.files.size());
        assertEquals(100L, partial.totalSize);
        assertFalse(partial.isInProgress());
    }
}
