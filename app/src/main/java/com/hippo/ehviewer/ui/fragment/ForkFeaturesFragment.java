package com.hippo.ehviewer.ui.fragment;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;

public class ForkFeaturesFragment extends BasePreferenceFragmentCompat {

    private static final String KEY_MANUAL_IMAGE_SAVE_LOCATION =
            "manual_image_save_location";
    private static final int REQUEST_CODE_PICK_MANUAL_IMAGE_DIR = 0;
    private static final int REQUEST_CODE_PICK_MANUAL_IMAGE_DIR_L = 1;
    private static final String KEY_DELETE_EMPTY_GALLERIES =
            "fork_delete_empty_galleries";

    @Nullable
    private Preference mManualImageSaveLocation;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState,
                                    @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.fork_features_settings);
        mManualImageSaveLocation = findPreference(KEY_MANUAL_IMAGE_SAVE_LOCATION);
        Preference deleteEmptyGalleries = findPreference(KEY_DELETE_EMPTY_GALLERIES);
        if (deleteEmptyGalleries != null) {
            deleteEmptyGalleries.setOnPreferenceClickListener(preference -> {
                MissingGalleryCleaner.showConfirmation(requireActivity(),
                        R.string.settings_fork_delete_empty_galleries);
                return true;
            });
        }
        Preference updateGalleryVersions =
                findPreference(DownloadFragment.KEY_UPDATE_GALLERY_VERSIONS);
        if (updateGalleryVersions != null) {
            updateGalleryVersions.setOnPreferenceClickListener(preference -> {
                GalleryVersionMaintenance.showUpdateConfirmation(requireActivity());
                return true;
            });
        }
        updateManualSaveLocationSummary();
        if (mManualImageSaveLocation != null) {
            mManualImageSaveLocation.setOnPreferenceClickListener(preference -> {
                ManualImageSaveLocationPreferences.selectLocation(this,
                        REQUEST_CODE_PICK_MANUAL_IMAGE_DIR,
                        REQUEST_CODE_PICK_MANUAL_IMAGE_DIR_L,
                        this::updateManualSaveLocationSummary);
                return true;
            });
        }
    }

    @Override
    public void onDestroy() {
        mManualImageSaveLocation = null;
        super.onDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateManualSaveLocationSummary();
        Preference priority = findPreference(Settings.KEY_MOBILE_BROWSING_PRIORITY);
        if (priority instanceof androidx.preference.TwoStatePreference toggle) {
            toggle.setChecked(Settings.getMobileBrowsingPriority());
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (SettingsDirectoryPicker.handleActivityResult(this,
                requestCode, resultCode, data,
                REQUEST_CODE_PICK_MANUAL_IMAGE_DIR,
                REQUEST_CODE_PICK_MANUAL_IMAGE_DIR_L,
                directory -> {
                    Settings.putManualImageSaveLocation(directory);
                    updateManualSaveLocationSummary();
                })) {
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void updateManualSaveLocationSummary() {
        ManualImageSaveLocationPreferences.updateSummary(this, mManualImageSaveLocation);
    }

}
