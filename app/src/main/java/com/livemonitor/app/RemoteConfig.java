package com.livemonitor.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Remote configuration fetched from a hosted config.json file.
 *
 * Purpose:
 * - Update YouTube client list without rebuilding APK.
 * - Rotate API keys without rebuilding APK.
 * - Update visitorData endpoint without rebuilding APK.
 * - Keep a validated cached config for offline fallback.
 *
 * Important:
 * If config.json is hosted in a public GitHub repo, values inside it are public.
 * Do not treat API keys in this config as secret.
 */
public class RemoteConfig {

    private static final String JSON_CONFIG_VERSION = "configVersion";
    private static final String JSON_MIN_APP_VERSION = "minAppVersion";
    private static final String JSON_UPDATED_AT = "updatedAt";
    private static final String JSON_YOUTUBE_CLIENTS = "youtubeClients";
    private static final String JSON_API_KEYS = "apiKeys";
    private static final String JSON_VISITOR_DATA_URL = "visitorDataUrl";
    private static final String JSON_YOUTUBE_EXTRACTOR_MODE = "youtubeExtractorMode";
    private static final String JSON_YTDLP_EXECUTABLE = "ytDlpExecutable";
    private static final String JSON_YTDLP_EXTRACTOR_ARGS = "ytDlpExtractorArgs";
    private static final String JSON_YTDLP_EXTRA_ARGS = "ytDlpExtraArgs";
    private static final String JSON_YTDLP_PLAYER_CLIENT_FALLBACK = "ytDlpPlayerClientFallback";
    private static final String JSON_YTDLP_COOKIES_PATH = "ytDlpCookiesPath";
    private static final String JSON_YTDLP_COOKIES_FROM_BROWSER = "ytDlpCookiesFromBrowser";
    private static final String JSON_YTDLP_COOKIE_HEADER = "ytDlpCookieHeader";
    private static final String JSON_YTDLP_RESOLVE_TIMEOUT_SECONDS = "ytDlpResolveTimeoutSeconds";
    private static final String JSON_JAVA_HLS_FALLBACK_ENABLED = "javaHlsFallbackEnabled";
    private static final String JSON_INNERTUBE_BASE_URL = "innertubeBaseUrl";
    private static final String JSON_WEB_PLAYER_BASE_URL = "webPlayerBaseUrl";
    private static final String JSON_USER_AGENT = "userAgent";
    private static final String JSON_ENABLED = "enabled";
    private static final String JSON_NOTES = "notes";
    private static final String JSON_FETCHED_AT = "fetchedAt";

    private int configVersion;
    private int minAppVersion;
    private String updatedAt;
    private List<YoutubeClient> youtubeClients;
    private List<String> apiKeys;
    private String visitorDataUrl;
    private String youtubeExtractorMode;
    private String ytDlpExecutable;
    private String ytDlpExtractorArgs;
    private List<String> ytDlpExtraArgs;
    private List<String> ytDlpPlayerClientFallback;
    private String ytDlpCookiesPath;
    private String ytDlpCookiesFromBrowser;
    private String ytDlpCookieHeader;
    private int ytDlpResolveTimeoutSeconds;
    private boolean javaHlsFallbackEnabled;
    private String innertubeBaseUrl;
    private String webPlayerBaseUrl;
    private String userAgent;
    private boolean enabled;
    private String notes;
    private long fetchedAt;

    public RemoteConfig() {
        this.configVersion = 2;
        this.minAppVersion = 1;
        this.updatedAt = "";
        this.youtubeClients = buildDefaultClients();
        this.apiKeys = new ArrayList<>();
        this.visitorDataUrl = "";
        this.youtubeExtractorMode = "yt-dlp-first";
        this.ytDlpExecutable = "yt-dlp";
        this.ytDlpExtractorArgs = "";
        this.ytDlpExtraArgs = new ArrayList<>();
        this.ytDlpPlayerClientFallback = buildDefaultPlayerClientFallback();
        this.ytDlpCookiesPath = "";
        this.ytDlpCookiesFromBrowser = "";
        this.ytDlpCookieHeader = "";
        this.ytDlpResolveTimeoutSeconds = 45;
        this.javaHlsFallbackEnabled = true;
        this.innertubeBaseUrl = "https://www.youtube.com/youtubei/v1";
        this.webPlayerBaseUrl = "https://www.youtube.com";
        this.userAgent = buildDefaultUserAgent();
        this.enabled = true;
        this.notes = "";
        this.fetchedAt = 0L;
    }

