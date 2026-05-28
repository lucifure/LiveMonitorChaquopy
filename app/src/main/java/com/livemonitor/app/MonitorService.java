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
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
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
        NewPipe.init(NewPipeDownloader.getInstance());

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
                        String watchUrl = "https://www.youtube.com/watch?v=" + videoId;
                        executor.execute(() -> startRecording(watchUrl, title));
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

    // ── Recording ─────────────────────────────────────────────────────────────

    private void startRecording(String watchUrl, String title) {
        try {
            sendLog("Extracting stream URL via NewPipeExtractor...", "info");

            // Retry up to 3 times — YouTube sometimes needs a retry
            StreamInfo streamInfo = null;
            Exception lastError = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    streamInfo = StreamInfo.getInfo(ServiceList.YouTube, watchUrl);
                    break;
                } catch (Exception e) {
                    lastError = e;
                    sendLog("Attempt " + attempt + " failed: " + e.getMessage() + " — retrying...", "warning");
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                }
            }

            if (streamInfo == null) {
                sendLog("All attempts failed: " + (lastError != null ? lastError.getMessage() : "unknown"), "error");
                recording = false;
                return;
            }

            String manifestUrl = streamInfo.getHlsUrl();

            if (isBlank(manifestUrl)) {
                manifestUrl = streamInfo.getDashMpdUrl();
            }

            if (isBlank(manifestUrl)) {
                List<VideoStream> streams = streamInfo.getVideoStreams();
                if (!streams.isEmpty()) {
                    manifestUrl = streams.get(0).getContent();
                }
            }

            if (isBlank(manifestUrl)) {
                sendLog("Could not extract any stream URL!", "error");
                recording = false;
                return;
            }

            sendLog("Stream URL obtained. Starting FFmpeg...", "success");

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

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
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
