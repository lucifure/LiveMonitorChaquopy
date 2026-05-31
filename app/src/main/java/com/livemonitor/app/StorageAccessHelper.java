package com.livemonitor.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Helper for Android Storage Access Framework folder picker.
 *
 * Used by SettingsActivity to choose a save location without broad storage
 * permissions.
 */
public class StorageAccessHelper {

    public interface Callback {
        void onFolderSelected(Uri uri, String displayName);
    }

    private final AppCompatActivity activity;
    private final Callback callback;
    private final ActivityResultLauncher<Intent> folderPickerLauncher;

    public StorageAccessHelper(AppCompatActivity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;
        this.folderPickerLauncher = activity.registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() != Activity.RESULT_OK) {
                    return;
                }

                Intent data = result.getData();

                if (data == null || data.getData() == null) {
                    return;
                }

                Uri uri = data.getData();
                persistPermission(uri, data.getFlags());

                if (callback != null) {
                    callback.onFolderSelected(uri, buildDisplayName(uri));
                }
            }
        );
    }

    public void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);

        folderPickerLauncher.launch(intent);
    }

    private void persistPermission(Uri uri, int flags) {
        if (uri == null) {
            return;
        }

        int takeFlags = flags
            & (Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        try {
            activity.getContentResolver().takePersistableUriPermission(uri, takeFlags);
        } catch (Exception ignored) {
            // Some providers do not support persistable permissions.
        }
    }

    private String buildDisplayName(Uri uri) {
        if (uri == null) {
            return "Default app recordings folder";
        }

        try {
            String treeId = DocumentsContract.getTreeDocumentId(uri);

            if (treeId != null && !treeId.trim().isEmpty()) {
                return treeId;
            }
        } catch (Exception ignored) {
            // Fall back to URI string.
        }

        return uri.toString();
    }
}
