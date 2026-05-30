package com.livemonitor.app;

import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class HlsProxyServer {

    private static final String TAG = "HlsProxyServer";
    private static final String HOST = "127.0.0.1";
    private static final Pattern URI_ATTR_PATTERN = Pattern.compile("URI=\"([^\"]+)\"");

    private final OkHttpClient client;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ServerSocket serverSocket;
    private Thread serverThread;
    private int port;

    public HlsProxyServer() {
        client = new OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build();
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
        return "http://" + HOST + ":" + port + "/proxy?url=" + encoded;
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
            s.setSoTimeout(30000);

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)
            );

            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }

            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // Consume HTTP request headers.
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

            proxyRemoteUrl(s, remoteUrl);

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

    private void proxyRemoteUrl(Socket socket, String remoteUrl) throws IOException {
        Request request = new Request.Builder()
            .url(remoteUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36")
            .header("Referer", "https://www.youtube.com/")
            .header("Origin", "https://www.youtube.com")
            .header("Accept", "*/*")
            .build();

        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();

            if (!response.isSuccessful() || body == null) {
                writeTextResponse(
                    socket,
                    response.code(),
                    "Upstream Error",
                    "Upstream error: " + response.code()
                );
                return;
            }

            String contentType = response.header("Content-Type", "");
            boolean isPlaylist =
                remoteUrl.toLowerCase(Locale.US).contains(".m3u8")
                || contentType.toLowerCase(Locale.US).contains("mpegurl")
                || contentType.toLowerCase(Locale.US).contains("application/vnd.apple.mpegurl");

            if (isPlaylist) {
                String playlist = body.string();
                String rewritten = rewritePlaylist(remoteUrl, playlist);
                writeTextResponse(socket, 200, "OK", rewritten, "application/vnd.apple.mpegurl");
            } else {
                writeStreamResponse(socket, response, body);
            }
        }
    }

    private String rewritePlaylist(String playlistUrl, String playlist) {
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

    private void writeStreamResponse(Socket socket, Response response, ResponseBody body) throws IOException {
        String contentType = response.header("Content-Type", "application/octet-stream");

        BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
        writeHeaders(out, 200, "OK", contentType);

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
        writeHeaders(out, code, status, contentType, bytes.length);
        out.write(bytes);
        out.flush();
    }

    private void writeHeaders(BufferedOutputStream out,
                              int code,
                              String status,
                              String contentType) throws IOException {
        String headers =
            "HTTP/1.1 " + code + " " + status + "\r\n"
            + "Content-Type: " + contentType + "\r\n"
            + "Connection: close\r\n"
            + "\r\n";

        out.write(headers.getBytes(StandardCharsets.UTF_8));
    }

    private void writeHeaders(BufferedOutputStream out,
                              int code,
                              String status,
                              String contentType,
                              int contentLength) throws IOException {
        String headers =
            "HTTP/1.1 " + code + " " + status + "\r\n"
            + "Content-Type: " + contentType + "\r\n"
            + "Content-Length: " + contentLength + "\r\n"
            + "Connection: close\r\n"
            + "\r\n";

        out.write(headers.getBytes(StandardCharsets.UTF_8));
    }
          }
