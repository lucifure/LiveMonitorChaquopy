package com.livemonitor.app;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class FFmpegRunner {

    private static final String TAG = "FFmpegRunner";
    private static Process currentProcess = null;
    private static Context appContext = null;

    public static boolean setup(Context context) {
        appContext = context.getApplicationContext();
        try {
            File ffmpegFile = getFfmpegFile();
            if (ffmpegFile.exists() && ffmpegFile.canExecute()) {
                Log.d(TAG, "FFmpeg already exists at: " + ffmpegFile.getAbsolutePath());
                return true;
            }

            String assetName = getAssetName();
            if (assetName == null) {
                Log.e(TAG, "Unsupported ABI: " + Build.SUPPORTED_ABIS[0]);
                return false;
            }

            // List available assets for debugging
            try {
                String[] assets = context.getAssets().list("");
                if (assets != null) {
                    Log.d(TAG, "Available assets:");
                    for (String a : assets) Log.d(TAG, "  - " + a);
                }
            } catch (Exception e) {
                Log.e(TAG, "Could not list assets", e);
            }

            // Copy from assets to app's private files dir
            InputStream in = context.getAssets().open(assetName);
            OutputStream out = new FileOutputStream(ffmpegFile);
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            in.close();
            out.close();

            ffmpegFile.setExecutable(true);
            Log.d(TAG, "FFmpeg binary ready at: " + ffmpegFile.getAbsolutePath());
            return true;

        } catch (Exception e) {
            Log.e(TAG, "FFmpeg setup failed: " + e.getMessage(), e);
            return false;
        }
    }

    public static void executeAsync(String manifestUrl, String outPath,
                                    OnCompleteCallback callback,
                                    OnLogCallback logCallback) {
        new Thread(() -> {
            try {
                File ffmpegFile = getFfmpegFile();
                if (!ffmpegFile.exists()) {
                    if (logCallback != null) logCallback.onLog("FFmpeg binary not found!");
                    if (callback != null) callback.onComplete(-1);
                    return;
                }

                String cmd = ffmpegFile.getAbsolutePath()
                        + " -i " + manifestUrl
                        + " -c copy"
                        + " -bsf:a aac_adtstoasc"
                        + " -movflags +faststart"
                        + " " + outPath;

                ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", cmd);
                pb.redirectErrorStream(true);
                currentProcess = pb.start();

                InputStream is = currentProcess.getInputStream();
                byte[] buf = new byte[1024];
                StringBuilder line = new StringBuilder();
                int b;
                while ((b = is.read(buf)) != -1) {
                    String chunk = new String(buf, 0, b);
                    line.append(chunk);
                    int nl;
                    while ((nl = line.indexOf("\n")) >= 0) {
                        String l = line.substring(0, nl).trim();
                        if (!l.isEmpty() && logCallback != null) logCallback.onLog(l);
                        line.delete(0, nl + 1);
                    }
                }

                int exitCode = currentProcess.waitFor();
                if (callback != null) callback.onComplete(exitCode);

            } catch (Exception e) {
                Log.e(TAG, "FFmpeg execute error", e);
                if (callback != null) callback.onComplete(-1);
            } finally {
                currentProcess = null;
            }
        }).start();
    }

    public static void cancel() {
        if (currentProcess != null) {
            currentProcess.destroy();
            currentProcess = null;
        }
    }

    private static File getFfmpegFile() {
        if (appContext != null) {
            return new File(appContext.getFilesDir(), "ffmpeg");
        }
        return new File("/data/data/com.livemonitor.app/files/ffmpeg");
    }

    private static String getAssetName() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if (abi.equals("arm64-v8a")) return "ffmpeg_arm64";
            if (abi.equals("x86_64"))   return "ffmpeg_x86_64";
        }
        return null;
    }

    public interface OnCompleteCallback {
        void onComplete(int returnCode);
    }

    public interface OnLogCallback {
        void onLog(String message);
    }
}
