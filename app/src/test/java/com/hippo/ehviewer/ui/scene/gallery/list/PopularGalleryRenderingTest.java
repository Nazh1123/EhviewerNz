package com.hippo.ehviewer.ui.scene.gallery.list;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Rect;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.hippo.conaco.Conaco;
import com.hippo.easyrecyclerview.EasyRecyclerView;
import com.hippo.drawable.GalleryCountDrawable;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.dao.QuickSearch;
import com.hippo.ehviewer.FavouriteStatusRouter;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.client.EhClient;
import com.hippo.ehviewer.client.GallerySeenState;
import com.hippo.ehviewer.client.SubscriptionUpdateManager;
import com.hippo.ehviewer.client.parser.GalleryListParser;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.lib.image.Image;
import com.hippo.lib.yorozuya.LayoutUtils;
import com.hippo.refreshlayout.RefreshLayout;
import com.hippo.widget.ContentLayout;
import com.hippo.widget.FabLayout;
import com.hippo.widget.LoadImageViewNew;
import com.hippo.widget.SearchBarMover;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, shadows = {
        PopularGalleryRenderingTest.AppShadow.class, PopularGalleryRenderingTest.DownloadShadow.class,
        PopularGalleryRenderingTest.ImageShadow.class, PopularGalleryRenderingTest.DbShadow.class})
public class PopularGalleryRenderingTest {
    private static final List<QuickSearch> bookmarks = new ArrayList<>();
    private static FavouriteStatusRouter router;
    private static DownloadManager downloads;
    private static final Set<Long> downloadedGids = new HashSet<>();
    private TestScene scene;
    private ContentLayout content;
    private GalleryListScene.GalleryListHelper helper;
    private GalleryAdapterNew adapter;
    private ListUrlBuilder builder;
    private RecyclerView recycler;
    private int revealScrollCount;

