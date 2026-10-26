package com.hippo.ehviewer.gallery;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Looper;
import android.provider.DocumentsContract;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.dao.DaoMaster;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.DownloadLabel;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.ui.scene.download.DownloadsScene;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.greenrobot.greendao.database.StandardDatabase;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowEnvironment;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 30}, application = Application.class)
public class LocalFolderArchiveScanTest {
    private Context context;
    private Uri tree;
    private Documents documents;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        register("archive.scan." + UUID.randomUUID(), "root");
    }

    private void register(String authority, String rootId) {
        tree = DocumentsContract.buildTreeDocumentUri(authority, rootId);
        documents = new Documents();
        documents.rootId = rootId;
        ProviderInfo info = new ProviderInfo();
        info.authority = authority;
        info.exported = true;
        info.grantUriPermissions = true;
        documents.attachInfo(context, info);
        ShadowContentResolver.registerProviderInternal(authority, documents);
        ResolveInfo resolve = new ResolveInfo();
        resolve.providerInfo = info;
        Shadows.shadowOf(context.getPackageManager()).addResolveInfoForIntent(
                new Intent(DocumentsContract.PROVIDER_INTERFACE), resolve);
    }

    @Test public void safArchivesKeepOpaqueDocumentUrisAndIgnoreAllResultDirectories() throws Exception {
        documents.files.addAll(Arrays.asList("10.zip", "2.CBZ", "book.rar", "book.cbr", "book.7z"));
        documents.directories.addAll(Arrays.asList("_translation", "_translated",
                "_SUPER_RESOLUTION", "123.zip_results", "_results"));
        LocalFolderGalleryScanner.ScanResult scan = scan();
        assertTrue(scan.isArchiveOnly());
        assertTrue(scan.images.isEmpty());
        assertEquals(1, scan.directoryCount);
        assertEquals("2.CBZ", scan.archives.get(0).filename);
        assertEquals("10.zip", scan.archives.get(1).filename);
        assertEquals("opaque:2.CBZ", DocumentsContract.getDocumentId(scan.archives.get(0).uri));
        assertEquals("content", scan.archives.get(0).uri.getScheme());
    }

    @Test public void safMixedFilesAndOrdinarySubdirectoriesDoNotTriggerArchiveMode() throws Exception {
        documents.files.addAll(Arrays.asList("1.zip", "001.jpg"));
        assertFalse(scan().isArchiveOnly());
        assertEquals(1, scan().images.size());
        documents.files.remove("001.jpg");
        documents.files.add("readme.txt");
        assertFalse(scan().isArchiveOnly());
        documents.files.remove("readme.txt");
        documents.directories.add("chapter_results_backup");
        assertFalse(scan().isArchiveOnly());
    }

    @Test public void safImageScanSkipsResultTreesButKeepsOrdinaryImageFolders() throws Exception {
        documents.files.add("001.jpg");
        documents.directories.addAll(Arrays.asList("_translation", "output_RESULTS", "chapter"));
        documents.nestedImage = true;
        LocalFolderGalleryScanner.ScanResult scan = scan();
        assertEquals(2, scan.images.size());
        assertEquals("chapter/002.png", scan.images.get(1).relativePath);
        assertEquals(2, scan.directoryCount);
    }

    @Test public void directScanUsesSameArchiveAndResultDirectoryRules() throws Exception {
        File root = Files.createTempDirectory(context.getCacheDir().toPath(), "archive-folder").toFile();
        Files.createFile(new File(root, "10.zip").toPath());
        Files.createFile(new File(root, "2.cbz").toPath());
        for (String name : Arrays.asList("_translation", "_translated", "_super_resolution", "a_results")) {
            File result = new File(root, name);
            assertTrue(result.mkdir());
            Files.createFile(new File(result, "ignored.jpg").toPath());
        }
        LocalFolderGalleryScanner.ScanResult scan = scanLocal(root);
        assertTrue(scan.isArchiveOnly());
        assertEquals(2, scan.archives.size());
        assertEquals("2.cbz", scan.archives.get(0).filename);
        assertEquals(1, scan.directoryCount);
        Files.createFile(new File(root, "readme.txt").toPath());
        assertFalse(scanLocal(root).isArchiveOnly());
        Files.delete(new File(root, "readme.txt").toPath());
        Files.createFile(new File(root, "001.jpg").toPath());
        assertFalse(scanLocal(root).isArchiveOnly());
        assertEquals(1, scanLocal(root).images.size());
    }

    @Test public void filesystemFastPathStillReturnsGrantedSafUrisForArchives() throws Exception {
        File volume = Files.createTempDirectory(context.getCacheDir().toPath(), "volume").toFile();
        File root = new File(volume, "Books");
        assertTrue(root.mkdir());
        Files.createFile(new File(root, "1.zip").toPath());
        ShadowEnvironment.setExternalStorageDirectory(volume.toPath());
        register("com.android.externalstorage.documents", "primary:Books");
        documents.files.add("1.zip");
        LocalFolderGalleryScanner.ScanResult scan = scan();
        assertTrue(scan.isArchiveOnly());
        assertEquals("content", scan.archives.get(0).uri.getScheme());
        assertEquals("opaque:1.zip", DocumentsContract.getDocumentId(scan.archives.get(0).uri));
    }

    @Test public void emptyOrOnlyResultDirectoriesAreNotArchiveCollections() throws Exception {
        assertFalse(scan().isArchiveOnly());
        documents.directories.add("anything_results");
        assertFalse(scan().isArchiveOnly());
    }

    @Test public void folderImportCreatesArchiveGalleriesInNamedLabelAndRemembersSyncSource() {
        SQLiteDatabase database = SQLiteDatabase.create(null);
        Object previousSession = ReflectionHelpers.getStaticField(EhDB.class, "sDaoSession");
        Object previousSettings = ReflectionHelpers.getStaticField(Settings.class, "sSettingsPre");
        try {
            DaoMaster.createAllTables(new StandardDatabase(database), false);
            ReflectionHelpers.setStaticField(EhDB.class, "sDaoSession", new DaoMaster(database).newSession());
            ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre",
                    context.getSharedPreferences("archive-folder-import", Context.MODE_PRIVATE));
            DownloadManager manager = new DownloadManager(context);
            DownloadsScene scene = new DownloadsScene();
            documents.files.addAll(Arrays.asList("123.zip", "456.cbz"));
            processFolder(scene, manager, null);
            List<DownloadInfo> imported = manager.getLabelDownloadInfoList("Books");
            assertNotNull(imported);
            assertEquals(2, imported.size());
            assertEquals("123.zip", imported.get(0).title);
            assertEquals("456.cbz", imported.get(1).title);
            assertEquals(java.util.Collections.singleton(tree.toString()), manager.getLocalFolderImportTrees("Books"));
            for (DownloadInfo archive : imported) {
                assertTrue(archive.gid < 0);
                assertTrue(archive.archiveUri.startsWith("content://"));
                assertTrue(ImportedGalleryProgress.isImportedGallery(archive));
            }
            processFolder(scene, manager, null);
            assertEquals(2, manager.getAllDownloadInfoList().size());
            manager.renameLabel("Books", "My archives");
            documents.files.add("789.zip");
            processFolder(scene, manager, manager.findDownloadLabel("My archives"));
            assertEquals(3, manager.getLabelDownloadInfoList("My archives").size());
            assertEquals(3, manager.getAllDownloadInfoList().size());
        } finally {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            ReflectionHelpers.setStaticField(EhDB.class, "sDaoSession", previousSession);
            ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre", previousSettings);
            database.close();
        }
    }

    private void processFolder(DownloadsScene scene, DownloadManager manager, DownloadLabel label) {
        ReflectionHelpers.callInstanceMethod(scene, "processLocalFolder",
                ReflectionHelpers.ClassParameter.from(Context.class, context),
                ReflectionHelpers.ClassParameter.from(DownloadManager.class, manager),
                ReflectionHelpers.ClassParameter.from(Uri.class, tree),
                ReflectionHelpers.ClassParameter.from(DownloadLabel.class, label));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private LocalFolderGalleryScanner.ScanResult scan() throws Exception {
        return LocalFolderGalleryScanner.scan(context, LocalFolderGallerySource.create(tree, ""));
    }

    private LocalFolderGalleryScanner.ScanResult scanLocal(File root) throws Exception {
        return LocalFolderGalleryScanner.scanLocal(root,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(15), true);
    }

    private static class Documents extends ContentProvider {
        String rootId;
        List<String> files = new ArrayList<>();
        List<String> directories = new ArrayList<>();
        boolean nestedImage;
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection,
                String[] args, String order) {
            MatrixCursor cursor = new MatrixCursor(projection);
            String id = DocumentsContract.getDocumentId(uri);
            if ("children".equals(uri.getLastPathSegment())) {
                if (rootId.equals(id)) {
                    for (String file : files) row(cursor, projection, "opaque:" + file, file, false);
                    for (String dir : directories) row(cursor, projection, "dir:" + dir, dir, true);
                } else {
                    if (!"dir:chapter".equals(id) && !"dir:chapter_results_backup".equals(id)) {
                        throw new AssertionError("Traversed result directory: " + id);
                    }
                    if (nestedImage) row(cursor, projection, "nested-image", "002.png", false);
                }
            } else row(cursor, projection, id, "Books", true);
            return cursor;
        }
        private void row(MatrixCursor cursor, String[] projection, String id, String name, boolean directory) {
            Object[] values = new Object[projection.length];
            for (int i = 0; i < projection.length; i++) {
                values[i] = switch (projection[i]) {
                    case DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id;
                    case DocumentsContract.Document.COLUMN_DISPLAY_NAME -> name;
                    case DocumentsContract.Document.COLUMN_MIME_TYPE -> directory
                            ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream";
                    case DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 123L;
                    case DocumentsContract.Document.COLUMN_SIZE -> 456L;
                    default -> null;
                };
            }
            cursor.addRow(values);
        }
        @Override public String getType(Uri uri) { return DocumentsContract.Document.MIME_TYPE_DIR; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
    }
}
