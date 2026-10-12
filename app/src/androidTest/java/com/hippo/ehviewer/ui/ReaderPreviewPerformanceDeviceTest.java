package com.hippo.ehviewer.ui;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.Trace;
import android.system.Os;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.gallery.ReaderPreviewBitmapCache;
import com.hippo.ehviewer.widget.ReversibleSeekBar;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Opt-in real-device measurements, through the actual reader and its GL surface. */
@RunWith(AndroidJUnit4.class)
public class ReaderPreviewPerformanceDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final JSONArray measurements = new JSONArray();
    private String label;
    private File report;

    @Test(timeout = 240000) public void measureDecodeColdWarmAndScrub() throws Exception {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("readerPreviewPerf")));
        label = InstrumentationRegistry.getArguments().getString("label", "baseline");
        File root = new File(instrumentation.getTargetContext().getCacheDir(), "preview-perf-" + label + "-" + System.nanoTime());
        assertTrue(root.mkdirs());
        File evidence = new File(instrumentation.getTargetContext().getExternalFilesDir(null), "reader-preview-perf");
        evidence.mkdirs();
        report = new File(evidence, label + ".json");
        boolean guide = Settings.getGuideGallery();
        Settings.putGuideGallery(false);
        // Keep an activity resumed between cases: vendor background freezing otherwise suspends
        // the instrumentation thread in native decode, invalidating wall-clock measurements.
        Instrumentation.ActivityMonitor baseMonitor = instrumentation.addMonitor(MainActivity.class.getName(), null, false);
        try (ParcelFileDescriptor.AutoCloseInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.getUiAutomation().executeShellCommand("am start -W -n "
                        + instrumentation.getTargetContext().getPackageName() + "/" + MainActivity.class.getName()))) {
            while (in.read() != -1) { }
        }
        android.app.Activity base = instrumentation.waitForMonitorWithTimeout(baseMonitor, 7000);
        instrumentation.removeMonitor(baseMonitor);
        assertNotNull("Foreground test activity did not open", base);
        instrumentation.runOnMainSync(() -> base.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        try {
            String cases = InstrumentationRegistry.getArguments().getString("cases", "jpeg,png,webp,png-noise");
            for (String name : cases.split(",")) {
                File folder = fixture(root, name);
                String mode = InstrumentationRegistry.getArguments().getString("mode", "full");
                if (mode.equals("full") || mode.equals("decode"))
                    benchmarkDecode(name, new File(folder, "000.image." + extension(name)));
                if ("decode".equals(InstrumentationRegistry.getArguments().getString("mode"))) continue;
                if (!mode.equals("scrub")) measureReader(name, folder, false);
                int scrubRuns = Math.max(1, Math.min(5, Integer.parseInt(InstrumentationRegistry.getArguments().getString("scrubRuns", "1"))));
                for (int run = 0; run < scrubRuns; run++) {
                    File scrubFolder = new File(root, name + "-scrub-" + run);
                    scrubFolder.mkdirs();
                    for (int page = 0; page < 64; page++) linkFixture(
                            new File(folder, String.format(Locale.US, "%03d.image.%s", page, extension(name))),
                            new File(scrubFolder, String.format(Locale.US, "%03d.image.%s", page, extension(name))));
                    measureReader(name, scrubFolder, true);
                }
            }
        } finally {
            Settings.putGuideGallery(guide);
            writeReport();
            deleteFixture(root);
            instrumentation.runOnMainSync(base::finish);
        }
    }

    private File fixture(File root, String name) throws Exception {
        File folder = new File(root, name);
        folder.mkdirs();
        Bitmap page = Bitmap.createBitmap(2400, 3600, Bitmap.Config.ARGB_8888);
        page.eraseColor(Color.WHITE);
        Canvas canvas = new Canvas(page);
        Paint paint = new Paint();
        paint.setColor(Color.BLACK); paint.setTextSize(80);
        if (name.equals("png-noise")) {
            int[] row = new int[2400];
            int seed = 12345;
            for (int y = 0; y < 3600; y++) {
                for (int x = 0; x < row.length; x++) { seed = seed * 1664525 + 1013904223; row[x] = 0xFF000000 | (seed >>> 8); }
                page.setPixels(row, 0, row.length, 0, y, row.length, 1);
            }
        } else for (int y = 100; y < 3600; y += 120) {
            canvas.drawText("Reader preview benchmark 0123456789", 60, y, paint);
            canvas.drawRect(1300, y, 2250, y + 20, paint);
        }
        File first = new File(folder, "000.image." + extension(name));
        Bitmap.CompressFormat format = name.equals("jpeg") ? Bitmap.CompressFormat.JPEG
                : name.equals("webp") ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.PNG;
        try (FileOutputStream out = new FileOutputStream(first)) { assertTrue(page.compress(format, 90, out)); }
        page.recycle();
        // Unique paths, without allocating 64 copies of a large encoded image.
        for (int i = 1; i < 64; i++) linkFixture(first,
                new File(folder, String.format(Locale.US, "%03d.image.%s", i, extension(name))));
        return folder;
    }

    private static String extension(String name) { return name.equals("jpeg") ? "jpg" : name.equals("webp") ? "webp" : "png"; }

    private static void linkFixture(File original, File copy) throws Exception {
        try { Os.symlink(original.getPath(), copy.getPath()); }
        catch (android.system.ErrnoException unsupported) {
            java.nio.file.Files.copy(original.toPath(), copy.toPath());
        }
    }

    private void benchmarkDecode(String name, File file) throws Exception {
        String[] paths = "decode".equals(InstrumentationRegistry.getArguments().getString("mode"))
                ? new String[] {"decoder-sample", "decoder-mapped", "decoder-sample32", "decoder-sample64"}
                : new String[] {"bounds", "factory-sample", "decoder-exact", "decoder-sample", "decoder-original"};
        for (String path : paths) {
            long[] values = new long[5];
            for (int i = 0; i < 7; i++) {
                Trace.beginSection("PreviewPerf." + path);
                long began = SystemClock.elapsedRealtimeNanos();
                Bitmap decoded = null;
                if (path.equals("bounds") || path.equals("factory-sample")) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    try (FileInputStream in = new FileInputStream(file)) { BitmapFactory.decodeStream(in, null, options); }
                    if (path.equals("factory-sample")) {
                        options.inJustDecodeBounds = false; options.inSampleSize = 16;
                        try (FileInputStream in = new FileInputStream(file)) { decoded = BitmapFactory.decodeStream(in, null, options); }
                    }
                } else {
                    try (FileInputStream input = new FileInputStream(file)) {
                        ImageDecoder.Source source = path.equals("decoder-mapped")
                                ? ImageDecoder.createSource(input.getChannel().map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, file.length()))
                                : ImageDecoder.createSource(file);
                        decoded = ImageDecoder.decodeBitmap(source, (decoder, info, ignored) -> {
                            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                            if (path.equals("decoder-exact")) decoder.setTargetSize(170, 256);
                            else if (!path.equals("decoder-original"))
                                decoder.setTargetSampleSize(path.equals("decoder-sample64") ? 64 : path.equals("decoder-sample32") ? 32 : 16);
                        });
                    }
                }
                long elapsed = SystemClock.elapsedRealtimeNanos() - began;
                Trace.endSection();
                if (i >= 2) values[i - 2] = elapsed;
                if (decoded != null) decoded.recycle();
            }
            Arrays.sort(values);
            measurements.put(new JSONObject().put("case", name).put("phase", path).put("bytes", file.length())
                    .put("medianMs", values[2] / 1e6).put("maxMs", values[4] / 1e6).put("runs", 5));
            writeReport();
        }
    }

    private void measureReader(String name, File folder, boolean scrub) throws Exception {
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(GalleryActivity.class.getName(), null, false);
        GalleryActivity activity = null;
        try {
            String command = "am start -W -n " + instrumentation.getTargetContext().getPackageName() + "/" + GalleryActivity.class.getName()
                    + " -a dir --es filename " + folder.getPath() + " --ei page 0";
            try (ParcelFileDescriptor.AutoCloseInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.getUiAutomation().executeShellCommand(command))) {
                while (in.read() != -1) { }
            }
            activity = (GalleryActivity) instrumentation.waitForMonitorWithTimeout(monitor, 7000);
            assertNotNull("Reader did not open", activity);
            GalleryActivity reader = activity;
            waitFor(() -> ((Integer) field(reader, "mSize")) == 64, 10000);
            instrumentation.runOnMainSync(() -> {
                reader.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                reader.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                View panel = (View) field(reader, "mSeekBarPanel");
                panel.setVisibility(View.VISIBLE);
                panel.setTranslationY(0);
            });
            // Let startup and the already displayed page finish before measuring seek preview.
            SystemClock.sleep(600);
            ReversibleSeekBar seek = reader.findViewById(R.id.seek_bar);
            if (!scrub) {
                for (int target : new int[] {10, 20, 30, 40, 50}) {
                    timePreview(name, "cold", reader, seek, target);
                    timePreview(name, "memory", reader, seek, target);
                }
            } else {
                long began = SystemClock.elapsedRealtimeNanos();
                DrawProbe probe = new DrawProbe(reader);
                instrumentation.runOnMainSync(() -> reader.onStartTrackingTouch(seek));
                for (int target = 3; target <= 60; target += 3) {
                    int page = target;
                    instrumentation.runOnMainSync(() -> {
                        if (page == 60) probe.begin();
                        seek.setProgress(page); reader.onProgressChanged(seek, page, true);
                    });
                    SystemClock.sleep(16);
                }
                probe.await();
                measurements.put(new JSONObject().put("case", name).put("phase", "scrub")
                        .put("centerAfterReleaseMs", probe.centerMs()).put("windowAfterReleaseMs", probe.windowMs())
                        .put("totalMs", (probe.centerAt - began) / 1e6));
                writeReport();
            }
            CountDownLatch presented = new CountDownLatch(1);
            instrumentation.runOnMainSync(() -> reader.findViewById(R.id.page_preview_strip).postOnAnimation(
                    () -> reader.findViewById(R.id.page_preview_strip).postOnAnimation(presented::countDown)));
            assertTrue("Screenshot frame timeout", presented.await(3, TimeUnit.SECONDS));
            Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
            if (screenshot != null) {
                try (FileOutputStream out = new FileOutputStream(new File(report.getParentFile(), label + "-" + name + (scrub ? "-scrub" : "") + ".png"))) {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                screenshot.recycle();
            }
        } finally {
            instrumentation.removeMonitor(monitor);
            if (activity != null) { GalleryActivity reader = activity; instrumentation.runOnMainSync(reader::finish); instrumentation.waitForIdleSync(); }
        }
    }

    private void timePreview(String name, String phase, GalleryActivity reader, ReversibleSeekBar seek, int target) throws Exception {
        instrumentation.runOnMainSync(() -> ((ReaderPagePreview) field(reader, "mPagePreview")).hide());
        DrawProbe probe = new DrawProbe(reader);
        long[] bound = {-1};
        instrumentation.runOnMainSync(() -> {
            probe.begin();
            Trace.beginSection("PreviewPerf." + name + "." + phase);
            try {
                seek.setProgress(target); reader.onStartTrackingTouch(seek); reader.onProgressChanged(seek, target, true);
                if (centerReady(reader)) bound[0] = SystemClock.elapsedRealtimeNanos() - probe.began;
            } finally { Trace.endSection(); }
        });
        probe.await();
        measurements.put(new JSONObject().put("case", name).put("phase", phase).put("page", target)
                .put("centerMs", probe.centerMs()).put("windowMs", probe.windowMs()).put("syncBindMs", bound[0] / 1e6));
        writeReport();
    }

    /** First draw containing the requested drawable, independent of instrumentation idle waits. */
    private final class DrawProbe implements ViewTreeObserver.OnDrawListener {
        final GalleryActivity reader;
        final CountDownLatch center = new CountDownLatch(1), window = new CountDownLatch(1);
        long began, centerAt, windowAt;
        DrawProbe(GalleryActivity reader) { this.reader = reader; }
        void begin() {
            began = SystemClock.elapsedRealtimeNanos();
            reader.findViewById(R.id.page_preview_strip).getViewTreeObserver().addOnDrawListener(this);
        }
        @Override public void onDraw() {
            View strip = reader.findViewById(R.id.page_preview_strip);
            android.graphics.Rect bounds = new android.graphics.Rect();
            if (!strip.isShown() || strip.getHeight() == 0 || !strip.getGlobalVisibleRect(bounds)) return;
            long now = SystemClock.elapsedRealtimeNanos();
            if (centerAt == 0 && centerReady(reader)) { centerAt = now; center.countDown(); }
            if (windowAt == 0 && allReady(reader)) { windowAt = now; window.countDown(); }
        }
        void await() throws Exception {
            try {
                assertTrue("Center draw timeout", center.await(15, TimeUnit.SECONDS));
                assertTrue("Window draw timeout", window.await(15, TimeUnit.SECONDS));
            } finally {
                instrumentation.runOnMainSync(() -> reader.findViewById(R.id.page_preview_strip)
                        .getViewTreeObserver().removeOnDrawListener(this));
            }
        }
        double centerMs() { return (centerAt - began) / 1e6; }
        double windowMs() { return (windowAt - began) / 1e6; }
    }

    private boolean centerReady(GalleryActivity reader) {
        LinearLayout row = reader.findViewById(R.id.page_preview_strip);
        return ((ImageView) ((LinearLayout) row.getChildAt(2)).getChildAt(0)).getDrawable() != null;
    }

    private boolean allReady(GalleryActivity reader) {
        LinearLayout row = reader.findViewById(R.id.page_preview_strip);
        for (int i = 0; i < row.getChildCount(); i++) if (((ImageView) ((LinearLayout) row.getChildAt(i)).getChildAt(0)).getDrawable() == null) return false;
        return true;
    }

    private void waitFor(java.util.function.BooleanSupplier ready, long timeout) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        AtomicBoolean complete = new AtomicBoolean();
        do {
            instrumentation.runOnMainSync(() -> complete.set(ready.getAsBoolean()));
            if (complete.get()) return;
            SystemClock.sleep(2);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Preview timeout, see ReaderPreview logcat and partial JSON report");
    }

    private static Object field(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private void writeReport() throws Exception {
        JSONObject json = new JSONObject().put("label", label).put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("model", android.os.Build.MODEL).put("measurements", measurements);
        try (FileOutputStream out = new FileOutputStream(report)) { out.write(json.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    }

    private static void deleteFixture(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteFixture(child);
        file.delete();
    }
}
