package com.livemonitor.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
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
    private LinearLayout selectionBar;
    private TextView selectionSummaryView;
    private Button selectAllButton;
    private Button deselectAllButton;
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

            @Override
            public void onSelectionChanged(int selectedCount) {
                updateSelectionBar(selectedCount);
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
        root.setBackgroundResource(R.drawable.lm_screen_background);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, 0, 0, dp(8));

        ImageButton backButton = new ImageButton(this);
        backButton.setImageResource(R.drawable.ic_arrow_back_24);
        backButton.setColorFilter(getResources().getColor(R.color.lm_text_primary));
        backButton.setBackgroundColor(Color.TRANSPARENT);
        backButton.setContentDescription("Back");
        backButton.setOnClickListener(v -> finish());
        titleRow.addView(backButton, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView title = new TextView(this);
        title.setText("Past Recordings");
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setTextColor(getResources().getColor(R.color.lm_text_primary));
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(
            titleRow,
            new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        );

        summaryView = new TextView(this);
        summaryView.setTextColor(getResources().getColor(R.color.green));
        summaryView.setTextSize(14);
        summaryView.setPadding(0, 0, 0, dp(8));
        root.addView(summaryView);

        searchInput = new android.widget.EditText(this);
        searchInput.setHint("Search by channel name");
        searchInput.setTextColor(getResources().getColor(R.color.lm_text_primary));
        searchInput.setHintTextColor(getResources().getColor(R.color.lm_text_tertiary));
        searchInput.setTextSize(18);
        searchInput.setSingleLine(true);
        searchInput.setGravity(Gravity.CENTER);
        searchInput.setPadding(dp(18), 0, dp(18), 0);
        searchInput.setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.ic_menu_search, 0);
        searchInput.setCompoundDrawablePadding(dp(10));
        searchInput.setBackgroundResource(R.drawable.lm_input_background);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { applyFilter(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        root.addView(searchInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        LinearLayout folderActions = new LinearLayout(this);
        folderActions.setOrientation(LinearLayout.HORIZONTAL);
        folderActions.setPadding(0, dp(10), 0, 0);

        Button refreshFolderButton = new Button(this);
        refreshFolderButton.setAllCaps(false);
        refreshFolderButton.setText("Refresh selected folder");
        styleCyberButton(refreshFolderButton);
        refreshFolderButton.setOnClickListener(v -> importSelectedFolderRecordings(true));

        Button openFolderButton = new Button(this);
        openFolderButton.setAllCaps(false);
        openFolderButton.setText("Open folder");
        styleCyberButton(openFolderButton);
        openFolderButton.setOnClickListener(v -> openSelectedFolder());

        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(0, dp(50), 1f);
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(0, dp(50), 1f);
        openParams.leftMargin = dp(10);
        folderActions.addView(refreshFolderButton, refreshParams);
        folderActions.addView(openFolderButton, openParams);
        root.addView(folderActions);

        selectionBar = new LinearLayout(this);
        selectionBar.setOrientation(LinearLayout.HORIZONTAL);
        selectionBar.setGravity(Gravity.CENTER_VERTICAL);
        selectionBar.setPadding(0, dp(10), 0, dp(8));

        selectAllButton = new Button(this);
        selectAllButton.setAllCaps(false);
        selectAllButton.setText("✓");
        styleIconButton(selectAllButton);
        selectAllButton.setContentDescription("Select all");
        selectAllButton.setOnClickListener(v -> selectAllRecordings());

        Button deleteSelectedButton = new Button(this);
        deleteSelectedButton.setAllCaps(false);
        deleteSelectedButton.setText("🗑");
        styleIconButton(deleteSelectedButton);
        deleteSelectedButton.setContentDescription("Delete selected");
        deleteSelectedButton.setOnClickListener(v -> confirmDeleteSelectedFiles());

        Button detailsButton = new Button(this);
        detailsButton.setAllCaps(false);
        detailsButton.setText("i");
        styleIconButton(detailsButton);
        detailsButton.setContentDescription("Details");
        detailsButton.setOnClickListener(v -> showSelectedDetails());

        deselectAllButton = new Button(this);
        deselectAllButton.setAllCaps(false);
        deselectAllButton.setText("×");
        styleIconButton(deselectAllButton);
        deselectAllButton.setContentDescription("Deselect all");
        deselectAllButton.setOnClickListener(v -> adapter.clearSelection());

        selectionSummaryView = new TextView(this);
        selectionSummaryView.setTextColor(getResources().getColor(R.color.lm_text_secondary));
        selectionSummaryView.setTextSize(16);
        selectionSummaryView.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);

        selectionBar.addView(selectAllButton, new LinearLayout.LayoutParams(dp(56), dp(56)));
        selectionBar.addView(deleteSelectedButton, spacedIconParams());
        selectionBar.addView(detailsButton, spacedIconParams());
        selectionBar.addView(deselectAllButton, spacedIconParams());
        selectionBar.addView(selectionSummaryView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        selectionBar.setVisibility(android.view.View.GONE);
        root.addView(selectionBar);

        emptyView = new TextView(this);
        emptyView.setText("No history yet. Completed and stopped recordings will appear here.");
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextSize(15);
        emptyView.setTextColor(getResources().getColor(R.color.lm_text_tertiary));

        listView = new ListView(this);
        listView.setAdapter(adapter);
        listView.setEmptyView(emptyView);
        listView.setDividerHeight(0);
        listView.setPadding(0, dp(8), 0, dp(16));
        listView.setClipToPadding(false);
        listView.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        listView.setBackgroundColor(Color.TRANSPARENT);
        listView.setCacheColorHint(Color.TRANSPARENT);

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

    private LinearLayout.LayoutParams spacedIconParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(56), dp(56));
        params.leftMargin = dp(10);
        return params;
    }

    private void styleCyberButton(Button button) {
        button.setTextColor(getResources().getColor(R.color.lm_text_secondary));
        button.setTextSize(16);
        button.setBackgroundResource(R.drawable.lm_glass_button_background);
        button.setStateListAnimator(null);
        button.setMinHeight(0);
        button.setMinWidth(0);
    }

    private void styleIconButton(Button button) {
        button.setTextColor(getResources().getColor(R.color.lm_text_primary));
        button.setTextSize(28);
        button.setBackgroundResource(R.drawable.lm_glass_button_background);
        button.setStateListAnimator(null);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(0, 0, 0, 0);
    }

    private void refreshFiles() {
        storage.removeEmptyOrUnplayableFinishedRecordings();

        allCompleted = storage.loadCompletedRecordings();
        sortNewestFirst(allCompleted);
        updateSummary(allCompleted);
        applyFilter();
    }

    private void importSelectedFolderRecordings(boolean showToast) {
        SelectedFolderRecordingImporter.Result result = new SelectedFolderRecordingImporter(this).importFromSelectedFolder();
        storage.appendLog(new LogItem(LogItem.LEVEL_INFO, LogItem.SOURCE_UI, "", "", "", "", "Selected save folder refreshed.", result.getMessage()));
        if (showToast) {
            Toast.makeText(this, result.getMessage(), Toast.LENGTH_LONG).show();
        }
        allCompleted = storage.loadCompletedRecordings();
        sortNewestFirst(allCompleted);
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
        updateSelectionBar(0);
    }

    private void sortNewestFirst(List<RecordingItem> recordings) {
        if (recordings == null) {
            return;
        }
        recordings.sort((left, right) -> Long.compare(recordingSortTime(right), recordingSortTime(left)));
    }

    private long recordingSortTime(RecordingItem recording) {
        if (recording == null) {
            return 0L;
        }
        String path = recording.getBestPlayablePath();
        if (path != null && !path.trim().isEmpty()) {
            try {
                File file = new File(path);
                if (file.exists()) {
                    return file.lastModified();
                }
            } catch (Exception ignored) {
                // Fall back to stored timestamps below.
            }
        }
        if (recording.getFinishedAt() > 0L) return recording.getFinishedAt();
        if (recording.getUpdatedAt() > 0L) return recording.getUpdatedAt();
        return recording.getCreatedAt();
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

    private void updateSelectionBar(int selectedCount) {
        if (selectionBar == null || selectionSummaryView == null) {
            return;
        }
        boolean hasSelection = selectedCount > 0;
        selectionSummaryView.setText(hasSelection ? selectedCount + " selected" : "");
        selectionBar.setVisibility(hasSelection ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void selectAllRecordings() {
        adapter.selectAll();
    }

    private void showSelectedDetails() {
        List<RecordingItem> selected = adapter.getSelectedRecordings();
        if (selected.isEmpty()) {
            Toast.makeText(this, "Long-press a recording to select it first.", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder message = new StringBuilder();
        for (RecordingItem recording : selected) {
            if (message.length() > 0) message.append("\n\n");
            message.append(UiTextUtils.formatRecordingDetailsLikeMx(recording));
        }
        new AlertDialog.Builder(this)
            .setTitle(selected.size() == 1 ? "Recording details" : "Selected recording details")
            .setMessage(message.toString())
            .setPositiveButton("OK", null)
            .show();
    }

    private void confirmDeleteSelectedFiles() {
        List<RecordingItem> selected = adapter.getSelectedRecordings();
        if (selected.isEmpty()) {
            Toast.makeText(this, "Long-press recordings to select them first.", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Delete selected files?")
            .setMessage("Delete " + selected.size() + " selected recording file(s) from storage?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", (dialog, which) -> deleteSelectedFiles(selected))
            .show();
    }

    private void deleteSelectedFiles(List<RecordingItem> selected) {
        boolean allDeleted = true;
        for (RecordingItem recording : selected) {
            if (recording == null) continue;
            boolean finalDeleted = deletePathIfPresent(recording.getFinalMp4Path());
            boolean tempDeleted = deletePathIfPresent(recording.getTempTsPath());
            allDeleted = allDeleted && finalDeleted && tempDeleted;
            if (finalDeleted && tempDeleted) {
                storage.removeRecording(recording.getId());
            }
        }
        adapter.clearSelection();
        storage.appendLog(LogItem.info(LogItem.SOURCE_UI, "Selected downloaded files deleted."));
        refreshFiles();
        Toast.makeText(this, allDeleted ? "Selected files deleted." : "Could not delete one or more files.", Toast.LENGTH_LONG).show();
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
            if (isContentUri(path)) {
                return DocumentsContract.deleteDocument(getContentResolver(), Uri.parse(path.trim()));
            }
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

        try {
            Uri uri;
            if (isContentUri(path)) {
                uri = Uri.parse(path.trim());
            } else {
                File file = new File(path);

                if (!file.exists()) {
                    Toast.makeText(this, "File not found.", Toast.LENGTH_SHORT).show();
                    return;
                }

                uri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    file
                );
            }

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

    private static boolean isContentUri(String path) {
        return path != null && path.trim().toLowerCase(java.util.Locale.US).startsWith("content://");
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
