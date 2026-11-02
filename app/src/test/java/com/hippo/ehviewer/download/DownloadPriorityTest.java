package com.hippo.ehviewer.download;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DaoMaster;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.spider.SpiderQueen;

import org.greenrobot.greendao.database.StandardDatabase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;

import java.util.LinkedList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, shadows = DownloadPriorityTest.FakeSpider.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class DownloadPriorityTest {
    private SQLiteDatabase database;
    private DownloadManager manager;
    private List<DownloadInfo> downloads;

    @Before public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(Settings.class, "sContext", context);
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre",
                context.getSharedPreferences("priority-test", Context.MODE_PRIVATE));
        database = SQLiteDatabase.create(null);
        DaoMaster.createAllTables(new StandardDatabase(database), false);
        ReflectionHelpers.setStaticField(EhDB.class, "sDaoSession", new DaoMaster(database).newSession());
        manager = new DownloadManager(context);
        downloads = new java.util.ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            DownloadInfo info = new DownloadInfo();
            info.gid = i;
            info.token = "token";
            info.title = "Gallery " + i;
            downloads.add(info);
        }
        manager.addDownload(downloads);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        for (DownloadInfo info : downloads) manager.startDownload(info, null);
    }

    @After public void tearDown() {
        manager.stopAllDownload();
        ReflectionHelpers.setStaticField(EhDB.class, "sDaoSession", null);
        database.close();
    }

    @Test public void middleWaitingGalleryStartsImmediatelyAndOthersKeepOrder() {
        List<DownloadInfo> visibleOrder = List.copyOf(manager.getAllDownloadInfoList());
        manager.prioritizeDownload(3);
        assertCurrent(3);
        assertQueue(1L, 2L, 4L);
        assertEquals(DownloadInfo.STATE_WAIT, downloads.get(0).state);
        try (android.database.Cursor cursor = database.rawQuery(
                "SELECT STATE FROM DOWNLOADS WHERE GID = 1", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(DownloadInfo.STATE_WAIT, cursor.getInt(0));
        }
        assertEquals(visibleOrder, manager.getAllDownloadInfoList());
        manager.stopCurrentDownload();
        assertCurrent(1);
        assertQueue(2L, 4L);
    }

    @Test public void firstWaitingGalleryPreemptsCurrentDownload() {
        manager.prioritizeDownload(2);
        assertCurrent(2);
        assertQueue(1L, 3L, 4L);
    }

    @Test public void repeatedPriorityRequestsDoNotDuplicateTasks() {
        manager.prioritizeDownload(4);
        manager.prioritizeDownload(4);
        assertCurrent(4);
        assertQueue(1L, 2L, 3L);
        manager.prioritizeDownload(3);
        assertCurrent(3);
        assertQueue(4L, 1L, 2L);
    }

    @Test public void staleRequestCannotStartPausedFinishedOrMissingDownloads() {
        manager.stopDownload(3);
        downloads.get(2).state = DownloadInfo.STATE_FINISH;
        manager.prioritizeDownload(3);
        manager.prioritizeDownload(999);
        manager.prioritizeDownload(1);
        assertCurrent(1);
        assertQueue(2L, 4L);
    }

    @Test public void queuedCallbacksFromInterruptedDownloadCannotAffectNewTask() {
        manager.onGetPages(99);
        manager.onPageSuccess(0, 99, 99, 99);
        manager.onFinish(99, 99, 99);
        manager.prioritizeDownload(3);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertCurrent(3);
        assertEquals(-1, downloads.get(2).total);
        assertEquals(0, downloads.get(2).finished);
        assertQueue(1L, 2L, 4L);
        manager.onGetPages(42);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(42, downloads.get(2).total);
    }

    @Test public void startsWaitingTaskWhenNoCurrentDownloadExists() {
        ReflectionHelpers.callInstanceMethod(manager, "stopCurrentDownloadInternal");
        manager.prioritizeDownload(4);
        assertCurrent(4);
        assertQueue(2L, 3L);
    }

    private void assertCurrent(long gid) {
        DownloadInfo current = ReflectionHelpers.getField(manager, "mCurrentTask");
        assertNotNull(current);
        assertEquals(gid, current.gid);
        assertEquals(DownloadInfo.STATE_DOWNLOAD, current.state);
    }

    private void assertQueue(Long... gids) {
        LinkedList<DownloadInfo> queue = ReflectionHelpers.getField(manager, "mWaitList");
        assertEquals(List.of(gids), queue.stream().map(info -> info.gid).toList());
    }

    @Implements(SpiderQueen.class)
    public static class FakeSpider {
        @Implementation protected static SpiderQueen obtainSpiderQueen(
                Context context, GalleryInfo gallery, int mode) {
            return Shadow.newInstanceOf(SpiderQueen.class);
        }
        @Implementation protected static void releaseSpiderQueen(SpiderQueen spider, int mode) {}
        @Implementation protected void addOnSpiderListener(SpiderQueen.OnSpiderListener listener) {}
        @Implementation protected void removeOnSpiderListener(SpiderQueen.OnSpiderListener listener) {}
    }
}
