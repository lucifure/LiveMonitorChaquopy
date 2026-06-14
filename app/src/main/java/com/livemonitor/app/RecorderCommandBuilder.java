package com.livemonitor.app;

import java.io.File;
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
        return buildYtDlpRecordArgs(videoUrl, outputTsPath, settings, remoteConfig, true);
    }

    public List<String> buildYtDlpRecordArgs(
        String videoUrl,
        String outputTsPath,
        AppSettings settings,
        RemoteConfig remoteConfig,
        boolean allowWaitForVideo
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
        args.add("--no-update");

        /*
         * Prefer one complete/muxed stream.
         * Avoid bestvideo+bestaudio because user requested no separate streams.
         */
        args.add("-f");
        args.add(settings.buildYtDlpFormatSelector());

        /*
         * Record live as MPEG-TS for better crash resilience. Prefer yt-dlp's
         * native HLS downloader so the bundled youtubedl-android runtime can
         * record without depending on an external ffmpeg executable path.
         */
        args.add("--hls-use-mpegts");
        args.add("--hls-prefer-native");

        /*
         * Do not stop because of missing HLS fragments.
         */
        if (settings.isSkipUnavailableFragmentsEnabled()) {
            args.add("--skip-unavailable-fragments");
            args.add("--fragment-retries");
            args.add("10");
        }

        /*
         * Wait and retry if the live stream page exists but video is not ready.
         */
        if (allowWaitForVideo && settings.isWaitForVideoEnabled()) {
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
         * Match the proven Termux recorder behavior, but keep retries bounded
         * so Android does not keep a broken recorder alive forever.
         */
        args.add("--retries");
        args.add("10");
        args.add("--extractor-retries");
        args.add("10");
        args.add("--file-access-retries");
        args.add("10");
        args.add("--retry-sleep");
        args.add("5");

        args.add("--socket-timeout");
        args.add("10");

        args.add("--no-part");
        args.add("--force-overwrites");

        /*
         * Force file output path.
         */
        args.add("-o");
        args.add(outputTsPath);

        /*
         * Remote config driven request headers/client behavior.
         */
        addRemoteConfigArgs(args, remoteConfig);
        addSettingsYtDlpArgs(args, settings);

        args.add(videoUrl);

        return Collections.unmodifiableList(args);
    }

    /**
     * Builds yt-dlp record arguments with an explicit extractor-args override.
     * Used by youtubedl-android retries so failed clients can be rotated without
     * giving up on the active recording.
     */
    public List<String> buildYtDlpRecordArgs(
        String videoUrl,
        String outputTsPath,
        AppSettings settings,
        RemoteConfig remoteConfig,
        String extractorArgsOverride,
        boolean allowLiveFromStart
    ) {
        return buildYtDlpRecordArgs(
            videoUrl,
            outputTsPath,
            settings,
            remoteConfig,
            extractorArgsOverride,
            allowLiveFromStart,
            true
        );
    }

    public List<String> buildYtDlpRecordArgs(
        String videoUrl,
        String outputTsPath,
        AppSettings settings,
        RemoteConfig remoteConfig,
        String extractorArgsOverride,
        boolean allowLiveFromStart,
        boolean allowWaitForVideo
    ) {
        if (settings == null) {
            settings = new AppSettings();
        }

        if (remoteConfig == null) {
            remoteConfig = new RemoteConfig();
        }

        List<String> args = new ArrayList<>(buildYtDlpRecordArgs(
            videoUrl,
            outputTsPath,
            settings,
            remoteConfig,
            allowWaitForVideo
        ));

        if (!allowLiveFromStart) {
            args.remove("--live-from-start");
        }

        if (!isBlank(extractorArgsOverride)) {
            removeOptionAndValue(args, "--extractor-args");

            if (!EXTRACTOR_ARGS_NONE.equals(extractorArgsOverride)) {
                int insertIndex = Math.max(1, args.size() - 1);
                args.add(insertIndex, "--extractor-args");
                args.add(insertIndex + 1, extractorArgsOverride.trim());
            }
        }

        return Collections.unmodifiableList(args);
    }


    /**
     * Builds the primary DASH recorder path from the proven Termux android_vr
     * command. When both saved cookies and a captured GVS PO token exist, switch
     * only the extractor identity to the yt-dlp-recommended mweb token+cookies
     * form; otherwise keep the original no-PO-token android_vr chain.
     */
    public List<String> buildDashRecordArgs(
        String playerClient,
        String videoUrl,
        String outputMp4Path,
        String tempDirectoryPath,
        AppSettings settings,
        RemoteConfig remoteConfig,
        boolean allowLiveFromStart,
        boolean allowWaitForVideo
    ) {
        if (remoteConfig == null) {
            remoteConfig = new RemoteConfig();
        }

        List<String> args = new ArrayList<>();

        args.add(remoteConfig.getYtDlpExecutable());
        args.add("--js-runtime");
        args.add("quickjs");

        if (allowWaitForVideo) {
            args.add("--wait-for-video");
            args.add("60");
        }

        if (allowLiveFromStart) {
            args.add("--live-from-start");
        }

        args.add("--extractor-args");
        String normalizedPlayerClient = normalizePlayerClient(playerClient);
        String mwebPoTokenExtractorArgs = "mweb".equals(normalizedPlayerClient)
            ? buildMwebPoTokenExtractorArgs(settings)
            : "";
        args.add(isBlank(mwebPoTokenExtractorArgs)
            ? "youtube:player_client=" + normalizedPlayerClient
            : mwebPoTokenExtractorArgs);
        args.add("--hls-use-mpegts");
        args.add("--no-part");
        args.add("--skip-unavailable-fragments");
        args.add("--retries");
        args.add("infinite");
        args.add("--fragment-retries");
        args.add("infinite");
        args.add("--extractor-retries");
        args.add("infinite");
        args.add("--file-access-retries");
        args.add("infinite");
        args.add("--retry-sleep");
        args.add("5");
        args.add("--socket-timeout");
        args.add("10");
        args.add("--force-ipv4");
        args.add("--no-check-certificates");
        args.add("-f");
        args.add("bv*[height<=480]+ba/b");
        args.add("--merge-output-format");
        args.add("mp4");

        File outputFile = isBlank(outputMp4Path) ? null : new File(outputMp4Path);

        /*
         * Use an absolute output path for youtubedl-android. Its request
         * wrapper can collapse repeated -P/--paths options, which leaves
         * yt-dlp running without a reliable home directory and produces the
         * observed stuck 0 B recorder diagnostics. Keeping the final output
         * absolute matches the Termux-style command while still allowing a
         * separate temp directory when the wrapper preserves it.
         */
        if (!isBlank(tempDirectoryPath)) {
            args.add("--paths");
            args.add("temp:" + tempDirectoryPath.trim());
        }

        args.add("-o");
        args.add(outputFile == null ? outputMp4Path : outputFile.getName());

        // Only mweb supports the cookies/PO-token path. Other player clients can
        // reject cookies and be skipped by yt-dlp, so keep them cookie-free.
        if ("mweb".equals(normalizedPlayerClient)) {
            addSettingsYtDlpArgs(args, settings, false);
        }

        args.add(videoUrl);

        return Collections.unmodifiableList(args);
    }

    /**
     * Backward-compatible wrapper for callers which still request the historical
     * android_vr DASH recorder explicitly.
     */
    public List<String> buildAndroidVrDashRecordArgs(
        String videoUrl,
        String outputMp4Path,
        String tempDirectoryPath,
        AppSettings settings,
        RemoteConfig remoteConfig,
        boolean allowLiveFromStart,
        boolean allowWaitForVideo
    ) {
        return buildDashRecordArgs(
            "android_vr",
            videoUrl,
            outputMp4Path,
            tempDirectoryPath,
            settings,
            remoteConfig,
            allowLiveFromStart,
            allowWaitForVideo
        );
    }

    private String normalizePlayerClient(String playerClient) {
        if (isBlank(playerClient)) {
            return "android_vr";
        }

        return playerClient.trim().toLowerCase(java.util.Locale.US);
    }

    private String buildMwebPoTokenExtractorArgs(AppSettings settings) {
        if (settings == null || !settings.hasYtDlpCookies() || !settings.hasYtDlpPoToken()) {
            return "";
        }

        String token = sanitizePoTokenValue(settings.getYtDlpPoTokenValue());

        if (isBlank(token)) {
            return "";
        }

        return "youtube:player_client=mweb"
            + ";po_token=mweb.gvs+"
            + token
            + ";player-skip=webpage,configs";
    }

    private String sanitizePoTokenValue(String value) {
        if (isBlank(value)) {
            return "";
        }

        return value.trim()
            .replaceAll("(?i)^[a-z0-9_]+\\.[a-z0-9_]+\\+", "")
            .replaceAll("[;\\s]+", "");
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
        args.add("--no-update");
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
        addSettingsYtDlpArgs(args, settings, isBlank(extractorArgsOverride));

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

        String cookiesPath = remoteConfig.getYtDlpCookiesPath();

        if (!isBlank(cookiesPath)) {
            args.add("--cookies");
            args.add(cookiesPath.trim());
        } else {
            String cookieHeader = remoteConfig.getYtDlpCookieHeader();

            if (!isBlank(cookieHeader)) {
                args.add("--add-header");
                args.add("Cookie:" + normalizeCookieHeader(cookieHeader));
            }
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

    private void addSettingsYtDlpArgs(List<String> args, AppSettings settings) {
        addSettingsYtDlpArgs(args, settings, true);
    }

    private void addSettingsYtDlpArgs(
        List<String> args,
        AppSettings settings,
        boolean allowExtractorArgs
    ) {
        if (args == null || settings == null) {
            return;
        }

        String extractorArgs = settings.getYtDlpExtractorArgs();

        if (allowExtractorArgs && isBlank(extractorArgs)) {
            extractorArgs = settings.buildYtDlpPoTokenExtractorArgs();
        }

        if (allowExtractorArgs && !isBlank(extractorArgs)) {
            removeOptionAndValue(args, "--extractor-args");
            int insertIndex = Math.max(1, args.size() - 1);
            args.add(insertIndex, "--extractor-args");
            args.add(insertIndex + 1, extractorArgs.trim());
        }

        String cookiesPath = settings.getYtDlpCookiesPath();

        if (!isBlank(cookiesPath)) {
            args.add("--cookies");
            args.add(cookiesPath.trim());
        } else {
            String cookieHeader = settings.getYtDlpCookieHeader();

            if (!isBlank(cookieHeader)) {
                args.add("--add-header");
                args.add("Cookie:" + normalizeCookieHeader(cookieHeader));
            }
        }
    }


    private void replaceOptionValue(List<String> args, String option, String value) {
        if (args == null || isBlank(option)) {
            return;
        }

        for (int index = 0; index < args.size() - 1; index++) {
            if (option.equals(args.get(index))) {
                args.set(index + 1, value == null ? "" : value);
                return;
            }
        }

        addOptionAndValueBeforeInput(args, option, value);
    }

    private void addOptionAndValueBeforeInput(List<String> args, String option, String value) {
        if (args == null || isBlank(option)) {
            return;
        }

        int insertIndex = Math.max(1, args.size() - 1);
        args.add(insertIndex, option);
        args.add(insertIndex + 1, value == null ? "" : value);
    }

    private void removeOptionAndValue(List<String> args, String option) {
        if (args == null || isBlank(option)) {
            return;
        }

        for (int index = 0; index < args.size(); index++) {
            if (!option.equals(args.get(index))) {
                continue;
            }

            args.remove(index);

            if (index < args.size()) {
                args.remove(index);
            }

            index--;
        }
    }


    private static String quoteForLog(String value) {
        if (value == null) {
            return "''";
        }

        if (value.toLowerCase(java.util.Locale.US).startsWith("cookie:")) {
            return "Cookie:<redacted>";
        }

        if (value.toLowerCase(java.util.Locale.US).contains("po_token=")) {
            value = value.replaceAll("(?i)(po_token=[^;\\s]+\\+)[^;\\s]+", "$1<redacted>");
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
