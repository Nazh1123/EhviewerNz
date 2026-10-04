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
        for (String field : List.of("PLAN_CACHE", "SOURCE_CACHE", "UPDATE_STATE_CACHE")) {
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
        assertNull(GalleryUpdateRecordStore.get(context).find(200));
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
        assertNull(GalleryUpdateRecordStore.get(context).find(200));
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
        assertNull(GalleryUpdateRecordStore.get(context).find(200));
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
