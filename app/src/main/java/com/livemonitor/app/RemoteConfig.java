package com.livemonitor.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

        RemoteConfig defaults = new RemoteConfig();

        return new RemoteConfig(
            json.optInt(JSON_CONFIG_VERSION, defaults.getConfigVersion()),
            json.optInt(JSON_MIN_APP_VERSION, defaults.getMinAppVersion()),
            json.optString(JSON_UPDATED_AT, defaults.getUpdatedAt()),
            clients.isEmpty() ? defaults.getYoutubeClients() : clients,
            apiKeys,
            json.optString(JSON_VISITOR_DATA_URL, defaults.getVisitorDataUrl()),
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

    private static List<YoutubeClient> buildDefaultClients() {
        List<YoutubeClient> clients = new ArrayList<>();

        clients.add(new YoutubeClient(
            "IOS",
            "19.29.1",
            "iOS",
            "17.5.1",
            "",
            "",
            "Apple",
            "iPhone16,2",
            "5",
            "MOBILE",
            buildIosUserAgent(),
            true
        ));

        clients.add(new YoutubeClient(
            "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            "2.0",
            "",
            "",
            "",
            "",
            "",
            "",
            "85",
            "TV",
            buildTvUserAgent(),
            true
        ));

        clients.add(new YoutubeClient(
            "WEB_EMBEDDED_PLAYER",
            "1.20240515.01.00",
            "",
            "",
            "",
            "",
            "",
            "",
            "56",
            "WEB",
            buildDefaultUserAgent(),
            true
        ));

        clients.add(new YoutubeClient(
            "WEB",
            "2.20240515.01.00",
            "",
            "",
            "",
            "",
            "",
            "",
            "1",
            "WEB",
            buildDefaultUserAgent(),
            true
        ));

        clients.add(new YoutubeClient(
            "ANDROID",
            "19.29.37",
            "Android",
            "14",
            "com.google.android.youtube",
            "34",
            "Google",
            "Pixel 8 Pro",
            "3",
            "MOBILE",
            buildAndroidUserAgent(),
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

    private static String buildAndroidUserAgent() {
        return "com.google.android.youtube/19.29.37 "
            + "(Linux; U; Android 14; en_US; Pixel 8 Pro) gzip";
    }

    private static String buildIosUserAgent() {
        return "com.google.ios.youtube/19.29.1 "
            + "(iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X; en_US)";
    }

    private static String buildTvUserAgent() {
        return "Mozilla/5.0 (ChromiumStylePlatform) "
            + "CTV AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/125.0.0.0 Safari/537.36";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
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
            this.clientVersion = "19.29.1";
            this.osName = "iOS";
            this.osVersion = "17.5.1";
            this.androidPackage = "";
            this.androidSdkVersion = "";
            this.deviceMake = "Apple";
            this.deviceModel = "iPhone16,2";
            this.clientId = "5";
            this.clientFormFactor = "MOBILE";
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
            this.clientFormFactor = nullToEmpty(clientFormFactor);
            this.userAgent = nullToEmpty(userAgent);
            this.enabled = enabled;
        }

        public static YoutubeClient fromJson(JSONObject json) throws JSONException {
            if (json == null) {
                return new YoutubeClient();
            }

            YoutubeClient defaults = new YoutubeClient();

            return new YoutubeClient(
                json.optString(JSON_CLIENT_NAME, defaults.getClientName()),
                json.optString(JSON_CLIENT_VERSION, defaults.getClientVersion()),
                json.optString(JSON_OS_NAME, defaults.getOsName()),
                json.optString(JSON_OS_VERSION, defaults.getOsVersion()),
                json.optString(JSON_ANDROID_PACKAGE, defaults.getAndroidPackage()),
                json.optString(JSON_ANDROID_SDK_VERSION, defaults.getAndroidSdkVersion()),
                json.optString(JSON_DEVICE_MAKE, defaults.getDeviceMake()),
                json.optString(JSON_DEVICE_MODEL, defaults.getDeviceModel()),
                json.optString(JSON_CLIENT_ID, defaults.getClientId()),
                json.optString(JSON_CLIENT_FORM_FACTOR, defaults.getClientFormFactor()),
                json.optString(JSON_USER_AGENT, defaults.getUserAgent()),
                json.optBoolean(JSON_ENABLED, defaults.isEnabled())
            );
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

            if (!isBlank(clientFormFactor)) {
                client.put("clientFormFactor", clientFormFactor);
            }

            return client;
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
            this.clientFormFactor = nullToEmpty(clientFormFactor);
        }

        public void setUserAgent(String userAgent) {
            this.userAgent = nullToEmpty(userAgent);
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
