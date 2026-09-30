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
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

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
    private final List<RemoteRecording> recordings = new ArrayList<>();
    private TextView statusView, diskView, errorView, emptyView;
    private ServerAdapter adapter;
    private boolean visible;
    private final Runnable poll = new Runnable() { @Override public void run() {
        if (visible) { refresh(); handler.postDelayed(this, 20_000L); }
    }};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); setTitle("Server Recordings"); setContentView(buildView());
    }
    @Override public void onStart() { super.onStart(); visible = true; refresh(); handler.postDelayed(poll, 20_000L); }
    @Override public void onStop() { visible = false; handler.removeCallbacks(poll); super.onStop(); }
    @Override public void onDestroy() { executor.shutdownNow(); super.onDestroy(); }

    private View buildView() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(12)); root.setBackgroundResource(R.drawable.lm_screen_background);
        LinearLayout heading = new LinearLayout(this); heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Server Recordings", 26); heading.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button refresh = new Button(this); refresh.setText("Refresh"); refresh.setAllCaps(false); refresh.setOnClickListener(v -> refresh()); heading.addView(refresh);
        root.addView(heading);
        statusView = text("Checking server…", 15); statusView.setPadding(0, dp(12), 0, dp(4)); root.addView(statusView);
        diskView = text("", 14); root.addView(diskView);
        errorView = text("", 14); errorView.setTextColor(0xfff28b82); errorView.setPadding(0, dp(8), 0, dp(4)); root.addView(errorView);
        TextView label = text("Recordings", 20); label.setPadding(0, dp(16), 0, dp(6)); root.addView(label);
        emptyView = text("No server recordings found.", 15); emptyView.setGravity(Gravity.CENTER); emptyView.setVisibility(View.GONE);
        adapter = new ServerAdapter(); ListView list = new ListView(this); list.setAdapter(adapter); list.setEmptyView(emptyView); list.setDividerHeight(dp(6));
        root.addView(emptyView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3));
        return root;
    }

    private void refresh() {
        if (!OracleServerSettings.isConfigured(this)) { showError("Set the Server URL and API token in Settings → Oracle Server."); return; }
        executor.execute(() -> {
            try {
                OracleControlClient api = new OracleControlClient(this);
                JSONObject status = api.get("/status"); JSONObject files = api.get("/recordings");
                List<RemoteRecording> loaded = new ArrayList<>(); JSONArray array = files.optJSONArray("recordings");
                if (array != null) for (int i = 0; i < array.length(); i++) loaded.add(RemoteRecording.from(array.getJSONObject(i)));
                runOnUiThread(() -> showData(status, loaded));
            } catch (Exception error) { runOnUiThread(() -> showError(readableError(error))); }
        });
    }

    private void showData(JSONObject status, List<RemoteRecording> loaded) {
        errorView.setText(""); StringBuilder channels = new StringBuilder(); boolean hasRecording = false; JSONArray list = status.optJSONArray("channels");
        if (list != null) for (int i = 0; i < list.length(); i++) { JSONObject channel = list.optJSONObject(i); if (channel == null) continue;
            if (channels.length() > 0) channels.append("\n"); String state = channel.optString("status", "unknown"); hasRecording |= "recording".equals(state);
            channels.append("recording".equals(state) ? "● " : "○ ").append(channel.optString("name", "Channel")).append(" — ").append(state);
            String title = channel.optString("title", ""); if (!title.isEmpty()) channels.append("\n   ").append(title);
        }
        statusView.setText(channels.length() == 0 ? "No channels reported." : channels.toString());
        statusView.setTextColor(hasRecording ? 0xffff6b6b : getResources().getColor(R.color.lm_text_primary));
        JSONObject disk = status.optJSONObject("disk"); if (disk != null) diskView.setText("Disk: " + bytes(disk.optLong("free")) + " free of " + bytes(disk.optLong("total")));
        recordings.clear(); recordings.addAll(loaded); adapter.notifyDataSetChanged();
    }
    private void showError(String message) { errorView.setText(message); statusView.setText("Server status unavailable."); diskView.setText(""); recordings.clear(); if (adapter != null) adapter.notifyDataSetChanged(); }

    private void confirmDelete(RemoteRecording recording) {
        new AlertDialog.Builder(this).setTitle("Delete recording?").setMessage("Delete " + recording.name + "? This cannot be undone.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete", (d, w) -> delete(recording)).show();
    }
    private void delete(RemoteRecording recording) { executor.execute(() -> { try {
        new OracleControlClient(this).delete("/recordings/" + Uri.encode(recording.name)); runOnUiThread(() -> { Toast.makeText(this, "Deleted.", Toast.LENGTH_SHORT).show(); refresh(); });
    } catch (Exception error) { runOnUiThread(() -> Toast.makeText(this, deleteError(error), Toast.LENGTH_LONG).show()); }}); }
    private void download(RemoteRecording recording) {
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
    private TextView text(String value, int size) { TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(getResources().getColor(R.color.lm_text_primary)); return text; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String bytes(long value) { if (value < 1024) return value + " B"; String[] u = {"KB", "MB", "GB", "TB"}; double n = value; int i = -1; do { n /= 1024; i++; } while (n >= 1024 && i < u.length - 1); return String.format(Locale.US, "%.1f %s", n, u[i]); }
    private static String relative(long seconds) { long delta = Math.max(0, System.currentTimeMillis() / 1000 - seconds); if (delta < 60) return "just now"; if (delta < 3600) return delta / 60 + " minutes ago"; if (delta < 86400) return delta / 3600 + " hours ago"; return delta / 86400 + " days ago"; }
    private static final class RemoteRecording { String name; long size, modified; boolean inProgress; static RemoteRecording from(JSONObject json) { RemoteRecording r = new RemoteRecording(); r.name = json.optString("name"); r.size = json.optLong("size"); r.modified = json.optLong("modified"); r.inProgress = json.optBoolean("in_progress"); return r; } }
    private final class ServerAdapter extends BaseAdapter {
        @Override public int getCount() { return recordings.size(); } @Override public Object getItem(int i) { return recordings.get(i); } @Override public long getItemId(int i) { return i; }
        @Override public View getView(int position, View convert, ViewGroup parent) { RemoteRecording r = recordings.get(position); LinearLayout row = new LinearLayout(ServerRecordingsActivity.this); row.setOrientation(LinearLayout.VERTICAL); row.setPadding(dp(12), dp(10), dp(12), dp(10)); row.setBackgroundResource(R.drawable.lm_glass_card_background);
            TextView name = text(r.name, 16); if (r.inProgress) name.setAlpha(.5f); row.addView(name); TextView details = text(bytes(r.size) + " · " + relative(r.modified) + (r.inProgress ? " · Recording…" : ""), 13); details.setTextColor(r.inProgress ? 0xff9e9e9e : 0xffb0bec5); row.addView(details);
            if (!r.inProgress) { LinearLayout actions = new LinearLayout(ServerRecordingsActivity.this); Button down = new Button(ServerRecordingsActivity.this); down.setText("Download"); down.setAllCaps(false); down.setOnClickListener(v -> download(r)); Button del = new Button(ServerRecordingsActivity.this); del.setText("Delete"); del.setAllCaps(false); del.setOnClickListener(v -> confirmDelete(r)); actions.addView(down); actions.addView(del); row.addView(actions); } return row; }
    }
}
