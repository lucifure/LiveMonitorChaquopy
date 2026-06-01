package com.livemonitor.app;

import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Utility helpers for distinguishing YouTube channel URLs from direct video URLs. */
public final class YouTubeUrlUtils {

    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    private YouTubeUrlUtils() {
        // Utility class.
    }

    public static boolean isDirectVideoUrl(String url) {
        return !extractVideoId(url).isEmpty();
    }

    public static String extractVideoId(String value) {
        if (value == null) {
            return "";
        }

        String trimmed = value.trim();

        if (isVideoId(trimmed)) {
            return trimmed;
        }

        try {
            URL url = new URL(trimmed);
            String host = url.getHost() == null ? "" : url.getHost().toLowerCase();
            String path = url.getPath() == null ? "" : url.getPath();

            if (host.endsWith("youtu.be")) {
                return cleanVideoId(path.replaceFirst("^/", ""));
            }

            String queryVideoId = extractQueryParameter(url.getQuery(), "v");

            if (!queryVideoId.isEmpty()) {
                return queryVideoId;
            }

            String[] parts = path.split("/");

            for (int i = 0; i < parts.length - 1; i++) {
                String part = parts[i];

                if ("live".equals(part)
                    || "shorts".equals(part)
                    || "embed".equals(part)
                    || "v".equals(part)) {
                    String videoId = cleanVideoId(parts[i + 1]);

                    if (!videoId.isEmpty()) {
                        return videoId;
                    }
                }
            }
        } catch (Exception ignored) {
            return "";
        }

        return "";
    }

    public static String buildWatchUrl(String videoId) {
        String safeVideoId = cleanVideoId(videoId);
        return safeVideoId.isEmpty() ? "" : "https://www.youtube.com/watch?v=" + safeVideoId;
    }

    private static String extractQueryParameter(String query, String key) {
        if (query == null || query.trim().isEmpty()) {
            return "";
        }

        String[] pairs = query.split("&");

        for (String pair : pairs) {
            int separator = pair.indexOf('=');
            String name = separator >= 0 ? pair.substring(0, separator) : pair;
            String value = separator >= 0 ? pair.substring(separator + 1) : "";

            if (key.equals(name)) {
                return cleanVideoId(value);
            }
        }

        return "";
    }

    private static String cleanVideoId(String value) {
        if (value == null) {
            return "";
        }

        String cleaned = value.trim().replaceAll("[?#&/].*", "");
        return isVideoId(cleaned) ? cleaned : "";
    }

    private static boolean isVideoId(String value) {
        if (value == null) {
            return false;
        }

        Matcher matcher = VIDEO_ID_PATTERN.matcher(value.trim());
        return matcher.matches();
    }
}