    public RemoteConfig(
        int configVersion,
        int minAppVersion,
        String updatedAt,
        List<YoutubeClient> youtubeClients,
        List<String> apiKeys,
        String visitorDataUrl,
        String youtubeExtractorMode,
        String ytDlpExecutable,
        String ytDlpExtractorArgs,
        List<String> ytDlpExtraArgs,
        List<String> ytDlpPlayerClientFallback,
        String ytDlpCookiesPath,
        String ytDlpCookiesFromBrowser,
        String ytDlpCookieHeader,
        int ytDlpResolveTimeoutSeconds,
        boolean javaHlsFallbackEnabled,
        String innertubeBaseUrl,
        String webPlayerBaseUrl,
        String userAgent,
        boolean enabled,
        String notes,
        long fetchedAt
    ) {
        this.configVersion = Math.max(1, configVersion);
        this.minAppVersion = Math.max(1, minAppVersion);
        this.updatedAt = nullToEmpty(updatedAt);
        this.youtubeClients = sanitizeClients(youtubeClients);
        this.apiKeys = sanitizeStringList(apiKeys);
        this.visitorDataUrl = nullToEmpty(visitorDataUrl);
        this.youtubeExtractorMode = normalizeExtractorMode(youtubeExtractorMode);
        this.ytDlpExecutable = isBlank(ytDlpExecutable) ? "yt-dlp" : ytDlpExecutable.trim();
        this.ytDlpExtractorArgs = nullToEmpty(ytDlpExtractorArgs).trim();
        this.ytDlpExtraArgs = sanitizeStringList(ytDlpExtraArgs);
        this.ytDlpPlayerClientFallback = sanitizePlayerClientFallback(ytDlpPlayerClientFallback);
        this.ytDlpCookiesPath = nullToEmpty(ytDlpCookiesPath).trim();
        this.ytDlpCookiesFromBrowser = nullToEmpty(ytDlpCookiesFromBrowser).trim();
        this.ytDlpCookieHeader = nullToEmpty(ytDlpCookieHeader).trim();
        this.ytDlpResolveTimeoutSeconds = clamp(ytDlpResolveTimeoutSeconds, 10, 300);
        this.javaHlsFallbackEnabled = javaHlsFallbackEnabled;
        this.innertubeBaseUrl = isBlank(innertubeBaseUrl)
            ? "https://www.youtube.com/youtubei/v1"
            : innertubeBaseUrl.trim();
        this.webPlayerBaseUrl = isBlank(webPlayerBaseUrl)
            ? "https://www.youtube.com"
            : webPlayerBaseUrl.trim();
        this.userAgent = isBlank(userAgent) ? buildDefaultUserAgent() : userAgent.trim();
        this.enabled = enabled;
        this.notes = nullToEmpty(notes);
        this.fetchedAt = Math.max(0L, fetchedAt);
    }

