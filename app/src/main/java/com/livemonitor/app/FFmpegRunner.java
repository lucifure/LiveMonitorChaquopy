package com.livemonitor.app;

import android.content.Context;
import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FFmpeg wrapper used by MonitorService.
 *
 * Updated behavior:
 * - records HLS into MPEG-TS during active recording
 * - keeps local HLS proxy support
 * - does not convert to MP4 here
 * - MP4 conversion is handled after recording finishes by MonitorService
 * - keeps one FFmpeg/proxy session per recording so multiple streams do not
 *   cancel or replace one another
 */
public class FFmpegRunner {

    private static final String TAG = "FFmpegRunner";

    private static final Map<String, RecorderSession> sessions = new ConcurrentHashMap<>();

    public static boolean setup(Context context) {
        Log.d(TAG, "Using FFmpegKit dependency with per-recording local HLS proxies.");
        return true;
    }

    public static void executeAsync(
        String recordingId,
        String manifestUrl,
        String outPath,
        OnCompleteCallback callback,
        OnLogCallback logCallback
    ) {
        executeAsync(recordingId, manifestUrl, outPath, false, callback, logCallback);
    }

    public static void executeAsync(
        String recordingId,
        String manifestUrl,
        String outPath,
        boolean appendOutput,
        OnCompleteCallback callback,
        OnLogCallback logCallback
    ) {
        String safeRecordingId = normalizeRecordingId(recordingId, outPath);

        try {
            cancel(safeRecordingId);
            ensureParentDirectory(outPath);

            HlsProxyServer proxyServer = new HlsProxyServer(
                logCallback,
                HlsProxyServer.PlaylistRewriteMode.FULL_RECORDING_DVR
            );
            proxyServer.start();

            RecorderSession recorderSession = new RecorderSession(safeRecordingId, proxyServer);
            sessions.put(safeRecordingId, recorderSession);

            String proxyManifestUrl = proxyServer.createProxyUrl(manifestUrl);

            if (logCallback != null) {
                logCallback.onLog("Local HLS proxy started for recording " + safeRecordingId + ".");
                logCallback.onLog("Proxy input: " + stripQuery(proxyManifestUrl));
                logCallback.onLog("Recording output: " + outPath);
            }

            String command = buildRecordTsCommand(proxyManifestUrl, outPath, appendOutput);

            if (logCallback != null) {
                logCallback.onLog(
                    appendOutput
                        ? "FFmpegKit TS recording command started in resume append mode."
                        : "FFmpegKit TS recording command started."
                );
            }

            FFmpegSession ffmpegSession = FFmpegKit.executeAsync(
                command,
                session -> finishSession(safeRecordingId, recorderSession, session, callback, logCallback),
                log -> {
                    if (logCallback == null || log == null || log.getMessage() == null) {
                        return;
                    }

                    String message = normalizeFfmpegLog(log.getMessage());

                    if (isImportantFfmpegLog(message)) {
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

            recorderSession.setSession(ffmpegSession);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start FFmpeg with local proxy", e);
            RecorderSession removed = sessions.remove(safeRecordingId);

            if (removed != null) {
                removed.stopProxy();
            }

            if (logCallback != null) {
                logCallback.onLog("Local HLS proxy error: " + e.getMessage());
            }

            if (callback != null) {
                callback.onComplete(-1);
            }
        }
    }

    public static void executeAsync(
        String manifestUrl,
        String outPath,
        OnCompleteCallback callback,
        OnLogCallback logCallback
    ) {
        executeAsync(outPath, manifestUrl, outPath, callback, logCallback);
    }

    public static boolean cancel(String recordingId) {
        String safeRecordingId = normalizeRecordingId(recordingId, "");
        RecorderSession recorderSession = sessions.remove(safeRecordingId);

        if (recorderSession == null) {
            return false;
        }

        recorderSession.cancel();
        return true;
    }

    public static synchronized void cancel() {
        List<String> recordingIds = new ArrayList<>(sessions.keySet());

        for (String recordingId : recordingIds) {
            cancel(recordingId);
        }
    }

    public static boolean isRunning(String recordingId) {
        return sessions.containsKey(normalizeRecordingId(recordingId, ""));
    }

    public static boolean isRunning() {
        return !sessions.isEmpty();
    }

    private static void finishSession(
        String recordingId,
        RecorderSession expectedSession,
        FFmpegSession completedSession,
        OnCompleteCallback callback,
        OnLogCallback logCallback
    ) {
        RecorderSession currentSession = sessions.get(recordingId);

        if (currentSession == expectedSession) {
            sessions.remove(recordingId);
        }

        expectedSession.stopProxy();

        ReturnCode returnCode = completedSession == null ? null : completedSession.getReturnCode();

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

        String failStackTrace = completedSession == null ? "" : completedSession.getFailStackTrace();

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
    }

    private static String buildRecordTsCommand(String proxyManifestUrl, String outPath, boolean appendOutput) {
        return (appendOutput ? "" : "-y")
            + " -hide_banner"
            + " -loglevel warning"
            + " -reconnect 1"
            + " -reconnect_at_eof 1"
            + " -reconnect_streamed 1"
            + " -reconnect_on_network_error 1"
            + " -reconnect_on_http_error 4xx,5xx"
            + " -reconnect_delay_max 5"
            + " -rw_timeout 10000000"
            + " -multiple_requests 1"
            + " -live_start_index 0"
            + " -m3u8_hold_counters 1000000"
            + " -i " + quote(proxyManifestUrl)
            + " -map 0:v:0?"
            + " -map 0:a:0?"
            + " -c copy"
            + " -mpegts_flags +resend_headers"
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

    private static String normalizeRecordingId(String recordingId, String fallback) {
        if (recordingId != null && !recordingId.trim().isEmpty()) {
            return recordingId.trim();
        }

        if (fallback != null && !fallback.trim().isEmpty()) {
            return fallback.trim();
        }

        return "default";
    }

    private static String stripQuery(String url) {
        if (url == null) {
            return "";
        }

        int queryIndex = url.indexOf('?');
        return queryIndex >= 0 ? url.substring(0, queryIndex) : url;
    }

    private static String normalizeFfmpegLog(String message) {
        return message == null ? "" : message.trim();
    }

    private static boolean isImportantFfmpegLog(String message) {
        if (message == null || message.trim().isEmpty()) {
            return false;
        }

        String lower = message.toLowerCase(java.util.Locale.US);

        return lower.contains("error")
            || lower.contains("failed")
            || lower.contains("timeout")
            || lower.contains("reconnect")
            || lower.contains("http")
            || lower.contains("opening")
            || lower.contains("server returned")
            || lower.contains("invalid")
            || lower.contains("end of file")
            || lower.contains("no such file")
            || lower.contains("non-monotonous")
            || lower.contains("corrupt")
            || lower.contains("hls")
            || lower.contains("segment")
            || lower.contains("ts recording");
    }

    private static String quote(String value) {
        return value == null ? "''" : "'" + value.replace("'", "'\\''") + "'";
    }

    public interface OnCompleteCallback {
        void onComplete(int returnCode);
    }

    public interface OnLogCallback {
        void onLog(String message);
    }

    private static class RecorderSession {
        private final String recordingId;
        private final HlsProxyServer proxyServer;
        private FFmpegSession session;
        private boolean cancelled;

        private RecorderSession(String recordingId, HlsProxyServer proxyServer) {
            this.recordingId = recordingId;
            this.proxyServer = proxyServer;
        }

        private synchronized void setSession(FFmpegSession session) {
            if (cancelled && session != null) {
                session.cancel();
                return;
            }

            this.session = session;
        }

        private synchronized void cancel() {
            cancelled = true;

            if (session != null) {
                session.cancel();
                session = null;
            }

            stopProxy();
        }

        private void stopProxy() {
            if (proxyServer == null) {
                return;
            }

            try {
                proxyServer.stop();
            } catch (Exception e) {
                Log.w(TAG, "Failed to stop HLS proxy for recording " + recordingId, e);
            }
        }
    }
}
