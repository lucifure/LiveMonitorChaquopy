package com.livemonitor.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class RecorderCommandBuilderTest {
    @Test
    public void defaultQualityRemains480p() {
        AppSettings settings = new AppSettings();

        assertEquals(AppSettings.QUALITY_480P, settings.getDownloadQuality());
        assertTrue(settings.buildYtDlpFormatSelector().contains("height<=480"));
        assertFalse(settings.buildYtDlpFormatSelector().contains("/best[protocol^=m3u8]"));
    }

    @Test
    public void dashPrimaryRecorderUsesSettingsQualitySelector() {
        RecorderCommandBuilder builder = new RecorderCommandBuilder();
        AppSettings settings = new AppSettings();
        settings.setDownloadQuality(AppSettings.QUALITY_360P);

        List<String> args = builder.buildDashRecordArgs(
            "auto",
            "https://www.youtube.com/watch?v=abc123",
            "/tmp/out.mp4",
            "/tmp/cache",
            settings,
            new RemoteConfig(),
            true,
            false
        );

        int formatIndex = args.indexOf("-f");
        assertTrue(formatIndex >= 0);
        assertEquals(settings.buildYtDlpFormatSelector(), args.get(formatIndex + 1));
        assertFalse(args.contains("bv*[height<=480]+ba/b"));
    }
}
