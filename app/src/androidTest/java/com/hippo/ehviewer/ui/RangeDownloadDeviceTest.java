package com.hippo.ehviewer.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.content.Intent;
import android.util.SparseArray;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.download.GalleryDownloadRangeStore;
import com.hippo.ehviewer.gallery.GalleryProvider2;
import com.hippo.ehviewer.gallery.ReadingHistory;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.lib.glgallery.GalleryView;
import com.hippo.streampipe.OutputStreamPipe;
import com.hippo.unifile.UniFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Opt-in, local-only round trip through the real download queue, dialogs, cache and reader. */
@RunWith(AndroidJUnit4.class)
public class RangeDownloadDeviceTest {
    private final Instrumentation in = InstrumentationRegistry.getInstrumentation();
    private GalleryActivity reader;
    private MainActivity main;
    private File evidence;
    private DownloadManager manager;
    private DownloadInfo info;
    private String phase = "setup";

    @Test(timeout = 180000) public void convertEditReadAndCompleteUsingExistingImagesAndCache() throws Exception {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("rangeDownload")));
        manager = EhApplication.getDownloadManager(in.getTargetContext());
        for (DownloadInfo stale : new java.util.ArrayList<>(manager.getAllDownloadInfoList())) {
            if (stale.gid >= 8_000_000_000L && stale.title != null && stale.title.startsWith("Range QA ")) {
                UniFile old = SpiderDen.getExistingGalleryDownloadDir(stale);
                onMain(() -> manager.deleteDownload(stale.gid));
                GalleryDownloadRangeStore.get(in.getTargetContext()).remove(stale.gid);
                if (old != null) old.delete();
                EhDB.removeDownloadDirname(stale.gid);
            }
        }
        Assume.assumeTrue(manager.getAllDownloadInfoList().stream().noneMatch(
                value -> value.state == DownloadInfo.STATE_DOWNLOAD || value.state == DownloadInfo.STATE_WAIT));
        boolean history = Settings.isReadingHistoryEnabled(), guide = Settings.getGuideGallery();
        boolean progress = Settings.getShowProgress(), sync = Settings.getSyncDownloadWhileReading();
        int direction = Settings.getReadingDirection();
        boolean warning = Settings.getShowWarning(), signIn = Settings.getNeedSignIn();
        boolean site = Settings.getSelectSite(), analytics = Settings.getAskAnalytics();
        java.util.Map<String, String> locations = new java.util.HashMap<>();
        for (String key : java.util.List.of(Settings.KEY_DOWNLOAD_SAVE_SCHEME, Settings.KEY_DOWNLOAD_SAVE_AUTHORITY,
                Settings.KEY_DOWNLOAD_SAVE_PATH, Settings.KEY_DOWNLOAD_SAVE_QUERY, Settings.KEY_DOWNLOAD_SAVE_FRAGMENT))
            locations.put(key, Settings.getString(key, null));
        info = new DownloadInfo(8_000_000_000L + SystemClock.elapsedRealtime());
        info.token = "0123456789"; info.title = "Range QA " + info.gid;
        info.pages = info.total = info.finished = info.downloaded = 36; info.state = DownloadInfo.STATE_FINISH;
        UniFile directory = null;
        try {
            Settings.putBoolean(Settings.KEY_RECORD_READING_HISTORY, false);
            Settings.putGuideGallery(false); Settings.putShowProgress(true);
            Settings.putReadingDirection(GalleryView.LAYOUT_LEFT_TO_RIGHT);
            Settings.putSyncDownloadWhileReading(false);
            Settings.putShowWarning(false); Settings.putNeedSignIn(false);
            Settings.putSelectSite(false); Settings.putAskAnalytics(false);
            File testRoot = new File(in.getTargetContext().getCacheDir(), "range-download-test");
            assertTrue(testRoot.isDirectory() || testRoot.mkdirs());
            Settings.putDownloadLocation(UniFile.fromFile(testRoot));
            directory = SpiderDen.getGalleryDownloadDir(info);
            assertNotNull(directory); assertTrue(directory.ensureDir());
            createPagesAndCache(directory);
            // Page 20 exists only in the ordinary reader cache, exercising cache reuse.
            assertTrue(SpiderDen.findImageFile(directory, 19).delete());
            onMain(() -> manager.addDownload(info, null, DownloadInfo.STATE_FINISH));
            DownloadInfo original = manager.getDownloadInfo(info.gid);
            long originalTime = original.time;
            int originalCount = manager.getAllDownloadInfoList().size();
            evidence = new File(in.getTargetContext().getExternalFilesDir(null), "range-download-qa");
            assertTrue(evidence.isDirectory() || evidence.mkdirs());
            main = open(MainActivity.class, null);

            onMain(() -> RangeDownloadDialog.show(main, info, manager));
            phase = "create range from full download";
            editInput(null, "10,11,20,30");
            click(main.getString(R.string.download));
            await(() -> completed(4));
            assertSame(original, manager.getDownloadInfo(info.gid));
            assertEquals(originalCount, manager.getAllDownloadInfoList().size());
            assertEquals(originalTime, original.time);
            assertTrue(SpiderDen.findImageFile(directory, 19).isFile());
            assertNull(SpiderDen.findImageFile(directory, 18));
            assertEquals("10-11,20,30", GalleryDownloadRangeStore.peek(info.gid).range().toString());

            reader = open(GalleryActivity.class, new Intent(in.getTargetContext(), GalleryActivity.class)
                    .setAction(GalleryActivity.ACTION_EH).putExtra(GalleryActivity.KEY_GALLERY_INFO, info)
                    .putExtra(ReadingHistory.KEY_SOURCE, ReadingHistory.DOWNLOAD));
            phase = "open range reader";
            await(() -> rangeAt(0, 9, 4));
            onMain(() -> assertTrue(((TextView) field(reader, "mProgress")).getText().toString().contains("p10/36")));
            screenshot("range-page10.png");
            Settings.putSyncDownloadWhileReading(true);
            chooseMode(R.string.gallery_read_full);
            phase = "switch to full reader";
            await(() -> fullAt(9));
            onMain(() -> ((GalleryView) field(reader, "mGalleryView")).setCurrentPage(18));
            await(() -> fullAt(18));
            assertNull("Full browsing must not add an outside page to the range folder", SpiderDen.findImageFile(directory, 18));
            chooseMode(R.string.range_read_downloaded);
            phase = "return to range reader";
            await(() -> rangeAt(2, 19, 4));

            onMain(() -> RangeDownloadDialog.show(reader, info, manager));
            phase = "edit range while reading";
            editInput("10-11,20,30", "10,20,25");
            click(reader.getString(R.string.download));
            await(() -> completed(3) && rangeAt(1, 19, 3));
            assertNull(SpiderDen.findImageFile(directory, 10));
            assertNull(SpiderDen.findImageFile(directory, 29));
            assertNotNull(SpiderDen.findImageFile(directory, 24));
            assertSame(original, manager.getDownloadInfo(info.gid));
            screenshot("edited-range-page20.png");

            onMain(() -> DownloadRemovalDialog.show(reader, info, manager));
            phase = "upgrade to full download";
            screenshot("range-removal-actions.png");
            GalleryActivity beforeUpgrade = reader;
            click(reader.getString(R.string.range_download_full));
            await(() -> !GalleryDownloadRangeStore.isRange(info.gid) && completed(36));
            await(() -> {
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                    if (activity instanceof GalleryActivity && activity != beforeUpgrade) reader = (GalleryActivity) activity;
                return reader != beforeUpgrade && fullAt(19);
            });
            assertEquals(originalCount, manager.getAllDownloadInfoList().size());
            assertSame(original, manager.getDownloadInfo(info.gid));
            assertEquals(originalTime, original.time);
            for (int page = 0; page < 36; page++) assertNotNull(SpiderDen.findImageFile(directory, page));
            assertNull(directory.findFile(GalleryDownloadRangeStore.FILENAME));
        } finally {
            if (reader != null) onMain(reader::finish);
            if (main != null) onMain(main::finish);
            onMain(() -> manager.deleteDownload(info.gid));
            SystemClock.sleep(3500);
            GalleryDownloadRangeStore.get(in.getTargetContext()).remove(info.gid);
            if (directory != null) directory.delete();
            EhDB.removeDownloadDirname(info.gid);
            Settings.putBoolean(Settings.KEY_RECORD_READING_HISTORY, history);
            Settings.putGuideGallery(guide); Settings.putShowProgress(progress);
            Settings.putSyncDownloadWhileReading(sync); Settings.putReadingDirection(direction);
            Settings.putShowWarning(warning); Settings.putNeedSignIn(signIn);
            Settings.putSelectSite(site); Settings.putAskAnalytics(analytics);
            for (java.util.Map.Entry<String, String> entry : locations.entrySet()) Settings.putString(entry.getKey(), entry.getValue());
        }
    }

    private boolean completed(int count) {
        DownloadInfo current = manager.getDownloadInfo(info.gid);
        return current != null && current.state == DownloadInfo.STATE_FINISH && current.total == count
                && current.finished == count && current.legacy == 0;
    }
    private boolean rangeAt(int index, int source, int count) {
        return (Boolean) field(reader, "mRangeReaderMode") && (Integer) field(reader, "mSize") == count
                && (Integer) field(reader, "mCurrentIndex") == index
                && ((GalleryView) field(reader, "mGalleryView")).getCurrentIndex() == index
                && ((GalleryProvider2) field(reader, "mGalleryProvider")).getSourcePage(index) == source;
    }
    private boolean fullAt(int source) {
        return !(Boolean) field(reader, "mRangeReaderMode") && (Integer) field(reader, "mSize") == 36
                && (Integer) field(reader, "mCurrentIndex") == source;
    }
    private void chooseMode(int resource) throws Exception {
        GalleryActivity previous = reader;
        onMain(() -> reader.onLongPressPage((Integer) field(reader, "mCurrentIndex")));
        await(() -> field(reader, "mPageDialogItems") != null);
        onMain(() -> {
            android.widget.ArrayAdapter<?> adapter = (android.widget.ArrayAdapter<?>) field(reader, "mPageDialogItems");
            StringBuilder labels = new StringBuilder();
            for (int i = 0; i < adapter.getCount(); i++) labels.append(adapter.getItem(i)).append('|');
            android.util.Log.i("RangeDeviceQA", "Mode menu: " + labels);
        });
        screenshot("mode-menu.png");
        click(reader.getString(resource));
        await(() -> {
            for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                if (activity instanceof GalleryActivity && activity != previous) { reader = (GalleryActivity) activity; return true; }
            return false;
        });
    }
    private <T extends Activity> T open(Class<T> type, Intent intent) throws Exception {
        Instrumentation.ActivityMonitor monitor = in.addMonitor(type.getName(), null, false);
        try {
            if (intent == null) {
                String command = "am start -W -n " + in.getTargetContext().getPackageName() + "/" + type.getName();
                try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(in.getUiAutomation().executeShellCommand(command))) {
                    while (input.read() != -1) { }
                }
            } else onMain(() -> in.getTargetContext().startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            Activity activity = in.waitForMonitorWithTimeout(monitor, 8000);
            assertNotNull("Activity did not open: " + type, activity);
            return type.cast(activity);
        } finally { in.removeMonitor(monitor); }
    }
    private void editInput(String expected, String replacement) throws Exception {
        in.waitForIdleSync();
        AccessibilityNodeInfo root = in.getUiAutomation().getRootInActiveWindow(); assertNotNull(root);
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>(); queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.remove();
            if (node.isEditable()) {
                if (expected != null) assertEquals(expected, node.getText().toString());
                Bundle args = new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replacement);
                assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args));
                in.waitForIdleSync(); return;
            }
            for (int i = 0; i < node.getChildCount(); i++) if (node.getChild(i) != null) queue.add(node.getChild(i));
        }
        fail("No page-range input field");
    }
    private void click(String text) {
        long deadline = SystemClock.elapsedRealtime() + 3000;
        do {
            in.waitForIdleSync();
            AccessibilityNodeInfo root = in.getUiAutomation().getRootInActiveWindow();
            if (root != null && !root.findAccessibilityNodeInfosByText("Viewing full screen").isEmpty()) {
                for (AccessibilityNodeInfo acknowledgement : root.findAccessibilityNodeInfosByText("Got it"))
                    acknowledgement.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                SystemClock.sleep(150);
                continue;
            }
            if (root != null) for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) {
                if (node.getText() == null || !text.equalsIgnoreCase(node.getText().toString()) || !node.isVisibleToUser()) continue;
                android.graphics.Rect bounds = new android.graphics.Rect(); node.getBoundsInScreen(bounds);
                if (bounds.isEmpty()) continue;
                long down = SystemClock.uptimeMillis();
                android.view.MotionEvent press = android.view.MotionEvent.obtain(down, down,
                        android.view.MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY(), 0);
                android.view.MotionEvent release = android.view.MotionEvent.obtain(down, down + 60,
                        android.view.MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY(), 0);
                try { in.sendPointerSync(press); in.sendPointerSync(release); }
                finally { press.recycle(); release.recycle(); }
                return;
            }
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        AccessibilityNodeInfo root = in.getUiAutomation().getRootInActiveWindow();
        StringBuilder labels = new StringBuilder();
        if (root != null) {
            ArrayDeque<AccessibilityNodeInfo> nodes = new ArrayDeque<>(); nodes.add(root);
            while (!nodes.isEmpty()) {
                AccessibilityNodeInfo node = nodes.remove();
                if (node.getText() != null) labels.append(node.getText()).append('|');
                for (int i = 0; i < node.getChildCount(); i++) if (node.getChild(i) != null) nodes.add(node.getChild(i));
            }
        }
        fail("Action not found: " + text + "; visible text: " + labels);
    }
    private void await(BooleanSupplier ready) {
        long deadline = SystemClock.elapsedRealtime() + 20000;
        boolean[] done = {false};
        do {
            onMain(() -> done[0] = ready.getAsBoolean());
            if (done[0]) return;
            SystemClock.sleep(25);
        } while (SystemClock.elapsedRealtime() < deadline);
        DownloadInfo current = manager.getDownloadInfo(info.gid);
        fail("Range flow timed out at " + phase + "; download state=" + (current != null ? current.state + " " + current.finished + "/" + current.total : "missing"));
    }
    private void onMain(Runnable action) { in.runOnMainSync(action); }
    private Object field(Object object, String name) {
        try { Field field = GalleryActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void screenshot(String name) throws Exception {
        onMain(() -> reader.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));
        SystemClock.sleep(200);
        Bitmap bitmap = in.getUiAutomation().takeScreenshot(); assertNotNull(bitmap);
        try (FileOutputStream output = new FileOutputStream(new File(evidence, name))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); }
        bitmap.recycle();
    }
    private void createPagesAndCache(UniFile directory) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(); paint.setColor(Color.BLACK); paint.setTextSize(100);
        Canvas canvas = new Canvas(bitmap);
        SpiderInfo metadata = new SpiderInfo(); metadata.gid = info.gid; metadata.token = info.token;
        metadata.pages = 36; metadata.previewPages = 1; metadata.previewPerPage = 40; metadata.pTokenMap = new SparseArray<>();
        SpiderDen den = new SpiderDen(info); den.setMode(SpiderQueen.MODE_READ);
        for (int page = 0; page < 36; page++) {
            bitmap.eraseColor(Color.rgb(180, 210, 170 + page)); canvas.drawText("Page " + (page + 1), 40, 200, paint);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
            byte[] image = bytes.toByteArray();
            try (OutputStream output = directory.createFile(String.format(Locale.US, "%08d.png", page + 1)).openOutputStream()) { output.write(image); }
            OutputStreamPipe pipe = den.openOutputStreamPipe(page, "png"); assertNotNull(pipe);
            try { pipe.obtain(); pipe.open().write(image); } finally { pipe.close(); pipe.release(); }
            metadata.pTokenMap.put(page, String.format(Locale.US, "%010x", page + 1));
        }
        bitmap.recycle(); metadata.write(directory.createFile(SpiderQueen.SPIDER_INFO_FILENAME).openOutputStream());
    }
}
