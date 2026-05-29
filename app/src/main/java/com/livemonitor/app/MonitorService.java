package com.livemonitor.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MonitorService extends Service {

    private static final String TAG             = "MonitorService";
    private static final String CHANNEL_ID      = "LiveMonitorChannel";
    private static final String CHANNEL_LIVE_ID = "LiveDetectedChannel";
    private static final int    NOTIF_ID        = 1;
    private static final int    POLL_SECONDS    = 60;
    private static final String YT_API_KEY      = "AIzaSyDnAsBrxe_aFkUSpqkrFDczUw-PpLoEhuY";

    // Each entry: { clientName, clientVersion, userAgent, androidSdkVersion }
    // NO api key in the player URL — YouTube internal player works without it
    private static final String[][] YT_CLIENTS = {
        {
            "ANDROID_TESTSUITE",
            "1.9",
            "com.google.android.youtube/1.9 (Linux; U; Android 11) gzip",
            "30"
        },
        {
            "ANDROID",
            "18.11.34",
            "com.google.android.youtube/18.11.34 (Linux; U; Android 11) gzip",
            "30"
        },
        {
            "ANDROID_VR",
            "1.56.21",
            "com.google.android.apps.youtube.vr.oculus/1.56.21 (Linux; U; Android 12) gzip",
            "32"
        },
        {
            "IOS",
            "19.09.3",
            "com.google.ios.youtube/19.09.3 (iPhone14,3; U; CPU iOS 16_0 like Mac OS X)",
            ""
        },
        {
            "WEB_EMBEDDED_PLAYER",
            "2.20231121.08.00",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            ""
        }
    };

    private PowerManager.WakeLock wakeLock;
    private ExecutorService executor;
    private volatile boolean running   = false;
    private volatile boolean recording = false;
    private String channelUrl          = "";

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newCachedThreadPool();
        createNotificationChannels();

        boolean ffmpegReady = FFmpegRunner.setup(this);
        if (!ffmpegReady) {
            sendLog("WARNING: FFmpeg setup failed!", "error");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();

        if ("START".equals(action)) {
            channelUrl = intent.getStringExtra("url");
            startForeground(NOTIF_ID, buildNotification("Monitoring: " + shortUrl(channelUrl)));
            acquireWakeLock();
            running = true;
            executor.execute(this::monitorLoop);
        } else if ("STOP".equals(action)) {
            stopAll();
        }
        return START_NOT_STICKY;
    }

    // ── Monitor loop ──────────────────────────────────────────────────────────

    private void monitorLoop() {
        sendLog("Monitor started.", "success");

        while (running) {
            sendLog("Checking live status...", "dim");
            try {
                String channelId = resolveChannelId(channelUrl);
                if (channelId == null) {
                    sendLog("Could not resolve channel ID. Retry in 60s...", "error");
                    sleep(POLL_SECONDS);
                    continue;
                }

                String[] liveInfo = checkLive(channelId);

                if (liveInfo != null) {
                    String videoId = liveInfo[0];
                    String title   = liveInfo[1];

                    sendLog("LIVE DETECTED: " + title, "live");
                    updateNotification("LIVE: " + title);
                    sendLiveNotification(title, videoId);

                    if (!recording) {
                        recording = true;
                        executor.execute(() -> startRecording(videoId, title));
                    }

                    while (running && recording) {
                        sleep(POLL_SECONDS);
                        if (!running) break;
                        sendLog("Re-checking stream status...", "dim");
                        String[] stillLive = checkLive(channelId);
                        if (stillLive == null) {
                            sendLog("Stream ended. Stopping recorder...", "warning");
                            stopRecording();
                            updateNotification("Monitoring: " + shortUrl(channelUrl));
                            break;
                        }
                    }
                } else {
                    sendLog("Not live. Next check in " + POLL_SECONDS + "s...", "dim");
                    sleep(POLL_SECONDS);
                }

            } catch (Exception e) {
                sendLog("Monitor error: " + e.getMessage(), "error");
                sleep(POLL_SECONDS);
            }
        }
        sendLog("Monitor stopped.", "info");
    }

    // ── Get HLS manifest — try multiple YouTube clients ───────────────────────

    private String getHlsManifestUrl(String videoId) {
        for (String[] client : YT_CLIENTS) {
            String clientName    = client[0];
            String clientVersion = client[1];
            String userAgent     = client[2];
            String sdkVersion    = client[3];

            sendLog("Trying client: " + clientName + " v" + clientVersion, "info");
            try {
                String result = tryGetHls(videoId, clientName, clientVersion,
                                          userAgent, sdkVersion);
                if (result != null) {
                    sendLog("SUCCESS with client: " + clientName, "success");
                    return result;
                }
            } catch (Exception e) {
                sendLog("Client " + clientName + " exception: " + e.getMessage(), "error");
            }
        }
        sendLog("All clients failed to get HLS URL.", "error");
        return null;
    }

    private String tryGetHls(String videoId, String clientName, String clientVersion,
                              String userAgent, String androidSdkVersion) {
        try {
            // NO api key in URL — YouTube's internal player doesn't need it
            URL url = new URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("User-Agent", userAgent);
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            conn.setRequestProperty("Origin", "https://www.youtube.com");
            conn.setRequestProperty("X-YouTube-Client-Name", "3");
            conn.setRequestProperty("X-YouTube-Client-Version", clientVersion);
            conn.setRequestProperty("X-Origin", "https://www.youtube.com");

            // Build context — only include androidSdkVersion for Android clients
            String clientBlock;
            if (!androidSdkVersion.isEmpty()) {
                clientBlock = "{"
                    + "\"clientName\":\"" + clientName + "\","
                    + "\"clientVersion\":\"" + clientVersion + "\","
                    + "\"androidSdkVersion\":" + androidSdkVersion + ","
                    + "\"hl\":\"en\","
                    + "\"gl\":\"US\","
                    + "\"utcOffsetMinutes\":0"
                    + "}";
            } else {
                clientBlock = "{"
                    + "\"clientName\":\"" + clientName + "\","
                    + "\"clientVersion\":\"" + clientVersion + "\","
                    + "\"hl\":\"en\","
                    + "\"gl\":\"US\","
                    + "\"utcOffsetMinutes\":0"
                    + "}";
            }

            String body = "{"
                + "\"videoId\":\"" + videoId + "\","
                + "\"context\":{"
                +   "\"client\":" + clientBlock
                + "},"
                + "\"playbackContext\":{"
                +   "\"contentPlaybackContext\":{"
                +     "\"html5Preference\":\"HTML5_PREF_WANTS\""
                +   "}"
                + "}"
                + "}";

            byte[] bodyBytes = body.getBytes("UTF-8");
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
            OutputStream os = conn.getOutputStream();
            os.write(bodyBytes);
            os.flush();
            os.close();

            int responseCode = conn.getResponseCode();

            // Read body regardless of status
            BufferedReader reader;
            try {
                reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            } catch (Exception e) {
                reader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
            }
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            if (responseCode != 200) {
                String errSnippet = sb.toString();
                errSnippet = errSnippet.substring(0, Math.min(300, errSnippet.length()));
                sendLog("Client " + clientName + " HTTP " + responseCode + ": " + errSnippet, "error");
                return null;
            }

            JSONObject json = new JSONObject(sb.toString());

            // Log playability status
            JSONObject playability = json.optJSONObject("playabilityStatus");
            if (playability != null) {
                String status = playability.optString("status", "unknown");
                String reason = playability.optString("reason", "");
                sendLog("Playability [" + clientName + "]: " + status
                    + (reason.isEmpty() ? "" : " — " + reason), "info");

                if ("ERROR".equals(status)
                        || "LOGIN_REQUIRED".equals(status)
                        || "UNPLAYABLE".equals(status)) {
                    return null;
                }
            }

            JSONObject streamingData = json.optJSONObject("streamingData");
            if (streamingData == null) {
                sendLog("No streamingData from " + clientName, "warning");
                return null;
            }

            String hlsUrl = streamingData.optString("hlsManifestUrl", null);
            if (hlsUrl != null && !hlsUrl.isEmpty()) {
                return hlsUrl;
            }

            // Log what keys ARE present so we can debug further
            sendLog("No hlsManifestUrl from " + clientName
                + " — streamingData keys: " + streamingData.keys().toString(), "warning");
            return null;

        } catch (Exception e) {
            sendLog("tryGetHls [" + clientName + "] error: " + e.getMessage(), "error");
            return null;
        }
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    private void startRecording(String videoId, String title) {
        try {
            sendLog("Getting stream URL for video: " + videoId, "info");

            String manifestUrl = getHlsManifestUrl(videoId);

            if (manifestUrl == null) {
                sendLog("Could not get stream URL — all clients failed!", "error");
                recording = false;
                return;
            }

            sendLog("Stream URL obtained. Starting FFmpeg...", "success");
            sendLog("HLS: " + manifestUrl.substring(0, Math.min(80, manifestUrl.length())) + "...", "dim");

            String date    = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(new Date());
            String safe    = title.replaceAll("[^a-zA-Z0-9._-]", "_");
            safe           = safe.substring(0, Math.min(safe.length(), 40));
            String outDir  = getExternalFilesDir(null) + "/YouTubeMonitor/";
            String outPath = outDir + safe + "_" + date + ".mp4";

            new File(outDir).mkdirs();
            sendLog("Saving to: " + outPath, "info");

            FFmpegRunner.executeAsync(
                manifestUrl,
                outPath,
                returnCode -> {
                    if (returnCode == 0) {
                        sendLog("Recording finished: " + outPath, "success");
                    } else if (returnCode == 255 || returnCode == -1) {
                        sendLog("Recording stopped.", "warning");
                    } else {
                        sendLog("FFmpeg exited with code: " + returnCode, "error");
                    }
                    recording = false;
                    updateNotification("Monitoring: " + shortUrl(channelUrl));
                },
                msg -> {
                    if (!msg.startsWith("frame=") && !msg.startsWith("size=")) {
                        sendLog(msg, "dim");
                    }
                }
            );

        } catch (Exception e) {
            sendLog("startRecording error: " + e.getMessage(), "error");
            Log.e(TAG, "startRecording", e);
            recording = false;
        }
    }

    private void stopRecording() {
        try {
            FFmpegRunner.cancel();
        } catch (Exception e) {
            sendLog("stopRecording error: " + e.getMessage(), "error");
        } finally {
            recording = false;
        }
    }

    // ── YouTube Data API v3 ───────────────────────────────────────────────────

    private String resolveChannelId(String url) {
        sendLog("Resolving URL: " + url, "info");
        try {
            if (url.contains("/channel/")) {
                return url.substring(url.indexOf("/channel/") + 9)
                          .replaceAll("[/?#].*", "");
            }

            String handle = null;
            if (url.contains("/@")) {
                handle = url.substring(url.indexOf("/@") + 2).replaceAll("[/?#].*", "");
            } else if (url.contains("/c/") || url.contains("/user/")) {
                handle = url.substring(url.lastIndexOf("/") + 1).replaceAll("[/?#].*", "");
            }

            if (handle == null) {
                sendLog("Could not extract handle from URL", "error");
                return null;
            }

            sendLog("Extracted handle: " + handle, "info");

            String apiUrl = "https://www.googleapis.com/youtube/v3/channels"
                          + "?part=id&forHandle=" + handle + "&key=" + YT_API_KEY;
            String resp = httpGet(apiUrl);
            if (resp == null) {
                sendLog("YouTube API returned null response", "error");
                return null;
            }

            JSONObject json  = new JSONObject(resp);
            JSONArray  items = json.optJSONArray("items");
            if (items != null && items.length() > 0) {
                String channelId = items.getJSONObject(0).getString("id");
                sendLog("Channel ID resolved: " + channelId, "info");
                return channelId;
            } else {
                sendLog("No channel found for handle: " + handle, "error");
                sendLog("API response: " + resp, "error");
            }
        } catch (Exception e) {
            sendLog("resolveChannelId error: " + e.getMessage(), "error");
        }
        return null;
    }

    private String[] checkLive(String channelId) {
        try {
            String apiUrl = "https://www.googleapis.com/youtube/v3/search"
                          + "?part=snippet&channelId=" + channelId
                          + "&eventType=live&type=video&maxResults=1&key=" + YT_API_KEY;
            String resp = httpGet(apiUrl);
            if (resp == null) return null;

            JSONObject json  = new JSONObject(resp);
            JSONArray  items = json.optJSONArray("items");
            if (items != null && items.length() > 0) {
                JSONObject item    = items.getJSONObject(0);
                String     videoId = item.getJSONObject("id").getString("videoId");
                String     title   = item.getJSONObject("snippet").getString("title");
                return new String[]{videoId, title};
            }
        } catch (Exception e) {
            sendLog("checkLive error: " + e.getMessage(), "error");
        }
        return null;
    }

    private String httpGet(String urlString) {
        try {
            URL               url  = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(15_000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) {
                sendLog("HTTP error: " + conn.getResponseCode() + " for " + urlString, "error");
                return null;
            }

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ── Wake lock ─────────────────────────────────────────────────────────────

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LiveMonitor::WakeLock");
        wakeLock.acquire(12 * 60 * 60 * 1000L);
        sendLog("Wake lock acquired.", "success");
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private void createNotificationChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);

        NotificationChannel monitor = new NotificationChannel(
            CHANNEL_ID, "Monitor Status", NotificationManager.IMPORTANCE_LOW);
        monitor.setDescription("Ongoing monitoring status");
        nm.createNotificationChannel(monitor);

        NotificationChannel live = new NotificationChannel(
            CHANNEL_LIVE_ID, "Live Detected", NotificationManager.IMPORTANCE_HIGH);
        live.setDescription("Alerts when a stream goes live");
        nm.createNotificationChannel(live);
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Live Monitor")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }
 
    private void updateNotification(String text) {
        getSystemService(NotificationManager.class)
            .notify(NOTIF_ID, buildNotification(text));
    }
 
    private void sendLiveNotification(String title, String videoId) {
        Intent openIntent = new Intent(Intent.ACTION_VIEW,
            Uri.parse("https://youtube.com/watch?v=" + videoId));
        PendingIntent pi = PendingIntent.getActivity(
            this, 2, openIntent, PendingIntent.FLAG_IMMUTABLE);
 
        Notification notif = new NotificationCompat.Builder(this, CHANNEL_LIVE_ID)
            .setContentTitle("Stream is LIVE!")
            .setContentText(title)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(new long[]{0, 400, 200, 400, 200, 400})
            .build();
 
        getSystemService(NotificationManager.class).notify(2, notif);
    }
 
    // ── Helpers ───────────────────────────────────────────────────────────────
 
    private void sendLog(String message, String type) {
        Log.d(TAG, "[" + type + "] " + message);
        Intent intent = new Intent("MONITOR_LOG");
        intent.putExtra("message", message);
        intent.putExtra("type", type);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
 
    private String shortUrl(String url) {
        return url.replace("https://www.youtube.com/", "")
                  .replace("https://youtube.com/", "");
    }
 
    private void sleep(int seconds) {
        try { Thread.sleep(seconds * 1000L); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
 
    private void stopAll() {
        running = false;
        recording = false;
        stopRecording();
        releaseWakeLock();
        stopForeground(true);
        stopSelf();
    }
 
    @Override public IBinder onBind(Intent intent) { return null; }
 
    @Override
    public void onDestroy() {
        stopAll();
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }
}
