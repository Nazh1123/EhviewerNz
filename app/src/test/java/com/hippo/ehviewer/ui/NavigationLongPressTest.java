package com.hippo.ehviewer.ui;

import android.app.Application;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.navigation.NavigationView;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene;
import com.hippo.ehviewer.ui.scene.gallery.list.SubscriptionsScene;
import com.hippo.ehviewer.ui.scene.history.HistoryScene;
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
        Settings.putBoolean(Settings.KEY_POPULAR_UPDATES_ONLY, false);
        android.preference.PreferenceManager.getDefaultSharedPreferences(
                RuntimeEnvironment.getApplication()).edit()
                .remove(Settings.KEY_UPDATE_BOOKMARK_SUBSCRIPTIONS_ON_OPEN).commit();
        // Avoid launching network-backed scenes; only attach the navigation and dialog UI.
        activity = Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(R.style.AppTheme_Main);
        navigation = new NavigationView(activity);
        navigation.inflateMenu(R.menu.nav_drawer_main);
        ReflectionHelpers.setField(activity, "mNavView", navigation);
        row = new View(activity);
        new FrameLayout(activity).addView(row);
        row.setId(R.id.nav_history);
        ReflectionHelpers.callInstanceMethod(activity, "bindNavigationLongPress",
                ReflectionHelpers.ClassParameter.from(View.class, row));
    }

    @Test
    public void otherNavigationButtonsKeepTheirClicks() {
        int[] clicks = {0};
        row.setOnClickListener(v -> clicks[0]++);
        row.setId(R.id.nav_history);
        assertFalse(row.performLongClick());
        row.performClick();
        assertEquals(1, clicks[0]);
        row.setId(R.id.nav_downloads);
        assertFalse(row.performLongClick());
    }

    @Test
    @Config(shadows = ShadowStageActivity.class, instrumentedPackages = "com.hippo.scene")
    public void subscriptionButtonsDefaultToTapReuseAndHoldUpdateAndSettingReversesBoth() {
        assertFalse(Settings.getUpdateBookmarkSubscriptionsOnOpen());
        int[] ids = {R.id.nav_bookmark_subscription, R.id.nav_global_subscription};
        String[] actions = {GalleryListScene.ACTION_BOOKMARK_SUBSCRIPTION,
                GalleryListScene.ACTION_GLOBAL_SUBSCRIPTION};
        for (boolean updateOnOpen : new boolean[]{false, true}) {
            Settings.putBoolean(Settings.KEY_UPDATE_BOOKMARK_SUBSCRIPTIONS_ON_OPEN, updateOnOpen);
            for (int i = 0; i < ids.length; i++) {
                row.setId(ids[i]);
                assertTrue(row.performLongClick());
                assertEquals(GalleryListScene.class, ShadowStageActivity.destination);
                assertEquals(actions[i], ShadowStageActivity.args.getString(GalleryListScene.KEY_ACTION));
                assertEquals(updateOnOpen ? GalleryListScene.SUBSCRIPTION_REUSE_AVAILABLE
                        : GalleryListScene.SUBSCRIPTION_FORCE_REFRESH,
                        ShadowStageActivity.args.getInt(GalleryListScene.KEY_SUBSCRIPTION_REUSE_POLICY));

                // Re-selecting the current destination must obey the same setting.
                var item = navigation.getMenu().findItem(ids[i]);
                item.setChecked(true);
                assertTrue(activity.onNavigationItemSelected(item));
                assertEquals(GalleryListScene.class, ShadowStageActivity.destination);
                assertEquals(actions[i], ShadowStageActivity.args.getString(GalleryListScene.KEY_ACTION));
                assertEquals(updateOnOpen ? GalleryListScene.SUBSCRIPTION_REUSE_RECENT
                        : GalleryListScene.SUBSCRIPTION_REUSE_AVAILABLE,
                        ShadowStageActivity.args.getInt(GalleryListScene.KEY_SUBSCRIPTION_REUSE_POLICY));
            }
        }
    }

    @Test
    @Config(shadows = ShadowStageActivity.class, instrumentedPackages = "com.hippo.scene")
    public void subscriptionLongPressOpensSubscriptionManagement() {
        row.setId(R.id.nav_subscription);
        assertTrue(row.performLongClick());
        assertEquals(SubscriptionsScene.class, ShadowStageActivity.destination);
    }

    @Test
    @Config(shadows = ShadowStageActivity.class, instrumentedPackages = "com.hippo.scene")
    public void historyGroupLongPressOpensBrowsingHistoryWithoutExpanding() {
        row.setId(R.id.nav_history_group);
        assertTrue(row.performLongClick());
        assertEquals(HistoryScene.class, ShadowStageActivity.destination);
        assertFalse(ReflectionHelpers.<Boolean>getField(activity, "mHistorySidebarExpanded"));
    }

    @Test
    @Config(shadows = ShadowStageActivity.class, instrumentedPackages = "com.hippo.scene")
    public void popularLongPressOpensOppositeModeAndClickKeepsDefault() {
        row.setId(R.id.nav_whats_hot);
        for (boolean defaultUpdates : new boolean[]{false, true}) {
            Settings.putBoolean(Settings.KEY_POPULAR_UPDATES_ONLY, defaultUpdates);
            assertTrue(row.performLongClick());
            assertEquals(GalleryListScene.class, ShadowStageActivity.destination);
            assertEquals(GalleryListScene.ACTION_WHATS_HOT,
                    ShadowStageActivity.args.getString(GalleryListScene.KEY_ACTION));
            assertTrue(ShadowStageActivity.args.containsKey(GalleryListScene.KEY_POPULAR_UPDATES_ONLY));
            assertEquals(!defaultUpdates,
                    ShadowStageActivity.args.getBoolean(GalleryListScene.KEY_POPULAR_UPDATES_ONLY));
            assertEquals(defaultUpdates, Settings.getPopularUpdatesOnly());

            assertTrue(activity.onNavigationItemSelected(
                    navigation.getMenu().findItem(R.id.nav_whats_hot)));
            assertEquals(GalleryListScene.ACTION_WHATS_HOT,
                    ShadowStageActivity.args.getString(GalleryListScene.KEY_ACTION));
            assertFalse(ShadowStageActivity.args.containsKey(GalleryListScene.KEY_POPULAR_UPDATES_ONLY));
        }
    }

    @Implements(value = StageActivity.class, isInAndroidSdk = false)
    public static class ShadowStageActivity extends ShadowActivity {
        static Class<?> destination;
        static Bundle args;

        @Implementation
        protected void startScene(Announcer announcer) {
            destination = announcer.getClazz();
            args = announcer.getArgs();
        }

        @Implementation
        protected void startSceneFirstly(Announcer announcer) {
            startScene(announcer);
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
        assertNull(ReflectionHelpers.getField(activity, "mSubscriptionUpdateManager"));
        settings.dismiss();
        ShadowLooper.idleMainLooper();
        assertNull(ReflectionHelpers.getField(activity, "mSubscriptionUpdateSettingsDialog"));
    }

}
