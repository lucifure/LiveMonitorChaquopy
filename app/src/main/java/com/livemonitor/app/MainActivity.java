package com.livemonitor.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.method.ScrollingMovementMethod;
import android.widget.ScrollView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.livemonitor.app.databinding.ActivityMainBinding;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private BroadcastReceiver logReceiver;
    private SpannableStringBuilder logBuilder = new SpannableStringBuilder();
    private int logLineCount = 0;
    private static final int MAX_LOG_LINES = 200;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.logText.setMovementMethod(new ScrollingMovementMethod());
        binding.logText.setTextIsSelectable(true);
        binding.btnStop.setEnabled(false);

        requestPermissions();

        binding.btnStart.setOnClickListener(v -> {
            String url = binding.urlInput.getText().toString()
                .trim()
                .replaceAll("\\s+", "")
                .replaceAll("/+$", "");
            if (url.isEmpty()) {
                Toast.makeText(this, "Please enter a YouTube URL", Toast.LENGTH_SHORT).show();
                return;
            }
            startMonitoring(url);
        });

        binding.btnStop.setOnClickListener(v -> stopMonitoring());

        binding.btnClearLog.setOnClickListener(v -> {
            logBuilder = new SpannableStringBuilder();
            logLineCount = 0;
            binding.logText.setText("");
            appendLog("Log cleared.", "dim");
        });

        logReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String msg  = intent.getStringExtra("message");
                String type = intent.getStringExtra("type");
                if (msg != null) appendLog(msg, type);
            }
        };
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(logReceiver, new IntentFilter("MONITOR_LOG"));

        appendLog("━━━━━━━━━━━━━━━━━━━━━━━━━━", "divider");
        appendLog("  Live Monitor — Ready", "header");
        appendLog("━━━━━━━━━━━━━━━━━━━━━━━━━━", "divider");
        appendLog("Paste a YouTube channel URL and tap Start.", "info");
    }

    private void startMonitoring(String url) {
        binding.btnStart.setEnabled(false);
        binding.btnStop.setEnabled(true);
        binding.urlInput.setEnabled(false);
        binding.statusText.setText("● Monitoring");
        binding.statusText.setTextColor(getColor(R.color.green));

        Intent intent = new Intent(this, MonitorService.class);
        intent.setAction("START");
        intent.putExtra("url", url);
        startForegroundService(intent);

        appendLog("━━━━━━━━━━━━━━━━━━━━━━━━━━", "divider");
        appendLog("SESSION STARTED", "header");
        appendLog("━━━━━━━━━━━━━━━━━━━━━━━━━━", "divider");
        appendLog("URL: " + url, "info");
    }

    private void stopMonitoring() {
        binding.btnStart.setEnabled(true);
        binding.btnStop.setEnabled(false);
        binding.urlInput.setEnabled(true);
        binding.statusText.setText("○ Stopped");
        binding.statusText.setTextColor(getColor(R.color.text_dim));

        Intent intent = new Intent(this, MonitorService.class);
        intent.setAction("STOP");
        startService(intent);

        appendLog("━━━━━━━━━━━━━━━━━━━━━━━━━━", "divider");
        appendLog("SESSION STOPPED BY USER", "warning");
        appendLog("━━━━━━━━━━━━━━━━━━━━━━━━━━", "divider");
    }

    private void appendLog(String message, String type) {
        runOnUiThread(() -> {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());

            // Trim log if too long
            if (logLineCount > MAX_LOG_LINES) {
                logBuilder = new SpannableStringBuilder();
                logLineCount = 0;
                appendLog("[ Log trimmed — too many lines ]", "dim");
                return;
            }

            String prefix;
            int prefixColor;
            int msgColor;

            switch (type) {
                case "header":
                    prefix      = "  ";
                    prefixColor = Color.parseColor("#00A884");
                    msgColor    = Color.parseColor("#00A884");
                    break;
                case "divider":
                    prefix      = "";
                    prefixColor = Color.parseColor("#3D5263");
                    msgColor    = Color.parseColor("#3D5263");
                    break;
                case "success":
                    prefix      = "[" + time + "] ✔ OK   » ";
                    prefixColor = Color.parseColor("#00A884");
                    msgColor    = Color.parseColor("#00C49A");
                    break;
                case "live":
                    prefix      = "[" + time + "] 🔴 LIVE » ";
                    prefixColor = Color.parseColor("#FF5252");
                    msgColor    = Color.parseColor("#FF5252");
                    break;
                case "error":
                    prefix      = "[" + time + "] ✖ ERR  » ";
                    prefixColor = Color.parseColor("#FF5252");
                    msgColor    = Color.parseColor("#FF6E6E");
                    break;
                case "warning":
                    prefix      = "[" + time + "] ⚠ WARN » ";
                    prefixColor = Color.parseColor("#FFB300");
                    msgColor    = Color.parseColor("#FFD54F");
                    break;
                case "download":
                    prefix      = "[" + time + "] ↓ REC  » ";
                    prefixColor = Color.parseColor("#7C4DFF");
                    msgColor    = Color.parseColor("#B39DDB");
                    break;
                case "info":
                    prefix      = "[" + time + "] ℹ INFO » ";
                    prefixColor = Color.parseColor("#29B6F6");
                    msgColor    = Color.parseColor("#81D4FA");
                    break;
                case "dim":
                default:
                    prefix      = "[" + time + "] ·      » ";
                    prefixColor = Color.parseColor("#3D5263");
                    msgColor    = Color.parseColor("#667781");
                    break;
            }

            // Add newline if not first line
            if (logBuilder.length() > 0) {
                logBuilder.append("\n");
            }

            // Append colored prefix
            int start = logBuilder.length();
            logBuilder.append(prefix);
            logBuilder.setSpan(
                new ForegroundColorSpan(prefixColor),
                start,
                logBuilder.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            );

            // Append colored message
            start = logBuilder.length();
            logBuilder.append(message);
            logBuilder.setSpan(
                new ForegroundColorSpan(msgColor),
                start,
                logBuilder.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            );

            logLineCount++;
            binding.logText.setText(logBuilder);

            // Auto scroll to bottom
            binding.logText.post(() -> {
                ScrollView scrollView = findViewById(R.id.logScrollView);
                if (scrollView != null) {
                    scrollView.fullScroll(ScrollView.FOCUS_DOWN);
                }
            });
        });
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(logReceiver);
    }
}
