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
    private static final Object sessionStartLock = new Object();

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
        PreparedRecording prepared = null;
        try {
            cancel(normalizeRecordingId(recordingId, outPath));
            prepared = prepareRecording(recordingId, manifestUrl, outPath, appendOutput, logCallback);
            prepared.start(callback);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start FFmpeg with local proxy", e);
            if (prepared != null) {
                prepared.close();
            }
            if (logCallback != null) {
                logCallback.onLog("Local HLS proxy error: " + e.getMessage());
            }

            if (callback != null) {
                callback.onComplete(-1);
            }
        }
    }

    /** Starts local proxy preparation before the recording lifecycle gate is acquired. */
    public static PreparedRecording prepareRecording(
        String recordingId,
        String manifestUrl,
        String outPath,
        boolean appendOutput,
        OnLogCallback logCallback
    ) throws Exception {
        String safeRecordingId = normalizeRecordingId(recordingId, "");
        ensureParentDirectory(outPath);
        HlsProxyServer proxyServer = new HlsProxyServer(
            logCallback,
            HlsProxyServer.PlaylistRewriteMode.FULL_RECORDING_DVR
        );
        try {
            proxyServer.start();
            String proxyManifestUrl = proxyServer.createProxyUrl(manifestUrl);
            String command = buildRecordTsCommand(proxyManifestUrl, outPath, appendOutput);
            return new PreparedRecording(safeRecordingId, proxyServer, proxyManifestUrl, command, logCallback);
        } catch (Exception e) {
            proxyServer.stop();
            throw e;
        }
    }

    public static final class PreparedRecording implements AutoCloseable {
        private final String recordingId;
        private final HlsProxyServer proxyServer;
        private final String proxyManifestUrl;
        private final String command;
        private final OnLogCallback logCallback;
        private boolean started;

        private PreparedRecording(
            String recordingId,
            HlsProxyServer proxyServer,
            String proxyManifestUrl,
            String command,
            OnLogCallback logCallback
        ) {
            this.recordingId = recordingId;
            this.proxyServer = proxyServer;
            this.proxyManifestUrl = proxyManifestUrl;
            this.command = command;
            this.logCallback = logCallback;
        }

        /** Returns after FFmpegKit has returned its cancellable session handle. */
        public synchronized RecorderSession start(
            OnCompleteCallback callback
        ) throws Exception {
            if (started) {
                throw new IllegalStateException("Prepared FFmpeg recording was already started.");
            }
            synchronized (sessionStartLock) {
                if (sessions.containsKey(recordingId)) {
                    throw new IllegalStateException("FFmpeg recorder already has a session for this recording.");
                }
                RecorderSession recorderSession = new RecorderSession(recordingId, proxyServer);
                sessions.put(recordingId, recorderSession);
                boolean executeCallInvoked = false;
                FFmpegSession returnedSession = null;
                try {
                    if (logCallback != null) {
                        logCallback.onLog("Local HLS proxy started for recording " + recordingId + ".");
                        logCallback.onLog("Proxy input: " + stripQuery(proxyManifestUrl));
                        logCallback.onLog("FFmpeg recording output is prepared.");
                    }
                    executeCallInvoked = true;
                    returnedSession = FFmpegKit.executeAsync(
                        command,
                        session -> finishSession(recordingId, recorderSession, session, callback, logCallback),
                        log -> {
                            if (logCallback == null || log == null || log.getMessage() == null) {
                                return;
                            }
                            String message = normalizeFfmpegLog(log.getMessage());
                            if (isImportantFfmpegLog(message)) {
                                logCallback.onLog(message);
                            }
                        },
                        statistics -> { }
                    );
                    if (returnedSession == null) {
                        throw new IllegalStateException("FFmpegKit returned no cancellable session handle.");
                    }
                    recorderSession.setSession(returnedSession);
                    started = true;
                    return recorderSession;
                } catch (Exception e) {
                    sessions.remove(recordingId, recorderSession);
                    boolean nativeStartOutcomeUnknown = executeCallInvoked && returnedSession == null;
                    if (returnedSession != null) {
                        // The caller receives this real session in the exception
                        // and can cancel it after leaving the lifecycle gate.
                    }
                    // The caller closes the prepared proxy after leaving its lifecycle gate.
                    throw new StartupException(e, nativeStartOutcomeUnknown, returnedSession);
                }
            }
        }

        @Override
        public synchronized void close() {
            if (!started) {
                proxyServer.stop();
                started = true;
            }
        }
    }

    /** Indicates whether FFmpegKit may have started without returning a cancellable session. */
    public static final class StartupException extends Exception {
        private final boolean nativeStartOutcomeUnknown;
        private final FFmpegSession returnedSession;

        private StartupException(
            Exception cause,
            boolean nativeStartOutcomeUnknown,
            FFmpegSession returnedSession
        ) {
            super(cause == null ? "FFmpeg startup failed." : cause.getMessage(), cause);
            this.nativeStartOutcomeUnknown = nativeStartOutcomeUnknown;
            this.returnedSession = returnedSession;
        }

        public boolean isNativeStartOutcomeUnknown() {
            return nativeStartOutcomeUnknown;
        }

        public FFmpegSession getReturnedSession() {
            return returnedSession;
        }

        /** Cancels a session returned before a later handoff step failed. Call outside lifecycle locks. */
        public void cancelReturnedSession() {
            if (returnedSession != null) {
                returnedSession.cancel();
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
            /*
             * Do not enable -reconnect_at_eof for HLS recordings. A normal HLS
             * playlist or segment HTTP response ends at EOF, and FFmpegKit then
             * retries the same local proxy URL with a byte offset instead of
             * letting the HLS demuxer reload the playlist. In logs this appears
             * as an endless "Will reconnect at <playlist length> ... End of file"
             * loop immediately after selecting a variant.
             */
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

    public static class RecorderSession implements RecorderLaunchCoordinator.Handle {
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

        @Override
        public synchronized void cancel() {
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
