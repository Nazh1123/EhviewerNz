package com.hippo.ehviewer.ui.fragment;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.preference.Preference;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.unifile.UniFile;
import com.hippo.util.ExceptionUtils;

public class ForkFeaturesFragment extends BasePreferenceFragmentCompat {

    private static final String KEY_MANUAL_IMAGE_SAVE_LOCATION =
            "manual_image_save_location";

    @Nullable
    private Preference mManualImageSaveLocation;

    private final ActivityResultLauncher<Uri> mManualDirectoryLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null || getActivity() == null) {
                    return;
                }
                try {
                    requireActivity().getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    UniFile directory = UniFile.fromTreeUri(requireContext(), uri);
                    if (directory == null) {
                        showInvalidDirectory();
                        return;
                    }
                    Settings.putManualImageSaveLocation(directory);
                    updateManualSaveLocationSummary();
                } catch (Throwable e) {
                    ExceptionUtils.throwIfFatal(e);
                    showInvalidDirectory();
                }
            });

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState,
                                    @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.fork_features_settings);
        mManualImageSaveLocation = findPreference(KEY_MANUAL_IMAGE_SAVE_LOCATION);
        Preference showThumbnailDownloadBadge =
                findPreference(Settings.KEY_SHOW_THUMBNAIL_DOWNLOAD_BADGE);
        if (showThumbnailDownloadBadge != null) {
            showThumbnailDownloadBadge.setOnPreferenceChangeListener((preference, newValue) -> {
                if (getActivity() != null) {
                    getActivity().setResult(Activity.RESULT_OK);
                }
                return true;
            });
        }
        updateManualSaveLocationSummary();
        if (mManualImageSaveLocation != null) {
            mManualImageSaveLocation.setOnPreferenceClickListener(preference -> {
                Uri initialUri = Settings.getManualImageSaveLocationUri();
                mManualDirectoryLauncher.launch(initialUri);
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

    private void showInvalidDirectory() {
        if (getContext() != null) {
            Toast.makeText(getContext(),
                    R.string.settings_download_cant_get_download_location,
                    Toast.LENGTH_SHORT).show();
        }
    }
}
