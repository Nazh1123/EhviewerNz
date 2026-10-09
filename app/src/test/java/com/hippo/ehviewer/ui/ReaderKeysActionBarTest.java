package com.hippo.ehviewer.ui;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.widget.FrameLayout;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

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
    }

    @After public void tearDown() {
        controller.pause().stop().destroy();
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
