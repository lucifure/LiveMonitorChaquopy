package com.livemonitor.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ChannelAdapterTest {
    @Test
    public void recordingTitleIsShownWhenItDiffersFromVideoId() {
        assertEquals("A live stream", ChannelAdapter.recordingSubLine("A live stream", "video-id"));
    }

    @Test
    public void blankRecordingTitleFallsBackToVideoId() {
        assertEquals("video-id", ChannelAdapter.recordingSubLine("  ", "video-id"));
    }

    @Test
    public void recordingTitleEqualToVideoIdFallsBackToVideoId() {
        assertEquals("video-id", ChannelAdapter.recordingSubLine("video-id", "video-id"));
    }

    @Test
    public void missingRecordingTitleUsesKnownChannelVideoId() {
        assertEquals("video-id", ChannelAdapter.recordingSubLine(null, "video-id"));
    }
}