    public static RemoteConfig fromJson(JSONObject json) throws JSONException {
        if (json == null) {
            return new RemoteConfig();
        }

        List<YoutubeClient> clients = new ArrayList<>();
        JSONArray clientsArray = json.optJSONArray(JSON_YOUTUBE_CLIENTS);

        if (clientsArray != null) {
            for (int i = 0; i < clientsArray.length(); i++) {
                JSONObject clientJson = clientsArray.optJSONObject(i);

                if (clientJson != null) {
                    YoutubeClient client = YoutubeClient.fromJson(clientJson);

                    if (client.isValid()) {
                        clients.add(client);
                    }
                }
            }
        }

        List<String> apiKeys = new ArrayList<>();
        JSONArray apiKeysArray = json.optJSONArray(JSON_API_KEYS);

        if (apiKeysArray != null) {
            for (int i = 0; i < apiKeysArray.length(); i++) {
                String key = apiKeysArray.optString(i, "").trim();

                if (!key.isEmpty()) {
                    apiKeys.add(key);
                }
            }
        }

        List<String> ytDlpExtraArgs = new ArrayList<>();
        JSONArray ytDlpExtraArgsArray = json.optJSONArray(JSON_YTDLP_EXTRA_ARGS);

        if (ytDlpExtraArgsArray != null) {
            for (int i = 0; i < ytDlpExtraArgsArray.length(); i++) {
                String arg = ytDlpExtraArgsArray.optString(i, "").trim();

                if (!arg.isEmpty()) {
                    ytDlpExtraArgs.add(arg);
                }
            }
        }

        List<String> ytDlpPlayerClientFallback = new ArrayList<>();
        JSONArray ytDlpPlayerClientFallbackArray = json.optJSONArray(JSON_YTDLP_PLAYER_CLIENT_FALLBACK);

        if (ytDlpPlayerClientFallbackArray != null) {
            for (int i = 0; i < ytDlpPlayerClientFallbackArray.length(); i++) {
                String client = ytDlpPlayerClientFallbackArray.optString(i, "").trim();

                if (!client.isEmpty()) {
                    ytDlpPlayerClientFallback.add(client);
                }
            }
        }

        RemoteConfig defaults = new RemoteConfig();

        return new RemoteConfig(
            json.optInt(JSON_CONFIG_VERSION, defaults.getConfigVersion()),
            json.optInt(JSON_MIN_APP_VERSION, defaults.getMinAppVersion()),
            json.optString(JSON_UPDATED_AT, defaults.getUpdatedAt()),
            clients.isEmpty() ? defaults.getYoutubeClients() : clients,
            apiKeys,
            json.optString(JSON_VISITOR_DATA_URL, defaults.getVisitorDataUrl()),
            json.optString(JSON_YOUTUBE_EXTRACTOR_MODE, defaults.getYoutubeExtractorMode()),
            json.optString(JSON_YTDLP_EXECUTABLE, defaults.getYtDlpExecutable()),
            json.optString(JSON_YTDLP_EXTRACTOR_ARGS, defaults.getYtDlpExtractorArgs()),
            ytDlpExtraArgs.isEmpty() ? defaults.getYtDlpExtraArgs() : ytDlpExtraArgs,
            ytDlpPlayerClientFallback.isEmpty()
                ? defaults.getYtDlpPlayerClientFallback()
                : ytDlpPlayerClientFallback,
            json.optString(JSON_YTDLP_COOKIES_PATH, defaults.getYtDlpCookiesPath()),
            json.optString(JSON_YTDLP_COOKIES_FROM_BROWSER, defaults.getYtDlpCookiesFromBrowser()),
            json.optString(JSON_YTDLP_COOKIE_HEADER, defaults.getYtDlpCookieHeader()),
            json.optInt(JSON_YTDLP_RESOLVE_TIMEOUT_SECONDS, defaults.getYtDlpResolveTimeoutSeconds()),
            json.optBoolean(JSON_JAVA_HLS_FALLBACK_ENABLED, defaults.isJavaHlsFallbackEnabled()),
            json.optString(JSON_INNERTUBE_BASE_URL, defaults.getInnertubeBaseUrl()),
            json.optString(JSON_WEB_PLAYER_BASE_URL, defaults.getWebPlayerBaseUrl()),
            json.optString(JSON_USER_AGENT, defaults.getUserAgent()),
            json.optBoolean(JSON_ENABLED, defaults.isEnabled()),
            json.optString(JSON_NOTES, defaults.getNotes()),
            json.optLong(JSON_FETCHED_AT, System.currentTimeMillis())
        );
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();

        json.put(JSON_CONFIG_VERSION, configVersion);
        json.put(JSON_MIN_APP_VERSION, minAppVersion);
        json.put(JSON_UPDATED_AT, updatedAt);

        JSONArray clientsArray = new JSONArray();

        for (YoutubeClient client : youtubeClients) {
            clientsArray.put(client.toJson());
        }

        json.put(JSON_YOUTUBE_CLIENTS, clientsArray);

        JSONArray apiKeysArray = new JSONArray();

        for (String apiKey : apiKeys) {
            apiKeysArray.put(apiKey);
        }

        json.put(JSON_API_KEYS, apiKeysArray);
        json.put(JSON_VISITOR_DATA_URL, visitorDataUrl);
        json.put(JSON_YOUTUBE_EXTRACTOR_MODE, youtubeExtractorMode);
        json.put(JSON_YTDLP_EXECUTABLE, ytDlpExecutable);
        json.put(JSON_YTDLP_EXTRACTOR_ARGS, ytDlpExtractorArgs);

        JSONArray ytDlpExtraArgsArray = new JSONArray();

        for (String arg : ytDlpExtraArgs) {
            ytDlpExtraArgsArray.put(arg);
        }

        json.put(JSON_YTDLP_EXTRA_ARGS, ytDlpExtraArgsArray);

        JSONArray ytDlpPlayerClientFallbackArray = new JSONArray();

        for (String client : ytDlpPlayerClientFallback) {
            ytDlpPlayerClientFallbackArray.put(client);
        }

        json.put(JSON_YTDLP_PLAYER_CLIENT_FALLBACK, ytDlpPlayerClientFallbackArray);
        json.put(JSON_YTDLP_COOKIES_PATH, ytDlpCookiesPath);
        json.put(JSON_YTDLP_COOKIES_FROM_BROWSER, ytDlpCookiesFromBrowser);
        json.put(JSON_YTDLP_COOKIE_HEADER, ytDlpCookieHeader);
        json.put(JSON_YTDLP_RESOLVE_TIMEOUT_SECONDS, ytDlpResolveTimeoutSeconds);
        json.put(JSON_JAVA_HLS_FALLBACK_ENABLED, javaHlsFallbackEnabled);
        json.put(JSON_INNERTUBE_BASE_URL, innertubeBaseUrl);
        json.put(JSON_WEB_PLAYER_BASE_URL, webPlayerBaseUrl);
        json.put(JSON_USER_AGENT, userAgent);
        json.put(JSON_ENABLED, enabled);
        json.put(JSON_NOTES, notes);
        json.put(JSON_FETCHED_AT, fetchedAt);

        return json;
    }

    public boolean isValidForAppVersion(int appVersionCode) {
        return enabled
            && configVersion > 0
            && appVersionCode >= minAppVersion
            && !youtubeClients.isEmpty()
            && !isBlank(innertubeBaseUrl)
            && !isBlank(webPlayerBaseUrl)
            && !isBlank(userAgent);
    }

    public boolean hasApiKeys() {
        return !apiKeys.isEmpty();
    }

    public String getPrimaryApiKey() {
        if (apiKeys.isEmpty()) {
            return "";
        }

        return apiKeys.get(0);
    }

    public String getApiKeyForAttempt(int attempt) {
        if (apiKeys.isEmpty()) {
            return "";
        }

        int safeAttempt = Math.max(0, attempt);
        return apiKeys.get(safeAttempt % apiKeys.size());
    }

    public YoutubeClient getPrimaryClient() {
        if (youtubeClients.isEmpty()) {
            return new YoutubeClient();
        }

        return youtubeClients.get(0);
    }

    public YoutubeClient getClientForAttempt(int attempt) {
        if (youtubeClients.isEmpty()) {
            return new YoutubeClient();
        }

        int safeAttempt = Math.max(0, attempt);
        return youtubeClients.get(safeAttempt % youtubeClients.size());
    }

