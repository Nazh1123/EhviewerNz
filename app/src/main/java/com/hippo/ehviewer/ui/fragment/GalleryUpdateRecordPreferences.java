package com.hippo.ehviewer.ui.fragment;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.download.GalleryUpdateRecordStore;

/** Both settings entries use the same preference and immediately prune when it is reduced. */
final class GalleryUpdateRecordPreferences {
    private GalleryUpdateRecordPreferences() {}

    static void bind(PreferenceFragmentCompat fragment) {
        Preference preference = fragment.findPreference(Settings.KEY_GALLERY_UPDATE_RECORD_LIMIT);
        if (preference == null) return;
        android.content.Context context = fragment.requireContext().getApplicationContext();
        preference.setOnPreferenceChangeListener((item, value) -> {
            int limit = Integer.parseInt(value.toString());
            Settings.putIntToStr(Settings.KEY_GALLERY_UPDATE_RECORD_LIMIT, limit);
            EhApplication.getExecutorService(context).execute(() ->
                    GalleryUpdateRecordStore.get(context).trim(limit));
            return true;
        });
    }
}
