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

        args.add("yt-dlp");
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
        if (args == null || remoteConfig == null) {
            return;
        }

        String userAgent = remoteConfig.getUserAgent();

        if (!isBlank(userAgent)) {
            args.add("--user-agent");
            args.add(userAgent);
        }

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

    private static String quoteForLog(String value) {
        if (value == null) {
            return "''";
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
