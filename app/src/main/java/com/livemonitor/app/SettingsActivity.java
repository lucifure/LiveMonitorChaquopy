package com.livemonitor.app;

import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Settings screen.
 *
 * Includes:
 * - poll interval
 * - download quality
 * - save location
 * - scheduled monitoring
 * - remote config URL
 * - boot restore
 * - battery optimization helper
 */
public class SettingsActivity extends AppCompatActivity {

    private AppStorage storage;
    private AppSettings settings;
    private StorageAccessHelper storageAccessHelper;

    private EditText pollIntervalInput;
    private Spinner qualitySpinner;
    private TextView saveLocationText;
    private CheckBox scheduledCheckBox;
    private EditText scheduleStartInput;
    private EditText scheduleEndInput;
    private CheckBox allowCurrentRecordingCheckBox;
    private CheckBox waitForVideoCheckBox;
    private CheckBox liveFromStartCheckBox;
    private CheckBox skipUnavailableFragmentsCheckBox;
    private EditText ytDlpCookieHeaderInput;
    private EditText ytDlpCookiesPathInput;
    private CheckBox convertTsToMp4CheckBox;
    private CheckBox restoreBootCheckBox;
    private CheckBox batteryOptimizationCheckBox;
    private CheckBox remoteConfigCheckBox;
    private EditText remoteConfigUrlInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        storage = new AppStorage(this);
        settings = storage.loadSettings();
        storageAccessHelper = new StorageAccessHelper(this, this::onSaveLocationSelected);

        setTitle("Settings");
        setContentView(buildContentView());

