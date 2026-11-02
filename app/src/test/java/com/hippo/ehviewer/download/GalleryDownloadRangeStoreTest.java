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
        assertTrue(provider.hasReadablePages());
        assertEquals(2, provider.getStartPage());
        assertEquals(19, provider.getSourcePage(provider.getStartPage()));
        assertEquals(30, provider.getSourcePageCount());
        assertEquals("00000020", provider.getTranslationFilename(2));
        SpiderDen.findImageFile(directory, 19).delete();
        provider = new RangeGalleryProvider(context, gallery, 18);
        assertTrue(provider.hasReadablePages());
        assertEquals(1, provider.getStartPage());
        assertEquals(10, provider.getSourcePage(1));
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
