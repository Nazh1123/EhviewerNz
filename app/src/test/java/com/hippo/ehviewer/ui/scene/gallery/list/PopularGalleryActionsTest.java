package com.hippo.ehviewer.ui.scene.gallery.list;

import android.animation.ValueAnimator;
import android.app.Application;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.hippo.easyrecyclerview.LayoutManagerUtils;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.widget.GalleryListActions;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.widget.FabLayout;
import com.hippo.widget.recyclerview.AutoStaggeredGridLayoutManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.time.Duration;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28)
public class PopularGalleryActionsTest {
    private GalleryListScene scene;
    private View root;
    private FabLayout menu;
    private ListUrlBuilder builder;
    private GalleryListActions actions;

    @Before public void setUp() {
        Context app = RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre",
                app.getSharedPreferences("popular-actions-test", Context.MODE_PRIVATE));
        Context context = new ContextThemeWrapper(app, R.style.AppTheme);
        root = LayoutInflater.from(context).inflate(R.layout.scene_gallery_list, null);
        actions = root.findViewById(R.id.gallery_list_actions);
        menu = root.findViewById(R.id.fab_layout);
        menu.setExpanded(false, false);
        scene = new GalleryListScene();
        builder = new ListUrlBuilder();
        builder.setMode(ListUrlBuilder.MODE_WHATS_HOT);
        ReflectionHelpers.setField(scene, "mUrlBuilder", builder);
        ReflectionHelpers.setField(scene, "mFabLayout", menu);
        bind("mOutsideActions", R.id.gallery_list_actions);
        bind("mPopularUpdates", R.id.popular_updates);
        bind("mPopularPrevious", R.id.popular_previous);
        bind("mPopularUpdatesMenu", R.id.popular_updates_menu);
        bind("mPopularPreviousMenu", R.id.popular_previous_menu);
        ReflectionHelpers.setField(scene, "mPopularResponseReady", true);
        menu.setOnExpandListener(scene);
        update();
    }

    @Test public void outsideActionsFollowFabInsteadOfAccumulatingScrollDistance() {
        RecyclerView recycler = new RecyclerView(root.getContext());
        recycler.layout(0, 0, 600, 1200);
        RecyclerView.OnScrollListener listener = ReflectionHelpers.getField(scene, "mOnScrollListener");
        // Refreshing while far down the list can reposition it by more than half a screen.
        listener.onScrolled(recycler, 0, -1200);
        assertEquals(View.VISIBLE, root.findViewById(R.id.gallery_list_actions).getVisibility());
        ReflectionHelpers.setField(recycler, "mScrollState", RecyclerView.SCROLL_STATE_DRAGGING);
        // Small steps do not hide the main FAB, regardless of their total distance.
        ReflectionHelpers.setField(scene, "mHideActionFabSlop", 20);
        for (int i = 0; i < 100; i++) listener.onScrolled(recycler, 0, 10);
        assertEquals(View.VISIBLE, root.findViewById(R.id.gallery_list_actions).getVisibility());
        listener.onScrolled(recycler, 0, 20);
        assertEquals(View.INVISIBLE, actions.getVisibility());
        listener.onScrolled(recycler, 0, -20);
        assertEquals(View.VISIBLE, root.findViewById(R.id.gallery_list_actions).getVisibility());
        menu.setExpanded(true, false);
        assertEquals(View.VISIBLE, root.findViewById(R.id.popular_updates_menu).getVisibility());
        assertEquals(View.VISIBLE, root.findViewById(R.id.popular_previous_menu).getVisibility());
        listener.onScrolled(recycler, 0, -600);
        assertEquals(View.INVISIBLE, actions.getVisibility());
    }

    @Test public void closingMenuRestoresOutsideActions() {
        scene.onClickPrimaryFab(menu, menu.getPrimaryFab());
        assertTrue(menu.isExpanded());
        assertEquals(View.INVISIBLE, actions.getVisibility());
        menu.setExpanded(false, false);
        assertEquals(View.VISIBLE, root.findViewById(R.id.gallery_list_actions).getVisibility());
    }

    @Test public void activeModeKeepsOnlyItsReturnButtonAndOccupiesHiddenFabPosition() {
        layout(600, 1200);
        RecyclerView recycler = new RecyclerView(root.getContext());
        recycler.layout(0, 0, 600, 1200);
        ReflectionHelpers.setField(recycler, "mScrollState", RecyclerView.SCROLL_STATE_DRAGGING);
        RecyclerView.OnScrollListener listener = ReflectionHelpers.getField(scene, "mOnScrollListener");
        for (int mode : new int[]{PopularGalleryHistory.UPDATES, PopularGalleryHistory.PREVIOUS}) {
            menu.setExpanded(false, false);
            ReflectionHelpers.setField(scene, "mPopularViewMode", mode);
            update();
            settle();
            int activeId = mode == PopularGalleryHistory.UPDATES
                    ? R.id.popular_updates : R.id.popular_previous;
            int otherId = mode == PopularGalleryHistory.UPDATES
                    ? R.id.popular_previous : R.id.popular_updates;
            assertEquals(View.VISIBLE, root.findViewById(activeId).getVisibility());
            assertEquals(View.INVISIBLE, root.findViewById(otherId).getVisibility());
            assertCenterAbove(root.findViewById(activeId), menu.getPrimaryFab(), 1);
            listener.onScrolled(recycler, 0, 1200);
            settle();
            assertEquals(View.VISIBLE, root.findViewById(R.id.gallery_list_actions).getVisibility());
            assertCenterAbove(root.findViewById(activeId), menu.getPrimaryFab(), 0);
            listener.onScrolled(recycler, 0, -1200);
            settle();
            assertCenterAbove(root.findViewById(activeId), menu.getPrimaryFab(), 1);
            scene.onClickPrimaryFab(menu, menu.getPrimaryFab());
            settle();
            assertTrue(menu.isExpanded());
            assertEquals(View.INVISIBLE, actions.getVisibility());
            menu.setExpanded(false, false);
            settle();
            assertEquals(View.VISIBLE, root.findViewById(R.id.gallery_list_actions).getVisibility());
        }
        ReflectionHelpers.setField(scene, "mPopularViewMode", PopularGalleryHistory.CURRENT);
        update();
        listener.onScrolled(recycler, 0, 600);
        settle();
        assertEquals(View.INVISIBLE, actions.getVisibility());
    }

    @Test public void actionsAppearOnlyOnPopularAndStayDisabledDuringRequests() {
        ReflectionHelpers.setField(scene, "mPopularRequestInFlight", true);
        update();
        assertEquals(View.INVISIBLE, actions.getVisibility());
        assertFalse(root.findViewById(R.id.popular_updates_menu).isEnabled());
        builder.setMode(ListUrlBuilder.MODE_NORMAL);
        update();
        assertEquals(View.GONE, root.findViewById(R.id.popular_updates_menu).getVisibility());
        assertEquals(View.GONE, root.findViewById(R.id.popular_previous_menu).getVisibility());
    }

    @Test public void positionsFollowActualFabThroughSnackbarInsetsResizeAndBothModes() {
        layout(600, 1200);
        assertCenterAbove(root.findViewById(R.id.popular_updates), menu.getPrimaryFab(), 2);
        assertCenterAbove(root.findViewById(R.id.popular_previous), menu.getPrimaryFab(), 1);
        menu.setTranslationY(-48f);
        actions.getViewTreeObserver().dispatchOnPreDraw();
        View updates = root.findViewById(R.id.popular_updates);
        assertEquals(menu.getPrimaryFab().getY() + menu.getPrimaryFab().getHeight() / 2f - 48f
                        - 2 * (root.getResources().getDimension(R.dimen.fab_size)
                        + root.getResources().getDimension(R.dimen.fab_layout_secondary_margin)),
                updates.getY() + updates.getHeight() / 2f, 0.5f);
        menu.setTranslationY(0f);
        menu.setPadding(0, 0, 38, 82);
        layout(1200, 600);
        for (int mode : new int[]{PopularGalleryHistory.UPDATES, PopularGalleryHistory.PREVIOUS,
                PopularGalleryHistory.CURRENT}) {
            ReflectionHelpers.setField(scene, "mPopularViewMode", mode);
            update();
            settle();
            if (mode == PopularGalleryHistory.CURRENT) {
                assertCenterAbove(updates, menu.getPrimaryFab(), 2);
                assertCenterAbove(root.findViewById(R.id.popular_previous), menu.getPrimaryFab(), 1);
            } else {
                assertCenterAbove(root.findViewById(mode == PopularGalleryHistory.UPDATES
                        ? R.id.popular_updates : R.id.popular_previous), menu.getPrimaryFab(), 1);
            }
        }
    }

    @Test public void outsideActionsScaleWithPrimaryAndRapidReversalsNeverHideRestoredFabs() {
        layout(600, 1200);
        View updates = root.findViewById(R.id.popular_updates);
        ReflectionHelpers.callInstanceMethod(scene, "hideActionFab");
        assertEquals(View.VISIBLE, updates.getVisibility());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        assertEquals(menu.getPrimaryFab().getScaleY(), updates.getScaleY(), 0.08f);
        ReflectionHelpers.callInstanceMethod(scene, "showActionFab");
        settle();
        assertEquals(View.VISIBLE, menu.getPrimaryFab().getVisibility());
        assertEquals(1f, updates.getScaleY(), 0f);
        assertEquals(0f, updates.getRotation(), 0f);
        assertCenterAbove(updates, menu.getPrimaryFab(), 2);
        for (int i = 0; i < 3; i++) {
            menu.setExpanded(true);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80));
            menu.setExpanded(false);
        }
        settle();
        assertEquals(View.VISIBLE, actions.getVisibility());
        assertEquals(View.VISIBLE, updates.getVisibility());
        assertEquals(1f, updates.getScaleY(), 0f);
    }

    @Test public void modeAndScrollTransitionsKeepVisibleButtonsFromOverlapping() {
        layout(600, 1200);
        for (int mode : new int[]{PopularGalleryHistory.UPDATES, PopularGalleryHistory.CURRENT,
                PopularGalleryHistory.PREVIOUS, PopularGalleryHistory.UPDATES,
                PopularGalleryHistory.CURRENT}) {
            ReflectionHelpers.setField(scene, "mPopularViewMode", mode);
            update();
            assertNonOverlappingDuringTransition();
        }
        ReflectionHelpers.setField(scene, "mPopularViewMode", PopularGalleryHistory.UPDATES);
        update();
        settle();
        ReflectionHelpers.callInstanceMethod(scene, "hideActionFab");
        assertNonOverlappingDuringTransition();
        ReflectionHelpers.callInstanceMethod(scene, "showActionFab");
        assertNonOverlappingDuringTransition();
        // Switch modes while the retained button occupies the hidden primary FAB's slot.
        ReflectionHelpers.callInstanceMethod(scene, "hideActionFab");
        settle();
        ReflectionHelpers.setField(scene, "mPopularViewMode", PopularGalleryHistory.PREVIOUS);
        update();
        ReflectionHelpers.callInstanceMethod(scene, "showActionFab");
        assertNonOverlappingDuringTransition();
        // Reverse a hide while the retained button is moving into the primary FAB slot.
        ReflectionHelpers.callInstanceMethod(scene, "hideActionFab");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        ReflectionHelpers.callInstanceMethod(scene, "showActionFab");
        assertNonOverlappingDuringTransition();
    }

    private void assertNonOverlappingDuringTransition() {
        View[] buttons = {root.findViewById(R.id.rating_sort_active), root.findViewById(R.id.downloaded_only_active),
                root.findViewById(R.id.subscription_refresh_active), root.findViewById(R.id.popular_updates),
                root.findViewById(R.id.popular_previous), menu.getPrimaryFab()};
        for (int frame = 0; frame < 20; frame++) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
            for (int i = 0; i < buttons.length; i++) {
                for (int j = i + 1; j < buttons.length; j++) {
                    View a = buttons[i], b = buttons[j];
                    if (a.getVisibility() != View.VISIBLE || b.getVisibility() != View.VISIBLE) continue;
                    double distance = Math.hypot(a.getX() + a.getWidth() / 2f - b.getX() - b.getWidth() / 2f,
                            a.getY() + a.getHeight() / 2f - b.getY() - b.getHeight() / 2f);
                    float radiusSum = root.getResources().getDimension(R.dimen.fab_size) / 2f
                            * (a.getScaleY() + b.getScaleY());
                    assertTrue("Visible FAB circles overlap at frame " + frame, distance + 0.5f >= radiusSum);
                }
            }
        }
    }

    @Test public void leavingDuringARequestSchedulesAFreshRequestOnReturn() {
        ReflectionHelpers.setField(scene, "mHasFirstRefresh", true);
        ReflectionHelpers.setField(scene, "mPopularRequestInFlight", true);
        scene.onDestroyView();
        assertFalse(ReflectionHelpers.<Boolean>getField(scene, "mHasFirstRefresh"));
        assertFalse(ReflectionHelpers.<Boolean>getField(scene, "mPopularRequestInFlight"));
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void noUpdatesNoticeDrawsShrinkingCircleWithoutChangingButtonColors() {
        layout(600, 1200);
        FloatingActionButton outside = root.findViewById(R.id.popular_updates);
        FloatingActionButton inside = root.findViewById(R.id.popular_updates_menu);
        ColorStateList outsideTint = outside.getBackgroundTintList();
        ColorStateList insideTint = inside.getBackgroundTintList();
        int iconAlpha = outside.getDrawable().getAlpha();
        ReflectionHelpers.callInstanceMethod(scene, "showPopularNoUpdatesNotice");
        ValueAnimator animator = ReflectionHelpers.getField(actions, "mNoticeAnimator");
        assertEquals(1500L, animator.getDuration());
        assertEquals(2.5f, ReflectionHelpers.<Float>getField(actions, "mNoticeRadiusFactor"), 0f);
        android.graphics.Rect bounds = new android.graphics.Rect();
        outside.getContentRect(bounds);
        int x = Math.round(outside.getX() + outside.getWidth() / 2f - bounds.width() * 1.125f);
        int y = Math.round(outside.getY() + outside.getHeight() / 2f);
        Bitmap bitmap = Bitmap.createBitmap(600, 1200, Bitmap.Config.ARGB_8888);
        actions.draw(new Canvas(bitmap));
        assertEquals(65, Color.alpha(bitmap.getPixel(x, y)));
        // Low-alpha pixels are quantized by the premultiplied bitmap; the paint retains exact RGB.
        android.graphics.Paint paint = ReflectionHelpers.getField(actions, "mNoticePaint");
        assertEquals(outsideTint.getDefaultColor() & 0xffffff, paint.getColor() & 0xffffff);
        assertEquals(Color.green(outsideTint.getDefaultColor()), Color.green(bitmap.getPixel(x, y)), 4);
        animator.setCurrentPlayTime(750L);
        assertEquals(1.75f, ReflectionHelpers.<Float>getField(actions, "mNoticeRadiusFactor"), 0f);
        bitmap.eraseColor(Color.TRANSPARENT);
        actions.draw(new Canvas(bitmap));
        assertEquals(0, Color.alpha(bitmap.getPixel(x, y)));
        assertSame(outsideTint, outside.getBackgroundTintList());
        assertSame(insideTint, inside.getBackgroundTintList());
        menu.setExpanded(true, false);
        assertEquals(View.VISIBLE, inside.getVisibility());
        assertEquals(1f, outside.getAlpha(), 0f);
        assertEquals(iconAlpha, outside.getDrawable().getAlpha());
        animator.end();
        assertSame(outsideTint, outside.getBackgroundTintList());
        assertSame(insideTint, inside.getBackgroundTintList());
        assertNull(ReflectionHelpers.getField(actions, "mNoticeAnimator"));
        assertEquals(0f, ReflectionHelpers.<Float>getField(actions, "mNoticeRadiusFactor"), 0f);
    }

    @Test public void noUpdatesNoticeRestartsCleanlyAndCancelsOnRefreshNavigationAndDestroy() {
        FloatingActionButton button = root.findViewById(R.id.popular_updates);
        ColorStateList original = button.getBackgroundTintList();
        ReflectionHelpers.callInstanceMethod(scene, "showPopularNoUpdatesNotice");
        ReflectionHelpers.callInstanceMethod(scene, "showPopularNoUpdatesNotice");
        assertEquals(2.5f, ReflectionHelpers.<Float>getField(actions, "mNoticeRadiusFactor"), 0f);
        ReflectionHelpers.setField(scene, "mPopularRequestInFlight", true);
        update();
        assertSame(original, button.getBackgroundTintList());
        assertNull(ReflectionHelpers.getField(actions, "mNoticeAnimator"));
        ReflectionHelpers.setField(scene, "mPopularRequestInFlight", false);
        ReflectionHelpers.callInstanceMethod(scene, "showPopularNoUpdatesNotice");
        builder.setMode(ListUrlBuilder.MODE_NORMAL);
        update();
        assertSame(original, button.getBackgroundTintList());
        ReflectionHelpers.callInstanceMethod(scene, "showPopularNoUpdatesNotice");
        scene.onDestroyView();
        assertSame(original, button.getBackgroundTintList());
        assertNull(ReflectionHelpers.getField(actions, "mNoticeAnimator"));
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void iconAndBackgroundColorsMatchNeighboringFabsInAllThemes() {
        for (int theme : new int[]{R.style.AppTheme, R.style.AppTheme_Dark, R.style.AppTheme_Black}) {
            Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), theme);
            View themed = LayoutInflater.from(context).inflate(R.layout.scene_gallery_list, null);
            FabLayout themedMenu = themed.findViewById(R.id.fab_layout);
            FloatingActionButton menuPeer = themed.findViewById(R.id.downloaded_only_fab);
            FloatingActionButton outsidePeer = themedMenu.getPrimaryFab();
            outsidePeer.setImageDrawable(new com.hippo.drawable.AddDeleteDrawable(context,
                    context.getColor(R.color.primary_drawable_dark)));
            for (int id : new int[]{R.id.popular_updates, R.id.popular_previous,
                    R.id.popular_updates_menu, R.id.popular_previous_menu}) {
                FloatingActionButton action = themed.findViewById(id);
                FloatingActionButton backgroundPeer = id == R.id.popular_updates || id == R.id.popular_previous
                        ? outsidePeer : menuPeer;
                assertEquals(backgroundPeer.getBackgroundTintList().getDefaultColor(),
                        action.getBackgroundTintList().getDefaultColor());
                assertEquals(iconColor(backgroundPeer), iconColor(action));
                assertNotNull(action.getContentDescription());
                if (id == R.id.popular_updates || id == R.id.popular_updates_menu) {
                    com.hippo.drawable.GalleryCountDrawable count = new com.hippo.drawable.GalleryCountDrawable(context);
                    count.setCounts(12, 25);
                    action.setImageDrawable(count);
                    assertEquals(iconColor(backgroundPeer), iconColor(action));
                }
            }
        }
    }

    @Test public void iconMenuActionsFitAndDispatchWithOtherRoundFabs() {
        ReflectionHelpers.callInstanceMethod(scene, "showNormalFabs", ClassParameter.from(FabLayout.class, menu));
        menu.setExpanded(true, false);
        int width = (int) (360 * root.getResources().getDisplayMetrics().density);
        int height = (int) (640 * root.getResources().getDisplayMetrics().density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        View updates = root.findViewById(R.id.popular_updates_menu);
        View previous = root.findViewById(R.id.popular_previous_menu);
        assertTrue(updates.getTop() >= 0);
        assertTrue(updates.getBottom() <= previous.getTop());
        assertTrue(previous.getBottom() <= menu.getPrimaryFab().getTop());
        final int[] clicked = {-1};
        menu.setOnClickFabListener(new FabLayout.OnClickFabListener() {
            @Override public void onClickPrimaryFab(FabLayout view, FloatingActionButton fab) {}
            @Override public void onClickSecondaryFab(FabLayout view, FloatingActionButton fab, int position) {
                clicked[0] = position;
            }
        });
        updates.performClick();
        assertEquals(9, clicked[0]);
        previous.performClick();
        assertEquals(10, clicked[0]);
    }

    @Test public void staleSubscriptionRefreshFollowsPrimaryFabAndReservesItsOwnSlot() {
        View refresh = root.findViewById(R.id.subscription_refresh_active);
        for (int mode : new int[]{ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION,
                ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION}) {
            builder.setMode(mode);
            ReflectionHelpers.setField(scene, "mSubscriptionStaleResult", true);
            layout(600, 1200);
            update();
            settle();
            assertEquals(View.VISIBLE, refresh.getVisibility());
            assertCenterAbove(refresh, menu.getPrimaryFab(), 1);

            ReflectionHelpers.callInstanceMethod(scene, "hideActionFab");
            assertEquals(View.VISIBLE, refresh.getVisibility());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
            assertEquals(menu.getPrimaryFab().getScaleY(), refresh.getScaleY(), 0.08f);
            settle();
            assertEquals(View.INVISIBLE, refresh.getVisibility());
            ReflectionHelpers.callInstanceMethod(scene, "showActionFab");
            settle();
            assertEquals(View.VISIBLE, refresh.getVisibility());
            assertCenterAbove(refresh, menu.getPrimaryFab(), 1);

            menu.setExpanded(true, false);
            settle();
            assertEquals(View.INVISIBLE, refresh.getVisibility());
            menu.setExpanded(false, false);
            settle();
            assertEquals(View.VISIBLE, refresh.getVisibility());

            ReflectionHelpers.setField(scene, "mRatingSortMode", true);
            ReflectionHelpers.setField(scene, "mDownloadedOnlyMode", true);
            update();
            settle();
            assertCenterAbove(root.findViewById(R.id.rating_sort_active), menu.getPrimaryFab(), 1);
            assertCenterAbove(root.findViewById(R.id.downloaded_only_active), menu.getPrimaryFab(), 2);
            assertCenterAbove(refresh, menu.getPrimaryFab(), 3);
            ReflectionHelpers.setField(scene, "mMultiSelectMode", true);
            update();
            settle();
            assertEquals(View.INVISIBLE, refresh.getVisibility());
            ReflectionHelpers.setField(scene, "mMultiSelectMode", false);
            ReflectionHelpers.setField(scene, "mRatingSortMode", false);
            ReflectionHelpers.setField(scene, "mDownloadedOnlyMode", false);
        }
        ReflectionHelpers.setField(scene, "mSubscriptionStaleResult", false);
        update();
        settle();
        assertEquals(View.INVISIBLE, refresh.getVisibility());
        ReflectionHelpers.setField(scene, "mSubscriptionStaleResult", true);
        builder.setMode(ListUrlBuilder.MODE_NORMAL);
        update();
        settle();
        assertEquals(View.INVISIBLE, refresh.getVisibility());
    }

    @Test public void independentActiveModesShareTheStackAndRemainVisibleWhenScrolling() {
        bind("mRatingSortFab", R.id.rating_sort_fab);
        ReflectionHelpers.setField(scene, "mRatingSortMode", true);
        ReflectionHelpers.setField(scene, "mDownloadedOnlyMode", true);
        layout(600, 1200);
        update();
        settle();
        View sort = root.findViewById(R.id.rating_sort_active);
        View downloaded = root.findViewById(R.id.downloaded_only_active);
        assertEquals(View.VISIBLE, sort.getVisibility());
        assertEquals(View.VISIBLE, downloaded.getVisibility());
        assertCenterAbove(sort, menu.getPrimaryFab(), 1);
        assertCenterAbove(downloaded, menu.getPrimaryFab(), 2);
        assertCenterAbove(root.findViewById(R.id.popular_previous), menu.getPrimaryFab(), 3);
        assertCenterAbove(root.findViewById(R.id.popular_updates), menu.getPrimaryFab(), 4);
        ReflectionHelpers.callInstanceMethod(scene, "hideActionFab");
        settle();
        assertCenterAbove(sort, menu.getPrimaryFab(), 0);
        assertCenterAbove(downloaded, menu.getPrimaryFab(), 1);
        menu.setExpanded(true, false);
        settle();
        assertEquals(View.INVISIBLE, root.findViewById(R.id.gallery_list_actions).getVisibility());
        menu.setExpanded(false, false);
        settle();
        assertEquals(View.VISIBLE, root.findViewById(R.id.gallery_list_actions).getVisibility());
    }

    @Test public void ratingSortShortcutSharesSlotsWithPopularAndSubscriptionActions() {
        bindScrollableGalleryList();
        layout(600, 1200);
        update();
        settle();
        View shortcut = root.findViewById(R.id.rating_sort_active);
        assertEquals(View.VISIBLE, shortcut.getVisibility());
        assertCenterAbove(shortcut, menu.getPrimaryFab(), 1);
        assertCenterAbove(root.findViewById(R.id.popular_previous), menu.getPrimaryFab(), 2);
        assertCenterAbove(root.findViewById(R.id.popular_updates), menu.getPrimaryFab(), 3);

        builder.setMode(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION);
        ReflectionHelpers.setField(scene, "mSubscriptionStaleResult", true);
        update();
        settle();
        View refresh = root.findViewById(R.id.subscription_refresh_active);
        assertEquals(View.VISIBLE, refresh.getVisibility());
        assertCenterAbove(shortcut, menu.getPrimaryFab(), 1);
        assertCenterAbove(refresh, menu.getPrimaryFab(), 2);
    }

    @Test public void retiringRatingShortcutDoesNotMoveOtherButtonsThroughItsSlot() {
        RecyclerView recycler = bindScrollableGalleryList();
        ReflectionHelpers.setField(scene, "mHideActionFabSlop", 1000);
        ReflectionHelpers.setField(scene, "mPopularViewMode", PopularGalleryHistory.PREVIOUS);
        ReflectionHelpers.setField(scene, "mDownloadedOnlyMode", true);
        // There are only three rows above the bottom edge at this height.
        layout(600, Math.round(255 * root.getResources().getDisplayMetrics().density));
        update();
        settle();
        View shortcut = root.findViewById(R.id.rating_sort_active);
        View downloaded = root.findViewById(R.id.downloaded_only_active);
        View previous = root.findViewById(R.id.popular_previous);
        assertEquals(View.VISIBLE, shortcut.getVisibility());
        assertTrue(previous.getX() < downloaded.getX());

        recycler.scrollBy(0, recycler.getHeight());
        ValueAnimator retirement = ReflectionHelpers.getField(actions, "mTransition");
        retirement.setCurrentPlayTime(150L);
        assertTrue(shortcut.getScaleY() > 0f && shortcut.getScaleY() < 1f);
        assertCenterAbove(downloaded, menu.getPrimaryFab(), 2);
        assertNonOverlappingDuringTransition();
        assertEquals(View.INVISIBLE, shortcut.getVisibility());
        assertCenterAbove(downloaded, menu.getPrimaryFab(), 1);
        assertCenterAbove(previous, menu.getPrimaryFab(), 2);

        ReflectionHelpers.callInstanceMethod(scene, "hideActionFab");
        assertNonOverlappingDuringTransition();
        assertCenterAbove(downloaded, menu.getPrimaryFab(), 0);
        ReflectionHelpers.callInstanceMethod(scene, "showActionFab");
        assertNonOverlappingDuringTransition();
        assertCenterAbove(previous, menu.getPrimaryFab(), 2);
    }

    @Test public void collapsedMenuWaitsForItsButtonsToLeaveBeforeShowingOutsideActions() {
        bindScrollableGalleryList();
        layout(600, 1200);
        update();
        settle();
        scene.onClickPrimaryFab(menu, menu.getPrimaryFab());
        settle();
        View shortcut = root.findViewById(R.id.rating_sort_active);
        View inside = root.findViewById(R.id.rating_sort_fab);
        assertEquals(View.INVISIBLE, shortcut.getVisibility());
        assertEquals(View.VISIBLE, inside.getVisibility());
        menu.setExpanded(false);
        assertEquals(View.VISIBLE, inside.getVisibility());
        ValueAnimator handoff = ReflectionHelpers.getField(actions, "mTransition");
        assertEquals(600L, handoff.getDuration());
        handoff.setCurrentPlayTime(150L);
        assertEquals(View.INVISIBLE, shortcut.getVisibility());
        settle();
        assertEquals(View.INVISIBLE, inside.getVisibility());
        assertEquals(View.VISIBLE, shortcut.getVisibility());
    }

    @Test public void returningFromSearchRevealsTheShortcutAfterSearchButtonsLeave() {
        bindScrollableGalleryList();
        builder.setMode(ListUrlBuilder.MODE_NORMAL);
        bind("mSearchFab", R.id.search_fab);
        bind("mSearchOptionsFab", R.id.search_options_fab);
        layout(600, 1200);
        update();
        settle();
        View shortcut = root.findViewById(R.id.rating_sort_active);
        View search = root.findViewById(R.id.search_fab);
        View searchOptions = root.findViewById(R.id.search_options_fab);
        ReflectionHelpers.setField(scene, "mState", GalleryListScene.STATE_SIMPLE_SEARCH);
        ReflectionHelpers.callInstanceMethod(scene, "selectSearchFab", ClassParameter.from(boolean.class, false));
        update();
        assertEquals(View.INVISIBLE, shortcut.getVisibility());
        assertEquals(View.VISIBLE, search.getVisibility());
        assertEquals(View.VISIBLE, searchOptions.getVisibility());

        ReflectionHelpers.setField(scene, "mState", GalleryListScene.STATE_NORMAL);
        ReflectionHelpers.callInstanceMethod(scene, "selectActionFab", ClassParameter.from(boolean.class, true));
        ValueAnimator handoff = ReflectionHelpers.getField(actions, "mTransition");
        handoff.setCurrentPlayTime(150L);
        assertEquals(View.INVISIBLE, shortcut.getVisibility());
        settle();
        assertEquals(View.INVISIBLE, search.getVisibility());
        assertEquals(View.INVISIBLE, searchOptions.getVisibility());
        assertEquals(View.VISIBLE, shortcut.getVisibility());
    }

    @Test public void ratingSortShortcutFollowsThePrimaryFabAndReturnsWithinSeventyPercentOfTheViewport() {
        RecyclerView recycler = bindScrollableGalleryList();
        recycler.setPadding(0, 80, 0, 40);
        bind("mRatingSortFab", R.id.rating_sort_fab);
        View shortcut = root.findViewById(R.id.rating_sort_active);
        builder.setMode(ListUrlBuilder.MODE_NORMAL);
        layout(600, 1200);
        update();
        settle();
        assertEquals(View.VISIBLE, shortcut.getVisibility());
        assertEquals(root.getContext().getText(R.string.gallery_rating_sort), shortcut.getContentDescription());
        assertCenterAbove(shortcut, menu.getPrimaryFab(), 1);

        recycler.scrollBy(0, 20);
        settle();
        assertEquals(View.INVISIBLE, shortcut.getVisibility());
        assertEquals(View.INVISIBLE, menu.getPrimaryFab().getVisibility());
        recycler.scrollBy(0, -20);
        settle();
        assertEquals(View.VISIBLE, shortcut.getVisibility());

        int shortcutRange = (recycler.getHeight() - 120) * 70 / 100;
        recycler.scrollBy(0, shortcutRange + 21);
        settle();
        recycler.scrollBy(0, -20);
        settle();
        assertEquals(shortcutRange + 1, recycler.computeVerticalScrollOffset());
        assertEquals(View.VISIBLE, menu.getPrimaryFab().getVisibility());
        assertEquals(View.INVISIBLE, shortcut.getVisibility());

        recycler.scrollBy(0, -1);
        settle();
        assertEquals(shortcutRange, recycler.computeVerticalScrollOffset());
        assertEquals(View.VISIBLE, shortcut.getVisibility());
        recycler.scrollBy(0, 1);
        settle();
        assertEquals(View.INVISIBLE, shortcut.getVisibility());
        menu.setExpanded(true, false);
        assertEquals(View.VISIBLE, root.findViewById(R.id.rating_sort_fab).getVisibility());
        menu.setExpanded(false, false);
        settle();
        assertEquals(View.INVISIBLE, shortcut.getVisibility());
    }

    @Test public void ratingSortShortcutReevaluatesTheViewportAfterResizeAndSearchResultJump() {
        RecyclerView recycler = bindScrollableGalleryList();
        builder.setMode(ListUrlBuilder.MODE_NORMAL);
        builder.setKeyword("first search");
        layout(600, 1200);
        update();
        settle();
        View shortcut = root.findViewById(R.id.rating_sort_active);
        LayoutManagerUtils.scrollToPositionWithOffset(recycler.getLayoutManager(), 11, 0);
        layout(600, 1200);
        settle();
        assertEquals(660, recycler.computeVerticalScrollOffset());
        assertEquals(View.VISIBLE, shortcut.getVisibility());

        layout(600, 900);
        settle();
        assertEquals(View.INVISIBLE, shortcut.getVisibility());
        assertEquals(View.VISIBLE, menu.getPrimaryFab().getVisibility());

        builder.setKeyword("second search");
        LayoutManagerUtils.scrollToPositionWithOffset(recycler.getLayoutManager(), 0, 0);
        layout(600, 900);
        settle();
        assertEquals(View.VISIBLE, shortcut.getVisibility());
        LayoutManagerUtils.scrollToPositionWithOffset(recycler.getLayoutManager(), 11, 0);
        layout(600, 900);
        settle();
        assertEquals(View.INVISIBLE, shortcut.getVisibility());
    }

    @Test public void previousDismissalDoesNotPreventTheShortcutWhenPopularIsReady() {
        root.getContext().getSharedPreferences("popular-actions-test", Context.MODE_PRIVATE)
                .edit().putBoolean("guide_rating_sort_manual_dismissal", false).apply();
        bindScrollableGalleryList();
        ReflectionHelpers.setField(scene, "mPopularRequestInFlight", true);
        layout(600, 1200);
        update();
        settle();
        View shortcut = root.findViewById(R.id.rating_sort_active);
        assertEquals(View.INVISIBLE, shortcut.getVisibility());

        ReflectionHelpers.setField(scene, "mPopularRequestInFlight", false);
        ReflectionHelpers.setField(scene, "mPopularViewMode", PopularGalleryHistory.PREVIOUS);
        update();
        settle();
        assertEquals(View.VISIBLE, shortcut.getVisibility());
    }

    @Test public void activeRatingSortUsesPopularModeMovementAndMenuVisibilityAnimations() {
        bind("mRatingSortFab", R.id.rating_sort_fab);
        builder.setMode(ListUrlBuilder.MODE_NORMAL);
        ReflectionHelpers.setField(scene, "mRatingSortMode", true);
        layout(600, 1200);
        update();
        settle();
        View sort = root.findViewById(R.id.rating_sort_active);
        assertEquals(View.VISIBLE, sort.getVisibility());
        assertCenterAbove(sort, menu.getPrimaryFab(), 1);

        ReflectionHelpers.callInstanceMethod(scene, "hideActionFab");
        // The active button waits for the main FAB to leave, then slides into its position.
        assertCenterAbove(sort, menu.getPrimaryFab(), 1);
        ValueAnimator movement = ReflectionHelpers.getField(actions, "mTransition");
        assertNotNull(movement);
        assertEquals(600L, movement.getDuration());
        movement.setCurrentPlayTime(450L);
        float step = root.getResources().getDimension(R.dimen.fab_size)
                + root.getResources().getDimension(R.dimen.fab_layout_secondary_margin);
        float distance = menu.getPrimaryFab().getY() + menu.getPrimaryFab().getHeight() / 2f
                - sort.getY() - sort.getHeight() / 2f;
        assertTrue("Rating button must slide between the two FAB positions", distance > 0f && distance < step);
        movement.end();
        settle();
        assertCenterAbove(sort, menu.getPrimaryFab(), 0);

        ReflectionHelpers.callInstanceMethod(scene, "showActionFab");
        assertEquals(300L, menu.getPrimaryFab().animate().getStartDelay());
        settle();
        assertCenterAbove(sort, menu.getPrimaryFab(), 1);

        menu.setExpanded(true);
        assertEquals(View.VISIBLE, sort.getVisibility());
        settle();
        assertEquals(View.INVISIBLE, sort.getVisibility());
        menu.setExpanded(false, false);
        settle();
        assertEquals(View.VISIBLE, sort.getVisibility());
        assertEquals(1f, sort.getScaleY(), 0f);
        assertEquals(0f, sort.getRotation(), 0f);
        assertCenterAbove(sort, menu.getPrimaryFab(), 1);
        for (int i = 0; i < 3; i++) {
            menu.setExpanded(true);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80));
            menu.setExpanded(false);
        }
        settle();
        assertEquals(View.VISIBLE, sort.getVisibility());
        assertEquals(1f, sort.getScaleY(), 0f);
    }

    @Test public void expandedGalleryMenuWrapsOnShortScreensAndKeepsEveryActionClickable() {
        ReflectionHelpers.callInstanceMethod(scene, "showNormalFabs", ClassParameter.from(FabLayout.class, menu));
        menu.setWrapSecondaryFabs(true);
        menu.setExpanded(true, false);
        float density = root.getResources().getDisplayMetrics().density;
        layout((int) (640 * density), (int) (320 * density));
        boolean anotherColumn = false;
        for (int i = 0; i < menu.getSecondaryFabCount(); i++) {
            View button = menu.getSecondaryFabAt(i);
            if (button.getVisibility() != View.VISIBLE) continue;
            assertTrue("Action must fit above bottom edge", button.getBottom() <= menu.getHeight());
            assertTrue("Action must fit below top edge", button.getTop() >= 0);
            assertTrue(button.getLeft() >= 0);
            anotherColumn |= button.getLeft() + button.getWidth() / 2f
                    < menu.getPrimaryFab().getLeft() + menu.getPrimaryFab().getWidth() / 2f
                    - root.getResources().getDimension(R.dimen.fab_min_size);
        }
        assertTrue(anotherColumn);
        int[] clicked = {-1};
        menu.setOnClickFabListener(new FabLayout.OnClickFabListener() {
            @Override public void onClickPrimaryFab(FabLayout view, FloatingActionButton fab) {}
            @Override public void onClickSecondaryFab(FabLayout view, FloatingActionButton fab, int position) {
                clicked[0] = position;
            }
        });
        root.findViewById(R.id.rating_sort_fab).performClick();
        assertEquals(11, clicked[0]);
    }

    private void bind(String field, int id) {
        ReflectionHelpers.setField(scene, field, root.findViewById(id));
    }
    private void update() {
        ReflectionHelpers.callInstanceMethod(scene, "updatePopularActions");
    }

    private RecyclerView bindScrollableGalleryList() {
        RecyclerView recycler = root.findViewById(R.id.recycler_view);
        recycler.setLayoutManager(new AutoStaggeredGridLayoutManager(0, StaggeredGridLayoutManager.VERTICAL));
        recycler.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
                View row = new View(parent.getContext());
                row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 60));
                return new RecyclerView.ViewHolder(row) {};
            }

            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {}

            @Override public int getItemCount() {
                return 40;
            }
        });
        recycler.addOnScrollListener(ReflectionHelpers.getField(scene, "mOnScrollListener"));
        recycler.addOnLayoutChangeListener(ReflectionHelpers.getField(scene, "mRatingSortShortcutLayoutListener"));
        ReflectionHelpers.setField(scene, "mHideActionFabSlop", 20);
        return recycler;
    }

    private void layout(int width, int height) {
        FrameLayout.LayoutParams size = new FrameLayout.LayoutParams(width, height);
        if (!root.isAttachedToWindow()) {
            Robolectric.buildActivity(Activity.class).setup().get().setContentView(root, size);
        } else {
            root.setLayoutParams(size);
        }
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }
    private void settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000));
        actions.getViewTreeObserver().dispatchOnPreDraw();
    }
    private void assertCenterAbove(View button, View anchor, int slots) {
        float step = root.getResources().getDimension(R.dimen.fab_size)
                + root.getResources().getDimension(R.dimen.fab_layout_secondary_margin);
        assertEquals(anchor.getX() + anchor.getWidth() / 2f,
                button.getX() + button.getWidth() / 2f, 0.5f);
        assertEquals(anchor.getY() + anchor.getHeight() / 2f - slots * step,
                button.getY() + button.getHeight() / 2f, 0.5f);
    }
    private int iconColor(FloatingActionButton button) {
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(48, 48,
                android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.drawable.Drawable icon = button.getDrawable();
        icon.setBounds(0, 0, 48, 48);
        icon.draw(new android.graphics.Canvas(bitmap));
        for (int y = 0; y < 48; y++) {
            for (int x = 0; x < 48; x++) {
                int color = bitmap.getPixel(x, y);
                if (android.graphics.Color.alpha(color) == 255) return color;
            }
        }
        throw new AssertionError("Icon did not draw any solid pixels");
    }
}
