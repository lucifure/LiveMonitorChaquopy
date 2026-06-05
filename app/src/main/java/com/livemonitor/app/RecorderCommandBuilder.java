package com.livemonitor.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds recorder and conversion commands.
 *
 * The app records live streams into MPEG-TS first for resilience.
 * After the stream ends, FFmpeg converts TS to MP4.
 *
 * Recording requirements:
 * - max quality default 480p
 * - no separate video/audio stream merge
 * - wait for video behavior
 * - live from start
 * - skip unavailable fragments
 */
public class RecorderCommandBuilder {

    public static final String EXTRACTOR_ARGS_NONE = "__live_monitor_no_extractor_args__";

    /**
     * Builds yt-dlp style arguments for recording a live video URL to a TS file.
     *
     * This is intentionally returned as argument list instead of one shell string.
     * It is safer when passed to ProcessBuilder.
     */
    public List<String> buildYtDlpRecordArgs(
        String videoUrl,
        String outputTsPath,
        AppSettings settings,
        RemoteConfig remoteConfig
    ) {
        if (settings == null) {
            settings = new AppSettings();
        }

        if (remoteConfig == null) {
            remoteConfig = new RemoteConfig();
        }

        List<String> args = new ArrayList<>();

        args.add(remoteConfig.getYtDlpExecutable());
        args.add("--js-runtime");
        args.add("quickjs");
        args.add("--force-ipv4");
        args.add("--no-check-certificates");

        /*
         * Prefer one complete/muxed stream.
         * Avoid bestvideo+bestaudio because user requested no separate streams.
         */
        args.add("-f");
        args.add(settings.buildYtDlpFormatSelector());

        /*
         * Record live as MPEG-TS for better crash resilience.
         */
        args.add("--hls-use-mpegts");

        /*
         * Do not stop because of missing HLS fragments.
         */
        if (settings.isSkipUnavailableFragmentsEnabled()) {
            args.add("--skip-unavailable-fragments");
            args.add("--fragment-retries");
            args.add("infinite");
        }

        /*
         * Wait and retry if the live stream page exists but video is not ready.
         */
        if (settings.isWaitForVideoEnabled()) {
            args.add("--wait-for-video");
            args.add("60");
        }

        /*
         * Record live from the start where supported.
         */
        if (settings.isLiveFromStartEnabled()) {
            args.add("--live-from-start");
        }

        /*
         * Match the proven Termux recorder behavior: keep retrying live
         * fragments and extractors instead of ending a long recording early.
         */
        args.add("--retries");
        args.add("infinite");
        args.add("--extractor-retries");
        args.add("infinite");
        args.add("--file-access-retries");
        args.add("infinite");
        args.add("--retry-sleep");
        args.add("5");

        args.add("--socket-timeout");
        args.add("10");

        args.add("--no-part");

        /*
         * Force file output path.
         */
        args.add("-o");
        args.add(outputTsPath);

        /*
         * Remote config driven request headers/client behavior.
         */
        addRemoteConfigArgs(args, remoteConfig);
        addSettingsCookieArgs(args, settings);

        args.add(videoUrl);

        return Collections.unmodifiableList(args);
    }


    /**
     * Builds yt-dlp arguments for resolving one playable media URL.
     * MonitorService feeds the resolved URL into FFmpegRunner so the existing
     * recording/progress/recovery pipeline stays unchanged.
     */
    public List<String> buildYtDlpResolveArgs(
        String videoUrl,
        AppSettings settings,
        RemoteConfig remoteConfig
    ) {
        return buildYtDlpResolveArgs(videoUrl, settings, remoteConfig, "");
    }

    public List<String> buildYtDlpResolveArgs(
        String videoUrl,
        AppSettings settings,
        RemoteConfig remoteConfig,
        String extractorArgsOverride
    ) {
        return buildYtDlpResolveArgs(
            videoUrl,
            settings,
            remoteConfig,
            extractorArgsOverride,
            true
        );
    }

    public List<String> buildYtDlpResolveArgs(
        String videoUrl,
        AppSettings settings,
        RemoteConfig remoteConfig,
        String extractorArgsOverride,
        boolean allowLiveFromStart
    ) {
        if (settings == null) {
            settings = new AppSettings();
        }

        if (remoteConfig == null) {
            remoteConfig = new RemoteConfig();
        }

        List<String> args = new ArrayList<>();

        args.add(remoteConfig.getYtDlpExecutable());
        args.add("--no-playlist");
        args.add("--no-warnings");
        args.add("--force-ipv4");
        args.add("--no-check-certificates");
        args.add("--socket-timeout");
        args.add("10");
        args.add("-f");
        args.add(settings.buildYtDlpFormatSelector());

        if (allowLiveFromStart && settings.isLiveFromStartEnabled()) {
            args.add("--live-from-start");
        }

        if (settings.isSkipUnavailableFragmentsEnabled()) {
            args.add("--skip-unavailable-fragments");
        }

        addRemoteConfigArgs(args, remoteConfig, extractorArgsOverride);
        addSettingsCookieArgs(args, settings);

        args.add("--get-url");
        args.add(videoUrl);

        return Collections.unmodifiableList(args);
    }

