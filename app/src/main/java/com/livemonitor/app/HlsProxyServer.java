package com.livemonitor.app;

import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class HlsProxyServer {

    private static final String TAG = "HlsProxyServer";
    private static final String HOST = "127.0.0.1";
    private static final int LIVE_SEGMENTS_TO_KEEP = 8;
    private static final int MAX_UPSTREAM_ATTEMPTS = 3;
    private static final long INITIAL_UPSTREAM_RETRY_DELAY_MS = 750L;

    private static final Pattern URI_ATTR_PATTERN = Pattern.compile("URI=\"([^\"]+)\"");
    private static final Pattern BANDWIDTH_PATTERN = Pattern.compile("BANDWIDTH=(\\d+)");
    private static final Pattern RESOLUTION_PATTERN = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)");
    private static final Pattern MEDIA_SEQUENCE_PATTERN = Pattern.compile("#EXT-X-MEDIA-SEQUENCE:(\\d+)");
    private static final Pattern KEYFORMAT_ATTR_PATTERN = Pattern.compile("KEYFORMAT=\"([^\"]+)\"|KEYFORMAT=([^,]+)");

    public enum PlaylistRewriteMode {
        LIVE_EDGE_PLAYBACK,
        FULL_RECORDING_DVR
    }

    private final OkHttpClient client;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger proxyLogCount = new AtomicInteger(0);
    private final FFmpegRunner.OnLogCallback logCallback;
    private final PlaylistRewriteMode playlistRewriteMode;

    private ServerSocket serverSocket;
    private Thread serverThread;
    private int port;

    public HlsProxyServer() {
        this(null, PlaylistRewriteMode.LIVE_EDGE_PLAYBACK);
    }

    public HlsProxyServer(FFmpegRunner.OnLogCallback logCallback) {
        this(logCallback, PlaylistRewriteMode.LIVE_EDGE_PLAYBACK);
    }

    public HlsProxyServer(FFmpegRunner.OnLogCallback logCallback, PlaylistRewriteMode playlistRewriteMode) {
        this.logCallback = logCallback;
        this.playlistRewriteMode = playlistRewriteMode == null
            ? PlaylistRewriteMode.LIVE_EDGE_PLAYBACK
            : playlistRewriteMode;

        client = new OkHttpClient.Builder()
            .dns(HlsProxyServer::lookupIpv4First)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build();
    }

    private static List<InetAddress> lookupIpv4First(String hostname) throws UnknownHostException {
        List<InetAddress> all = Dns.SYSTEM.lookup(hostname);
        List<InetAddress> ipv4 = new ArrayList<>();
        List<InetAddress> rest = new ArrayList<>();

        for (InetAddress address : all) {
            if (address instanceof Inet4Address) {
                ipv4.add(address);
            } else {
                rest.add(address);
            }
        }

        if (!ipv4.isEmpty()) {
            ipv4.addAll(rest);
            return ipv4;
        }

        return all;
    }

    public synchronized void start() throws IOException {
        if (running.get()) {
            return;
        }

        serverSocket = new ServerSocket(0, 50, InetAddress.getByName(HOST));
        port = serverSocket.getLocalPort();
        running.set(true);

        serverThread = new Thread(this::acceptLoop, "hls-proxy-server");
        serverThread.start();

        Log.d(TAG, "HLS proxy started on http://" + HOST + ":" + port);
        proxyLog("playlist rewrite mode: " + describePlaylistRewriteMode());
    }

    public synchronized void stop() {
        running.set(false);

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }

            serverSocket = null;
        }

        serverThread = null;
        port = 0;

        Log.d(TAG, "HLS proxy stopped.");
    }

    public String createProxyUrl(String remoteUrl) {
        String encoded = URLEncoder.encode(remoteUrl, StandardCharsets.UTF_8);
        String lower = remoteUrl == null ? "" : remoteUrl.toLowerCase(Locale.US);

        boolean isSegmentUrl =
            lower.contains("/videoplayback/")
            || lower.contains("/seg.ts")
            || lower.contains("file/seg.ts");

        boolean isPlaylistUrl =
            lower.contains(".m3u8")
            || lower.contains("/hls_playlist/")
            || lower.contains("/hls_variant/");

        String localPath = "/proxy";

        if (isSegmentUrl) {
            localPath = "/seg.ts";
        } else if (isPlaylistUrl) {
            localPath = "/playlist.m3u8";
        }

        return "http://" + HOST + ":" + port + localPath + "?url=" + encoded;
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                Thread worker = new Thread(() -> handleSocket(socket), "hls-proxy-client");
                worker.start();
            } catch (IOException e) {
                if (running.get()) {
                    Log.e(TAG, "Accept failed", e);
                }
            }
        }
    }

    private void handleSocket(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(90000);

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)
            );

            String requestLine = reader.readLine();

            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }

            Map<String, String> requestHeaders = new HashMap<>();

            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');

                if (colon > 0) {
                    String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
                    String value = line.substring(colon + 1).trim();
                    requestHeaders.put(name, value);
                }
            }

            String[] parts = requestLine.split(" ");

            if (parts.length < 2) {
                writeTextResponse(s, 400, "Bad Request", "Bad request");
                return;
            }

            String path = parts[1];
            String remoteUrl = extractRemoteUrl(path);

            if (remoteUrl == null || remoteUrl.isEmpty()) {
                writeTextResponse(s, 400, "Bad Request", "Missing url");
                return;
            }

            String rangeHeader = requestHeaders.get("range");
            proxyRemoteUrl(s, remoteUrl, rangeHeader);

        } catch (Exception e) {
            Log.e(TAG, "Socket handling failed", e);
        }
    }

    private String extractRemoteUrl(String path) {
        int marker = path.indexOf("?url=");

        if (marker < 0) {
            return null;
        }

        String encoded = path.substring(marker + 5);
        int amp = encoded.indexOf('&');

        if (amp >= 0) {
            encoded = encoded.substring(0, amp);
        }

        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }

    private void proxyRemoteUrl(Socket socket, String remoteUrl, String rangeHeader) throws IOException {
        boolean isSegmentUrl =
            remoteUrl.contains("/videoplayback/")
            || remoteUrl.contains("/seg.ts")
            || remoteUrl.contains("file/seg.ts");

        boolean isPlaylistUrl =
            remoteUrl.toLowerCase(Locale.US).contains(".m3u8")
            || remoteUrl.contains("/hls_playlist/")
            || remoteUrl.contains("/hls_variant/");

        IOException lastIoException = null;
        int maxAttempts = isSegmentUrl || isPlaylistUrl ? MAX_UPSTREAM_ATTEMPTS : 1;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Request.Builder builder = new Request.Builder()
                .url(remoteUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36")
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
                .header("Connection", "close");

            if (isPlaylistUrl) {
                builder.header("Referer", "https://www.youtube.com/");
                builder.header("Origin", "https://www.youtube.com");
            }

            if (rangeHeader != null && !rangeHeader.isEmpty()) {
                builder.header("Range", rangeHeader);
            }

            try (Response response = client.newCall(builder.build()).execute()) {
                ResponseBody body = response.body();
                String contentType = response.header("Content-Type", "");
                long contentLength = body != null ? body.contentLength() : -1;

                if (isSegmentUrl || !response.isSuccessful() || attempt > 1) {
                    proxyLog(
                        "upstream "
                            + response.code()
                            + " attempt="
                            + attempt
                            + "/"
                            + maxAttempts
                            + " type="
                            + contentType
                            + " len="
                            + contentLength
                            + " url="
                            + shortRemoteUrl(remoteUrl)
                    );
                }

                if (!response.isSuccessful() || body == null) {
                    String errorText = readBodyForLog(body);

                    if (!errorText.isEmpty()) {
                        proxyLog("upstream error body: " + shorten(errorText, 220));
                    }

                    if (shouldRetryUpstream(response.code(), attempt, maxAttempts)) {
                        sleepBeforeRetry(attempt);
                        continue;
                    }

                    int errorCode = response.isSuccessful() ? 502 : response.code();
                    String errorStatus = response.isSuccessful() ? "Bad Gateway" : response.message();
                    String errorMessage = response.isSuccessful()
                        ? "Upstream returned an empty response body"
                        : "Upstream returned " + response.code() + " " + response.message();

                    if (!errorText.isEmpty()) {
                        errorMessage += "\n" + errorText;
                    }

                    writeTextResponse(socket, errorCode, errorStatus, errorMessage);
                    return;
                }

                boolean isPlaylist =
                    !isSegmentUrl
                    && (remoteUrl.toLowerCase(Locale.US).contains(".m3u8")
                        || contentType.toLowerCase(Locale.US).contains("mpegurl")
                        || contentType.toLowerCase(Locale.US).contains("application/vnd.apple.mpegurl"));

                if (isPlaylist) {
                    String playlist = body.string();
                    String rewritten = rewritePlaylist(remoteUrl, playlist);
                    writeTextResponse(socket, 200, "OK", rewritten, "application/vnd.apple.mpegurl");
                    return;
                }

                if (isSegmentUrl && looksLikeTextResponse(contentType)) {
                    String text = body.string();
                    proxyLog("segment returned text instead of media: " + shorten(text, 220));

                    if (attempt < maxAttempts) {
                        sleepBeforeRetry(attempt);
                        continue;
                    }

                    writeTextResponse(
                        socket,
                        502,
                        "Bad Gateway",
                        "Segment returned text instead of media"
                    );
                    return;
                }

                writeStreamResponse(socket, response, body, isSegmentUrl);
                return;
            } catch (IOException e) {
                lastIoException = e;
                proxyLog(
                    "upstream IO error attempt="
                        + attempt
                        + "/"
                        + maxAttempts
                        + " error="
                        + shorten(e.getMessage(), 180)
                        + " url="
                        + shortRemoteUrl(remoteUrl)
                );

                if (attempt < maxAttempts) {
                    sleepBeforeRetry(attempt);
                    continue;
                }
            }
        }

        if (lastIoException != null) {
            writeTextResponse(socket, 504, "Gateway Timeout", "Upstream timeout or network error");
        }
    }

    private String readBodyForLog(ResponseBody body) {
        if (body == null) {
            return "";
        }

        try {
            return body.string();
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean shouldRetryUpstream(int responseCode, int attempt, int maxAttempts) {
        if (attempt >= maxAttempts) {
            return false;
        }

        return responseCode == 408
            || responseCode == 429
            || responseCode == 500
            || responseCode == 502
            || responseCode == 503
            || responseCode == 504;
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(Math.min(5_000L, INITIAL_UPSTREAM_RETRY_DELAY_MS * Math.max(1, attempt)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String rewritePlaylist(String playlistUrl, String playlist) {
        if (playlist.contains("#EXT-X-STREAM-INF")) {
            return rewriteMasterPlaylistChooseOneVariant(playlistUrl, playlist);
        }

        if (playlist.contains("#EXTINF")) {
            return rewriteMediaPlaylist(playlistUrl, playlist);
        }

        return rewriteSimplePlaylist(playlistUrl, playlist);
    }

    private String rewriteSimplePlaylist(String playlistUrl, String playlist) {
        StringBuilder out = new StringBuilder();
        String[] lines = playlist.split("\\r?\\n");

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                out.append('\n');
                continue;
            }

            if (trimmed.startsWith("#")) {
                out.append(rewriteTagUris(playlistUrl, line)).append('\n');
                continue;
            }

            String absolute = resolveUrl(playlistUrl, trimmed);
            out.append(createProxyUrl(absolute)).append('\n');
        }

        return out.toString();
    }

    private String rewriteMasterPlaylistChooseOneVariant(String playlistUrl, String playlist) {
        String[] lines = playlist.split("\\r?\\n");
        List<String> headerLines = new ArrayList<>();
        List<Variant> variants = new ArrayList<>();

        String pendingStreamInfo = null;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                continue;
            }

            if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                pendingStreamInfo = rewriteTagUris(playlistUrl, line);
                continue;
            }

            if (pendingStreamInfo != null) {
                if (!trimmed.startsWith("#")) {
                    String absolute = resolveUrl(playlistUrl, trimmed);
                    variants.add(new Variant(pendingStreamInfo, createProxyUrl(absolute), absolute));
                    pendingStreamInfo = null;
                    continue;
                }

                headerLines.add(pendingStreamInfo);
                pendingStreamInfo = null;
            }

            if (!trimmed.startsWith("#EXT-X-STREAM-INF")) {
                headerLines.add(rewriteTagUris(playlistUrl, line));
            }
        }

        if (variants.isEmpty()) {
            return rewriteSimplePlaylist(playlistUrl, playlist);
        }

        Variant chosen = chooseBestVariant(variants);

        proxyLog("selected HLS variant " + describeVariant(chosen.streamInfo));

        StringBuilder out = new StringBuilder();

        boolean hasPlaylistHeader = false;

        for (String header : headerLines) {
            if (header.startsWith("#EXTM3U")) {
                hasPlaylistHeader = true;
            }
        }

        if (!hasPlaylistHeader) {
            out.append("#EXTM3U\n");
        }

        for (String header : headerLines) {
            if (!header.startsWith("#EXT-X-STREAM-INF")) {
                out.append(header).append('\n');
            }
        }

        out.append(chosen.streamInfo).append('\n');
        out.append(chosen.proxyUrl).append('\n');

        return out.toString();
       }

    private Variant chooseBestVariant(List<Variant> variants) {
        Variant bestUnder480 = null;
        Variant bestAny = null;

        for (Variant variant : variants) {
            int height = parseHeight(variant.streamInfo);
            int bandwidth = parseBandwidth(variant.streamInfo);

            if (bestAny == null || bandwidth > parseBandwidth(bestAny.streamInfo)) {
                bestAny = variant;
            }

            if (height > 0 && height <= 480) {
                if (bestUnder480 == null || height > parseHeight(bestUnder480.streamInfo)) {
                    bestUnder480 = variant;
                }
            }
        }

        if (bestUnder480 != null) {
            return bestUnder480;
        }

        for (Variant variant : variants) {
            if (variant.remoteUrl.contains("/itag/94/")
                || variant.remoteUrl.contains("/itag/93/")
                || variant.remoteUrl.contains("/itag/92/")
                || variant.remoteUrl.contains("/itag/91/")) {
                return variant;
            }
        }

        return bestAny != null ? bestAny : variants.get(0);
    }

    private int parseHeight(String streamInfo) {
        Matcher matcher = RESOLUTION_PATTERN.matcher(streamInfo);

        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(2));
            } catch (Exception ignored) {
            }
        }

        return -1;
    }

    private int parseBandwidth(String streamInfo) {
        Matcher matcher = BANDWIDTH_PATTERN.matcher(streamInfo);

        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (Exception ignored) {
            }
        }

        return -1;
    }

    private String describeVariant(String streamInfo) {
        int height = parseHeight(streamInfo);
        int bandwidth = parseBandwidth(streamInfo);

        if (height > 0 && bandwidth > 0) {
            return height + "p bw=" + bandwidth;
        }

        if (height > 0) {
            return height + "p";
        }

        if (bandwidth > 0) {
            return "bw=" + bandwidth;
        }

        return "unknown";
    }

    private String rewriteMediaPlaylist(String playlistUrl, String playlist) {
        if (playlistRewriteMode == PlaylistRewriteMode.FULL_RECORDING_DVR) {
            return rewriteMediaPlaylistForRecordingDvr(playlistUrl, playlist);
        }

        return rewriteMediaPlaylistAtLiveEdge(playlistUrl, playlist);
    }

    private String rewriteMediaPlaylistAtLiveEdge(String playlistUrl, String playlist) {
        return rewriteMediaPlaylistWithSegmentWindow(playlistUrl, playlist, false);
    }

    private String rewriteMediaPlaylistForRecordingDvr(String playlistUrl, String playlist) {
        return rewriteMediaPlaylistWithSegmentWindow(playlistUrl, playlist, true);
    }

    private String rewriteMediaPlaylistWithSegmentWindow(String playlistUrl,
                                                        String playlist,
                                                        boolean keepAllSegments) {
        String[] lines = playlist.split("\\r?\\n");

        List<String> headerLines = new ArrayList<>();
        List<List<String>> segmentGroups = new ArrayList<>();
        List<String> pendingSegmentTags = new ArrayList<>();
        List<String> currentSegmentGroup = new ArrayList<>();

        boolean sawFirstSegment = false;
        boolean collectingSegment = false;
        boolean hasEndList = false;
        long originalMediaSequence = parseMediaSequence(playlist);

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                continue;
            }

            if (trimmed.equals("#EXT-X-ENDLIST")) {
                hasEndList = true;
                continue;
            }

            if (trimmed.startsWith("#EXTINF")) {
                collectingSegment = true;
                currentSegmentGroup = new ArrayList<>(pendingSegmentTags);
                pendingSegmentTags.clear();
                currentSegmentGroup.add(rewriteTagUris(playlistUrl, line));
                sawFirstSegment = true;
                continue;
            }

            if (collectingSegment) {
                if (trimmed.startsWith("#")) {
                    currentSegmentGroup.add(rewriteTagUris(playlistUrl, line));
                    continue;
                }

                String absolute = resolveUrl(playlistUrl, trimmed);
                currentSegmentGroup.add(createProxyUrl(absolute));
                segmentGroups.add(currentSegmentGroup);

                currentSegmentGroup = new ArrayList<>();
                collectingSegment = false;
                continue;
            }

            if (trimmed.startsWith("#")) {
                if (trimmed.startsWith("#EXT-X-MEDIA-SEQUENCE")) {
                    continue;
                }

                String rewritten = rewriteTagUris(playlistUrl, line);

                if (sawFirstSegment || isSegmentScopedTag(trimmed)) {
                    pendingSegmentTags.add(rewritten);
                } else {
                    headerLines.add(rewritten);
                }
            }
        }

        int from = keepAllSegments ? 0 : Math.max(0, segmentGroups.size() - LIVE_SEGMENTS_TO_KEEP);

        StringBuilder out = new StringBuilder();

        boolean hasPlaylistHeader = false;
        boolean hasVersion = false;
        boolean hasTargetDuration = false;

        for (String header : headerLines) {
            if (header.startsWith("#EXTM3U")) {
                hasPlaylistHeader = true;
            }

            if (header.startsWith("#EXT-X-VERSION")) {
                hasVersion = true;
            }

            if (header.startsWith("#EXT-X-TARGETDURATION")) {
                hasTargetDuration = true;
            }
        }

        if (!hasPlaylistHeader) {
            out.append("#EXTM3U\n");
        }

        for (String header : headerLines) {
            out.append(header).append('\n');
        }

        if (!hasVersion) {
            out.append("#EXT-X-VERSION:3\n");
        }

        if (!hasTargetDuration) {
            out.append("#EXT-X-TARGETDURATION:6\n");
        }

        out.append("#EXT-X-MEDIA-SEQUENCE:")
            .append(originalMediaSequence + from)
            .append('\n');

        SegmentState segmentState = segmentStateBefore(segmentGroups, from);

        for (int i = from; i < segmentGroups.size(); i++) {
            List<String> group = segmentGroups.get(i);

            if (i == from && from > 0) {
                appendMissingSegmentState(out, segmentState, group);
            }

            for (String groupLine : group) {
                out.append(groupLine).append('\n');
            }
        }

        if (keepAllSegments && hasEndList) {
            out.append("#EXT-X-ENDLIST\n");
        }

        return out.toString();
    }

    private boolean isSegmentScopedTag(String trimmedLine) {
        return trimmedLine.startsWith("#EXT-X-KEY")
            || trimmedLine.startsWith("#EXT-X-MAP")
            || trimmedLine.startsWith("#EXT-X-DISCONTINUITY")
            || trimmedLine.startsWith("#EXT-X-PROGRAM-DATE-TIME")
            || trimmedLine.startsWith("#EXT-X-DATERANGE");
    }

    private SegmentState segmentStateBefore(List<List<String>> segmentGroups, int toExclusive) {
        SegmentState state = new SegmentState();

        for (int i = 0; i < toExclusive && i < segmentGroups.size(); i++) {
            state.update(segmentGroups.get(i));
        }

        return state;
    }

    private void appendMissingSegmentState(StringBuilder out, SegmentState state, List<String> firstKeptGroup) {
        if (state.mapTag != null && !containsMapTag(firstKeptGroup)) {
            out.append(state.mapTag).append('\n');
        }

        for (Map.Entry<String, String> entry : state.keyTagsByFormat.entrySet()) {
            if (!containsKeyFormat(firstKeptGroup, entry.getKey())) {
                out.append(entry.getValue()).append('\n');
            }
        }
    }

    private boolean containsMapTag(List<String> group) {
        for (String line : group) {
            if (line.trim().startsWith("#EXT-X-MAP")) {
                return true;
            }
        }

        return false;
    }

    private boolean containsKeyFormat(List<String> group, String keyFormat) {
        for (String line : group) {
            String trimmed = line.trim();

            if (trimmed.startsWith("#EXT-X-KEY") && keyFormat.equals(keyFormatKey(trimmed))) {
                return true;
            }
        }

        return false;
    }

    private String keyFormatKey(String keyLine) {
        Matcher matcher = KEYFORMAT_ATTR_PATTERN.matcher(keyLine);

        if (matcher.find()) {
            String quoted = matcher.group(1);
            String unquoted = matcher.group(2);
            return quoted != null ? quoted : unquoted;
        }

        return "identity";
    }

    private long parseMediaSequence(String playlist) {
        Matcher matcher = MEDIA_SEQUENCE_PATTERN.matcher(playlist);

        if (matcher.find()) {
            try {
                return Long.parseLong(matcher.group(1));
            } catch (Exception ignored) {
            }
        }

        return 0;
    }

    private String rewriteTagUris(String baseUrl, String line) {
        Matcher matcher = URI_ATTR_PATTERN.matcher(line);
        StringBuffer buffer = new StringBuffer();

        while (matcher.find()) {
            String original = matcher.group(1);
            String absolute = resolveUrl(baseUrl, original);
            String proxy = createProxyUrl(absolute);
            matcher.appendReplacement(buffer, "URI=\"" + Matcher.quoteReplacement(proxy) + "\"");
        }

        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String resolveUrl(String baseUrl, String value) {
        try {
            URL base = new URL(baseUrl);
            URL resolved = new URL(base, value);
            return resolved.toString();
        } catch (Exception e) {
            return value;
        }
    }

    private void writeStreamResponse(Socket socket,
                                     Response response,
                                     ResponseBody body,
                                     boolean isSegmentUrl) throws IOException {
        String contentType = response.header("Content-Type", "application/octet-stream");

        if (isSegmentUrl
            && (contentType == null
                || contentType.isEmpty()
                || contentType.toLowerCase(Locale.US).contains("application/octet-stream"))) {
            contentType = "video/mp2t";
        }

        String contentRange = response.header("Content-Range", null);
        long contentLength = body.contentLength();
        int code = response.code();

        BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());

        if (contentLength >= 0 && contentLength <= Integer.MAX_VALUE) {
            writeHeaders(out, code, response.message(), contentType, (int) contentLength, contentRange);
        } else {
            writeHeaders(out, code, response.message(), contentType, contentRange);
        }

        try (InputStream in = body.byteStream()) {
            byte[] buffer = new byte[64 * 1024];
            int read;

            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }

        out.flush();
    }

    private void writeTextResponse(Socket socket, int code, String status, String body) throws IOException {
        writeTextResponse(socket, code, status, body, "text/plain; charset=utf-8");
    }

    private void writeTextResponse(Socket socket,
                                   int code,
                                   String status,
                                   String body,
                                   String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
        writeHeaders(out, code, status, contentType, bytes.length, null);
        out.write(bytes);
        out.flush();
    }

    private void writeHeaders(BufferedOutputStream out,
                              int code,
                              String status,
                              String contentType,
                              String contentRange) throws IOException {
        String headers =
            "HTTP/1.1 " + code + " " + status + "\r\n"
            + "Content-Type: " + contentType + "\r\n"
            + "Accept-Ranges: bytes\r\n"
            + contentRangeHeader(contentRange)
            + "Connection: close\r\n"
            + "\r\n";

        out.write(headers.getBytes(StandardCharsets.UTF_8));
    }

    private void writeHeaders(BufferedOutputStream out,
                              int code,
                              String status,
                              String contentType,
                              int contentLength,
                              String contentRange) throws IOException {
        String headers =
            "HTTP/1.1 " + code + " " + status + "\r\n"
            + "Content-Type: " + contentType + "\r\n"
            + "Content-Length: " + contentLength + "\r\n"
            + "Accept-Ranges: bytes\r\n"
            + contentRangeHeader(contentRange)
            + "Connection: close\r\n"
            + "\r\n";

        out.write(headers.getBytes(StandardCharsets.UTF_8));
    }

    private String contentRangeHeader(String contentRange) {
        if (contentRange == null || contentRange.isEmpty()) {
            return "";
        }

        return "Content-Range: " + contentRange + "\r\n";
    }

    private boolean looksLikeTextResponse(String contentType) {
        String lower = contentType == null ? "" : contentType.toLowerCase(Locale.US);

        return lower.contains("text/")
            || lower.contains("html")
            || lower.contains("json")
            || lower.contains("xml");
    }

    private String describePlaylistRewriteMode() {
        if (playlistRewriteMode == PlaylistRewriteMode.FULL_RECORDING_DVR) {
            return "full-recording/DVR mode (preserving all available media playlist segments)";
        }

        return "live-edge playback mode (keeping last " + LIVE_SEGMENTS_TO_KEEP + " segments)";
    }

    private void proxyLog(String message) {
        Log.d(TAG, message);

        if (logCallback == null) {
            return;
        }

        int count = proxyLogCount.incrementAndGet();

        if (count <= 10
            || message.contains("error")
            || message.contains("403")
            || message.contains("404")
            || message.contains("502")) {
            logCallback.onLog("Proxy: " + message);
        }
    }

    private String shortRemoteUrl(String remoteUrl) {
        if (remoteUrl == null) {
            return "";
        }

        int sq = remoteUrl.indexOf("/sq/");

        if (sq >= 0) {
            return redactSensitiveUrlText(remoteUrl.substring(sq));
        }

        int itag = remoteUrl.indexOf("/itag/");

        if (itag >= 0) {
            return shorten(redactSensitiveUrlText(remoteUrl.substring(itag)), 160);
        }

        return shorten(redactSensitiveUrlText(remoteUrl), 160);
    }

    private String redactSensitiveUrlText(String value) {
        if (value == null) {
            return "";
        }

        return value
            .replaceAll("(?i)(/ip/)[^/?#]+", "$1<redacted>")
            .replaceAll("(?i)(/(?:sig|signature|lsig|spc|bui|ei|expire|tx|txs|xpc|n|rqh)/)[^/?#]+", "$1<redacted>")
            .replaceAll("(?i)([?&](?:ip|sig|signature|lsig|spc|bui|ei|expire|xpc|n)=)[^&#]+", "$1<redacted>");
    }

    private String shorten(String value, int max) {
        if (value == null) {
            return "";
        }

        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();

        if (clean.length() <= max) {
            return clean;
        }

        return clean.substring(0, max) + "...";
    }

    private static class SegmentState {
        final Map<String, String> keyTagsByFormat = new HashMap<>();
        String mapTag;

        void update(List<String> group) {
            for (String line : group) {
                String trimmed = line.trim();

                if (trimmed.startsWith("#EXT-X-KEY")) {
                    String keyFormat = keyFormatKeyStatic(trimmed);
                    keyTagsByFormat.put(keyFormat, line);
                } else if (trimmed.startsWith("#EXT-X-MAP")) {
                    mapTag = line;
                }
            }
        }

        private static String keyFormatKeyStatic(String keyLine) {
            Matcher matcher = KEYFORMAT_ATTR_PATTERN.matcher(keyLine);

            if (matcher.find()) {
                String quoted = matcher.group(1);
                String unquoted = matcher.group(2);
                return quoted != null ? quoted : unquoted;
            }

            return "identity";
        }
    }

    private static class Variant {
        final String streamInfo;
        final String proxyUrl;
        final String remoteUrl;

        Variant(String streamInfo, String proxyUrl, String remoteUrl) {
            this.streamInfo = streamInfo;
            this.proxyUrl = proxyUrl;
            this.remoteUrl = remoteUrl;
        }
    }
}
