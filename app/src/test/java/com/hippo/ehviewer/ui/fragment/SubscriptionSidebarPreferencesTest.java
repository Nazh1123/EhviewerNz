package com.hippo.ehviewer.ui.fragment;

import android.app.Application;
import android.os.Bundle;
import android.preference.PreferenceManager;

import androidx.fragment.app.FragmentActivity;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceCategory;
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
                .edit().clear().putBoolean("show_subscription_buttons", false).commit();
        Settings.initialize(RuntimeEnvironment.getApplication());
        FragmentActivity host = Robolectric.buildActivity(FragmentActivity.class).setup().get();
        host.setTheme(R.style.AppTheme);
        fragment = new Harness();
        host.getSupportFragmentManager().beginTransaction().add(fragment, "settings").commitNow();
    }

    private TwoStatePreference preference(String key) { return fragment.findPreference(key); }
    private void change(String key, boolean value) { preference(key).callChangeListener(value); }

    @Test public void headingIsNotASwitchAndDefaultsIgnoreRetiredMaster() {
        PreferenceCategory heading = fragment.findPreference("subscription_sidebar_buttons");
        assertNotNull(heading);
        assertFalse(heading.isSelectable());
        assertEquals(Settings.KEY_SHOW_EH_SUBSCRIPTION, heading.getPreference(0).getKey());
        assertNull(fragment.findPreference("show_subscription_buttons"));
        assertTrue(Settings.getShowEhSubscription());
        assertTrue(preference(Settings.KEY_SHOW_EH_SUBSCRIPTION).isChecked());
        assertFalse(Settings.getShowBookmarkSubscription());
        assertTrue(Settings.getShowGlobalSubscription());
        assertTrue(Settings.getShowUpdateSubscription());
        for (int i = 0; i < heading.getPreferenceCount(); i++) {
            assertTrue(heading.getPreference(i).isEnabled());
        }
    }

    @Test public void turningAllButtonsOffAndOneOnKeepsOthersOff() {
        change(Settings.KEY_SHOW_EH_SUBSCRIPTION, false);
        change(Settings.KEY_SHOW_GLOBAL_SUBSCRIPTION, false);
        change(Settings.KEY_SHOW_UPDATE_SUBSCRIPTION, false);
        Settings.initialize(RuntimeEnvironment.getApplication());
        assertFalse(Settings.getShowEhSubscription());
        assertFalse(Settings.getShowGlobalSubscription());
        assertFalse(Settings.getShowUpdateSubscription());
        change(Settings.KEY_SHOW_EH_SUBSCRIPTION, true);
        assertTrue(Settings.getShowEhSubscription());
        assertFalse(preference(Settings.KEY_SHOW_BOOKMARK_SUBSCRIPTION).isChecked());
        assertFalse(preference(Settings.KEY_SHOW_GLOBAL_SUBSCRIPTION).isChecked());
        assertFalse(preference(Settings.KEY_SHOW_UPDATE_SUBSCRIPTION).isChecked());
    }

    @Test public void changingEhPreservesOtherChoicesAndNotifiesRefresh() {
        change(Settings.KEY_SHOW_BOOKMARK_SUBSCRIPTION, true);
        change(Settings.KEY_SHOW_GLOBAL_SUBSCRIPTION, false);
        int changes = fragment.changes;
        change(Settings.KEY_SHOW_EH_SUBSCRIPTION, false);
        assertEquals(changes + 1, fragment.changes);
        assertFalse(Settings.getShowEhSubscription());
        assertTrue(Settings.getShowBookmarkSubscription());
        assertFalse(Settings.getShowGlobalSubscription());
        assertTrue(Settings.getShowUpdateSubscription());
        assertTrue(preference(Settings.KEY_SHOW_GLOBAL_SUBSCRIPTION).isEnabled());
        Settings.initialize(RuntimeEnvironment.getApplication());
        assertFalse(Settings.getShowEhSubscription());
        change(Settings.KEY_SHOW_EH_SUBSCRIPTION, true);
        assertTrue(Settings.getShowBookmarkSubscription());
        assertFalse(Settings.getShowGlobalSubscription());
        assertTrue(Settings.getShowUpdateSubscription());
    }

    public static class Harness extends PreferenceFragmentCompat {
        int changes;
        @Override public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.subscription_settings, rootKey);
            SubscriptionSidebarPreferences.bind(this, () -> changes++);
        }
    }
}
