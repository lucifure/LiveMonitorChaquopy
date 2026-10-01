package com.livemonitor.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Connection details for the Oracle recorder. These are deliberately separate from
 * remote-config settings: the token is never sent to GitHub, yt-dlp, or a proxy.
 * SharedPreferences is private to this application. Android backup is disabled
 * for the application so pairing credentials are not copied to cloud backup.
 */
public final class OracleServerSettings {
    private static final String PREFERENCES_NAME = "oracle_server_settings";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_DEVICE_TOKEN = "device_token";

    private OracleServerSettings() { }

    public static String getBaseUrl(Context context) {
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BASE_URL, "");
    }

    public static boolean isConfigured(Context context) {
        return !getBaseUrl(context).isEmpty() && !getDeviceToken(context).isEmpty();
    }

    public static String getDeviceToken(Context context) {
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEVICE_TOKEN, "");
    }

    /** Saves a normalized HTTPS URL or an HTTP URL on the Tailscale 100.x range. */
    public static void save(Context context, String baseUrl, String deviceToken) {
        String normalizedUrl = normalizeHttpsBaseUrl(baseUrl);
        String normalizedToken = deviceToken == null ? "" : deviceToken.trim();
        if (normalizedToken.isEmpty()) {
            normalizedToken = getDeviceToken(context);
        }
        if (normalizedToken.isEmpty()) {
            throw new IllegalArgumentException("Device token is required.");
        }
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_BASE_URL, normalizedUrl)
            .putString(KEY_DEVICE_TOKEN, normalizedToken)
            .apply();
    }

    public static String normalizeHttpsBaseUrl(String value) {
        String raw = value == null ? "" : value.trim();
        final URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Server URL is malformed.");
        }
        String host = uri.getHost();
        boolean isHttps = "https".equalsIgnoreCase(uri.getScheme());
        boolean isTailscaleHttp = "http".equalsIgnoreCase(uri.getScheme()) && isTailscale100Address(host);
        if ((!isHttps && !isTailscaleHttp) || host == null || host.isEmpty()
            || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
            || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("Use HTTPS, or an HTTP 100.x Tailscale server URL without credentials.");
        }
        String normalized = uri.toString();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private static boolean isTailscale100Address(String host) {
        if (host == null) return false;
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4 || !"100".equals(octets[0])) return false;
        for (String octet : octets) {
            try {
                int value = Integer.parseInt(octet);
                if (value < 0 || value > 255) return false;
            } catch (NumberFormatException error) {
                return false;
            }
        }
        return true;
    }
}
