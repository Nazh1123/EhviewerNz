package com.hippo.ehviewer.ui.fragment;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.preference.Preference;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.unifile.UniFile;

final class ManualImageSaveLocationPreferences {

    private ManualImageSaveLocationPreferences() {
    }

    static void selectLocation(@NonNull Fragment fragment, int directoryRequestCode,
                               int documentRequestCode, @NonNull Runnable onModeChanged) {
        CharSequence[] choices = {
                fragment.getText(R.string.settings_manual_save_custom),
                fragment.getText(R.string.settings_manual_save_default),
                fragment.getText(R.string.settings_manual_save_follow_download)
        };
        new AlertDialog.Builder(fragment.requireActivity())
                .setTitle(R.string.settings_download_manual_save_location)
                .setSingleChoiceItems(choices, Settings.getManualImageSaveMode(), (dialog, which) -> {
                    dialog.dismiss();
                    if (which == Settings.MANUAL_IMAGE_SAVE_CUSTOM) {
                        // Only commit custom mode after the directory picker succeeds.
                        UniFile current = Settings.getConfiguredManualImageSaveLocation();
                        if (current == null) {
                            current = Settings.getManualImageSaveMode()
                                    == Settings.MANUAL_IMAGE_SAVE_FOLLOW_DOWNLOAD
                                    ? Settings.getDownloadLocation()
                                    : Settings.getManualImageSaveLocation();
                        }
                        SettingsDirectoryPicker.selectLocation(fragment,
                                current,
                                directoryRequestCode, documentRequestCode);
                    } else {
                        Settings.putManualImageSaveMode(which);
                        onModeChanged.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    static void updateSummary(@NonNull Fragment fragment, @Nullable Preference preference) {
        if (preference == null) {
            return;
        }
        int mode = Settings.getManualImageSaveMode();
        int label;
        UniFile directory;
        if (mode == Settings.MANUAL_IMAGE_SAVE_FOLLOW_DOWNLOAD) {
            label = R.string.settings_manual_save_follow_download;
            // Showing settings must not create the child directory, especially on SAF storage.
            UniFile download = Settings.getDownloadLocation();
            if (download != null) {
                preference.setSummary(fragment.getText(label) + "\n"
                        + download.getUri() + "/" + Settings.MANUAL_IMAGE_SAVE_DOWNLOAD_SUBDIR);
                return;
            }
            directory = null;
        } else {
            label = mode == Settings.MANUAL_IMAGE_SAVE_CUSTOM
                    ? R.string.settings_manual_save_custom : R.string.settings_manual_save_default;
            directory = Settings.getManualImageSaveLocation();
        }
        CharSequence path = directory != null ? directory.getUri().toString()
                : fragment.getText(R.string.settings_download_invalid_download_location);
        preference.setSummary(fragment.getText(label) + "\n" + path);
    }
}
