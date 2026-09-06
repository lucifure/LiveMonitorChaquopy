package com.livemonitor.app;

import static org.junit.Assert.assertFalse;

import java.util.Arrays;
import org.junit.Test;

public class RemoteConfigTest {
    @Test
    public void playerClientFallbackExcludesUnsupportedTvEmbeddedClient() {
        RemoteConfig config = new RemoteConfig();

        assertFalse(config.getYtDlpPlayerClientFallback().contains("tv_embedded"));

        config.setYtDlpPlayerClientFallback(Arrays.asList("web", "tv_embedded", "ios"));

        assertFalse(config.getYtDlpPlayerClientFallback().contains("tv_embedded"));
    }
}
