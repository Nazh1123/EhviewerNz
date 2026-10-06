package com.hippo.ehviewer.ui;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.gallery.GalleryProvider2;
import com.hippo.lib.glgallery.GalleryView;
import com.hippo.lib.glgallery.ReaderKeyMap;
import com.hippo.unifile.UniFile;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
public class ReaderKeySaveTest {
    private GalleryActivity activity;
    private SaveProvider provider;
    private Context originalContext;
    private com.hippo.unifile.UriHandler uriHandler;

    @Before public void setup() {
        Context context = RuntimeEnvironment.getApplication();
        originalContext = ReflectionHelpers.getStaticField(Settings.class, "sContext");
        ReflectionHelpers.setStaticField(Settings.class, "sContext", context);
        android.content.SharedPreferences prefs = context.getSharedPreferences("reader-key-save-test", 0);
        prefs.edit().clear().commit();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre", prefs);
        File directory = new File(context.getCacheDir(), "key-saves");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        UniFile destination = UniFile.fromFile(directory);
        // Keep Windows drive-letter paths intact while exercising the real save pipeline.
        uriHandler = (app, uri) -> uri.equals(destination.getUri()) ? destination : null;
        UniFile.addUriHandler(uriHandler);
        Settings.putManualImageSaveLocation(destination);
        assertNotNull("Manual destination must resolve before exercising the save", Settings.getManualImageSaveLocation());
        activity = Robolectric.buildActivity(GalleryActivity.class).get();
        activity.setTheme(R.style.AppTheme_Gallery_Dark);
        provider = new SaveProvider();
        ReflectionHelpers.setField(activity, "mGalleryProvider", provider);
        ReflectionHelpers.setField(activity, "mAnimatedWebpLifecycleResumed", true);
        ReflectionHelpers.setField(activity, "mSize", 5);
        ReflectionHelpers.setField(activity, "mCurrentIndex", 2);
        ReflectionHelpers.setField(activity, "mLayoutMode", GalleryView.LAYOUT_LEFT_TO_RIGHT);
        View notice = new View(activity);
        notice.setVisibility(View.GONE);
        ReflectionHelpers.setField(activity, "mSaveNotice", notice);
        ReflectionHelpers.setField(activity, "mSaveNoticeIcon", new ImageView(activity));
    }

    @After public void cleanup() {
        UniFile.removeUriHandler(uriHandler);
        ((ExecutorService) ReflectionHelpers.getField(activity, "mImageFileExecutor")).shutdownNow();
        ((ExecutorService) ReflectionHelpers.getField(activity, "transferService")).shutdownNow();
        ((android.os.Handler) ReflectionHelpers.getField(activity, "mAnimatedWebpHandler")).removeCallbacksAndMessages(null);
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre", null);
        ReflectionHelpers.setStaticField(Settings.class, "sContext", originalContext);
    }

    @Test public void savePreviousUsesExistingDestinationNoticeUndoAndDebounce() throws Exception {
        dispatch(ReaderKeyMap.SAVE_PREVIOUS, 2);
        assertEquals(1, provider.savedPage);
        assertEquals(1, provider.calls);
        assertEquals(1, (int) ReflectionHelpers.getField(activity, "mSaveNoticeUndoPage"));
        assertNotNull(ReflectionHelpers.getField(activity, "mSaveNoticeUndoFile"));
        assertEquals(2, (int) ReflectionHelpers.getField(activity, "mCurrentIndex"));
        ImageView icon = ReflectionHelpers.getField(activity, "mSaveNoticeIcon");
        assertEquals(R.drawable.v_save_previous_x24, shadowOf(icon.getDrawable()).getCreatedFromResId());
        ReflectionHelpers.setField(activity, "mLastLongPressSaveAt", Math.max(1L, SystemClock.elapsedRealtime()));
        dispatch(ReaderKeyMap.SAVE_PREVIOUS, 2);
        assertEquals(1, provider.calls);
        ReflectionHelpers.setField(activity, "mCurrentIndex", 0);
        dispatch(ReaderKeyMap.SAVE_PREVIOUS, 0);
        assertEquals(1, provider.calls);
    }

    @Test public void sequentialSaveUsesPreviousPageAndFallsBackToImageMenu() throws Exception {
        Settings.putExperimentalAnimatedWebpEnabled(true);
        Settings.putAnimatedWebpAutoAdvance(true);
        dispatch(ReaderKeyMap.SAVE_PREVIOUS_SEQUENTIAL, 2);
        assertEquals(1, provider.savedPage);
        Settings.putAnimatedWebpAutoAdvance(false);
        dispatch(ReaderKeyMap.SAVE_PREVIOUS_SEQUENTIAL, 2);
        assertEquals(1, provider.calls);
        assertTrue(ShadowDialog.getLatestDialog().isShowing());
        ShadowDialog.getLatestDialog().dismiss();
        Settings.putAnimatedWebpAutoAdvance(true);
        ReflectionHelpers.setField(activity, "mLayoutMode", GalleryView.LAYOUT_TOP_TO_BOTTOM);
        dispatch(ReaderKeyMap.SAVE_PREVIOUS_SEQUENTIAL, 2);
        assertEquals(1, provider.calls);
        assertTrue(ShadowDialog.getLatestDialog().isShowing());
    }

    private void dispatch(int action, int index) throws Exception {
        activity.onReaderKeyAction(action, index);
        shadowOf(Looper.getMainLooper()).idle();
        ExecutorService executor = ReflectionHelpers.getField(activity, "mImageFileExecutor");
        executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static final class SaveProvider extends GalleryProvider2 {
        int savedPage = -1, calls;
        @Override public int size() { return 5; }
        @Override public String getError() { return null; }
        @Override protected void onRequest(int index) { }
        @Override protected void onForceRequest(int index) { }
        @Override protected void onCancelRequest(int index) { }
        @Override public String getImageFilename(int index) { return "page-" + index; }
        @Override public boolean save(int index, UniFile file) { throw new AssertionError(); }
        @Override public UniFile save(int index, UniFile dir, String name) { throw new AssertionError(); }
        @Override public SaveResult saveWithResult(int index, UniFile dir, String name) {
            savedPage = index; calls++;
            return prepareSaveDestination(dir, name + ".png");
        }
    }
}
