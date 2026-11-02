package com.hippo.ehviewer.ui.fragment;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.ui.MainActivity;
import com.hippo.ehviewer.ui.scene.history.GalleryUpdateHistoryScene;
import com.hippo.ehviewer.ui.scene.history.ReadingHistoryScene;
import com.hippo.scene.StageActivity;

public class HistoryFragment extends BasePreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.history_settings, rootKey);
        ReadingHistoryPreferences.bind(this);
        GalleryUpdateRecordPreferences.bind(this);
        findPreference(Settings.KEY_COLLAPSE_HISTORY_IN_SIDEBAR).setOnPreferenceChangeListener(
                (preference, value) -> {
                    requireActivity().setResult(Activity.RESULT_OK);
                    return true;
                });
        findPreference(Settings.KEY_SHOW_UPDATE_HISTORY_IN_SIDEBAR).setOnPreferenceChangeListener(
                (preference, value) -> {
                    requireActivity().setResult(Activity.RESULT_OK);
                    return true;
                });
        findPreference(Settings.KEY_RECORD_READING_HISTORY).setOnPreferenceChangeListener(
                (preference, value) -> {
                    requireActivity().setResult(Activity.RESULT_OK);
                    return true;
                });
        findPreference(Settings.KEY_SHOW_VIEWED_GALLERIES).setOnPreferenceChangeListener(
                (preference, value) -> {
                    requireActivity().setResult(Activity.RESULT_OK);
                    return true;
                });
        findPreference("view_reading_history").setOnPreferenceClickListener(preference ->
                openHistory(ReadingHistoryScene.class));
        findPreference("view_gallery_update_history").setOnPreferenceClickListener(preference ->
                openHistory(GalleryUpdateHistoryScene.class));
        findPreference(DownloadFragment.KEY_UPDATE_GALLERY_VERSIONS)
                .setOnPreferenceClickListener(preference -> {
                    GalleryVersionMaintenance.showUpdateConfirmation(requireActivity());
                    return true;
                });
    }

    private boolean openHistory(Class<?> scene) {
        Intent intent = new Intent(requireContext(), MainActivity.class);
        intent.setAction(StageActivity.ACTION_START_SCENE);
        intent.putExtra(StageActivity.KEY_SCENE_NAME, scene.getName());
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        requireActivity().finish();
        return true;
    }
}
