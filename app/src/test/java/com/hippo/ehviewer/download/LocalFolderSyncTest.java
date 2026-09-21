package com.hippo.ehviewer.download;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.dao.DaoMaster;
import com.hippo.ehviewer.dao.DaoSession;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.gallery.ImportedGalleryProgress;
import com.hippo.ehviewer.gallery.LocalFolderGallerySource;

import org.junit.After;
import org.greenrobot.greendao.database.StandardDatabase;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.util.ReflectionHelpers;

import java.util.Arrays;
import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class LocalFolderSyncTest {
    private static final String TREE = "content://provider/tree/books";
    private static final String LABEL = "/books/...";
    private Context context;
    private SQLiteDatabase database;
    private DownloadManager manager;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre",
                context.getSharedPreferences("sync-test", Context.MODE_PRIVATE));
        database = SQLiteDatabase.create(null);
        DaoMaster.createAllTables(new StandardDatabase(database), false);
        ReflectionHelpers.setStaticField(EhDB.class, "sDaoSession",
                new DaoMaster(database).newSession());
        manager = new DownloadManager(context);
        manager.addLabel(LABEL);
    }

    @After
    public void tearDown() {
        ReflectionHelpers.setStaticField(EhDB.class, "sDaoSession", null);
        database.close();
    }

    @Test
    public void repeatedSyncAddsOnlyNewPathsAndKeepsReadProgressAndMovedLabel() {
        DownloadInfo first = gallery("book A", 3);
        manager.applyLocalFolderScan(Collections.singletonList(first), LABEL, false);
        ImportedGalleryProgress.save(context, first.gid, 1, 3);
        manager.addLabel("moved");
        manager.changeLabel(Collections.singletonList(first), "moved");

        DownloadManager.FolderSyncResult result = manager.applyLocalFolderScan(
                Arrays.asList(gallery("book A", 5), gallery("book B", 2)), LABEL, true);
        assertEquals(1, result.added);
        assertEquals(1, result.updated);
        assertEquals(2, manager.getAllDownloadInfoList().size());
        assertSame(first, manager.getDownloadInfo(first.gid));
        assertEquals("moved", first.label);
        assertEquals(5, first.pages);
        assertEquals(1, ImportedGalleryProgress.getStartPage(context, first.gid));
        assertEquals(5, ImportedGalleryProgress.toSpiderInfo(context, first).pages);
        assertEquals(5, ImportedGalleryProgress.get(context, first.gid).pages);

        result = manager.applyLocalFolderScan(
                Arrays.asList(gallery("book A", 5), gallery("book B", 2)), LABEL, true);
        assertEquals(0, result.added);
        assertEquals(0, result.updated);

        DaoSession session = ReflectionHelpers.getStaticField(EhDB.class, "sDaoSession");
        session.clear();
        manager = new DownloadManager(context);
        result = manager.applyLocalFolderScan(
                Arrays.asList(gallery("book A", 5), gallery("book B", 2)), LABEL, true);
        assertEquals(0, result.added);
        assertEquals(0, result.updated);
        assertEquals(1, ImportedGalleryProgress.getStartPage(context, first.gid));
    }

    @Test
    public void legacySourceSurvivesRenameEmptyLabelAndManagerRecreation() {
        DownloadInfo first = gallery("book A", 3);
        manager.applyLocalFolderScan(Collections.singletonList(first), LABEL, false);
        // Simulate upgrading with legacy records, then emptying the label before opening it.
        manager = new DownloadManager(context);
        manager.addLabel("moved");
        first = manager.getDownloadInfo(first.gid);
        manager.changeLabel(Collections.singletonList(first), "moved");
        manager.renameLabel(LABEL, "renamed");
        assertTrue(manager.getLabelDownloadInfoList("renamed").isEmpty());
        manager = new DownloadManager(context);
        assertEquals(Collections.singleton(TREE), manager.getLocalFolderImportTrees("renamed"));
        assertTrue(manager.getLocalFolderImportTrees("moved").isEmpty());
        assertTrue(manager.getLocalFolderImportTrees(null).isEmpty());
    }

    @Test
    public void mergeKeepsBothSourcesAndDeletedLabelDoesNotLeakSource() {
        manager.rememberLocalFolderImport(LABEL, TREE);
        manager.addLabel("second");
        manager.rememberLocalFolderImport("second", TREE + "2");
        assertTrue(manager.mergeLabel(LABEL, "second"));
        assertEquals(2, manager.getLocalFolderImportTrees("second").size());
        manager.deleteLabel("second");
        manager.addLabel("second");
        assertTrue(manager.getLocalFolderImportTrees("second").isEmpty());
    }

    @Test
    public void emptyScanKeepsExistingEntriesAndDifferentTreeIsNewContent() {
        DownloadInfo first = gallery("book A", 3);
        manager.applyLocalFolderScan(Collections.singletonList(first), LABEL, false);
        manager.applyLocalFolderScan(Collections.emptyList(), LABEL, true);
        assertSame(first, manager.getDownloadInfo(first.gid));
        DownloadInfo otherTree = gallery("book A", 3);
        LocalFolderGallerySource source = LocalFolderGallerySource.create(Uri.parse(TREE + "2"), "book A");
        otherTree.gid = source.stableGalleryId();
        otherTree.archiveUri = source.encode();
        assertEquals(1, manager.applyLocalFolderScan(
                Collections.singletonList(otherTree), LABEL, true).added);
    }

    private static DownloadInfo gallery(String path, int pages) {
        LocalFolderGallerySource source = LocalFolderGallerySource.create(Uri.parse(TREE), path);
        DownloadInfo info = new DownloadInfo();
        info.gid = source.stableGalleryId();
        info.archiveUri = source.encode();
        info.token = "";
        info.title = path;
        info.pages = info.total = info.finished = info.downloaded = pages;
        info.state = DownloadInfo.STATE_FINISH;
        return info;
    }
}
