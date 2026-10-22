package com.hippo.ehviewer.ui.scene.gallery.detail;

import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.RatingBar;

import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.app.AlertDialog;

import com.hippo.beerbelly.SimpleDiskCache;
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
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.client.exception.EhException;
import com.hippo.ehviewer.dao.DaoMaster;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.download.GalleryUpdateManager;
import com.hippo.ehviewer.download.GalleryUpdateRecord;
import com.hippo.ehviewer.download.GalleryUpdateRecordStore;
import com.hippo.ehviewer.ui.MainActivity;
import com.hippo.ehviewer.ui.GalleryActivity;
import com.hippo.ehviewer.ui.scene.GalleryInfoScene;
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.scene.Announcer;
import com.hippo.streampipe.OutputStreamPipe;
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
import org.robolectric.shadows.ShadowDialog;
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
    private GalleryUpdateRecordStore recordStore;
    private TestScene scene;
    private View sceneView;
    private View content;
    private Context context;

    @Before public void setUp() throws Exception {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme);
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre", context.getSharedPreferences("offline-test", Context.MODE_PRIVATE));
        ReflectionHelpers.setStaticField(Settings.class, "sArchiverPre", context.getSharedPreferences("offline-archiver-test", Context.MODE_PRIVATE));
        context.getSharedPreferences("offline-test", Context.MODE_PRIVATE).edit().clear().commit();
        context.deleteDatabase("gallery_update_records.db");
        recordStore = GalleryUpdateRecordStore.get(context);
        database = SQLiteDatabase.create(null);
        DaoMaster.createAllTables(new StandardDatabase(database), false);
        ReflectionHelpers.setStaticField(EhDB.class, "sDaoSession", new DaoMaster(database).newSession());
        manager = new DownloadManager(context);
        client = Shadow.newInstanceOf(EhClient.class);
        executor = new QueuedExecutor();
        ReflectionHelpers.setField(RuntimeEnvironment.getApplication(), "mSpiderInfoCache",
                new SimpleDiskCache(temporaryFolder.newFolder("progress"), 1024 * 1024));
        ((android.util.LruCache<?, ?>) ReflectionHelpers.getStaticField(SpiderQueen.class, "sStartPages")).evictAll();
        requests.clear();
        imageNetworkLoads.clear();
        scene = new TestScene(context);
        ReflectionHelpers.setField(scene, "mDownloadState", DownloadInfo.STATE_INVALID);
        sceneView = LayoutInflater.from(context).inflate(R.layout.scene_gallery_detail, null);
        content = sceneView.findViewById(R.id.below_header);
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
        ReflectionHelpers.setField(scene, "mViewTransition", new ViewTransition(
                sceneView.findViewById(R.id.preview_pull_layout), sceneView.findViewById(R.id.progress_view), tip));
        ReflectionHelpers.setField(scene, "mViewTransition2", new ViewTransition(
                content.findViewById(R.id.detail_body), sceneView.findViewById(R.id.progress)));
        ReflectionHelpers.setField(scene, "mInfo", content.findViewById(R.id.info));
        ReflectionHelpers.setField(scene, "mArtist", content.findViewById(R.id.artist));
        ReflectionHelpers.setField(scene, "mSimilar", content.findViewById(R.id.similar));
        ReflectionHelpers.setField(scene, "mGalleryInfo", source());
    }

    @After public void tearDown() {
        recordStore.close();
        ReflectionHelpers.setStaticField(GalleryUpdateRecordStore.class, "instance", null);
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

    private void bindPendingHeader() {
        ReflectionHelpers.setField(scene, "mAction", GalleryDetailScene.ACTION_GALLERY_INFO);
        ReflectionHelpers.callInstanceMethod(scene, "bindViewFirst");
        ReflectionHelpers.callInstanceMethod(scene, "adjustViewVisibility",
                ReflectionHelpers.ClassParameter.from(int.class, 2),
                ReflectionHelpers.ClassParameter.from(boolean.class, false));
    }

    @Test public void knownSummaryRendersOutsideLoadingBodyWithoutRequestingMoreData() {
        GalleryInfo info = ReflectionHelpers.getField(scene, "mGalleryInfo");
        info.rating = 4.5f;
        info.firstGid = 100L;
        info.posted = "2026-01-01 16:00";
        bindPendingHeader();
        // onResume/download callbacks also refresh version actions while HTML is pending.
        ReflectionHelpers.callInstanceMethod(scene, "updateGalleryVersionActionsVisibility");
        assertEquals("Saved title", text(R.id.title));
        assertEquals(4.5f, ((RatingBar) content.findViewById(R.id.rating)).getRating(), 0.001f);
        assertTrue(text(R.id.rating_text).contains(String.format(java.util.Locale.getDefault(), "%.2f", 4.5f)));
        assertEquals("0/10P", text(R.id.pages));
        assertEquals(View.VISIBLE, content.findViewById(R.id.header).getVisibility());
        assertEquals(View.VISIBLE, content.findViewById(R.id.actions).getVisibility());
        assertEquals(View.VISIBLE, content.getVisibility());
        assertEquals(View.GONE, content.findViewById(R.id.detail_body).getVisibility());
        assertEquals(View.VISIBLE, sceneView.findViewById(R.id.progress).getVisibility());
        assertEquals(View.GONE, content.findViewById(R.id.update_action_card).getVisibility());
        for (int id : new int[]{R.id.update_gallery, R.id.info, R.id.rate, R.id.heart_group,
                R.id.torrent, R.id.h_h, R.id.archiver, R.id.artist, R.id.similar}) {
            assertTrue(content.findViewById(id).isEnabled());
        }
        assertTrue(content.findViewById(R.id.download).isEnabled());
        assertTrue(content.findViewById(R.id.read).isEnabled());
        assertTrue(imageNetworkLoads.contains(true));
        assertTrue(requests.isEmpty());
        // Hiding the body cannot hide any part of the visible summary.
        for (int id : new int[]{R.id.header, R.id.info, R.id.actions}) {
            assertSame(content, content.findViewById(id).getParent());
        }
    }

    @Test public void missingLanguageDefaultsToJapaneseAndMissingTokenDisablesGalleryOperations() {
        GalleryInfo info = ReflectionHelpers.getField(scene, "mGalleryInfo");
        info.token = null;
        info.rating = -1;
        info.pages = 0;
        bindPendingHeader();
        assertEquals("", text(R.id.rating_text));
        assertEquals("", text(R.id.pages));
        assertEquals("", text(R.id.size));
        assertEquals("Japanese", text(R.id.language));
        assertEquals(context.getString(R.string.not_favorited), text(R.id.heart_outline));
        assertEquals(context.getString(R.string.favored_times, 0).replace("0", " "), text(R.id.favoredTimes));
        assertEquals(context.getString(R.string.torrent_count, 0).replace("0", "  "), text(R.id.torrent));
        assertEquals(View.GONE, content.findViewById(R.id.tags).getVisibility());
        assertEquals(View.GONE, content.findViewById(R.id.update_action_card).getVisibility());
        assertFalse(content.findViewById(R.id.download).isEnabled());
        assertFalse(content.findViewById(R.id.read).isEnabled());
    }

    @Test public void loadingFloatsAtLowerQuarterWithoutTakingContentSpaceAndClearsOnStateChange() {
        View loading = sceneView.findViewById(R.id.progress);
        View indicator = loading.findViewById(R.id.detail_loading_indicator);
        assertNull(content.findViewById(R.id.progress));
        assertSame(sceneView, loading.getParent());
        assertSame(content, content.findViewById(R.id.detail_body).getParent());
        for (int[] size : new int[][]{{1080, 1920}, {1920, 1080}, {800, 600}}) {
            bindPendingHeader();
            sceneView.measure(View.MeasureSpec.makeMeasureSpec(size[0], View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(size[1], View.MeasureSpec.EXACTLY));
            sceneView.layout(0, 0, size[0], size[1]);
            assertEquals(size[0] / 2f, indicator.getLeft() + indicator.getWidth() / 2f, 1f);
            assertEquals(size[1] * 0.75f, indicator.getTop() + indicator.getHeight() / 2f, 1f);
            assertFalse(loading.isClickable());
            assertFalse(indicator.isClickable());
        }
        for (int state : new int[]{0, 1, 3}) {
            bindPendingHeader();
            ReflectionHelpers.callInstanceMethod(scene, "adjustViewVisibility",
                    ReflectionHelpers.ClassParameter.from(int.class, state),
                    ReflectionHelpers.ClassParameter.from(boolean.class, false));
            assertEquals(View.GONE, loading.getVisibility());
            assertEquals(state == 1 ? View.VISIBLE : View.GONE,
                    sceneView.findViewById(R.id.progress_view).getVisibility());
        }
    }

    @Test public void pendingCountsPreserveFinalLocalizedFormatAndRequestedBlankWidths() {
        for (java.util.Locale locale : new java.util.Locale[]{java.util.Locale.ENGLISH,
                java.util.Locale.SIMPLIFIED_CHINESE, java.util.Locale.JAPANESE,
                java.util.Locale.GERMAN, java.util.Locale.forLanguageTag("ar")}) {
            android.content.res.Configuration configuration =
                    new android.content.res.Configuration(context.getResources().getConfiguration());
            configuration.setLocale(locale);
            // The themed context can use a different language from the base Fragment context.
            scene.themeContext = new ContextThemeWrapper(context.createConfigurationContext(configuration), R.style.AppTheme);
            ReflectionHelpers.setField(scene, "mGalleryDetail", null);
            GalleryInfo summary = source();
            summary.rating = 4.5f;
            ReflectionHelpers.setField(scene, "mGalleryInfo", summary);
            bindPendingHeader();
            String favorites = text(R.id.favoredTimes);
            String torrents = text(R.id.torrent);
            String rating = text(R.id.rating_text);
            GalleryDetail detail = online();
            detail.rating = 4.5f;
            detail.ratingCount = 123;
            detail.favoriteCount = 987;
            detail.torrentCount = 64;
            ReflectionHelpers.setField(scene, "mUpdateActionGroup", null);
            scene.onGetGalleryDetailSuccess(detail);
            assertEquals(locale.toLanguageTag(),
                    text(R.id.favoredTimes).replace(String.format(locale, "%d", 987), " "), favorites);
            assertEquals(locale.toLanguageTag(),
                    text(R.id.torrent).replace(String.format(locale, "%d", 64), "  "), torrents);
            assertEquals(locale.toLanguageTag(),
                    text(R.id.rating_text).replace(String.format(locale, "%d", 123), "   "), rating);
        }
    }

    private DownloadInfo addKnownDownload(long gid, Long firstGid, int state) {
        DownloadInfo download = new DownloadInfo(source());
        download.gid = gid;
        download.firstGid = firstGid;
        download.state = state;
        ReflectionHelpers.callInstanceMethod(manager, "addToAllDownloads",
                ReflectionHelpers.ClassParameter.from(DownloadInfo.class, download),
                ReflectionHelpers.ClassParameter.from(boolean.class, false));
        return download;
    }

    @Test public void pendingUpdateNeedsKnownLocalOlderVersionAndRespondsToItsRemoval() {
        GalleryInfo summary = ReflectionHelpers.getField(scene, "mGalleryInfo");
        summary.firstGid = 100L;
        bindPendingHeader();
        assertEquals(View.GONE, content.findViewById(R.id.update_action_card).getVisibility());
        // A gallery from a different family is not an update source.
        addKnownDownload(90, 90L, DownloadInfo.STATE_FINISH);
        ReflectionHelpers.callInstanceMethod(scene, "updateGalleryVersionActionsVisibility");
        assertEquals(View.GONE, content.findViewById(R.id.update_action_card).getVisibility());
        DownloadInfo older = addKnownDownload(100, 100L, DownloadInfo.STATE_FINISH);
        ReflectionHelpers.callInstanceMethod(scene, "updateGalleryVersionActionsVisibility");
        assertEquals(View.VISIBLE, content.findViewById(R.id.update_gallery).getVisibility());
        assertEquals(View.VISIBLE, content.findViewById(R.id.update_action_card).getVisibility());
        assertTrue(content.findViewById(R.id.update_gallery).isEnabled());
        ReflectionHelpers.callInstanceMethod(manager, "removeFromAllDownloads",
                ReflectionHelpers.ClassParameter.from(DownloadInfo.class, older));
        ReflectionHelpers.callInstanceMethod(scene, "updateGalleryVersionActionsVisibility");
        assertEquals(View.GONE, content.findViewById(R.id.update_action_card).getVisibility());
        assertTrue(requests.isEmpty());
    }

    @Test public void pendingUpdateDefaultsHiddenWhenVersionMetadataIsUnknown() {
        addKnownDownload(100, 100L, DownloadInfo.STATE_FINISH);
        GalleryInfo summary = ReflectionHelpers.getField(scene, "mGalleryInfo");
        for (Long family : new Long[]{null, 0L, 123L}) {
            summary.firstGid = family;
            bindPendingHeader();
            ReflectionHelpers.callInstanceMethod(scene, "updateGalleryVersionActionsVisibility");
            assertEquals(View.GONE, content.findViewById(R.id.update_action_card).getVisibility());
        }
        assertTrue(requests.isEmpty());
    }

    @Test public void pendingUpdateDoesNotOfferAnExistingTargetOrActiveSource() {
        GalleryInfo summary = ReflectionHelpers.getField(scene, "mGalleryInfo");
        summary.firstGid = 100L;
        DownloadInfo older = addKnownDownload(100, 100L, DownloadInfo.STATE_WAIT);
        for (int state : new int[]{DownloadInfo.STATE_WAIT, DownloadInfo.STATE_DOWNLOAD, DownloadInfo.STATE_UPDATE}) {
            older.state = state;
            bindPendingHeader();
            assertEquals(View.GONE, content.findViewById(R.id.update_action_card).getVisibility());
        }
        older.state = DownloadInfo.STATE_FINISH;
        bindPendingHeader();
        assertEquals(View.VISIBLE, content.findViewById(R.id.update_action_card).getVisibility());
        addKnownDownload(123, 100L, DownloadInfo.STATE_FINISH);
        ReflectionHelpers.callInstanceMethod(scene, "updateGalleryVersionActionsVisibility");
        assertEquals(View.GONE, content.findViewById(R.id.update_action_card).getVisibility());
        assertTrue(requests.isEmpty());
    }

    private GalleryDetail updatableDetail() {
        GalleryDetail detail = online();
        detail.firstGid = 100L;
        detail.parent = "https://e-hentai.org/g/100/old-token/";
        return detail;
    }

    private void assertUpdateCardVisible() {
        assertEquals(View.VISIBLE, content.findViewById(R.id.update_action_card).getVisibility());
        assertEquals(View.VISIBLE, content.findViewById(R.id.update_gallery).getVisibility());
    }

    private void finishLocalLookups() {
        executor.runAll();
        ShadowLooper.idleMainLooper();
    }

    private void refreshUpdateRecord() {
        // Exercise the same invalidation/rebind used on return to the scene.
        ReflectionHelpers.callInstanceMethod(scene, "invalidateUpdateRecord");
        ReflectionHelpers.callInstanceMethod(scene, "updateGalleryVersionActionsVisibility");
    }

    @Test public void updateCardStaysVisibleAcrossHtmlAndRecordLookupAndRefresh() {
        GalleryInfo summary = ReflectionHelpers.getField(scene, "mGalleryInfo");
        summary.firstGid = 100L;
        addKnownDownload(100, 100L, DownloadInfo.STATE_FINISH);
        bindPendingHeader();
        assertUpdateCardVisible();
        GalleryDetail detail = updatableDetail();
        detail.firstGid = null; // The response must inherit the already known family.
        scene.onGetGalleryDetailSuccess(detail);
        assertEquals(Long.valueOf(100), detail.firstGid);
        assertTrue(ReflectionHelpers.<Boolean>getField(scene, "mUpdateRecordLookupPending"));
        assertUpdateCardVisible();
        finishLocalLookups();
        assertUpdateCardVisible();
        refreshUpdateRecord();
        assertTrue(ReflectionHelpers.<Boolean>getField(scene, "mUpdateRecordLookupPending"));
        assertUpdateCardVisible();
        finishLocalLookups();
        assertUpdateCardVisible();
        assertEquals(context.getString(R.string.update), text(R.id.update_gallery));
        assertTrue(requests.isEmpty());
    }

    @Test public void pendingRecordLookupStillHidesAnUpdateWhoseSourceWasRemoved() {
        DownloadInfo older = addKnownDownload(100, 100L, DownloadInfo.STATE_FINISH);
        scene.onGetGalleryDetailSuccess(updatableDetail());
        assertUpdateCardVisible();
        ReflectionHelpers.callInstanceMethod(manager, "removeFromAllDownloads",
                ReflectionHelpers.ClassParameter.from(DownloadInfo.class, older));
        scene.onRemove(older, List.of(), 0);
        assertEquals(View.GONE, content.findViewById(R.id.update_action_card).getVisibility());
        finishLocalLookups();
        assertEquals(View.GONE, content.findViewById(R.id.update_action_card).getVisibility());
    }

    @Test public void pendingRecordLookupRespectsCompletedTargetAndAuthoritativeMissingParent() {
        addKnownDownload(100, 100L, DownloadInfo.STATE_FINISH);
        scene.onGetGalleryDetailSuccess(updatableDetail());
        assertUpdateCardVisible();
        DownloadInfo target = addKnownDownload(123, 100L, DownloadInfo.STATE_FINISH);
        scene.onAdd(target, List.of(target), 0);
        assertEquals(View.GONE, content.findViewById(R.id.update_action_card).getVisibility());
        ReflectionHelpers.callInstanceMethod(manager, "removeFromAllDownloads",
                ReflectionHelpers.ClassParameter.from(DownloadInfo.class, target));
        GalleryDetail detail = updatableDetail();
        detail.parent = "";
        scene.onGetGalleryDetailSuccess(detail);
        assertEquals(View.GONE, content.findViewById(R.id.update_action_card).getVisibility());
        finishLocalLookups();
        assertEquals(View.GONE, content.findViewById(R.id.update_action_card).getVisibility());
    }

    @Test public void unknownFamilyStaysHiddenAndWaitsForRecordBeforeParentLookup() {
        GalleryDetail detail = updatableDetail();
        detail.firstGid = null;
        detail.parent = "https://e-hentai.org/g/50/unknown/";
        scene.onGetGalleryDetailSuccess(detail);
        assertTrue(ReflectionHelpers.<Boolean>getField(scene, "mUpdateRecordLookupPending"));
        assertEquals(View.GONE, content.findViewById(R.id.update_action_card).getVisibility());
        assertNull(ReflectionHelpers.getField(scene, "mGalleryVersionLookup"));
        assertTrue(requests.isEmpty());
    }

    @Test public void updateRecordReplacesButtonInPlaceAndRemainsVisibleDuringRefresh() {
        GalleryUpdateRecord failure = GalleryUpdateRecord.failure(123, 100, 1234,
                "Old title", "Download failed", new long[]{100}).withTargetToken("token");
        assertTrue(recordStore.saveFailure(failure));
        addKnownDownload(100, 100L, DownloadInfo.STATE_FINISH);
        scene.onGetGalleryDetailSuccess(updatableDetail());
        assertUpdateCardVisible();
        assertEquals(context.getString(R.string.update), text(R.id.update_gallery));
        finishLocalLookups();
        assertUpdateCardVisible();
        assertEquals(context.getString(R.string.download_state_failed), text(R.id.update_gallery));
        assertEquals(context.getString(R.string.gallery_update_error_log), text(R.id.gallery_history));
        refreshUpdateRecord();
        assertTrue(ReflectionHelpers.<Boolean>getField(scene, "mUpdateRecordLookupPending"));
        assertUpdateCardVisible();
        assertEquals(context.getString(R.string.download_state_failed), text(R.id.update_gallery));
        assertEquals(context.getString(R.string.gallery_update_error_log), text(R.id.gallery_history));
        finishLocalLookups();
        assertUpdateCardVisible();
        GalleryDetail replacement = online();
        replacement.token = "replacement-token";
        scene.onGetGalleryDetailSuccess(replacement);
        assertNull(ReflectionHelpers.getField(scene, "mGalleryUpdateRecord"));
        assertEquals(View.GONE, content.findViewById(R.id.update_action_card).getVisibility());
    }

    private void saveCompletedUpdateRecord() {
        SpiderInfo source = new SpiderInfo();
        source.gid = 100;
        source.token = "old-token";
        source.pages = 1;
        source.pTokenMap = new android.util.SparseArray<>();
        source.pTokenMap.put(0, "shared-page");
        SpiderInfo target = new SpiderInfo();
        target.gid = 123;
        target.token = "token";
        target.pages = 2;
        target.pTokenMap = new android.util.SparseArray<>();
        target.pTokenMap.put(0, "shared-page");
        target.pTokenMap.put(1, "new-page");
        GalleryUpdateRecord record = ReflectionHelpers.callStaticMethod(GalleryUpdateRecord.class, "compare",
                ReflectionHelpers.ClassParameter.from(long.class, 123L),
                ReflectionHelpers.ClassParameter.from(long.class, 100L),
                ReflectionHelpers.ClassParameter.from(SpiderInfo.class, source),
                ReflectionHelpers.ClassParameter.from(SpiderInfo.class, target));
        GalleryUpdateManager.UpdatePlan plan = ReflectionHelpers.callConstructor(GalleryUpdateManager.UpdatePlan.class,
                ReflectionHelpers.ClassParameter.from(long.class, 123L),
                ReflectionHelpers.ClassParameter.from(long.class, 100L),
                ReflectionHelpers.ClassParameter.from(List.class, List.of(100L)));
        assertTrue(recordStore.stage(plan, () -> record.withFirstGid(100)));
        assertTrue(recordStore.complete(123, 100));
    }

    @Test public void oldGalleryProgressDoesNotResetWhenDetailOrResumeRebindsIt() throws Exception {
        cacheProgress(100, 0, 145);
        DownloadInfo older = addKnownDownload(100, 100L, DownloadInfo.STATE_FINISH);
        older.pages = older.total = 0;
        GalleryInfo summary = ReflectionHelpers.getField(scene, "mGalleryInfo");
        summary.firstGid = 100L;
        bindPendingHeader();
        assertFalse(text(R.id.gallery_history).contains("0/0"));
        finishLocalLookups();
        String expected = context.getString(R.string.gallery_old_version_progress, 0, 145);
        assertEquals(expected, text(R.id.gallery_history));
        scene.onGetGalleryDetailSuccess(updatableDetail());
        assertEquals(expected, text(R.id.gallery_history));
        finishLocalLookups();
        ReflectionHelpers.setField(scene, "mVersionProgressTargetGid", -1L);
        ReflectionHelpers.setField(scene, "mVersionProgressSourceGid", -1L);
        refreshUpdateRecord();
        assertEquals(expected, text(R.id.gallery_history));
        finishLocalLookups();
        assertEquals(expected, text(R.id.gallery_history));
    }

    @Test public void completedUpdateActionsAndReaderAreAvailableBeforeHtmlOrRecordArrays() {
        saveCompletedUpdateRecord();
        addKnownDownload(123, 100L, DownloadInfo.STATE_FINISH);
        bindPendingHeader();
        assertUpdateCardVisible();
        assertEquals(context.getString(R.string.gallery_read_updates), text(R.id.update_gallery));
        assertTrue(content.findViewById(R.id.update_gallery).isEnabled());
        assertNull(ReflectionHelpers.getField(scene, "mGalleryDetail"));
        assertNull(ReflectionHelpers.getField(scene, "mGalleryUpdateRecord"));
        scene.onClick(content.findViewById(R.id.update_gallery));
        assertNotNull(scene.startedActivity);
        assertEquals(123L, scene.startedActivity.getLongExtra(GalleryActivity.KEY_UPDATE_RECORD_GID, -1));
        assertTrue(scene.startedActivity.getLongExtra(GalleryActivity.KEY_UPDATE_RECORD_TIME, 0) > 0);
        assertTrue(requests.isEmpty());
        scene.onGetGalleryDetailSuccess(updatableDetail());
        assertUpdateCardVisible();
        finishLocalLookups();
        assertUpdateCardVisible();
    }

    @Test public void coldUpdateRecordLoadsDuringPlaceholderInsteadOfWaitingForHtml() {
        saveCompletedUpdateRecord();
        addKnownDownload(123, 100L, DownloadInfo.STATE_FINISH);
        recordStore.close();
        ReflectionHelpers.setStaticField(GalleryUpdateRecordStore.class, "instance", null);
        recordStore = GalleryUpdateRecordStore.get(context);
        assertNull(recordStore.peekActionSnapshot(123));
        bindPendingHeader();
        finishLocalLookups();
        assertNull(ReflectionHelpers.getField(scene, "mGalleryDetail"));
        assertUpdateCardVisible();
        assertEquals(context.getString(R.string.gallery_read_updates), text(R.id.update_gallery));
        assertTrue(requests.isEmpty());
    }

    @Test public void prefetchedCompletedUpdateAvoidsHiddenInitialFrameAfterReopeningStore() {
        saveCompletedUpdateRecord();
        addKnownDownload(123, 100L, DownloadInfo.STATE_FINISH);
        recordStore.close();
        ReflectionHelpers.setStaticField(GalleryUpdateRecordStore.class, "instance", null);
        recordStore = GalleryUpdateRecordStore.get(context);
        recordStore.prefetchActionSnapshot(123, executor);
        finishLocalLookups();
        bindPendingHeader();
        assertUpdateCardVisible();
        assertEquals(context.getString(R.string.gallery_read_updates), text(R.id.update_gallery));
        assertNull(ReflectionHelpers.getField(scene, "mGalleryUpdateRecord"));
    }

    @Test public void readUpdatesStaysVisibleDuringRefreshIncludingTemporaryReadFailure() {
        saveCompletedUpdateRecord();
        addKnownDownload(123, 100L, DownloadInfo.STATE_FINISH);
        scene.onGetGalleryDetailSuccess(updatableDetail());
        finishLocalLookups();
        assertUpdateCardVisible();
        assertEquals(context.getString(R.string.gallery_read_updates), text(R.id.update_gallery));
        assertTrue(content.findViewById(R.id.update_gallery).isEnabled());
        refreshUpdateRecord();
        assertUpdateCardVisible();
        assertEquals(context.getString(R.string.gallery_read_updates), text(R.id.update_gallery));
        finishLocalLookups();
        assertUpdateCardVisible();
        recordStore.getWritableDatabase().execSQL("ALTER TABLE records RENAME TO unavailable_records");
        refreshUpdateRecord();
        finishLocalLookups();
        assertUpdateCardVisible();
        assertEquals(context.getString(R.string.gallery_read_updates), text(R.id.update_gallery));
        assertTrue(content.findViewById(R.id.update_gallery).isEnabled());
        recordStore.getWritableDatabase().execSQL("ALTER TABLE unavailable_records RENAME TO records");
    }

    @Test public void tokenChangeRejectsQueuedRecordCallback() {
        assertTrue(recordStore.saveFailure(GalleryUpdateRecord.failure(123, 100, 1234,
                "Old title", "Old failure", new long[]{100}).withTargetToken("token")));
        scene.onGetGalleryDetailSuccess(updatableDetail());
        executor.runAll(); // Queue the old callback, but do not deliver it yet.
        GalleryDetail replacement = online();
        replacement.token = "replacement-token";
        scene.onGetGalleryDetailSuccess(replacement);
        ShadowLooper.idleMainLooper();
        assertNull(ReflectionHelpers.getField(scene, "mGalleryUpdateRecord"));
        assertTrue(ReflectionHelpers.<Boolean>getField(scene, "mUpdateRecordLookupPending"));
        assertEquals(View.GONE, content.findViewById(R.id.update_action_card).getVisibility());
    }

    @Test public void completeHtmlProvidedAsArgumentsIsReusedWithoutAnotherDetailRequest() {
        GalleryDetail detail = online();
        ReflectionHelpers.setField(scene, "mGalleryInfo", detail);
        assertTrue(ReflectionHelpers.<Boolean>callInstanceMethod(scene, "prepareData"));
        assertSame(detail, ReflectionHelpers.getField(scene, "mGalleryDetail"));
        assertTrue(requests.isEmpty());
    }

    private void cacheProgress(int page) throws Exception {
        cacheProgress(123, page, 12);
    }

    private void cacheProgress(long gid, int page, int pages) throws Exception {
        SpiderInfo info = new SpiderInfo();
        info.gid = gid;
        info.token = "token";
        info.pages = pages;
        info.startPage = page;
        info.pTokenMap = new android.util.SparseArray<>();
        OutputStreamPipe pipe = EhApplication.getSpiderInfoCache(context).getOutputStreamPipe(Long.toString(gid));
        try { pipe.obtain(); info.write(pipe.open()); }
        finally { pipe.close(); pipe.release(); }
    }

    @Test public void prefetchedProgressAndLanguageAreVisibleBeforeHtmlAndUseFinalPageCount() throws Exception {
        cacheProgress(4);
        // The list prefetch populates the same bounded snapshot used by details.
        assertEquals(4, SpiderQueen.findStartPage(context, 123));
        GalleryInfo info = ReflectionHelpers.getField(scene, "mGalleryInfo");
        info.simpleLanguage = "ZH";
        bindPendingHeader();
        assertEquals("Chinese", text(R.id.language));
        assertEquals("5/10P", text(R.id.pages));
        ReflectionHelpers.setField(scene, "mUpdateActionGroup", null);
        GalleryDetail detail = online();
        detail.language = "Chinese";
        scene.onGetGalleryDetailSuccess(detail);
        assertEquals("5/12P", text(R.id.pages));
        executor.runAll();
        ShadowLooper.idleMainLooper();
        assertEquals("Chinese", text(R.id.language));
        assertEquals("5/12P", text(R.id.pages));
    }

    @Test public void pendingProgressDoesNotReadDiskAndNormalDetailsStillCan() throws Exception {
        cacheProgress(6);
        bindPendingHeader();
        assertEquals("0/10P", text(R.id.pages));
        assertTrue(executor.queue.isEmpty());
        ReflectionHelpers.setField(scene, "mUpdateActionGroup", null);
        scene.onGetGalleryDetailSuccess(online());
        executor.runAll();
        ShadowLooper.idleMainLooper();
        assertEquals("7/12P", text(R.id.pages));
        assertTrue(requests.isEmpty());
        SpiderQueen.resetReadingProgress(123);
        assertEquals(Integer.valueOf(0), SpiderQueen.peekStartPage(123));
    }

    @Test public void delayedOnlineProgressCannotOverwriteOfflineFallback() {
        bindPendingHeader();
        GalleryDetail detail = online();
        ReflectionHelpers.setField(scene, "mGalleryDetail", detail);
        ReflectionHelpers.callInstanceMethod(scene, "bindReadProgress",
                ReflectionHelpers.ClassParameter.from(GalleryInfo.class, detail));
        Runnable delayed = executor.queue.remove(0);
        detail.pages = 20;
        fail("Offline");
        assertEquals("0/20P", text(R.id.pages));
        delayed.run();
        ShadowLooper.idleMainLooper();
        assertEquals("0/20P", text(R.id.pages));
    }

    @Test public void pendingRenderingNeedsNeitherDatabaseNorBackgroundMetadataLookup() {
        GalleryInfo info = ReflectionHelpers.getField(scene, "mGalleryInfo");
        info.favoriteSlot = -1;
        Object session = ReflectionHelpers.getStaticField(EhDB.class, "sDaoSession");
        ReflectionHelpers.setStaticField(EhDB.class, "sDaoSession", null);
        try {
            bindPendingHeader();
            assertEquals(context.getString(R.string.local_favorites), text(R.id.heart));
            assertTrue(executor.queue.isEmpty());
            assertTrue(requests.isEmpty());
        } finally { ReflectionHelpers.setStaticField(EhDB.class, "sDaoSession", session); }
    }

    @Test public void alreadyDisplayedProgressSurvivesSharedCacheEvictionWithoutAnotherLookup() throws Exception {
        cacheProgress(4);
        SpiderQueen.findStartPage(context, 123);
        bindPendingHeader();
        ((android.util.LruCache<?, ?>) ReflectionHelpers.getStaticField(SpiderQueen.class, "sStartPages")).evictAll();
        ReflectionHelpers.setField(scene, "mUpdateActionGroup", null);
        scene.onGetGalleryDetailSuccess(online());
        assertEquals("5/12P", text(R.id.pages));
        assertTrue(executor.queue.isEmpty());
    }

    @Test public void downloadEntryReusesItsExistingProgressAndPageCountWithoutLoadingSpiderInfo() {
        GalleryInfo info = ReflectionHelpers.getField(scene, "mGalleryInfo");
        info.pages = 0;
        Bundle args = new Bundle();
        args.putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_GALLERY_INFO);
        args.putParcelable(GalleryDetailScene.KEY_GALLERY_INFO, info);
        args.putInt(GalleryDetailScene.KEY_KNOWN_START_PAGE, 4);
        args.putInt(GalleryDetailScene.KEY_KNOWN_PAGE_COUNT, 20);
        ReflectionHelpers.callInstanceMethod(scene, "handleArgs",
                ReflectionHelpers.ClassParameter.from(Bundle.class, args));
        assertEquals(1, executor.queue.size()); // The existing history write, now off the UI thread.
        bindPendingHeader();
        assertEquals("5/20P", text(R.id.pages));
        assertEquals(1, executor.queue.size()); // No reading-progress lookup added.
        ReflectionHelpers.setField(scene, "mUpdateActionGroup", null);
        scene.onGetGalleryDetailSuccess(online());
        assertEquals("5/12P", text(R.id.pages));
        assertEquals(1, executor.queue.size());
    }

    @Test public void sameCoverDoesNotRestartWhenDetailsArriveAndChangedCoverUsesAnotherKey() {
        bindPendingHeader();
        assertEquals(1, imageNetworkLoads.size());
        ReflectionHelpers.setField(scene, "mUpdateActionGroup", null);
        scene.onGetGalleryDetailSuccess(online());
        assertEquals(1, imageNetworkLoads.size());
        GalleryDetail changed = online();
        changed.thumb = "https://example.invalid/new-cover.jpg";
        scene.onGetGalleryDetailSuccess(changed);
        assertEquals(2, imageNetworkLoads.size());
        assertTrue(imageNetworkLoads.get(1));
    }

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void entryCoverIsAdoptedBeforePendingRenderingAndNeedsNoLoadOnSuccess() {
        LoadImageView source = new LoadImageView(context);
        GalleryInfo info = ReflectionHelpers.getField(scene, "mGalleryInfo");
        ReflectionHelpers.setField(source, "mKey", com.hippo.ehviewer.client.EhCacheKeyFactory.getThumbKey(info.gid));
        ReflectionHelpers.setField(source, "mUrl", info.thumb);
        Image image = Image.create(android.graphics.Bitmap.createBitmap(100, 150,
                android.graphics.Bitmap.Config.ARGB_8888));
        source.onGetValue(image, Conaco.SOURCE_MEMORY);
        scene.setEntryCover(source.snapshot());
        ReflectionHelpers.callInstanceMethod(source, "clearDrawable");
        bindPendingHeader();
        LoadImageView cover = content.findViewById(R.id.thumb);
        assertTrue(cover.hasImage());
        assertTrue(imageNetworkLoads.isEmpty());
        ReflectionHelpers.setField(scene, "mUpdateActionGroup", null);
        scene.onGetGalleryDetailSuccess(online());
        assertTrue(imageNetworkLoads.isEmpty());
        ReflectionHelpers.callInstanceMethod(cover, "clearDrawable");
        assertTrue(image.isRecycled());
    }

    private GalleryTagGroup group(String name, String... tags) {
        GalleryTagGroup group = new GalleryTagGroup();
        group.groupName = name;
        for (String tag : tags) group.addTag(tag);
        return group;
    }

    private void renderTags(GalleryTagGroup... groups) {
        ReflectionHelpers.callInstanceMethod(scene, "bindTags",
                ReflectionHelpers.ClassParameter.from(GalleryTagGroup[].class, groups));
    }

    @Test public void tagsReuseRowsAndLabelsAcrossInsertDeleteAndReorder() {
        renderTags(group("artist", "a", "b"), group("language", "chinese"));
        ViewGroup tags = content.findViewById(R.id.tags);
        ViewGroup artist = (ViewGroup) tags.getChildAt(1);
        View language = tags.getChildAt(2);
        ViewGroup labels = (ViewGroup) artist.getChildAt(1);
        View a = labels.getChildAt(0);
        View b = labels.getChildAt(1);
        renderTags(group("artist", "a", "b"), group("language", "chinese"));
        assertSame(artist, tags.getChildAt(1));
        assertSame(a, labels.getChildAt(0));
        renderTags(group("language", "chinese"), group("artist", "b", "c", "a"));
        assertSame(language, tags.getChildAt(1));
        assertSame(artist, tags.getChildAt(2));
        assertSame(b, labels.getChildAt(0));
        assertSame(a, labels.getChildAt(2));
        renderTags(group("artist", "a"));
        assertNull(language.getParent());
        assertNull(b.getParent());
        assertSame(a, labels.getChildAt(0));
        assertEquals(1, labels.getChildCount());
        renderTags();
        assertEquals(1, tags.getChildCount());
        assertEquals(View.VISIBLE, content.findViewById(R.id.no_tags).getVisibility());
    }

    @Test public void identicalFullTagsKeepTheAlreadyRenderedTagViews() {
        GalleryInfo info = ReflectionHelpers.getField(scene, "mGalleryInfo");
        info.simpleTags = new String[]{"artist:a", "language:chinese"};
        bindPendingHeader();
        ViewGroup tags = content.findViewById(R.id.tags);
        View artist = tags.getChildAt(1);
        GalleryDetail detail = online();
        detail.tags = new GalleryTagGroup[]{group("artist", "a"), group("language", "chinese")};
        ReflectionHelpers.setField(scene, "mUpdateActionGroup", null);
        scene.onGetGalleryDetailSuccess(detail);
        assertSame(artist, tags.getChildAt(1));
    }

    private List<String> tagKeys(View view) {
        List<String> result = new ArrayList<>();
        if (view.getTag(R.id.tag) instanceof String tag) result.add(tag);
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) result.addAll(tagKeys(group.getChildAt(i)));
        }
        return result;
    }

    @Test public void knownTagsShowAboveSpinnerThenHtmlReplacesThem() {
        GalleryInfo info = ReflectionHelpers.getField(scene, "mGalleryInfo");
        info.simpleTags = new String[]{"artist:known", "language:chinese"};
        info.tgList = new ArrayList<>(List.of("artist:known", ":full color"));
        bindPendingHeader();
        View tags = content.findViewById(R.id.tags);
        assertSame(content, tags.getParent());
        assertEquals(View.VISIBLE, tags.getVisibility());
        assertEquals(List.of("artist:known", "language:chinese", "misc:full color"), tagKeys(tags));
        assertEquals(View.VISIBLE, sceneView.findViewById(R.id.progress).getVisibility());
        assertEquals("Chinese", text(R.id.language));
        GalleryDetail detail = online();
        GalleryTagGroup group = new GalleryTagGroup();
        group.groupName = "artist";
        group.addTag("complete");
        detail.tags = new GalleryTagGroup[]{group};
        ReflectionHelpers.setField(scene, "mUpdateActionGroup", null);
        scene.onGetGalleryDetailSuccess(detail);
        assertEquals(List.of("artist:complete"), tagKeys(tags));
    }

    @Test public void htmlListTagsAndLanguageWorkWithoutApiMetadata() {
        GalleryInfo info = ReflectionHelpers.getField(scene, "mGalleryInfo");
        info.tgList = new ArrayList<>(List.of("artist:list artist", "language:korean"));
        bindPendingHeader();
        assertEquals("Korean", text(R.id.language));
        assertEquals(List.of("artist:list artist", "language:korean"), tagKeys(content.findViewById(R.id.tags)));
    }

    @Test public void titleSearchActionsWorkWithoutHtmlOrNetwork() {
        GalleryInfo info = ReflectionHelpers.getField(scene, "mGalleryInfo");
        info.title = "[Author] Example title [Chinese]";
        bindPendingHeader();
        scene.onClick(content.findViewById(R.id.artist));
        assertNotNull(scene.opened);
        assertEquals(GalleryListScene.class, scene.opened.getClazz());
        ListUrlBuilder artist = scene.opened.getArgs().getParcelable(GalleryListScene.KEY_LIST_URL_BUILDER);
        assertTrue(artist.getKeyword().contains("Author"));
        scene.opened = null;
        scene.onClick(content.findViewById(R.id.similar));
        assertNotNull(scene.opened);
        ListUrlBuilder similar = scene.opened.getArgs().getParcelable(GalleryListScene.KEY_LIST_URL_BUILDER);
        assertTrue(similar.getKeyword().contains("Example title"));
        assertTrue(requests.isEmpty());
    }

    @Test public void ratingDialogOpensImmediatelyAndSubmissionReusesPendingHtmlRequest() {
        bindPendingHeader();
        ReflectionHelpers.callInstanceMethod(scene, "refreshDetail");
        scene.onClick(content.findViewById(R.id.rate));
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(dialog.isShowing());
        assertNotNull(dialog.findViewById(R.id.rating_view));
        ((com.hippo.ehviewer.widget.GalleryRatingBar) dialog.findViewById(R.id.rating_view)).setRating(4f);
        assertEquals(1, requests.size());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(1, requests.size());
        assertEquals(1, ((java.util.Map<?, ?>) ReflectionHelpers.getField(scene, "mPendingDetailActions")).size());
        GalleryDetail detail = online();
        detail.apiUid = 99;
        detail.apiKey = "key";
        ReflectionHelpers.setField(scene, "mUpdateActionGroup", null);
        scene.onGetGalleryDetailSuccess(detail);
        assertEquals(2, requests.size());
        assertEquals(EhClient.METHOD_GET_RATE_GALLERY, requests.get(1).getMethod());
        assertEquals(99L, requests.get(1).getArgs()[0]);
        assertEquals("key", requests.get(1).getArgs()[1]);
        assertEquals(4f, requests.get(1).getArgs()[4]);
        assertTrue(((java.util.Map<?, ?>) ReflectionHelpers.getField(scene, "mPendingDetailActions")).isEmpty());
    }

    @Test public void torrentClickLoadsOnlyTorrentEndpointBeforeHtml() {
        bindPendingHeader();
        TorrentDownloadController controller = new TorrentDownloadController(new TorrentDownloadController.Host() {
            @Override public Context getEHContext() { return context; }
            @Override public boolean isHostActive() { return true; }
            @Override public void retryTorrentDownload() {}
        }, 123, "token");
        ReflectionHelpers.setField(scene, "torrentDownloadController", controller);
        scene.onClick(content.findViewById(R.id.torrent));
        assertTrue(ShadowDialog.getLatestDialog().isShowing());
        assertEquals(1, requests.size());
        EhRequest request = requests.get(0);
        assertEquals(EhClient.METHOD_GET_TORRENT_LIST, request.getMethod());
        assertTrue(request.getArgs()[0].toString().endsWith("gallerytorrents.php?gid=123&t=token"));
        assertEquals(123L, request.getArgs()[1]);
        assertEquals("token", request.getArgs()[2]);
        android.app.Dialog dialog = ShadowDialog.getLatestDialog();
        scene.onClick(content.findViewById(R.id.torrent));
        assertSame(dialog, ShadowDialog.getLatestDialog());
        assertEquals(1, requests.size());
        controller.release();
        ShadowLooper.idleMainLooper();
        assertFalse(dialog.isShowing());
        assertTrue(request.isCancelled());
    }

    @Test public void htmlFailureDropsPendingActionsAndDoesNotSubmitRating() {
        bindPendingHeader();
        ReflectionHelpers.callInstanceMethod(scene, "submitRating", ReflectionHelpers.ClassParameter.from(float.class, 4f));
        assertEquals(1, requests.size());
        fail("Offline");
        assertTrue(((java.util.Map<?, ?>) ReflectionHelpers.getField(scene, "mPendingDetailActions")).isEmpty());
        ReflectionHelpers.setField(scene, "mUpdateActionGroup", null);
        scene.onGetGalleryDetailSuccess(online());
        assertEquals(1, requests.size());
    }

    @Test public void completedEarlyFavoriteChangeSurvivesOlderHtmlResponse() {
        GalleryInfo info = ReflectionHelpers.getField(scene, "mGalleryInfo");
        info.favoriteSlot = 2;
        info.favoriteName = "My favorites";
        ReflectionHelpers.callInstanceMethod(scene, "onModifyFavoritesSuccess",
                ReflectionHelpers.ClassParameter.from(GalleryInfo.class, info));
        ReflectionHelpers.setField(scene, "mUpdateActionGroup", null);
        GalleryDetail detail = online();
        scene.onGetGalleryDetailSuccess(detail);
        assertTrue(detail.isFavorited);
        assertEquals(2, detail.favoriteSlot);
        assertEquals("My favorites", text(R.id.heart));
        assertEquals(View.VISIBLE, content.findViewById(R.id.heart).getVisibility());
    }

    @Test public void detailSuccessFillsRatingCountAndKeepsActionsEnabled() {
        bindPendingHeader();
        assertTrue(content.findViewById(R.id.info).isEnabled());
        ReflectionHelpers.setField(scene, "mUpdateActionGroup", null);
        GalleryDetail detail = online();
        detail.rating = 4.5f;
        detail.ratingCount = 123;
        scene.onGetGalleryDetailSuccess(detail);
        assertEquals("Online title", text(R.id.title));
        assertTrue(text(R.id.rating_text).contains("123"));
        assertTrue(content.findViewById(R.id.info).isEnabled());
        assertTrue(content.findViewById(R.id.rate).isEnabled());
        assertEquals(View.VISIBLE, content.findViewById(R.id.detail_body).getVisibility());
        assertEquals(View.GONE, sceneView.findViewById(R.id.progress).getVisibility());
    }

    @Test public void refreshingKeepsKnownSummaryVisible() {
        bindPendingHeader();
        ReflectionHelpers.callInstanceMethod(scene, "refreshDetail");
        assertEquals(1, requests.size());
        assertEquals(View.VISIBLE, content.getVisibility());
        assertEquals(View.VISIBLE, content.findViewById(R.id.header).getVisibility());
        assertEquals("Saved title", text(R.id.title));
    }

    @Test public void offlineFallbackRestoresInformationActionAfterPartialHeader() {
        bindPendingHeader();
        assertTrue(content.findViewById(R.id.info).isEnabled());
        fail("Offline");
        assertTrue(content.findViewById(R.id.info).isEnabled());
        assertEquals(View.VISIBLE, content.findViewById(R.id.detail_body).getVisibility());
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
        Context themeContext;
        final MainActivity activity = Shadow.newInstanceOf(MainActivity.class);
        Announcer opened;
        Intent startedActivity;
        TestScene(Context context) {
            this.context = context;
            this.themeContext = context;
            ReflectionHelpers.setField(activity, "mBase", context);
        }
        @Override public Context getContext() { return context; }
        @Override public Context getEHContext() { return themeContext; }
        @Override public MainActivity getActivity2() { return activity; }
        @Override public void startScene(Announcer announcer) { opened = announcer; }
        @Override public void startActivity(Intent intent) { startedActivity = intent; }
    }

    @Implements(EhApplication.class)
    public static class AppShadow extends org.robolectric.shadows.ShadowApplication {
        @Implementation protected static Conaco<Image> getConaco(Context context) { return null; }
        @Implementation protected static DownloadManager getDownloadManager(Context context) { return manager; }
        @Implementation protected static EhClient getEhClient(Context context) { return client; }
        @Implementation protected static ExecutorService getExecutorService(Context context) { return executor; }
        @Implementation protected static okhttp3.OkHttpClient getOkHttpClient(Context context) { return new okhttp3.OkHttpClient(); }
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
