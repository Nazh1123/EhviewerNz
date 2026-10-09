package com.hippo.ehviewer.ui.scene.history;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.TwoStatePreference;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.navigation.NavigationView;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.gallery.ReadingHistory;
import com.hippo.ehviewer.ui.GalleryActivity;
import com.hippo.ehviewer.ui.MainActivity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, qualifiers = "zh-rCN")
public class ReadingHistoryUiTest {
    private Context context;
    private SharedPreferences settings;

    @Before public void setUp() {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme);
        settings = android.preference.PreferenceManager.getDefaultSharedPreferences(context);
        settings.edit().clear().commit();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre", settings);
        ReadingHistory.clear(context);
    }

    @Test public void sidebarTracksSettingWhileExistingHistoryRemainsVisible() {
        MainActivity main = Robolectric.buildActivity(MainActivity.class).get();
        NavigationView navigation = new NavigationView(context);
        navigation.inflateMenu(R.menu.nav_drawer_main);
        ReflectionHelpers.setField(main, "mNavView", navigation);
        ReflectionHelpers.callInstanceMethod(main, "updateReadingHistoryNavigationItem");
        assertTrue(navigation.getMenu().findItem(R.id.nav_reading_history).isVisible());
        settings.edit().putBoolean(Settings.KEY_RECORD_READING_HISTORY, false).apply();
        ReflectionHelpers.callInstanceMethod(main, "updateReadingHistoryNavigationItem");
        assertFalse(navigation.getMenu().findItem(R.id.nav_reading_history).isVisible());
        assertTrue(navigation.getMenu().findItem(R.id.nav_history).isVisible());
        settings.edit().putBoolean(Settings.KEY_RECORD_READING_HISTORY, true).apply();
        ReflectionHelpers.callInstanceMethod(main, "updateReadingHistoryNavigationItem");
        assertTrue(navigation.getMenu().findItem(R.id.nav_reading_history).isVisible());
    }

    @Test public void preferenceDefaultsOnInRenamedSavingAndHistoryGroupAndPersistsToggle() {
        org.robolectric.android.controller.ActivityController<FragmentActivity> controller =
                Robolectric.buildActivity(FragmentActivity.class);
        FragmentActivity host = controller.get();
        host.setTheme(R.style.AppTheme);
        controller.setup();
        Harness preferences = new Harness();
        host.getSupportFragmentManager().beginTransaction().add(preferences, "preferences").commitNow();
        TwoStatePreference toggle = preferences.findPreference(Settings.KEY_RECORD_READING_HISTORY);
        assertNotNull(toggle);
        assertTrue(toggle.isChecked());
        assertEquals("保存与历史", toggle.getParent().getTitle().toString());
        toggle.setChecked(false);
        assertFalse(Settings.isReadingHistoryEnabled());
        toggle.setChecked(true);
        assertTrue(Settings.isReadingHistoryEnabled());
        controller.pause().stop().destroy();
    }

    @Test public void listShowsSourceAndTimeAndClickStartsReaderWithOriginalSource() {
        GalleryInfo gallery = new GalleryInfo();
        gallery.gid = 21;
        gallery.token = "token21";
        gallery.title = "Reading example";
        ReadingHistory.record(context, new Intent(GalleryActivity.ACTION_EH)
                .putExtra(GalleryActivity.KEY_GALLERY_INFO, gallery)
                .putExtra(ReadingHistory.KEY_SOURCE, ReadingHistory.DOWNLOAD));
        TestScene scene = new TestScene(context);
        View root = scene.onCreateView3(LayoutInflater.from(context), null, null);
        RecyclerView recycler = root.findViewById(R.id.recycler_view);
        RecyclerView.Adapter adapter = recycler.getAdapter();
        assertEquals(1, adapter.getItemCount());
        RecyclerView.ViewHolder row = adapter.createViewHolder(recycler, 0);
        adapter.bindViewHolder(row, 0);
        assertEquals("Reading example", ((TextView) row.itemView.findViewById(R.id.title)).getText().toString());
        String metadata = ((TextView) row.itemView.findViewById(R.id.reading_metadata)).getText().toString();
        assertTrue(metadata.contains("下载"));
        assertTrue(metadata.contains("·"));
        row.itemView.performClick();
        assertNotNull(scene.launched);
        assertEquals(GalleryActivity.class.getName(), scene.launched.getComponent().getClassName());
        assertEquals(ReadingHistory.DOWNLOAD, scene.launched.getStringExtra(ReadingHistory.KEY_SOURCE));
        assertEquals(21, ((GalleryInfo) scene.launched.getParcelableExtra(GalleryActivity.KEY_GALLERY_INFO)).gid);
        ReadingHistory.clear(context);
        ReflectionHelpers.callInstanceMethod(scene, "reload");
        assertEquals(0, adapter.getItemCount());
        assertEquals(View.VISIBLE, root.findViewById(R.id.tip).getVisibility());
        assertEquals("进入阅读器的画廊将在这里显示", ((TextView) root.findViewById(R.id.tip)).getText().toString());
    }

    public static class Harness extends PreferenceFragmentCompat {
        @Override public void onCreatePreferences(Bundle state, String rootKey) {
            setPreferencesFromResource(R.xml.fork_features_settings, rootKey);
        }
    }

    private static class TestScene extends ReadingHistoryScene {
        private final Context context;
        private final MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        private Intent launched;
        TestScene(Context context) { this.context = context; }
        @Override public Context getContext() { return context; }
        @Override public Context getEHContext() { return context; }
        @Override public MainActivity getActivity2() { return activity; }
        @Override public void startActivity(Intent intent) { launched = intent; }
    }
}
