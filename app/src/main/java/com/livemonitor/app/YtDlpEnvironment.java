package com.livemonitor.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.File;

/**
 * Resolves the yt-dlp executable that this Android app is allowed to run.
 *
 * Android apps should not rely on Termux's private files or shared storage for
 * executables. The preferred app-owned location is the extracted native library
 * directory: place an executable per ABI as jniLibs/<abi>/libyt-dlp.so and this
 * helper will point RemoteConfig at the extracted file.
 */
public final class YtDlpEnvironment {
    private static final String BUNDLED_YTDLP_LIBRARY = "libyt-dlp.so";
    private static final String DEFAULT_COMMAND = "yt-dlp";

    private YtDlpEnvironment() {
    }

    public static Result prepare(Context context, RemoteConfig remoteConfig) {
        if (remoteConfig == null) {
            return Result.warning("Remote config is unavailable; yt-dlp executable was not prepared.");
        }

        String configuredExecutable = safeTrim(remoteConfig.getYtDlpExecutable());
        File bundledExecutable = findBundledExecutable(context);

        if (bundledExecutable != null) {
            remoteConfig.setYtDlpExecutable(bundledExecutable.getAbsolutePath());
            return Result.success(
                "Using bundled app executable for yt-dlp.",
                bundledExecutable.getAbsolutePath()
            );
        }

        if (isDefaultCommand(configuredExecutable)) {
            return Result.warning(
                "No bundled yt-dlp executable was found. Add an ABI-specific executable as "
                    + "app/src/main/jniLibs/<abi>/libyt-dlp.so or set ytDlpExecutable to an "
                    + "absolute app-accessible executable path. Current value uses PATH: "
                    + DEFAULT_COMMAND
            );
        }

        File configuredFile = new File(configuredExecutable);

        if (!configuredFile.isAbsolute()) {
            return Result.warning(
                "ytDlpExecutable must be an absolute path on Android unless a bundled "
                    + BUNDLED_YTDLP_LIBRARY
                    + " is present. Current value: "
                    + configuredExecutable
            );
        }

        if (!configuredFile.exists()) {
            return Result.warning("Configured yt-dlp executable does not exist: " + configuredExecutable);
        }

        if (configuredFile.isDirectory()) {
            return Result.warning("Configured yt-dlp executable is a directory: " + configuredExecutable);
        }

        if (!configuredFile.canExecute()) {
            boolean chmodWorked = configuredFile.setExecutable(true, false);

            if (!chmodWorked || !configuredFile.canExecute()) {
                return Result.warning(
                    "Configured yt-dlp path exists but is not executable by this app: "
                        + configuredExecutable
                        + ". Do not use Termux-private paths or shared storage; use an app-owned executable."
                );
            }
        }

        return Result.success("Using configured yt-dlp executable.", configuredExecutable);
    }

    public static String describeExecutableProblem(String executable) {
        String safeExecutable = safeTrim(executable);

        if (safeExecutable.isEmpty()) {
            return "yt-dlp executable path is empty.";
        }

        if (isDefaultCommand(safeExecutable)) {
            return "yt-dlp is configured as a PATH command. Android services often cannot run "
                + "Termux/PATH commands. Bundle yt-dlp as jniLibs/<abi>/"
                + BUNDLED_YTDLP_LIBRARY
                + " or configure an absolute app-accessible executable path.";
        }

        File file = new File(safeExecutable);

        if (!file.isAbsolute()) {
            return "yt-dlp executable is not an absolute path: " + safeExecutable;
        }

        if (!file.exists()) {
            return "yt-dlp executable does not exist: " + safeExecutable;
        }

        if (file.isDirectory()) {
            return "yt-dlp executable path is a directory: " + safeExecutable;
        }

        if (!file.canExecute()) {
            return "yt-dlp executable is not executable by this app: "
                + safeExecutable
                + ". Avoid Termux-private paths and shared storage.";
        }

        return "yt-dlp executable appears accessible: " + safeExecutable;
    }

    private static File findBundledExecutable(Context context) {
        if (context == null) {
            return null;
        }

        ApplicationInfo applicationInfo = context.getApplicationInfo();

        if (applicationInfo == null || isBlank(applicationInfo.nativeLibraryDir)) {
            return null;
        }

        File candidate = new File(applicationInfo.nativeLibraryDir, BUNDLED_YTDLP_LIBRARY);

        if (!candidate.exists() || candidate.isDirectory()) {
            return null;
        }

        if (!candidate.canExecute()) {
            candidate.setExecutable(true, false);
        }

        return candidate.canExecute() ? candidate : null;
    }

    private static boolean isDefaultCommand(String executable) {
        return isBlank(executable) || DEFAULT_COMMAND.equals(executable.trim());
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static final class Result {
        private final boolean success;
        private final String message;
        private final String executablePath;

        private Result(boolean success, String message, String executablePath) {
            this.success = success;
            this.message = message;
            this.executablePath = executablePath;
        }

        public static Result success(String message, String executablePath) {
            return new Result(true, message, executablePath);
        }

        public static Result warning(String message) {
            return new Result(false, message, "");
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public String getExecutablePath() {
            return executablePath;
        }
    }
}
