package com.hippo.ehviewer.ui.fragment;

import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.TwoStatePreference;

import com.hippo.ehviewer.Settings;

/** Keeps the main thumbnail info switch in sync with its two visible rows. */
final class ThumbnailInfoBarPreferences {
    private ThumbnailInfoBarPreferences() {}

    static void bind(PreferenceFragmentCompat fragment, Runnable onChanged) {
        TwoStatePreference bar = fragment.findPreference(Settings.KEY_SHOW_THUMBNAIL_INFO_BAR);
        TwoStatePreference title = fragment.findPreference(Settings.KEY_SHOW_THUMBNAIL_INFO_TITLE);
        TwoStatePreference details = fragment.findPreference(Settings.KEY_SHOW_THUMBNAIL_INFO_DETAILS);
        TwoStatePreference highlight = fragment.findPreference(Settings.KEY_SHOW_THUMBNAIL_INFO_HIGHLIGHT);
        if (bar == null || title == null || details == null) {
            return;
        }

        bar.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean enabled = Boolean.TRUE.equals(newValue);
            if (enabled && !title.isChecked() && !details.isChecked()) {
                details.setChecked(true);
            } else if (!enabled) {
                title.setChecked(false);
                details.setChecked(false);
            }
            onChanged.run();
            return true;
        });

        title.setOnPreferenceChangeListener((preference, newValue) -> {
            bar.setChecked(Boolean.TRUE.equals(newValue) || details.isChecked());
            onChanged.run();
            return true;
        });
        details.setOnPreferenceChangeListener((preference, newValue) -> {
            bar.setChecked(Boolean.TRUE.equals(newValue) || title.isChecked());
            onChanged.run();
            return true;
        });
        if (highlight != null) {
            highlight.setOnPreferenceChangeListener((preference, newValue) -> {
                onChanged.run();
                return true;
            });
        }
    }
}