    @Before public void setUp() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre",
                app.getSharedPreferences("popular-render-test", Context.MODE_PRIVATE));
        bookmarks.clear();
        router = new FavouriteStatusRouter();
        downloads = Shadow.newInstanceOf(DownloadManager.class);
        downloadedGids.clear();
        Context context = new ContextThemeWrapper(app, R.style.AppTheme);
        scene = new TestScene(context);
        ReflectionHelpers.setField(scene, "mDownloadManager", downloads);
        content = new ContentLayout(context);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setContentView(content);
        recycler = content.getRecyclerView();
        builder = new ListUrlBuilder();
        builder.setMode(ListUrlBuilder.MODE_WHATS_HOT);
        ReflectionHelpers.setField(scene, "mUrlBuilder", builder);
        ReflectionHelpers.setField(scene, "mRecyclerView", recycler);
        ReflectionHelpers.setField(scene, "executorService", Executors.newSingleThreadExecutor());
        revealScrollCount = 0;
        helper = scene.new GalleryListHelper() {
            @Override protected void scrollToRevealNewContent() {
                revealScrollCount++;
                super.scrollToRevealNewContent();
            }
        };
        ReflectionHelpers.setField(scene, "mHelper", helper);
        content.setHelper(helper);
        ReflectionHelpers.setField(scene, "mSearchBarMover", new SearchBarMover(scene, new View(context), recycler));
        PopularGalleryHistory history = new PopularGalleryHistory(context.getCacheDir(), Settings.getGallerySite());
        history.record(galleries(1, 2));
        ReflectionHelpers.setField(scene, "mPopularHistory", history);
        ReflectionHelpers.setField(scene, "mPopularHistorySite", Settings.getGallerySite());
        Class<?> adapterClass = Class.forName(GalleryListScene.class.getName() + "$GalleryListAdapter");
        adapter = (GalleryAdapterNew) ReflectionHelpers.callConstructor(adapterClass,
                ClassParameter.from(GalleryListScene.class, scene),
                ClassParameter.from(LayoutInflater.class, LayoutInflater.from(context)),
                ClassParameter.from(android.content.res.Resources.class, context.getResources()),
                ClassParameter.from(RecyclerView.class, recycler),
                ClassParameter.from(int.class, GalleryAdapterNew.TYPE_LIST));
        ReflectionHelpers.setField(scene, "mAdapter", adapter);
        refresh(1, 2);
        assertRendered(2);
    }

    @Test @Config(sdk = 34)
    public void leftCoverAndInfoBarPreviewFollowQuickDownloadSwitchAndRestoreLongPress() {
        Settings.putString(Settings.KEY_LIST_MODE, "1");
        Settings.putString(Settings.KEY_THUMB_SIZE, "0");
        Settings.putBoolean(Settings.KEY_SHOW_THUMBNAIL_INFO_BAR, true);
        Settings.putBoolean(Settings.KEY_SHOW_THUMBNAIL_INFO_DETAILS, true);
        assertTrue(Settings.isThumbnailInfoBarEffective());
        adapter.setType(GalleryAdapterNew.TYPE_GRID);
        layout();
        EasyRecyclerView parent = (EasyRecyclerView) recycler;
        View item = recycler.findViewHolderForAdapterPosition(0).itemView;
        GalleryInfo gallery = adapter.getDataAt(0);
        for (int targetId : new int[]{R.id.thumb_new, R.id.thumbnail_info_bar}) {
            View target = item.findViewById(targetId);
            assertEquals(View.VISIBLE, target.getVisibility());
            assertTrue(target.getWidth() > 0 && target.getHeight() > 0);
            Rect bounds = new Rect();
            target.getDrawingRect(bounds);
            parent.offsetDescendantRectToMyCoords(target, bounds);
            ReflectionHelpers.setField(parent, "mStartX", bounds.left + bounds.width() * 0.25f);
            ReflectionHelpers.setField(parent, "mStartY", bounds.top + bounds.height() * 0.9f);
            for (boolean enabled : new boolean[]{false, true, false}) {
                Settings.putBoolean(Settings.KEY_GALLERY_LONG_PRESS_QUICK_DOWNLOAD, enabled);
                int before = scene.originalLongPresses;
                assertTrue(scene.onItemLongClick(parent, item, 0, gallery.gid));
                assertEquals(before + (enabled ? 0 : 1), scene.originalLongPresses);
                if (!enabled) assertSame(gallery, scene.lastLongPressedGallery);
            }
            Settings.putBoolean(Settings.KEY_GALLERY_LONG_PRESS_QUICK_DOWNLOAD, true);
            ReflectionHelpers.setField(scene, "mMultiSelectMode", true);
            int before = scene.originalLongPresses;
            assertTrue(scene.onItemLongClick(parent, item, 0, gallery.gid));
            assertEquals(before + 1, scene.originalLongPresses);
            ReflectionHelpers.setField(scene, "mMultiSelectMode", false);
        }
    }

    @Test public void noUpdatesActionsShowTipAndCurrentRefreshAndNewSearchAllRender() {
        toggle(PopularGalleryHistory.UPDATES);
        assertEquals(R.string.popular_page_no_updates, scene.lastTip);
        assertEquals(PopularGalleryHistory.CURRENT, (int) ReflectionHelpers.<Integer>getField(scene, "mPopularViewMode"));
        assertRendered(2);
        scene.lastTip = 0;
        toggle(PopularGalleryHistory.PREVIOUS);
        assertEquals(R.string.popular_page_no_updates, scene.lastTip);
        assertEquals(PopularGalleryHistory.CURRENT, (int) ReflectionHelpers.<Integer>getField(scene, "mPopularViewMode"));
        assertRendered(2);
        refresh(3, 4, 5);
        assertRendered(3);
        builder.setMode(ListUrlBuilder.MODE_NORMAL);
        builder.setKeyword("new search");
        refresh(6, 7);
        assertRendered(2);
    }

    @Test public void nonEmptyUpdatesAndPreviousCanBothReturnToCurrent() {
        refresh(2, 3, 4);
        toggle(PopularGalleryHistory.UPDATES);
        assertRendered(2);
        toggle(PopularGalleryHistory.UPDATES);
        assertRendered(3);
        toggle(PopularGalleryHistory.PREVIOUS);
        assertRendered(2);
        toggle(PopularGalleryHistory.PREVIOUS);
        assertRendered(3);
    }

    @Test public void exactRatingFiltersProjectionWithoutRemovingSnapshotsOrBreakingDividers() {
        refresh(2, 3, 4);
        PopularGalleryHistory history = ReflectionHelpers.getField(scene, "mPopularHistory");
        for (GalleryInfo gallery : history.galleries(PopularGalleryHistory.CURRENT)) {
            gallery.rating = gallery.gid == 3 ? 4.74f : 4.75f;
        }
        builder.setMinRatingHundredths(475);
        ReflectionHelpers.callInstanceMethod(scene, "updatePopularProjection");
        assertRendered(2);
        assertEquals(3, history.galleries(PopularGalleryHistory.CURRENT).size());
        toggle(PopularGalleryHistory.UPDATES);
        assertRendered(1);
        ReflectionHelpers.setField(scene, "mPopularRepeatedExpanded", true);
        ReflectionHelpers.callInstanceMethod(scene, "updatePopularProjection");
        assertEquals(3, adapter.getItemCount()); // one new, one divider, one repeated gallery
        assertEquals(1, (int) ReflectionHelpers.<Integer>getField(adapter, "mPopularNewCount"));
        builder.setMinRatingHundredths(0);
        ReflectionHelpers.callInstanceMethod(scene, "updatePopularProjection");
        assertEquals(3, history.galleries(PopularGalleryHistory.CURRENT).size());
        assertEquals(4, adapter.getItemCount());
    }

    @Test public void defaultRatingFiltersPopularSnapshotAndTemporaryZeroRestoresAllItems() {
        refresh(2, 3, 4);
        PopularGalleryHistory history = ReflectionHelpers.getField(scene, "mPopularHistory");
        for (GalleryInfo gallery : history.galleries(PopularGalleryHistory.CURRENT)) {
            gallery.rating = gallery.gid == 3 ? 3.99f : 4f;
        }
        Settings.putPersistentMinRatingHundredths(400);
        ReflectionHelpers.callInstanceMethod(scene, "updatePopularProjection");
        assertRendered(2);
        builder.setMinRatingHundredths(0);
        ReflectionHelpers.callInstanceMethod(scene, "updatePopularProjection");
        assertRendered(3);
        assertEquals(400, Settings.getPersistentMinRatingHundredths());
        assertEquals(3, history.galleries(PopularGalleryHistory.CURRENT).size());
    }

    @Test public void returningFromSettingsRefetchesChangedDefaultButKeepsBrowseOverride() {
        List<Integer> requestedRatings = new ArrayList<>();
        GalleryListScene.GalleryListHelper requestRecorder = scene.new GalleryListHelper() {
            @Override protected void getPageData(int taskId, int type, int page) {
                requestedRatings.add(builder.getEffectiveMinRatingHundredths());
            }
        };
        ReflectionHelpers.setField(scene, "mHelper", requestRecorder);
        content.setHelper(requestRecorder);
        ReflectionHelpers.setField(scene, "mLastRequestedRatingMinimum", 0);
        ReflectionHelpers.setField(scene, "mLastRequestedPreciseRating", true);
        Settings.putPersistentMinRatingHundredths(400);
        scene.onResume();
        assertEquals(java.util.List.of(400), requestedRatings);
        builder.setMinRatingHundredths(0);
        Settings.putPersistentMinRatingHundredths(350);
        scene.onResume();
        assertEquals(java.util.List.of(400), requestedRatings);
        Settings.putBoolean(Settings.KEY_ENABLE_PRECISE_RATING_FILTER, false);
        scene.onResume();
        assertEquals(java.util.List.of(400, 0), requestedRatings);
    }

    @Test public void countButtonsFollowVisibleUpdatesAndKeepTheCurrentSnapshotTotal() {
        View actions = bindPopularActions();
        refresh(2, 3, 4);
        toggle(PopularGalleryHistory.UPDATES);
        assertCountButtons(actions, 2, 3);
        ReflectionHelpers.callInstanceMethod(scene, "setDownloadedOnlyMode",
                ClassParameter.from(boolean.class, true), ClassParameter.from(boolean.class, false));
        assertCountButtons(actions, 0, 3);
        downloadedGids.add(3L);
        ReflectionHelpers.callInstanceMethod(scene, "onDownloadMembershipChanged");
        assertCountButtons(actions, 1, 3);
        ReflectionHelpers.callInstanceMethod(scene, "setDownloadedOnlyMode",
                ClassParameter.from(boolean.class, false), ClassParameter.from(boolean.class, false));
        assertCountButtons(actions, 2, 3);
        setDefaultUpdates(true);
        refresh(2, 3, 4, 5);
        assertCountButtons(actions, 1, 4);
        toggle(PopularGalleryHistory.UPDATES);
        for (int id : new int[]{R.id.popular_updates, R.id.popular_updates_menu}) {
            FloatingActionButton button = actions.findViewById(id);
            assertFalse(button.getDrawable() instanceof GalleryCountDrawable);
            assertEquals(scene.getContext().getString(R.string.popular_updates_only), button.getContentDescription());
        }
        assertRendered(4);
        ReflectionHelpers.setField(scene, "mPopularHistory",
                new PopularGalleryHistory(scene.getContext().getCacheDir(), Settings.getGallerySite()));
        refresh(7, 8);
        assertCountButtons(actions, 2, 2);
    }

    private void assertCountButtons(View actions, int filtered, int total) {
        assertEquals(filtered, (int) ReflectionHelpers.callInstanceMethod(adapter, "getGalleryCount"));
        for (int id : new int[]{R.id.popular_updates, R.id.popular_updates_menu}) {
            FloatingActionButton button = actions.findViewById(id);
            assertTrue(button.getDrawable() instanceof GalleryCountDrawable);
            assertEquals(Integer.toString(filtered), ReflectionHelpers.getField(button.getDrawable(), "filtered"));
            assertEquals(Integer.toString(total), ReflectionHelpers.getField(button.getDrawable(), "total"));
            assertEquals(scene.getContext().getString(R.string.popular_show_all_count, filtered, total),
                    button.getContentDescription());
        }
    }

    @Test public void rapidNoUpdateActionsDoNotLeaveContentHidden() {
        for (int i = 0; i < 4; i++) {
            toggle(PopularGalleryHistory.UPDATES);
            toggle(PopularGalleryHistory.UPDATES);
        }
        assertRendered(2);
        refresh(3, 4);
        assertRendered(2);
    }

    @Test public void restoredSpecialModeCanStillExitWhenCurrentHasNoUpdates() {
        for (int mode : new int[]{PopularGalleryHistory.UPDATES, PopularGalleryHistory.PREVIOUS}) {
            ReflectionHelpers.setField(scene, "mPopularViewMode", mode);
            scene.lastTip = 0;
            toggle(mode);
            assertEquals(0, scene.lastTip);
            assertEquals(PopularGalleryHistory.CURRENT, (int) ReflectionHelpers.<Integer>getField(scene, "mPopularViewMode"));
            assertRendered(2);
        }
    }

    @Test public void defaultUpdatesFilterCanExitAndDisablingSettingRestoresNormalResponses() {
        assertFalse(Settings.getPopularUpdatesOnly());
        setDefaultUpdates(true);
        refresh(2, 3, 4);
        assertRendered(2);
        assertEquals("Gallery 3", ((TextView) recycler.getChildAt(0).findViewById(R.id.title)).getText().toString());
        toggle(PopularGalleryHistory.UPDATES);
        assertRendered(3);
        setDefaultUpdates(false);
        refresh(2, 3, 4);
        assertRendered(3);
    }

    @Test public void defaultUpdatesWithNoNewGalleriesShowsCurrentListAndSignalsWithButton() {
        View actions = bindPopularActions();
        ReflectionHelpers.setField(scene, "mShowActionFab", false);
        setDefaultUpdates(true);
        refresh(1, 2);
        assertNotNull(ReflectionHelpers.getField(actions.findViewById(R.id.popular_actions), "mNoticeAnimator"));
        assertEquals(View.VISIBLE, actions.findViewById(R.id.popular_actions).getVisibility());
        assertEquals(PopularGalleryHistory.CURRENT,
                (int) ReflectionHelpers.<Integer>getField(scene, "mPopularViewMode"));
        assertEquals(0, scene.lastTip);
        assertEquals(2, helper.getData().size());
        assertRendered(2);
        toggle(PopularGalleryHistory.UPDATES);
        assertRendered(2);
        refresh(2, 3);
        assertRendered(1);
        toggle(PopularGalleryHistory.UPDATES);
        assertRendered(2);
        builder.setMode(ListUrlBuilder.MODE_NORMAL);
        builder.setKeyword("new search");
        refresh(5, 6);
        assertRendered(2);
    }

    private View bindPopularActions() {
        View actions = LayoutInflater.from(scene.getContext()).inflate(R.layout.scene_gallery_list, null);
        FabLayout menu = actions.findViewById(R.id.fab_layout);
        menu.setExpanded(false, false);
        ReflectionHelpers.setField(scene, "mFabLayout", menu);
        ReflectionHelpers.setField(scene, "mPopularActions", actions.findViewById(R.id.popular_actions));
        ReflectionHelpers.setField(scene, "mPopularUpdates", actions.findViewById(R.id.popular_updates));
        ReflectionHelpers.setField(scene, "mPopularPrevious", actions.findViewById(R.id.popular_previous));
        ReflectionHelpers.setField(scene, "mPopularUpdatesMenu", actions.findViewById(R.id.popular_updates_menu));
        ReflectionHelpers.setField(scene, "mPopularPreviousMenu", actions.findViewById(R.id.popular_previous_menu));
        return actions;
    }

    @Test public void footerAppendsRepeatedGalleriesOnceWithoutARequestOrBaselineChange() {
        View actions = bindPopularActions();
        refresh(2, 3, 1, 4);
        toggle(PopularGalleryHistory.UPDATES);
        layout();
        List<String> notifications = new ArrayList<>();
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override public void onChanged() { notifications.add("changed"); }
            @Override public void onItemRangeInserted(int start, int count) {
                notifications.add(start + ":" + count);
            }
        });
        int task = ReflectionHelpers.getField(helper, "mCurrentTaskId");
        int firstTop = recycler.getChildAt(0).getTop();
        footer();
        layout();
        assertEquals(task, (int) ReflectionHelpers.<Integer>getField(helper, "mCurrentTaskId"));
        assertFalse(content.getRefreshLayout().isRefreshing());
        assertEquals(Arrays.asList(3L, 4L, null, 2L, 1L), visibleIds());
        assertEquals(Arrays.asList("2:3"), notifications);
        assertEquals(1, revealScrollCount);
        assertEquals(firstTop, recycler.getChildAt(0).getTop());
        assertNull(adapter.getDataAt(2));
        assertEquals(4, (int) ReflectionHelpers.callInstanceMethod(adapter, "getGalleryCount"));
        assertEquals(2L, ((GalleryInfo) ReflectionHelpers.callInstanceMethod(adapter, "getGalleryAtIndex",
                ClassParameter.from(int.class, 2))).gid);
        assertCountButtons(actions, 4, 4);
        PopularGalleryHistory history = ReflectionHelpers.getField(scene, "mPopularHistory");
        assertEquals(Arrays.asList(1L, 2L), ids(history.galleries(PopularGalleryHistory.PREVIOUS)));
        assertEquals(Arrays.asList(2L, 3L, 1L, 4L), ids(history.galleries(PopularGalleryHistory.CURRENT)));
        assertEquals(Arrays.asList(3L, 4L), ids(history.galleries(PopularGalleryHistory.UPDATES)));
        footer();
        assertEquals(Arrays.asList(3L, 4L, null, 2L, 1L), visibleIds());
        assertEquals(Arrays.asList("2:3"), notifications);
        assertEquals(1, revealScrollCount);
        assertEquals(task, (int) ReflectionHelpers.<Integer>getField(helper, "mCurrentTaskId"));
        assertFalse(content.getRefreshLayout().isRefreshing());
        ReflectionHelpers.<Set<Long>>getField(scene, "mSelectedGids").add(2L);
        assertEquals(Arrays.asList(2L), ids(ReflectionHelpers.callInstanceMethod(
                scene, "getSelectedGalleriesOldestFirst")));
    }

    @Test @Config(sdk = 34, qualifiers = "600dpi")
    public void dividerUsesConfiguredRoundLinesAndGapAtDeviceDensity() {
        refresh(2, 3, 4);
        toggle(PopularGalleryHistory.UPDATES);
        adapter.setType(GalleryAdapterNew.TYPE_GRID);
        footer();
        layout();
        View divider = recycler.findViewHolderForAdapterPosition(2).itemView;
        assertEquals(R.id.popular_history_divider, divider.getId());
        assertTrue(((StaggeredGridLayoutManager.LayoutParams) divider.getLayoutParams()).isFullSpan());
        assertEquals(recycler.getWidth() - recycler.getPaddingLeft() - recycler.getPaddingRight(),
                divider.getWidth());
        assertEquals(11, divider.getPaddingTop());
        assertEquals(11, divider.getPaddingBottom());
        assertEquals(46, divider.getHeight());
        assertTrue(divider instanceof com.hippo.ehviewer.widget.GallerySectionDivider);
    }

    @Test public void headerRefreshAndModeSwitchResetExpansion() {
        setDefaultUpdates(true);
        refresh(2, 3, 4);
        footer();
        assertEquals(Arrays.asList(3L, 4L, null, 2L), visibleIds());
        refresh(2, 3, 4, 5);
        assertEquals(Arrays.asList(5L), visibleIds());
        assertFalse(ReflectionHelpers.getField(scene, "mPopularRepeatedExpanded"));
        footer();
        assertEquals(Arrays.asList(5L, null, 2L, 3L, 4L), visibleIds());
        toggle(PopularGalleryHistory.UPDATES);
        assertEquals(Arrays.asList(2L, 3L, 4L, 5L), visibleIds());
        toggle(PopularGalleryHistory.UPDATES);
        assertEquals(Arrays.asList(5L), visibleIds());
        footer();
        toggle(PopularGalleryHistory.PREVIOUS);
        assertEquals(Arrays.asList(2L, 3L, 4L), visibleIds());
        assertFalse(ReflectionHelpers.getField(scene, "mPopularRepeatedExpanded"));
    }

    @Test public void footerWithNoRepeatedGalleriesFinishesWithoutADividerOrRequest() {
        refresh(3, 4);
        toggle(PopularGalleryHistory.UPDATES);
        int task = ReflectionHelpers.getField(helper, "mCurrentTaskId");
        footer();
        assertEquals(Arrays.asList(3L, 4L), visibleIds());
        assertEquals(task, (int) ReflectionHelpers.<Integer>getField(helper, "mCurrentTaskId"));
        assertFalse(content.getRefreshLayout().isRefreshing());
        assertEquals(0, revealScrollCount);
    }

    @Test @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    public void appendingAtTheBottomScrollsDownByTheNormalNextPageDistance() {
        long[] gids = new long[32];
        for (int i = 0; i < 30; i++) gids[i] = i + 3;
        gids[30] = 2;
        gids[31] = 1;
        refresh(gids);
        toggle(PopularGalleryHistory.UPDATES);
        layout();
        ((StaggeredGridLayoutManager) recycler.getLayoutManager()).scrollToPositionWithOffset(29, 0);
        content.requestLayout();
        layout();
        RecyclerView.ViewHolder anchor = recycler.findViewHolderForAdapterPosition(29);
        assertNotNull("The end-of-list anchor must be laid out before testing the reveal scroll", anchor);
        int oldTop = anchor.itemView.getTop();
        footer();
        layout();
        assertEquals(oldTop - LayoutUtils.dp2pix(recycler.getContext(), 48),
                recycler.findViewHolderForAdapterPosition(29).itemView.getTop());
        assertEquals(1, revealScrollCount);
        assertEquals(33, adapter.getItemCount());
        assertEquals(32L, adapter.getDataAt(29).gid);
        assertNull(adapter.getDataAt(30));
        assertEquals(2L, adapter.getDataAt(31).gid);
        assertEquals(1L, adapter.getDataAt(32).gid);
        int newTop = recycler.findViewHolderForAdapterPosition(29).itemView.getTop();
        footer();
        layout();
        assertEquals(newTop, recycler.findViewHolderForAdapterPosition(29).itemView.getTop());
        assertEquals(1, revealScrollCount);
    }

    @Test public void normalSearchNextPageStillUsesTheSameScrollFeedback() {
        builder.setMode(ListUrlBuilder.MODE_NORMAL);
        helper.doGetData(ContentLayout.ContentHelper.TYPE_NEXT_PAGE_KEEP_POS, 1,
                ContentLayout.ContentHelper.REFRESH_TYPE_FOOTER);
        helper.onGetPageData(ReflectionHelpers.getField(helper, "mCurrentTaskId"), 2, 0, galleries(3, 4));
        assertEquals(Arrays.asList(1L, 2L, 3L, 4L), visibleIds());
        assertEquals(1, revealScrollCount);
        assertFalse(content.getRefreshLayout().isRefreshing());
    }

    @Test public void footerInAllPopularKeepsTheNormalLastPageRefresh() {
        refresh(2, 3, 4);
        int task = ReflectionHelpers.getField(helper, "mCurrentTaskId");
        footer();
        assertNotEquals(task, (int) ReflectionHelpers.<Integer>getField(helper, "mCurrentTaskId"));
        assertEquals(ContentLayout.ContentHelper.TYPE_REFRESH_PAGE,
                (int) ReflectionHelpers.<Integer>getField(helper, "mCurrentTaskType"));
        assertFalse(ReflectionHelpers.getField(scene, "mPopularRepeatedExpanded"));
        helper.onGetPageData(ReflectionHelpers.getField(helper, "mCurrentTaskId"), 1, 0, galleries(2, 3, 4));
        assertFalse(content.getRefreshLayout().isRefreshing());
    }

    @Test public void leavingExpandedPopularDoesNotShiftNormalGalleryPositions() {
        refresh(2, 3, 1, 4);
        toggle(PopularGalleryHistory.UPDATES);
        footer();
        assertEquals(Arrays.asList(3L, 4L, null, 2L, 1L), visibleIds());
        builder.setMode(ListUrlBuilder.MODE_NORMAL);
        assertEquals(Arrays.asList(2L, 3L, 1L, 4L), visibleIds());
        assertEquals(GalleryAdapterNew.TYPE_LIST, adapter.getItemViewType(2));
        refresh(7, 8);
        assertEquals(Arrays.asList(7L, 8L), visibleIds());
    }

    @Test public void downloadedFilterKeepsReadBoundaryWhenVisibleUpdatesDisappear() {
        View actions = bindPopularActions();
        refresh(2, 3, 1, 4);
        toggle(PopularGalleryHistory.UPDATES);
        downloadedGids.addAll(Arrays.asList(3L, 1L));
        ReflectionHelpers.callInstanceMethod(scene, "setDownloadedOnlyMode",
                ClassParameter.from(boolean.class, true), ClassParameter.from(boolean.class, false));
        assertEquals(Arrays.asList(3L), visibleIds());
        footer();
        assertEquals(Arrays.asList(3L, null, 1L), visibleIds());
        assertCountButtons(actions, 2, 4);
        downloadedGids.remove(3L);
        ReflectionHelpers.callInstanceMethod(scene, "onDownloadMembershipChanged");
        assertEquals(Arrays.asList(null, 1L), visibleIds());
        ReflectionHelpers.callInstanceMethod(scene, "setDownloadedOnlyMode",
                ClassParameter.from(boolean.class, false), ClassParameter.from(boolean.class, false));
        assertEquals(Arrays.asList(3L, 4L, null, 2L, 1L), visibleIds());
    }

    @Test @Config(sdk = 34, qualifiers = "600dpi")
    public void allSubscriptionEntriesUseSidebarGidsAndKeepTheBoundaryAfterClearingBadges() {
        Settings.putBoolean(Settings.KEY_SHOW_VIEWED_GALLERIES, true);
        Settings.putBoolean(Settings.KEY_AUTO_SUBSCRIPTION_UPDATES_EH, true);
        Settings.putBoolean(Settings.KEY_AUTO_SUBSCRIPTION_UPDATES_BOOKMARK, true);
        for (int mode : new int[]{ListUrlBuilder.MODE_SUBSCRIPTION,
                ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION, ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION}) {
            adapter.setType(GalleryAdapterNew.TYPE_LIST);
            SubscriptionUpdateManager manager = subscriptionManager(mode, 3, 5);
            assertEquals(new HashSet<>(Arrays.asList(3L, 5L)), manager.getUnreadGalleryGids(mode));
            ReflectionHelpers.callInstanceMethod(scene, "markSubscriptionOpened");
            assertTrue(manager.getUnreadGalleryGids(mode).isEmpty());
            // A backdated update appears after an old item in server order; GID membership wins.
            refresh(7, 5, 4, 3, 2);
            assertEquals(Arrays.asList(5L, 3L, null, 7L, 4L, 2L), visibleIds());
            assertRendered(6);
            adapter.setType(GalleryAdapterNew.TYPE_GRID);
            layout();
            View divider = recycler.findViewHolderForAdapterPosition(2).itemView;
            assertEquals(46, divider.getHeight());
            assertTrue(((StaggeredGridLayoutManager.LayoutParams) divider.getLayoutParams()).isFullSpan());
            assertEquals(3, helper.getAdapterPosition(0));
            assertEquals(1, helper.getDataPosition(0));
            assertEquals(0, helper.getDataPosition(2));
        }
    }

    @Test public void subscriptionBoundaryAppearsWhenPaginationReachesOldGalleries() {
        subscriptionManager(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION, 5, 4);
        ReflectionHelpers.callInstanceMethod(scene, "markSubscriptionOpened");
        refresh(5, 4);
        assertEquals(Arrays.asList(5L, 4L), visibleIds());
        helper.getData().addAll(galleries(3, 2));
        ReflectionHelpers.callInstanceMethod(adapter, "onSourceItemRangeInserted",
                ClassParameter.from(int.class, 2), ClassParameter.from(int.class, 2));
        assertEquals(Arrays.asList(5L, 4L, null, 3L, 2L), visibleIds());
        assertEquals(3, helper.getAdapterPosition(2));
        assertEquals(2, helper.getDataPosition(3));
    }

    @Test public void subscriptionDownloadedFilterKeepsReadBoundaryAndNewSearchDropsIt() {
        subscriptionManager(ListUrlBuilder.MODE_SUBSCRIPTION, 5, 4);
        ReflectionHelpers.callInstanceMethod(scene, "markSubscriptionOpened");
        refresh(5, 4, 3, 2);
        downloadedGids.addAll(Arrays.asList(5L, 3L));
        ReflectionHelpers.callInstanceMethod(scene, "setDownloadedOnlyMode",
                ClassParameter.from(boolean.class, true), ClassParameter.from(boolean.class, false));
        assertEquals(Arrays.asList(5L, null, 3L), visibleIds());
        downloadedGids.remove(5L);
        ReflectionHelpers.callInstanceMethod(scene, "onDownloadMembershipChanged");
        assertEquals(Arrays.asList(null, 3L), visibleIds());
        ReflectionHelpers.callInstanceMethod(scene, "setDownloadedOnlyMode",
                ClassParameter.from(boolean.class, false), ClassParameter.from(boolean.class, false));
        assertEquals(Arrays.asList(5L, 4L, null, 3L, 2L), visibleIds());
        builder.setMode(ListUrlBuilder.MODE_NORMAL);
        refresh(9, 8);
        assertEquals(Arrays.asList(9L, 8L), visibleIds());
    }

    @Test @Config(sdk = 34, qualifiers = "600dpi")
    public void allSubscriptionModesWithoutVisibleUpdatesRenderDividerAtTop() {
        for (int mode : new int[]{ListUrlBuilder.MODE_SUBSCRIPTION,
                ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION, ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION}) {
            for (long[] unread : new long[][]{new long[0], new long[]{9}}) {
                subscriptionManager(mode, unread);
                ReflectionHelpers.callInstanceMethod(scene, "markSubscriptionOpened");
                refresh(5, 4, 3);
                assertEquals(Arrays.asList(null, 5L, 4L, 3L), visibleIds());
                assertEquals(1, helper.getAdapterPosition(0));
                assertEquals(0, helper.getDataPosition(0));
                assertEquals(0, helper.getDataPosition(1));
                for (int type : new int[]{GalleryAdapterNew.TYPE_LIST, GalleryAdapterNew.TYPE_GRID}) {
                    adapter.setType(type);
                    layout();
                    View divider = recycler.findViewHolderForAdapterPosition(0).itemView;
                    assertTrue(divider instanceof com.hippo.ehviewer.widget.GallerySectionDivider);
                    assertTrue(((StaggeredGridLayoutManager.LayoutParams) divider.getLayoutParams()).isFullSpan());
                    assertNotNull(recycler.findViewHolderForAdapterPosition(1).itemView.findViewById(R.id.thumb_new));
                }
                refresh();
                assertEquals(0, adapter.getItemCount());
            }
        }
    }

    @Test public void viewedSearchWithoutUpdatesKeepsTopDividerThroughPaginationAndRestore() {
        Settings.putBoolean(Settings.KEY_SHOW_VIEWED_GALLERIES, true);
        builder.setMode(ListUrlBuilder.MODE_NORMAL);
        builder.setKeyword("all-viewed-search");
        refresh(5, 4);
        assertEquals(Arrays.asList(5L, 4L), visibleIds());
        refresh(5, 4);
        assertEquals(Arrays.asList(null, 5L, 4L), visibleIds());
        assertEquals(1, helper.getAdapterPosition(0));
        assertEquals(0, helper.getDataPosition(0));
        helper.getData().addAll(galleries(3, 2));
        ReflectionHelpers.callInstanceMethod(adapter, "onSourceItemRangeInserted",
                ClassParameter.from(int.class, 2), ClassParameter.from(int.class, 2));
        assertEquals(Arrays.asList(null, 5L, 4L, 3L, 2L), visibleIds());
        android.os.Bundle saved = new android.os.Bundle();
        scene.onSaveInstanceState(saved);
        ReflectionHelpers.setField(scene, "mSeenSearch", null);
        ReflectionHelpers.callInstanceMethod(scene, "onRestore", ClassParameter.from(android.os.Bundle.class, saved));
        builder = ReflectionHelpers.getField(scene, "mUrlBuilder");
        ReflectionHelpers.callInstanceMethod(adapter, "onSourceDataSetChanged");
        assertEquals(Arrays.asList(null, 5L, 4L, 3L, 2L), visibleIds());
        Settings.putBoolean(Settings.KEY_SHOW_VIEWED_GALLERIES, false);
        ReflectionHelpers.callInstanceMethod(adapter, "onSourceDataSetChanged");
        assertEquals(Arrays.asList(5L, 4L, 3L, 2L), visibleIds());
        Settings.putBoolean(Settings.KEY_SHOW_VIEWED_GALLERIES, true);
        refresh();
        assertEquals(0, adapter.getItemCount());
    }

    @Test public void subscriptionUnreadSnapshotSurvivesSceneStateRestoration() {
        subscriptionManager(ListUrlBuilder.MODE_SUBSCRIPTION, 5);
        ReflectionHelpers.callInstanceMethod(scene, "markSubscriptionOpened");
        refresh(5, 4);
        android.os.Bundle state = new android.os.Bundle();
        scene.onSaveInstanceState(state);
        ReflectionHelpers.<Set<Long>>getField(scene, "mSubscriptionNewGids").clear();
        ReflectionHelpers.callInstanceMethod(scene, "onRestore", ClassParameter.from(android.os.Bundle.class, state));
        builder = ReflectionHelpers.getField(scene, "mUrlBuilder");
        ReflectionHelpers.callInstanceMethod(scene, "markSubscriptionOpened", ClassParameter.from(boolean.class, false));
        refresh(5, 4);
        assertEquals(Arrays.asList(5L, null, 4L), visibleIds());
    }

    private SubscriptionUpdateManager subscriptionManager(int mode, long... gids) {
        Settings.putBoolean(Settings.KEY_SHOW_VIEWED_GALLERIES, true);
        builder.setMode(mode);
        SubscriptionUpdateManager manager = new SubscriptionUpdateManager(scene.getContext());
        Set<Long> eh = ReflectionHelpers.getField(manager, "mEhUnreadGids");
        Set<Long> bookmark = ReflectionHelpers.getField(manager, "mBookmarkUnreadGids");
        eh.clear();
        bookmark.clear();
        for (long gid : gids) {
            if (mode != ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION) eh.add(gid);
            if (mode != ListUrlBuilder.MODE_SUBSCRIPTION) bookmark.add(gid);
        }
        ReflectionHelpers.setField(scene, "mSubscriptionUpdateManager", manager);
        return manager;
    }

    @Test public void viewedSearchUsesPreviousMaximumAndPaginationKeepsTheBoundary() {
        Settings.putBoolean(Settings.KEY_SHOW_VIEWED_GALLERIES, true);
        builder.setMode(ListUrlBuilder.MODE_NORMAL);
        builder.setKeyword("seen-search");
        refresh(4, 3);
        assertEquals(Arrays.asList(4L, 3L), visibleIds());
        refresh(6, 4, 5, 3);
        assertEquals(Arrays.asList(6L, 5L, null, 4L, 3L), visibleIds());
        assertEquals(3, helper.getAdapterPosition(1));
        assertEquals(1, helper.getDataPosition(2));
        helper.getData().addAll(galleries(2, 1));
        ReflectionHelpers.callInstanceMethod(adapter, "onSourceItemRangeInserted",
                ClassParameter.from(int.class, 4), ClassParameter.from(int.class, 2));
        assertEquals(Arrays.asList(6L, 5L, null, 4L, 3L, 2L, 1L), visibleIds());
        layout();
        assertTrue(recycler.findViewHolderForAdapterPosition(2).itemView
                instanceof com.hippo.ehviewer.widget.GallerySectionDivider);
        android.os.Bundle saved = new android.os.Bundle();
        scene.onSaveInstanceState(saved);
        ReflectionHelpers.setField(scene, "mSeenSearch", null);
        ReflectionHelpers.callInstanceMethod(scene, "onRestore", ClassParameter.from(android.os.Bundle.class, saved));
        builder = ReflectionHelpers.getField(scene, "mUrlBuilder");
        ReflectionHelpers.callInstanceMethod(adapter, "onSourceDataSetChanged");
        assertEquals(Arrays.asList(6L, 5L, null, 4L, 3L, 2L, 1L), visibleIds());
        builder.setKeyword("different-search");
        refresh(8, 3);
        assertEquals(Arrays.asList(8L, 3L), visibleIds());
    }

    @Test public void disabledSwitchHidesSubscriptionBoundaryButStillAdvancesSubscriptionBaseline() {
        subscriptionManager(ListUrlBuilder.MODE_SUBSCRIPTION, 5);
        Settings.putBoolean(Settings.KEY_SHOW_VIEWED_GALLERIES, false);
        ReflectionHelpers.callInstanceMethod(scene, "markSubscriptionOpened");
        refresh(5, 4);
        assertEquals(Arrays.asList(5L, 4L), visibleIds());
        assertEquals(seen(5).minute(), SubscriptionUpdateManager.getLastSeenEhState().minute());
        assertEquals("", Settings.getString("seen_gallery_searches_v2", ""));
        builder.setMode(ListUrlBuilder.MODE_NORMAL);
        builder.setKeyword("disabled-search");
        refresh(9, 5);
        assertEquals(Arrays.asList(9L, 5L), visibleIds());
        assertEquals("", Settings.getString("seen_gallery_searches_v2", ""));
    }

    @Test public void latePublishedLowerGidAppearsAboveViewedDivider() throws Exception {
        String resource = "/com/hippo/ehviewer/client/parser/GalleryListUploader.html";
        org.jsoup.nodes.Document page;
        try (java.io.InputStream input = getClass().getResourceAsStream(resource)) {
            assertNotNull(input);
            page = org.jsoup.Jsoup.parse(input, "UTF-8", "");
        }
        // Existing website fixture: publication order is the reverse of GID order.
        assertEquals("2021-08-31 22:20", page.getElementById("posted_1992853").text());
        assertEquals("2021-08-31 14:28", page.getElementById("posted_1997697").text());
        Settings.putBoolean(Settings.KEY_SHOW_VIEWED_GALLERIES, true);
        builder.setMode(ListUrlBuilder.MODE_NORMAL);
        builder.setKeyword("late-publication-characterization");
        refresh(1997697);
        assertEquals(Arrays.asList(1997697L), visibleIds());

        // Replay a later response containing that previously absent, later-published GID.
        // 1999000 is a synthetic newer control. null denotes the section divider.
        refresh(1992853, 1999000, 1997697);
        assertEquals(Arrays.asList(1992853L, 1999000L, 1997697L), ids(helper.getData()));
        assertEquals(Arrays.asList(1992853L, 1999000L, null, 1997697L), visibleIds());
    }

    @Test public void olderUnknownGalleryOutsideRetainedTimeWindowRemainsBelowDivider() {
        Settings.putBoolean(Settings.KEY_SHOW_VIEWED_GALLERIES, true);
        builder.setMode(ListUrlBuilder.MODE_NORMAL);
        builder.setKeyword("unloaded-gallery-characterization");
        refresh(110, 109);
        // GID 108 was never returned to this scene, but the maximum already covers it.
        refresh(111, 110, 109, 108);
        assertEquals(Arrays.asList(111L, null, 110L, 109L, 108L), visibleIds());
    }

    @Test public void subscribedBookmarksKeepSharedAcknowledgementTime() {
        Settings.putBoolean(Settings.KEY_SHOW_VIEWED_GALLERIES, true);
        builder.setMode(ListUrlBuilder.MODE_NORMAL);
        builder.setKeyword("subscription-a-characterization");
        QuickSearch first = builder.toQuickSearch();
        first.subscribed = true;
        bookmarks.add(first);
        builder.setKeyword("subscription-b-characterization");
        QuickSearch second = builder.toQuickSearch();
        second.subscribed = true;
        bookmarks.add(second);
        SubscriptionUpdateManager manager = new SubscriptionUpdateManager(scene.getContext());
        ReflectionHelpers.setField(scene, "mSubscriptionUpdateManager", manager);
        builder.set(first);
        refresh(200);
        assertEquals(seen(200).minute(), SubscriptionUpdateManager.getLastSeenBookmarkState().minute());

        // First visit to B inherits A's watermark, despite B never having been opened.
        builder.set(second);
        refresh(190, 180);
        assertEquals(Arrays.asList(null, 190L, 180L), visibleIds());
    }

    @Test public void subscriptionRefreshRecomputesUpdatesAgainstPreviousPublicationState() {
        subscriptionManager(ListUrlBuilder.MODE_SUBSCRIPTION, 200);
        ReflectionHelpers.callInstanceMethod(scene, "markSubscriptionOpened");
        refresh(200, 190);
        assertEquals(Arrays.asList(200L, null, 190L), visibleIds());

        // A new successful refresh gets a new comparison snapshot before advancing it.
        refresh(210, 200, 190);
        assertEquals(Arrays.asList(210L, null, 200L, 190L), visibleIds());
    }

    @Test public void failedSubscriptionRefreshKeepsDisplayedUpdatesAndStoredPublicationState() {
        subscriptionManager(ListUrlBuilder.MODE_SUBSCRIPTION, 200);
        ReflectionHelpers.callInstanceMethod(scene, "markSubscriptionOpened");
        refresh(200, 190);
        long previous = SubscriptionUpdateManager.getLastSeenEhState().minute();
        helper.refresh();
        assertTrue(ReflectionHelpers.<Set<Long>>getField(scene, "mSubscriptionNewGids").contains(200L));
        int task = ReflectionHelpers.getField(helper, "mCurrentTaskId");
        ReflectionHelpers.callInstanceMethod(scene, "onGetGalleryListFailure",
                ClassParameter.from(Exception.class, new java.io.IOException("offline")),
                ClassParameter.from(int.class, task));
        assertTrue(ReflectionHelpers.<Set<Long>>getField(scene, "mSubscriptionNewGids").contains(200L));
        assertEquals(previous, SubscriptionUpdateManager.getLastSeenEhState().minute());
    }

    @Test public void subscribedBookmarkAndGlobalSubscriptionShareViewedMaximum() {
        Settings.putBoolean(Settings.KEY_SHOW_VIEWED_GALLERIES, true);
        builder.setMode(ListUrlBuilder.MODE_NORMAL);
        builder.setKeyword("subscribed-bookmark");
        QuickSearch bookmark = builder.toQuickSearch();
        bookmark.subscribed = true;
        bookmarks.add(bookmark);
        SubscriptionUpdateManager manager = new SubscriptionUpdateManager(scene.getContext());
        ReflectionHelpers.setField(scene, "mSubscriptionUpdateManager", manager);
        manager.recordGlobalPageViewed(seen(90), seen(100));
        refresh(110, 100, 95);
        assertEquals(Arrays.asList(110L, null, 100L, 95L), visibleIds());
        assertEquals(seen(110).minute(), SubscriptionUpdateManager.getLastSeenBookmarkState().minute());
        manager.recordBookmarkPageViewed(seen(120));
        refresh(125, 115, 100);
        assertEquals(Arrays.asList(125L, 115L, null, 100L), visibleIds());
    }

    private void footer() {
        content.getRefreshLayout().setFooterRefreshing(true);
        RefreshLayout.OnRefreshListener listener = ReflectionHelpers.getField(helper, "mOnRefreshListener");
        listener.onFooterRefresh();
    }

    private List<Long> visibleIds() {
        List<Long> result = new ArrayList<>();
        for (int i = 0; i < adapter.getItemCount(); i++) {
            GalleryInfo gallery = adapter.getDataAt(i);
            result.add(gallery == null ? null : gallery.gid);
        }
        return result;
    }

    private static List<Long> ids(List<GalleryInfo> galleries) {
        List<Long> result = new ArrayList<>();
        for (GalleryInfo gallery : galleries) result.add(gallery.gid);
        return result;
    }

    @Test public void defaultUpdatesWithNoHistoryShowsAllAndCanExitWithoutPreviousSnapshot() {
        setDefaultUpdates(true);
        ReflectionHelpers.setField(scene, "mPopularHistory",
                new PopularGalleryHistory(scene.getContext().getCacheDir(), Settings.getGallerySite()));
        refresh(7, 8);
        assertRendered(2);
        assertEquals(PopularGalleryHistory.UPDATES,
                (int) ReflectionHelpers.<Integer>getField(scene, "mPopularViewMode"));
        toggle(PopularGalleryHistory.UPDATES);
        assertRendered(2);
        assertEquals(PopularGalleryHistory.CURRENT,
                (int) ReflectionHelpers.<Integer>getField(scene, "mPopularViewMode"));
    }

    private void setDefaultUpdates(boolean enabled) {
        Settings.putBoolean(Settings.KEY_POPULAR_UPDATES_ONLY, enabled);
    }

    @Test @Config(sdk = 34)
    @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
    public void unrelatedGalleryReturnAndOlderCallbacksCannotOverrideReaderOrReset() {
        ManualProgressExecutor jobs = new ManualProgressExecutor();
        java.util.concurrent.atomic.AtomicInteger page = new java.util.concurrent.atomic.AtomicInteger(1);
        GalleryAdapterNew progressAdapter = new GalleryAdapterNew(LayoutInflater.from(scene.getContext()),
                scene.getContext().getResources(), recycler, GalleryAdapterNew.TYPE_GRID, false, jobs, true) {
            @Override public int getItemCount() { return 0; }
        };
        ReflectionHelpers.setField(progressAdapter, "mReadingProgress",
                new com.hippo.ehviewer.gallery.ReadingProgressCache(jobs, gid -> page.get()));
        GalleryInfo gallery = galleries(123).get(0);
        gallery.pages = 134;
        android.util.LruCache<?, ?> snapshots = ReflectionHelpers.getStaticField(
                com.hippo.ehviewer.spider.SpiderQueen.class, "sStartPages");
        com.hippo.ehviewer.spider.SpiderQueen.recordReadingProgress(gallery.gid, 1);
        GalleryAdapterNew.GalleryHolder holder = progressAdapter.onCreateViewHolder(recycler, GalleryAdapterNew.TYPE_GRID);
        holder.boundGid = gallery.gid;
        bindProgress(progressAdapter, holder, gallery);
        assertEquals("2/134", holder.thumbnailPages.getText().toString());
        // Another gallery was visited; the list rebinds without clearing progress.
        progressAdapter.notifyDataSetChanged();
        bindProgress(progressAdapter, holder, gallery);
        assertEquals("2/134", holder.thumbnailPages.getText().toString());
        jobs.runAll();
        // The old lookup's UI callback has not run yet when the reader advances.
        com.hippo.ehviewer.spider.SpiderQueen.recordReadingProgress(gallery.gid, 5);
        shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals("6/134", holder.thumbnailPages.getText().toString());
        bindProgress(progressAdapter, holder, gallery);
        assertEquals("6/134", holder.thumbnailPages.getText().toString());
        // Explicit reset wins over a still-fresh positive adapter cache entry.
        com.hippo.ehviewer.spider.SpiderQueen.resetReadingProgress(gallery.gid);
        shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals("134p", holder.thumbnailPages.getText().toString());
        snapshots.evictAll();
    }

    private void bindProgress(GalleryAdapterNew progressAdapter, GalleryAdapterNew.GalleryHolder holder, GalleryInfo gallery) {
        ReflectionHelpers.callInstanceMethod(progressAdapter, "bindPageProgress",
                ClassParameter.from(GalleryAdapterNew.GalleryHolder.class, holder),
                ClassParameter.from(TextView.class, holder.thumbnailPages),
                ClassParameter.from(GalleryInfo.class, gallery),
                ClassParameter.from(boolean.class, true), ClassParameter.from(boolean.class, false),
                ClassParameter.from(boolean.class, true));
    }

    private static class ManualProgressExecutor extends java.util.concurrent.AbstractExecutorService {
        final java.util.ArrayDeque<Runnable> jobs = new java.util.ArrayDeque<>();
        @Override public void execute(Runnable job) { jobs.add(job); }
        void runAll() { while (!jobs.isEmpty()) jobs.remove().run(); }
        @Override public void shutdown() {}
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) { return true; }
    }

    private void refresh(long... gids) {
        helper.refresh();
        GalleryListParser.Result result = new GalleryListParser.Result();
        result.galleryInfoList = galleries(gids);
        result.pages = 1;
        int task = ReflectionHelpers.getField(helper, "mCurrentTaskId");
        ReflectionHelpers.callInstanceMethod(scene, "onGetGalleryListSuccess",
                ClassParameter.from(GalleryListParser.Result.class, result), ClassParameter.from(int.class, task));
    }

    private void toggle(int mode) {
        ReflectionHelpers.callInstanceMethod(scene, "togglePopularView", ClassParameter.from(int.class, mode));
    }

    private void layout() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        content.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        content.layout(0, 0, 1080, 1920);
    }

    private void assertRendered(int count) {
        layout();
        assertEquals(count, adapter.getItemCount());
        assertEquals(0, helper.getShownViewIndex());
        assertTrue(recycler.isShown());
        assertEquals(1f, content.findViewById(R.id.content_view).getAlpha(), 0f);
        assertTrue("Recycler must attach visible gallery rows", recycler.getChildCount() > 0);
        TextView title = recycler.getChildAt(0).findViewById(R.id.title);
        assertFalse(title.getText().toString().isEmpty());
    }

    private static GallerySeenState seen(long... gids) {
        return GallerySeenState.empty().observe(galleries(gids));
    }

    private static List<GalleryInfo> galleries(long... gids) {
        List<GalleryInfo> result = new ArrayList<>();
        for (long gid : gids) {
            GalleryInfo gallery = new GalleryInfo();
            gallery.gid = gid;
            gallery.posted = com.hippo.ehviewer.client.parser.ParserUtils.formatPostedDate(
                    1767225600000L + gid * 60000L);
            if (gid == 1992853) gallery.posted = "2021-08-31 22:20";
            if (gid == 1997697) gallery.posted = "2021-08-31 14:28";
            if (gid == 1999000) gallery.posted = "2021-08-31 23:00";
            gallery.token = "token" + gid;
            gallery.title = "Gallery " + gid;
            gallery.thumb = "https://example.com/" + gid;
            result.add(gallery);
        }
        return result;
    }

    private static class TestScene extends GalleryListScene {
        private final Context context;
        int lastTip;
        int originalLongPresses;
        GalleryInfo lastLongPressedGallery;
        TestScene(Context context) { this.context = context; }
        @Override public Context getContext() { return context; }
        @Override public Context getEHContext() { return context; }
        @Override public void showTip(int id, int length) { lastTip = id; }
        @Override public boolean onItemLongClick(GalleryInfo gallery, View view) {
            originalLongPresses++;
            lastLongPressedGallery = gallery;
            return true;
        }
    }

    @Implements(EhApplication.class)
    public static class AppShadow extends org.robolectric.shadows.ShadowApplication {
        @Implementation protected static EhClient getEhClient(Context context) { return null; }
        @Implementation protected static ExecutorService getExecutorService(Context context) { return null; }
        @Implementation protected static DownloadManager getDownloadManager(Context context) { return downloads; }
        @Implementation protected static FavouriteStatusRouter getFavouriteStatusRouter() { return router; }
        @Implementation protected static Conaco<Image> getConaco(Context context) { return null; }
    }
    @Implements(EhDB.class)
    public static class DbShadow {
        @Implementation protected static List<QuickSearch> getAllQuickSearch() { return bookmarks; }
    }
    @Implements(DownloadManager.class)
    public static class DownloadShadow {
        @Implementation protected boolean containDownloadInfo(long gid) { return downloadedGids.contains(gid); }
        @Implementation protected boolean hasOlderGalleryVersion(long firstGid, long gid) { return false; }
    }
    @Implements(LoadImageViewNew.class)
    public static class ImageShadow extends org.robolectric.shadows.ShadowView {
        @Implementation protected void load(String key, String url, boolean network) {}
        @Implementation protected void unload() {}
    }
}
