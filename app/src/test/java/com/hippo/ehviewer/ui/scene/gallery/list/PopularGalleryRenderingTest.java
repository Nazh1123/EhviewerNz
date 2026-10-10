package com.hippo.ehviewer.ui.scene.gallery.list;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.conaco.Conaco;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.FavouriteStatusRouter;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.client.parser.GalleryListParser;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.lib.image.Image;
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
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, shadows = {
        PopularGalleryRenderingTest.AppShadow.class, PopularGalleryRenderingTest.DownloadShadow.class,
        PopularGalleryRenderingTest.ImageShadow.class})
public class PopularGalleryRenderingTest {
    private static FavouriteStatusRouter router;
    private static DownloadManager downloads;
    private TestScene scene;
    private ContentLayout content;
    private GalleryListScene.GalleryListHelper helper;
    private GalleryAdapterNew adapter;
    private ListUrlBuilder builder;
    private RecyclerView recycler;

    @Before public void setUp() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre",
                app.getSharedPreferences("popular-render-test", Context.MODE_PRIVATE));
        router = new FavouriteStatusRouter();
        downloads = Shadow.newInstanceOf(DownloadManager.class);
        Context context = new ContextThemeWrapper(app, R.style.AppTheme);
        scene = new TestScene(context);
        content = new ContentLayout(context);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setContentView(content);
        recycler = content.getRecyclerView();
        builder = new ListUrlBuilder();
        builder.setMode(ListUrlBuilder.MODE_WHATS_HOT);
        ReflectionHelpers.setField(scene, "mUrlBuilder", builder);
        ReflectionHelpers.setField(scene, "mRecyclerView", recycler);
        ReflectionHelpers.setField(scene, "executorService", Executors.newSingleThreadExecutor());
        helper = scene.new GalleryListHelper();
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
        View actions = LayoutInflater.from(scene.getContext()).inflate(R.layout.scene_gallery_list, null);
        FabLayout menu = actions.findViewById(R.id.fab_layout);
        menu.setExpanded(false, false);
        ReflectionHelpers.setField(scene, "mFabLayout", menu);
        ReflectionHelpers.setField(scene, "mPopularActions", actions.findViewById(R.id.popular_actions));
        ReflectionHelpers.setField(scene, "mPopularUpdates", actions.findViewById(R.id.popular_updates));
        ReflectionHelpers.setField(scene, "mPopularPrevious", actions.findViewById(R.id.popular_previous));
        ReflectionHelpers.setField(scene, "mPopularUpdatesMenu", actions.findViewById(R.id.popular_updates_menu));
        ReflectionHelpers.setField(scene, "mPopularPreviousMenu", actions.findViewById(R.id.popular_previous_menu));
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

    private static List<GalleryInfo> galleries(long... gids) {
        List<GalleryInfo> result = new ArrayList<>();
        for (long gid : gids) {
            GalleryInfo gallery = new GalleryInfo();
            gallery.gid = gid;
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
        TestScene(Context context) { this.context = context; }
        @Override public Context getContext() { return context; }
        @Override public Context getEHContext() { return context; }
        @Override public void showTip(int id, int length) { lastTip = id; }
    }

    @Implements(EhApplication.class)
    public static class AppShadow extends org.robolectric.shadows.ShadowApplication {
        @Implementation protected static DownloadManager getDownloadManager(Context context) { return downloads; }
        @Implementation protected static FavouriteStatusRouter getFavouriteStatusRouter() { return router; }
        @Implementation protected static Conaco<Image> getConaco(Context context) { return null; }
    }
    @Implements(DownloadManager.class)
    public static class DownloadShadow {
        @Implementation protected boolean containDownloadInfo(long gid) { return false; }
        @Implementation protected boolean hasOlderGalleryVersion(long firstGid, long gid) { return false; }
    }
    @Implements(LoadImageViewNew.class)
    public static class ImageShadow extends org.robolectric.shadows.ShadowView {
        @Implementation protected void load(String key, String url, boolean network) {}
        @Implementation protected void unload() {}
    }
}