    /**
     * Builds a command using FFmpeg to remux TS to MP4 without re-encoding.
     */
    public List<String> buildTsToMp4Args(String inputTsPath, String outputMp4Path) {
        List<String> args = new ArrayList<>();

        args.add("ffmpeg");
        args.add("-y");
        args.add("-i");
        args.add(inputTsPath);

        /*
         * Copy streams. This is fast and avoids quality loss.
         */
        args.add("-c");
        args.add("copy");

        /*
         * Make MP4 friendlier for playback/streaming.
         */
        args.add("-movflags");
        args.add("+faststart");

        args.add(outputMp4Path);

        return Collections.unmodifiableList(args);
    }

    /**
     * Builds a readable command string for logging/debugging only.
     */
    public String toLogString(List<String> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();

        for (String arg : args) {
            if (builder.length() > 0) {
                builder.append(' ');
            }

            builder.append(quoteForLog(arg));
        }

        return builder.toString();
    }

    /**
     * Builds an FFmpegRunner-compatible command string if existing FFmpegRunner
     * requires one string instead of ProcessBuilder arguments.
     */
    public String toCommandString(List<String> args) {
        return toLogString(args);
    }

    private void addRemoteConfigArgs(List<String> args, RemoteConfig remoteConfig) {
        addRemoteConfigArgs(args, remoteConfig, "");
    }

    private void addRemoteConfigArgs(
        List<String> args,
        RemoteConfig remoteConfig,
        String extractorArgsOverride
    ) {
        if (args == null || remoteConfig == null) {
            return;
        }

        String userAgent = remoteConfig.getUserAgent();

        if (!isBlank(userAgent)) {
            args.add("--user-agent");
            args.add(userAgent);
        }

        boolean suppressExtractorArgs = EXTRACTOR_ARGS_NONE.equals(extractorArgsOverride);
        String extractorArgs = isBlank(extractorArgsOverride) || suppressExtractorArgs
            ? remoteConfig.getYtDlpExtractorArgs()
            : extractorArgsOverride.trim();

        if (!suppressExtractorArgs && !isBlank(extractorArgs)) {
            args.add("--extractor-args");
            args.add(extractorArgs);
        } else if (!suppressExtractorArgs) {
            RemoteConfig.YoutubeClient client = remoteConfig.getPrimaryClient();

            if (client != null && client.isValid()) {
                /*
                 * yt-dlp supports extractor args for YouTube clients.
                 * The exact supported client names can change over time, which is why
                 * RemoteConfig keeps this configurable.
                 */
                args.add("--extractor-args");
                args.add("youtube:player_client=" + client.getClientName().toLowerCase());
            }
        }

        for (String extraArg : remoteConfig.getYtDlpExtraArgs()) {
            if (!isBlank(extraArg)) {
                args.add(extraArg.trim());
            }
        }

        String apiKey = remoteConfig.getPrimaryApiKey();

        if (!isBlank(apiKey)) {
            /*
             * Kept as a generic header-friendly hook.
             * If later recorder implementation uses direct Innertube calls, it can
             * read the same RemoteConfig API keys from storage.
             */
            args.add("--add-header");
            args.add("X-Goog-Api-Key:" + apiKey);
        }

        String cookieHeader = remoteConfig.getYtDlpCookieHeader();

        if (!isBlank(cookieHeader)) {
            args.add("--add-header");
            args.add("Cookie:" + normalizeCookieHeader(cookieHeader));
        }

        String cookiesPath = remoteConfig.getYtDlpCookiesPath();

        if (!isBlank(cookiesPath)) {
            args.add("--cookies");
            args.add(cookiesPath.trim());
        }

        String cookiesFromBrowser = remoteConfig.getYtDlpCookiesFromBrowser();

        if (!isBlank(cookiesFromBrowser)) {
            args.add("--cookies-from-browser");
            args.add(cookiesFromBrowser.trim());
        }

        String visitorDataUrl = remoteConfig.getVisitorDataUrl();

        if (!isBlank(visitorDataUrl)) {
            /*
             * yt-dlp may not directly consume visitorDataUrl.
             * We still log/configure it centrally so MonitorService or a future
             * Innertube helper can fetch visitorData from the same config source.
             */
            args.add("--add-header");
            args.add("X-LiveMonitor-VisitorData-Url:" + visitorDataUrl);
        }
    }

    private String normalizeCookieHeader(String cookieHeader) {
        String normalized = cookieHeader == null ? "" : cookieHeader.trim();

        if (normalized.toLowerCase(java.util.Locale.US).startsWith("cookie:")) {
            return normalized.substring("cookie:".length()).trim();
        }

        return normalized;
    }

    private void addSettingsCookieArgs(List<String> args, AppSettings settings) {
        if (args == null || settings == null) {
            return;
        }

        String cookieHeader = settings.getYtDlpCookieHeader();

        if (!isBlank(cookieHeader)) {
            args.add("--add-header");
            args.add("Cookie:" + normalizeCookieHeader(cookieHeader));
        }

        String cookiesPath = settings.getYtDlpCookiesPath();

        if (!isBlank(cookiesPath)) {
            args.add("--cookies");
            args.add(cookiesPath.trim());
        }
    }

    private static String quoteForLog(String value) {
        if (value == null) {
            return "''";
        }

        if (value.toLowerCase(java.util.Locale.US).startsWith("cookie:")) {
            return "Cookie:<redacted>";
        }

        if (value.isEmpty()) {
            return "''";
        }

        boolean needsQuote = value.contains(" ")
            || value.contains("\"")
            || value.contains("'")
            || value.contains("$")
            || value.contains("&")
            || value.contains(";")
            || value.contains("(")
            || value.contains(")");

        if (!needsQuote) {
            return value;
        }

        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
