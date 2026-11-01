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
import com.hippo.ehviewer.download.DownloadService;
import com.hippo.ehviewer.download.GalleryDownloadRangeStore;
import com.hippo.ehviewer.download.GalleryUpdateManager;
import com.hippo.ehviewer.download.GalleryUpdateRecordStore;
import com.hippo.ehviewer.gallery.GalleryProvider2;
import com.hippo.ehviewer.gallery.ReadingHistory;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene;
import com.hippo.lib.glgallery.GalleryView;
import com.hippo.scene.Announcer;
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

    @Test(timeout = 240000) public void convertEditReadAndCompleteUsingExistingImagesAndCache() throws Exception {
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
        boolean versionPrompt = Settings.isGalleryVersionUpdatePromptPending();
        java.util.Map<String, String> locations = new java.util.HashMap<>();
        for (String key : java.util.List.of(Settings.KEY_DOWNLOAD_SAVE_SCHEME, Settings.KEY_DOWNLOAD_SAVE_AUTHORITY,
                Settings.KEY_DOWNLOAD_SAVE_PATH, Settings.KEY_DOWNLOAD_SAVE_QUERY, Settings.KEY_DOWNLOAD_SAVE_FRAGMENT))
            locations.put(key, Settings.getString(key, null));
        info = new DownloadInfo(8_000_000_000L + SystemClock.elapsedRealtime());
        info.token = "0123456789"; info.title = "Range QA " + info.gid;
        info.firstGid = info.gid;
        info.pages = info.total = info.finished = info.downloaded = 36; info.state = DownloadInfo.STATE_FINISH;
        UniFile directory = null;
        try {
            Settings.putBoolean(Settings.KEY_RECORD_READING_HISTORY, false);
            Settings.putGuideGallery(false); Settings.putShowProgress(true);
            Settings.putReadingDirection(GalleryView.LAYOUT_LEFT_TO_RIGHT);
            Settings.putSyncDownloadWhileReading(false);
            Settings.putShowWarning(false); Settings.putNeedSignIn(false);
            Settings.putSelectSite(false); Settings.putAskAnalytics(false);
            Settings.setGalleryVersionUpdatePromptPending(false);
            File testRoot = new File(in.getTargetContext().getCacheDir(), "range-download-test");
            assertTrue(testRoot.isDirectory() || testRoot.mkdirs());
            Settings.putDownloadLocation(UniFile.fromFile(testRoot));
            directory = SpiderDen.getGalleryDownloadDir(info);
            assertNotNull(directory); assertTrue(directory.ensureDir());
            createPagesAndCache(directory, info);
            // Page 20 exists only in the ordinary reader cache, exercising cache reuse.
            assertTrue(SpiderDen.findImageFile(directory, 19).delete());
            onMain(() -> manager.addDownload(info, null, DownloadInfo.STATE_FINISH));
            DownloadInfo original = manager.getDownloadInfo(info.gid);
            long originalTime = original.time;
            int originalCount = manager.getAllDownloadInfoList().size();
            evidence = new File(in.getTargetContext().getExternalFilesDir(null), "range-download-qa");
            assertTrue(evidence.isDirectory() || evidence.mkdirs());
            main = open(MainActivity.class, null);
            phase = "detail range-download entry";
            Bundle detail = new Bundle();
            detail.putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_DOWNLOAD_GALLERY_INFO);
            detail.putParcelable(GalleryDetailScene.KEY_GALLERY_INFO, original);
            onMain(() -> main.startScene(new Announcer(GalleryDetailScene.class).setArgs(detail)));
            await(() -> main.findViewById(R.id.range_download) != null
                    && main.findViewById(R.id.range_download).isShown());
            await(() -> ((TextView) main.findViewById(R.id.rating_text)).getText().toString()
                    .contains(main.getString(R.string.offline_gallery_notice)));
            click(main.getString(R.string.range_download));
            phase = "create range from full download";
            editInput(null, "0,37");
            click(main.getString(R.string.download));
            assertFalse(GalleryDownloadRangeStore.isRange(info.gid));
            editInput(null, "10,11,20,30");
            click(main.getString(R.string.download));
            await(() -> completed(4));
            assertSame(original, manager.getDownloadInfo(info.gid));
            assertEquals(originalCount, manager.getAllDownloadInfoList().size());
            assertEquals(originalTime, original.time);
            assertTrue(SpiderDen.findImageFile(directory, 19).isFile());
            assertNull(SpiderDen.findImageFile(directory, 18));
            assertEquals("10-11,20,30", GalleryDownloadRangeStore.peek(info.gid).range().toString());
            phase = "cached outside page while sharing the active download reader";
            SpiderDen shared = new SpiderDen(info);
            shared.setMode(SpiderQueen.MODE_DOWNLOAD);
            assertTrue("A full reader sharing the range downloader must reuse cached outside pages", shared.contain(18));
            com.hippo.streampipe.InputStreamPipe outside = shared.openInputStreamPipe(18);
            assertNotNull("Cached outside pages must be readable while the range queue is active", outside);
            try { outside.obtain(); assertTrue(outside.open().read() >= 0); }
            finally { outside.close(); outside.release(); }
            assertNull(SpiderDen.findImageFile(directory, 18));
            phase = "detail downloaded-range state";
            await(() -> ((TextView) main.findViewById(R.id.download)).getText().toString()
                    .equals(main.getString(R.string.range_downloaded)));
            phase = "offline detail reloads pruned thumbnails";
            await(() -> {
                android.view.ViewGroup previews = main.findViewById(R.id.grid_layout);
                return previews.getChildCount() == 4
                        && Integer.valueOf(9).equals(previews.getChildAt(0).findViewById(R.id.image).getTag(R.id.index));
            });
            screenshot("detail-range.png");
            click(main.getString(R.string.range_downloaded));
            click(main.getString(R.string.range_download_full));
            await(() -> completed(36) && !GalleryDownloadRangeStore.isRange(info.gid));
            // Re-enter through the detail action to verify its label and listener remain current.
            click(main.getString(R.string.range_download));
            editInput(null, "10,11,20,30");
            click(main.getString(R.string.download));
            await(() -> completed(4));

            reader = open(GalleryActivity.class, new Intent(in.getTargetContext(), GalleryActivity.class)
                    .setAction(GalleryActivity.ACTION_EH).putExtra(GalleryActivity.KEY_GALLERY_INFO, info)
                    .putExtra(GalleryActivity.KEY_PAGE, 19)
                    .putExtra(ReadingHistory.KEY_SOURCE, ReadingHistory.PREVIEW));
            phase = "open range reader";
            await(() -> rangeAt(2, 19, 4));
            onMain(() -> assertTrue(((TextView) field(reader, "mProgress")).getText().toString().contains("p20/36")));
            screenshot("range-page20.png");
            phase = "range recreation preserves source page";
            GalleryActivity beforeRecreation = reader;
            onMain(reader::recreate);
            await(() -> findNewReader(beforeRecreation) && rangeAt(2, 19, 4));
            onMain(reader::finish);
            in.waitForIdleSync();
            reader = open(GalleryActivity.class, new Intent(in.getTargetContext(), GalleryActivity.class)
                    .setAction(GalleryActivity.ACTION_EH).putExtra(GalleryActivity.KEY_GALLERY_INFO, info));
            phase = "reopen saved range progress";
            await(() -> rangeAt(2, 19, 4));
            Settings.putSyncDownloadWhileReading(true);
            chooseMode(R.string.gallery_read_full);
            phase = "switch to full reader";
            await(() -> fullAt(19));
            for (int[] mapping : new int[][]{{29, 3, 29}, {15, 1, 10}, {0, 0, 9}}) {
                phase = "source-page mapping " + mapping[0];
                onMain(() -> ((GalleryView) field(reader, "mGalleryView")).setCurrentPage(mapping[0]));
                await(() -> fullAt(mapping[0]));
                chooseMode(R.string.range_read_downloaded);
                await(() -> rangeAt(mapping[1], mapping[2], 4));
                chooseMode(R.string.gallery_read_full);
                await(() -> fullAt(mapping[2]));
            }
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

            chooseMode(R.string.gallery_read_full);
            await(() -> fullAt(19));
            onMain(() -> RangeDownloadDialog.show(reader, info, manager));
            phase = "edit range while full reader remains active";
            editInput("10,20,25", "10,20,26");
            click(reader.getString(R.string.download));
            await(() -> completed(3) && fullAt(19));
            assertNotNull(SpiderDen.findImageFile(directory, 25));
            assertNull(SpiderDen.findImageFile(directory, 24));
            chooseMode(R.string.range_read_downloaded);
            await(() -> rangeAt(1, 19, 3));

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

            phase = "open-ended input and range retry";
            onMain(() -> RangeDownloadDialog.show(reader, info, manager));
            editInput(null, "-5,25-");
            click(reader.getString(R.string.download));
            await(() -> completed(17));
            assertEquals("1-5,25-36", GalleryDownloadRangeStore.peek(info.gid).range().toString());
            verifyUpdateReusesAndRetainsRange(directory);
            onMain(() -> DownloadRemovalDialog.show(reader, info, manager));
            click(reader.getString(R.string.download_redownload));
            await(() -> completed(17));
            assertNull(SpiderDen.findImageFile(directory, 9));
            assertEquals(originalCount, manager.getAllDownloadInfoList().size());

            onMain(reader::finish);
            reader = null;
            SystemClock.sleep(3500);
            verifyFailureAndRetry(directory);

            phase = "new full download after removing range record";
            onMain(() -> DownloadRemovalDialog.show(main, info, manager));
            click(main.getString(R.string.download_remove_record));
            await(() -> manager.getDownloadInfo(info.gid) == null);
            onMain(() -> in.getTargetContext().startService(new Intent(in.getTargetContext(), DownloadService.class)
                    .setAction(DownloadService.ACTION_START).putExtra(DownloadService.KEY_GALLERY_INFO, info)));
            await(() -> completed(36) && !GalleryDownloadRangeStore.isRange(info.gid));
            assertEquals(originalCount, manager.getAllDownloadInfoList().size());
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
            Settings.setGalleryVersionUpdatePromptPending(versionPrompt);
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
    private boolean findNewReader(GalleryActivity previous) {
        for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
            if (activity instanceof GalleryActivity && activity != previous) {
                reader = (GalleryActivity) activity;
                return true;
            }
        return false;
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
                if (node.getText() == null || !text.equalsIgnoreCase(node.getText().toString())) continue;
                if (!node.isVisibleToUser()) {
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
                    continue;
                }
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
        onMain(() -> (reader != null ? reader : main).getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));
        in.waitForIdleSync();
        SystemClock.sleep(600);
        Bitmap bitmap = in.getUiAutomation().takeScreenshot(); assertNotNull(bitmap);
        try (FileOutputStream output = new FileOutputStream(new File(evidence, name))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); }
        bitmap.recycle();
    }
    private void verifyUpdateReusesAndRetainsRange(UniFile sourceDirectory) throws Exception {
        phase = "update reuses range pages and retains the old range";
        DownloadInfo target = new DownloadInfo(info.gid + 1);
        target.token = info.token; target.title = "Range QA update " + target.gid;
        target.firstGid = info.gid; target.pages = target.total = target.finished = target.downloaded = 36;
        target.state = DownloadInfo.STATE_FINISH;
        UniFile targetDirectory = SpiderDen.getGalleryDownloadDir(target);
        assertNotNull(targetDirectory); assertTrue(targetDirectory.ensureDir());
        try {
            createPagesAndCache(targetDirectory, target);
            onMain(() -> manager.addDownload(target, null, DownloadInfo.STATE_FINISH));
            assertEquals(info.gid, manager.findClosestOlderGalleryVersion(info.gid, target.gid).gid);
            SpiderDen den = new SpiderDen(target); den.setMode(SpiderQueen.MODE_DOWNLOAD);
            assertTrue(den.remove(24)); // This target page must come from the range parent, without cache fallback.
            assertNull(SpiderDen.findImageFile(targetDirectory, 24));
            GalleryUpdateManager.register(target.gid, info.gid, java.util.List.of(info.gid));
            assertTrue(GalleryUpdateManager.tryReuse(target.gid, 24, String.format(Locale.US, "%010x", 25), den));
            assertNotNull(SpiderDen.findImageFile(targetDirectory, 24));
            onMain(() -> assertTrue(manager.retryGalleryUpdateCleanup(target.gid)));
            await(() -> Integer.valueOf(GalleryUpdateManager.UPDATE_STATE_UPDATED)
                    .equals(GalleryUpdateManager.getUpdateState(target.gid)));
            assertNotNull(manager.getDownloadInfo(info.gid));
            assertTrue(sourceDirectory.isDirectory());
            assertNotNull(sourceDirectory.findFile(GalleryDownloadRangeStore.FILENAME));
            assertNotNull(SpiderDen.findImageFile(sourceDirectory, 24));
            assertTrue(GalleryDownloadRangeStore.isRange(info.gid));
        } finally {
            onMain(() -> manager.deleteDownload(target.gid));
            GalleryUpdateManager.cancel(target.gid);
            GalleryUpdateRecordStore records = GalleryUpdateRecordStore.get(in.getTargetContext());
            for (String table : new String[]{"records", "pending"})
                records.getWritableDatabase().delete(table, "gid=?", new String[]{Long.toString(target.gid)});
            targetDirectory.delete();
            EhDB.removeDownloadDirname(target.gid);
        }
    }
    private void verifyFailureAndRetry(UniFile directory) throws Exception {
        phase = "selected-page failure and retry preserve the range";
        // Use an unvisited page so the fixture does not wait for a retained reader-cache image.
        int failedPage = 35;
        UniFile first = SpiderDen.findImageFile(directory, failedPage);
        assertNotNull(first);
        byte[] image;
        try (java.io.InputStream input = first.openInputStream()) { image = input.readAllBytes(); }
        SpiderInfo metadata = SpiderInfo.read(directory.findFile(SpiderQueen.SPIDER_INFO_FILENAME));
        assertNotNull(metadata);
        String firstToken = metadata.pTokenMap.get(failedPage);
        metadata.pTokenMap.remove(failedPage);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        metadata.write(bytes);
        bytes.write((failedPage + " failed\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (OutputStream output = directory.createFile(SpiderQueen.SPIDER_INFO_FILENAME).openOutputStream()) {
            output.write(bytes.toByteArray());
        }
        OutputStreamPipe cache = EhApplication.getSpiderInfoCache(in.getTargetContext())
                .getOutputStreamPipe(Long.toString(info.gid));
        try { cache.obtain(); cache.open().write(bytes.toByteArray()); }
        finally { cache.close(); cache.release(); }
        SpiderDen den = new SpiderDen(info); den.setMode(SpiderQueen.MODE_DOWNLOAD);
        assertTrue(den.remove(failedPage));
        startDownload(info);
        await(() -> {
            DownloadInfo failed = manager.getDownloadInfo(info.gid);
            return failed.state == DownloadInfo.STATE_FAILED && failed.total == 17 && failed.finished == 16 && failed.legacy == 1;
        });
        assertEquals("1-5,25-36", GalleryDownloadRangeStore.peek(info.gid).range().toString());
        await(() -> ((TextView) main.findViewById(R.id.download)).getText().toString()
                .equals(main.getString(R.string.download_state_failed)));
        try (OutputStream output = directory.createFile("00000036.png").openOutputStream()) { output.write(image); }
        metadata.pTokenMap.put(failedPage, firstToken);
        metadata.write(directory.createFile(SpiderQueen.SPIDER_INFO_FILENAME).openOutputStream());
        startDownload(info);
        await(() -> completed(17));
        await(() -> ((TextView) main.findViewById(R.id.download)).getText().toString()
                .equals(main.getString(R.string.range_downloaded)));
        assertNull(SpiderDen.findImageFile(directory, 9));
    }
    private void startDownload(DownloadInfo gallery) {
        onMain(() -> in.getTargetContext().startService(new Intent(in.getTargetContext(), DownloadService.class)
                .setAction(DownloadService.ACTION_START).putExtra(DownloadService.KEY_GALLERY_INFO, gallery)));
    }
    private void createPagesAndCache(UniFile directory, DownloadInfo fixture) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(); paint.setColor(Color.BLACK); paint.setTextSize(100);
        Canvas canvas = new Canvas(bitmap);
        SpiderInfo metadata = new SpiderInfo(); metadata.gid = fixture.gid; metadata.token = fixture.token;
        metadata.pages = 36; metadata.previewPages = 1; metadata.previewPerPage = 40; metadata.pTokenMap = new SparseArray<>();
        SpiderDen den = new SpiderDen(fixture); den.setMode(SpiderQueen.MODE_READ);
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
