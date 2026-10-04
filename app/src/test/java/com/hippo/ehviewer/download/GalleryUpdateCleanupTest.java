package com.hippo.ehviewer.download;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.util.SparseArray;

import com.hippo.ehviewer.EhApplication;
import com.hippo.beerbelly.SimpleDiskCache;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.dao.DaoMaster;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.streampipe.OutputStreamPipe;
import com.hippo.unifile.UniFile;

import org.greenrobot.greendao.database.StandardDatabase;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises real download cleanup, metadata/cache reads, progress migration and log publication. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = GalleryUpdateCleanupTest.TestApplication.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class GalleryUpdateCleanupTest {
    public static class TestApplication extends EhApplication {
        @Override public void onCreate() {
            ReflectionHelpers.setStaticField(EhApplication.class, "instance", this);
        }
    }

    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Context context;
    private SQLiteDatabase database;
    private DownloadManager manager;
    private File sourceDir, targetDir;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        // Android's rename replaces an existing cache file. Java File.renameTo on Windows
        // does not; emulate replacement only in this desktop fixture, keeping real cache I/O.
        SimpleDiskCache cache = new SimpleDiskCache(temporary.newFolder("cache"), 1024 * 1024) {
            @Override public OutputStreamPipe getOutputStreamPipe(String key) {
                if (System.getProperty("os.name").startsWith("Windows")) remove(key);
                return super.getOutputStreamPipe(key);
            }
        };
        ReflectionHelpers.setField(context, "mSpiderInfoCache", cache);
        ReflectionHelpers.setStaticField(Settings.class, "sContext", context);
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre",
                context.getSharedPreferences("cleanup-tests", Context.MODE_PRIVATE));
        for (String field : List.of("PLAN_CACHE", "SOURCE_CACHE", "UPDATE_STATE_CACHE", "FAILURE_REQUESTS")) {
            ((Map<?, ?>) ReflectionHelpers.getStaticField(GalleryUpdateManager.class, field)).clear();
        }
        ((Set<?>) ReflectionHelpers.getStaticField(GalleryUpdateManager.class, "CLEANUP_IN_PROGRESS")).clear();
        ReflectionHelpers.setStaticField(GalleryUpdateRecordStore.class, "instance", null);
        database = SQLiteDatabase.create(null);
        DaoMaster.createAllTables(new StandardDatabase(database), false);
        ReflectionHelpers.setStaticField(EhDB.class, "sDaoSession", new DaoMaster(database).newSession());
        File root = temporary.newFolder("downloads");
        Settings.putDownloadLocation(UniFile.fromFile(root));
        sourceDir = new File(root, "100-source");
        targetDir = new File(root, "200-target");
        assertTrue(sourceDir.mkdir()); assertTrue(targetDir.mkdir());
        EhDB.putDownloadDirname(100, sourceDir.getName());
        EhDB.putDownloadDirname(200, targetDir.getName());
        EhDB.putDownloadInfo(download(100, 2));
        EhDB.putDownloadInfo(download(200, 3));
        manager = new DownloadManager(context);
        ReflectionHelpers.setField(context, "mDownloadManager", manager);
        image(sourceDir, 0); image(sourceDir, 1);
        image(targetDir, 0); image(targetDir, 1); image(targetDir, 2);
        ShadowLooper.idleMainLooper();
    }

    @After public void cleanup() {
        GalleryUpdateRecordStore.get(context).close();
        ReflectionHelpers.setStaticField(GalleryUpdateRecordStore.class, "instance", null);
        ReflectionHelpers.setStaticField(EhDB.class, "sDaoSession", null);
        ReflectionHelpers.setStaticField(EhApplication.class, "instance", null);
        database.close();
    }

    private DownloadInfo download(long gid, int pages) {
        DownloadInfo info = new DownloadInfo();
        info.gid = gid; info.token = "0123456789"; info.title = "test";
        info.firstGid = 100L;
        info.pages = info.total = info.finished = info.downloaded = pages;
        info.state = DownloadInfo.STATE_FINISH;
        return info;
    }

    private SpiderInfo metadata(long gid, int startPage, String... tokens) {
        SpiderInfo info = new SpiderInfo();
        info.gid = gid; info.token = "0123456789"; info.startPage = startPage;
        info.pages = tokens.length; info.previewPages = 1; info.previewPerPage = tokens.length;
        info.pTokenMap = new SparseArray<>();
        for (int i = 0; i < tokens.length; i++) info.pTokenMap.put(i, tokens[i]);
        return info;
    }

    private void cache(SpiderInfo info) throws Exception {
        OutputStreamPipe pipe = EhApplication.getSpiderInfoCache(context).getOutputStreamPipe("" + info.gid);
        try { pipe.obtain(); info.write(pipe.open()); }
        finally { pipe.close(); pipe.release(); }
    }

    private void disk(File dir, SpiderInfo info) throws Exception {
        info.write(new FileOutputStream(new File(dir, SpiderQueen.SPIDER_INFO_FILENAME)));
    }

    private void image(File dir, int index) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(new File(dir,
                SpiderDen.generateImageFilename(index, ".png")))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally { bitmap.recycle(); }
    }

    private void awaitState(int state) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper();
            if (Integer.valueOf(state).equals(GalleryUpdateManager.getUpdateState(200))) return;
            Thread.sleep(10);
        }
        fail("Cleanup did not reach state " + state + ": " + GalleryUpdateManager.getUpdateState(200)
                + " logs: " + org.robolectric.shadows.ShadowLog.getLogs());
    }

    @Test public void diskMetadataMigratesProgressDeletesSourceAndPublishesAddedPagesWithoutCache() throws Exception {
        disk(sourceDir, metadata(100, 1, "1111111111", "2222222222"));
        disk(targetDir, metadata(200, 0, "1111111111", "3333333333", "2222222222"));
        assertNull(EhApplication.getSpiderInfoCache(context).getInputStreamPipe("100"));
        assertNull(EhApplication.getSpiderInfoCache(context).getInputStreamPipe("200"));
        GalleryUpdateManager.register(200, 100, List.of(100L));
        assertTrue(manager.retryGalleryUpdateCleanup(200));
        awaitState(GalleryUpdateManager.UPDATE_STATE_UPDATED);
        assertNull(manager.getDownloadInfo(100));
        assertTrue(EhDB.getAllDownloadInfo().stream().noneMatch(info -> info.gid == 100));
        assertFalse(sourceDir.exists());
        assertTrue(manager.isCompleteUsableGallery(manager.getDownloadInfo(200)));
        assertEquals(2, GalleryUpdateManager.readDownloadedSpiderInfo(200).startPage);
        GalleryUpdateRecord record = GalleryUpdateRecordStore.get(context).find(200);
        assertNotNull(record); assertTrue(record.complete);
        assertArrayEquals(new int[]{1}, record.addedPages);
        assertArrayEquals(new int[0], record.deletedPages);
        assertNull(GalleryUpdateManager.getPlan(200));
    }

    @Test public void corruptTargetKeepsSourceAndPlanThenRetryFinishesWithoutRedownload() throws Exception {
        disk(sourceDir, metadata(100, 0, "1111111111", "2222222222"));
        disk(targetDir, metadata(200, 0, "1111111111", "2222222222", "3333333333"));
        try (FileOutputStream out = new FileOutputStream(new File(targetDir,
                SpiderDen.generateImageFilename(2, ".png")))) { out.write(new byte[]{1, 2, 3}); }
        GalleryUpdateManager.register(200, 100, List.of(100L));
        assertTrue(manager.retryGalleryUpdateCleanup(200));
        awaitState(GalleryUpdateManager.UPDATE_STATE_FAILED);
        assertNotNull(manager.getDownloadInfo(100)); assertTrue(sourceDir.exists());
        assertNotNull(GalleryUpdateManager.getPlan(200));
        assertFailureLog(context.getString(com.hippo.ehviewer.R.string.gallery_update_error_image, 3));
        assertEquals(GalleryUpdateManager.UPDATE_STATE_FAILED, GalleryUpdateState.resolve(
                GalleryUpdateManager.getUpdateState(200), manager.getDownloadInfo(200), true));
        image(targetDir, 2);
        assertTrue(manager.retryGalleryUpdateCleanup(200));
        awaitState(GalleryUpdateManager.UPDATE_STATE_UPDATED);
        assertFalse(sourceDir.exists());
        assertArrayEquals(new int[]{2}, GalleryUpdateRecordStore.get(context).find(200).addedPages);
    }

    @Test public void cachedTargetCannotReplaceMissingDiskMetadata() throws Exception {
        disk(sourceDir, metadata(100, 0, "1111111111", "2222222222"));
        cache(metadata(200, 0, "1111111111", "2222222222", "3333333333"));
        GalleryUpdateManager.register(200, 100, List.of(100L));
        assertTrue(manager.retryGalleryUpdateCleanup(200));
        awaitState(GalleryUpdateManager.UPDATE_STATE_FAILED);
        assertTrue(sourceDir.exists()); assertNotNull(manager.getDownloadInfo(100));
        assertFailureLog(".ehviewer");
        assertFalse(new File(targetDir, ".ehviewer").exists());
    }

    @Test public void cachedSourceCannotReplaceMissingDiskMetadata() throws Exception {
        cache(metadata(100, 1, "1111111111", "2222222222"));
        disk(targetDir, metadata(200, 0, "1111111111", "3333333333", "2222222222"));
        assertNull(GalleryUpdateManager.readDownloadedSpiderInfo(100));
        GalleryUpdateManager.register(200, 100, List.of(100L));
        assertTrue(manager.retryGalleryUpdateCleanup(200));
        awaitState(GalleryUpdateManager.UPDATE_STATE_FAILED);
        assertTrue(sourceDir.exists()); assertNotNull(manager.getDownloadInfo(100));
        assertNotNull(GalleryUpdateManager.getPlan(200));
        assertFailureLog(".ehviewer");
        assertFalse(new File(sourceDir, ".ehviewer").exists());
    }

    @Test public void finishFlushesMetadataBeforeDownloadListenersRun() throws Exception {
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        ReflectionHelpers.setField(context, "mOkHttpClient", client);
        ReflectionHelpers.setField(context, "mImageOkHttpClient", client);
        SpiderQueen queen = ReflectionHelpers.callConstructor(SpiderQueen.class,
                ReflectionHelpers.ClassParameter.from(EhApplication.class, (EhApplication) context),
                ReflectionHelpers.ClassParameter.from(com.hippo.ehviewer.client.data.GalleryInfo.class,
                        manager.getDownloadInfo(200)));
        SpiderDen den = ReflectionHelpers.getField(queen, "mSpiderDen");
        den.setMode(SpiderQueen.MODE_DOWNLOAD);
        AtomicReference<SpiderInfo> info = ReflectionHelpers.getField(queen, "mSpiderInfo");
        info.set(metadata(200, 0, "1111111111", "2222222222", "3333333333"));
        ReflectionHelpers.callInstanceMethod(queen, "notifyFinish");
        assertTrue(manager.isCompleteUsableGallery(manager.getDownloadInfo(200)));
    }

    private GalleryUpdateRecord assertFailureLog(String reason) {
        GalleryUpdateRecord record = GalleryUpdateRecordStore.get(context).find(200);
        assertNotNull(record);
        assertTrue(record.isFailure());
        assertTrue(record.completedAt > 0);
        assertEquals(100, record.sourceGid);
        assertEquals("test", record.sourceTitle);
        assertTrue(record.errorReason, record.errorReason.contains(reason));
        assertArrayEquals(new long[]{100}, record.retainedParentGids);
        return record;
    }

    @Test public void failureIsSavedBeforeStateListenerAndRetrySuccessReplacesIt() throws Exception {
        disk(targetDir, metadata(200, 0, "1111111111", "3333333333", "2222222222"));
        GalleryUpdateManager.register(200, 100, List.of(100L), "Saved old title");
        AtomicReference<GalleryUpdateRecord> seen = new AtomicReference<>();
        GalleryUpdateManager.UpdateStateListener listener = (gid, state) -> {
            if (gid == 200 && state == GalleryUpdateManager.UPDATE_STATE_FAILED)
                seen.set(GalleryUpdateRecordStore.get(context).find(gid));
        };
        GalleryUpdateManager.addUpdateStateListener(listener);
        try {
            assertTrue(manager.retryGalleryUpdateCleanup(200));
            awaitState(GalleryUpdateManager.UPDATE_STATE_FAILED);
            assertNotNull(seen.get());
            assertEquals("Saved old title", seen.get().sourceTitle);
            assertTrue(seen.get().errorReason.contains(".ehviewer"));
            assertNotNull(GalleryUpdateManager.getPlan(200));
            disk(sourceDir, metadata(100, 0, "1111111111", "2222222222"));
            assertTrue(manager.retryGalleryUpdateCleanup(200));
            awaitState(GalleryUpdateManager.UPDATE_STATE_UPDATED);
            assertFalse(GalleryUpdateRecordStore.get(context).find(200).isFailure());
            assertArrayEquals(new int[]{1}, GalleryUpdateRecordStore.get(context).find(200).addedPages);
        } finally { GalleryUpdateManager.removeUpdateStateListener(listener); }
    }

    @Test public void replacedPlanCannotPublishStaleFailure() {
        GalleryUpdateManager.register(200, 100, List.of(100L));
        GalleryUpdateManager.UpdatePlan old = GalleryUpdateManager.getPlan(200);
        GalleryUpdateManager.register(200, 100, List.of(100L));
        GalleryUpdateManager.reportFailure(context, old, "Stale error");
        assertNull(GalleryUpdateRecordStore.get(context).find(200));
        assertEquals(Integer.valueOf(GalleryUpdateManager.UPDATE_STATE_UPDATING),
                GalleryUpdateManager.getUpdateState(200));
    }

    @Test public void downloadFailureKeepsUnderlyingPageErrorAndOldGallery() throws Exception {
        GalleryUpdateManager.register(200, 100, List.of(100L));
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        ReflectionHelpers.setField(context, "mOkHttpClient", client);
        ReflectionHelpers.setField(context, "mImageOkHttpClient", client);
        DownloadInfo target = manager.getDownloadInfo(200);
        target.state = DownloadInfo.STATE_DOWNLOAD;
        SpiderQueen queen = ReflectionHelpers.callConstructor(SpiderQueen.class,
                ReflectionHelpers.ClassParameter.from(EhApplication.class, (EhApplication) context),
                ReflectionHelpers.ClassParameter.from(com.hippo.ehviewer.client.data.GalleryInfo.class, target));
        ReflectionHelpers.setField(queen, "mDownloadReference", 1);
        ReflectionHelpers.setField(manager, "mCurrentTask", target);
        ReflectionHelpers.setField(manager, "mCurrentSpider", queen);
        manager.onPageFailure(2, "HTTP 403", 2, 2, 3);
        manager.onFinish(2, 2, 3);
        awaitState(GalleryUpdateManager.UPDATE_STATE_FAILED);
        GalleryUpdateRecord record = assertFailureLog("HTTP 403");
        assertTrue(record.errorReason.contains("p3"));
        assertEquals(DownloadInfo.STATE_FAILED, target.state);
        assertTrue(sourceDir.exists());
    }

    @Test public void preparationFailureIsPublishedEvenWithoutADownloadOrPlan() throws Exception {
        var controller = Robolectric.buildService(DownloadService.class).create();
        DownloadService service = controller.get();
        try {
            com.hippo.ehviewer.client.data.GalleryDetail target = new com.hippo.ehviewer.client.data.GalleryDetail();
            target.gid = 300; target.title = "new"; target.firstGid = 0L;
            ReflectionHelpers.callInstanceMethod(service, "startGalleryUpdate",
                    ReflectionHelpers.ClassParameter.from(com.hippo.ehviewer.client.data.GalleryDetail.class, target));
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (!Integer.valueOf(GalleryUpdateManager.UPDATE_STATE_FAILED).equals(
                    GalleryUpdateManager.getUpdateState(300)) && System.nanoTime() < deadline) {
                ShadowLooper.idleMainLooper(); Thread.sleep(10);
            }
            GalleryUpdateRecord record = GalleryUpdateRecordStore.get(context).find(300);
            assertNotNull(record); assertTrue(record.isFailure());
            assertEquals(0, record.sourceGid);
            assertEquals(context.getString(com.hippo.ehviewer.R.string.gallery_update_no_downloaded_parent),
                    record.errorReason);
            assertNull(GalleryUpdateManager.getPlan(300));
            assertNull(manager.getDownloadInfo(300));
        } finally { controller.destroy(); }
    }

    @Test public void partialCleanupRetainsSourceTitleAndOnlyListsSurvivingDirectories() throws Exception {
        File earlier = new File(sourceDir.getParentFile(), "50-earlier");
        assertTrue(earlier.mkdir()); EhDB.putDownloadDirname(50, earlier.getName());
        GalleryUpdateManager.register(200, 100, List.of(100L, 50L), "Saved source title");
        GalleryUpdateManager.UpdatePlan plan = GalleryUpdateManager.getPlan(200);
        ((Map<?, ?>) ReflectionHelpers.getStaticField(GalleryUpdateManager.class, "PLAN_CACHE")).clear();
        plan = GalleryUpdateManager.getPlan(200);
        assertEquals("Saved source title", plan.sourceTitle);
        assertTrue(UniFile.fromFile(sourceDir).delete()); EhDB.removeDownloadDirname(100);
        GalleryUpdateManager.reportFailure(context, plan, "Unable to delete GID 50");
        awaitState(GalleryUpdateManager.UPDATE_STATE_FAILED);
        GalleryUpdateRecord record = GalleryUpdateRecordStore.get(context).find(200);
        assertEquals("Saved source title", record.sourceTitle);
        assertArrayEquals(new long[]{50}, record.retainedParentGids);
    }

    @Test public void publicationFailureAfterCleanupKeepsLegacySourceTitleWithoutClaimingRetention() throws Exception {
        GalleryUpdateRecordStore failingStore = GalleryUpdateRecordStore.get(context);
        failingStore.getWritableDatabase().execSQL("CREATE TRIGGER fail_success_publication "
                + "BEFORE INSERT ON records WHEN NEW.payload LIKE '%\"error_reason\":\"\"%' "
                + "BEGIN SELECT RAISE(ABORT, 'Injected publication failure'); END");
        disk(sourceDir, metadata(100, 0, "1111111111", "2222222222"));
        disk(targetDir, metadata(200, 0, "1111111111", "3333333333", "2222222222"));
        GalleryUpdateManager.register(200, 100, List.of(100L));
        assertTrue(manager.retryGalleryUpdateCleanup(200));
        awaitState(GalleryUpdateManager.UPDATE_STATE_FAILED);
        assertFalse(sourceDir.exists());
        assertNull(manager.getDownloadInfo(100));
        GalleryUpdateRecord record = failingStore.find(200);
        assertTrue(record.isFailure());
        assertEquals("test", record.sourceTitle);
        assertTrue(record.errorReason.startsWith(context.getString(
                com.hippo.ehviewer.R.string.gallery_update_error_record_publish)));
        assertEquals(0, record.retainedParentGids.length);
        ((Map<?, ?>) ReflectionHelpers.getStaticField(GalleryUpdateManager.class, "PLAN_CACHE")).clear();
        assertEquals("test", GalleryUpdateManager.getPlan(200).sourceTitle);
    }

    @Test public void failureLogEntryIsVisibleWithPendingPlanAndRetryButtonStaysEnabled() throws Exception {
        disk(targetDir, metadata(200, 0, "1111111111", "3333333333", "2222222222"));
        GalleryUpdateManager.register(200, 100, List.of(100L));
        assertTrue(manager.retryGalleryUpdateCleanup(200));
        awaitState(GalleryUpdateManager.UPDATE_STATE_FAILED);
        android.app.Activity activity = Robolectric.buildActivity(android.app.Activity.class).setup().get();
        activity.setTheme(com.hippo.ehviewer.R.style.AppTheme);
        var scene = new com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene() {
            @Override public Context getEHContext() { return activity; }
        };
        com.hippo.ehviewer.client.data.GalleryDetail detail = new com.hippo.ehviewer.client.data.GalleryDetail();
        detail.gid = 200; detail.parent = "https://e-hentai.org/g/100/0123456789/";
        android.widget.TextView retry = new android.widget.TextView(activity);
        android.widget.TextView log = new android.widget.TextView(activity);
        ReflectionHelpers.setField(scene, "mUpdateActionGroup", new android.view.View(activity));
        ReflectionHelpers.setField(scene, "mUpdateGallery", retry);
        ReflectionHelpers.setField(scene, "mGalleryHistory", log);
        ReflectionHelpers.setField(scene, "mGalleryDetail", detail);
        ReflectionHelpers.setField(scene, "mUpdateRecordLookupGid", 200L);
        ReflectionHelpers.setField(scene, "mGalleryUpdateRecord", GalleryUpdateRecordStore.get(context).find(200));
        ReflectionHelpers.callInstanceMethod(scene, "updateGalleryVersionActionsVisibility");
        assertEquals(activity.getString(com.hippo.ehviewer.R.string.gallery_update_error_log), log.getText());
        assertEquals(android.view.View.VISIBLE, log.getVisibility());
        assertTrue(retry.isEnabled());
        assertEquals(activity.getString(com.hippo.ehviewer.R.string.download_state_failed), retry.getText());
        ReflectionHelpers.callInstanceMethod(scene, "showGalleryUpdateLog");
        androidx.appcompat.app.AlertDialog dialog = ReflectionHelpers.getField(scene, "mUpdateLogDialog");
        assertNotNull(dialog); assertTrue(dialog.isShowing());
        dialog.dismiss(); activity.finish();
    }

    @Test public void serviceDefersSuccessNotificationAndRetriesFinishedDownloadCleanup() throws Exception {
        disk(sourceDir, metadata(100, 0, "1111111111", "2222222222"));
        disk(targetDir, metadata(200, 0, "1111111111", "2222222222", "3333333333"));
        GalleryUpdateManager.register(200, 100, List.of(100L));
        var controller = Robolectric.buildService(DownloadService.class).create();
        DownloadService service = controller.get();
        try {
            service.onFinish(manager.getDownloadInfo(200));
            assertNull(ReflectionHelpers.getField(service, "mGalleryUpdatedBuilder"));
            assertNotNull(ReflectionHelpers.getField(service, "mUpdatingBuilder"));
            com.hippo.ehviewer.client.data.GalleryDetail detail =
                    new com.hippo.ehviewer.client.data.GalleryDetail();
            detail.gid = 200; detail.token = "0123456789"; detail.title = "test";
            ReflectionHelpers.callInstanceMethod(service, "startGalleryUpdate",
                    ReflectionHelpers.ClassParameter.from(
                            com.hippo.ehviewer.client.data.GalleryDetail.class, detail));
            awaitState(GalleryUpdateManager.UPDATE_STATE_UPDATED);
            ShadowLooper.idleMainLooper();
            assertNotNull(ReflectionHelpers.getField(service, "mGalleryUpdatedBuilder"));
            assertFalse(sourceDir.exists());
            assertNotNull(GalleryUpdateRecordStore.get(context).find(200));
        } finally { controller.destroy(); }
    }
}
