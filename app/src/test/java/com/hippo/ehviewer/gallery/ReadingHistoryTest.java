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
import com.hippo.ehviewer.ui.LocalViewerActivity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.List;

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

    @Test public void externalOpenWithAndDirectoryVisitsStayLocal() {
        Uri image = Uri.parse("file:///pictures/book/001.jpg");
        ReadingHistory.record(context, new Intent(Intent.ACTION_VIEW, image));
        ReadingHistory.Entry entry = ReadingHistory.list(context).get(0);
        assertEquals(ReadingHistory.LOCAL, entry.source);
        assertEquals("001.jpg", entry.title);
        assertEquals(image, entry.createIntent(context).getData());
        assertEquals(LocalViewerActivity.class.getName(),
                entry.createIntent(context).getComponent().getClassName());
        ReadingHistory.record(context, new Intent(GalleryActivity.ACTION_DIR)
                .putExtra(GalleryActivity.KEY_FILENAME, "/pictures/book"));
        assertTrue(ReadingHistory.list(context).stream().anyMatch(item ->
                "book".equals(item.title) && "/pictures/book".equals(
                        item.createIntent(context).getStringExtra(GalleryActivity.KEY_FILENAME))));
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

    @Test public void evictsOldestUsingHistoryLimitAndClearPreservesLocalResumeProgress() {
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
