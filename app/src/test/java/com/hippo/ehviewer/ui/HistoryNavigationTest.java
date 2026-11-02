package com.hippo.ehviewer.ui;

import android.app.Application;
import android.view.MenuItem;
import android.view.View;

import androidx.preference.PreferenceManager;

import com.google.android.material.navigation.NavigationView;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.ui.scene.history.GalleryUpdateHistoryScene;
import com.hippo.ehviewer.ui.scene.history.HistoryScene;
import com.hippo.ehviewer.ui.scene.history.ReadingHistoryScene;
import com.hippo.scene.Announcer;
import com.hippo.scene.StageActivity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE, qualifiers = "zh-rCN",
        shadows = HistoryNavigationTest.NavigationShadow.class,
        instrumentedPackages = "com.hippo.scene")
public class HistoryNavigationTest {
    private MainActivity activity;
    private NavigationView navigation;

    @Before public void setUp() {
        PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication())
                .edit().clear().commit();
        Settings.initialize(RuntimeEnvironment.getApplication());
        activity = Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(R.style.AppTheme_Main);
        navigation = new NavigationView(activity);
        navigation.setId(R.id.nav_view);
        navigation.inflateMenu(R.menu.nav_drawer_main);
        android.app.Activity host = Robolectric.buildActivity(android.app.Activity.class).setup().get();
        host.setContentView(navigation);
        ReflectionHelpers.setField(activity, "mNavView", navigation);
        navigation.setNavigationItemSelectedListener(activity);
        NavigationShadow.destination = null;
    }

    @Test public void allSettingCombinationsOnlyGroupMultipleVisibleEntries() {
        for (boolean collapse : new boolean[]{false, true}) {
            for (boolean reading : new boolean[]{false, true}) {
                for (boolean updates : new boolean[]{false, true}) {
                    configure(collapse, reading, updates);
                    setExpanded(false);
                    render();
                    boolean grouped = collapse && (reading || updates);
                    assertEquals(grouped, item(R.id.nav_history_group).isVisible());
                    assertEquals(!grouped, item(R.id.nav_history).isVisible());
                    assertEquals(!grouped && reading, item(R.id.nav_reading_history).isVisible());
                    assertEquals(!grouped && updates, item(R.id.nav_update_history).isVisible());
                    assertEquals(grouped ? "浏览历史" : "历史", item(R.id.nav_history).getTitle());
                    assertTrue(item(R.id.nav_downloads).isVisible());
                    assertTrue(item(R.id.nav_settings).isVisible());
                    if (grouped) {
                        toggle();
                        assertTrue(item(R.id.nav_history).isVisible());
                        assertEquals(reading, item(R.id.nav_reading_history).isVisible());
                        assertEquals(updates, item(R.id.nav_update_history).isVisible());
                    }
                }
            }
        }
    }

    @Test public void disclosureKeepsCurrentDestinationAndCanToggleWhileSelected() {
        configure(true, true, true);
        activity.setNavCheckedItem(R.id.nav_reading_history);
        assertEquals(R.id.nav_history_group, navigation.getCheckedItem().getItemId());
        toggle();
        assertTrue(item(R.id.nav_reading_history).isVisible());
        assertEquals(R.id.nav_reading_history, navigation.getCheckedItem().getItemId());
        assertEquals("收起历史菜单", item(R.id.nav_history_group).getContentDescription());
        toggle();
        assertFalse(item(R.id.nav_reading_history).isVisible());
        assertEquals(R.id.nav_history_group, navigation.getCheckedItem().getItemId());
        assertEquals("展开历史菜单", item(R.id.nav_history_group).getContentDescription());
        assertNull(NavigationShadow.destination);
        assertEquals((Integer) R.id.nav_reading_history,
                ReflectionHelpers.getField(activity, "mNavCheckedItem"));
        activity.setNavCheckedItem(R.id.nav_downloads);
        toggle();
        assertEquals(R.id.nav_downloads, navigation.getCheckedItem().getItemId());
    }

    @Test public void childrenStillOpenTheirOriginalDestinations() {
        configure(true, true, true);
        setExpanded(true);
        render();
        int[] ids = {R.id.nav_history, R.id.nav_reading_history, R.id.nav_update_history};
        Class<?>[] scenes = {HistoryScene.class, ReadingHistoryScene.class, GalleryUpdateHistoryScene.class};
        for (int i = 0; i < ids.length; i++) {
            assertTrue(navigation.getMenu().performIdentifierAction(ids[i], 0));
            assertEquals(scenes[i], NavigationShadow.destination);
        }
    }

    @Test public void settingsChangesRestoreStandaloneHistoryWithoutDisclosureIndicator() {
        configure(true, true, true);
        activity.setNavCheckedItem(R.id.nav_history);
        assertNull(item(R.id.nav_history_group).getActionView());
        configure(true, false, false);
        render();
        assertTrue(item(R.id.nav_history).isVisible());
        assertEquals("历史", item(R.id.nav_history).getTitle());
        assertEquals(R.id.nav_history, navigation.getCheckedItem().getItemId());
        assertNull(item(R.id.nav_history_group).getActionView());
        configure(true, false, true);
        render();
        assertTrue(item(R.id.nav_history_group).isVisible());
        assertNull(item(R.id.nav_history_group).getActionView());
        assertFalse(item(R.id.nav_history).isVisible());
    }

    @Test public void rowIndentDoesNotAccumulateOrAffectOtherButtons() {
        configure(true, true, true);
        render();
        toggle();
        assertTrue(item(R.id.nav_history).isVisible());
        View row = new View(activity);
        row.setId(R.id.nav_history);
        indent(row);
        int expected = navigation.getItemHorizontalPadding()
                + Math.round(24 * activity.getResources().getDisplayMetrics().density);
        assertEquals(expected, row.getPaddingStart());
        indent(row);
        assertEquals(expected, row.getPaddingStart());
        row.setId(R.id.nav_downloads);
        indent(row);
        assertEquals(navigation.getItemHorizontalPadding(), row.getPaddingStart());
        configure(false, true, true);
        render();
        row.setId(R.id.nav_history);
        indent(row);
        assertEquals(navigation.getItemHorizontalPadding(), row.getPaddingStart());
        assertEquals("历史", item(R.id.nav_history).getTitle());
    }

    private MenuItem item(int id) { return navigation.getMenu().findItem(id); }

    @Test public void eachLeftDrawerOpeningStartsCollapsedAndRightDrawerDoesNotResetIt() {
        configure(true, true, true);
        activity.setNavCheckedItem(R.id.nav_reading_history);
        View rightDrawer = new View(activity);
        rightDrawer.setId(R.id.right_drawer);
        for (int i = 0; i < 3; i++) {
            activity.onDrawerOpened(navigation);
            assertFalse(item(R.id.nav_history).isVisible());
            assertEquals(R.id.nav_history_group, navigation.getCheckedItem().getItemId());
            toggle();
            assertTrue(item(R.id.nav_history).isVisible());
            activity.onDrawerClosed(rightDrawer);
            assertTrue(item(R.id.nav_history).isVisible());
            activity.onDrawerClosed(navigation);
            assertFalse(item(R.id.nav_history).isVisible());
            assertEquals(R.id.nav_history_group, navigation.getCheckedItem().getItemId());
            layoutDrawer();
            assertEquals(navigation.findViewById(R.id.nav_history_group).getBottom(),
                    navigation.findViewById(R.id.nav_downloads).getTop());
        }
    }

    @Test public void oldSavedExpansionStateIsIgnored() {
        Settings.putBoolean("history_sidebar_expanded", true);
        configure(true, true, true);
        render();
        assertTrue(item(R.id.nav_history_group).isVisible());
        assertFalse(item(R.id.nav_history).isVisible());
    }

    @Test public void realRowClicksRefreshEntriesAndCloseGapsWithoutAnotherInteraction() {
        configure(true, true, true);
        render();
        ReflectionHelpers.callInstanceMethod(activity, "configureNavigationLongPress");
        layoutDrawer();
        int collapsedDownloadsTop = navigation.findViewById(R.id.nav_downloads).getTop();
        for (int i = 0; i < 3; i++) {
            assertTrue(navigation.findViewById(R.id.nav_history_group).performClick());
            ShadowLooper.idleMainLooper();
            layoutDrawer();
            View browsing = navigation.findViewById(R.id.nav_history);
            assertNotNull("The real click must refresh the navigation adapter", browsing);
            assertEquals(View.VISIBLE, browsing.getVisibility());
            View updates = navigation.findViewById(R.id.nav_update_history);
            View downloads = navigation.findViewById(R.id.nav_downloads);
            assertEquals(updates.getBottom(), downloads.getTop());
            assertTrue(downloads.getTop() > collapsedDownloadsTop);
            assertTrue(navigation.findViewById(R.id.nav_history_group).performClick());
            ShadowLooper.idleMainLooper();
            layoutDrawer();
            assertEquals(collapsedDownloadsTop, navigation.findViewById(R.id.nav_downloads).getTop());
            assertEquals(navigation.findViewById(R.id.nav_history_group).getBottom(),
                    navigation.findViewById(R.id.nav_downloads).getTop());
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void renderedDrawerKeepsChildIndentWhenRowsAreRebound() throws Exception {
        configure(true, true, true);
        setExpanded(true);
        activity.setNavCheckedItem(R.id.nav_reading_history);
        ReflectionHelpers.callInstanceMethod(activity, "configureNavigationLongPress");
        layoutDrawer();
        int indent = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        assertEquals(navigation.getItemHorizontalPadding() + indent,
                navigation.findViewById(R.id.nav_history).getPaddingStart());
        savePreview("expanded");
        item(R.id.nav_downloads).setTitle("下载");
        layoutDrawer();
        assertEquals(navigation.getItemHorizontalPadding() + indent,
                navigation.findViewById(R.id.nav_history).getPaddingStart());
        toggle();
        layoutDrawer();
        savePreview("collapsed");
        configure(true, false, false);
        render();
        layoutDrawer();
        assertEquals(navigation.getItemHorizontalPadding(),
                navigation.findViewById(R.id.nav_history).getPaddingStart());
        savePreview("standalone");
    }

    private void layoutDrawer() {
        float density = activity.getResources().getDisplayMetrics().density;
        int width = Math.round(304 * density);
        int height = Math.round(1000 * density);
        for (int i = 0; i < 2; i++) {
            navigation.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            navigation.layout(0, 0, width, height);
            navigation.getViewTreeObserver().dispatchOnGlobalLayout();
        }
    }

    private void savePreview(String name) throws Exception {
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                navigation.getWidth(), navigation.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        canvas.drawColor(android.graphics.Color.WHITE);
        navigation.draw(canvas);
        java.io.File directory = new java.io.File("build/history-sidebar-previews");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(
                new java.io.File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
        }
        bitmap.recycle();
    }

    private void configure(boolean collapse, boolean reading, boolean updates) {
        Settings.putBoolean(Settings.KEY_COLLAPSE_HISTORY_IN_SIDEBAR, collapse);
        Settings.putBoolean(Settings.KEY_RECORD_READING_HISTORY, reading);
        Settings.putBoolean(Settings.KEY_SHOW_UPDATE_HISTORY_IN_SIDEBAR, updates);
    }

    private void render() {
        ReflectionHelpers.callInstanceMethod(activity, "updateHistoryNavigationItems");
    }

    private void setExpanded(boolean expanded) {
        ReflectionHelpers.setField(activity, "mHistorySidebarExpanded", expanded);
    }

    private void indent(View row) {
        ReflectionHelpers.callInstanceMethod(activity, "updateHistoryNavigationIndent",
                ReflectionHelpers.ClassParameter.from(View.class, row));
    }

    private void toggle() {
        // NavigationView must not select a disclosure as a new destination.
        assertFalse(navigation.getMenu().performIdentifierAction(R.id.nav_history_group, 0));
        ShadowLooper.idleMainLooper();
    }

    @Implements(value = StageActivity.class, isInAndroidSdk = false)
    public static class NavigationShadow extends org.robolectric.shadows.ShadowActivity {
        static Class<?> destination;
        @Implementation protected void startScene(Announcer announcer) {
            destination = announcer.getClazz();
        }
    }
}
