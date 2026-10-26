package com.livemonitor.app;

import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings screen for choosing which log categories are saved and shown.
 */
public class LogSettingsActivity extends AppCompatActivity {
    private final ExecutorService historyExecutor = Executors.newSingleThreadExecutor();

    private AppStorage storage;
    private AppSettings settings;

    private CheckBox uiLogsCheckBox;
    private CheckBox serviceLogsCheckBox;
    private CheckBox recorderLogsCheckBox;
    private CheckBox ffmpegLogsCheckBox;
    private CheckBox networkLogsCheckBox;
    private CheckBox remoteConfigLogsCheckBox;
    private CheckBox bootLogsCheckBox;
    private CheckBox appLogsCheckBox;
    private CheckBox debugLogsCheckBox;

    private boolean bindingValues;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        storage = new AppStorage(this);
        settings = storage.loadSettings();

        setTitle("Log Settings");
        setContentView(buildContentView());
        bindSettingsToViews();
    }

    private ScrollView buildContentView() {
        ScrollView scrollView = new ScrollView(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        scrollView.addView(root);

        TextView title = new TextView(this);
        title.setText("Log Settings");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        TextView help = new TextView(this);
        help.setText(
            "Turn on only the log types you need. Disabled categories are not saved, "
                + "so keep Recorder logs on when debugging manifest or recording problems."
        );
        help.setTextSize(14);
        help.setPadding(0, 0, 0, dp(12));
        root.addView(help);

        uiLogsCheckBox = addCheckBox(
            root,
            "UI logs",
            "Button taps, channel add/delete actions, and other screen events."
        );
        serviceLogsCheckBox = addCheckBox(
            root,
            "Service logs",
            "Monitoring service start/stop and retry state."
        );
        recorderLogsCheckBox = addCheckBox(
            root,
            "Recorder logs",
            "Live detection, HLS manifest attempts, and recording status."
        );
        ffmpegLogsCheckBox = addCheckBox(
            root,
            "FFmpeg / HLS proxy logs",
            "FFmpeg and local proxy details. Keep off unless recording chunks fail."
        );
        networkLogsCheckBox = addCheckBox(
            root,
            "Network logs",
            "Connectivity changes and network diagnostics."
        );
        remoteConfigLogsCheckBox = addCheckBox(
            root,
            "Remote config logs",
            "Remote config fetch, cache, and validation messages."
        );
        bootLogsCheckBox = addCheckBox(
            root,
            "Boot logs",
            "Auto-restore events after phone reboot or app update."
        );
        appLogsCheckBox = addCheckBox(
            root,
            "App logs",
            "General app-level messages."
        );
        debugLogsCheckBox = addCheckBox(
            root,
            "Verbose / debug logging",
            "Re-enable high-frequency diagnostics and repeated live-fallback confirmations. Leave off for normal phone use."
        );

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.END);
        buttonRow.setPadding(0, dp(12), 0, 0);

        Button enableAllButton = new Button(this);
        enableAllButton.setAllCaps(false);
        enableAllButton.setText("Turn All On");
        enableAllButton.setOnClickListener(v -> setAllLogsEnabled(true));

        Button disableAllButton = new Button(this);
        disableAllButton.setAllCaps(false);
        disableAllButton.setText("Turn All Off");
        disableAllButton.setOnClickListener(v -> setAllLogsEnabled(false));

        buttonRow.addView(enableAllButton);

        LinearLayout.LayoutParams disableParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        disableParams.leftMargin = dp(8);
        buttonRow.addView(disableAllButton, disableParams);

        root.addView(buttonRow);

        Button saveHistoryButton = new Button(this);
        saveHistoryButton.setAllCaps(false);
        saveHistoryButton.setText("Save full log history (.txt)");
        saveHistoryButton.setOnClickListener(v -> saveFullLogHistory());
        LinearLayout.LayoutParams historyParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        historyParams.topMargin = dp(16);
        root.addView(saveHistoryButton, historyParams);

        Button deleteHistoryButton = new Button(this);
        deleteHistoryButton.setAllCaps(false);
        deleteHistoryButton.setText("Delete log history files");
        deleteHistoryButton.setOnClickListener(v -> confirmDeleteLogHistory());
        root.addView(deleteHistoryButton, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        return scrollView;
    }

    private void saveFullLogHistory() {
        historyExecutor.execute(() -> {
            try {
                List<File> files = LogHistoryWriter.listHistoryFiles(this);
                if (files.isEmpty()) {
                    runOnUiThread(() -> Toast.makeText(this, "No log history yet.", Toast.LENGTH_SHORT).show());
                    return;
                }
                String name = LogFileSaver.saveFiles(
                    this, storage, files, "LiveMonitor_FullLog_"
                );
                runOnUiThread(() -> Toast.makeText(
                    this, "Log history saved: " + name, Toast.LENGTH_LONG
                ).show());
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(
                    this, "Could not save log history.", Toast.LENGTH_SHORT
                ).show());
            }
        });
    }

    private void confirmDeleteLogHistory() {
        new AlertDialog.Builder(this)
            .setTitle("Delete log history files?")
            .setMessage("This permanently deletes the daily app log text files. The in-app log is unchanged.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", (dialog, which) -> deleteLogHistory())
            .show();
    }

    private void deleteLogHistory() {
        historyExecutor.execute(() -> {
            List<File> files = LogHistoryWriter.listHistoryFiles(this);
            int deleted = LogHistoryWriter.deleteAll(this);
            String message = files.isEmpty()
                ? "No log history files to delete."
                : deleted == files.size()
                    ? "Log history files deleted."
                    : "Deleted " + deleted + " of " + files.size() + " log history files.";
            runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
        });
    }

    @Override
    protected void onDestroy() {
        historyExecutor.shutdown();
        super.onDestroy();
    }

    private CheckBox addCheckBox(LinearLayout root, String title, String summary) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(title + "\n" + summary);
        checkBox.setTextSize(15);
        checkBox.setPadding(0, dp(6), 0, dp(6));
        checkBox.setOnCheckedChangeListener(this::onLogSettingChanged);
        root.addView(
            checkBox,
            new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        );

        return checkBox;
    }

    private void bindSettingsToViews() {
        bindingValues = true;

        uiLogsCheckBox.setChecked(settings.isLogUiEnabled());
        serviceLogsCheckBox.setChecked(settings.isLogServiceEnabled());
        recorderLogsCheckBox.setChecked(settings.isLogRecorderEnabled());
        ffmpegLogsCheckBox.setChecked(settings.isLogFfmpegEnabled());
        networkLogsCheckBox.setChecked(settings.isLogNetworkEnabled());
        remoteConfigLogsCheckBox.setChecked(settings.isLogRemoteConfigEnabled());
        bootLogsCheckBox.setChecked(settings.isLogBootEnabled());
        appLogsCheckBox.setChecked(settings.isLogAppEnabled());
        debugLogsCheckBox.setChecked(settings.isLogDebugEnabled());

        bindingValues = false;
    }

    private void onLogSettingChanged(CompoundButton buttonView, boolean isChecked) {
        if (bindingValues) {
            return;
        }

        saveSettingsFromViews();
        Toast.makeText(this, "Log settings saved.", Toast.LENGTH_SHORT).show();
    }

    private void setAllLogsEnabled(boolean enabled) {
        bindingValues = true;

        uiLogsCheckBox.setChecked(enabled);
        serviceLogsCheckBox.setChecked(enabled);
        recorderLogsCheckBox.setChecked(enabled);
        ffmpegLogsCheckBox.setChecked(enabled);
        networkLogsCheckBox.setChecked(enabled);
        remoteConfigLogsCheckBox.setChecked(enabled);
        bootLogsCheckBox.setChecked(enabled);
        appLogsCheckBox.setChecked(enabled);
        debugLogsCheckBox.setChecked(enabled);

        bindingValues = false;

        saveSettingsFromViews();
        Toast.makeText(
            this,
            enabled ? "All log categories enabled." : "All log categories disabled.",
            Toast.LENGTH_SHORT
        ).show();
    }

    private void saveSettingsFromViews() {
        settings.setLogUiEnabled(uiLogsCheckBox.isChecked());
        settings.setLogServiceEnabled(serviceLogsCheckBox.isChecked());
        settings.setLogRecorderEnabled(recorderLogsCheckBox.isChecked());
        settings.setLogFfmpegEnabled(ffmpegLogsCheckBox.isChecked());
        settings.setLogNetworkEnabled(networkLogsCheckBox.isChecked());
        settings.setLogRemoteConfigEnabled(remoteConfigLogsCheckBox.isChecked());
        settings.setLogBootEnabled(bootLogsCheckBox.isChecked());
        settings.setLogAppEnabled(appLogsCheckBox.isChecked());
        settings.setLogDebugEnabled(debugLogsCheckBox.isChecked());
        storage.saveSettings(settings);
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
