package com.hippo.ehviewer.ui;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.os.ParcelFileDescriptor;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.ui.fragment.ForkFeaturesFragment;
import com.hippo.ehviewer.widget.ReversibleSeekBar;
import com.hippo.lib.glgallery.GalleryView;
import com.hippo.preference.SwitchPreference;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Opt-in physical-device flow, using actual touch input and the Fork settings preference. */
@RunWith(AndroidJUnit4.class)
public class ReaderPreviewCarouselDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private File evidence;

    @Test(timeout = 120000) public void browseClickDismissAndTogglePreview() throws Exception {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("readerPreviewCarousel")));
        boolean originalPreview = Settings.getReaderPagePreview(), originalGuide = Settings.getGuideGallery();
        int originalDirection = Settings.getReadingDirection();
        File root = new File(instrumentation.getTargetContext().getCacheDir(), "preview-carousel-" + System.nanoTime());
        assertTrue(root.mkdir());
        evidence = new File(instrumentation.getTargetContext().getExternalFilesDir(null), "reader-preview-carousel");
        assertTrue(evidence.isDirectory() || evidence.mkdirs());
        GalleryActivity reader = null;
        android.app.Activity base = null;
        try {
            Settings.putReaderPagePreview(true); Settings.putGuideGallery(false);
            Settings.putReadingDirection(GalleryView.LAYOUT_LEFT_TO_RIGHT);
            base = open(MainActivity.class, new Intent(instrumentation.getTargetContext(), MainActivity.class));
            android.app.Activity foreground = base;
            instrumentation.runOnMainSync(() -> foreground.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            createPages(root);
            reader = open(GalleryActivity.class, new Intent(instrumentation.getTargetContext(), GalleryActivity.class)
                    .setAction("dir").putExtra("filename", root.getPath()).putExtra("page", 0));
            GalleryActivity live = reader;
            instrumentation.runOnMainSync(() -> {
                live.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                live.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
            waitFor(() -> current(live) == 0 && (Integer) field(live, "mSize") == 24);
            showSlider(live);
            ReversibleSeekBar seek = live.findViewById(R.id.seek_bar);
            RecyclerView strip = live.findViewById(R.id.page_preview_strip);
            tapSeek(seek, 12);
            waitFor(() -> strip.isShown() && strip.getChildCount() > 0);
            assertEquals("Slider tap committed a page", 0, current(live));
            dragSeek(seek, 18);
            assertEquals("Slider drag/release committed a page", 0, current(live));
            SystemClock.sleep(3300);
            assertTrue("Preview was hidden by the old slider timer", strip.isShown());
            assertTrue(strip.getChildCount() <= 6);
            int available = strip.getWidth() - strip.getPaddingLeft() - strip.getPaddingRight();
            assertEquals(available / 4, strip.getChildAt(0).getWidth(), 1);
            int before = strip.getChildAdapterPosition(strip.getChildAt(0));
            swipeStrip(strip);
            waitFor(() -> strip.getScrollState() == RecyclerView.SCROLL_STATE_IDLE);
            int after = strip.getChildAdapterPosition(strip.getChildAt(0));
            assertNotEquals("Thumbnail carousel did not scroll", before, after);
            assertEquals("Horizontal browsing committed a page", 0, current(live));
            screenshot("enabled-browsing.png");
            int chosen = (Integer) field(field(live, "mPagePreview"), "selected");
            View card = strip.getLayoutManager().findViewByPosition(chosen);
            assertNotNull(card);
            tap(card);
            waitFor(() -> current(live) == chosen && !strip.isShown());
            screenshot("thumbnail-chosen.png");

            tapSeek(seek, 5);
            waitFor(strip::isShown);
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            waitFor(() -> !strip.isShown());
            assertFalse(live.isFinishing()); assertEquals(chosen, current(live));
            assertEquals(chosen, seek.getProgress());
            tapSeek(seek, 6); waitFor(strip::isShown);
            tap((View) field(live, "mGLRootView"));
            waitFor(() -> !strip.isShown());
            assertEquals("Outside dismissal also turned a page", chosen, current(live));

            setPreferenceThroughForkScreen(live, false);
            waitFor(() -> field(live, "mPagePreview") == null && live.hasWindowFocus());
            showSlider(live); tapSeek(seek, 8);
            waitFor(() -> current(live) == 8);
            assertFalse(strip.isShown());
            assertFalse((Boolean) field(live, "mPageSeeking"));
            screenshot("disabled-legacy-seek.png");

            setPreferenceThroughForkScreen(live, true);
            waitFor(() -> field(live, "mPagePreview") != null && live.hasWindowFocus());
            showSlider(live); tapSeek(seek, 15);
            waitFor(strip::isShown);
            assertEquals("Re-enabled preview committed on release", 8, current(live));
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        } catch (Exception | AssertionError failure) {
            screenshot("failure.png");
            throw failure;
        } finally {
            Settings.putReaderPagePreview(originalPreview); Settings.putGuideGallery(originalGuide);
            Settings.putReadingDirection(originalDirection);
            if (reader != null) { GalleryActivity live = reader; instrumentation.runOnMainSync(live::finish); }
            if (base != null) { android.app.Activity live = base; instrumentation.runOnMainSync(live::finish); }
            for (File file : root.listFiles()) assertTrue(file.delete());
            assertTrue(root.delete());
        }
    }

    private void setPreferenceThroughForkScreen(GalleryActivity reader, boolean enabled) throws Exception {
        SettingsActivity settings = open(SettingsActivity.class, new Intent(reader, SettingsActivity.class));
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            ForkFeaturesFragment fragment = new ForkFeaturesFragment();
            settings.getSupportFragmentManager().beginTransaction().replace(R.id.settings, fragment).commitNow();
            SwitchPreference preference = fragment.findPreference(Settings.KEY_READER_PAGE_PREVIEW);
            assertNotNull("Fork settings lack the preview switch", preference);
            assertEquals(settings.getString(R.string.settings_reader_page_preview), preference.getTitle());
            assertEquals(!enabled, preference.isChecked());
            fragment.scrollToPreference(preference);
            preference.setChecked(enabled);
            assertEquals(enabled, Settings.getReaderPagePreview());
            settings.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        });
        SystemClock.sleep(350);
        screenshot(enabled ? "fork-setting-enabled.png" : "fork-setting-disabled.png");
        instrumentation.runOnMainSync(settings::finish);
        instrumentation.waitForIdleSync();
    }

    private <T extends android.app.Activity> T open(Class<T> type, Intent intent) throws Exception {
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(type.getName(), null, false);
        try {
            if (type == SettingsActivity.class) {
                // Settings is private to the app and cannot be launched by the shell.
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                instrumentation.runOnMainSync(() -> instrumentation.getTargetContext().startActivity(intent));
            } else {
            // A shell launch avoids vendor background-start restrictions/freezing before the
            // first foreground Activity; keep the same monitor for subsequent screens.
            String command = "am start -W -n " + instrumentation.getTargetContext().getPackageName()
                    + "/" + type.getName();
            if (intent.getAction() != null) command += " -a " + intent.getAction();
            if (intent.hasExtra("filename")) command += " --es filename " + intent.getStringExtra("filename");
            if (intent.hasExtra("page")) command += " --ei page " + intent.getIntExtra("page", 0);
            try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.getUiAutomation().executeShellCommand(command))) {
                while (input.read() != -1) { }
            }
            }
            android.app.Activity activity = instrumentation.waitForMonitorWithTimeout(monitor, 7000);
            assertNotNull(type.getName() + " did not open", activity);
            return type.cast(activity);
        } finally { instrumentation.removeMonitor(monitor); }
    }

    private void showSlider(GalleryActivity reader) {
        instrumentation.runOnMainSync(() -> {
            View panel = reader.findViewById(R.id.seek_bar_panel);
            panel.setVisibility(View.VISIBLE); panel.setTranslationY(0);
        });
        instrumentation.waitForIdleSync();
    }

    private void tapSeek(ReversibleSeekBar seek, int page) {
        int[] point = new int[2]; seek.getLocationOnScreen(point);
        float x = point[0] + seek.getPaddingLeft()
                + (seek.getWidth() - seek.getPaddingLeft() - seek.getPaddingRight()) * (page / (float) seek.getMax());
        long down = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, x, point[1] + seek.getHeight() / 2f, down);
        touch(MotionEvent.ACTION_UP, x, point[1] + seek.getHeight() / 2f, down);
    }

    private void tap(View view) {
        int[] point = new int[2]; view.getLocationOnScreen(point);
        long down = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, point[0] + view.getWidth() / 2f, point[1] + view.getHeight() / 2f, down);
        touch(MotionEvent.ACTION_UP, point[0] + view.getWidth() / 2f, point[1] + view.getHeight() / 2f, down);
    }

    private void dragSeek(ReversibleSeekBar seek, int page) {
        int[] point = new int[2]; seek.getLocationOnScreen(point);
        int width = seek.getWidth() - seek.getPaddingLeft() - seek.getPaddingRight();
        float start = point[0] + seek.getPaddingLeft() + width * (seek.getProgress() / (float) seek.getMax());
        float end = point[0] + seek.getPaddingLeft() + width * (page / (float) seek.getMax());
        float y = point[1] + seek.getHeight() / 2f;
        long down = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, start, y, down);
        for (int i = 1; i <= 12; i++) {
            SystemClock.sleep(25);
            touch(MotionEvent.ACTION_MOVE, start + (end - start) * i / 12, y, down);
        }
        touch(MotionEvent.ACTION_UP, end, y, down);
    }

    private void swipeStrip(RecyclerView row) {
        int[] point = new int[2]; row.getLocationOnScreen(point);
        float start = point[0] + row.getWidth() * .8f, end = point[0] + row.getWidth() * .2f;
        float y = point[1] + row.getHeight() / 2f;
        long down = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, start, y, down);
        for (int i = 1; i <= 12; i++) {
            SystemClock.sleep(25);
            touch(MotionEvent.ACTION_MOVE, start + (end - start) * i / 12, y, down);
        }
        touch(MotionEvent.ACTION_UP, end, y, down);
    }

    private void touch(int action, float x, float y, long down) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        try { instrumentation.sendPointerSync(event); } finally { event.recycle(); }
    }

    private void waitFor(java.util.function.BooleanSupplier ready) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        AtomicBoolean done = new AtomicBoolean();
        do {
            instrumentation.runOnMainSync(() -> done.set(ready.getAsBoolean()));
            if (done.get()) return;
            SystemClock.sleep(15);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Carousel state did not settle; inspect screenshots and logcat");
    }

    private int current(GalleryActivity reader) { return (Integer) field(reader, "mCurrentIndex"); }

    private static Object field(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private void screenshot(String filename) throws Exception {
        // State callbacks precede the next rendered frame; capture the presented UI.
        SystemClock.sleep(150);
        CountDownLatch frames = new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> {
            android.app.Activity resumed = (android.app.Activity) androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                    .getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).iterator().next();
            resumed.getWindow().getDecorView().postOnAnimation(() -> resumed.getWindow().getDecorView().postOnAnimation(frames::countDown));
        });
        assertTrue("Screenshot frame did not settle", frames.await(3, TimeUnit.SECONDS));
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(bitmap);
        try (FileOutputStream out = new FileOutputStream(new File(evidence, filename))) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); }
        bitmap.recycle();
    }

    private void createPages(File root) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(); paint.setColor(Color.BLACK); paint.setTextSize(130);
        Canvas canvas = new Canvas(bitmap);
        for (int page = 0; page < 24; page++) {
            bitmap.eraseColor(Color.rgb((page * 59 + 60) % 210, (page * 41 + 80) % 210, (page * 71 + 90) % 210));
            paint.setColor(Color.WHITE); canvas.drawRect(40, 80, 760, 340, paint);
            paint.setColor(Color.BLACK); canvas.drawText(Integer.toString(page + 1), 90, 250, paint);
            try (FileOutputStream out = new FileOutputStream(new File(root, String.format(Locale.US, "%03d.jpg", page)))) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out));
            }
        }
        bitmap.recycle();
    }
}
