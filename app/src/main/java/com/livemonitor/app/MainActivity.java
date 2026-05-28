package com.livemonitor.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.logText.setMovementMethod(new ScrollingMovementMethod());
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

        appendLog("Ready. Paste a YouTube channel URL and tap Start.", "info");
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
            String time    = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            String current = binding.logText.getText().toString();
            String newText = current.isEmpty()
                ? "[" + time + "] " + message
                : current + "\n[" + time + "] " + message;
            binding.logText.setText(newText);
            if (binding.logText.getLayout() != null) {
                int scroll = binding.logText.getLayout()
                    .getLineTop(binding.logText.getLineCount()) - binding.logText.getHeight();
                if (scroll > 0) binding.logText.scrollTo(0, scroll);
            }
        });
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
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
