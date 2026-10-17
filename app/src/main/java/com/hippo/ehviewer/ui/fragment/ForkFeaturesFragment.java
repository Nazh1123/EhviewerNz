package com.hippo.ehviewer.ui.fragment;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.EditTextPreference;
import android.widget.Toast;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.SearchPresetQuery;

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
        bindSearchPreset(Settings.KEY_SEARCH_PRESET_TAG_1, Settings.getSearchPresetTag1());
        bindSearchPreset(Settings.KEY_SEARCH_PRESET_TAG_2, Settings.getSearchPresetTag2());
        mManualImageSaveLocation = findPreference(KEY_MANUAL_IMAGE_SAVE_LOCATION);
        Preference showThumbnailDownloadBadge =
                findPreference(Settings.KEY_SHOW_THUMBNAIL_DOWNLOAD_BADGE);
        Preference galleryLongPressQuickDownload =
                findPreference(Settings.KEY_GALLERY_LONG_PRESS_QUICK_DOWNLOAD);
        Preference.OnPreferenceChangeListener galleryListPreferenceListener =
                (preference, newValue) -> {
                if (getActivity() != null) {
                    getActivity().setResult(Activity.RESULT_OK);
                }
                return true;
            };
        if (showThumbnailDownloadBadge != null) {
            showThumbnailDownloadBadge.setOnPreferenceChangeListener(
                    galleryListPreferenceListener);
        }
        ThumbnailInfoBarPreferences.bind(this, () -> {
            if (getActivity() != null) {
                getActivity().setResult(Activity.RESULT_OK);
            }
        });
        if (galleryLongPressQuickDownload != null) {
            galleryLongPressQuickDownload.setOnPreferenceChangeListener(
                    galleryListPreferenceListener);
        }
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

    private void bindSearchPreset(String key, String value) {
        EditTextPreference preference = findPreference(key);
        if (preference == null) return;
        preference.setText(value);
        preference.setOnBindEditTextListener(editText -> {
            editText.setSingleLine(true);
            editText.setSelectAllOnFocus(true);
            editText.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        });
        preference.setOnPreferenceChangeListener((ignored, newValue) -> {
            if (!SearchPresetQuery.isValid((String) newValue)) {
                Toast.makeText(requireContext(), R.string.settings_search_preset_tag_invalid,
                        Toast.LENGTH_LONG).show();
                return false;
            }
            return true;
        });
    }

}
