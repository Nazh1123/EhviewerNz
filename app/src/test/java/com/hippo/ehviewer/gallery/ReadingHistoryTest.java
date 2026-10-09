package com.hippo.ehviewer.gallery;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import com.alibaba.fastjson.JSON;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.ui.GalleryActivity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.List;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import com.hippo.unifile.UniFile;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28)
public class ReadingHistoryTest {
    private Context context;
    private SharedPreferences settings;
    private SharedPreferences storage;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        settings = context.getSharedPreferences("reading-settings-test", Context.MODE_PRIVATE);
        settings.edit().clear().commit();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre", settings);
        storage = context.getSharedPreferences(ReadingHistory.PREFERENCES_NAME, Context.MODE_PRIVATE);
        storage.edit().clear().commit();
        context.getSharedPreferences(LocalGalleryHistory.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test public void defaultEnabledAndDisabledStopsNewVisitsWithoutDeletingExistingOnes() {
        assertTrue(Settings.isReadingHistoryEnabled());
        ReadingHistory.record(context, online(1, ReadingHistory.DETAIL));
        settings.edit().putBoolean(Settings.KEY_RECORD_READING_HISTORY, false).apply();
        ReadingHistory.record(context, online(2, ReadingHistory.DOWNLOAD));
        assertEquals(1, ReadingHistory.list(context).size());
        assertEquals(1, ReadingHistory.list(context).get(0).gallery.gid);
        settings.edit().putBoolean(Settings.KEY_RECORD_READING_HISTORY, true).apply();
        ReadingHistory.record(context, online(2, ReadingHistory.DOWNLOAD));
        assertEquals(2, ReadingHistory.list(context).size());
    }

    @Test public void revisitingUpdatesSnapshotAndPreservesDifferentEntryWaysForSameGallery() {
        ReadingHistory.record(context, online(1, ReadingHistory.DETAIL));
        ReadingHistory.record(context, online(1, ReadingHistory.DOWNLOAD));
        Intent revised = online(1, ReadingHistory.DETAIL);
        GalleryInfo gallery = revised.getParcelableExtra(GalleryActivity.KEY_GALLERY_INFO);
        gallery.title = "Updated title";
        ReadingHistory.record(context, revised);
        List<ReadingHistory.Entry> entries = ReadingHistory.list(context);
        assertEquals(2, entries.size());
        ReadingHistory.Entry detail = entries.stream()
                .filter(entry -> ReadingHistory.DETAIL.equals(entry.source)).findFirst().get();
        assertEquals("Updated title", detail.title);
        Intent reopened = detail.createIntent(context);
        assertEquals(GalleryActivity.class.getName(), reopened.getComponent().getClassName());
        assertEquals(GalleryActivity.ACTION_EH, reopened.getAction());
        assertEquals(ReadingHistory.DETAIL, reopened.getStringExtra(ReadingHistory.KEY_SOURCE));
        GalleryInfo restored = reopened.getParcelableExtra(GalleryActivity.KEY_GALLERY_INFO);
        assertEquals("token1", restored.token);
        assertEquals(1, restored.gid);
    }

    @Test public void downloadedArchiveAndFolderKeepTheirActualProviderAndImportedGalleryId() {
        DownloadInfo imported = new DownloadInfo();
        imported.gid = -42;
        imported.title = "Imported archive";
        Uri archive = Uri.parse("content://archive-provider/book.cbz");
        Intent archiveIntent = new Intent(Intent.ACTION_VIEW, archive)
                .putExtra(ReadingHistory.KEY_SOURCE, ReadingHistory.DOWNLOAD)
                .putExtra(GalleryActivity.KEY_GALLERY_INFO, imported);
        ReadingHistory.record(context, archiveIntent);
        ReadingHistory.Entry archiveEntry = ReadingHistory.list(context).get(0);
        Intent reopened = archiveEntry.createIntent(context);
        assertEquals(Intent.ACTION_VIEW, reopened.getAction());
        assertEquals(archive, reopened.getData());
        assertEquals(-42, ((GalleryInfo) reopened.getParcelableExtra(GalleryActivity.KEY_GALLERY_INFO)).gid);
        assertEquals(ReadingHistory.DOWNLOAD, reopened.getStringExtra(ReadingHistory.KEY_SOURCE));
        DownloadInfo restored = reopened.getParcelableExtra(GalleryActivity.KEY_GALLERY_INFO);
        assertTrue(ImportedGalleryProgress.isImportedGallery(restored));

        String folder = LocalFolderGallerySource.create(
                Uri.parse("content://documents/tree/primary%3APictures"), "Book").encode();
        ReadingHistory.record(context, new Intent(GalleryActivity.ACTION_LOCAL_FOLDER)
                .putExtra(GalleryActivity.KEY_FILENAME, folder)
                .putExtra(ReadingHistory.KEY_SOURCE, ReadingHistory.DOWNLOAD));
        assertTrue(ReadingHistory.list(context).stream().anyMatch(entry -> {
            Intent request = entry.createIntent(context);
            return GalleryActivity.ACTION_LOCAL_FOLDER.equals(request.getAction())
                    && folder.equals(request.getStringExtra(GalleryActivity.KEY_FILENAME));
        }));
    }

    @Test public void externalOpenWithAndDirectoryVisitsShareDirectoryAndResumeProgress() throws IOException {
        Uri image = Uri.parse("file:///pictures/book/001.jpg");
        ReadingHistory.record(context, new Intent(Intent.ACTION_VIEW, image));
        ReadingHistory.Entry entry = ReadingHistory.list(context).get(0);
        String directory = new File("/pictures/book").getCanonicalPath();
        assertEquals(ReadingHistory.LOCAL, entry.source);
        assertEquals(directory, entry.title);
        assertEquals(GalleryActivity.ACTION_DIR, entry.createIntent(context).getAction());
        assertNull(entry.createIntent(context).getData());
        assertEquals("001.jpg", entry.createIntent(context).getStringExtra(GalleryActivity.KEY_LOCAL_RESUME_FILENAME));
        assertEquals(GalleryActivity.class.getName(),
                entry.createIntent(context).getComponent().getClassName());
        ReadingHistory.record(context, new Intent(Intent.ACTION_VIEW, Uri.parse("file:///pictures/book/010.jpg")));
        ReadingHistory.record(context, new Intent(GalleryActivity.ACTION_DIR)
                .putExtra(GalleryActivity.KEY_FILENAME, "/pictures/book"));
        assertEquals(1, ReadingHistory.list(context).size());
        LocalGalleryHistory.put(context, directory, "010.jpg");
        Intent reopen = ReadingHistory.list(context).get(0).createIntent(context);
        assertEquals(directory, reopen.getStringExtra(GalleryActivity.KEY_FILENAME));
        assertEquals("010.jpg", reopen.getStringExtra(GalleryActivity.KEY_LOCAL_RESUME_FILENAME));
        assertEquals(-1, reopen.getIntExtra(GalleryActivity.KEY_PAGE, 0));
    }

    @Test public void previewPageAndUpdateRecordAreReopenedWithoutEventBusDependency() {
        Intent request = online(9, ReadingHistory.PREVIEW)
                .putExtra(GalleryActivity.KEY_PAGE, 12)
                .putExtra(GalleryActivity.DATA_IN_EVENT, true)
                .putExtra(GalleryActivity.KEY_UPDATE_RECORD_GID, 8L)
                .putExtra(GalleryActivity.KEY_UPDATE_RECORD_TIME, 1234L);
        ReadingHistory.record(context, request);
        Intent reopened = ReadingHistory.list(context).get(0).createIntent(context);
        assertEquals(12, reopened.getIntExtra(GalleryActivity.KEY_PAGE, -1));
        assertEquals(8L, reopened.getLongExtra(GalleryActivity.KEY_UPDATE_RECORD_GID, 0));
        assertEquals(1234L, reopened.getLongExtra(GalleryActivity.KEY_UPDATE_RECORD_TIME, 0));
        assertFalse(reopened.getBooleanExtra(GalleryActivity.DATA_IN_EVENT, false));
        assertNotNull(reopened.getParcelableExtra(GalleryActivity.KEY_GALLERY_INFO));
    }

    @Test public void corruptRecordsAndInvalidReaderRequestsDoNotHideValidEntries() {
        storage.edit().putString("broken", "not json").putString("null", "null").apply();
        ReadingHistory.record(context, new Intent(GalleryActivity.ACTION_EH));
        ReadingHistory.record(context, new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")));
        ReadingHistory.record(context, online(5, ReadingHistory.DETAIL));
        assertEquals(1, ReadingHistory.list(context).size());
    }

    @Test public void evictsOldestUsingReadingHistoryLimitAndClearPreservesLocalResumeProgress() {
        Settings.setHistoryInfoSize(500);
        for (int i = 1; i <= 100; i++) {
            ReadingHistory.Entry entry = ReadingHistory.fromIntent(context, online(i, ReadingHistory.DETAIL));
            entry.readAt = i;
            storage.edit().putString(entry.key, JSON.toJSONString(entry)).apply();
        }
        ReadingHistory.record(context, online(101, ReadingHistory.DETAIL));
        List<ReadingHistory.Entry> entries = ReadingHistory.list(context);
        assertEquals(100, entries.size());
        assertEquals(101, entries.get(0).gallery.gid);
        assertFalse(entries.stream().anyMatch(entry -> entry.gallery.gid == 1));
        ReadingHistory.remove(context, entries.get(0));
        assertEquals(99, ReadingHistory.list(context).size());
        LocalGalleryHistory.put(context, "/pictures", "010.jpg");
        ReadingHistory.clear(context);
        assertTrue(ReadingHistory.list(context).isEmpty());
        assertEquals("010.jpg", LocalGalleryHistory.get(context, "/pictures").filename);
    }

    @Test public void readingLimitHasSameDefaultAndMinimumButIsIndependentAndTrimsOldest() {
        assertEquals(100, Settings.getReadingHistorySize());
        Settings.setReadingHistorySize(2);
        assertEquals(100, Settings.getReadingHistorySize());
        settings.edit().putString(Settings.KEY_READING_HISTORY_SIZE, "invalid").apply();
        assertEquals(100, Settings.getReadingHistorySize());
        Settings.setReadingHistorySize(500);
        assertEquals(100, Settings.getHistoryInfoSize());
        for (int i = 1; i <= 105; i++) {
            ReadingHistory.Entry entry = ReadingHistory.fromIntent(context, online(i, ReadingHistory.DETAIL));
            entry.readAt = i;
            storage.edit().putString(entry.key, JSON.toJSONString(entry)).apply();
        }
        assertEquals(105, ReadingHistory.list(context).size());
        Settings.setReadingHistorySize(100);
        List<ReadingHistory.Entry> entries = ReadingHistory.list(context);
        assertEquals(100, entries.size());
        assertEquals(105, entries.get(0).gallery.gid);
        assertEquals(6, entries.get(99).gallery.gid);
        assertEquals(100, storage.getAll().size());
    }

    @Test public void migratesOldImageVisitsAndKeepsLatestDirectoryVisit() throws IOException {
        for (int i = 1; i <= 2; i++) {
            ReadingHistory.Entry old = new ReadingHistory.Entry();
            old.action = Intent.ACTION_VIEW;
            old.uri = "file:///pictures/migration/" + i + ".jpg";
            old.source = ReadingHistory.LOCAL;
            old.key = old.source + ':' + old.action + ':' + old.uri;
            old.title = i + ".jpg";
            old.readAt = i;
            storage.edit().putString(old.key, JSON.toJSONString(old)).apply();
        }
        List<ReadingHistory.Entry> entries = ReadingHistory.list(context);
        assertEquals(1, entries.size());
        assertEquals(new File("/pictures/migration").getCanonicalPath(), entries.get(0).title);
        assertEquals("2.jpg", entries.get(0).resumeFilename);
        assertEquals(2, entries.get(0).readAt);
        assertEquals(1, storage.getAll().size());
        assertEquals(entries.get(0).key, ReadingHistory.list(context).get(0).key);
    }

    @Test public void directoryReaderPersistsProgressAndReopensWithoutExternalImagePrompt() throws IOException {
        File folder = new File(context.getCacheDir(), "resume-test");
        assertTrue(folder.mkdirs() || folder.isDirectory());
        for (String name : new String[]{"1.jpg", "2.jpg", "4.jpg"}) {
            File file = new File(folder, name);
            assertTrue(file.createNewFile() || file.isFile());
        }
        Intent open = new Intent(GalleryActivity.ACTION_DIR)
                .putExtra(GalleryActivity.KEY_FILENAME, folder.getCanonicalPath());
        GalleryActivity activity = Robolectric.buildActivity(GalleryActivity.class, open).get();
        ReflectionHelpers.callInstanceMethod(activity, "onInit");
        DirGalleryProvider provider = ReflectionHelpers.getField(activity, "mGalleryProvider");
        AtomicReference<UniFile[]> files = ReflectionHelpers.getField(provider, "mFileList");
        files.set(DirGalleryProvider.listAndSortImageFiles(UniFile.fromFile(folder)));
        ReflectionHelpers.setField(provider, "mSize", 3);
        ReflectionHelpers.setField(activity, "mCurrentIndex", 1);
        ReflectionHelpers.callInstanceMethod(activity, "persistReadingHistoryProgressNow");
        ReadingHistory.Entry entry = ReadingHistory.list(context).get(0);
        assertEquals("2.jpg", entry.resumeFilename);
        assertEquals(1, entry.page);
        LocalGalleryHistory.put(context, folder.getCanonicalPath(), "2.jpg", 1);
        for (int i = 0; i < LocalGalleryHistory.MAX_ENTRIES; i++) {
            LocalGalleryHistory.put(context, "/another-directory/" + i, "1.jpg", i + 2);
        }
        assertNull(LocalGalleryHistory.get(context, folder.getCanonicalPath()));
        Intent reopen = entry.createIntent(context);
        GalleryActivity resumed = Robolectric.buildActivity(GalleryActivity.class, reopen).get();
        ReflectionHelpers.callInstanceMethod(resumed, "onInit");
        DirGalleryProvider resumedProvider = ReflectionHelpers.getField(resumed, "mGalleryProvider");
        assertEquals("2.jpg", ReflectionHelpers.getField(resumedProvider, "mInitialFilename"));
        assertEquals(Boolean.TRUE, ReflectionHelpers.getField(resumedProvider, "mResumeFilename"));
        assertEquals(Boolean.FALSE, ReflectionHelpers.getField(resumed, "mExternalImage"));
        ReadingHistory.clear(context);
        ReflectionHelpers.callInstanceMethod(activity, "persistReadingHistoryProgressNow");
        assertTrue(ReadingHistory.list(context).isEmpty());
    }

    @Test public void importedFolderReopensUsingImportedProgressAndDirectoryTitle() {
        LocalFolderGallerySource folder = LocalFolderGallerySource.create(
                Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APictures"), "Book");
        DownloadInfo info = new DownloadInfo();
        info.gid = folder.stableGalleryId();
        info.title = "A gallery title";
        info.archiveUri = folder.encode();
        ImportedGalleryProgress.save(context, info.gid, 7, 20);
        Intent open = new Intent(GalleryActivity.ACTION_LOCAL_FOLDER)
                .putExtra(GalleryActivity.KEY_FILENAME, folder.encode())
                .putExtra(GalleryActivity.KEY_GALLERY_INFO, info)
                .putExtra(ReadingHistory.KEY_SOURCE, ReadingHistory.DOWNLOAD);
        ReadingHistory.record(context, open);
        ReadingHistory.Entry entry = ReadingHistory.list(context).get(0);
        assertTrue(entry.title.replace('\\', '/').endsWith("Pictures/Book"));
        Intent reopen = entry.createIntent(context);
        DownloadInfo restored = reopen.getParcelableExtra(GalleryActivity.KEY_GALLERY_INFO);
        assertTrue(ImportedGalleryProgress.isImportedGallery(restored));
        assertEquals(info.archiveUri, restored.archiveUri);
        assertEquals(-1, reopen.getIntExtra(GalleryActivity.KEY_PAGE, 0));
        assertEquals(7, ImportedGalleryProgress.getStartPage(context, restored.gid));
        ReadingHistory.record(context, new Intent(open).putExtra(ReadingHistory.KEY_SOURCE, ReadingHistory.LOCAL));
        assertEquals(1, ReadingHistory.list(context).size());
    }

    @Test public void localSafArchivesShareDirectoryAndReopenLastArchiveAtSavedPage() throws IOException {
        Uri first = Uri.parse("content://com.android.externalstorage.documents/document/primary%3APictures%2FBook%2Ffirst.cbz");
        Uri last = Uri.parse("content://com.android.externalstorage.documents/document/primary%3APictures%2FBook%2Flast.cbz");
        ReadingHistory.record(context, new Intent(Intent.ACTION_VIEW, first));
        Intent open = new Intent(Intent.ACTION_VIEW, last);
        ReadingHistory.record(context, open);
        ReadingHistory.saveLocalProgress(context, open, 8, null);
        assertEquals(1, ReadingHistory.list(context).size());
        ReadingHistory.Entry entry = ReadingHistory.list(context).get(0);
        assertEquals(new File(android.os.Environment.getExternalStorageDirectory(), "Pictures/Book")
                .getCanonicalPath(), entry.title);
        Intent reopen = entry.createIntent(context);
        assertEquals(Intent.ACTION_VIEW, reopen.getAction());
        assertEquals(last, reopen.getData());
        assertEquals(8, reopen.getIntExtra(GalleryActivity.KEY_PAGE, -1));
        ReadingHistory.saveLocalProgress(context, new Intent(Intent.ACTION_VIEW, first), 1, null);
        assertEquals(8, ReadingHistory.list(context).get(0).page);
    }

    @Test public void newestProgressWinsEvenWhenRecordingWasTemporarilyDisabled() {
        ReadingHistory.Entry entry = ReadingHistory.fromIntent(context,
                new Intent(Intent.ACTION_VIEW, Uri.parse("file:///pictures/timestamps/2.jpg")));
        entry.readAt = 10;
        entry.progressAt = 30;
        LocalGalleryHistory.put(context, entry.filename, "1.jpg", 20);
        assertEquals("2.jpg", entry.createIntent(context).getStringExtra(GalleryActivity.KEY_LOCAL_RESUME_FILENAME));
        settings.edit().putBoolean(Settings.KEY_RECORD_READING_HISTORY, false).apply();
        LocalGalleryHistory.put(context, entry.filename, "4.jpg", 40);
        settings.edit().putBoolean(Settings.KEY_RECORD_READING_HISTORY, true).apply();
        assertEquals("4.jpg", entry.createIntent(context).getStringExtra(GalleryActivity.KEY_LOCAL_RESUME_FILENAME));
    }

    @Test public void providerCreationRecordsOnceAndConfigurationRestoreDoesNotAddVisit() {
        GalleryActivity activity = Robolectric.buildActivity(GalleryActivity.class, online(7, ReadingHistory.DOWNLOAD)).get();
        ReflectionHelpers.callInstanceMethod(activity, "onInit");
        assertEquals(1, ReadingHistory.list(context).size());
        assertEquals(ReadingHistory.DOWNLOAD, ReadingHistory.list(context).get(0).source);
        ReadingHistory.clear(context);
        ReflectionHelpers.callInstanceMethod(activity, "buildProvider");
        assertTrue(ReadingHistory.list(context).isEmpty());
        GalleryActivity restored = Robolectric.buildActivity(GalleryActivity.class, online(7, ReadingHistory.DOWNLOAD)).get();
        android.os.Bundle state = new android.os.Bundle();
        state.putString(GalleryActivity.KEY_ACTION, GalleryActivity.ACTION_EH);
        state.putParcelable(GalleryActivity.KEY_GALLERY_INFO,
                online(7, ReadingHistory.DOWNLOAD).getParcelableExtra(GalleryActivity.KEY_GALLERY_INFO));
        ReflectionHelpers.callInstanceMethod(restored, "onRestore",
                ReflectionHelpers.ClassParameter.from(android.os.Bundle.class, state));
        assertTrue(ReadingHistory.list(context).isEmpty());
    }

    private Intent online(long gid, String source) {
        GalleryInfo gallery = new GalleryInfo();
        gallery.gid = gid;
        gallery.token = "token" + gid;
        gallery.title = "Gallery " + gid;
        return new Intent(GalleryActivity.ACTION_EH)
                .putExtra(GalleryActivity.KEY_GALLERY_INFO, gallery)
                .putExtra(ReadingHistory.KEY_SOURCE, source);
    }
}
