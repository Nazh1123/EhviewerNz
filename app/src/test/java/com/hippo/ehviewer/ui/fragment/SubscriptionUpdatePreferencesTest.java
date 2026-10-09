package com.hippo.ehviewer.ui.fragment;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.TwoStatePreference;

import com.google.android.material.textfield.TextInputLayout;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE)
public class SubscriptionUpdatePreferencesTest {
    private Activity activity;
    private AtomicInteger saves;

    @Before
    public void setUp() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.putBoolean(Settings.KEY_AUTO_SUBSCRIPTION_UPDATES, false);
        Settings.putBoolean(Settings.KEY_AUTO_SUBSCRIPTION_UPDATES_EH, true);
        Settings.putBoolean(Settings.KEY_AUTO_SUBSCRIPTION_UPDATES_BOOKMARK, true);
        Settings.putString(Settings.KEY_AUTO_SUBSCRIPTION_UPDATE_INTERVAL, "60");
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.AppTheme);
        saves = new AtomicInteger();
    }

    @Test
    public void parentSwitchControlsAllThreeChildrenAndCancelDoesNotPersistEdits() {
        AlertDialog dialog = SubscriptionUpdatePreferences.showDialog(activity, saves::incrementAndGet);
        SwitchCompat automatic = dialog.findViewById(R.id.auto_subscription_updates);
        SwitchCompat eh = dialog.findViewById(R.id.auto_subscription_updates_eh);
        SwitchCompat bookmark = dialog.findViewById(R.id.auto_subscription_updates_bookmark);
        EditText interval = dialog.findViewById(R.id.auto_subscription_update_interval);
        assertFalse(eh.isEnabled());
        assertFalse(bookmark.isEnabled());
        assertFalse(interval.isEnabled());
        assertEquals("60", interval.getText().toString());
        automatic.setChecked(true);
        assertTrue(eh.isEnabled());
        assertTrue(bookmark.isEnabled());
        assertTrue(interval.isEnabled());
        eh.setChecked(false);
        interval.setText("15");
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(Settings.getAutoSubscriptionUpdates());
        assertTrue(Settings.getAutoSubscriptionUpdatesEh());
        assertEquals(60, Settings.getAutoSubscriptionUpdateIntervalMinutes());
        assertEquals(0, saves.get());
    }

    @Test
    public void invalidIntervalKeepsDialogOpenAndValidSaveUpdatesAllSharedSettings() {
        AlertDialog dialog = SubscriptionUpdatePreferences.showDialog(activity, saves::incrementAndGet);
        ((SwitchCompat) dialog.findViewById(R.id.auto_subscription_updates)).setChecked(true);
        ((SwitchCompat) dialog.findViewById(R.id.auto_subscription_updates_eh)).setChecked(false);
        EditText interval = dialog.findViewById(R.id.auto_subscription_update_interval);
        interval.setText("0");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(dialog.isShowing());
        TextInputLayout intervalLayout = dialog.findViewById(R.id.auto_subscription_update_interval_layout);
        assertNotNull(intervalLayout.getError());
        assertFalse(Settings.getAutoSubscriptionUpdates());
        assertEquals(0, saves.get());
        interval.setText("0015");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertFalse(dialog.isShowing());
        assertTrue(Settings.getAutoSubscriptionUpdates());
        assertFalse(Settings.getAutoSubscriptionUpdatesEh());
        assertTrue(Settings.getAutoSubscriptionUpdatesBookmark());
        assertEquals("15", Settings.getString(Settings.KEY_AUTO_SUBSCRIPTION_UPDATE_INTERVAL, null));
        assertEquals(1, saves.get());
    }

    @Test
    public void forkPreferenceAndDialogReadAndWriteTheSameIntervalAndDependency() {
        Settings.putBoolean(Settings.KEY_AUTO_SUBSCRIPTION_UPDATES, true);
        FragmentActivity host = Robolectric.buildActivity(FragmentActivity.class).setup().get();
        host.setTheme(R.style.AppTheme);
        Harness fragment = new Harness();
        host.getSupportFragmentManager().beginTransaction().add(fragment, "settings").commitNow();
        EditTextPreference interval = fragment.findPreference(Settings.KEY_AUTO_SUBSCRIPTION_UPDATE_INTERVAL);
        assertEquals(Settings.KEY_AUTO_SUBSCRIPTION_UPDATES, interval.getDependency());
        assertEquals("60", interval.getText());
        assertFalse(interval.callChangeListener("0"));
        assertEquals("60", interval.getText());
        interval.callChangeListener("0030");
        assertEquals("30", interval.getText());
        assertEquals(30, Settings.getAutoSubscriptionUpdateIntervalMinutes());
        AlertDialog dialog = SubscriptionUpdatePreferences.showDialog(activity, saves::incrementAndGet);
        assertEquals("30", ((EditText) dialog.findViewById(R.id.auto_subscription_update_interval))
                .getText().toString());
        dialog.dismiss();
        TwoStatePreference automatic = fragment.findPreference(Settings.KEY_AUTO_SUBSCRIPTION_UPDATES);
        automatic.setChecked(false);
        assertFalse(interval.isEnabled());
        host.finish();
    }

    public static class Harness extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            addPreferencesFromResource(R.xml.subscription_settings);
            SubscriptionUpdatePreferences.bind(this);
        }
    }
}