    public boolean isCacheFresh(int ttlMinutes) {
        if (fetchedAt <= 0L) {
            return false;
        }

        int safeTtlMinutes = Math.max(1, ttlMinutes);
        long ageMillis = System.currentTimeMillis() - fetchedAt;
        long ttlMillis = safeTtlMinutes * 60_000L;

        return ageMillis >= 0L && ageMillis <= ttlMillis;
    }

    public String toSummary() {
        return "RemoteConfig{"
            + "version=" + configVersion
            + ", minAppVersion=" + minAppVersion
            + ", clients=" + youtubeClients.size()
            + ", apiKeys=" + apiKeys.size()
            + ", enabled=" + enabled
            + ", fetchedAt=" + fetchedAt
            + "}";
    }

    public String buildDebugSummary() {
        return toSummary()
            + ", updatedAt=" + updatedAt
            + ", visitorDataUrl=" + describeOptionalUrl(visitorDataUrl)
            + ", extractorMode=" + youtubeExtractorMode
            + ", ytDlpExecutable=" + ytDlpExecutable
            + ", ytDlpExtraArgs=" + ytDlpExtraArgs.size()
            + ", ytDlpPlayerClientFallback=" + ytDlpPlayerClientFallback
            + ", ytDlpCookies=" + hasYtDlpCookies()
            + ", javaHlsFallback=" + javaHlsFallbackEnabled
            + ", innertubeBaseUrl=" + innertubeBaseUrl
            + ", webPlayerBaseUrl=" + webPlayerBaseUrl
            + ", notes=" + notes;
    }

    public void markFetchedNow() {
        fetchedAt = System.currentTimeMillis();
    }

    public static List<YoutubeClient> getDefaultClients() {
        return Collections.unmodifiableList(buildDefaultClients());
    }

    private static List<YoutubeClient> buildDefaultClients() {
        List<YoutubeClient> clients = new ArrayList<>();

        // Prefer current web-style clients for HLS. Mobile app clients are kept
        // later in the list because YouTube increasingly expects attestation for
        // IOS/ANDROID player requests, which can otherwise fail with HTTP 400
        // "Precondition check failed" before a manifest is returned.
        clients.add(new YoutubeClient(
            "WEB",
            "2.20260114.08.00",
            "",
            "",
            "",
            "",
            "",
            "",
            "1",
            "LARGE_FORM_FACTOR",
            buildSafariUserAgent(),
            true
        ));

        clients.add(new YoutubeClient(
            "MWEB",
            "2.20260115.01.00",
            "",
            "",
            "",
            "",
            "",
            "",
            "2",
            "SMALL_FORM_FACTOR",
            buildMobileWebUserAgent(),
            true
        ));

        clients.add(new YoutubeClient(
            "WEB",
            "2.20260114.08.00",
            "",
            "",
            "",
            "",
            "",
            "",
            "1",
            "LARGE_FORM_FACTOR",
            buildDefaultUserAgent(),
            true
        ));

        clients.add(new YoutubeClient(
            "WEB_EMBEDDED_PLAYER",
            "1.20260115.01.00",
            "",
            "",
            "",
            "",
            "",
            "",
            "56",
            "LARGE_FORM_FACTOR",
            buildDefaultUserAgent(),
            true
        ));

        clients.add(new YoutubeClient(
            "TVHTML5",
            "7.20260114.12.00",
            "",
            "",
            "",
            "",
            "",
            "",
            "7",
            "LARGE_FORM_FACTOR",
            buildTvUserAgent(),
            true
        ));

        clients.add(new YoutubeClient(
            "ANDROID_VR",
            "1.65.10",
            "Android",
            "12L",
            "",
            "32",
            "Oculus",
            "Quest 3",
            "28",
            "SMALL_FORM_FACTOR",
            buildAndroidVrUserAgent(),
            true
        ));

        clients.add(new YoutubeClient(
            "ANDROID",
            "21.02.35",
            "Android",
            "11",
            "com.google.android.youtube",
            "30",
            "Google",
            "Pixel 8 Pro",
            "3",
            "SMALL_FORM_FACTOR",
            buildAndroidUserAgent(),
            true
        ));

        clients.add(new YoutubeClient(
            "IOS",
            "21.02.3",
            "iPhone",
            "18.3.2.22D82",
            "",
            "",
            "Apple",
            "iPhone16,2",
            "5",
            "SMALL_FORM_FACTOR",
            buildIosUserAgent(),
            true
        ));

        return clients;
    }

    private static List<YoutubeClient> sanitizeClients(List<YoutubeClient> clients) {
        List<YoutubeClient> sanitized = new ArrayList<>();

        if (clients != null) {
            for (YoutubeClient client : clients) {
                if (client != null && client.isValid()) {
                    sanitized.add(client);
                }
            }
        }

        return sanitized.isEmpty() ? buildDefaultClients() : sanitized;
    }

    private static List<String> sanitizeStringList(List<String> values) {
        List<String> sanitized = new ArrayList<>();

        if (values == null) {
            return sanitized;
        }

        for (String value : values) {
            if (!isBlank(value)) {
                sanitized.add(value.trim());
            }
        }

        return sanitized;
    }

