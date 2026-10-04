package com.hippo.ehviewer.ui.scene.gallery.detail;

import android.app.Activity;
import android.app.Application;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.download.GalleryUpdateRecord;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE)
public class GalleryUpdateLogDialogTest {
    private GalleryUpdateRecord record(boolean complete) {
        return ReflectionHelpers.callConstructor(GalleryUpdateRecord.class,
                ReflectionHelpers.ClassParameter.from(long.class, 200L),
                ReflectionHelpers.ClassParameter.from(long.class, 100L),
                ReflectionHelpers.ClassParameter.from(long.class, 1791076310000L),
                ReflectionHelpers.ClassParameter.from(int.class, 100),
                ReflectionHelpers.ClassParameter.from(int.class, 121),
                ReflectionHelpers.ClassParameter.from(boolean.class, complete),
                ReflectionHelpers.ClassParameter.from(int[].class, new int[]{99,100,101,102,103,104,105,106,107,119,120}),
                ReflectionHelpers.ClassParameter.from(int[].class, new int[]{0,1}),
                ReflectionHelpers.ClassParameter.from(int.class, 0));
    }

    @Test public void versionHistoryIsIdleUntilClickedAndLogUsesCorrectPageRanges() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.AppTheme);
        AtomicInteger historyLoads = new AtomicInteger();
        AlertDialog dialog = GalleryUpdateLogDialog.show(activity, record(true), historyLoads::incrementAndGet);
        ShadowLooper.idleMainLooper();
        assertEquals(0, historyLoads.get());
        String text = text(dialog.getWindow().getDecorView());
        assertTrue(text.contains(activity.getString(R.string.gallery_update_log_added, 11)));
        assertTrue(text.contains(activity.getString(R.string.gallery_update_log_deleted, 2)));
        assertTrue(text.contains("p100–108, p120–121"));
        assertTrue(text.contains("p1–2"));
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(1, historyLoads.get());
        activity.finish();
    }

    @Test public void incompleteMetadataDisplaysNoticeInsteadOfDefinitiveCounts() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.AppTheme);
        AlertDialog dialog = GalleryUpdateLogDialog.show(activity, record(false), () -> {});
        String text = text(dialog.getWindow().getDecorView());
        assertTrue(text.contains(activity.getString(R.string.gallery_update_log_incomplete)));
        assertFalse(text.contains("p100"));
        dialog.dismiss(); activity.finish();
    }

    private String text(View view) {
        StringBuilder text = new StringBuilder();
        if (view instanceof TextView label) text.append(label.getText()).append('\n');
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) text.append(text(group.getChildAt(i)));
        }
        return text.toString();
    }
}
