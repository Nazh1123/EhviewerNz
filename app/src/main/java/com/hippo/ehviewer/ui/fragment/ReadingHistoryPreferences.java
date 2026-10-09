package com.hippo.ehviewer.ui.fragment;

import androidx.preference.PreferenceFragmentCompat;

import com.hippo.ehviewer.Settings;
import com.hippo.preference.ListPreference;

/** Initialize the custom list preference with the same validated limit used by storage. */
public final class ReadingHistoryPreferences {
    private ReadingHistoryPreferences() {}

    public static void bind(PreferenceFragmentCompat fragment) {
        ListPreference limit = fragment.findPreference(Settings.KEY_READING_HISTORY_SIZE);
        if (limit == null) return;
        limit.setValue(Integer.toString(Settings.getReadingHistorySize()));
        limit.setOnPreferenceChangeListener((preference, value) -> {
            try {
                Settings.setReadingHistorySize(Math.max(Settings.DEFAULT_HISTORY_INFO_SIZE,
                        Integer.parseInt(value.toString())));
                return true;
            } catch (NumberFormatException ignored) {
                return false;
            }
        });
    }
}
