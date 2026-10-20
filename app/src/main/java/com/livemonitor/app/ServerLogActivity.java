package com.livemonitor.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays and manages the server's in-memory log. */
public class ServerLogActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<Entry> entries = new ArrayList<>();
    private final List<Button> filterButtons = new ArrayList<>();
    private LinearLayout root;
    private TextView subtitle, logText;
    private ScrollView scrollView;
    private String selectedFilter = "ALL";
    private AppStorage storage;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        storage = new AppStorage(this);
        setTitle("Server Log");
        setContentView(buildView());
        refresh();
    }

    @Override protected void onDestroy() { executor.shutdownNow(); super.onDestroy(); }

    private View buildView() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12)); root.setBackgroundColor(Color.rgb(15, 15, 15));
        root.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView title = new TextView(this); title.setText("Server Log"); title.setTextSize(22); title.setTextColor(Color.WHITE); title.setPadding(0, 0, 0, dp(3)); root.addView(title);
        addLogModeToggle(root, false);
        subtitle = new TextView(this); subtitle.setTextColor(Color.rgb(190, 190, 190)); subtitle.setTextSize(12); subtitle.setPadding(0, 0, 0, dp(8)); root.addView(subtitle);

        LinearLayout filters = new LinearLayout(this); filters.setGravity(Gravity.CENTER);
        addFilter(filters, "All", "ALL"); addFilter(filters, "Errors", "ERRORS"); addFilter(filters, "Warnings", "WARNINGS"); addFilter(filters, "Success", "SUCCESS"); root.addView(filters);

        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.CENTER);
        addAction(actions, "Refresh", v -> refresh()); addAction(actions, "Copy Log", v -> copyLog()); addAction(actions, "Save Log", v -> saveLog()); addAction(actions, "Clear Log", v -> confirmClear()); root.addView(actions);

        scrollView = new ScrollView(this); scrollView.setFillViewport(true); scrollView.setBackgroundColor(Color.rgb(15, 15, 15));
        logText = new TextView(this); logText.setTextSize(12); logText.setTextColor(Color.rgb(224, 224, 224)); logText.setTextIsSelectable(true); logText.setPadding(0, dp(8), 0, dp(8)); scrollView.addView(logText);
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); logParams.topMargin = dp(6); root.addView(scrollView, logParams);
        Button close = new Button(this); close.setText("Close"); close.setAllCaps(false); close.setOnClickListener(v -> finish()); root.addView(close);
        updateFilterButtons(); return root;
    }

    private void addLogModeToggle(LinearLayout parent, boolean appSelected) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        Button app = new Button(this); app.setText("App Log"); app.setAllCaps(false);
        Button server = new Button(this); server.setText("Server Log"); server.setAllCaps(false);
        styleLogModeButton(app, appSelected); styleLogModeButton(server, !appSelected);
        app.setOnClickListener(v -> { startActivity(new Intent(this, LogActivity.class)); overridePendingTransition(0, 0); finish(); });
        server.setOnClickListener(v -> {});
        row.addView(app, new LinearLayout.LayoutParams(0, dp(40), 1f));
        LinearLayout.LayoutParams serverParams = new LinearLayout.LayoutParams(0, dp(40), 1f); serverParams.leftMargin = dp(6); row.addView(server, serverParams);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); params.bottomMargin = dp(8); parent.addView(row, params);
    }

    private void styleLogModeButton(Button button, boolean selected) {
        button.setTextColor(selected ? Color.WHITE : Color.rgb(190, 190, 190));
        button.setBackgroundResource(selected ? R.drawable.lm_blue_add_pill_background : R.drawable.lm_glass_button_background);
    }

    private void addFilter(LinearLayout row, String label, String filter) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setOnClickListener(v -> { selectedFilter = filter; updateFilterButtons(); render(); });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); params.setMargins(dp(2), 0, dp(2), 0); row.addView(button, params); filterButtons.add(button);
    }
    private void updateFilterButtons() {
        String[] filters = {"ALL", "ERRORS", "WARNINGS", "SUCCESS"};
        for (int i = 0; i < filterButtons.size(); i++) { boolean selected = filters[i].equals(selectedFilter); filterButtons.get(i).setTextColor(selected ? Color.WHITE : Color.rgb(190, 190, 190)); filterButtons.get(i).setBackgroundTintList(android.content.res.ColorStateList.valueOf(selected ? 0xff455a64 : 0xff242424)); }
    }
    private void addAction(LinearLayout row, String label, View.OnClickListener click) { Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setOnClickListener(click); button.setTextSize(12); row.addView(button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); }

    private void refresh() {
        executor.execute(() -> { try {
            JSONObject response = new OracleControlClient(this).get("/logs?limit=1000");
            JSONArray lines = response.optJSONArray("lines"), levels = response.optJSONArray("levels");
            int offset = response.optInt("utc_offset_seconds", 0); List<Entry> loaded = new ArrayList<>();
            if (lines != null) for (int i = 0; i < lines.length(); i++) {
                String line = lines.optString(i); String level = levels != null && i < levels.length() && !levels.isNull(i) ? levels.optString(i) : null;
                loaded.add(new Entry(ServerLogFormatter.convertTime(line, offset, TimeZone.getDefault()), ServerLogFormatter.levelFor(line, level)));
            }
            runOnUiThread(() -> { entries.clear(); entries.addAll(loaded); render(); });
        } catch (Exception error) { runOnUiThread(() -> showLoadError(error)); }});
    }
    private void showLoadError(Exception error) {
        subtitle.setText("Unable to load server log");
        logText.setText(error instanceof OracleControlClient.ApiException && ((OracleControlClient.ApiException) error).getStatusCode() == 401
            ? "Unauthorized — check the Server API token." : "Cannot reach server. Check Tailscale, the server address, and server status.");
        logText.setTextColor(0xfff28b82);
    }
    private void render() {
        SpannableStringBuilder shown = new SpannableStringBuilder(); int count = 0;
        for (Entry entry : entries) if (ServerLogFormatter.matchesFilter(entry.level, selectedFilter)) {
            int start = shown.length(); shown.append(entry.text).append('\n');
            shown.setSpan(new ForegroundColorSpan(colorFor(entry.level)), start, shown.length(), 0); count++;
        }
        logText.setText(shown.length() == 0 ? "No log entries." : shown); subtitle.setText(count + " entries shown · times shown in your phone's time zone"); logText.setTextColor(Color.rgb(224, 224, 224));
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }
    private int colorFor(String level) { if ("ERROR".equals(level)) return 0xffff6b6b; if ("WARNING".equals(level)) return 0xffffc857; if ("SUCCESS".equals(level)) return 0xff81c784; return 0xffe0e0e0; }
    private String visibleText() { SpannableStringBuilder text = new SpannableStringBuilder(); for (Entry entry : entries) if (ServerLogFormatter.matchesFilter(entry.level, selectedFilter)) text.append(entry.text).append('\n'); return text.toString(); }
    private void copyLog() { String text = visibleText(); if (text.trim().isEmpty()) { Toast.makeText(this, "Log is empty.", Toast.LENGTH_SHORT).show(); return; }
        if (text.length() <= ClipboardLogSplitter.MAX_CLIPBOARD_CHARS_PER_PART) { copyText("LiveMonitor Server Log", text); Toast.makeText(this, "Full log copied.", Toast.LENGTH_SHORT).show(); } else showChunkedCopyDialog(ClipboardLogSplitter.split(text)); }
    private void saveLog() { String text = visibleText(); if (text.trim().isEmpty()) { Toast.makeText(this, "Log is empty.", Toast.LENGTH_SHORT).show(); return; }
        try { String name = LogFileSaver.save(this, storage, text, "LiveMonitor_ServerLog_"); Toast.makeText(this, "Log saved: " + name, Toast.LENGTH_LONG).show(); }
        catch (Exception error) { Toast.makeText(this, "Failed to save log: " + error.getMessage(), Toast.LENGTH_LONG).show(); }
    }
    private void confirmClear() { new AlertDialog.Builder(this).setTitle("Clear server log?").setMessage("This clears the log kept in the server's memory. Recordings are not affected.").setNegativeButton("Cancel", null).setPositiveButton("Clear", (d, w) -> clearServerLog()).show(); }
    private void clearServerLog() { executor.execute(() -> { try { new OracleControlClient(this).delete("/logs"); runOnUiThread(() -> { Toast.makeText(this, "Server log cleared.", Toast.LENGTH_SHORT).show(); refresh(); }); }
        catch (Exception error) { runOnUiThread(() -> { if (error instanceof OracleControlClient.ApiException && (((OracleControlClient.ApiException) error).getStatusCode() == 404 || ((OracleControlClient.ApiException) error).getStatusCode() == 405)) Toast.makeText(this, "This server version can't clear logs yet. Update the server.", Toast.LENGTH_LONG).show(); else Toast.makeText(this, readableError(error), Toast.LENGTH_LONG).show(); }); }}); }
    private String readableError(Exception error) { return error instanceof OracleControlClient.ApiException && ((OracleControlClient.ApiException) error).getStatusCode() == 401 ? "Unauthorized — check the Server API token." : "Cannot reach server. Check Tailscale, the server address, and server status."; }
    private void showChunkedCopyDialog(List<ClipboardLogSplitter.Part> chunks) {
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(12), 0, dp(12), 0);
        TextView help = new TextView(this); help.setText("Choose Copy to place one safe-sized part on the clipboard, or View/Select to manually select exact lines."); help.setPadding(0, 0, 0, dp(8)); content.addView(help);
        for (int i = 0; i < chunks.size(); i++) { final int index = i; ClipboardLogSplitter.Part part = chunks.get(i);
            TextView label = new TextView(this); label.setText("Part " + (i + 1) + " of " + chunks.size() + ": lines " + part.getStartLine() + "–" + part.getEndLine() + " (" + part.length() + " chars)"); label.setPadding(0, dp(8), 0, dp(4)); content.addView(label);
            LinearLayout row = new LinearLayout(this); Button copy = new Button(this); copy.setAllCaps(false); copy.setText("Copy"); copy.setOnClickListener(v -> { copyText("LiveMonitor Server Log part " + (index + 1), chunks.get(index).getText()); Toast.makeText(this, "Copied log part " + (index + 1) + " of " + chunks.size() + ".", Toast.LENGTH_SHORT).show(); }); row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
            Button view = new Button(this); view.setAllCaps(false); view.setText("View/Select"); view.setOnClickListener(v -> showSelectablePart(chunks, index)); LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, -2, 1f); vp.leftMargin = dp(8); row.addView(view, vp); content.addView(row);
        }
        ScrollView scroll = new ScrollView(this); scroll.addView(content); new AlertDialog.Builder(this).setTitle("Log split into " + chunks.size() + " parts").setView(scroll).setNegativeButton("Close", null).show();
    }
    private void showSelectablePart(List<ClipboardLogSplitter.Part> chunks, int index) { TextView text = new TextView(this); text.setText(chunks.get(index).getText()); text.setTextIsSelectable(true); text.setTextSize(12); text.setPadding(dp(12), dp(12), dp(12), dp(12)); ScrollView scroll = new ScrollView(this); scroll.addView(text);
        new AlertDialog.Builder(this).setTitle("Log part " + (index + 1) + " of " + chunks.size()).setView(scroll).setPositiveButton("Copy this part", (d, w) -> { copyText("LiveMonitor Server Log part " + (index + 1), chunks.get(index).getText()); Toast.makeText(this, "Copied log part " + (index + 1) + ".", Toast.LENGTH_SHORT).show(); }).setNegativeButton("Close", null).show(); }
    private void copyText(String label, String text) { ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE); clipboard.setPrimaryClip(ClipData.newPlainText(label, text)); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static final class Entry { final String text, level; Entry(String text, String level) { this.text = text; this.level = level.toUpperCase(Locale.US); } }
}
