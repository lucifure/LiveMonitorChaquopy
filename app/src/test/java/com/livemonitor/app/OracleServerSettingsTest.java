package com.livemonitor.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class OracleServerSettingsTest {
    @Test public void allowsThePairedTailscaleHttpEndpoint() {
        assertEquals("http://100.87.137.48:8787",
            OracleServerSettings.normalizeHttpsBaseUrl("http://100.87.137.48:8787/"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAPathWhichWouldChangeTheStatusEndpoint() {
        OracleServerSettings.normalizeHttpsBaseUrl("http://100.87.137.48:8787/api");
    }
}
