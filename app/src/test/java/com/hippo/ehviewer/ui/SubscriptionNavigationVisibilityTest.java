package com.hippo.ehviewer.ui;

import android.app.Application;
import android.preference.PreferenceManager;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.view.menu.MenuItemImpl;

import com.google.android.material.internal.NavigationMenuItemView;
import com.google.android.material.navigation.NavigationView;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;

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
@Config(application = Application.class, sdk = 28, manifest = Config.NONE)
public class SubscriptionNavigationVisibilityTest {
    private MainActivity activity;
    private NavigationView navigation;

    @Before public void setUp() {
        PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication())
                .edit().clear().commit();
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.putBoolean(Settings.KEY_SHOW_BOOKMARK_SUBSCRIPTION, true);
        activity = Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(R.style.AppTheme_Main);
        navigation = new NavigationView(activity);
        navigation.inflateMenu(R.menu.nav_drawer_main);
        ReflectionHelpers.setField(activity, "mNavView", navigation);
        ReflectionHelpers.callInstanceMethod(activity, "initSubscriptionUpdateBadges");
    }

    private MenuItem item(int id) { return navigation.getMenu().findItem(id); }

    private NavigationMenuItemView bindRow(int id, String text) {
        TextView action = (TextView) item(id).getActionView();
        action.setText(text);
        action.setVisibility(View.VISIBLE);
        NavigationMenuItemView row = new NavigationMenuItemView(activity);
        row.initialize((MenuItemImpl) item(id), 0);
        assertNotNull(action.getParent());
        return row;
    }

    private void render() {
        ReflectionHelpers.callInstanceMethod(activity, "renderSubscriptionUpdateState");
    }

    @Test public void hidingUpdateRemovesCountdownBeforeItsRowIsReusedForHot() {
        NavigationMenuItemView row = bindRow(R.id.nav_update_subscription, "12:34");
        TextView countdown = (TextView) item(R.id.nav_update_subscription).getActionView();
        Settings.putSubscriptionButtonVisibility(Settings.KEY_SHOW_UPDATE_SUBSCRIPTION, false);
        render();
        assertFalse(item(R.id.nav_update_subscription).isVisible());
        assertNull(item(R.id.nav_update_subscription).getActionView());
        assertNull(countdown.getParent());
        assertEquals("", countdown.getText().toString());
        assertEquals(View.GONE, countdown.getVisibility());
        assertNull(ReflectionHelpers.getField(activity, "mSubscriptionUpdateCountdown"));
        row.initialize((MenuItemImpl) item(R.id.nav_whats_hot), 0);
        assertNull(row.findViewById(R.id.subscription_update_countdown));
        assertNull(item(R.id.nav_whats_hot).getActionView());
    }

    @Test public void reenablingUpdateInSameActivityCreatesAndBindsAFreshCountdown() {
        NavigationMenuItemView row = bindRow(R.id.nav_update_subscription, "12:34");
        View oldCountdown = item(R.id.nav_update_subscription).getActionView();
        Settings.putSubscriptionButtonVisibility(Settings.KEY_SHOW_UPDATE_SUBSCRIPTION, false);
        render();
        row.initialize((MenuItemImpl) item(R.id.nav_whats_hot), 0);
        Settings.putSubscriptionButtonVisibility(Settings.KEY_SHOW_UPDATE_SUBSCRIPTION, true);
        render();
        TextView countdown = (TextView) item(R.id.nav_update_subscription).getActionView();
        assertTrue(item(R.id.nav_update_subscription).isVisible());
        assertNotSame(oldCountdown, countdown);
        assertSame(countdown, ReflectionHelpers.getField(activity, "mSubscriptionUpdateCountdown"));
        assertEquals("", countdown.getText().toString());
        row.initialize((MenuItemImpl) item(R.id.nav_update_subscription), 0);
        assertSame(countdown, row.findViewById(R.id.subscription_update_countdown));
    }

    @Test public void masterOffRemovesAllSubscriptionActionViewsAndLeavesEhBadge() {
        int[] ids = {R.id.nav_bookmark_subscription, R.id.nav_global_subscription,
                R.id.nav_update_subscription};
        for (int id : ids) bindRow(id, "42");
        View ehBadge = item(R.id.nav_subscription).getActionView();
        Settings.putSubscriptionButtonVisibility(Settings.KEY_SHOW_SUBSCRIPTION_BUTTONS, false);
        render();
        for (int id : ids) {
            assertFalse(item(id).isVisible());
            assertNull(item(id).getActionView());
        }
        assertSame(ehBadge, item(R.id.nav_subscription).getActionView());
        Settings.putSubscriptionButtonVisibility(Settings.KEY_SHOW_SUBSCRIPTION_BUTTONS, true);
        render();
        for (int id : ids) {
            assertTrue(item(id).isVisible());
            assertNotNull(item(id).getActionView());
        }
    }
}
