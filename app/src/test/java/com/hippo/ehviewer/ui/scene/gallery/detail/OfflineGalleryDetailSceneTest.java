package com.hippo.ehviewer.ui.scene.gallery.detail;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.widget.PopupMenu;

import com.hippo.conaco.Conaco;
import com.hippo.conaco.DataContainer;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhClient;
import com.hippo.ehviewer.client.EhRequest;
import com.hippo.ehviewer.client.data.GalleryComment;
import com.hippo.ehviewer.client.data.GalleryCommentList;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.GalleryTagGroup;
import com.hippo.ehviewer.client.data.NormalPreviewSet;
import com.hippo.ehviewer.client.exception.EhException;
import com.hippo.ehviewer.dao.DaoMaster;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.ui.MainActivity;
import com.hippo.ehviewer.ui.scene.GalleryInfoScene;
import com.hippo.lib.image.Image;
import com.hippo.unifile.UniFile;
import com.hippo.view.ViewTransition;
import com.hippo.widget.LoadImageView;

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
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = OfflineGalleryDetailSceneTest.TestApplication.class, sdk = 28, shadows = {
        OfflineGalleryDetailSceneTest.AppShadow.class,
        OfflineGalleryDetailSceneTest.ClientShadow.class,
        OfflineGalleryDetailSceneTest.ImageShadow.class})
public class OfflineGalleryDetailSceneTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();
    private static DownloadManager manager;
    private static EhClient client;
    private static QueuedExecutor executor;
    private static final List<EhRequest> requests = new ArrayList<>();
    private static final List<Boolean> imageNetworkLoads = new ArrayList<>();
    private SQLiteDatabase database;
    private TestScene scene;
    private View content;
    private Context context;

    @Before public void setUp() {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme);
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre", context.getSharedPreferences("offline-test", Context.MODE_PRIVATE));
        ReflectionHelpers.setStaticField(Settings.class, "sArchiverPre", context.getSharedPreferences("offline-archiver-test", Context.MODE_PRIVATE));
        context.getSharedPreferences("offline-test", Context.MODE_PRIVATE).edit().clear().commit();
        database = SQLiteDatabase.create(null);
        DaoMaster.createAllTables(new StandardDatabase(database), false);
        ReflectionHelpers.setStaticField(EhDB.class, "sDaoSession", new DaoMaster(database).newSession());
        manager = new DownloadManager(context);
        client = Shadow.newInstanceOf(EhClient.class);
        executor = new QueuedExecutor();
        requests.clear();
        imageNetworkLoads.clear();
        scene = new TestScene(context);
        ReflectionHelpers.setField(scene, "mDownloadState", DownloadInfo.STATE_INVALID);
        content = LayoutInflater.from(context).inflate(R.layout.gallery_detail_content, null);
        String[] fields = {"mThumb", "mTitle", "mUploader", "mCategory", "mDownload", "mRead", "mHaveNewVersion",
                "mArchiverDownloadProgress", "mUpdateActionGroup", "mUpdateGallery", "mGalleryHistory", "mUpdateActionDivider",
                "mLanguage", "mPages", "mSize", "mPosted", "mFavoredTimes", "mHeartGroup", "mRate", "mShare", "mTorrent",
                "mArchiver", "mHaH", "mSearchCover", "mLocalDelete", "mRating", "mRatingText", "mComments", "mCommentsText",
                "mTags", "mNoTags", "mGridLayout", "mPreviewText", "mHeart", "mHeartOutline"};
        int[] ids = {R.id.thumb, R.id.title, R.id.uploader, R.id.category, R.id.download, R.id.read, R.id.new_version,
                R.id.archiver_download_progress, R.id.update_action_card, R.id.update_gallery, R.id.gallery_history, R.id.update_action_divider,
                R.id.language, R.id.pages, R.id.size, R.id.posted, R.id.favoredTimes, R.id.heart_group, R.id.rate, R.id.share, R.id.torrent,
                R.id.archiver, R.id.h_h, R.id.search_cover, R.id.local_delete, R.id.rating, R.id.rating_text, R.id.comments, R.id.comments_text,
                R.id.tags, R.id.no_tags, R.id.grid_layout, R.id.preview_text, R.id.heart, R.id.heart_outline};
        for (int i = 0; i < fields.length; i++) ReflectionHelpers.setField(scene, fields[i], content.findViewById(ids[i]));
        TextView tip = new TextView(context);
        ReflectionHelpers.setField(scene, "mTip", tip);
        ReflectionHelpers.setField(scene, "mViewTransition", new ViewTransition(content, new View(context), tip));
        ReflectionHelpers.setField(scene, "mViewTransition2", new ViewTransition(content, new View(context)));
        ReflectionHelpers.setField(scene, "mGalleryInfo", source());
    }

    @After public void tearDown() {
        database.close();
        ReflectionHelpers.setStaticField(EhDB.class, "sDaoSession", null);
    }

    private GalleryInfo source() {
        GalleryInfo info = new GalleryInfo();
        info.gid = 123;
        info.token = "token";
        info.title = "Saved title";
        info.titleJpn = "保存的标题";
        info.thumb = "https://example.invalid/cover.jpg";
        info.pages = 10;
        return info;
    }

    private void fail(String message) {
        scene.onGetGalleryDetailFailure(new EhException(message));
        executor.runAll();
        ShadowLooper.idleMainLooper();
    }

    private GalleryDetail online() {
        GalleryDetail detail = new GalleryDetail();
        detail.gid = 123;
        detail.token = "token";
        detail.title = "Online title";
        detail.thumb = "https://example.invalid/cover.jpg";
        detail.firstGid = 123L;
        detail.pages = 12;
        detail.tags = new GalleryTagGroup[0];
        detail.comments = new GalleryCommentList(new GalleryComment[0], false);
        detail.previewSet = new NormalPreviewSet();
        return detail;
    }

    @Test public void uploadTimePreferenceAppliesToOnlineAndOfflineDetailsWithoutMutatingSource() {
        java.util.TimeZone previous = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
            GalleryInfo info = ReflectionHelpers.getField(scene, "mGalleryInfo");
            info.posted = "2026-01-01 16:00";
            fail("Offline");
            assertEquals("2026-01-02 00:00", text(R.id.posted));
            assertEquals("2026-01-01 16:00", info.posted);
            Settings.putBoolean(Settings.KEY_USE_LOCAL_TIMEZONE, false);
            ReflectionHelpers.callInstanceMethod(scene, "bindOfflineDetail");
            assertEquals("2026-01-01 16:00", text(R.id.posted));

            GalleryDetail detail = online();
            detail.posted = "2026-01-01 16:00";
            ReflectionHelpers.setField(scene, "mUpdateActionGroup", null);
            Settings.putBoolean(Settings.KEY_USE_LOCAL_TIMEZONE, true);
            scene.onGetGalleryDetailSuccess(detail);
            assertEquals("2026-01-02 00:00", text(R.id.posted));
            assertEquals("2026-01-01 16:00", detail.posted);
            Settings.putBoolean(Settings.KEY_USE_LOCAL_TIMEZONE, false);
            scene.onGetGalleryDetailSuccess(detail);
            assertEquals("2026-01-01 16:00", text(R.id.posted));
        } finally {
            java.util.TimeZone.setDefault(previous);
        }
    }

    @Test public void failureShowsLocalContentErrorAndArtistSimilarAndLocalDeleteActions() {
        Settings.setShowGalleryComment(false);
        Settings.setShowGalleryRating(false);
        fail("Gallery removed");
        assertEquals("Saved title", text(R.id.title));
        assertEquals(context.getString(R.string.refresh), text(R.id.download));
        assertEquals("\n" + context.getString(R.string.offline_gallery_notice) + "\nGallery removed", text(R.id.rating_text));
        assertEquals(context.getString(R.string.offline_gallery_refresh), text(R.id.comments_text));
        for (int id : new int[]{R.id.artist, R.id.similar, R.id.comments, R.id.rating_text}) assertEquals(View.VISIBLE, content.findViewById(id).getVisibility());
        for (int id : new int[]{R.id.heart_group, R.id.rate, R.id.share, R.id.torrent, R.id.archiver, R.id.h_h, R.id.search_cover,
                R.id.rating, R.id.favoredTimes, R.id.update_action_card, R.id.local_delete}) assertEquals(View.GONE, content.findViewById(id).getVisibility());
        assertFalse(imageNetworkLoads.isEmpty());
        assertFalse(imageNetworkLoads.contains(true));
        assertNull(ReflectionHelpers.getField(scene, "mGalleryDetail"));
        assertTrue(requests.isEmpty());
    }

    @Test public void downloadAndLongPressRefreshOnceWithoutDeletingLocalDownload() {
        fail("Offline");
        ReflectionHelpers.callInstanceMethod(scene, "onDownload");
        assertTrue(scene.onLongClick(content.findViewById(R.id.download)));
        scene.onClick(content.findViewById(R.id.comments));
        assertEquals(1, requests.size());
        assertEquals(EhClient.METHOD_GET_GALLERY_DETAIL, requests.get(0).getMethod());
        assertTrue(requests.get(0).getArgs()[0].toString().contains("123/token"));
        assertFalse(content.findViewById(R.id.download).isEnabled());
        ((EhApplication) RuntimeEnvironment.getApplication()).removeGlobalStuff(requests.get(0).getCallback());
        fail("Another error");
        assertTrue(content.findViewById(R.id.download).isEnabled());
        assertTrue(text(R.id.rating_text).endsWith("\nAnother error"));
    }

    @Test public void onlineSuccessRestoresActionsAndRejectsQueuedOfflineResult() {
        fail("Offline");
        scene.onGetGalleryDetailFailure(new EhException("Stale failure"));
        ReflectionHelpers.setField(scene, "mUpdateActionGroup", null);
        scene.onGetGalleryDetailSuccess(online());
        executor.runAll();
        ShadowLooper.idleMainLooper();
        assertFalse(ReflectionHelpers.<Boolean>getField(scene, "mOffline"));
        assertEquals("Online title", text(R.id.title));
        assertEquals(View.VISIBLE, content.findViewById(R.id.share).getVisibility());
        assertEquals(View.VISIBLE, content.findViewById(R.id.rating).getVisibility());
        assertFalse(text(R.id.rating_text).contains("Stale failure"));
        assertEquals(context.getString(R.string.download), text(R.id.download));
    }

    @Test public void offlineStatusErrorAndTokenSurviveSavedState() {
        fail("Gallery removed");
        ReflectionHelpers.setField(scene, "mToken", "original-token");
        Bundle saved = new Bundle();
        scene.onSaveInstanceState(saved);
        TestScene restored = new TestScene(context);
        ReflectionHelpers.callInstanceMethod(restored, "onRestore", ReflectionHelpers.ClassParameter.from(Bundle.class, saved));
        assertTrue(ReflectionHelpers.<Boolean>getField(restored, "mOffline"));
        assertEquals("Gallery removed", ReflectionHelpers.getField(restored, "mOfflineError"));
        assertEquals("original-token", ReflectionHelpers.getField(restored, "mToken"));
    }

    @Test public void noLocalInformationKeepsTheOriginalFailureView() {
        GalleryInfo empty = new GalleryInfo();
        empty.gid = 123;
        empty.token = "token";
        ReflectionHelpers.setField(scene, "mGalleryInfo", empty);
        fail("Original error");
        assertFalse(ReflectionHelpers.<Boolean>getField(scene, "mOffline"));
        assertEquals("Original error", ((TextView) ReflectionHelpers.getField(scene, "mTip")).getText().toString());
        assertEquals(3, ReflectionHelpers.<Integer>getField(scene, "mState").intValue());
    }

    @Test public void offlineInformationOmitsUnavailableWebsiteCounters() {
        GalleryInfoScene infoScene = new GalleryInfoScene();
        ReflectionHelpers.setField(infoScene, "mThemeContext", context);
        ReflectionHelpers.setField(infoScene, "mKeys", new ArrayList<String>());
        ReflectionHelpers.setField(infoScene, "mValues", new ArrayList<String>());
        Bundle args = new Bundle();
        args.putParcelable(GalleryInfoScene.KEY_GALLERY_DETAIL, OfflineGalleryDetail.read(source(), null, null).info);
        args.putBoolean(GalleryInfoScene.KEY_OFFLINE, true);
        ReflectionHelpers.callInstanceMethod(infoScene, "handlerArgs", ReflectionHelpers.ClassParameter.from(Bundle.class, args));
        List<String> keys = ReflectionHelpers.getField(infoScene, "mKeys");
        assertTrue(keys.contains(context.getString(R.string.key_title)));
        assertTrue(keys.contains(context.getString(R.string.key_title_jpn)));
        assertFalse(keys.contains(context.getString(R.string.key_favorite_count)));
        assertFalse(keys.contains(context.getString(R.string.key_rating_count)));
        assertFalse(keys.contains(context.getString(R.string.key_torrents)));
        assertFalse(keys.contains(context.getString(R.string.key_parent)));
    }

    @Test public void localMenuActionsFollowLocalDeleteForEveryDownloadStateOnlineAndOffline() {
        ReflectionHelpers.setField(scene, "mOtherActions", new ImageView(context));
        ReflectionHelpers.callInstanceMethod(scene, "ensurePopMenu");
        PopupMenu popup = ReflectionHelpers.getField(scene, "mPopupMenu");
        assertFalse(popup.getMenu().findItem(R.id.action_view_local_gallery_size).isVisible());
        for (boolean offline : new boolean[]{false, true}) {
            ReflectionHelpers.setField(scene, "mOffline", offline);
            for (int state : new int[]{DownloadInfo.STATE_FINISH, DownloadInfo.STATE_NONE,
                    DownloadInfo.STATE_INVALID, DownloadInfo.STATE_WAIT, DownloadInfo.STATE_DOWNLOAD,
                    DownloadInfo.STATE_FAILED, DownloadInfo.STATE_UPDATE, DownloadInfo.GOTO_NEW}) {
                ReflectionHelpers.setField(scene, "mDownloadState", state);
                ReflectionHelpers.callInstanceMethod(scene, "updateDownloadText");
                boolean visible = state == DownloadInfo.STATE_FINISH || state == DownloadInfo.STATE_NONE;
                assertEquals(visible ? View.VISIBLE : View.GONE, content.findViewById(R.id.local_delete).getVisibility());
                for (int id : new int[]{R.id.action_open_local_gallery_image,
                        R.id.action_copy_local_gallery_path, R.id.action_view_local_gallery_size}) {
                    assertEquals("offline=" + offline + ", state=" + state, visible, popup.getMenu().findItem(id).isVisible());
                }
                assertTrue(popup.getMenu().findItem(R.id.action_refresh).isVisible());
                assertTrue(popup.getMenu().findItem(R.id.action_open_in_other_app).isVisible());
            }
        }
    }

    @Test public void localFolderSizeIncludesNestedFilesAndMetadataWithoutRequiringImages() throws Exception {
        File directory = temporaryFolder.newFolder("gallery");
        File nested = new File(directory, "translations");
        assertTrue(nested.mkdir());
        Files.write(new File(directory, ".ehviewer").toPath(), new byte[13]);
        Files.write(new File(nested, "translated.png").toPath(), new byte[1024]);
        Files.write(new File(nested, "empty").toPath(), new byte[0]);
        assertEquals(1037L, calculateSize(directory));
        Files.write(new File(directory, ".ehviewer").toPath(), new byte[26]);
        assertEquals(1050L, calculateSize(directory));
        assertEquals(0L, calculateSize(temporaryFolder.newFolder("empty")));
    }

    @Test public void missingLocalFolderDoesNotReportZeroSize() throws Exception {
        InvocationTargetException error = assertThrows(InvocationTargetException.class,
                () -> calculateSize(new File(temporaryFolder.getRoot(), "missing")));
        assertTrue(error.getCause() instanceof IOException);
    }

    private long calculateSize(File directory) throws Exception {
        Method method = GalleryDetailScene.class.getDeclaredMethod("calculateLocalGallerySize", UniFile.class);
        method.setAccessible(true);
        return (long) method.invoke(null, UniFile.fromFile(directory));
    }

    private String text(int id) { return ((TextView) content.findViewById(id)).getText().toString(); }

    public static class TestApplication extends EhApplication {
        @Override public void onCreate() {}
    }

    private static class TestScene extends GalleryDetailScene {
        final Context context;
        final MainActivity activity = Shadow.newInstanceOf(MainActivity.class);
        TestScene(Context context) { this.context = context; }
        @Override public Context getContext() { return context; }
        @Override public Context getEHContext() { return context; }
        @Override public MainActivity getActivity2() { return activity; }
    }

    @Implements(EhApplication.class)
    public static class AppShadow extends org.robolectric.shadows.ShadowApplication {
        @Implementation protected static Conaco<Image> getConaco(Context context) { return null; }
        @Implementation protected static DownloadManager getDownloadManager(Context context) { return manager; }
        @Implementation protected static EhClient getEhClient(Context context) { return client; }
        @Implementation protected static ExecutorService getExecutorService(Context context) { return executor; }
    }

    @Implements(EhClient.class)
    public static class ClientShadow {
        @Implementation protected void execute(EhRequest request) { requests.add(request); }
    }

    @Implements(LoadImageView.class)
    public static class ImageShadow extends org.robolectric.shadows.ShadowView {
        @Implementation protected void unload() {}
        @Implementation protected void load(String key, String url, DataContainer container, boolean network, boolean hardware) {
            imageNetworkLoads.add(network);
        }
    }

    private static class QueuedExecutor extends AbstractExecutorService {
        final List<Runnable> queue = new ArrayList<>();
        @Override public void execute(Runnable task) { queue.add(task); }
        void runAll() { while (!queue.isEmpty()) queue.remove(0).run(); }
        @Override public void shutdown() {}
        @Override public List<Runnable> shutdownNow() { return new ArrayList<>(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }
}
