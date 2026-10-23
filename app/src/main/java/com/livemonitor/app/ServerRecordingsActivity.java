package com.livemonitor.app;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Remote Oracle/Tailscale status and recording browser. Network work never runs on the UI thread. */
public class ServerRecordingsActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<ServerRecordingGrouper.Group> recordings = new ArrayList<>();
    private TextView statusView, diskView, errorView, emptyView, subtitleView;
    private EditText urlInput;
    private LinearLayout channelContainer, recordingContainer;
    private ProgressBar diskProgress;
    private Button monitoringSectionButton, recordedFilesSectionButton;
    private int selectedSection;
    private static final int SECTION_MONITORING = 0;
    private static final int SECTION_RECORDED_FILES = 1;
    private boolean visible;
    private final Runnable poll = new Runnable() { @Override public void run() {
        if (visible) { refresh(); handler.postDelayed(this, 20_000L); }
    }};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        selectedSection = getPreferences(MODE_PRIVATE).getInt("selected_server_section", SECTION_MONITORING);
        setTitle("Server Recordings"); setContentView(AppChrome.wrapWithBottomNav(this, buildView(), R.id.nav_server));
    }
    @Override public void onBackPressed() { AppChrome.handleBack(this); }
    @Override protected void onResume() { super.onResume(); AppChrome.selectCurrentTab(this, R.id.nav_server); }
    @Override public void onStart() { super.onStart(); visible = true; refresh(); handler.postDelayed(poll, 20_000L); }
    @Override public void onStop() { visible = false; handler.removeCallbacks(poll); super.onStop(); }
    @Override public void onDestroy() { executor.shutdownNow(); super.onDestroy(); }

    private View buildView() {
        android.widget.ScrollView root = new android.widget.ScrollView(this);
        root.setFillViewport(true); root.setBackgroundResource(R.drawable.lm_screen_background);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(16), dp(20), dp(16)); root.addView(page);
        android.widget.FrameLayout heading = new android.widget.FrameLayout(this);
        heading.setMinimumHeight(dp(88));
        LinearLayout titleGroup = new LinearLayout(this); titleGroup.setOrientation(LinearLayout.VERTICAL); titleGroup.setGravity(Gravity.CENTER);
        android.widget.FrameLayout.LayoutParams centered = new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        TextView title = text("Live Monitor 2.0", 30); title.setTypeface(android.graphics.Typeface.SERIF); title.setGravity(Gravity.CENTER); title.setSingleLine(true); titleGroup.addView(title);
        subtitleView = text("Server · 0 channels", 15); subtitleView.setTextColor(0xff9ADAA0); subtitleView.setGravity(Gravity.CENTER); titleGroup.addView(subtitleView);
        heading.addView(titleGroup, centered);
        ImageButton settings = AppChrome.settingsButton(this);
        android.widget.FrameLayout.LayoutParams settingsParams = new android.widget.FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.CENTER_VERTICAL); heading.addView(settings, settingsParams); page.addView(heading);

        LinearLayout addRow = new LinearLayout(this); addRow.setGravity(Gravity.CENTER_VERTICAL); addRow.setBackgroundResource(R.drawable.lm_search_container_background);
        addRow.setPadding(dp(18), dp(5), dp(6), dp(5)); LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(-1, dp(58)); addParams.topMargin = dp(4); page.addView(addRow, addParams);
        urlInput = new EditText(this); urlInput.setSingleLine(true); urlInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI); urlInput.setHint("Paste YouTube channel URL..."); urlInput.setTextColor(getResources().getColor(R.color.lm_text_primary)); urlInput.setHintTextColor(0xff91A0AA); urlInput.setTextSize(18); urlInput.setBackgroundColor(android.graphics.Color.TRANSPARENT); urlInput.setPadding(0, 0, dp(8), 0);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, -1, 1); inputParams.rightMargin = dp(10);
        addRow.addView(urlInput, inputParams);
        MaterialButton add = new MaterialButton(this); add.setText("＋  ADD"); add.setTextColor(getResources().getColor(R.color.text)); add.setTextSize(18); add.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)); add.setLetterSpacing(0.05f); add.setBackgroundResource(R.drawable.lm_blue_add_pill_background); add.setStateListAnimator(null); add.setMinWidth(0); add.setMinimumWidth(0); add.setPadding(dp(14), 0, dp(14), 0); addRow.addView(add, new LinearLayout.LayoutParams(dp(130), -1)); add.setOnClickListener(v -> submitChannel());
        ImageButton refresh = iconButton(R.drawable.ic_refresh_24, "Refresh server"); LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(dp(40), dp(44)); refreshParams.leftMargin = dp(3); addRow.addView(refresh, refreshParams); refresh.setOnClickListener(v -> refresh());

        LinearLayout sectionToggle = new LinearLayout(this); sectionToggle.setOrientation(LinearLayout.HORIZONTAL);
        monitoringSectionButton = new Button(this); monitoringSectionButton.setText("Monitoring"); monitoringSectionButton.setAllCaps(false);
        recordedFilesSectionButton = new Button(this); recordedFilesSectionButton.setText("Recorded Files"); recordedFilesSectionButton.setAllCaps(false);
        sectionToggle.addView(monitoringSectionButton, new LinearLayout.LayoutParams(0, dp(40), 1f));
        LinearLayout.LayoutParams recordedParams = new LinearLayout.LayoutParams(0, dp(40), 1f); recordedParams.leftMargin = dp(6);
        sectionToggle.addView(recordedFilesSectionButton, recordedParams);
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toggleParams.topMargin = dp(8); toggleParams.bottomMargin = dp(8); page.addView(sectionToggle, toggleParams);
        monitoringSectionButton.setOnClickListener(v -> selectSection(SECTION_MONITORING));
        recordedFilesSectionButton.setOnClickListener(v -> selectSection(SECTION_RECORDED_FILES));

        statusView = text("Checking server…", 14); statusView.setPadding(0, dp(10), 0, dp(2)); page.addView(statusView);
        LinearLayout diskRow = new LinearLayout(this); diskRow.setOrientation(LinearLayout.VERTICAL); diskRow.setPadding(0, dp(3), 0, dp(2)); diskView = text("", 13); diskRow.addView(diskView);
        diskProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); diskProgress.setMax(1000); if (android.os.Build.VERSION.SDK_INT >= 21) { diskProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xff5DEBFF)); diskProgress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x5535404A)); }
        diskRow.addView(diskProgress, new LinearLayout.LayoutParams(-1, dp(4))); diskRow.setVisibility(View.GONE); page.addView(diskRow); diskRow.setTag("disk_row");
        errorView = text("", 14); errorView.setTextColor(0xfff28b82); errorView.setPadding(0, dp(6), 0, dp(2)); page.addView(errorView);
        channelContainer = new LinearLayout(this); channelContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cardsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        page.addView(channelContainer, cardsParams);
        emptyView = text("No server recordings found.", 15); emptyView.setGravity(Gravity.CENTER); page.addView(emptyView);
        recordingContainer = new LinearLayout(this); recordingContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams recordingCardsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        page.addView(recordingContainer, recordingCardsParams);
        selectSection(selectedSection);
        return root;
    }

    private void selectSection(int section) {
        selectedSection = section == SECTION_RECORDED_FILES ? SECTION_RECORDED_FILES : SECTION_MONITORING;
        getPreferences(MODE_PRIVATE).edit().putInt("selected_server_section", selectedSection).apply();
        styleSectionButton(monitoringSectionButton, selectedSection == SECTION_MONITORING);
        styleSectionButton(recordedFilesSectionButton, selectedSection == SECTION_RECORDED_FILES);
        if (channelContainer != null) channelContainer.setVisibility(selectedSection == SECTION_MONITORING ? View.VISIBLE : View.GONE);
        if (recordingContainer != null) recordingContainer.setVisibility(selectedSection == SECTION_RECORDED_FILES ? View.VISIBLE : View.GONE);
        if (emptyView != null) emptyView.setVisibility(selectedSection == SECTION_RECORDED_FILES && recordings.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void styleSectionButton(Button button, boolean selected) {
        if (button == null) return;
        button.setTextColor(selected ? getResources().getColor(R.color.white) : getResources().getColor(R.color.text_secondary));
        if (selected) {
            button.setBackgroundResource(R.drawable.lm_blue_add_pill_background);
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getResources().getColor(R.color.info)));
        } else {
            button.setBackgroundResource(R.drawable.lm_glass_button_background);
            button.setBackgroundTintList(null);
        }
    }

    private ImageButton iconButton(int icon, String description) { ImageButton button = new ImageButton(this); button.setImageResource(icon); button.setContentDescription(description); button.setPadding(dp(10), dp(10), dp(10), dp(10)); button.setBackgroundResource(android.R.drawable.list_selector_background); if (android.os.Build.VERSION.SDK_INT >= 21) button.setImageTintList(android.content.res.ColorStateList.valueOf(0xffAEB7C2)); return button; }

    private void refresh() {
        if (!OracleServerSettings.isConfigured(this)) { showError("Set the Server URL and API token in Settings → Oracle Server."); return; }
        executor.execute(() -> {
            try {
                OracleControlClient api = new OracleControlClient(this);
                JSONObject status = api.get("/status"); JSONObject files = api.get("/recordings");
                List<ServerRecordingGrouper.RemoteFile> loaded = new ArrayList<>(); JSONArray array = files.optJSONArray("recordings");
                if (array != null) for (int i = 0; i < array.length(); i++) {
                    JSONObject file = array.optJSONObject(i);
                    if (file != null) loaded.add(new ServerRecordingGrouper.RemoteFile(
                        file.optString("name"), file.optLong("size"), file.optLong("modified"), file.optBoolean("in_progress")));
                }
                runOnUiThread(() -> showData(status, loaded));
            } catch (Exception error) { runOnUiThread(() -> showError(readableError(error))); }
        });
    }

    private void showData(JSONObject status, List<ServerRecordingGrouper.RemoteFile> loaded) {
        errorView.setText(""); JSONArray list = status.optJSONArray("channels");
        List<RemoteChannel> channels = new ArrayList<>();
        double serverTime = status.optDouble("server_time", 0d);
        if (list != null) for (int i = 0; i < list.length(); i++) { JSONObject channel = list.optJSONObject(i); if (channel == null) continue;
            channels.add(RemoteChannel.from(channel, serverTime));
        }
        statusView.setText(""); statusView.setVisibility(View.GONE); subtitleView.setText("Server · " + channels.size() + " " + (channels.size() == 1 ? "channel" : "channels"));
        JSONObject disk = status.optJSONObject("disk"); View diskRow = (View) diskView.getParent();
        if (disk != null && disk.optLong("total") > 0) { long total = disk.optLong("total"), free = disk.optLong("free"); diskView.setText("Disk: " + bytes(free) + " free of " + bytes(total)); diskProgress.setProgress((int) Math.max(0, Math.min(1000, ((total - free) * 1000L) / total))); diskRow.setVisibility(View.VISIBLE); } else diskRow.setVisibility(View.GONE);
        recordings.clear(); recordings.addAll(ServerRecordingGrouper.group(loaded));
        renderChannels(channels);
        renderRecordings();
        selectSection(selectedSection);
    }
    private void showError(String message) { errorView.setText(message); statusView.setText("Server status unavailable."); statusView.setVisibility(View.VISIBLE); diskView.setText(""); if (diskView.getParent() != null) ((View) diskView.getParent()).setVisibility(View.GONE); if (channelContainer != null) channelContainer.removeAllViews(); recordings.clear(); renderRecordings(); }

    private void renderChannels(List<RemoteChannel> channels) {
        channelContainer.removeAllViews();
        if (channels.isEmpty()) { channelContainer.addView(text("No configured server channels.", 14)); return; }
        for (RemoteChannel channel : channels) {
            boolean recording = isStatus(channel, "recording");
            boolean finalizing = isStatus(channel, "finalizing");
            boolean active = recording || finalizing;
            boolean paused = isStatus(channel, "paused");
            boolean stopped = isStatus(channel, "stopped");
            boolean failed = isStatus(channel, "failed");
            ChannelCardView.Model model = new ChannelCardView.Model();
            model.title = channel.displayTitle();
            model.badgeLabel = finalizing ? "Finalizing" : active ? "Recording" : stopped ? "Stopped"
                : failed ? "Failed" : paused ? "Paused" : "Monitoring";
            model.badgeStyle = finalizing ? ChannelCardView.BADGE_FINALIZING
                : active ? ChannelCardView.BADGE_RECORDING : stopped || failed ? ChannelCardView.BADGE_STOPPED
                : paused ? ChannelCardView.BADGE_PAUSED : ChannelCardView.BADGE_MONITORING;
            model.subLineError = channel.lastError != null && !channel.lastError.trim().isEmpty();
            model.subLine = model.subLineError ? "Error: " + channel.lastError.trim()
                : active ? (channel.videoId == null || channel.videoId.isEmpty() ? "—" : channel.videoId)
                : paused ? "Monitoring paused" : "Waiting for stream";
            if (active) {
                model.stat1 = "⏱ " + formatServerElapsed(channel);
                model.stat2 = "📺 " + (channel.quality == null || channel.quality.isEmpty() ? "—" : channel.quality);
                ServerRecordingGrouper.Group group = findRecordingGroup(channel, channels);
                model.stat3 = "💾 " + (group == null ? "—" : bytes(group.totalSize));
            } else {
                model.stat1 = "🔄 Checked " + formatServerAge(channel.serverTime, channel.lastCheckAt);
                model.stat2 = "📺 " + (channel.quality == null || channel.quality.trim().isEmpty() ? "—" : channel.quality.trim());
                model.stat3 = channel.lastLiveAt > 0d
                    ? "📡 Live " + formatServerAge(channel.serverTime, channel.lastLiveAt) + (isJustNow(channel.serverTime, channel.lastLiveAt) ? "" : " ago")
                    : channel.hasLastCheckAt ? "📡 None yet" : "📡 Live —";
            }
            boolean pausedAction = "paused".equals(channel.status);
            boolean canStart = pausedAction || "idle".equals(channel.status);
            model.button1 = pausedAction ? "Resume" : "Start";
            model.button1Enabled = canStart;
            model.button2 = "Stop";
            model.button2Enabled = !paused;
            model.button3 = "Delete";
            model.button3Enabled = !active;
            model.button1Click = v -> channelAction(channel, pausedAction ? "resume" : "start");
            model.button2Click = v -> channelAction(channel, "stop");
            model.button3Click = v -> confirmDeleteChannel(channel);
            channelContainer.addView(new ChannelCardView(this, model));
        }
    }

    private boolean isStatus(RemoteChannel channel, String status) {
        return channel != null && status.equalsIgnoreCase(channel.status);
    }

    private String formatServerElapsed(RemoteChannel channel) {
        if (channel.serverTime <= 0d || channel.recordingStartedAt <= 0d) return "—";
        long seconds = Math.max(0L, (long) (channel.serverTime - channel.recordingStartedAt));
        return RecordingProgressTracker.formatDuration(seconds);
    }

    private String formatServerAge(double serverTime, double timestamp) {
        if (serverTime <= 0d || timestamp <= 0d) return "—";
        long seconds = Math.max(0L, (long) (serverTime - timestamp));
        if (seconds < 60L) return "just now";
        if (seconds < 3600L) return (seconds / 60L) + "m";
        if (seconds < 86400L) return (seconds / 3600L) + "h";
        return (seconds / 86400L) + "d";
    }

    private boolean isJustNow(double serverTime, double timestamp) {
        return serverTime > 0d && timestamp > 0d && Math.max(0L, (long) (serverTime - timestamp)) < 60L;
    }

    private ServerRecordingGrouper.Group findRecordingGroup(RemoteChannel target, List<RemoteChannel> channels) {
        List<ServerRecordingGrouper.Group> inProgress = new ArrayList<>();
        ServerRecordingGrouper.Group matching = null;
        String title = normalizeGroupTitle(target.title == null || target.title.trim().isEmpty()
            ? target.name : target.title);
        for (ServerRecordingGrouper.Group group : recordings) {
            if (!group.isInProgress()) continue;
            inProgress.add(group);
            String baseName = normalizeGroupTitle(group.baseName);
            if (!title.isEmpty() && baseName.startsWith(title)
                && (matching == null || group.modified > matching.modified)) {
                matching = group;
            }
        }
        if (matching != null) return matching;

        int recordingChannels = 0;
        for (RemoteChannel channel : channels) {
            if (isStatus(channel, "recording") || isStatus(channel, "finalizing")) recordingChannels++;
        }
        return inProgress.size() == 1 && recordingChannels == 1 ? inProgress.get(0) : null;
    }

    private static String normalizeGroupTitle(String value) {
        if (value == null) return "";
        String lower = value.toLowerCase(Locale.US);
        StringBuilder normalized = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char character = lower.charAt(i);
            if (Character.isLetterOrDigit(character)) normalized.append(character);
        }
        return normalized.toString();
    }

    private Button actionButton(String label) {
        Button button = new Button(this); button.setText(label); AppCardStyle.styleHomeActionButton(this, button);
        if ("Delete".equals(label)) {
            button.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_delete_24, 0, 0, 0);
            button.setCompoundDrawablePadding(dp(4));
        }
        button.setMinHeight(dp(40)); button.setMinimumHeight(dp(40)); return button;
    }

    private void addWeightedAction(LinearLayout row, Button button, boolean addGap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(40), 1f);
        if (addGap) params.leftMargin = dp(8);
        row.addView(button, params);
    }

    private LinearLayout newServerCard(LinearLayout parent) {
        FrameLayout outer = new FrameLayout(this);
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        AppCardStyle.applyHomeCard(this, outer, card);
        outer.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        parent.addView(outer, params);
        return card;
    }
    private void submitChannel() {
        String url = urlInput.getText().toString().trim(); if (url.isEmpty()) { urlInput.setError("Paste a YouTube channel URL"); return; }
        List<String> segments = Uri.parse(url).getPathSegments();
        String path = segments.isEmpty() ? "channel" : segments.get(segments.size() - 1);
        if ("live".equalsIgnoreCase(path) && segments.size() > 1) path = segments.get(segments.size() - 2);
        while (path.startsWith("@")) path = path.substring(1);
        String name = path.replaceFirst("(?i)\\.(html?|php)$", "").replaceAll("[^A-Za-z0-9_-]", "_").replaceAll("^_+|_+$", "");
        if (name.length() > 64) name = name.substring(0, 64);
        name = name.replaceAll("^_+|_+$", "");
        if (name.isEmpty()) name = "channel";
        JSONObject body = new JSONObject(); try { body.put("name", name); body.put("url", url); body.put("quality", "480p"); } catch (Exception ignored) { return; }
        executor.execute(() -> { try { new OracleControlClient(this).post("/channels", body); runOnUiThread(() -> { urlInput.setText(""); Toast.makeText(this, "Channel added.", Toast.LENGTH_SHORT).show(); refresh(); }); } catch (Exception error) { runOnUiThread(() -> Toast.makeText(this, actionError(error), Toast.LENGTH_LONG).show()); } });
    }
    private void renderRecordings() {
        if (recordingContainer == null) return;
        recordingContainer.removeAllViews(); emptyView.setVisibility(selectedSection == SECTION_RECORDED_FILES && recordings.isEmpty() ? View.VISIBLE : View.GONE);
        for (ServerRecordingGrouper.Group group : recordings) {
            LinearLayout card = newServerCard(recordingContainer);
            if (group.isPartial()) {
                TextView name = text("Partial recording", 16); card.addView(name);
                TextView details = text(group.baseName + " · " + group.mediaFiles.size() + " parts · " + bytes(group.totalSize) + " · " + relative(group.modified), 13);
                details.setTextColor(getResources().getColor(R.color.lm_text_secondary)); card.addView(details);
            } else if (group.isInProgress()) {
                TextView name = text(group.baseName, 16); name.setTextColor(getResources().getColor(R.color.info)); card.addView(name);
                TextView details = text("Recording... · " + bytes(group.totalSize), 13);
                details.setTextColor(getResources().getColor(R.color.lm_text_secondary)); card.addView(details);
            } else {
                TextView name = text(group.mediaFiles.get(0).name, 16); card.addView(name);
                TextView details = text(bytes(group.totalSize) + " · " + relative(group.modified), 13);
                details.setTextColor(getResources().getColor(R.color.lm_text_secondary)); card.addView(details);
            }

            if (!group.isInProgress()) {
                LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.END);
                Button down = actionButton("Download"); down.setOnClickListener(v -> downloadGroup(group));
                Button del = actionButton("Delete"); del.setOnClickListener(v -> confirmDelete(group));
                addWeightedAction(actions, down, false); addWeightedAction(actions, del, true); card.addView(actions);
            }
        }
    }

    private void downloadGroup(ServerRecordingGrouper.Group group) {
        if (group == null || group.isInProgress()) return;
        for (ServerRecordingGrouper.RemoteFile file : group.mediaFiles) {
            if (file.size > 0L) download(file);
        }
    }

    private void channelAction(RemoteChannel channel, String action) { executor.execute(() -> { try { new OracleControlClient(this).post("/channels/" + Uri.encode(channel.name) + "/" + action, new JSONObject()); runOnUiThread(() -> { Toast.makeText(this, "Channel " + action + " requested.", Toast.LENGTH_SHORT).show(); refresh(); }); } catch (Exception error) { runOnUiThread(() -> Toast.makeText(this, actionError(error), Toast.LENGTH_LONG).show()); } }); }
    private void confirmDeleteChannel(RemoteChannel channel) { new AlertDialog.Builder(this).setTitle("Remove channel?").setMessage("Remove " + channel.name + " from server monitoring? Existing recordings are kept.").setNegativeButton("Cancel", null).setPositiveButton("Remove", (d, w) -> deleteChannel(channel)).show(); }
    private void deleteChannel(RemoteChannel channel) { executor.execute(() -> { try { new OracleControlClient(this).delete("/channels/" + Uri.encode(channel.name)); runOnUiThread(() -> { Toast.makeText(this, "Channel removed.", Toast.LENGTH_SHORT).show(); refresh(); }); } catch (Exception error) { runOnUiThread(() -> Toast.makeText(this, actionError(error), Toast.LENGTH_LONG).show()); } }); }
    private void confirmDelete(ServerRecordingGrouper.Group group) {
        if (group == null) return;
        String description = group.isPartial() ? group.baseName + " and its " + group.mediaFiles.size() + " part(s)" : group.baseName;
        new AlertDialog.Builder(this).setTitle("Delete recording?").setMessage("Delete " + description + " from the server? This cannot be undone.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete", (d, w) -> delete(group)).show();
    }
    private void delete(ServerRecordingGrouper.Group group) { executor.execute(() -> {
        Exception failure = null;
        OracleControlClient api = new OracleControlClient(this);
        for (ServerRecordingGrouper.RemoteFile file : group.files) {
            try { api.delete("/recordings/" + Uri.encode(file.name)); }
            catch (Exception error) {
                if (!(error instanceof OracleControlClient.ApiException) || ((OracleControlClient.ApiException) error).getStatusCode() != 404) {
                    if (failure == null) failure = error;
                }
            }
        }
        Exception result = failure;
        runOnUiThread(() -> {
            if (result == null) { Toast.makeText(this, "Deleted.", Toast.LENGTH_SHORT).show(); refresh(); }
            else Toast.makeText(this, deleteError(result), Toast.LENGTH_LONG).show();
        });
    }); }
    private void download(ServerRecordingGrouper.RemoteFile recording) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(OracleServerSettings.getBaseUrl(this) + "/recordings/" + Uri.encode(recording.name)));
            request.addRequestHeader("Authorization", "Bearer " + OracleServerSettings.getDeviceToken(this)); request.setTitle(recording.name);
            request.setDescription("Downloading server recording"); request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            File destination = new File(new RecordingFileManager(this).getCompletedDirectory(), recording.name);
            request.setDestinationUri(Uri.fromFile(destination));
            ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(request);
            Toast.makeText(this, "Download started.", Toast.LENGTH_SHORT).show();
        } catch (Exception error) { Toast.makeText(this, "Unable to start download: " + error.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private String readableError(Exception error) { return error instanceof OracleControlClient.ApiException && ((OracleControlClient.ApiException) error).getStatusCode() == 401 ? "Unauthorized — check the Server API token." : "Cannot reach server. Check Tailscale, the server address, and server status."; }
    private String deleteError(Exception error) { if (error instanceof OracleControlClient.ApiException) { int code = ((OracleControlClient.ApiException) error).getStatusCode(); if (code == 409) return "Still recording — try again after it finishes"; if (code == 404) return "Already deleted"; } return readableError(error); }
    private String actionError(Exception error) { if (error instanceof OracleControlClient.ApiException) { int code = ((OracleControlClient.ApiException) error).getStatusCode(); if (code == 409) return "Action is unavailable while this channel is recording."; if (code == 400) return "Check the channel name, YouTube URL, and quality."; if (code == 404) return "Channel no longer exists."; } return readableError(error); }
    private TextView text(String value, int size) { TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(getResources().getColor(R.color.lm_text_primary)); return text; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String bytes(long value) { if (value < 1024) return value + " B"; String[] u = {"KB", "MB", "GB", "TB"}; double n = value; int i = -1; do { n /= 1024; i++; } while (n >= 1024 && i < u.length - 1); return String.format(Locale.US, "%.1f %s", n, u[i]); }
    private static String relative(long seconds) { long delta = Math.max(0, System.currentTimeMillis() / 1000 - seconds); if (delta < 60) return "just now"; if (delta < 3600) return delta / 60 + " minutes ago"; if (delta < 86400) return delta / 3600 + " hours ago"; return delta / 86400 + " days ago"; }
    private static final class RemoteChannel {
        String name, url, quality, status, title, lastError, videoId;
        double serverTime, recordingStartedAt, lastCheckAt, lastLiveAt;
        boolean hasLastCheckAt;

        String displayTitle() {
            if (title != null && !title.trim().isEmpty()) return title.trim();
            if (name == null || name.trim().isEmpty()) return "Unknown channel";
            return name.startsWith("@") ? name : "@" + name;
        }

        static RemoteChannel from(JSONObject json, double serverTime) {
            RemoteChannel channel = new RemoteChannel();
            channel.name = json.optString("name");
            channel.url = json.optString("url");
            channel.quality = json.optString("quality", "480p");
            channel.status = json.optString("status", "unknown");
            channel.title = json.optString("title");
            channel.lastError = json.optString("last_error");
            channel.videoId = json.optString("video_id");
            channel.serverTime = serverTime;
            channel.recordingStartedAt = json.optDouble("recording_started_at", 0d);
            channel.hasLastCheckAt = json.has("last_check_at");
            channel.lastCheckAt = json.optDouble("last_check_at", 0d);
            channel.lastLiveAt = json.optDouble("last_live_at", 0d);
            return channel;
        }
    }
}
