package com.hippo.ehviewer.ui.fragment;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.util.SparseArray;

import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.dao.DaoMaster;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.gallery.LocalFolderGallerySource;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.unifile.UniFile;
import com.hippo.unifile.UriHandler;

import org.greenrobot.greendao.database.StandardDatabase;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class GalleryDownloadCleanupTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private SQLiteDatabase database;
    private DownloadManager manager;
    private DownloadInfo info;
    private File directory;
    private UriHandler uriHandler;
    private final List<String> logs = new ArrayList<>();

    @Before public void setup() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(Settings.class, "sContext", context);
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre",
                context.getSharedPreferences("gallery-cleanup", Context.MODE_PRIVATE));
        database = SQLiteDatabase.create(null);
        DaoMaster.createAllTables(new StandardDatabase(database), false);
        ReflectionHelpers.setStaticField(EhDB.class, "sDaoSession", new DaoMaster(database).newSession());
        File root = temporary.newFolder("downloads");
        UniFile rootFile = UniFile.fromFile(root);
        // Android file URIs retain an extra slash before a Windows drive letter.
        uriHandler = (app, uri) -> rootFile.getUri().equals(uri) ? rootFile : null;
        UniFile.addUriHandler(uriHandler);
        Settings.putDownloadLocation(rootFile);
        directory = new File(root, "100-gallery");
        assertTrue(directory.mkdir());
        EhDB.putDownloadDirname(100, directory.getName());
        info = new DownloadInfo(100);
        info.title = "Gallery";
        info.token = "0123456789";
        info.state = DownloadInfo.STATE_FINISH;
        EhDB.putDownloadInfo(info);
        manager = new DownloadManager(context);
        info = manager.getDownloadInfo(100);
        metadata(2);
    }

    @After public void tearDown() {
        UniFile.removeUriHandler(uriHandler);
        ReflectionHelpers.setStaticField(EhDB.class, "sDaoSession", null);
        database.close();
    }

    private void metadata(int pages) throws Exception {
        SpiderInfo metadata = new SpiderInfo();
        metadata.gid = 100;
        metadata.token = info.token;
        metadata.pages = pages;
        metadata.previewPages = 1;
        metadata.previewPerPage = pages;
        metadata.pTokenMap = new SparseArray<>();
        metadata.write(new FileOutputStream(new File(directory, ".ehviewer")));
    }

    private File file(String path) throws Exception {
        File file = new File(directory, path);
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        Files.write(file.toPath(), new byte[]{1, 2, 3});
        return file;
    }

    private DownloadInfo clean() {
        return GalleryDownloadCleanup.cleanInvalidDirectory(UniFile.fromFile(directory), manager, logs);
    }

    @Test public void completeGalleryKeepsOriginalsResultsAndExtraFiles() throws Exception {
        List<File> files = List.of(file("00000001.jpg"), file("00000002.webp"),
                file("_translated/page.png"), file("_super_resolution/page.webp"),
                file("cover.jpg"), file("notes.txt"), file(".ehnz-results.json"));
        assertNull(clean());
        for (File file : files) assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(file.toPath()));
        assertEquals(DownloadInfo.STATE_FINISH, info.state);
        assertTrue(logs.isEmpty());
    }

    @Test public void incompleteGalleryOnlyClearsNumberedOriginals() throws Exception {
        File original = file("00000001.jpg");
        List<File> kept = List.of(file("_translated/page.png"), file("_super_resolution/page.webp"),
                file("cover.jpg"), file("notes.txt"), file(".ehnz-results.json"),
                file("00000002.png/nested.txt"));
        assertSame(info, clean());
        assertFalse(original.exists());
        for (File file : kept) assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(file.toPath()));
        assertNotNull(SpiderInfo.readHeader(UniFile.fromFile(new File(directory, ".ehviewer"))));
    }

    @Test public void duplicateExtensionsCannotStandInForAMissingPage() throws Exception {
        File jpeg = file("00000001.jpg");
        File png = file("00000001.png");
        assertSame(info, clean());
        assertFalse(jpeg.exists());
        assertFalse(png.exists());
    }

    @Test public void extraNumberedFilesDoNotInvalidateCompleteGallery() throws Exception {
        File first = file("00000001.jpg");
        file("00000002.webp");
        File extra = file("00000003.png");
        assertNull(clean());
        assertTrue(first.exists());
        assertTrue(extra.exists());
    }

    @Test public void zeroLengthOriginalDoesNotCountAsACompletePage() throws Exception {
        file("00000001.jpg");
        assertTrue(new File(directory, "00000002.png").createNewFile());
        assertSame(info, clean());
    }

    @Test public void legacyMetadataIsParsedBySpiderInfo() throws Exception {
        Files.write(new File(directory, ".ehviewer").toPath(),
                "00000000\n100\n0123456789\n1\n1\n0\n2\n".getBytes(StandardCharsets.UTF_8));
        file("00000001.jpg");
        file("00000002.png");
        assertNull(clean());
        assertTrue(logs.isEmpty());
    }

    @Test public void corruptOrMissingMetadataNeverDeletesImages() throws Exception {
        File original = file("00000001.jpg");
        File metadata = new File(directory, ".ehviewer");
        Files.write(metadata.toPath(), "broken metadata".getBytes(StandardCharsets.UTF_8));
        assertNull(clean());
        assertTrue(original.exists());
        assertTrue(metadata.delete());
        assertNull(clean());
        assertTrue(original.exists());
    }

    @Test public void unownedDirectoryWithCopiedMetadataIsLeftAlone() throws Exception {
        File other = temporary.newFolder("other");
        Files.copy(new File(directory, ".ehviewer").toPath(), new File(other, ".ehviewer").toPath());
        File original = new File(other, "00000001.jpg");
        Files.write(original.toPath(), new byte[]{1});
        assertNull(GalleryDownloadCleanup.cleanInvalidDirectory(UniFile.fromFile(other), manager, logs));
        assertTrue(original.exists());
    }

    @Test public void bothCleanersSkipWaitingDownloadingAndUpdatingGalleries() throws Exception {
        File original = file("00000001.jpg");
        for (int state : new int[]{DownloadInfo.STATE_WAIT, DownloadInfo.STATE_DOWNLOAD,
                DownloadInfo.STATE_UPDATE}) {
            info.state = state;
            assertNull(clean());
            assertTrue(original.exists());
            assertFalse(MissingGalleryCleaner.isLocalGalleryMissing(info));
            assertTrue(MissingGalleryCleaner.filterRemovableDownloads(manager, List.of(info)).isEmpty());
        }
    }

    @Test public void missingCleanerSkipsActiveDownloadsEvenWithoutADirectory() {
        EhDB.removeDownloadDirname(info.gid);
        for (int state : new int[]{DownloadInfo.STATE_WAIT, DownloadInfo.STATE_DOWNLOAD,
                DownloadInfo.STATE_UPDATE}) {
            info.state = state;
            assertFalse(MissingGalleryCleaner.isLocalGalleryMissing(info));
        }
    }

    @Test public void bothCleanersProtectAllImportedSources() throws Exception {
        File original = file("00000001.jpg");
        for (String source : List.of("file:///storage/emulated/0/comics/book.cbz",
                "content://provider/document/book.zip", LocalFolderGallerySource.create(
                        Uri.parse("content://provider/tree/comics"), "folder").encode())) {
            info.archiveUri = source;
            assertNull(clean());
            assertTrue(original.exists());
            assertFalse(MissingGalleryCleaner.isLocalGalleryMissing(info));
            assertTrue(MissingGalleryCleaner.filterRemovableDownloads(manager, List.of(info)).isEmpty());
        }
    }

    @Test public void missingCleanerStillFindsInactiveEmptyOrAbsentGalleries() {
        for (int state : new int[]{DownloadInfo.STATE_NONE, DownloadInfo.STATE_FAILED,
                DownloadInfo.STATE_FINISH}) {
            info.state = state;
            assertTrue(MissingGalleryCleaner.isLocalGalleryMissing(info));
            assertEquals(List.of(info), MissingGalleryCleaner.filterRemovableDownloads(manager, List.of(info)));
        }
        EhDB.removeDownloadDirname(info.gid);
        assertTrue(MissingGalleryCleaner.isLocalGalleryMissing(info));
    }

    @Test public void importedFileArchiveOutsideTheDownloadRootIsNotMissing() throws Exception {
        File archive = temporary.newFile("local-book.cbz");
        info.archiveUri = Uri.fromFile(archive).toString();
        EhDB.removeDownloadDirname(info.gid);
        assertFalse(MissingGalleryCleaner.isLocalGalleryMissing(info));
        assertTrue(MissingGalleryCleaner.filterRemovableDownloads(manager, List.of(info)).isEmpty());
        assertTrue(archive.exists());
    }

    @Test public void removalRechecksTasksQueuedAfterTheScan() {
        assertTrue(MissingGalleryCleaner.isLocalGalleryMissing(info));
        List<DownloadInfo> scanned = List.of(info);
        info.state = DownloadInfo.STATE_WAIT;
        assertTrue(MissingGalleryCleaner.filterRemovableDownloads(manager, scanned).isEmpty());
    }

    @Test public void removalSkipsRecordsReplacedAfterTheScan() {
        List<DownloadInfo> scanned = List.of(info);
        DownloadInfo replacement = new DownloadInfo(100);
        manager.replaceInfo(replacement, info);
        assertTrue(MissingGalleryCleaner.filterRemovableDownloads(manager, scanned).isEmpty());
    }

    @Test public void missingCleanerKeepsGalleriesWithLocalFilesOrResults() throws Exception {
        file("_translated/page.png");
        assertFalse(MissingGalleryCleaner.isLocalGalleryMissing(info));
    }
}
