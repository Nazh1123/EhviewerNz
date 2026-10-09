package com.hippo.ehviewer.ui.fragment;

import android.app.Application;
import android.os.Bundle;
import android.preference.PreferenceManager;

import androidx.fragment.app.FragmentActivity;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.TwoStatePreference;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE)
public class SubscriptionSidebarPreferencesTest {
    private Harness fragment;

    @Before public void setUp() {
        PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication())
                .edit().clear().commit();
        Settings.initialize(RuntimeEnvironment.getApplication());
        FragmentActivity host = Robolectric.buildActivity(FragmentActivity.class).setup().get();
        host.setTheme(R.style.AppTheme);
        fragment = new Harness();
        host.getSupportFragmentManager().beginTransaction().add(fragment, "settings").commitNow();
    }

    private TwoStatePreference preference(String key) { return fragment.findPreference(key); }
    private void change(String key, boolean value) { preference(key).callChangeListener(value); }

    @Test public void defaultsAndMasterOffPreserveChildChoices() {
        assertTrue(Settings.getShowSubscriptionButtons());
        assertFalse(Settings.getShowBookmarkSubscription());
        assertTrue(Settings.getShowGlobalSubscription());
        assertTrue(Settings.getShowUpdateSubscription());
        change(Settings.KEY_SHOW_SUBSCRIPTION_BUTTONS, false);
        assertFalse(Settings.getShowSubscriptionButtons());
        assertFalse(preference(Settings.KEY_SHOW_GLOBAL_SUBSCRIPTION).isEnabled());
        assertTrue(Settings.getShowGlobalSubscription());
        change(Settings.KEY_SHOW_SUBSCRIPTION_BUTTONS, true);
        assertTrue(preference(Settings.KEY_SHOW_GLOBAL_SUBSCRIPTION).isEnabled());
        assertFalse(Settings.getShowBookmarkSubscription());
    }

    @Test public void disablingLastChildDisablesMasterAndReenablingRestoresGlobalAndUpdate() {
        change(Settings.KEY_SHOW_GLOBAL_SUBSCRIPTION, false);
        assertTrue(Settings.getShowSubscriptionButtons());
        change(Settings.KEY_SHOW_UPDATE_SUBSCRIPTION, false);
        assertFalse(Settings.getShowSubscriptionButtons());
        assertFalse(preference(Settings.KEY_SHOW_SUBSCRIPTION_BUTTONS).isChecked());
        Settings.initialize(RuntimeEnvironment.getApplication());
        assertFalse(Settings.getShowSubscriptionButtons());
        change(Settings.KEY_SHOW_SUBSCRIPTION_BUTTONS, true);
        assertTrue(Settings.getShowSubscriptionButtons());
        assertFalse(preference(Settings.KEY_SHOW_BOOKMARK_SUBSCRIPTION).isChecked());
        assertTrue(preference(Settings.KEY_SHOW_GLOBAL_SUBSCRIPTION).isChecked());
        assertTrue(preference(Settings.KEY_SHOW_UPDATE_SUBSCRIPTION).isChecked());
    }

    @Test public void reenablingMasterRetainsBookmarkOnlyChoice() {
        change(Settings.KEY_SHOW_BOOKMARK_SUBSCRIPTION, true);
        change(Settings.KEY_SHOW_GLOBAL_SUBSCRIPTION, false);
        change(Settings.KEY_SHOW_UPDATE_SUBSCRIPTION, false);
        assertTrue(Settings.getShowSubscriptionButtons());
        change(Settings.KEY_SHOW_SUBSCRIPTION_BUTTONS, false);
        change(Settings.KEY_SHOW_SUBSCRIPTION_BUTTONS, true);
        assertTrue(Settings.getShowBookmarkSubscription());
        assertFalse(Settings.getShowGlobalSubscription());
        assertFalse(Settings.getShowUpdateSubscription());
    }

    public static class Harness extends PreferenceFragmentCompat {
        @Override public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.subscription_settings, rootKey);
            SubscriptionSidebarPreferences.bind(this, () -> {});
        }
    }
}
