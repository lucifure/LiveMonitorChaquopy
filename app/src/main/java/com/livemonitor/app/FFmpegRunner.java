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

    public static boolean setup(Context context) {
        try {
            File ffmpegFile = getFfmpegFile(context);
            if (ffmpegFile.exists()) return true;

            // Pick the right binary for this device ABI
            String assetName = getAssetName();
            if (assetName == null) {
                Log.e(TAG, "Unsupported ABI: " + Build.SUPPORTED_ABIS[0]);
                return false;
            }

            // Copy from assets to app's private files dir
            InputStream in = context.getAssets().open(assetName);
            OutputStream out = new FileOutputStream(ffmpegFile);
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            in.close();
            out.close();

            // Make it executable
            ffmpegFile.setExecutable(true);
            Log.d(TAG, "FFmpeg binary ready at: " + ffmpegFile.getAbsolutePath());
            return true;

        } catch (Exception e) {
            Log.e(TAG, "FFmpeg setup failed", e);
            return false;
        }
    }

    public static void executeAsync(String manifestUrl, String outPath,
                                    OnCompleteCallback callback,
                                    OnLogCallback logCallback) {
        new Thread(() -> {
            try {
                File ffmpegFile = getFfmpegFile(null);
                String cmd = ffmpegFile.getAbsolutePath()
                        + " -i " + manifestUrl
                        + " -c copy"
                        + " -bsf:a aac_adtstoasc"
                        + " -movflags +faststart"
                        + " " + outPath;

                ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", cmd);
                pb.redirectErrorStream(true);
                currentProcess = pb.start();

                // Read FFmpeg output line by line
                InputStream is = currentProcess.getInputStream();
                byte[] buf = new byte[1024];
                StringBuilder line = new StringBuilder();
                int b;
                while ((b = is.read(buf)) != -1) {
                    String chunk = new String(buf, 0, b);
                    line.append(chunk);
                    // Log complete lines only
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

    private static File getFfmpegFile(Context context) {
        // Use a static path after first setup
        String path = "/data/data/com.livemonitor.app/files/ffmpeg";
        return new File(path);
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
