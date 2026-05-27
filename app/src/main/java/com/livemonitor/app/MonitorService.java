package com.livemonitor.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import org.json.JSONArray;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor;
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
    private FFmpegSession ffmpegSession = null;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newCachedThreadPool();
        createNotificationChannels();

        // Initialize NewPipeExtractor
        NewPipe.init(org.schabi.newpipe.extractor.downloader.RecorderDownloader.getInstance());
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
                        sendLog("Re-checking stream...", "dim");
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

    // ── Recording with NewPipeExtractor + FFmpegKit ───────────────────────────

    private void startRecording(String watchUrl, String title) {
        try {
            sendLog("Extracting stream URL via NewPipe...", "info");

            // Get stream info using NewPipeExtractor
            StreamInfo streamInfo = StreamInfo.getInfo(
                    ServiceList.YouTube, watchUrl);

            // Get HLS manifest URL
            String manifestUrl = streamInfo.getHlsUrl();

            if (manifestUrl == null || manifestUrl.isEmpty()) {
                // Try video streams
                List<VideoStream> videoStreams = streamInfo.getVideoStreams();
                if (!videoStreams.isEmpty()) {
                    manifestUrl = videoStreams.get(0).getUrl();
                }
            }

            if (manifestUrl == null || manifestUrl.isEmpty()) {
                sendLog("Could not extract stream URL!", "error");
                recording = false;
                return;
            }

            sendLog("Stream URL extracted!", "success");
            sendLog("Starting FFmpegKit recording...", "info");

            String date = new SimpleDateFormat("yyyyMMdd_HHmm",
                    Locale.getDefault()).format(new Date());
            String safe = title.replaceAll("[^a-zA-Z0-9._-]", "_");
            safe = safe.substring(0, Math.min(safe.length(), 40));
            String outPath = "/storage/emulated/0/Download/YouTubeMonitor/"
                    + safe + "_" + date + ".mp4";

            new File("/storage/emulated/0/Download/YouTubeMonitor/").mkdirs();

            String cmd = "-i " + manifestUrl
                    + " -c copy"
                    + " -bsf:a aac_adtstoasc"
                    + " " + outPath;

            sendLog("Recording to: " + outPath, "info");

            ffmpegSession = FFmpegKit.executeAsync(cmd,
                session -> {
                    if (ReturnCode.isSuccess(session.getReturnCode())) {
                        sendLog("Recording finished successfully!", "success");
                    } else if (ReturnCode.isCancel(session.getReturnCode())) {
                        sendLog("Recording cancelled.", "warning");
                    } else {
                        sendLog("Recording failed: " + session.getFailStackTrace(), "error");
                    }
                    recording = false;
                    updateNotification("Monitoring: " + shortUrl(channelUrl));
                },
                log -> sendLog(log.getMessage(), "info"),
                stats -> {
                    long size = stats.getSize();
                    double mb = size / (1024.0 * 1024.0);
                    sendLog("Recording: " + String.format("%.1f", mb) + " MB", "download");
                }
            );

        } catch (Exception e) {
            sendLog("startRecording error: " + e.getMessage(), "error");
            recording = false;
        }
    }

    private void stopRecording() {
        try {
            if (ffmpegSession != null) {
                FFmpegKit.cancel(ffmpegSession.getSessionId());
                ffmpegSession = null;
            }
            recording = false;
        } catch (Exception e) {
            sendLog("stopRecording error: " + e.getMessage(), "error");
        }
    }

    // ── YouTube API ───────────────────────────────────────────────────────────

    private String resolveChannelId(String url) {
        try {
            if (url.contains("/channel/")) {
                return url.substring(url.indexOf("/channel/") + 9).replaceAll("[/?#].*", "");
            }
            String handle = null;
            if (url.contains("/@")) {
                handle = url.substring(url.indexOf("/@") + 2).replaceAll("[/?#].*", "");
            } else if (url.contains("/c/") || url.contains("/user/")) {
                handle = url.substring(url.lastIndexOf("/") + 1).replaceAll("[/?#].*", "");
            }
            if (handle == null) return null;

            String apiUrl = "https://www.googleapis.com/youtube/v3/channels"
                    + "?part=id&forHandle=" + handle + "&key=" + YT_API_KEY;
            String resp = httpGet(apiUrl);
            if (resp == null) return null;

            JSONObject json = new JSONObject(resp);
            JSONArray items = json.optJSONArray("items");
            if (items != null && items.length() > 0) {
                return items.getJSONObject(0).getString("id");
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

            JSONObject json = new JSONObject(resp);
            JSONArray items = json.optJSONArray("items");
            if (items != null && items.length() > 0) {
                JSONObject item = items.getJSONObject(0);
                String videoId = item.getJSONObject("id").getString("videoId");
                String title   = item.getJSONObject("snippet").getString("title");
                return new String[]{videoId, title};
            }
        } catch (Exception e) {
            sendLog("checkLive error: " + e.getMessage(), "error");
        }
        return null;
    }

    private String httpGet(String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) return null;
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
        sendLog("CPU wake lock acquired.", "success");
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
        monitor.setDescription("Monitoring status");
        nm.createNotificationChannel(monitor);
        NotificationChannel live = new NotificationChannel(
                CHANNEL_LIVE_ID, "Live Detected", NotificationManager.IMPORTANCE_HIGH);
        live.setDescription("Alerts when stream goes live");
        nm.createNotificationChannel(live);
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Live Monitor")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification(text));
    }

    private void sendLiveNotification(String title, String videoId) {
        Intent openIntent = new Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://youtube.com/watch?v=" + videoId));
        PendingIntent pi = PendingIntent.getActivity(this, 2, openIntent,
                PendingIntent.FLAG_IMMUTABLE);
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
        try { Thread.sleep(seconds * 1000L); } catch (InterruptedException ignored) {}
    }

    private void stopAll() {
        running = false;
        recording = false;
        stopRecording();
        releaseWakeLock();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        stopAll();
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }
}
