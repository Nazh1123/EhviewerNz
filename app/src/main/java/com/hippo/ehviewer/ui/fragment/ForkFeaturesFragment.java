package com.hippo.ehviewer.ui.fragment;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.ui.MainActivity;
import com.hippo.ehviewer.ui.scene.gallery.list.QuickSearchScene;
import com.hippo.scene.StageActivity;
import com.hippo.unifile.UniFile;

public class ForkFeaturesFragment extends BasePreferenceFragmentCompat {

    private static final String KEY_MANUAL_IMAGE_SAVE_LOCATION =
            "manual_image_save_location";
    private static final int REQUEST_CODE_PICK_MANUAL_IMAGE_DIR = 0;
    private static final int REQUEST_CODE_PICK_MANUAL_IMAGE_DIR_L = 1;
    private static final String KEY_BOOKMARK_SUBSCRIPTION_SETTINGS =
            "bookmark_subscription_settings";
    private static final String KEY_DELETE_EMPTY_GALLERIES =
            "fork_delete_empty_galleries";

    @Nullable
    private Preference mManualImageSaveLocation;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState,
                                    @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.fork_features_settings);
        mManualImageSaveLocation = findPreference(KEY_MANUAL_IMAGE_SAVE_LOCATION);
        Preference showThumbnailDownloadBadge =
                findPreference(Settings.KEY_SHOW_THUMBNAIL_DOWNLOAD_BADGE);
        Preference showThumbnailInfoBar =
                findPreference(Settings.KEY_SHOW_THUMBNAIL_INFO_BAR);
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
        if (showThumbnailInfoBar != null) {
            showThumbnailInfoBar.setOnPreferenceChangeListener(
                    galleryListPreferenceListener);
        }
        if (galleryLongPressQuickDownload != null) {
            galleryLongPressQuickDownload.setOnPreferenceChangeListener(
                    galleryListPreferenceListener);
        }
        Preference bookmarkSubscriptionSettings =
                findPreference(KEY_BOOKMARK_SUBSCRIPTION_SETTINGS);
        if (bookmarkSubscriptionSettings != null) {
            bookmarkSubscriptionSettings.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(requireContext(), MainActivity.class);
                intent.setAction(StageActivity.ACTION_START_SCENE);
                intent.putExtra(StageActivity.KEY_SCENE_NAME,
                        QuickSearchScene.class.getName());
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP |
                        Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                requireActivity().finish();
                return true;
            });
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
                SettingsDirectoryPicker.selectLocation(this,
                        Settings.getManualImageSaveLocation(),
                        REQUEST_CODE_PICK_MANUAL_IMAGE_DIR,
                        REQUEST_CODE_PICK_MANUAL_IMAGE_DIR_L);
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
        if (mManualImageSaveLocation == null) {
            return;
        }
        UniFile directory = Settings.getManualImageSaveLocation();
        if (directory != null) {
            mManualImageSaveLocation.setSummary(directory.getUri().toString());
        } else {
            mManualImageSaveLocation.setSummary(
                    R.string.settings_download_invalid_download_location);
        }
    }

}
