package com.livemonitor.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.JavascriptInterface;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * Enqueues a one-shot immediate refresh — used when a recording is about
     * to start and no po_token is cached yet. Runs alongside the periodic job
     * without disturbing its schedule. Uses a unique name so rapid repeated
     * calls (e.g. multiple channels starting at the same time) collapse into
     * a single work unit.
     */
    public static void triggerNow(Context context) {
        Constraints constraints = new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build();

        OneTimeWorkRequest request =
            new OneTimeWorkRequest.Builder(PoTokenRefreshWorker.class)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME + "_immediate",
            ExistingWorkPolicy.KEEP,
            request
        );
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        AppStorage storage = new AppStorage(ctx);
        AppSettings settings = storage.loadSettings();

        if (!settings.isYtDlpPoTokenRefreshNeeded(System.currentTimeMillis())) {
            return Result.success();
        }

        String videoId = settings.getYtDlpPoTokenVideoId();
        if (YouTubePoTokenHelper.isBlank(videoId)) {
            videoId = FALLBACK_VIDEO_ID;
        }

        storage.appendLog(LogItem.info(
            LogItem.SOURCE_REMOTE_CONFIG,
            "[PoToken/BgWorker] Background refresh worker started"
                + " | videoId=" + videoId
                + " | using fetch+XHR intercept strategy"
        ));

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
                // Same Chrome mobile UA as YouTubeSignInActivity — strips the
                // "wv" WebView marker that causes Google to serve a degraded page.
                webSettings.setUserAgentString(
                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                );
                // Required for the YouTube player to render and populate its JS globals.
                webSettings.setLoadWithOverviewMode(true);
                webSettings.setUseWideViewPort(true);
                webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                webSettings.setMediaPlaybackRequiresUserGesture(false);
                webSettings.setAllowContentAccess(true);

                CookieManager cookieManager = CookieManager.getInstance();
                cookieManager.setAcceptCookie(true);
                cookieManager.setAcceptThirdPartyCookies(webView, true);

                /*
                 * Register the LiveMonitorApp JavaScript bridge before loading any URL.
                 * The FETCH_INTERCEPTOR_SCRIPT (injected in onPageStarted) calls
                 * onPoTokenIntercepted() when it finds a po_token in the body of a
                 * /youtubei/v1/player network request — which is the primary and most
                 * reliable way to capture the token without reading page globals.
                 */
                webView.addJavascriptInterface(new Object() {
                    @JavascriptInterface
                    public void onPoTokenIntercepted(String token, String clientName,
                            String videoId, String src) {
                        String safeToken  = token == null ? "" : token.trim();
                        String safeClient = (clientName == null || clientName.isEmpty()) ? "WEB" : clientName;
                        String safeVideo  = (videoId == null || videoId.isEmpty()) ? finalVideoId : videoId;
                        String safeSrc    = src == null ? "?" : src;

                        storage.appendLog(LogItem.debug(
                            LogItem.SOURCE_REMOTE_CONFIG,
                            "[PoToken/BgIntercept] fetch/XHR bridge fired"
                                + " | source=" + safeSrc
                                + " | client=" + safeClient
                                + " | videoId=" + safeVideo
                                + " | tokenLen=" + safeToken.length()
                                + (safeToken.length() >= 8
                                    ? " | token=" + safeToken.substring(0, 8) + "..."
                                    : " | token=<too short, ignored>")
                        ));

                        if (safeToken.length() < 16) {
                            storage.appendLog(LogItem.warning(
                                LogItem.SOURCE_REMOTE_CONFIG,
                                "[PoToken/BgIntercept] Token rejected — too short (len="
                                    + safeToken.length() + ")"
                            ));
                            return;
                        }

                        try {
                            JSONObject json = new JSONObject();
                            json.put("token", safeToken);
                            json.put("tokenType", "gvs");
                            json.put("clientName", safeClient);
                            json.put("videoId", safeVideo);
                            json.put("source", "intercept:" + safeSrc);
                            json.put("playerUrl", "");
                            boolean saved = YouTubePoTokenHelper.parseAndSaveToken(
                                json.toString(), storage, finalVideoId);
                            if (saved) {
                                storage.appendLog(LogItem.success(
                                    LogItem.SOURCE_REMOTE_CONFIG,
                                    "[PoToken/BgIntercept] Token saved successfully via background intercept."
                                        + " client=" + safeClient
                                        + " | videoId=" + safeVideo
                                ));
                                tokenSaved.set(true);
                                latch.countDown();
                            } else {
                                storage.appendLog(LogItem.warning(
                                    LogItem.SOURCE_REMOTE_CONFIG,
                                    "[PoToken/BgIntercept] parseAndSaveToken rejected the intercepted token"
                                ));
                            }
                        } catch (Exception e) {
                            storage.appendLog(LogItem.error(
                                LogItem.SOURCE_REMOTE_CONFIG,
                                "[PoToken/BgIntercept] Exception saving token: " + e.getMessage(),
                                null
                            ));
                        }
                    }
                }, "LiveMonitorApp");

                // Grant media/DRM permission requests so the player JS initialises
                // fully and its globals (ytcfg, ytInitialPlayerResponse) are populated.
                webView.setWebChromeClient(new WebChromeClient() {
                    @Override
                    public void onPermissionRequest(PermissionRequest request) {
                        request.grant(request.getResources());
                    }
                });

                webView.setWebViewClient(new WebViewClient() {
                    private volatile boolean handled = false;

                    @Override
                    public void onPageStarted(WebView view, String url,
                            android.graphics.Bitmap favicon) {
                        /*
                         * Inject the fetch/XHR interceptor as early as possible so
                         * it is installed before YouTube's player JS makes the
                         * /youtubei/v1/player request that contains the po_token.
                         */
                        view.evaluateJavascript(
                            YouTubePoTokenHelper.FETCH_INTERCEPTOR_SCRIPT, null);
                        storage.appendLog(LogItem.debug(
                            LogItem.SOURCE_REMOTE_CONFIG,
                            "[PoToken/BgWorker] Background WebView page loading: "
                                + (url == null ? "null" : url)
                                + " | fetch+XHR interceptor injected"
                        ));
                    }

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

                        // Delay extraction by 3 s — onPageFinished fires when the
                        // HTML document is parsed, but YouTube's player JS (which
                        // populates ytcfg and the PO token) loads asynchronously
                        // after that. Running the script immediately returns empty
                        // globals. A second attempt fires 5 s later if the first
                        // finds no token.
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (handled) return;
                            view.evaluateJavascript(YouTubePoTokenHelper.PO_TOKEN_SCRIPT, result -> {
                                boolean saved = YouTubePoTokenHelper.parseAndSaveToken(result, storage, url);
                                if (saved) {
                                    finish(true);
                                } else if (!handled) {
                                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                        if (handled) return;
                                        view.evaluateJavascript(YouTubePoTokenHelper.PO_TOKEN_SCRIPT, retry -> {
                                            boolean retrySaved = YouTubePoTokenHelper.parseAndSaveToken(retry, storage, url);
                                            finish(retrySaved);
                                        });
                                    }, 5000);
                                }
                            });
                        }, 3000);
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

                /*
                 * Use the desktop YouTube URL — m.youtube.com frequently shows a
                 * black player and does not initialise ytcfg globals in a headless
                 * WebView, so the PO token script finds nothing. The desktop site
                 * populates ytcfg / ytInitialPlayerResponse reliably.
                 */
                webView.loadUrl("https://www.youtube.com/watch?v=" + finalVideoId);

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
                        + WEBVIEW_TIMEOUT_SECONDS + "s. Will retry next cycle."
                ));
                return Result.retry();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.retry();
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
        return Result.retry();
    }
}
