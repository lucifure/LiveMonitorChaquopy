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
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MonitorService extends Service {

    private static final String CHANNEL_ID      = "MonitorChannel";
    private static final String CHANNEL_LIVE_ID = "LiveChannel";
    private static final int    NOTIF_ID        = 1;
    private static final int    POLL_SECONDS    = 60;
    private static final String YT_API_KEY      = "AIzaSyDnAsBrxe_aFkUSpqkrFDczUw-PpLoEhuY";
    private static final String OUTPUT_DIR      = "/storage/emulated/0/Download/YouTubeMonitor";

    private PowerManager.WakeLock wakeLock;
    private ExecutorService executor;
    private volatile boolean running   = false;
    private volatile boolean recording = false;
    private String channelUrl = "";
    private PyObject recorder;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newCachedThreadPool();
        createNotificationChannels();
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }
        recorder = Python.getInstance().getModule("recorder");
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
            recorder.callAttr("set_log_callback", (PyObject.Callable) args -> {
                sendLog(args[0].toString(), "info");
                return null;
            });
            recorder.callAttr("reset");
            executor.execute(this::monitorLoop);
        } else if ("STOP".equals(action)) {
            stopAll();
        }
        return START_NOT_STICKY;
    }

    private void monitorLoop() {
        sendLog("Monitor started. Wake lock ON.", "success");
        sendLog("Checking: " + channelUrl, "info");
        while (running) {
            sendLog("Checking live status...", "dim");
            try {
                String channelId = getChannelId(channelUrl);
                if (channelId == null) {
                    sendLog("Could not resolve channel. Retrying in 60s...", "error");
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
                        String watchUrl = "https://youtube.com/watch?v=" + videoId;
                        executor.execute(() -> startRecording(watchUrl));
                    }
                    while (running && recording) {
                        sleep(POLL_SECONDS);
                        if (!running) break;
                        sendLog("Re-checking stream...", "dim");
                        if (checkLive(channelId) == null) {
                            sendLog("Stream ended. Stopping recording...", "warning");
                            recorder.callAttr("stop");
                            recording = false;
                            updateNotification("Monitoring: " + shortUrl(channelUrl));
                            sendLog("Resuming monitor...", "info");
                            recorder.callAttr("reset");
                            break;
                        }
                    }
                } else {
                    sendLog("Not live. Next check in " + POLL_SECONDS + "s...", "dim");
                    sleep(POLL_SECONDS);
                }
            } catch (Exception e) {
                sendLog("Error: " + e.getMessage(), "error");
                sleep(POLL_SECONDS);
            }
        }
        sendLog("Monitor stopped.", "info");
    }

    private void startRecording(String watchUrl) {
        try {
            sendLog("Starting yt-dlp recording...", "info");
            recorder.callAttr("record", watchUrl, OUTPUT_DIR);
        } catch (Exception e) {
            if (running) sendLog("Recording error: " + e.getMessage(), "error");
        }
        recording = false;
    }

    private String getChannelId(String url) {
        try {
            String handle = null;
            if (url.contains("/@")) {
                handle = url.substring(url.indexOf("/@") + 2).replaceAll("[/?#].*", "");
            } else if (url.contains("/channel/")) {
                return url.substring(url.indexOf("/channel/") + 9).replaceAll("[/?#].*", "");
            }
            if (handle == null) return null;
            String response = httpGet("https://www.googleapis.com/youtube/v3/channels?part=id&forHandle=" + handle + "&key=" + YT_API_KEY);
            if (response == null) return null;
            org.json.JSONArray items = new org.json.JSONObject(response).optJSONArray("items");
            if (items != null && items.length() > 0) return items.getJSONObject(0).getString("id");
        } catch (Exception e) { sendLog("getChannelId error: " + e.getMessage(), "error"); }
        return null;
    }

    private String[] checkLive(String channelId) {
        try {
            String response = httpGet("https://www.googleapis.com/youtube/v3/search?part=snippet&channelId=" + channelId + "&eventType=live&type=video&maxResults=1&key=" + YT_API_KEY);
            if (response == null) return null;
            org.json.JSONArray items = new org.json.JSONObject(response).optJSONArray("items");
            if (items != null && items.length() > 0) {
                org.json.JSONObject item = items.getJSONObject(0);
                return new String[]{item.getJSONObject("id").getString("videoId"), item.getJSONObject("snippet").getString("title")};
            }
        } catch (Exception e) { sendLog("checkLive error: " + e.getMessage(), "error"); }
        return null;
    }

    private String httpGet(String urlString) {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(urlString).openConnection();
            conn.setConnectTimeout(15000); conn.setReadTimeout(15000);
            if (conn.getResponseCode() != 200) return null;
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close(); return sb.toString();
        } catch (Exception e) { return null; }
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LiveMonitor::WakeLock");
        wakeLock.acquire();
        sendLog("CPU Wake lock acquired.", "success");
    }

    private void createNotificationChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Monitor Status", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_LIVE_ID, "Live Detected", NotificationManager.IMPORTANCE_HIGH));
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Live Monitor").setContentText(text)
            .setSmallIcon(R.drawable.ic_notification).setContentIntent(pi).setOngoing(true).build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification(text));
    }

    private void sendLiveNotification(String title, String videoId) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
            new Intent(Intent.ACTION_VIEW, Uri.parse("https://youtube.com/watch?v=" + videoId)), PendingIntent.FLAG_IMMUTABLE);
        getSystemService(NotificationManager.class).notify(2,
            new NotificationCompat.Builder(this, CHANNEL_LIVE_ID)
                .setContentTitle("Stream is LIVE!").setContentText(title)
                .setSmallIcon(R.drawable.ic_notification).setContentIntent(pi)
                .setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVibrate(new long[]{0,500,200,500,200,500}).build());
    }

    private void sendLog(String msg, String type) {
        Intent i = new Intent("MONITOR_LOG");
        i.putExtra("message", msg); i.putExtra("type", type);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    private String shortUrl(String url) {
        return url.replace("https://www.youtube.com/","").replace("https://youtube.com/","");
    }

    private void sleep(int s) { try { Thread.sleep(s*1000L); } catch (InterruptedException ignored) {} }

    private void stopAll() {
        running = false; recording = false;
        if (recorder != null) try { recorder.callAttr("stop"); } catch (Exception ignored) {}
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(true); stopSelf();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { stopAll(); if (executor != null) executor.shutdownNow(); super.onDestroy(); }
            }
