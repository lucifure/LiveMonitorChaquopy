package com.livemonitor.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.util.List;

/**
 * Downloaded files screen from 3-dot menu.
 *
 * Shows completed playable recordings only.
 */
public class DownloadedFilesActivity extends AppCompatActivity {

    private AppStorage storage;
    private RecordingAdapter adapter;
    private TextView emptyView;
    private ListView listView;
    private TextView summaryView;
    private android.widget.EditText searchInput;
    private List<RecordingItem> allCompleted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        storage = new AppStorage(this);
        adapter = new RecordingAdapter(this);
        adapter.setMode(RecordingAdapter.Mode.DOWNLOADED);
        adapter.setListener(new RecordingAdapter.Listener() {
            @Override
            public void onRecordingClicked(RecordingItem recording) {
                openRecording(recording);
            }

            @Override
            public void onOpenFileClicked(RecordingItem recording) {
                openRecording(recording);
            }

            @Override
            public void onPauseResumeClicked(RecordingItem recording) {
                // Downloaded files are read-only from this screen.
            }

            @Override
            public void onDeleteClicked(RecordingItem recording) {
                confirmDeleteDownloadedFile(recording);
            }
        });

        setTitle("Past Recordings");
        setContentView(buildContentView());

        refreshFiles();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshFiles();
    }

    private LinearLayout buildContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackgroundColor(Color.rgb(15, 15, 15));

        TextView title = new TextView(this);
        title.setText("Past Recordings");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(0, 0, 0, dp(8));
        title.setTextColor(Color.WHITE);

        root.addView(
            title,
            new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        );

        summaryView = new TextView(this);
        summaryView.setTextColor(Color.rgb(22, 199, 132));
        summaryView.setTextSize(14);
        summaryView.setPadding(0, 0, 0, dp(8));
        root.addView(summaryView);

        searchInput = new android.widget.EditText(this);
        searchInput.setHint("Search by channel name");
        searchInput.setTextColor(Color.WHITE);
        searchInput.setHintTextColor(Color.rgb(102, 102, 102));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { applyFilter(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        root.addView(searchInput);

        TextView filters = new TextView(this);
        filters.setText("All   Completed   Failed   Stopped");
        filters.setTextColor(Color.rgb(190, 190, 190));
        filters.setPadding(0, dp(8), 0, dp(8));
        root.addView(filters);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        Button refreshButton = new Button(this);
        refreshButton.setAllCaps(false);
        refreshButton.setText("Refresh");
        refreshButton.setOnClickListener(v -> refreshFiles());
        Button openFolderButton = new Button(this);
        openFolderButton.setAllCaps(false);
        openFolderButton.setText("Open folder");
        openFolderButton.setOnClickListener(v -> openSelectedFolder());
        actionRow.addView(refreshButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        actionRow.addView(openFolderButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(actionRow);

        emptyView = new TextView(this);
        emptyView.setText("No history yet. Completed and stopped recordings will appear here.");
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextSize(15);
        emptyView.setTextColor(Color.rgb(102, 102, 102));

        listView = new ListView(this);
        listView.setAdapter(adapter);
        listView.setEmptyView(emptyView);
        listView.setDividerHeight(dp(12));
        listView.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        listView.setBackgroundColor(Color.rgb(15, 15, 15));

        root.addView(
            emptyView,
            new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        );

        root.addView(
            listView,
            new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        );

        return root;
    }

    private void refreshFiles() {
        storage.removeEmptyOrUnplayableFinishedRecordings();

        allCompleted = storage.loadCompletedRecordings();
        updateSummary(allCompleted);
        applyFilter();
    }

    private void applyFilter() {
        java.util.ArrayList<RecordingItem> filtered = new java.util.ArrayList<>();
        String query = searchInput == null ? "" : searchInput.getText().toString().trim().toLowerCase(java.util.Locale.US);
        if (allCompleted != null) {
            for (RecordingItem recording : allCompleted) {
                if (recording == null) continue;
                if (query.isEmpty() || recording.getDisplayTitle().toLowerCase(java.util.Locale.US).contains(query)) {
                    filtered.add(recording);
                }
            }
        }
        adapter.setRecordings(filtered);
    }

    private void updateSummary(List<RecordingItem> recordings) {
        long totalBytes = 0L;
        int count = recordings == null ? 0 : recordings.size();
        if (recordings != null) {
            for (RecordingItem recording : recordings) {
                if (recording != null) totalBytes += recording.getBytesRecorded();
            }
        }
        summaryView.setText(count + " past recordings · " + RecordingProgressTracker.formatBytes(totalBytes) + " used");
    }

    private void openSelectedFolder() {
        AppSettings settings = storage.loadSettings();
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            if (!settings.getSaveLocationUri().trim().isEmpty()) {
                intent.setData(Uri.parse(settings.getSaveLocationUri()));
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(intent);
            } else {
                Toast.makeText(this, "Using app storage. Select a folder in Settings to open it here.", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Unable to open folder: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void confirmDeleteDownloadedFile(RecordingItem recording) {
        if (recording == null) {
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("Delete downloaded file?")
            .setMessage("Do you want to delete this file from storage and remove it from Downloaded Files?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", (dialog, which) -> deleteDownloadedFile(recording))
            .show();
    }

    private void deleteDownloadedFile(RecordingItem recording) {
        if (recording == null) {
            return;
        }

        boolean finalDeleted = deletePathIfPresent(recording.getFinalMp4Path());
        boolean tempDeleted = deletePathIfPresent(recording.getTempTsPath());

        if (!finalDeleted || !tempDeleted) {
            Toast.makeText(this, "Could not delete one or more files.", Toast.LENGTH_LONG).show();
            return;
        }

        storage.removeRecording(recording.getId());
        storage.appendLog(LogItem.info(LogItem.SOURCE_UI, "Downloaded file deleted."));
        refreshFiles();
        Toast.makeText(this, "Downloaded file deleted.", Toast.LENGTH_SHORT).show();
    }

    private boolean deletePathIfPresent(String path) {
        if (path == null || path.trim().isEmpty()) {
            return true;
        }

        try {
            File file = new File(path);
            return !file.exists() || file.delete();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void openRecording(RecordingItem recording) {
        if (recording == null) {
            return;
        }

        String path = recording.getBestPlayablePath();

        if (path.trim().isEmpty()) {
            Toast.makeText(this, "File not found.", Toast.LENGTH_SHORT).show();
            return;
        }

        File file = new File(path);

        if (!file.exists()) {
            Toast.makeText(this, "File not found.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Uri uri = FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                file
            );

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, path.endsWith(".ts") ? "video/mp2t" : "video/mp4");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(Intent.createChooser(intent, "Open recording"));
        } catch (Exception e) {
            Toast.makeText(
                this,
                "Unable to open file: " + e.getMessage(),
                Toast.LENGTH_LONG
            ).show();
        }
    }


    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
