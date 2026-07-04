package com.livemonitor.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Fetches and validates remote config.json.
 *
 * Intended behavior:
 * - Called on app start.
 * - Called on service start as a fallback.
 * - Uses cached config when cache is fresh.
 * - Uses default config if remote fetch fails.
 * - Never crashes the app because of bad remote JSON.
 */
public class RemoteConfigFetcher {

    public interface Callback {
        void onConfigReady(RemoteConfig config, boolean fromNetwork, String message);
    }

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final long FAILURE_LOG_INTERVAL_MS = 30L * 60L * 1000L;

    private static volatile long lastFetchFailureLoggedAt = 0L;

    private final Context appContext;
    private final AppStorage storage;

    public RemoteConfigFetcher(Context context) {
        this.appContext = context.getApplicationContext();
        this.storage = new AppStorage(appContext);
    }

    /**
     * Loads the best available config.
     *
     * Priority:
     * 1. Fresh cached config
     * 2. Remote config from settings URL
     * 3. Existing cached config
     * 4. Built-in default config
     */
    public RemoteConfig loadBestAvailableConfig() {
        AppSettings settings = storage.loadSettings();
        RemoteConfig cached = storage.loadRemoteConfig();

        if (!settings.isRemoteConfigEnabled()) {
            return cached;
        }

        if (cached.isCacheFresh(settings.getRemoteConfigCacheTtlMinutes())) {
            return cached;
        }

        if (isBlank(settings.getRemoteConfigUrl())) {
            if (storage.hasCachedRemoteConfig()
                && cached.isValidForAppVersion(getAppVersionCode())) {
                return cached;
            }

            return new RemoteConfig();
        }

        FetchResult result = fetchFromSettings(settings);

        if (result.success && result.config != null) {
            return result.config;
        }

        if (cached.isValidForAppVersion(getAppVersionCode())) {
            return cached;
        }

        return new RemoteConfig();
    }

    /**
     * Fetches remote config on a background thread and returns through callback.
     */
    public void fetchAsync(Callback callback) {
        Thread thread = new Thread(() -> {
            AppSettings settings = storage.loadSettings();
            RemoteConfig cached = storage.loadRemoteConfig();

            if (!settings.isRemoteConfigEnabled()) {
                if (callback != null) {
                    callback.onConfigReady(cached, false, "Remote config disabled.");
                }
                return;
            }

            if (cached.isCacheFresh(settings.getRemoteConfigCacheTtlMinutes())) {
                if (callback != null) {
                    callback.onConfigReady(cached, false, "Using fresh cached remote config.");
                }
                return;
            }

            if (isBlank(settings.getRemoteConfigUrl())) {
                if (callback != null) {
                    if (storage.hasCachedRemoteConfig()
                        && cached.isValidForAppVersion(getAppVersionCode())) {
                        callback.onConfigReady(
                            cached,
                            false,
                            "Remote config URL is empty. Using cached config."
                        );
                    } else {
                        callback.onConfigReady(
                            new RemoteConfig(),
                            false,
                            "Remote config URL is empty. Using default config."
                        );
                    }
                }
                return;
            }

            FetchResult result = fetchFromSettings(settings);

            if (result.success && result.config != null) {
                if (callback != null) {
                    callback.onConfigReady(result.config, true, result.message);
                }
                return;
            }

            if (storage.hasCachedRemoteConfig()
                && cached.isValidForAppVersion(getAppVersionCode())) {
                if (callback != null) {
                    callback.onConfigReady(
                        cached,
                        false,
                        "Remote config fetch failed. Using cached config. " + result.message
                    );
                }
                return;
            }

            RemoteConfig defaults = new RemoteConfig();

            if (callback != null) {
                callback.onConfigReady(
                    defaults,
                    false,
                    "Remote config fetch failed. Using default config. " + result.message
                );
            }
        }, "RemoteConfigFetcher");

        thread.start();
    }

    public FetchResult fetchFromSettings(AppSettings settings) {
        if (settings == null) {
            settings = new AppSettings();
        }

        String remoteConfigUrl = settings.getRemoteConfigUrl();

        if (isBlank(remoteConfigUrl)) {
            return FetchResult.failure("Remote config URL is empty.");
        }

        return fetchFromUrl(remoteConfigUrl);
    }

    public FetchResult fetchFromUrl(String configUrl) {
        if (isBlank(configUrl)) {
            return FetchResult.failure("Remote config URL is empty.");
        }

        HttpURLConnection connection = null;

        try {
            URL url = new URL(configUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", new RemoteConfig().getUserAgent());
            connection.setUseCaches(false);

            int responseCode = connection.getResponseCode();

            if (responseCode < 200 || responseCode >= 300) {
                return FetchResult.failure("HTTP " + responseCode + " while fetching config.");
            }

            String body = readStream(connection.getInputStream());

            if (isBlank(body)) {
                return FetchResult.failure("Remote config response was empty.");
            }

            JSONObject json = new JSONObject(body);
            RemoteConfig config = RemoteConfig.fromJson(json);
            config.markFetchedNow();

            int appVersionCode = getAppVersionCode();

            if (!config.isValidForAppVersion(appVersionCode)) {
                return FetchResult.failure(
                    "Remote config is not valid for app version " + appVersionCode + "."
                );
            }

            storage.saveRemoteConfig(config);
            storage.appendLog(new LogItem(
                LogItem.LEVEL_SUCCESS,
                LogItem.SOURCE_REMOTE_CONFIG,
                "",
                "",
                "",
                "",
                "Remote config updated successfully.",
                config.buildDebugSummary()
            ));

            return FetchResult.success(config, "Remote config fetched successfully.");
        } catch (Exception e) {
            logFetchFailureIfDue(e);

            return FetchResult.failure("Remote config fetch failed: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }


    private void logFetchFailureIfDue(Exception e) {
        long now = System.currentTimeMillis();
        if (now - lastFetchFailureLoggedAt <= FAILURE_LOG_INTERVAL_MS) {
            return;
        }

        lastFetchFailureLoggedAt = now;
        storage.appendLog(new LogItem(
            LogItem.LEVEL_WARNING,
            LogItem.SOURCE_REMOTE_CONFIG,
            "",
            "",
            "",
            "",
            "Remote config fetch failed.",
            e == null ? "" : e.getMessage()
        ));
    }

    private int getAppVersionCode() {
        try {
            PackageManager packageManager = appContext.getPackageManager();
            PackageInfo packageInfo = packageManager.getPackageInfo(appContext.getPackageName(), 0);

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                return (int) packageInfo.getLongVersionCode();
            }

            return packageInfo.versionCode;
        } catch (Exception ignored) {
            return 1;
        }
    }

    private static String readStream(InputStream inputStream) throws Exception {
        StringBuilder builder = new StringBuilder();

        try (
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8)
            )
        ) {
            String line;

            while ((line = reader.readLine()) != null) {
                builder.append(line);
                builder.append('\n');
            }
        }

        return builder.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static class FetchResult {

        private final boolean success;
        private final RemoteConfig config;
        private final String message;

        private FetchResult(boolean success, RemoteConfig config, String message) {
            this.success = success;
            this.config = config;
            this.message = message == null ? "" : message;
        }

        public static FetchResult success(RemoteConfig config, String message) {
            return new FetchResult(true, config, message);
        }

        public static FetchResult failure(String message) {
            return new FetchResult(false, null, message);
        }

        public boolean isSuccess() {
            return success;
        }

        public RemoteConfig getConfig() {
            return config;
        }

        public String getMessage() {
            return message;
        }
    }
}
