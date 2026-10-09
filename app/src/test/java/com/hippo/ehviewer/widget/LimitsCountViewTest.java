package com.hippo.ehviewer.widget;

import android.app.Application;
import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28)
public class LimitsCountViewTest {
    @Before public void setUp() {
        Context app = RuntimeEnvironment.getApplication();
        android.content.SharedPreferences preferences =
                app.getSharedPreferences("limits-count-test", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre", preferences);
    }

    @Test public void signedInStartupInflatesActualLimitsControlsInAllThemes() {
        Settings.setLoginState(true);
        Settings.setKeyShowEhLimits(true);
        for (int theme : new int[]{R.style.AppTheme_Main, R.style.AppTheme_Main_Dark,
                R.style.AppTheme_Main_Black}) {
            LimitsCountView view = new LimitsCountView(
                    new ContextThemeWrapper(RuntimeEnvironment.getApplication(), theme));
            assertEquals(View.VISIBLE, view.getVisibility());
            assertControls(view);
            assertTrue(view.findViewById(R.id.reset_limits).hasOnClickListeners());
            assertTrue(view.findViewById(R.id.limits_count).hasOnClickListeners());
            assertTrue(view.findViewById(R.id.refresh_icon).hasOnClickListeners());
        }
    }

    @Test public void signedOutStartupHidesTheCorrectLimitsLayout() {
        Settings.setLoginState(false);
        LimitsCountView view = createView();
        assertEquals(View.GONE, view.getVisibility());
        assertControls(view);
    }

    @Test public void disabledLimitsRemainHiddenForSignedInUser() {
        Settings.setLoginState(true);
        Settings.setKeyShowEhLimits(false);
        LimitsCountView view = createView();
        assertEquals(View.GONE, view.getVisibility());
        assertControls(view);
    }

    private LimitsCountView createView() {
        return new LimitsCountView(new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(), R.style.AppTheme_Main));
    }

    private void assertControls(LimitsCountView view) {
        for (int id : new int[]{R.id.from_gallery, R.id.from_torrent, R.id.from_download,
                R.id.from_hentai, R.id.reset_limits, R.id.limits_count}) {
            assertTrue("Expected limits TextView " + id, view.findViewById(id) instanceof TextView);
        }
        assertNotNull(view.findViewById(R.id.refresh_icon));
        assertNotNull(view.findViewById(R.id.refreshing));
    }
}