        bindSettingsToViews();
    }

    private LinearLayout buildContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.setBackgroundColor(Color.rgb(15, 15, 15));

        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(0, 0, 0, dp(10));
        root.addView(title);

        Button logSettingsButton = new Button(this);
        logSettingsButton.setAllCaps(false);
        logSettingsButton.setText("Log Settings");
        logSettingsButton.setOnClickListener(v -> startActivity(new Intent(this, LogSettingsActivity.class)));
        root.addView(logSettingsButton);

        pollIntervalInput = addEditText(root, "Poll interval seconds", "60");

        qualitySpinner = new Spinner(this);
        ArrayAdapter<String> qualityAdapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_item,
            new String[] {
                AppSettings.QUALITY_360P,
                AppSettings.QUALITY_480P,
                AppSettings.QUALITY_720P,
                AppSettings.QUALITY_1080P,
                AppSettings.QUALITY_BEST
            }
        );
        qualityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        qualitySpinner.setAdapter(qualityAdapter);
        addLabel(root, "Download quality");
        root.addView(qualitySpinner);

        addLabel(root, "Save location");
        saveLocationText = new TextView(this);
        saveLocationText.setTextSize(14);
        saveLocationText.setPadding(0, dp(4), 0, dp(4));
        root.addView(saveLocationText);

        Button chooseSaveLocationButton = new Button(this);
        chooseSaveLocationButton.setAllCaps(false);
        chooseSaveLocationButton.setText("Choose Save Folder");
        chooseSaveLocationButton.setOnClickListener(v -> storageAccessHelper.openFolderPicker());
        root.addView(chooseSaveLocationButton);

        Button openSaveLocationButton = new Button(this);
        openSaveLocationButton.setAllCaps(false);
        openSaveLocationButton.setText("Open folder");
        openSaveLocationButton.setOnClickListener(v -> openSelectedSaveFolder());
        root.addView(openSaveLocationButton);

        scheduledCheckBox = addCheckBox(root, "Enable scheduled monitoring");
        scheduleStartInput = addEditText(root, "Schedule start HH:mm", "00:00");
        scheduleEndInput = addEditText(root, "Schedule end HH:mm", "23:59");
        allowCurrentRecordingCheckBox = addCheckBox(
            root,
            "Allow current recording to finish outside schedule"
        );

        waitForVideoCheckBox = addCheckBox(root, "Wait for scheduled live video to start");
        liveFromStartCheckBox = addCheckBox(
            root,
            "Record from start when YouTube DVR is available"
        );
        skipUnavailableFragmentsCheckBox = addCheckBox(
            root,
            "Skip unavailable fragments and keep retrying"
        );
        ytDlpCookieHeaderInput = addEditText(
            root,
            "YouTube Cookie header for yt-dlp (optional)",
            "VISITOR_INFO1_LIVE=...; YSC=...; SID=..."
        );
        ytDlpCookiesPathInput = addEditText(
            root,
            "yt-dlp cookies.txt path (optional)",
            "/data/user/0/com.livemonitor.app/files/youtube-cookies.txt"
        );
        convertTsToMp4CheckBox = addCheckBox(root, "Convert completed TS recordings to MP4");

        restoreBootCheckBox = addCheckBox(root, "Restore monitoring after reboot");
        batteryOptimizationCheckBox = addCheckBox(
            root,
            "Ask for battery optimization exemption"
        );

        remoteConfigCheckBox = addCheckBox(root, "Enable remote config");
        remoteConfigUrlInput = addEditText(
            root,
            "Remote config URL",
            "https://raw.githubusercontent.com/lucifure/LiveMonitorChaquopy/main/config.json"
        );

        Button batteryButton = new Button(this);
        batteryButton.setAllCaps(false);
        batteryButton.setText("Open Battery Optimization Settings");
        batteryButton.setOnClickListener(v -> openBatteryOptimizationSettings());
        root.addView(batteryButton);

        Button saveButton = new Button(this);
        saveButton.setAllCaps(false);
        saveButton.setText("Save Settings");
        saveButton.setOnClickListener(v -> saveSettings());
        root.addView(saveButton);

        return root;
    }

    private void bindSettingsToViews() {
        pollIntervalInput.setText(String.valueOf(settings.getPollIntervalSeconds()));

        String[] qualities = {
            AppSettings.QUALITY_360P,
            AppSettings.QUALITY_480P,
            AppSettings.QUALITY_720P,
            AppSettings.QUALITY_1080P,
            AppSettings.QUALITY_BEST
        };

        for (int i = 0; i < qualities.length; i++) {
            if (qualities[i].equals(settings.getDownloadQuality())) {
                qualitySpinner.setSelection(i);
                break;
            }
        }

        saveLocationText.setText(settings.getSaveLocationDisplayName());
        scheduledCheckBox.setChecked(settings.isScheduledMonitoringEnabled());
        scheduleStartInput.setText(
            AppSettings.minutesToTimeLabel(settings.getScheduleStartMinutes())
        );
        scheduleEndInput.setText(
            AppSettings.minutesToTimeLabel(settings.getScheduleEndMinutes())
        );
        allowCurrentRecordingCheckBox.setChecked(
            settings.isAllowCurrentRecordingOutsideSchedule()
        );
        waitForVideoCheckBox.setChecked(settings.isWaitForVideoEnabled());
        liveFromStartCheckBox.setChecked(settings.isLiveFromStartEnabled());
        skipUnavailableFragmentsCheckBox.setChecked(settings.isSkipUnavailableFragmentsEnabled());
        ytDlpCookieHeaderInput.setText(settings.getYtDlpCookieHeader());
        ytDlpCookiesPathInput.setText(settings.getYtDlpCookiesPath());
        convertTsToMp4CheckBox.setChecked(settings.isConvertTsToMp4());
        restoreBootCheckBox.setChecked(settings.isRestoreMonitoringOnBoot());
        batteryOptimizationCheckBox.setChecked(
            settings.isRequestBatteryOptimizationExemption()
        );
        remoteConfigCheckBox.setChecked(settings.isRemoteConfigEnabled());

        if (settings.getRemoteConfigUrl().trim().isEmpty()) {
            remoteConfigUrlInput.setText(
                "https://raw.githubusercontent.com/lucifure/LiveMonitorChaquopy/main/config.json"
            );
        } else {
            remoteConfigUrlInput.setText(settings.getRemoteConfigUrl());
        }
    }

    private void saveSettings() {
        settings.setPollIntervalSeconds(parseInt(pollIntervalInput.getText().toString(), 60));
        settings.setDownloadQuality(String.valueOf(qualitySpinner.getSelectedItem()));
        settings.setScheduledMonitoringEnabled(scheduledCheckBox.isChecked());
        settings.setScheduleWindow(
            AppSettings.timeToMinutes(scheduleStartInput.getText().toString()),
            AppSettings.timeToMinutes(scheduleEndInput.getText().toString())
        );
        settings.setAllowCurrentRecordingOutsideSchedule(
            allowCurrentRecordingCheckBox.isChecked()
        );
        settings.setWaitForVideoEnabled(waitForVideoCheckBox.isChecked());
        settings.setLiveFromStartEnabled(liveFromStartCheckBox.isChecked());
        settings.setSkipUnavailableFragmentsEnabled(skipUnavailableFragmentsCheckBox.isChecked());
        settings.setYtDlpCookieHeader(ytDlpCookieHeaderInput.getText().toString());
        settings.setYtDlpCookiesPath(ytDlpCookiesPathInput.getText().toString());
        settings.setConvertTsToMp4(convertTsToMp4CheckBox.isChecked());
        settings.setRestoreMonitoringOnBoot(restoreBootCheckBox.isChecked());
        settings.setRequestBatteryOptimizationExemption(
            batteryOptimizationCheckBox.isChecked()
        );
        settings.setRemoteConfigEnabled(remoteConfigCheckBox.isChecked());
        settings.setRemoteConfigUrl(remoteConfigUrlInput.getText().toString().trim());

        storage.saveSettings(settings);
        storage.appendLog(LogItem.info(LogItem.SOURCE_UI, "Settings saved."));

        Toast.makeText(this, "Settings saved.", Toast.LENGTH_SHORT).show();
    }

    private void onSaveLocationSelected(Uri uri, String displayName) {
        if (uri == null) {
            return;
        }

        settings.setSaveLocation(uri.toString(), displayName);
        storage.saveSettings(settings);
        saveLocationText.setText(settings.getSaveLocationDisplayName());

        Toast.makeText(this, "Save location updated.", Toast.LENGTH_SHORT).show();
    }

    private void openBatteryOptimizationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(
                this,
                "Unable to open battery settings: " + e.getMessage(),
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void openSelectedSaveFolder() {
        String uriString = settings == null ? "" : settings.getSaveLocationUri();

        if (uriString == null || uriString.trim().isEmpty()) {
            Toast.makeText(this, "No custom save folder selected yet.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(uriString));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Unable to open folder: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private EditText addEditText(LinearLayout root, String label, String hint) {
        addLabel(root, label);

        EditText editText = new EditText(this);
        editText.setSingleLine(true);
        editText.setHint(hint);

        root.addView(
            editText,
            new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        );

        return editText;
    }

    private CheckBox addCheckBox(LinearLayout root, String text) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(text);
        root.addView(checkBox);
        return checkBox;
    }

    private void addLabel(LinearLayout root, String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(13);
        label.setTextColor(Color.rgb(190, 190, 190));
        label.setPadding(0, dp(8), 0, 0);
        root.addView(label);
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
