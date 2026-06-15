package com.livemonitor.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.CookieManager;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;

/**
 * WorkManager periodic worker that silently refreshes the GVS PO token
 * in the background using a headless WebView.
 *
 * Runs every 6 hours (matching AppSettings.getYtDlpPoTokenRefreshIntervalMillis).
 * If the token is still fresh, returns immediately without doing any network work.
 * If the refresh fails (session expired / network error), fires a notification
 * asking the user to re-open YouTube PO Token Setup once.
 */
public class PoTokenRefreshWorker extends Worker {

    public static final String WORK_NAME = "po_token_auto_refresh";

    private static final int WEBVIEW_TIMEOUT_SECONDS = 45;
    private static final String FALLBACK_VIDEO_ID = "dQw4w9WgXcQ";

    public PoTokenRefreshWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /**
     * Schedules (or keeps) the periodic refresh job.
     * Uses KEEP policy so repeated calls (e.g. on every MonitorService start)
     * do not reset the clock on an already-running schedule.
     */
    public static void scheduleIfNeeded(Context context) {
        AppStorage storage = new AppStorage(context);
        AppSettings settings = storage.loadSettings();
        RemoteConfig remoteConfig = storage.loadRemoteConfig();

        if (!requiresPoTokenAutoRefresh(storage, settings, remoteConfig)) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
            storage.appendLog(LogItem.info(
                LogItem.SOURCE_REMOTE_CONFIG,
                "PO token background auto-refresh skipped (android_vr/no-PO-token path is active)."
            ));
            return;
        }

        Constraints constraints = new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
            PoTokenRefreshWorker.class,
            6, TimeUnit.HOURS
        )
        .setConstraints(constraints)
        .setInitialDelay(1, TimeUnit.HOURS)
        .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        );
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        AppStorage storage = new AppStorage(ctx);
        AppSettings settings = storage.loadSettings();
        RemoteConfig remoteConfig = storage.loadRemoteConfig();

        if (!requiresPoTokenAutoRefresh(storage, settings, remoteConfig)) {
            storage.appendLog(LogItem.info(
                LogItem.SOURCE_REMOTE_CONFIG,
                "PO token background auto-refresh skipped (android_vr/no-PO-token path is active)."
            ));
            return Result.success();
        }

        if (!settings.isYtDlpPoTokenRefreshNeeded(System.currentTimeMillis())) {
            return Result.success();
        }

        String videoId = settings.getYtDlpPoTokenVideoId();
        if (YouTubePoTokenHelper.isBlank(videoId)) {
            videoId = FALLBACK_VIDEO_ID;
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean tokenSaved = new AtomicBoolean(false);
        String finalVideoId = videoId;

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                WebView webView = new WebView(ctx);

                WebSettings webSettings = webView.getSettings();
                webSettings.setJavaScriptEnabled(true);
                webSettings.setDomStorageEnabled(true);
                webSettings.setDatabaseEnabled(true);

                CookieManager cookieManager = CookieManager.getInstance();
                cookieManager.setAcceptCookie(true);
                cookieManager.setAcceptThirdPartyCookies(webView, true);

                webView.setWebViewClient(new WebViewClient() {
                    private volatile boolean handled = false;

                    private void finish(boolean saved) {
                        if (handled) return;
                        handled = true;
                        tokenSaved.set(saved);
                        new Handler(Looper.getMainLooper()).post(() -> {
                            try {
                                webView.stopLoading();
                                webView.destroy();
                            } catch (Exception ignored) {}
                        });
                        latch.countDown();
                    }

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        if (handled) return;

                        if (!YouTubePoTokenHelper.looksLikePlayerUrl(url)) {
                            finish(false);
                            return;
                        }

                        view.evaluateJavascript(YouTubePoTokenHelper.PO_TOKEN_SCRIPT, result -> {
                            boolean saved = YouTubePoTokenHelper.parseAndSaveToken(result, storage, url);
                            finish(saved);
                        });
                    }

                    @Override
                    public void onReceivedError(WebView view, int errorCode,
                            String description, String failingUrl) {
                        finish(false);
                    }

                    @Override
                    public void onReceivedHttpError(WebView view, WebResourceRequest request,
                            WebResourceResponse errorResponse) {
                        if (request != null && request.isForMainFrame()) {
                            finish(false);
                        }
                    }
                });

                webView.loadUrl("https://m.youtube.com/watch?v=" + finalVideoId);

            } catch (Exception e) {
                latch.countDown();
            }
        });

        try {
            boolean completed = latch.await(WEBVIEW_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                storage.appendLog(LogItem.warning(
                    LogItem.SOURCE_REMOTE_CONFIG,
                    "PO token background auto-refresh timed out after "
                        + WEBVIEW_TIMEOUT_SECONDS + "s. Backing off until the next scheduled cycle."
                ));
                return Result.success();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.success();
        }

        if (tokenSaved.get()) {
            return Result.success();
        }

        NotificationHelper helper = new NotificationHelper(ctx);
        helper.showPoTokenSessionExpiredNotification();
        storage.appendLog(LogItem.warning(
            LogItem.SOURCE_REMOTE_CONFIG,
            "PO token background auto-refresh failed. "
                + "YouTube session may have expired. "
                + "Notification sent — open YouTube PO Token Setup to re-sign in."
        ));
        return Result.success();
    }

    private static boolean requiresPoTokenAutoRefresh(
        AppStorage storage,
        AppSettings settings,
        RemoteConfig remoteConfig
    ) {
        if (storage == null || settings == null) {
            return false;
        }

        boolean hasConfiguredCookies = settings.hasYtDlpCookies()
            || (remoteConfig != null && remoteConfig.hasYtDlpCookies());
        boolean hasCachedPoToken = settings.hasYtDlpPoToken();

        if (!hasConfiguredCookies && !hasCachedPoToken) {
            return false;
        }

        String lastWorkingClient = storage.getLastWorkingPlayerClient();

        if (clientMayUsePoToken(lastWorkingClient)) {
            return true;
        }

        List<String> fallbackClients = remoteConfig == null
            ? null
            : remoteConfig.getYtDlpPlayerClientFallback();

        if (fallbackClients == null || fallbackClients.isEmpty()) {
            return hasCachedPoToken && !"android_vr".equals(lastWorkingClient);
        }

        String firstClient = fallbackClients.get(0);

        if ("android_vr".equals(normalizePlayerClient(firstClient)) && !hasConfiguredCookies) {
            return false;
        }

        for (String client : fallbackClients) {
            if (clientMayUsePoToken(client)) {
                return hasConfiguredCookies || hasCachedPoToken;
            }
        }

        return false;
    }

    private static boolean clientMayUsePoToken(String client) {
        String normalized = normalizePlayerClient(client);
        return "mweb".equals(normalized) || "web".equals(normalized);
    }

    private static String normalizePlayerClient(String client) {
        return client == null ? "" : client.trim().toLowerCase(java.util.Locale.US);
    }
}
