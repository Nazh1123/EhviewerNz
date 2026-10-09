package com.hippo.view;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.Duration;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28)
public class ViewTransitionTest {
    @Test public void requestingCurrentViewRepairsVisibilityAndAlpha() {
        Context context = RuntimeEnvironment.getApplication();
        View content = new View(context);
        View empty = new View(context);
        ViewTransition transition = new ViewTransition(content, empty);
        // A completed/interrupted outgoing transition must not make future show calls a no-op.
        content.setVisibility(View.GONE);
        content.setAlpha(0f);
        transition.showView(0);
        assertEquals(View.VISIBLE, content.getVisibility());
        assertEquals(1f, content.getAlpha(), 0f);
    }

    @Test public void immediateShowSettlesAnInFlightTransitionEvenForTheSameTarget() {
        Context context = RuntimeEnvironment.getApplication();
        View content = new View(context);
        View empty = new View(context);
        ViewTransition transition = new ViewTransition(content, empty);
        transition.showView(1);
        transition.showView(0);
        transition.showView(0, false);
        assertEquals(View.VISIBLE, content.getVisibility());
        assertEquals(1f, content.getAlpha(), 0f);
        assertEquals(View.GONE, empty.getVisibility());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertEquals(View.VISIBLE, content.getVisibility());
        assertEquals(1f, content.getAlpha(), 0f);
        assertEquals(View.GONE, empty.getVisibility());
    }
}
