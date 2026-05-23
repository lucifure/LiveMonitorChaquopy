package com.livemonitor.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.livemonitor.app.databinding.ActivityMainBinding;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private BroadcastReceiver logReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.logText.setMovementMethod(new ScrollingMovementMethod());

        requestPermissions();

        binding.btnStart.setOnClickListener(v -> {
            String url = binding.urlInput.getText().toString().trim();
            if (url.isEmpty()) {
                Toast.makeText(this, "Please enter a YouTube URL", Toast.LENGTH_SHORT).show();
                return;
            }
            startMonitoring(url);
        });

        binding.btnStop.setOnClickListener(v -> stopMonitoring());

        logReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String msg  = intent.getStringExtra("message");
                String type = intent.getStringExtra("type");
                if (msg != null) appendLog(msg, type);
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(
                logReceiver, new IntentFilter("MONITOR_LOG"));

        appendLog("Initialising Python / yt-dlp...", "info");
        new Thread(() -> {
            try {
                Python py = Python.getInstance();
                String ver = py.getModule("recorder")
                               .callAttr("get_yt_dlp_version")
                               .toString();
                appendLog("yt-dlp " + ver + " ready via Chaquopy ✓", "success");
                appendLog("Paste a YouTube channel URL and tap Start.", "info");
            } catch (Exception e) {
                appendLog("Python init error: " + e.getMessage(), "error");
            }
        }).start();
    }

    private void startMonitoring(String url) {
        binding.btnStart.setEnabled(false);
        binding.btnStop.setEnabled(true);
        binding.urlInput.setEnabled(false);
        binding.statusText.setText("Monitoring...");
        binding.statusText.setTextColor(getColor(R.color.green));

        Intent intent = new Intent(this, MonitorService.class);
        intent.setAction("START");
        intent.putExtra("url", url);
        startForegroundService(intent);

        appendLog("Started monitoring: " + url, "success");
    }

    private void stopMonitoring() {
        binding.btnStart.setEnabled(true);
        binding.btnStop.setEnabled(false);
        binding.urlInput.setEnabled(true);
        binding.statusText.setText("Stopped");
        binding.statusText.setTextColor(getColor(R.color.text_dim));

        Intent intent = new Intent(this, MonitorService.class);
        intent.setAction("STOP");
        startService(intent);

        appendLog("Monitoring stopped.", "info");
    }

    private void appendLog(String message, String type) {
        runOnUiThread(() -> {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            String icon = iconFor(type);
            String current = binding.logText.getText().toString();
            String newText = current + "\n[" + time + "] " + icon + " " + message;

            String[] lines = newText.split("\n");
            if (lines.length > 150) {
                StringBuilder sb = new StringBuilder();
                for (int i = lines.length - 150; i < lines.length; i++) {
                    sb.append(lines[i]).append("\n");
                }
                newText = sb.toString();
            }
            binding.logText.setText(newText.trim());

            if (binding.logText.getLayout() != null) {
                int scrollAmount = binding.logText.getLayout()
                        .getLineTop(binding.logText.getLineCount())
                        - binding.logText.getHeight();
                if (scrollAmount > 0) binding.logText.scrollTo(0, scrollAmount);
            }
        });
    }

    private String iconFor(String type) {
        if (type == null) return "-";
        switch (type) {
            case "success":  return "✓";
            case "error":    return "✗";
            case "warning":  return "!";
            case "live":     return "●";
            case "download": return "↓";
            default:         return "-";
        }
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(logReceiver);
    }
                                    }
