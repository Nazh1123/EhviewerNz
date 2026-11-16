package com.hippo.ehviewer.download;

import android.app.Application;
import android.content.Context;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.gallery.RangeGalleryProvider;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.unifile.UniFile;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE,
        shadows = GalleryDownloadRangeStoreTest.Directory.class)
public class GalleryDownloadRangeStoreTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static UniFile directory;
    private Context context;
    private GalleryDownloadRangeStore store;
    private GalleryInfo gallery;
    private byte[] png;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("download_page_ranges", 0).edit().clear().commit();
        ReflectionHelpers.setStaticField(GalleryDownloadRangeStore.class, "instance", null);
        store = GalleryDownloadRangeStore.get(context);
        gallery = new GalleryInfo(); gallery.gid = 9001; gallery.token = "token"; gallery.pages = 30;
        directory = UniFile.fromFile(folder.newFolder());
        png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j8XcAAAAASUVORK5CYII=");
        for (int page = 0; page < 30; page++) image(page);
        Files.write(folder.getRoot().toPath().resolve(directory.getName()).resolve(".ehviewer"), new byte[]{1});
    }
    private void image(int page) throws Exception {
        try (OutputStream output = directory.createFile(SpiderDen.generateImageFilename(page, ".png")).openOutputStream()) {
            output.write(png);
        }
    }
    @After public void cleanup() {
        GalleryDownloadRangeStore.endEdit(gallery.gid);
        ReflectionHelpers.setStaticField(GalleryDownloadRangeStore.class, "instance", null);
    }
    @Test public void convertingFullToRangeKeepsSelectedFilesAndPreservesGalleryMetadata() throws Exception {
        store.apply(gallery, GalleryPageRange.parse("10,11,20,30", 30));
        assertNotNull(directory.findFile(".ehviewer"));
        assertNotNull(directory.findFile(GalleryDownloadRangeStore.FILENAME));
        assertNotNull(SpiderDen.findImageFile(directory, 9));
        assertNull(SpiderDen.findImageFile(directory, 8));
        assertEquals(4, GalleryDownloadRangeStore.downloadCount(gallery.gid, 30));
        assertEquals("10-11,20,30", GalleryDownloadRangeStore.peek(gallery.gid).range().toString());
        assertFalse(GalleryDownloadRangeStore.peek(gallery.gid).pending());
        assertFalse(GalleryDownloadRangeStore.canPersist(gallery.gid, 8));
    }
    @Test public void rangeEditsAndUpgradeReuseTheSameDirectoryAndDoNotRenamePages() throws Exception {
        store.apply(gallery, GalleryPageRange.parse("10,20,30", 30));
        store.apply(gallery, GalleryPageRange.parse("10,11,20", 30));
        assertNull(SpiderDen.findImageFile(directory, 29));
        assertNull(SpiderDen.findImageFile(directory, 10)); // New page remains missing until copied/downloaded.
        image(10);
        store.apply(gallery, null);
        assertFalse(GalleryDownloadRangeStore.isRange(gallery.gid));
        assertNull(directory.findFile(GalleryDownloadRangeStore.FILENAME));
        assertNotNull(SpiderDen.findImageFile(directory, 9));
        assertNotNull(SpiderDen.findImageFile(directory, 10));
        assertEquals(30, GalleryDownloadRangeStore.downloadCount(gallery.gid, 30));
    }
    @Test public void rangeSurvivesRestartAndCanBeRecoveredFromTheDirectorySidecar() throws Exception {
        store.apply(gallery, GalleryPageRange.parse("-5,25-", 30));
        ReflectionHelpers.setStaticField(GalleryDownloadRangeStore.class, "instance", null);
        store = GalleryDownloadRangeStore.get(context);
        assertEquals("1-5,25-30", GalleryDownloadRangeStore.peek(gallery.gid).range().toString());
        store.remove(gallery.gid);
        store.restore(gallery.gid, directory);
        assertEquals("1-5,25-30", GalleryDownloadRangeStore.peek(gallery.gid).range().toString());
    }
    @Test public void localReaderMapsRealSourcePagesAndExplicitAnchorOverridesSavedPosition() throws Exception {
        store.apply(gallery, GalleryPageRange.parse("10,11,20,30", 30));
        store.saveReadingPage(gallery.gid, 29);
        RangeGalleryProvider provider = new RangeGalleryProvider(context, gallery, 18);
        assertTrue(provider.hasSelectedPages());
        assertEquals(2, provider.getStartPage());
        assertEquals(19, provider.getSourcePage(provider.getStartPage()));
        assertEquals(30, provider.getSourcePageCount());
        assertEquals("00000020", provider.getTranslationFilename(2));
        SpiderDen.findImageFile(directory, 19).delete();
        provider = new RangeGalleryProvider(context, gallery, 18);
        assertTrue(provider.hasSelectedPages());
        assertEquals(2, provider.getStartPage());
        assertEquals(19, provider.getSourcePage(2));
    }

    @Test public void fixedRangeIncludesMissingPagesAndNeverAddsUnselectedFiles() throws Exception {
        store.apply(gallery, GalleryPageRange.parse("10,20,30", 30));
        SpiderDen.findImageFile(directory, 9).delete();
        SpiderDen.findImageFile(directory, 19).delete();
        SpiderDen.findImageFile(directory, 29).delete();
        RangeGalleryProvider provider = new RangeGalleryProvider(context, gallery, 19);
        assertTrue(provider.hasSelectedPages());
        UniFile[] before = ReflectionHelpers.callInstanceMethod(provider, "loadImageFiles");
        assertEquals(3, before.length);
        assertArrayEquals(new UniFile[]{null, null, null}, before);
        assertEquals(1, provider.getStartPage());
        assertEquals(19, provider.getSourcePage(1));
        image(19);
        image(5); // A full-reader/cache file outside the selected interval stays outside this reader.
        UniFile[] after = ReflectionHelpers.callInstanceMethod(provider, "loadImageFiles");
        assertEquals(3, after.length);
        assertNull(after[0]); assertNotNull(after[1]); assertNull(after[2]);
        assertEquals(1, provider.getStartPage());
    }

    @Test public void listProgressAlwaysUsesRangePositionWithoutMutatingFullMetadata() throws Exception {
        store.apply(gallery, GalleryPageRange.parse("10-20,25-30", 30));
        com.hippo.ehviewer.spider.SpiderInfo full = new com.hippo.ehviewer.spider.SpiderInfo();
        full.startPage = 28; full.pages = 30;
        store.recordReadingPage(gallery.gid, 24);
        assertEquals(new DownloadReadingProgress(12, 17), DownloadReadingProgress.resolve(gallery.gid, full));
        assertEquals(new DownloadReadingProgress(12, 17), DownloadReadingProgress.resolve(gallery.gid, null));
        assertEquals(28, full.startPage); assertEquals(30, full.pages);
        store.flushReadingPage(gallery.gid);
        ReflectionHelpers.setStaticField(GalleryDownloadRangeStore.class, "instance", null);
        store = GalleryDownloadRangeStore.get(context);
        assertEquals(new DownloadReadingProgress(12, 17), DownloadReadingProgress.resolve(gallery.gid, full));
        store.saveReadingPage(gallery.gid, 9);
        assertEquals(new DownloadReadingProgress(1, 17), DownloadReadingProgress.resolve(gallery.gid, full));
        store.apply(gallery, GalleryPageRange.parse("25-30", 30));
        assertEquals(new DownloadReadingProgress(1, 6), DownloadReadingProgress.resolve(gallery.gid, full));
        store.remove(gallery.gid);
        assertEquals(new DownloadReadingProgress(29, 30), DownloadReadingProgress.resolve(gallery.gid, full));
        assertNull(DownloadReadingProgress.resolve(gallery.gid, null));
    }

    @Test public void completedRangePagePublishesOnlyItsFixedSlot() throws Exception {
        store.apply(gallery, GalleryPageRange.parse("10,20,30", 30));
        SpiderDen.findImageFile(directory, 19).delete();
        RangeGalleryProvider provider = new RangeGalleryProvider(context, gallery, 19);
        java.util.concurrent.BlockingQueue<String> changes = new java.util.concurrent.LinkedBlockingQueue<>();
        provider.setListener((com.hippo.lib.glgallery.GalleryProvider.Listener) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{com.hippo.lib.glgallery.GalleryProvider.Listener.class},
                (proxy, method, args) -> {
                    changes.add(method.getName() + ":" + (args == null ? "all" : args[0]));
                    return null;
                }));
        provider.setGLRoot((com.hippo.lib.glview.view.GLRoot) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{com.hippo.lib.glview.view.GLRoot.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("addOnGLIdleListener"))
                        ((com.hippo.lib.glview.view.GLRoot.OnGLIdleListener) args[0]).onGLIdle(null, false);
                    return null;
                }));
        Thread decoder = new Thread(provider);
        try {
            decoder.start();
            assertEquals("onDataChanged:all", changes.poll(5, TimeUnit.SECONDS));
            assertEquals(3, provider.size());
            assertEquals(1, provider.getStartPage());
            image(19);
            GalleryDownloadRangeStore.changed(gallery.gid);
            assertEquals("onDataChanged:1", changes.poll(5, TimeUnit.SECONDS));
            assertEquals(3, provider.size());
            assertEquals(19, provider.getSourcePage(1));
            assertTrue(changes.isEmpty());
        } finally {
            decoder.interrupt(); decoder.join(5000); assertFalse(decoder.isAlive());
            provider.stop();
        }
    }

    @Test public void returningFromReaderAndResettingProgressKeepTheListInRangeCoordinates() throws Exception {
        store.apply(gallery, GalleryPageRange.parse("10,20,30", 30));
        store.recordReadingPage(gallery.gid, 19);
        com.hippo.ehviewer.spider.SpiderInfo full = new com.hippo.ehviewer.spider.SpiderInfo();
        full.gid = gallery.gid; full.token = gallery.token; full.pages = 30; full.startPage = 28;
        full.previewPages = 1; full.previewPerPage = 40; full.pTokenMap = new android.util.SparseArray<>();
        try (OutputStream output = directory.findFile(".ehviewer").openOutputStream()) { full.write(output); }
        com.hippo.ehviewer.dao.DownloadInfo row = new com.hippo.ehviewer.dao.DownloadInfo(gallery.gid);
        var host = (com.hippo.ehviewer.ui.scene.download.part.DownloadPaginationController.Host)
                java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class[]{com.hippo.ehviewer.ui.scene.download.part.DownloadPaginationController.Host.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "getList" -> java.util.List.of(row);
                            case "getEHContext" -> context;
                            case "isContinuousLabelBrowse" -> false;
                            default -> null;
                        });
        var controller = new com.hippo.ehviewer.ui.scene.download.part.DownloadPaginationController(host);
        controller.updateReadProcess(new androidx.activity.result.ActivityResult(
                com.hippo.ehviewer.ui.scene.download.DownloadsScene.LOCAL_GALLERY_INFO_CHANGE,
                new android.content.Intent().putExtra("info", gallery)));
        com.hippo.ehviewer.spider.SpiderInfo cached = controller.getSpiderInfoMap().get(gallery.gid);
        assertNotNull(cached);
        assertEquals(30, cached.pages); assertEquals(28, cached.startPage);
        assertEquals(new DownloadReadingProgress(2, 3), DownloadReadingProgress.resolve(gallery.gid, cached));
        controller.resetReadingProgressInUi();
        assertEquals(new DownloadReadingProgress(1, 3), DownloadReadingProgress.resolve(gallery.gid, cached));
    }

    @Test public void databaseBackupPreservesScopeWithoutTheOriginalImageDirectory() throws Exception {
        store.apply(gallery, GalleryPageRange.parse("10,20-25", 30));
        store.saveReadingPage(gallery.gid, 22);
        try (android.database.sqlite.SQLiteDatabase database = android.database.sqlite.SQLiteDatabase.create(null)) {
            store.exportBackup(database, java.util.Set.of(gallery.gid));
            store.remove(gallery.gid);
            store.importBackup(database, java.util.Set.of(gallery.gid));
            assertEquals("10,20-25", GalleryDownloadRangeStore.peek(gallery.gid).range().toString());
            assertEquals(22, GalleryDownloadRangeStore.peek(gallery.gid).readingPage());
        }
    }
    @Test public void editWaitsForAnExistingWriterAndLateWritersCannotResurrectRemovedPages() throws Exception {
        OutputStream writer = GalleryDownloadRangeStore.openImageOutput(gallery.gid, 0, directory, "00000001.png");
        writer.write(png);
        assertTrue(GalleryDownloadRangeStore.beginEdit(gallery.gid));
        CompletableFuture<Void> edit = CompletableFuture.runAsync(() -> {
            try { store.apply(gallery, GalleryPageRange.parse("10", 30)); }
            catch (Exception error) { throw new RuntimeException(error); }
        });
        assertFalse(edit.isDone());
        writer.close();
        edit.get(5, TimeUnit.SECONDS);
        GalleryDownloadRangeStore.endEdit(gallery.gid);
        assertNull(SpiderDen.findImageFile(directory, 0));
        assertThrows(java.io.IOException.class, () -> GalleryDownloadRangeStore.openImageOutput(
                gallery.gid, 0, directory, "00000001.png"));
        assertNull(SpiderDen.findImageFile(directory, 0));
    }
    @Implements(SpiderDen.class)
    public static class Directory {
        @Implementation protected static UniFile getGalleryDownloadDir(GalleryInfo info) { return directory; }
        @Implementation protected static UniFile getExistingGalleryDownloadDir(GalleryInfo info) { return directory; }
    }
}
