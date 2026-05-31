package com.livemonitor.app;

import android.content.Context;
import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;

/**
 * FFmpeg wrapper used by MonitorService.
 *
 * Updated behavior:
 * - records HLS into MPEG-TS during active recording
 * - keeps local HLS proxy support
 * - does not convert to MP4 here
 * - MP4 conversion is handled after recording finishes by MonitorService
 */
public class FFmpegRunner {

    private static final String TAG = "FFmpegRunner";

    private static FFmpegSession currentSession = null;
    private static HlsProxyServer proxyServer = null;

    public static boolean setup(Context context) {
        Log.d(TAG, "Using FFmpegKit dependency with local HLS proxy.");
        return true;
    }

    public static synchronized void executeAsync(
        String manifestUrl,
        String outPath,
        OnCompleteCallback callback,
        OnLogCallback logCallback
    ) {
        try {
            stopProxy();

            ensureParentDirectory(outPath);

            proxyServer = new HlsProxyServer(logCallback);
            proxyServer.start();

            String proxyManifestUrl = proxyServer.createProxyUrl(manifestUrl);

            if (logCallback != null) {
                logCallback.onLog("Local HLS proxy started.");
                logCallback.onLog("Proxy input: " + proxyManifestUrl);
                logCallback.onLog("Recording output: " + outPath);
            }

            String command = buildRecordTsCommand(proxyManifestUrl, outPath);

            if (logCallback != null) {
                logCallback.onLog("FFmpegKit TS recording command started.");
            }

            currentSession = FFmpegKit.executeAsync(
                command,
                session -> {
                    synchronized (FFmpegRunner.class) {
                        currentSession = null;
                    }

                    ReturnCode returnCode = session.getReturnCode();
                    stopProxy();

                    if (ReturnCode.isSuccess(returnCode)) {
                        if (callback != null) {
                            callback.onComplete(0);
                        }
                        return;
                    }

                    if (ReturnCode.isCancel(returnCode)) {
                        if (callback != null) {
                            callback.onComplete(255);
                        }
                        return;
                    }

                    String failStackTrace = session.getFailStackTrace();

                    if (failStackTrace != null && !failStackTrace.isEmpty()) {
                        Log.e(TAG, failStackTrace);

                        if (logCallback != null) {
                            logCallback.onLog("FFmpegKit failure: " + failStackTrace);
                        }
                    }

                    int code = returnCode != null ? returnCode.getValue() : -1;

                    if (callback != null) {
                        callback.onComplete(code);
                    }
                },
                log -> {
                    if (logCallback == null || log == null || log.getMessage() == null) {
                        return;
                    }

                    String message = log.getMessage().trim();

                    if (!message.isEmpty()) {
                        logCallback.onLog(message);
                    }
                },
                statistics -> {
                    /*
                     * Progress is tracked by RecordingProgressTracker using
                     * the growing .ts file size, not notification progress.
                     */
                }
            );
        } catch (Exception e) {
            Log.e(TAG, "Failed to start FFmpeg with local proxy", e);
            stopProxy();

            if (logCallback != null) {
                logCallback.onLog("Local HLS proxy error: " + e.getMessage());
            }

            if (callback != null) {
                callback.onComplete(-1);
            }
        }
    }

    public static synchronized void cancel() {
        if (currentSession != null) {
            currentSession.cancel();
            currentSession = null;
        }

        FFmpegKit.cancel();
        stopProxy();
    }

    public static synchronized boolean isRunning() {
        return currentSession != null;
    }

    private static String buildRecordTsCommand(String proxyManifestUrl, String outPath) {
        return "-y"
            + " -hide_banner"
            + " -loglevel info"
            + " -reconnect 1"
            + " -reconnect_streamed 1"
            + " -reconnect_on_network_error 1"
            + " -reconnect_delay_max 5"
            + " -rw_timeout 90000000"
            + " -live_start_index 0"
            + " -i " + quote(proxyManifestUrl)
            + " -map 0:v:0?"
            + " -map 0:a:0?"
            + " -c copy"
            + " -f mpegts"
            + " " + quote(outPath);
    }

    private static void ensureParentDirectory(String outPath) {
        if (outPath == null || outPath.trim().isEmpty()) {
            return;
        }

        File file = new File(outPath);
        File parent = file.getParentFile();

        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
    }

    private static synchronized void stopProxy() {
        if (proxyServer == null) {
            return;
        }

        try {
            proxyServer.stop();
        } catch (Exception e) {
            Log.w(TAG, "Failed to stop HLS proxy", e);
        }

        proxyServer = null;
    }

    private static String quote(String value) {
        if (value == null) {
            return "''";
        }

        return "'" + value.replace("'", "'\\''") + "'";
    }

    public interface OnCompleteCallback {
        void onComplete(int returnCode);
    }

    public interface OnLogCallback {
        void onLog(String message);
    }
}
