package com.hippo.ehviewer.ui.fragment;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;

import androidx.fragment.app.FragmentActivity;
import androidx.preference.PreferenceManager;
import androidx.preference.TwoStatePreference;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.ui.MainActivity;
import com.hippo.ehviewer.ui.scene.history.GalleryUpdateHistoryScene;
import com.hippo.ehviewer.ui.scene.history.ReadingHistoryScene;
import com.hippo.preference.ListPreference;
import com.hippo.scene.StageActivity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE, qualifiers = "zh-rCN")
public class HistoryFragmentTest {
    private FragmentActivity host;
    private HistoryFragment fragment;

    @Before public void setUp() {
        PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication())
                .edit().clear()
                .putBoolean(Settings.KEY_RECORD_READING_HISTORY, false)
                .putBoolean(Settings.KEY_SHOW_VIEWED_GALLERIES, false)
                .putString(Settings.KEY_READING_HISTORY_SIZE, "500")
                .putString(Settings.KEY_GALLERY_UPDATE_RECORD_LIMIT, "200")
                .commit();
        Settings.initialize(RuntimeEnvironment.getApplication());
        host = Robolectric.buildActivity(FragmentActivity.class).setup().get();
        host.setTheme(R.style.AppTheme_Settings);
        fragment = new HistoryFragment();
        host.getSupportFragmentManager().beginTransaction().add(fragment, "history").commitNow();
    }

    @Test public void movedPreferencesKeepExistingValuesAndNotifyGalleryRefresh() {
        TwoStatePreference recording = fragment.findPreference(Settings.KEY_RECORD_READING_HISTORY);
        TwoStatePreference viewed = fragment.findPreference(Settings.KEY_SHOW_VIEWED_GALLERIES);
        assertFalse(recording.isChecked());
        assertFalse(viewed.isChecked());
        assertEquals("500", ((ListPreference) fragment.findPreference(
                Settings.KEY_READING_HISTORY_SIZE)).getValue());
        assertEquals("200", ((ListPreference) fragment.findPreference(
                Settings.KEY_GALLERY_UPDATE_RECORD_LIMIT)).getValue());
        assertTrue(recording.callChangeListener(true));
        recording.setChecked(true);
        assertTrue(Settings.isReadingHistoryEnabled());
        assertEquals(Activity.RESULT_OK, shadowOf(host).getResultCode());
        host.setResult(Activity.RESULT_CANCELED);
        assertTrue(viewed.callChangeListener(true));
        viewed.setChecked(true);
        assertTrue(Settings.getShowViewedGalleries());
        assertEquals(Activity.RESULT_OK, shadowOf(host).getResultCode());
    }

    @Test public void readingHistoryOpensEvenWhenRecordingIsDisabled() {
        fragment.findPreference("view_reading_history").performClick();
        assertHistoryIntent(ReadingHistoryScene.class);
        assertFalse(Settings.isReadingHistoryEnabled());
    }

    @Test public void updateHistoryOpensExistingRecordPage() {
        fragment.findPreference("view_gallery_update_history").performClick();
        assertHistoryIntent(GalleryUpdateHistoryScene.class);
    }

    @Test public void updateSidebarSwitchIsFirstDefaultsOffAndPersists() {
        TwoStatePreference toggle = fragment.findPreference(Settings.KEY_SHOW_UPDATE_HISTORY_IN_SIDEBAR);
        assertNotNull(toggle);
        assertSame(toggle, toggle.getParent().getPreference(0));
        assertEquals("在侧边栏显示按钮", toggle.getTitle().toString());
        assertEquals("开启后在左侧边栏显示“更新记录”按钮", toggle.getSummary().toString());
        assertFalse(toggle.isChecked());
        assertFalse(Settings.getShowUpdateHistoryInSidebar());
        assertTrue(toggle.callChangeListener(true));
        toggle.setChecked(true);
        assertTrue(Settings.getShowUpdateHistoryInSidebar());
        assertEquals(Activity.RESULT_OK, shadowOf(host).getResultCode());
    }

    private void assertHistoryIntent(Class<?> scene) {
        Intent intent = shadowOf(host).getNextStartedActivity();
        assertNotNull(intent);
        assertEquals(MainActivity.class.getName(), intent.getComponent().getClassName());
        assertEquals(StageActivity.ACTION_START_SCENE, intent.getAction());
        assertEquals(scene.getName(), intent.getStringExtra(StageActivity.KEY_SCENE_NAME));
        assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0);
        assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0);
        assertTrue(host.isFinishing());
    }
}
