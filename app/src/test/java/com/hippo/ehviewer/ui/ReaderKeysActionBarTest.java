package com.hippo.ehviewer.ui;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.ActionBarOverlayLayout;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.ui.fragment.ReaderKeysFragment;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28)
public class ReaderKeysActionBarTest {
    private ActivityController<AppCompatActivity> controller;
    private AppCompatActivity activity;
    private ActionBar actionBar;

    @Before public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        android.content.SharedPreferences preferences = context.getSharedPreferences("keys-action-bar-test", 0);
        preferences.edit().clear().commit();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre", preferences);
        controller = Robolectric.buildActivity(AppCompatActivity.class);
        activity = controller.get();
        activity.setTheme(R.style.AppTheme_Settings);
        controller.setup();
        activity.setContentView(new FrameLayout(activity));
        actionBar = activity.getSupportActionBar();
        assertNotNull(actionBar);
        actionBar.setShowHideAnimationEnabled(true);
        activity.getWindow().getDecorView().measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        activity.getWindow().getDecorView().layout(0, 0, 1080, 1920);
    }

    @After public void tearDown() {
        controller.pause().stop().destroy();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre", null);
    }

    @Test public void fullscreenEntryDoesNotLeaveContentTranslatedUpwards() {
        Settings.putReadingFullscreen(true);
        ActionBarOverlayLayout overlay = activity.findViewById(androidx.appcompat.R.id.decor_content_parent);
        View content = activity.findViewById(android.R.id.content);
        assertTrue(actionBar.getHeight() > 0);
        for (int i = 0; i < 2; i++) {
            ReaderKeysFragment editor = openEditor();
            // The decor receives this after the fragment starts hiding the action bar.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
            overlay.onWindowSystemUiVisibilityChanged(activity.getWindow().getDecorView().getSystemUiVisibility());
            finishTransition();
            assertEquals(0f, content.getTranslationY(), 0f);
            assertEquals(View.GONE, activity.findViewById(androidx.appcompat.R.id.action_bar_container).getVisibility());
            activity.getSupportFragmentManager().beginTransaction().remove(editor).commitNow();
            overlay.onWindowSystemUiVisibilityChanged(activity.getWindow().getDecorView().getSystemUiVisibility());
            finishTransition();
            assertEquals(0f, content.getTranslationY(), 0f);
            assertTrue(actionBar.isShowing());
        }
    }

    @Test public void entryAndReturnChangeActionBarWithoutSlidingContent() {
        Settings.putReadingFullscreen(false);
        View container = activity.findViewById(androidx.appcompat.R.id.action_bar_container);
        ReaderKeysFragment editor = openEditor();
        assertEquals(View.GONE, container.getVisibility());
        activity.getSupportFragmentManager().beginTransaction().remove(editor).commitNow();
        assertEquals(View.VISIBLE, container.getVisibility());
        assertEquals(0f, container.getTranslationY(), 0f);
        assertEquals(0f, activity.findViewById(android.R.id.content).getTranslationY(), 0f);
    }

    @Test public void editorHidesActionBarAndRestoresItOnEveryReturn() {
        actionBar.show();
        finishTransition();
        for (int i = 0; i < 2; i++) {
            ReaderKeysFragment editor = openEditor();
            assertFalse(actionBar.isShowing());
            activity.getSupportFragmentManager().beginTransaction().remove(editor).commitNow();
            finishTransition();
            assertTrue(actionBar.isShowing());
        }
    }

    @Test public void editorPreservesAnAlreadyHiddenActionBar() {
        actionBar.hide();
        finishTransition();
        ReaderKeysFragment editor = openEditor();
        assertFalse(actionBar.isShowing());
        activity.getSupportFragmentManager().beginTransaction().remove(editor).commitNow();
        finishTransition();
        assertFalse(actionBar.isShowing());
    }

    private ReaderKeysFragment openEditor() {
        ReaderKeysFragment editor = new ReaderKeysFragment();
        activity.getSupportFragmentManager().beginTransaction().add(android.R.id.content, editor).commitNow();
        return editor;
    }

    private void finishTransition() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
    }
}
