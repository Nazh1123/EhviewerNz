package com.hippo.ehviewer.ui.fragment;

import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.TwoStatePreference;

import com.hippo.ehviewer.Settings;

public final class SubscriptionSidebarPreferences {
    private static final String[] KEYS = {
            Settings.KEY_SHOW_SUBSCRIPTION_BUTTONS,
            Settings.KEY_SHOW_BOOKMARK_SUBSCRIPTION,
            Settings.KEY_SHOW_GLOBAL_SUBSCRIPTION,
            Settings.KEY_SHOW_UPDATE_SUBSCRIPTION
    };

    private SubscriptionSidebarPreferences() {}

    public static void bind(PreferenceFragmentCompat fragment, Runnable onChanged) {
        sync(fragment);
        for (String key : KEYS) {
            TwoStatePreference preference = fragment.findPreference(key);
            if (preference == null) continue;
            preference.setOnPreferenceChangeListener((ignored, value) -> {
                Settings.putSubscriptionButtonVisibility(key, (boolean) value);
                sync(fragment);
                onChanged.run();
                return false;
            });
        }
    }

    private static void sync(PreferenceFragmentCompat fragment) {
        boolean[] values = {Settings.getShowSubscriptionButtons(),
                Settings.getShowBookmarkSubscription(), Settings.getShowGlobalSubscription(),
                Settings.getShowUpdateSubscription()};
        for (int i = 0; i < KEYS.length; i++) {
            TwoStatePreference preference = fragment.findPreference(KEYS[i]);
            if (preference != null) preference.setChecked(values[i]);
        }
    }
}
