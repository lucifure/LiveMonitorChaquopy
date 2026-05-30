package com.livemonitor.app;

import android.content.Context;
import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

public class FFmpegRunner {

    private static final String TAG = "FFmpegRunner";
    private static FFmpegSession currentSession = null;

    public static boolean setup(Context context) {
        Log.d(TAG, "Using FFmpegKit dependency.");
        return true;
    }

    public static void executeAsync(String manifestUrl,
                                    String outPath,
                                    OnCompleteCallback callback,
                                    OnLogCallback logCallback) {
        String command =
            "-y"
            + " -i " + quote(manifestUrl)
            + " -c copy"
            + " -bsf:a aac_adtstoasc"
            + " -movflags +faststart"
            + " " + quote(outPath);

        if (logCallback != null) {
            logCallback.onLog("FFmpegKit command started.");
            logCallback.onLog("Output: " + outPath);
        }

        currentSession = FFmpegKit.executeAsync(
            command,
            session -> {
                currentSession = null;

                ReturnCode returnCode = session.getReturnCode();

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
                if (logCallback != null && log != null && log.getMessage() != null) {
                    String message = log.getMessage().trim();

                    if (!message.isEmpty()) {
                        logCallback.onLog(message);
                    }
                }
            },
            statistics -> {
                // Not needed for now.
            }
        );
    }

    public static void cancel() {
        if (currentSession != null) {
            currentSession.cancel();
            currentSession = null;
        }

        FFmpegKit.cancel();
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
