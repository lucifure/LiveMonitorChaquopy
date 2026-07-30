package com.livemonitor.app;

import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;

public class SettingsActivity extends AppCompatActivity {
    private static final String SECTION_RECORDING = "Recording";
    private static final String SECTION_SCHEDULE = "Schedule";
    private static final String SECTION_AUTH = "Authentication";
    private static final String SECTION_APP = "App Behaviour";
    private static final String SECTION_DEBUG = "Developer / Debug";

    private AppStorage storage;
    private AppSettings settings;
    private StorageAccessHelper storageAccessHelper;
    private String currentSection = "";

    private EditText pollIntervalInput, scheduleStartInput, scheduleEndInput, ytDlpCookieHeaderInput,
        ytDlpCookiesPathInput, ytDlpExtractorArgsInput, ytDlpPoTokenClientInput, ytDlpPoTokenValueInput,
        remoteConfigUrlInput;
    private Spinner qualitySpinner;
    private TextView saveLocationText, remoteConfigUrlLabel;
    private CheckBox scheduledCheckBox, allowCurrentRecordingCheckBox, waitForVideoCheckBox,
        liveFromStartCheckBox, skipUnavailableFragmentsCheckBox, convertTsToMp4CheckBox,
        restoreBootCheckBox, batteryOptimizationCheckBox, remoteConfigCheckBox, verboseDebugLoggingCheckBox;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        storage = new AppStorage(this);
        settings = storage.loadSettings();
        storageAccessHelper = new StorageAccessHelper(this, this::onSaveLocationSelected);
        showMainSettings();
    }

    @Override protected void onResume() {
        super.onResume();
        if (storage != null) {
            settings = storage.loadSettings();
            bindSettingsToViews();
        }
    }

    @Override public void onBackPressed() {
        if (!currentSection.isEmpty()) {
            showMainSettings();
            return;
        }
        super.onBackPressed();
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item != null && item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showMainSettings() {
        currentSection = "";
        setTitle("Settings");
        setContentView(buildMainSettingsView());
    }

    private void showSection(String section) {
        currentSection = section;
        setTitle(section);
        setContentView(buildSectionView(section));
        bindSettingsToViews();
    }

    private View buildMainSettingsView() {
        LinearLayout root = baseRoot();
        addToolbar(root, "Settings");
        root.setPadding(dp(14), 0, dp(14), dp(12));
        addSectionRow(root, SECTION_RECORDING, "Quality, save location, recording options", R.drawable.ic_videocam_24);
        addDivider(root);
        addSectionRow(root, SECTION_SCHEDULE, "Monitoring schedule and timing", R.drawable.ic_schedule_24);
        addDivider(root);
        addSectionRow(root, SECTION_AUTH, "Cookies, PO token, extractor args", R.drawable.ic_lock_24);
        addDivider(root);
        addSectionRow(root, SECTION_APP, "Reboot, battery, remote config", R.drawable.ic_settings_24);
        addDivider(root);
        addSectionRow(root, SECTION_DEBUG, "Logging and diagnostics", R.drawable.ic_code_24);
        return wrap(root);
    }

    private View buildSectionView(String section) {
        LinearLayout root = baseRoot();
        addToolbar(root, section);
        root.setPadding(dp(14), 0, dp(14), dp(14));
        if (SECTION_RECORDING.equals(section)) addRecordingSection(root);
        else if (SECTION_SCHEDULE.equals(section)) addScheduleSection(root);
        else if (SECTION_AUTH.equals(section)) addAuthenticationSection(root);
        else if (SECTION_APP.equals(section)) addAppBehaviourSection(root);
        else if (SECTION_DEBUG.equals(section)) addDebugSection(root);
        Button saveButton = new Button(this);
        saveButton.setAllCaps(false);
        saveButton.setText("Save Settings");
        saveButton.setOnClickListener(v -> saveSettings());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(18);
        root.addView(saveButton, lp);
        return wrap(root);
    }

    private LinearLayout baseRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.lm_screen_background);
        return root;
    }

    private View wrap(LinearLayout root) {
        View toolbar = null;
        if (root.getChildCount() > 0 && root.getChildAt(0) instanceof Toolbar) {
            toolbar = root.getChildAt(0);
            root.removeViewAt(0);
        }

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundResource(R.drawable.lm_screen_background);
        scrollView.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (toolbar == null) {
            return scrollView;
        }

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundResource(R.drawable.lm_screen_background);
        screen.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        screen.addView(scrollView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return screen;
    }

    private void addToolbar(LinearLayout root, String titleText) {
        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle(titleText);
        toolbar.setTitleTextColor(Color.WHITE);
        toolbar.setBackgroundColor(Color.rgb(5, 36, 40));
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back_24);
        toolbar.setNavigationOnClickListener(v -> onBackPressed());
        root.addView(toolbar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(64)
        ));
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeButtonEnabled(true);
            actionBar.setTitle(titleText);
        }
    }

    private void addSectionRow(LinearLayout root, String title, String subtitle, int iconRes) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), 0, dp(14), 0);
        row.setMinimumHeight(dp(64));
        row.setOnClickListener(v -> showSection(title));
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(getResources().getColor(R.color.accent));
        row.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(18), 0, 0, 0);
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setTextColor(Color.WHITE);
        TextView sub = new TextView(this);
        sub.setText(subtitle);
        sub.setTextSize(13);
        sub.setTextColor(Color.rgb(155, 170, 170));
        texts.addView(name);
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextSize(30);
        chevron.setTextColor(Color.rgb(180, 190, 190));
        row.addView(chevron);
        root.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
    }

    private void addRecordingSection(LinearLayout root) {
        pollIntervalInput = addEditText(root, "Poll interval seconds", "300");
        qualitySpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
            new String[]{AppSettings.QUALITY_144P, AppSettings.QUALITY_360P, AppSettings.QUALITY_480P, AppSettings.QUALITY_720P, AppSettings.QUALITY_1080P});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        qualitySpinner.setAdapter(adapter);
        addLabel(root, "Download quality"); root.addView(qualitySpinner);
        addLabel(root, "Save location");
        saveLocationText = new TextView(this); saveLocationText.setTextColor(getResources().getColor(R.color.lm_text_secondary)); saveLocationText.setTextSize(14); saveLocationText.setPadding(0, dp(4), 0, dp(4)); root.addView(saveLocationText);
        Button choose = addButton(root, "Choose Save Folder"); choose.setOnClickListener(v -> storageAccessHelper.openFolderPicker());
        Button open = addButton(root, "Open folder"); open.setOnClickListener(v -> openSelectedSaveFolder());
        liveFromStartCheckBox = addCheckBox(root, "Record from start when YouTube DVR is available");
        skipUnavailableFragmentsCheckBox = addCheckBox(root, "Skip unavailable fragments and keep retrying");
        convertTsToMp4CheckBox = addCheckBox(root, "Convert completed TS recordings to MP4");
    }

    private void addScheduleSection(LinearLayout root) {
        scheduledCheckBox = addCheckBox(root, "Enable scheduled monitoring");
        scheduledCheckBox.setOnCheckedChangeListener((b, checked) -> updateScheduleFieldsEnabled());
        scheduleStartInput = addEditText(root, "Schedule start HH:mm", "00:00");
        scheduleEndInput = addEditText(root, "Schedule end HH:mm", "23:59");
        allowCurrentRecordingCheckBox = addCheckBox(root, "Allow current recording to finish outside schedule");
        waitForVideoCheckBox = addCheckBox(root, "Wait for scheduled live video to start");
    }

    private void addAuthenticationSection(LinearLayout root) {
        ytDlpCookieHeaderInput = addEditText(root, "YouTube Cookie header for yt-dlp (optional)", "VISITOR_INFO1_LIVE=...; YSC=...; SID=...");
        ytDlpCookiesPathInput = addEditText(root, "yt-dlp cookies.txt path (optional)", "/data/user/0/com.livemonitor.app/files/youtube-cookies.txt");
        Button signIn = addButton(root, "Open YouTube session / PO token setup"); signIn.setOnClickListener(v -> startActivity(new Intent(this, YouTubeSignInActivity.class)));
        ytDlpPoTokenClientInput = addEditText(root, "YouTube PO token client for GVS formats", "mweb");
        ytDlpPoTokenValueInput = addEditText(root, "YouTube GVS PO token value", "Paste real token only, or mweb.gvs+TOKEN");
        Button apply = addButton(root, "Apply PO token to extractor args"); apply.setOnClickListener(v -> applyPoTokenToExtractorArgs());
        ytDlpExtractorArgsInput = addEditText(root, "yt-dlp extractor args (optional)", "youtube:player_client=mweb");
    }

    private void addAppBehaviourSection(LinearLayout root) {
        restoreBootCheckBox = addCheckBox(root, "Restore monitoring after reboot");
        batteryOptimizationCheckBox = addCheckBox(root, "Ask for battery optimization exemption");
        Button battery = addButton(root, "Open Battery Optimization Settings"); battery.setOnClickListener(v -> openBatteryOptimizationSettings());
        remoteConfigCheckBox = addCheckBox(root, "Enable remote config");
        remoteConfigCheckBox.setOnCheckedChangeListener((b, checked) -> updateRemoteConfigVisibility());
        remoteConfigUrlInput = addEditText(root, "Remote config URL", "https://raw.githubusercontent.com/lucifure/LiveMonitorChaquopy/main/config.json");
    }

    private void addDebugSection(LinearLayout root) {
        verboseDebugLoggingCheckBox = addCheckBox(root, "Verbose/Debug logging");
        Button logs = addButton(root, "Log Settings"); logs.setOnClickListener(v -> startActivity(new Intent(this, LogSettingsActivity.class)));
        Button viewSelectLog = addButton(root, "View/Select & Copy Log");
        viewSelectLog.setOnClickListener(v -> {
            Intent intent = new Intent(this, LogActivity.class);
            intent.putExtra(LogActivity.EXTRA_VIEW_SELECT_ON_OPEN, true);
            startActivity(intent);
        });
    }

    private void bindSettingsToViews() {
        if (settings == null) return;
        if (pollIntervalInput != null) pollIntervalInput.setText(String.valueOf(settings.getPollIntervalSeconds()));
        if (qualitySpinner != null) {
            String[] qualities = {AppSettings.QUALITY_144P, AppSettings.QUALITY_360P, AppSettings.QUALITY_480P, AppSettings.QUALITY_720P, AppSettings.QUALITY_1080P};
            for (int i = 0; i < qualities.length; i++) if (qualities[i].equals(settings.getDownloadQuality())) qualitySpinner.setSelection(i);
        }
        if (saveLocationText != null) saveLocationText.setText(settings.getSaveLocationDisplayName());
        if (scheduledCheckBox != null) scheduledCheckBox.setChecked(settings.isScheduledMonitoringEnabled());
        if (scheduleStartInput != null) scheduleStartInput.setText(AppSettings.minutesToTimeLabel(settings.getScheduleStartMinutes()));
        if (scheduleEndInput != null) scheduleEndInput.setText(AppSettings.minutesToTimeLabel(settings.getScheduleEndMinutes()));
        if (allowCurrentRecordingCheckBox != null) allowCurrentRecordingCheckBox.setChecked(settings.isAllowCurrentRecordingOutsideSchedule());
        if (waitForVideoCheckBox != null) waitForVideoCheckBox.setChecked(settings.isWaitForVideoEnabled());
        if (liveFromStartCheckBox != null) liveFromStartCheckBox.setChecked(settings.isLiveFromStartEnabled());
        if (skipUnavailableFragmentsCheckBox != null) skipUnavailableFragmentsCheckBox.setChecked(settings.isSkipUnavailableFragmentsEnabled());
        if (ytDlpCookieHeaderInput != null) ytDlpCookieHeaderInput.setText(settings.getYtDlpCookieHeader());
        if (ytDlpCookiesPathInput != null) ytDlpCookiesPathInput.setText(settings.getYtDlpCookiesPath());
        if (ytDlpExtractorArgsInput != null) ytDlpExtractorArgsInput.setText(settings.getYtDlpExtractorArgs());
        if (ytDlpPoTokenClientInput != null) ytDlpPoTokenClientInput.setText(settings.getYtDlpPoTokenClient());
        if (ytDlpPoTokenValueInput != null) ytDlpPoTokenValueInput.setText(settings.getYtDlpPoTokenValue());
        if (convertTsToMp4CheckBox != null) convertTsToMp4CheckBox.setChecked(settings.isConvertTsToMp4());
        if (restoreBootCheckBox != null) restoreBootCheckBox.setChecked(settings.isRestoreMonitoringOnBoot());
        if (batteryOptimizationCheckBox != null) batteryOptimizationCheckBox.setChecked(settings.isRequestBatteryOptimizationExemption());
        if (remoteConfigCheckBox != null) remoteConfigCheckBox.setChecked(settings.isRemoteConfigEnabled());
        if (verboseDebugLoggingCheckBox != null) verboseDebugLoggingCheckBox.setChecked(settings.isLogDebugEnabled());
        if (remoteConfigUrlInput != null) remoteConfigUrlInput.setText(settings.getRemoteConfigUrl().trim().isEmpty() ? "https://raw.githubusercontent.com/lucifure/LiveMonitorChaquopy/main/config.json" : settings.getRemoteConfigUrl());
        updateScheduleFieldsEnabled(); updateRemoteConfigVisibility();
    }

    private void saveSettings() {
        if (SECTION_RECORDING.equals(currentSection)) {
            settings.setPollIntervalSeconds(parseInt(pollIntervalInput.getText().toString(), 300));
            settings.setDownloadQuality(String.valueOf(qualitySpinner.getSelectedItem()));
            settings.setLiveFromStartEnabled(liveFromStartCheckBox.isChecked());
            settings.setSkipUnavailableFragmentsEnabled(skipUnavailableFragmentsCheckBox.isChecked());
            settings.setConvertTsToMp4(convertTsToMp4CheckBox.isChecked());
        } else if (SECTION_SCHEDULE.equals(currentSection)) {
            settings.setScheduledMonitoringEnabled(scheduledCheckBox.isChecked());
            settings.setScheduleWindow(AppSettings.timeToMinutes(scheduleStartInput.getText().toString()), AppSettings.timeToMinutes(scheduleEndInput.getText().toString()));
            settings.setAllowCurrentRecordingOutsideSchedule(allowCurrentRecordingCheckBox.isChecked());
            settings.setWaitForVideoEnabled(waitForVideoCheckBox.isChecked());
        } else if (SECTION_AUTH.equals(currentSection)) {
            settings.setYtDlpCookieHeader(ytDlpCookieHeaderInput.getText().toString());
            settings.setYtDlpCookiesPath(ytDlpCookiesPathInput.getText().toString());
            settings.setYtDlpExtractorArgs(ytDlpExtractorArgsInput.getText().toString());
            String previous = settings.getYtDlpPoTokenValue();
            String token = ytDlpPoTokenValueInput.getText().toString();
            settings.setYtDlpPoTokenClient(inferPoTokenClient(ytDlpPoTokenClientInput.getText().toString(), token));
            settings.setYtDlpPoTokenValue(token);
            if (settings.getYtDlpPoTokenValue().isEmpty()) settings.clearYtDlpPoToken();
            else if (!settings.getYtDlpPoTokenValue().equals(previous)) settings.setYtDlpPoTokenMetadata("gvs", System.currentTimeMillis(), "manual-settings", "manual-session", "", "");
        } else if (SECTION_APP.equals(currentSection)) {
            settings.setRestoreMonitoringOnBoot(restoreBootCheckBox.isChecked());
            settings.setRequestBatteryOptimizationExemption(batteryOptimizationCheckBox.isChecked());
            settings.setRemoteConfigEnabled(remoteConfigCheckBox.isChecked());
            settings.setRemoteConfigUrl(remoteConfigUrlInput.getText().toString().trim());
        } else if (SECTION_DEBUG.equals(currentSection)) {
            settings.setLogDebugEnabled(verboseDebugLoggingCheckBox.isChecked());
        }
        storage.saveSettings(settings);
        storage.appendLog(LogItem.info(LogItem.SOURCE_UI, "Settings saved."));
        Toast.makeText(this, "Settings saved.", Toast.LENGTH_SHORT).show();
    }

    private void applyPoTokenToExtractorArgs() {
        AppSettings preview = new AppSettings();
        String token = ytDlpPoTokenValueInput.getText().toString();
        preview.setYtDlpPoTokenClient(inferPoTokenClient(ytDlpPoTokenClientInput.getText().toString(), token));
        preview.setYtDlpPoTokenValue(token);
        String args = preview.buildYtDlpPoTokenExtractorArgs();
        if (args.isEmpty()) { Toast.makeText(this, "Enter a real GVS PO token first.", Toast.LENGTH_SHORT).show(); return; }
        ytDlpExtractorArgsInput.setText(args);
        Toast.makeText(this, "PO token extractor args applied.", Toast.LENGTH_SHORT).show();
    }

    private String inferPoTokenClient(String selectedClient, String tokenInput) {
        String normalized = tokenInput == null ? "" : tokenInput.trim().toLowerCase(java.util.Locale.US);
        int marker = normalized.indexOf(".gvs+");
        return marker > 0 ? normalized.substring(0, marker) : selectedClient;
    }

    private void onSaveLocationSelected(Uri uri, String displayName) {
        if (uri == null) return;
        settings.setSaveLocation(uri.toString(), displayName);
        storage.saveSettings(settings);
        if (saveLocationText != null) saveLocationText.setText(settings.getSaveLocationDisplayName());
        Toast.makeText(this, "Save location updated.", Toast.LENGTH_SHORT).show();
    }

    private void openBatteryOptimizationSettings() {
        try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
        catch (Exception e) { Toast.makeText(this, "Unable to open battery settings: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private void openSelectedSaveFolder() {
        String uriString = settings == null ? "" : settings.getSaveLocationUri();
        if (uriString == null || uriString.trim().isEmpty()) {
            Toast.makeText(this, "No custom save folder selected yet.", Toast.LENGTH_SHORT).show();
            return;
        }

        Uri folderUri = Uri.parse(uriString);
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(folderUri, DocumentsContract.Document.MIME_TYPE_DIR);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception viewError) {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, folderUri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                startActivity(intent);
            } catch (Exception treeError) {
                Toast.makeText(this, "Unable to open folder: " + treeError.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private EditText addEditText(LinearLayout root, String label, String hint) { addLabel(root, label); EditText e = new EditText(this); e.setSingleLine(true); e.setHint(hint); root.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)); return e; }
    private CheckBox addCheckBox(LinearLayout root, String text) { CheckBox c = new CheckBox(this); c.setText(text); c.setTextColor(Color.WHITE); root.addView(c); return c; }
    private Button addButton(LinearLayout root, String text) { Button b = new Button(this); b.setAllCaps(false); b.setText(text); root.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)); return b; }
    private void addLabel(LinearLayout root, String text) { TextView l = new TextView(this); l.setText(text); l.setTextSize(13); l.setTextColor(Color.rgb(190,190,190)); l.setPadding(0, dp(8), 0, 0); root.addView(l); if ("Remote config URL".equals(text)) remoteConfigUrlLabel = l; }
    private void addDivider(LinearLayout root) { View d = new View(this); d.setBackgroundColor(getResources().getColor(R.color.lm_divider)); root.addView(d, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))); }
    private void updateScheduleFieldsEnabled() { boolean enabled = scheduledCheckBox != null && scheduledCheckBox.isChecked(); setEnabledWithAlpha(scheduleStartInput, enabled); setEnabledWithAlpha(scheduleEndInput, enabled); setEnabledWithAlpha(allowCurrentRecordingCheckBox, enabled); setEnabledWithAlpha(waitForVideoCheckBox, enabled); }
    private void updateRemoteConfigVisibility() { boolean enabled = remoteConfigCheckBox != null && remoteConfigCheckBox.isChecked(); if (remoteConfigUrlInput != null) { remoteConfigUrlInput.setVisibility(enabled ? View.VISIBLE : View.GONE); remoteConfigUrlInput.setEnabled(enabled); } if (remoteConfigUrlLabel != null) remoteConfigUrlLabel.setVisibility(enabled ? View.VISIBLE : View.GONE); }
    private void setEnabledWithAlpha(View view, boolean enabled) { if (view != null) { view.setEnabled(enabled); view.setAlpha(enabled ? 1f : 0.45f); } }
    private int parseInt(String value, int fallback) { try { return Integer.parseInt(value.trim()); } catch (Exception ignored) { return fallback; } }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
