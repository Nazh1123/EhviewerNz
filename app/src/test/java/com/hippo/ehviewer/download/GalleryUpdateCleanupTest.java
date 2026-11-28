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
import java.nio.file.Files;
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
        context.getSharedPreferences("download_page_ranges", Context.MODE_PRIVATE).edit().clear().commit();
        ReflectionHelpers.setStaticField(GalleryDownloadRangeStore.class, "instance", null);
        ((android.util.LruCache<?, ?>) ReflectionHelpers.getStaticField(SpiderQueen.class, "sStartPages")).evictAll();
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
        for (String field : List.of("PLAN_CACHE", "UPDATE_STATE_CACHE", "FAILURE_REQUESTS")) {
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
        ReflectionHelpers.setStaticField(GalleryDownloadRangeStore.class, "instance", null);
        ((android.util.LruCache<?, ?>) ReflectionHelpers.getStaticField(SpiderQueen.class, "sStartPages")).evictAll();
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
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i] != null) info.pTokenMap.put(i, tokens[i]);
        }
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
        image(dir, index, 0xff000000);
    }

    private void image(File dir, int index, int color) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        try (FileOutputStream out = new FileOutputStream(new File(dir,
                SpiderDen.generateImageFilename(index, ".png")))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally { bitmap.recycle(); }
    }

    private File addSource(long gid, String... tokens) throws Exception {
        File dir = new File(sourceDir.getParentFile(), gid + "-source");
        assertTrue(dir.mkdir());
        EhDB.putDownloadDirname(gid, dir.getName());
        manager.addDownload(List.of(download(gid, tokens.length)));
        disk(dir, metadata(gid, 0, tokens));
        for (int i = 0; i < tokens.length; i++) image(dir, i);
        return dir;
    }

    private void limitSource(long gid, String pages, int total) throws Exception {
        GalleryDownloadRangeStore.get(context).apply(manager.getDownloadInfo(gid), GalleryPageRange.parse(pages, total));
    }

    private GalleryUpdatePreviewCheck.Result checkPreview(GalleryUpdatePreviewCheck check) throws Exception {
        var result = new AtomicReference<GalleryUpdatePreviewCheck.Result>();
        check.start(result::set);
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (result.get() == null && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper();
            Thread.sleep(10);
        }
        assertNotNull(result.get());
        assertNull(result.get().error());
        return result.get();
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

    @Test public void updatingACompleteTargetRetainsRangeParentsAndTheirFiles() throws Exception {
        disk(sourceDir, metadata(100, 1, "1111111111", "2222222222"));
        disk(targetDir, metadata(200, 0, "1111111111", "3333333333", "2222222222"));
        GalleryDownloadRangeStore.get(context).apply(manager.getDownloadInfo(100), GalleryPageRange.parse("2", 2));
        // The new feature must not add a range-specific update eligibility restriction.
        assertEquals(100, manager.findClosestOlderGalleryVersion(100, 200).gid);
        GalleryUpdateManager.register(200, 100, List.of(100L));
        assertTrue(manager.retryGalleryUpdateCleanup(200));
        awaitState(GalleryUpdateManager.UPDATE_STATE_UPDATED);
        assertNotNull(manager.getDownloadInfo(100));
        assertTrue(sourceDir.isDirectory());
        assertTrue(new File(sourceDir, "00000002.png").isFile());
        assertTrue(manager.isCompleteUsableGallery(manager.getDownloadInfo(200)));
    }

    @Test public void aFinishedRangeTargetCannotTriggerParentCleanup() throws Exception {
        disk(sourceDir, metadata(100, 1, "1111111111", "2222222222"));
        disk(targetDir, metadata(200, 0, "1111111111", "3333333333", "2222222222"));
        GalleryDownloadRangeStore.get(context).apply(manager.getDownloadInfo(200), GalleryPageRange.parse("1", 3));
        GalleryUpdateManager.register(200, 100, List.of(100L));
        assertTrue(manager.retryGalleryUpdateCleanup(200));
        awaitState(GalleryUpdateManager.UPDATE_STATE_FAILED);
        assertNotNull(manager.getDownloadInfo(100));
        assertTrue(sourceDir.isDirectory());
    }

    @Test public void confirmedComparisonKeepsEveryGroupAfterOlderMetadataChanges() throws Exception {
        disk(sourceDir, metadata(100, 0, "6666666666", "5555555555"));
        File middle = addSource(120, "1111111111", "7777777777");
        File latest = addSource(140, "8888888888", "3333333333");
        limitSource(120, "1", 2);
        limitSource(140, "2", 2);
        disk(latest, metadata(140, 0, null, "3333333333"));
        cache(metadata(140, 0, "1111111111", null));
        ReflectionHelpers.setField(context, "mBrowsingOkHttpClient",
                new okhttp3.OkHttpClient.Builder().addInterceptor(chain -> {
                    throw new AssertionError("The disk and browsing cache should cover all required pages");
                }).build());
        SpiderInfo updated = metadata(200, 0, "1111111111", "4444444444", "5555555555");
        disk(targetDir, updated);
        var target = new com.hippo.ehviewer.client.data.GalleryDetail();
        target.gid = 200;
        target.token = updated.token;
        target.pages = updated.pages;
        target.firstGid = 100L;
        DownloadInfo source = manager.getDownloadInfo(140);

        var checked = checkPreview(new GalleryUpdatePreviewCheck(context, source, target));
        var groups = checked.record().sourceDeletions;
        assertEquals(List.of(140L, 120L, 100L), groups.stream().map(GalleryUpdateRecord.SourceDeletion::gid).toList());
        assertArrayEquals(new int[]{1}, groups.get(0).pages());
        assertArrayEquals(new int[0], groups.get(1).pages());
        assertArrayEquals(new int[]{0}, groups.get(2).pages());
        assertTrue(checked.record().hasKnownAdditions());
        assertTrue(checked.record().showsLocalCoverage());
        assertArrayEquals(new int[]{1}, checked.record().addedPages);
        assertTrue(GalleryUpdatePreviewCheck.matchesCurrentSources(context, source, target, checked));
        limitSource(120, "1-2", 2);
        assertFalse(GalleryUpdatePreviewCheck.matchesCurrentSources(context, source, target, checked));
        limitSource(120, "1", 2);
        checked = checkPreview(new GalleryUpdatePreviewCheck(context, source, target));
        assertEquals(checked.record().toJson(), checkPreview(new GalleryUpdatePreviewCheck(context, source, target))
                .record().toJson());
        assertTrue(GalleryUpdateManager.registerConfirmed(context, 200, 140, List.of(100L, 120L, 140L), "", checked));
        GalleryUpdateRecordStore store = GalleryUpdateRecordStore.get(context);
        assertNull(store.find(200));
        store.getWritableDatabase().delete("previews", null, null);
        ((Map<?, ?>) ReflectionHelpers.getStaticField(GalleryUpdateManager.class, "PLAN_CACHE")).clear();
        assertEquals(checked.record().toJson(), GalleryUpdateManager.getPlanPreview(
                context, GalleryUpdateManager.getPlan(200), target.token).toJson());
        disk(sourceDir, metadata(100, 0, "1111111111", "5555555555"));
        assertTrue(manager.retryGalleryUpdateCleanup(200));
        awaitState(GalleryUpdateManager.UPDATE_STATE_UPDATED);
        assertEquals(checked.record().toJson(), GalleryUpdateRecordStore.get(context).find(200).toJson());
        assertEquals(1, GalleryUpdateRecordStore.get(context).listSummaries().get(0).addedPages());
        assertTrue(latest.exists());
        assertTrue(middle.exists());
        assertFalse(sourceDir.exists());
    }

    @Test public void reuseTriesRangeSourcesAndStopsAtFirstReadableCompleteVersion() throws Exception {
        disk(sourceDir, metadata(100, 0, "9999999999", "9999999999"));
        File complete = addSource(110, "1111111111", "4444444444");
        File damaged = addSource(120, "2222222222", "3333333333");
        damageFirstPage(damaged);
        File middle = addSource(130, "1111111111", "2222222222", "7777777777");
        File latest = addSource(140, "1111111111", "4444444444");
        limitSource(130, "1-2", 3);
        limitSource(140, "1", 2);
        image(latest, 0, 0xff112233);
        image(latest, 1, 0xff445566);
        image(middle, 0, 0xff778899);
        GalleryUpdateManager.register(200, 140, List.of(100L, 110L, 120L, 130L, 140L));
        assertEquals(Set.of("1111111111", "2222222222", "3333333333", "4444444444"),
                GalleryUpdateManager.findLocallyCoveredTokens(List.of(100L, 110L, 120L, 130L, 140L), () -> false));
        var den = new SpiderDen(manager.getDownloadInfo(200));
        den.setMode(SpiderQueen.MODE_DOWNLOAD);
        assertTrue(GalleryUpdateManager.tryReuse(200, 0, "1111111111", den));
        assertSameImage(latest, 0, 0);
        assertTrue(GalleryUpdateManager.tryReuse(200, 1, "2222222222", den));
        assertSameImage(middle, 1, 1);
        assertTrue(GalleryUpdateManager.tryReuse(200, 2, "3333333333", den));
        assertSameImage(damaged, 1, 2);
        assertTrue(GalleryUpdateManager.tryReuse(200, 3, "4444444444", den));
        assertSameImage(complete, 1, 3);
        assertFalse(GalleryUpdateManager.tryReuse(200, 4, "9999999999", den));
        damageFirstPage(latest);
        assertTrue(GalleryUpdateManager.tryReuse(200, 4, "1111111111", den));
        assertSameImage(middle, 0, 4);
        // No intact complete source remains. The remaining older local files are still candidates.
        damageFirstPage(complete);
        damageFirstPage(sourceDir);
        GalleryUpdateManager.register(200, 140, List.of(100L, 110L, 120L, 130L, 140L));
        assertTrue(GalleryUpdateManager.tryReuse(200, 5, "9999999999", den));
        assertSameImage(sourceDir, 1, 5);
    }

    @Test public void confirmedLogCannotBeRecomputedWhenItsDraftIsMissing() throws Exception {
        disk(sourceDir, metadata(100, 0, "1111111111", "2222222222"));
        SpiderInfo updated = metadata(200, 0, "1111111111", "2222222222", "3333333333");
        disk(targetDir, updated);
        var target = new com.hippo.ehviewer.client.data.GalleryDetail();
        target.gid = 200;
        target.token = updated.token;
        target.pages = updated.pages;
        target.firstGid = 100L;
        var checked = checkPreview(new GalleryUpdatePreviewCheck(context, manager.getDownloadInfo(100), target));
        assertTrue(GalleryUpdateManager.registerConfirmed(context, 200, 100, List.of(100L), "", checked));
        GalleryUpdateRecordStore.get(context).discardPending(200);
        assertTrue(manager.retryGalleryUpdateCleanup(200));
        awaitState(GalleryUpdateManager.UPDATE_STATE_FAILED);
        assertTrue(sourceDir.isDirectory());
        assertTrue(GalleryUpdateRecordStore.get(context).find(200).isFailure());
    }

    private void damageFirstPage(File dir) throws Exception {
        Files.write(new File(dir, "00000001.png").toPath(), new byte[]{1, 2, 3});
    }

    private void assertSameImage(File source, int sourcePage, int targetPage) throws Exception {
        var oldFile = new File(source, SpiderDen.generateImageFilename(sourcePage, ".png"));
        var newFile = new File(targetDir, SpiderDen.generateImageFilename(targetPage, ".png"));
        assertArrayEquals(Files.readAllBytes(oldFile.toPath()), Files.readAllBytes(newFile.toPath()));
    }

    @Test public void earlierRangePreviewFetchesOnlyMetadataCoveringSelectedMissingPages() throws Exception {
        disk(sourceDir, metadata(100, 0, "1111111111", "2222222222"));
        addSource(140, new String[8]);
        limitSource(140, "5", 8);
        addSource(160, "1111111111", "2222222222");
        SpiderInfo updated = metadata(200, 0, "1111111111", "2222222222", "3333333333");
        disk(targetDir, updated);
        var target = new com.hippo.ehviewer.client.data.GalleryDetail();
        target.gid = 200;
        target.token = updated.token;
        target.pages = updated.pages;
        target.firstGid = 100L;
        var requests = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        var client = new okhttp3.OkHttpClient.Builder().addInterceptor(chain -> {
            var request = chain.request();
            assertTrue(request.url().encodedPath().contains("/140/"));
            String value = request.url().queryParameter("p");
            int previewPage = value != null ? Integer.parseInt(value) : 0;
            requests.add(previewPage);
            return sourcePageResponse(request, previewPage, 200);
        }).build();
        ReflectionHelpers.setField(context, "mBrowsingOkHttpClient", client);
        var checked = checkPreview(new GalleryUpdatePreviewCheck(context, manager.getDownloadInfo(160), target));
        assertEquals(List.of(0, 2), requests);
        assertTrue(checked.record().sourceDeletions.get(1).complete());
        assertArrayEquals(new int[]{4}, checked.record().sourceDeletions.get(1).pages());
    }

    @Test public void latestRangePreviewListsUncoveredPagesAndRefreshesCoverageFromDisk() throws Exception {
        disk(sourceDir, metadata(100, 0, "1111111111", "2222222222", null));
        limitSource(100, "2", 3);
        SpiderInfo updated = metadata(200, 0, "3333333333", "1111111111", "2222222222");
        disk(targetDir, updated);
        var target = new com.hippo.ehviewer.client.data.GalleryDetail();
        target.gid = 200;
        target.token = updated.token;
        target.pages = updated.pages;
        target.firstGid = 100L;
        var client = new okhttp3.OkHttpClient.Builder().addInterceptor(chain -> {
            throw new AssertionError("Missing old metadata outside the user's range must not be fetched");
        }).build();
        ReflectionHelpers.setField(context, "mBrowsingOkHttpClient", client);
        DownloadInfo latest = manager.getDownloadInfo(100);
        var preview = checkPreview(new GalleryUpdatePreviewCheck(context, latest, target));
        assertTrue(preview.record().showsLocalCoverage());
        assertArrayEquals(new int[]{0, 1}, preview.record().addedPages);
        assertArrayEquals(new int[0], preview.record().sourceDeletions.get(0).pages());

        assertTrue(new File(sourceDir, "00000002.png").delete());
        var refreshed = checkPreview(new GalleryUpdatePreviewCheck(context, latest, target)).record();
        assertArrayEquals(new int[]{0, 1, 2}, refreshed.addedPages);
        assertArrayEquals(new int[0], refreshed.sourceDeletions.get(0).pages());
    }

    private okhttp3.Response sourcePageResponse(okhttp3.Request request, int previewPage, int code) {
        StringBuilder html = new StringBuilder("<table><tr><td>Length:</td><td>8 pages</td></tr></table>"
                + "<table class=\"ptt\"><tr><td>1</td><td>2</td><td>3</td><td>4</td><td>&gt;</td></tr></table>");
        for (int page = previewPage * 2 + 1; page <= previewPage * 2 + 2; page++) {
            html.append("<a href=\"https://e-hentai.org/s/000000000").append(page).append("/140-").append(page)
                    .append("\"><div title=\"Page ").append(page)
                    .append(": test\" style=\"background:url(https://example.com/3.jpg) 0 0\"></div></a>");
        }
        return new okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(code).message("Preview").body(okhttp3.ResponseBody.create(null, html.toString())).build();
    }

    @Test public void diskMetadataMigratesProgressDeletesSourceAndPublishesAddedPagesWithoutCache() throws Exception {
        disk(sourceDir, metadata(100, 1, "1111111111", "2222222222"));
        disk(targetDir, metadata(200, 0, "1111111111", "3333333333", "2222222222"));
        assertNull(EhApplication.getSpiderInfoCache(context).getInputStreamPipe("100"));
        assertNull(EhApplication.getSpiderInfoCache(context).getInputStreamPipe("200"));
        assertEquals(0, SpiderQueen.findStartPage(context, 200));
        GalleryUpdateManager.register(200, 100, List.of(100L));
        assertTrue(manager.retryGalleryUpdateCleanup(200));
        awaitState(GalleryUpdateManager.UPDATE_STATE_UPDATED);
        assertNull(manager.getDownloadInfo(100));
        assertTrue(EhDB.getAllDownloadInfo().stream().noneMatch(info -> info.gid == 100));
        assertFalse(sourceDir.exists());
        assertTrue(manager.isCompleteUsableGallery(manager.getDownloadInfo(200)));
        assertEquals(2, GalleryUpdateManager.readDownloadedSpiderInfo(200).startPage);
        assertEquals(Integer.valueOf(2), SpiderQueen.peekStartPage(200));
        assertEquals(2, SpiderQueen.findStartPage(context, 200));
        GalleryUpdateRecord record = GalleryUpdateRecordStore.get(context).find(200);
        assertNotNull(record); assertTrue(record.complete);
        assertArrayEquals(new int[]{1}, record.addedPages);
        assertArrayEquals(new int[0], record.deletedPages);
        assertNull(GalleryUpdateManager.getPlan(200));
    }

    @Test public void persistentProgressSurvivesMissingOrStaleCacheAndUnrelatedGalleryReads() throws Exception {
        disk(targetDir, metadata(200, 1, "1111111111", "2222222222", "3333333333"));
        assertEquals(1, SpiderQueen.findStartPage(context, 200));
        // A separate gallery detail lookup must not change the downloaded gallery's source.
        assertEquals(0, SpiderQueen.findStartPage(context, 100));
        cache(metadata(200, 0, "1111111111", "2222222222", "3333333333"));
        assertEquals(1, SpiderQueen.findStartPage(context, 200));
        EhApplication.getSpiderInfoCache(context).remove("200");
        assertEquals(1, SpiderQueen.findStartPage(context, 200));
        assertTrue(new File(targetDir, SpiderQueen.SPIDER_INFO_FILENAME).delete());
        // Temporary storage loss cannot manufacture an explicit reset.
        assertEquals(1, SpiderQueen.findStartPage(context, 200));
    }

    @Test public void readerBackwardMovementAndExplicitResetWinBeforeDiskWriteCompletes() throws Exception {
        disk(targetDir, metadata(200, 2, "1111111111", "2222222222", "3333333333"));
        cache(metadata(200, 2, "1111111111", "2222222222", "3333333333"));
        assertEquals(2, SpiderQueen.findStartPage(context, 200));
        SpiderQueen.recordReadingProgress(200, 1);
        assertEquals(1, SpiderQueen.findStartPage(context, 200));
        SpiderQueen.resetReadingProgress(200);
        assertEquals(0, SpiderQueen.findStartPage(context, 200));
        assertEquals(2, GalleryUpdateManager.readDownloadedSpiderInfo(200).startPage);
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

    @Test public void completedUpdateReplacesReuseSourcePositionInLabelAndAfterRestart() throws Exception {
        assertUpdatePosition("saved");
    }

    @Test public void completedUpdateReplacesReuseSourcePositionInDefaultLabel() throws Exception {
        assertUpdatePosition(null);
    }

    private void assertUpdatePosition(String label) throws Exception {
        if (label != null) EhDB.addDownloadLabel(label);
        EhDB.addDownloadLabel("other");
        DownloadInfo source = manager.getDownloadInfo(100);
        DownloadInfo target = manager.getDownloadInfo(200);
        source.time = 300; source.label = label;
        target.time = 600; target.label = label;
        EhDB.putDownloadInfo(source); EhDB.putDownloadInfo(target);
        for (long[] row : new long[][]{{300, 500}, {250, 400}, {50, 100}, {80, 200}, {900, 350}}) {
            DownloadInfo info = download(row[0], 2);
            info.time = row[1];
            info.label = info.gid == 900 ? "other" : label;
            EhDB.putDownloadInfo(info);
        }
        manager = new DownloadManager(context);
        ReflectionHelpers.setField(context, "mDownloadManager", manager);
        disk(sourceDir, metadata(100, 0, "1111111111", "2222222222"));
        disk(targetDir, metadata(200, 0, "1111111111", "3333333333", "2222222222"));
        GalleryUpdateManager.register(200, 100, List.of(80L, 100L));
        assertTrue(manager.retryGalleryUpdateCleanup(200));
        awaitState(GalleryUpdateManager.UPDATE_STATE_UPDATED);
        assertEquals(300, manager.getDownloadInfo(200).time);
        assertEquals(List.of(300L, 250L, 200L, 50L), (label == null
                ? manager.getDefaultDownloadInfoList() : manager.getLabelDownloadInfoList(label))
                .stream().map(info -> info.gid).toList());
        assertEquals(List.of(300L, 250L, 900L, 200L, 50L), manager.getAllDownloadInfoList()
                .stream().map(info -> info.gid).toList());
        DownloadManager restarted = new DownloadManager(context);
        assertEquals(300, restarted.getDownloadInfo(200).time);
        assertEquals(List.of(300L, 250L, 200L, 50L), (label == null
                ? restarted.getDefaultDownloadInfoList() : restarted.getLabelDownloadInfoList(label))
                .stream().map(info -> info.gid).toList());
        assertEquals(List.of(300L, 250L, 900L, 200L, 50L), restarted.getAllDownloadInfoList()
                .stream().map(info -> info.gid).toList());
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
            service.onStartCommand(new android.content.Intent(DownloadService.ACTION_START_GALLERY_UPDATE)
                    .putExtra(DownloadService.KEY_GALLERY_INFO, target), 0, 1);
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

    @Test public void previewFetchesMissingMetadataAndReopensFromCacheWithoutStartingUpdate() throws Exception {
        disk(sourceDir, metadata(100, 1, "1111111111", "2222222222"));
        var requests = new java.util.concurrent.CopyOnWriteArrayList<okhttp3.HttpUrl>();
        var client = new okhttp3.OkHttpClient.Builder().addInterceptor(chain -> {
            var request = chain.request();
            requests.add(request.url());
            String html = "<table><tr><td>Length:</td><td>3 pages</td></tr></table>"
                    + "<a href=\"https://e-hentai.org/s/3333333333/200-3\">"
                    + "<div title=\"Page 3: test\" style=\"background:url(https://example.com/3.jpg) 0 0\"></div></a>";
            return new okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("OK").body(okhttp3.ResponseBody.create(null, html)).build();
        }).build();
        ReflectionHelpers.setField(context, "mBrowsingOkHttpClient", client);
        var detail = new com.hippo.ehviewer.client.data.GalleryDetail();
        detail.gid = 200;
        detail.token = "0123456789";
        detail.pages = detail.SpiderInfoPages = 3;
        detail.SpiderInfoPreviewPages = 2;
        var previews = new com.hippo.ehviewer.client.data.LargePreviewSet();
        previews.addItem(0, "", "https://e-hentai.org/s/2222222222/200-1");
        previews.addItem(1, "", "https://e-hentai.org/s/4444444444/200-2");
        detail.SpiderInfoPreviewSet = previews;

        for (int attempt = 0; attempt < 2; attempt++) {
            var check = new GalleryUpdatePreviewCheck(context, manager.getDownloadInfo(100), detail);
            var result = new AtomicReference<GalleryUpdatePreviewCheck.Result>();
            check.start(result::set);
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (result.get() == null && System.nanoTime() < deadline) {
                ShadowLooper.idleMainLooper();
                Thread.sleep(10);
            }
            assertNotNull(result.get());
            assertNull(result.get().error());
            assertArrayEquals(new int[]{1, 2}, result.get().record().addedPages);
            assertArrayEquals(new int[]{0}, result.get().record().deletedPages);
        }
        assertEquals(1, requests.size());
        assertEquals("1", requests.get(0).queryParameter("p"));
        assertTrue(GalleryUpdateRecordStore.get(context).listSummaries().isEmpty());
        assertNull(GalleryUpdateManager.getPlan(200));
        assertTrue(sourceDir.exists());
    }

    @Test public void confirmedPreviewWaitsForComparisonBeforeRunningCleanup() throws Exception {
        SpiderInfo old = metadata(100, 1, "1111111111", "2222222222");
        SpiderInfo target = metadata(200, 0, "1111111111", "3333333333", "2222222222");
        GalleryUpdateRecord preview = GalleryUpdateRecord.compare(200, 100, old, target);
        GalleryUpdateRecordStore.get(context).savePreview(preview, old);
        old.pTokenMap.remove(1);
        disk(sourceDir, old);
        disk(targetDir, target);
        GalleryUpdateManager.register(200, 100, List.of(100L));
        var controller = Robolectric.buildService(DownloadService.class).create();
        DownloadService service = controller.get();
        try {
            var detail = new com.hippo.ehviewer.client.data.GalleryDetail();
            detail.gid = 200;
            detail.token = target.token;
            detail.firstGid = 100L;
            detail.title = "new";
            var task = new GalleryUpdatePreviewCheck(context, manager.getDownloadInfo(100), detail);
            task.handOff();
            service.onStartCommand(new android.content.Intent(DownloadService.ACTION_START_GALLERY_UPDATE)
                    .putExtra(DownloadService.KEY_GALLERY_INFO, detail)
                    .putExtra(DownloadService.KEY_UPDATE_SOURCE, 100L)
                    .putExtra(DownloadService.KEY_AWAIT_PREVIEW, true), 0, 1);
            ShadowLooper.idleMainLooper();
            assertTrue(sourceDir.exists());
            assertNull(GalleryUpdateRecordStore.get(context).find(200));
            task.deliver(new GalleryUpdatePreviewCheck.Result(preview, null));
            awaitState(GalleryUpdateManager.UPDATE_STATE_UPDATED);
            assertFalse(sourceDir.exists());
            assertEquals(2, GalleryUpdateManager.readDownloadedSpiderInfo(200).startPage);
            assertArrayEquals(new int[]{1}, GalleryUpdateRecordStore.get(context).find(200).addedPages);
        } finally {
            controller.destroy();
        }
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
            service.onStartCommand(new android.content.Intent(DownloadService.ACTION_START_GALLERY_UPDATE)
                    .putExtra(DownloadService.KEY_GALLERY_INFO, detail), 0, 1);
            awaitState(GalleryUpdateManager.UPDATE_STATE_UPDATED);
            ShadowLooper.idleMainLooper();
            assertNotNull(ReflectionHelpers.getField(service, "mGalleryUpdatedBuilder"));
            assertFalse(sourceDir.exists());
            assertNotNull(GalleryUpdateRecordStore.get(context).find(200));
        } finally { controller.destroy(); }
    }
}
