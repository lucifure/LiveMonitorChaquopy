package com.livemonitor.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class KeepAliveReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Intent serviceIntent = new Intent(context, MonitorService.class);
        serviceIntent.setAction(MonitorService.ACTION_KEEP_ALIVE_PING);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }

        ExecutorService pingExecutor = Executors.newSingleThreadExecutor();
        pingExecutor.execute(() -> {
            HttpURLConnection conn = null;

            try {
                URL url = new URL("https://www.youtube.com/generate_204");
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5_000);
                conn.setReadTimeout(5_000);
                conn.connect();
            } catch (Exception ignored) {
                // Ping failure is expected during an outage.
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
                pingExecutor.shutdown();
            }
        });
    }
}
