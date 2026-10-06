package com.hippo.ehviewer.ui;

import android.app.Application;
import android.view.View;
import android.widget.FrameLayout;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.navigation.NavigationView;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.ui.scene.gallery.list.QuickSearchScene;
import com.hippo.preference.ListPreference;
import com.hippo.scene.Announcer;
import com.hippo.scene.StageActivity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE)
public class NavigationLongPressTest {
    private MainActivity activity;
    private NavigationView navigation;
    private View row;

    @Before
    public void setUp() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.putString(Settings.KEY_SEARCH_LANGUAGE, "chinese");
        // Avoid launching network-backed scenes; only attach the navigation and dialog UI.
        activity = Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(R.style.AppTheme_Main);
        navigation = new NavigationView(activity);
        navigation.inflateMenu(R.menu.nav_drawer_main);
        ReflectionHelpers.setField(activity, "mNavView", navigation);
        row = new View(activity);
        new FrameLayout(activity).addView(row);
        row.setId(R.id.nav_search_language);
        ReflectionHelpers.callInstanceMethod(activity, "bindNavigationLongPress",
                ReflectionHelpers.ClassParameter.from(View.class, row));
    }

    @Test
    public void materialNavigationRowsReceiveLongPressListener() {
        ReflectionHelpers.callInstanceMethod(activity, "configureNavigationLongPress");
        navigation.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY));
        navigation.layout(0, 0, 600, 2400);
        View menuRow = navigation.findViewById(R.id.nav_search_language);
        assertNotNull(menuRow);
        assertTrue(menuRow.performLongClick());
        assertTrue(dialog().isShowing());
        dialog().dismiss();
    }

    @Test
    public void longPressShowsCurrentLanguageAndCancelKeepsSettings() {
        assertTrue(row.performLongClick());
        AlertDialog dialog = dialog();
        assertTrue(dialog.isShowing());
        assertEquals(preference().findIndexOfValue("chinese"),
                dialog.getListView().getCheckedItemPosition());
        assertEquals("chinese", Settings.getSearchLanguage());
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(dialog.isShowing());
        assertEquals("chinese", Settings.getSearchLanguage());
    }

    @Test
    public void selectionPersistsAndDisableImmediatelyHidesNavigationItem() {
        assertTrue(row.performLongClick());
        choose("japanese");
        assertEquals("japanese", Settings.getSearchLanguage());
        assertTrue(navigation.getMenu().findItem(R.id.nav_search_language).isVisible());
        assertTrue(row.performLongClick());
        assertEquals(preference().findIndexOfValue("japanese"),
                dialog().getListView().getCheckedItemPosition());
        choose(Settings.SEARCH_LANGUAGE_DISABLED);
        assertEquals(Settings.SEARCH_LANGUAGE_DISABLED, Settings.getSearchLanguage());
        assertFalse(navigation.getMenu().findItem(R.id.nav_search_language).isVisible());
    }

    @Test
    public void recycledRowsUseTheirCurrentIdAndOtherButtonsKeepTheirClicks() {
        int[] clicks = {0};
        row.setOnClickListener(v -> clicks[0]++);
        row.setId(R.id.nav_history);
        assertFalse(row.performLongClick());
        row.performClick();
        assertEquals(1, clicks[0]);
        row.setId(R.id.nav_downloads);
        assertFalse(row.performLongClick());
        assertNull(preference());
        row.setId(R.id.nav_search_language);
        assertTrue(row.performLongClick());
        assertEquals(1, clicks[0]);
        preference().onDetached();
        assertFalse(dialogIsShowing());
    }

    @Test
    @Config(shadows = ShadowStageActivity.class, instrumentedPackages = "com.hippo.scene")
    public void bookmarkSubscriptionLongPressOpensTheExistingBookmarkSettingsScene() {
        ShadowStageActivity.destination = null;
        row.setId(R.id.nav_bookmark_subscription);
        assertTrue(row.performLongClick());
        assertEquals(QuickSearchScene.class, ShadowStageActivity.destination);
        assertNull(preference());
    }

    @Implements(value = StageActivity.class, isInAndroidSdk = false)
    public static class ShadowStageActivity extends ShadowActivity {
        static Class<?> destination;

        @Implementation
        protected void startScene(Announcer announcer) {
            destination = announcer.getClazz();
        }
    }

    @Test
    public void updateSubscriptionLongPressOpensSettingsWithoutStartingAnUpdate() {
        row.setId(R.id.nav_update_subscription);
        assertTrue(row.performLongClick());
        AlertDialog settings = ReflectionHelpers.getField(activity,
                "mSubscriptionUpdateSettingsDialog");
        assertTrue(settings.isShowing());
        assertNotNull(settings.findViewById(R.id.auto_subscription_updates));
        assertNotNull(settings.findViewById(R.id.auto_subscription_updates_eh));
        assertNotNull(settings.findViewById(R.id.auto_subscription_updates_bookmark));
        assertNotNull(settings.findViewById(R.id.auto_subscription_update_interval));
        assertNull(preference());
        assertNull(ReflectionHelpers.getField(activity, "mSubscriptionUpdateManager"));
        settings.dismiss();
        ShadowLooper.idleMainLooper();
        assertNull(ReflectionHelpers.getField(activity, "mSubscriptionUpdateSettingsDialog"));
    }

    private ListPreference preference() {
        return ReflectionHelpers.getField(activity, "mSearchLanguagePreference");
    }

    private AlertDialog dialog() {
        return (AlertDialog) preference().getDialog();
    }

    private boolean dialogIsShowing() {
        return preference().getDialog() != null && preference().getDialog().isShowing();
    }

    private void choose(String value) {
        AlertDialog dialog = dialog();
        int index = preference().findIndexOfValue(value);
        dialog.getListView().performItemClick(null, index, index);
        ShadowLooper.idleMainLooper();
        assertFalse(dialog.isShowing());
    }
}