    private static String buildDefaultUserAgent() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/125.0.0.0 Safari/537.36";
    }

    private static String buildSafariUserAgent() {
        return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) "
            + "Version/15.5 Safari/605.1.15,gzip(gfe)";
    }

    private static String buildMobileWebUserAgent() {
        return "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) "
            + "Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)";
    }

    private static String buildAndroidUserAgent() {
        return "com.google.android.youtube/21.02.35 (Linux; U; Android 11) gzip";
    }

    private static String buildAndroidVrUserAgent() {
        return "com.google.android.apps.youtube.vr.oculus/1.65.10 "
            + "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip";
    }

    private static String buildIosUserAgent() {
        return "com.google.ios.youtube/21.02.3 "
            + "(iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)";
    }

    private static String buildTvUserAgent() {
        return "Mozilla/5.0 (ChromiumStylePlatform) "
            + "Cobalt/25.lts.30.1034943-gold (unlike Gecko), "
            + "Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)";
    }

    private static List<String> buildDefaultPlayerClientFallback() {
        List<String> clients = new ArrayList<>();
        clients.add("android_vr");
        clients.add("tv");
        clients.add("web");
        clients.add("mweb");
        clients.add("ios");
        clients.add("tv_embedded");
        return clients;
    }

    private static List<String> sanitizePlayerClientFallback(List<String> values) {
        List<String> clients = new ArrayList<>();
        List<String> source = values == null || values.isEmpty()
            ? buildDefaultPlayerClientFallback()
            : values;

        for (String value : source) {
            String normalized = nullToEmpty(value).trim().toLowerCase(Locale.US);

            if (normalized.isEmpty() || clients.contains(normalized)) {
                continue;
            }

            clients.add(normalized);
        }

        if (clients.isEmpty()) {
            clients.addAll(buildDefaultPlayerClientFallback());
        }

        return clients;
    }

    private static String normalizeExtractorMode(String mode) {
        String normalized = nullToEmpty(mode).trim().toLowerCase(Locale.US);

        if ("java-first".equals(normalized)
            || "java-only".equals(normalized)
            || "yt-dlp-only".equals(normalized)
            || "yt-dlp-first".equals(normalized)) {
            return normalized;
        }

        return "yt-dlp-first";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String describeOptionalUrl(String value) {
        return isBlank(value) ? "empty" : value.trim();
    }

    public int getConfigVersion() {
        return configVersion;
    }

    public int getMinAppVersion() {
        return minAppVersion;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public List<YoutubeClient> getYoutubeClients() {
        return Collections.unmodifiableList(youtubeClients);
    }

    public List<String> getApiKeys() {
        return Collections.unmodifiableList(apiKeys);
    }

    public String getVisitorDataUrl() {
        return visitorDataUrl;
    }

    public String getYoutubeExtractorMode() {
        return youtubeExtractorMode;
    }

    public String getYtDlpExecutable() {
        return ytDlpExecutable;
    }

    public String getYtDlpExtractorArgs() {
        return ytDlpExtractorArgs;
    }

    public List<String> getYtDlpExtraArgs() {
        return Collections.unmodifiableList(ytDlpExtraArgs);
    }

    public List<String> getYtDlpPlayerClientFallback() {
        return Collections.unmodifiableList(ytDlpPlayerClientFallback);
    }

    public String getYtDlpCookiesPath() {
        return ytDlpCookiesPath;
    }

    public String getYtDlpCookiesFromBrowser() {
        return ytDlpCookiesFromBrowser;
    }

    public String getYtDlpCookieHeader() {
        return ytDlpCookieHeader;
    }

    public boolean hasYtDlpCookies() {
        return !isBlank(ytDlpCookiesPath)
            || !isBlank(ytDlpCookiesFromBrowser)
            || !isBlank(ytDlpCookieHeader);
    }

    public int getYtDlpResolveTimeoutSeconds() {
        return ytDlpResolveTimeoutSeconds;
    }

    public boolean isJavaHlsFallbackEnabled() {
        return javaHlsFallbackEnabled;
    }

    public boolean isYtDlpEnabled() {
        return !"java-only".equals(youtubeExtractorMode);
    }

    public boolean isYtDlpFirst() {
        return "yt-dlp-first".equals(youtubeExtractorMode) || "yt-dlp-only".equals(youtubeExtractorMode);
    }

    public boolean isJavaHlsEnabled() {
        return !"yt-dlp-only".equals(youtubeExtractorMode) && javaHlsFallbackEnabled;
    }

    public String getInnertubeBaseUrl() {
        return innertubeBaseUrl;
    }

    public String getWebPlayerBaseUrl() {
        return webPlayerBaseUrl;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getNotes() {
        return notes;
    }

    public long getFetchedAt() {
        return fetchedAt;
    }

    public void setConfigVersion(int configVersion) {
        this.configVersion = Math.max(1, configVersion);
    }

    public void setMinAppVersion(int minAppVersion) {
        this.minAppVersion = Math.max(1, minAppVersion);
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = nullToEmpty(updatedAt);
    }

    public void setYoutubeClients(List<YoutubeClient> youtubeClients) {
        this.youtubeClients = sanitizeClients(youtubeClients);
    }

    public void setApiKeys(List<String> apiKeys) {
        this.apiKeys = sanitizeStringList(apiKeys);
    }

    public void setVisitorDataUrl(String visitorDataUrl) {
        this.visitorDataUrl = nullToEmpty(visitorDataUrl);
    }

    public void setYoutubeExtractorMode(String youtubeExtractorMode) {
        this.youtubeExtractorMode = normalizeExtractorMode(youtubeExtractorMode);
    }

    public void setYtDlpExecutable(String ytDlpExecutable) {
        this.ytDlpExecutable = isBlank(ytDlpExecutable) ? "yt-dlp" : ytDlpExecutable.trim();
    }

    public void setYtDlpExtractorArgs(String ytDlpExtractorArgs) {
        this.ytDlpExtractorArgs = nullToEmpty(ytDlpExtractorArgs).trim();
    }

    public void setYtDlpExtraArgs(List<String> ytDlpExtraArgs) {
        this.ytDlpExtraArgs = sanitizeStringList(ytDlpExtraArgs);
    }

    public void setYtDlpPlayerClientFallback(List<String> ytDlpPlayerClientFallback) {
        this.ytDlpPlayerClientFallback = sanitizePlayerClientFallback(ytDlpPlayerClientFallback);
    }

    public void setYtDlpCookiesPath(String ytDlpCookiesPath) {
        this.ytDlpCookiesPath = nullToEmpty(ytDlpCookiesPath).trim();
    }

    public void setYtDlpCookiesFromBrowser(String ytDlpCookiesFromBrowser) {
        this.ytDlpCookiesFromBrowser = nullToEmpty(ytDlpCookiesFromBrowser).trim();
    }

    public void setYtDlpCookieHeader(String ytDlpCookieHeader) {
        this.ytDlpCookieHeader = nullToEmpty(ytDlpCookieHeader).trim();
    }

    public void setYtDlpResolveTimeoutSeconds(int ytDlpResolveTimeoutSeconds) {
        this.ytDlpResolveTimeoutSeconds = clamp(ytDlpResolveTimeoutSeconds, 10, 300);
    }

    public void setJavaHlsFallbackEnabled(boolean javaHlsFallbackEnabled) {
        this.javaHlsFallbackEnabled = javaHlsFallbackEnabled;
    }

    public void setInnertubeBaseUrl(String innertubeBaseUrl) {
        this.innertubeBaseUrl = isBlank(innertubeBaseUrl)
            ? "https://www.youtube.com/youtubei/v1"
            : innertubeBaseUrl.trim();
    }

    public void setWebPlayerBaseUrl(String webPlayerBaseUrl) {
        this.webPlayerBaseUrl = isBlank(webPlayerBaseUrl)
            ? "https://www.youtube.com"
            : webPlayerBaseUrl.trim();
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = isBlank(userAgent) ? buildDefaultUserAgent() : userAgent.trim();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setNotes(String notes) {
        this.notes = nullToEmpty(notes);
    }

    public void setFetchedAt(long fetchedAt) {
        this.fetchedAt = Math.max(0L, fetchedAt);
    }

    /**
     * Represents one YouTube client profile used for Innertube/player requests.
     */
    public static class YoutubeClient {

        private static final String JSON_CLIENT_NAME = "clientName";
        private static final String JSON_CLIENT_VERSION = "clientVersion";
        private static final String JSON_OS_NAME = "osName";
        private static final String JSON_OS_VERSION = "osVersion";
        private static final String JSON_ANDROID_PACKAGE = "androidPackage";
        private static final String JSON_ANDROID_SDK_VERSION = "androidSdkVersion";
        private static final String JSON_DEVICE_MAKE = "deviceMake";
        private static final String JSON_DEVICE_MODEL = "deviceModel";
        private static final String JSON_CLIENT_ID = "clientId";
        private static final String JSON_CLIENT_FORM_FACTOR = "clientFormFactor";
        private static final String JSON_USER_AGENT = "userAgent";
        private static final String JSON_ENABLED = "enabled";

        private String clientName;
        private String clientVersion;
        private String osName;
        private String osVersion;
        private String androidPackage;
        private String androidSdkVersion;
        private String deviceMake;
        private String deviceModel;
        private String clientId;
        private String clientFormFactor;
        private String userAgent;
        private boolean enabled;

        public YoutubeClient() {
            this.clientName = "IOS";
            this.clientVersion = "21.02.3";
            this.osName = "iPhone";
            this.osVersion = "18.3.2.22D82";
            this.androidPackage = "";
            this.androidSdkVersion = "";
            this.deviceMake = "Apple";
            this.deviceModel = "iPhone16,2";
            this.clientId = "5";
            this.clientFormFactor = "SMALL_FORM_FACTOR";
            this.userAgent = buildIosUserAgent();
            this.enabled = true;
        }

        public YoutubeClient(
            String clientName,
            String clientVersion,
            String osName,
            String osVersion,
            String androidPackage,
            String androidSdkVersion,
            boolean enabled
        ) {
            this(
                clientName,
                clientVersion,
                osName,
                osVersion,
                androidPackage,
                androidSdkVersion,
                "",
                "",
                "",
                "",
                "",
                enabled
            );
        }

        public YoutubeClient(
            String clientName,
            String clientVersion,
            String osName,
            String osVersion,
            String androidPackage,
            String androidSdkVersion,
            String deviceMake,
            String deviceModel,
            String clientId,
            String clientFormFactor,
            String userAgent,
            boolean enabled
        ) {
            this.clientName = nullToEmpty(clientName);
            this.clientVersion = nullToEmpty(clientVersion);
            this.osName = nullToEmpty(osName);
            this.osVersion = nullToEmpty(osVersion);
            this.androidPackage = nullToEmpty(androidPackage);
            this.androidSdkVersion = nullToEmpty(androidSdkVersion);
            this.deviceMake = nullToEmpty(deviceMake);
            this.deviceModel = nullToEmpty(deviceModel);
            this.clientId = nullToEmpty(clientId);
            this.clientFormFactor = normalizeClientFormFactor(clientFormFactor, clientName);
            this.userAgent = nullToEmpty(userAgent);
            this.enabled = enabled;
        }

        public static YoutubeClient fromJson(JSONObject json) throws JSONException {
            if (json == null) {
                return new YoutubeClient();
            }

            YoutubeClient defaults = new YoutubeClient();
            String clientName = json.optString(JSON_CLIENT_NAME, defaults.getClientName());

            return new YoutubeClient(
                clientName,
                json.optString(JSON_CLIENT_VERSION, defaults.getClientVersion()),
                json.optString(JSON_OS_NAME, inferOsName(clientName)),
                json.optString(JSON_OS_VERSION, inferOsVersion(clientName)),
                json.optString(JSON_ANDROID_PACKAGE, inferAndroidPackage(clientName)),
                json.optString(JSON_ANDROID_SDK_VERSION, inferAndroidSdkVersion(clientName)),
                json.optString(JSON_DEVICE_MAKE, inferDeviceMake(clientName)),
                json.optString(JSON_DEVICE_MODEL, inferDeviceModel(clientName)),
                json.optString(JSON_CLIENT_ID, inferClientId(clientName)),
                json.optString(JSON_CLIENT_FORM_FACTOR, inferClientFormFactor(clientName)),
                json.optString(JSON_USER_AGENT, inferUserAgent(clientName)),
                json.optBoolean(JSON_ENABLED, defaults.isEnabled())
            );
        }

        private static String inferOsName(String clientName) {
            if (isAndroidClient(clientName)) {
                return "Android";
            }

            if (isIosClient(clientName)) {
                return "iOS";
            }

            return "";
        }

        private static String inferOsVersion(String clientName) {
            if (isAndroidClient(clientName)) {
                return "11";
            }

            if (isIosClient(clientName)) {
                return "18.3.2.22D82";
            }

            return "";
        }

        private static String inferAndroidPackage(String clientName) {
            return isAndroidClient(clientName) ? "com.google.android.youtube" : "";
        }

        private static String inferAndroidSdkVersion(String clientName) {
            return isAndroidClient(clientName) ? "30" : "";
        }

        private static String inferDeviceMake(String clientName) {
            if (isAndroidClient(clientName)) {
                return "Google";
            }

            if (isIosClient(clientName)) {
                return "Apple";
            }

            return "";
        }

        private static String inferDeviceModel(String clientName) {
            if (isAndroidClient(clientName)) {
                return "Pixel 8 Pro";
            }

            if (isIosClient(clientName)) {
                return "iPhone16,2";
            }

            return "";
        }

        private static String inferClientId(String clientName) {
            if (isIosClient(clientName)) {
                return "5";
            }

            if (isAndroidClient(clientName)) {
                return "3";
            }

            if (isMobileWebClient(clientName)) {
                return "2";
            }

            if (isTvClient(clientName)) {
                return "7";
            }

            if (isWebEmbeddedClient(clientName)) {
                return "56";
            }

            if (isWebClient(clientName)) {
                return "1";
            }

            return "";
        }

        private static String inferClientFormFactor(String clientName) {
            if (isAndroidClient(clientName) || isIosClient(clientName)) {
                return "SMALL_FORM_FACTOR";
            }

            if (isTvClient(clientName) || isWebClient(clientName)) {
                return "LARGE_FORM_FACTOR";
            }

            return "UNKNOWN_FORM_FACTOR";
        }

        private static String inferUserAgent(String clientName) {
            if (isAndroidClient(clientName)) {
                return buildAndroidUserAgent();
            }

            if (isIosClient(clientName)) {
                return buildIosUserAgent();
            }

            if (isTvClient(clientName)) {
                return buildTvUserAgent();
            }

            if (isMobileWebClient(clientName)) {
                return buildMobileWebUserAgent();
            }

            return buildDefaultUserAgent();
        }

        private static boolean isAndroidClient(String clientName) {
            return "ANDROID".equalsIgnoreCase(nullToEmpty(clientName));
        }

        private static boolean isIosClient(String clientName) {
            return "IOS".equalsIgnoreCase(nullToEmpty(clientName));
        }

        private static boolean isTvClient(String clientName) {
            return nullToEmpty(clientName).toUpperCase().contains("TV");
        }

        private static boolean isMobileWebClient(String clientName) {
            return "MWEB".equalsIgnoreCase(nullToEmpty(clientName));
        }

        private static boolean isWebEmbeddedClient(String clientName) {
            return nullToEmpty(clientName).toUpperCase().contains("WEB_EMBEDDED");
        }

        private static boolean isWebClient(String clientName) {
            return nullToEmpty(clientName).toUpperCase().startsWith("WEB");
        }

        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();

            json.put(JSON_CLIENT_NAME, clientName);
            json.put(JSON_CLIENT_VERSION, clientVersion);
            json.put(JSON_OS_NAME, osName);
            json.put(JSON_OS_VERSION, osVersion);
            json.put(JSON_ANDROID_PACKAGE, androidPackage);
            json.put(JSON_ANDROID_SDK_VERSION, androidSdkVersion);
            json.put(JSON_DEVICE_MAKE, deviceMake);
            json.put(JSON_DEVICE_MODEL, deviceModel);
            json.put(JSON_CLIENT_ID, clientId);
            json.put(JSON_CLIENT_FORM_FACTOR, clientFormFactor);
            json.put(JSON_USER_AGENT, userAgent);
            json.put(JSON_ENABLED, enabled);

            return json;
        }

        public boolean isValid() {
            return enabled && !isBlank(clientName) && !isBlank(clientVersion);
        }

        public JSONObject toInnertubeClientJson() throws JSONException {
            JSONObject client = new JSONObject();

            client.put("clientName", clientName);
            client.put("clientVersion", clientVersion);

            if (!isBlank(osName)) {
                client.put("osName", osName);
            }

            if (!isBlank(osVersion)) {
                client.put("osVersion", osVersion);
            }

            if (!isBlank(androidPackage)) {
                client.put("androidPackage", androidPackage);
            }

            if (!isBlank(androidSdkVersion)) {
                client.put("androidSdkVersion", androidSdkVersion);
            }

            if (!isBlank(deviceMake)) {
                client.put("deviceMake", deviceMake);
            }

            if (!isBlank(deviceModel)) {
                client.put("deviceModel", deviceModel);
            }

            if (!isBlank(userAgent)) {
                client.put("userAgent", userAgent);
            }

            client.put("hl", "en");
            client.put("timeZone", "UTC");
            client.put("utcOffsetMinutes", 0);

            // Do not send clientFormFactor to Innertube/player. YouTube rejects
            // legacy values such as MOBILE, TV, and WEB with HTTP 400, and this
            // optional field is not needed for manifest resolution.
            return client;
        }

        private static String normalizeClientFormFactor(String clientFormFactor, String clientName) {
            String normalized = nullToEmpty(clientFormFactor).trim().toUpperCase(Locale.US);

            if ("UNKNOWN_FORM_FACTOR".equals(normalized)
                || "SMALL_FORM_FACTOR".equals(normalized)
                || "LARGE_FORM_FACTOR".equals(normalized)
                || "AUTOMOTIVE_FORM_FACTOR".equals(normalized)) {
                return normalized;
            }

            if ("MOBILE".equals(normalized)) {
                return "SMALL_FORM_FACTOR";
            }

            if ("TV".equals(normalized) || "WEB".equals(normalized)) {
                return "LARGE_FORM_FACTOR";
            }

            return inferClientFormFactor(clientName);
        }

        public boolean isWebLike() {
            return clientName != null && clientName.toUpperCase().contains("WEB");
        }

        public boolean isEmbeddedLike() {
            return clientName != null && clientName.toUpperCase().contains("EMBED");
        }

        public boolean isAndroidLike() {
            return "ANDROID".equalsIgnoreCase(clientName)
                || "ANDROID_TESTSUITE".equalsIgnoreCase(clientName);
        }

        public boolean isIosLike() {
            return "IOS".equalsIgnoreCase(clientName);
        }

        public boolean isTvLike() {
            return clientName != null && clientName.toUpperCase().contains("TV");
        }

        public String getClientName() {
            return clientName;
        }

        public String getClientVersion() {
            return clientVersion;
        }

        public String getOsName() {
            return osName;
        }

        public String getOsVersion() {
            return osVersion;
        }

        public String getAndroidPackage() {
            return androidPackage;
        }

        public String getAndroidSdkVersion() {
            return androidSdkVersion;
        }

        public String getDeviceMake() {
            return deviceMake;
        }

        public String getDeviceModel() {
            return deviceModel;
        }

        public String getClientId() {
            return clientId;
        }

        public String getClientFormFactor() {
            return clientFormFactor;
        }

        public String getUserAgent() {
            return userAgent;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setClientName(String clientName) {
            this.clientName = nullToEmpty(clientName);
        }

        public void setClientVersion(String clientVersion) {
            this.clientVersion = nullToEmpty(clientVersion);
        }

        public void setOsName(String osName) {
            this.osName = nullToEmpty(osName);
        }

        public void setOsVersion(String osVersion) {
            this.osVersion = nullToEmpty(osVersion);
        }

        public void setAndroidPackage(String androidPackage) {
            this.androidPackage = nullToEmpty(androidPackage);
        }

        public void setAndroidSdkVersion(String androidSdkVersion) {
            this.androidSdkVersion = nullToEmpty(androidSdkVersion);
        }

        public void setDeviceMake(String deviceMake) {
            this.deviceMake = nullToEmpty(deviceMake);
        }

        public void setDeviceModel(String deviceModel) {
            this.deviceModel = nullToEmpty(deviceModel);
        }

        public void setClientId(String clientId) {
            this.clientId = nullToEmpty(clientId);
        }

        public void setClientFormFactor(String clientFormFactor) {
            this.clientFormFactor = normalizeClientFormFactor(clientFormFactor, clientName);
        }

        public void setUserAgent(String userAgent) {
            this.userAgent = nullToEmpty(userAgent);
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
