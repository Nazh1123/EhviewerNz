package com.hippo.scene;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.ui.MainActivity;
import com.hippo.ehviewer.ui.scene.download.DownloadsScene;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = StageActivityNavigationTest.TestApplication.class, sdk = 28,
        manifest = Config.NONE)
public class StageActivityNavigationTest {
    private ActivityController<TestStage> controller;
    private TestStage activity;

    public static class TestApplication extends EhApplication {
        @Override public void onCreate() {
            // Exercise real Fragment lifecycles without starting production services.
            Settings.initialize(this);
        }
    }

    public static class TestStage extends StageActivity {
        @Override protected int getThemeResId(int theme) { return R.style.AppTheme_Main; }
        @Override public int getContainerViewId() { return R.id.content; }
        @Override protected Announcer getLaunchAnnouncer() { return null; }
        @Override protected void onCreate2(@Nullable Bundle savedInstanceState) {
            FrameLayout container = new FrameLayout(this);
            container.setId(getContainerViewId());
            setContentView(container);
        }
    }

    public static class TestScene extends SceneFragment {
        int viewCreations;
        Bundle lastArguments;

        @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
                @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
            viewCreations++;
            return new FrameLayout(requireContext());
        }

        @Override public void onNewArguments(@NonNull Bundle args) { lastArguments = args; }
    }

    public static class RootScene extends TestScene {}
    public static class DownloadScene extends TestScene {}
    public static class LabelScene extends DownloadScene {}
    public static class DetailScene extends TestScene {}
    public static class ListScene extends TestScene {}
    public static class SingleTaskScene extends TestScene {}

    @Before public void setUp() {
        StageActivity.registerLaunchMode(RootScene.class, SceneFragment.LAUNCH_MODE_STANDARD);
        StageActivity.registerLaunchMode(DownloadScene.class, SceneFragment.LAUNCH_MODE_SHARED);
        StageActivity.registerLaunchMode(LabelScene.class, SceneFragment.LAUNCH_MODE_STANDARD);
        StageActivity.registerLaunchMode(DetailScene.class, SceneFragment.LAUNCH_MODE_STANDARD);
        StageActivity.registerLaunchMode(ListScene.class, SceneFragment.LAUNCH_MODE_SINGLE_TOP);
        StageActivity.registerLaunchMode(SingleTaskScene.class, SceneFragment.LAUNCH_MODE_SINGLE_TASK);
        controller = Robolectric.buildActivity(TestStage.class).setup();
        activity = controller.get();
    }

    @After public void tearDown() { controller.close(); }

    @Test public void downloadsUseSharedModeInProduction() throws Exception {
        Class.forName(MainActivity.class.getName());
        assertEquals(SceneFragment.LAUNCH_MODE_SHARED,
                activity.getSceneLaunchMode(DownloadsScene.class));
    }

    @Test public void revisitingDownloadsPreservesIntermediatePagesAndReusesTheSameInstance() {
        SceneFragment root = open(RootScene.class);
        SceneFragment downloads = open(DownloadScene.class);
        SceneFragment detail = open(DetailScene.class);
        SceneFragment list = open(ListScene.class);
        assertNull(downloads.getView());

        assertSame(downloads, open(DownloadScene.class));
        assertEquals(5, activity.getSceneCount());
        assertEquals(4, downloads.getStackIndex());
        assertEquals(2, detail.getStackIndex());
        assertEquals(3, list.getStackIndex());
        assertNull(list.getView());
        assertNotNull(downloads.getView());
        assertEquals(1, activity.getSupportFragmentManager().getFragments().size());

        // A third visit merges the old placeholders and releases the old path.
        SceneFragment nextDetail = open(DetailScene.class);
        assertSame(downloads, open(DownloadScene.class));
        assertEquals(4, activity.getSceneCount());
        assertNull(detail.getActivity());
        assertNull(list.getActivity());
        backTo(nextDetail);
        backTo(downloads);
        backTo(root);
        assertNull(downloads.getActivity());
    }

    @Test public void reopeningTheTopSceneKeepsItsViewAndDeliversArguments() {
        open(RootScene.class);
        TestScene downloads = (TestScene) open(DownloadScene.class);
        View view = downloads.getView();
        Bundle args = new Bundle();
        args.putString("label", "saved-label");
        activity.startScene(new Announcer(DownloadScene.class).setArgs(args));
        settle();

        assertSame(downloads, activity.getTopScene());
        assertSame(view, downloads.getView());
        assertSame(args, downloads.lastArguments);
        assertEquals(1, downloads.viewCreations);
        assertEquals(2, activity.getSceneCount());
    }

    @Test public void sharedPlaceholdersAndAnimationOrderSurviveActivityRecreation() {
        SceneFragment root = open(RootScene.class);
        SceneFragment downloads = open(DownloadScene.class);
        SceneFragment detail = open(DetailScene.class);
        open(DownloadScene.class);
        SceneFragment list = open(ListScene.class);
        String rootTag = root.getTag();
        String downloadTag = downloads.getTag();
        String detailTag = detail.getTag();
        String listTag = list.getTag();
        assertViewOrder(root, detail, downloads, list);

        controller.recreate();
        activity = controller.get();
        settle();
        SceneFragment restoredDownloads = activity.findSceneByTag(downloadTag);
        SceneFragment restoredDetail = activity.findSceneByTag(detailTag);
        SceneFragment restoredList = activity.findSceneByTag(listTag);
        assertSame(restoredList, activity.getTopScene());
        assertSame(restoredDownloads, open(DownloadScene.class));
        assertSame(restoredDownloads, activity.getTopScene());
        assertEquals(4, activity.getSceneCount());
        assertNull(restoredDetail.getActivity());
        assertViewOrder(activity.findSceneByTag(rootTag), restoredList, restoredDownloads);
        backTo(restoredList);
        backTo(restoredDownloads);
        backTo(activity.findSceneByTag(rootTag));
    }

    @Test public void downloadsAtTheBottomRemainAvailableAfterPoppingANewerVisit() {
        SceneFragment downloads = open(DownloadScene.class);
        SceneFragment detail = open(DetailScene.class);
        assertSame(downloads, open(DownloadScene.class));
        assertEquals(3, activity.getSceneCount());
        assertEquals(1, detail.getStackIndex());
        backTo(detail);
        backTo(downloads);
        activity.onBackPressed();
        assertTrue(activity.isFinishing());
    }

    @Test public void specializedLabelPageIsNotReusedAsTheMainDownloadsPage() {
        SceneFragment label = open(LabelScene.class);
        SceneFragment downloads = open(DownloadScene.class);
        assertEquals(DownloadScene.class, downloads.getClass());
        assertNotSame(label, downloads);
        assertEquals(2, activity.getSceneCount());
        backTo(label);
    }

    @Test public void singleTaskStillClearsPagesAndSingleTopStillReusesOnlyTheTop() {
        open(RootScene.class);
        SceneFragment singleTask = open(SingleTaskScene.class);
        SceneFragment list = open(ListScene.class);
        assertSame(list, open(ListScene.class));
        open(DetailScene.class);
        assertNotSame(list, open(ListScene.class));
        assertSame(singleTask, open(SingleTaskScene.class));
        assertEquals(2, activity.getSceneCount());
        assertNull(list.getActivity());
    }

    @Test public void singleTaskClearingKeepsASharedInstanceReferencedBelowTheDestination() {
        SceneFragment root = open(RootScene.class);
        SceneFragment downloads = open(DownloadScene.class);
        SceneFragment singleTask = open(SingleTaskScene.class);
        open(DetailScene.class);
        open(DownloadScene.class);
        assertSame(singleTask, open(SingleTaskScene.class));
        assertEquals(3, activity.getSceneCount());
        assertNotNull(downloads.getActivity());
        assertNull(downloads.getView());
        backTo(downloads);
        backTo(root);
    }

    @Test public void singleTaskClearingRemovesASharedInstanceWithNoRemainingPlaceholder() {
        open(RootScene.class);
        SceneFragment singleTask = open(SingleTaskScene.class);
        SceneFragment downloads = open(DownloadScene.class);
        open(DetailScene.class);
        open(DownloadScene.class);
        assertSame(singleTask, open(SingleTaskScene.class));
        assertEquals(2, activity.getSceneCount());
        assertNull(downloads.getActivity());
        assertNotSame(downloads, open(DownloadScene.class));
    }

    @Test public void resettingTheRootRemovesAllPlaceholdersAndTheSharedInstance() {
        open(RootScene.class);
        SceneFragment downloads = open(DownloadScene.class);
        open(DetailScene.class);
        open(DownloadScene.class);
        activity.startSceneFirstly(new Announcer(ListScene.class));
        settle();
        assertEquals(1, activity.getSceneCount());
        assertEquals(ListScene.class, activity.getTopSceneClass());
        assertNull(downloads.getActivity());
        assertNotSame(downloads, open(DownloadScene.class));
    }

    @Test public void resettingTheRootToDownloadsRetainsExactlyOneSharedInstance() {
        open(RootScene.class);
        SceneFragment downloads = open(DownloadScene.class);
        open(DetailScene.class);
        open(DownloadScene.class);
        activity.startSceneFirstly(new Announcer(DownloadScene.class));
        settle();
        assertEquals(1, activity.getSceneCount());
        assertSame(downloads, activity.getTopScene());
        assertNotNull(downloads.getView());
    }

    @Test public void removingAnIntermediateSceneCanLeaveAdjacentPlaceholders() {
        SceneFragment root = open(RootScene.class);
        SceneFragment downloads = open(DownloadScene.class);
        SceneFragment detail = open(DetailScene.class);
        open(DownloadScene.class);
        activity.finishScene(detail);
        settle();
        assertEquals(3, activity.getSceneCount());
        backTo(downloads);
        assertEquals(2, activity.getSceneCount());
        backTo(root);
    }

    @Test public void fourthAndLaterVisitsContinueToTrimOnlyTheOlderInterval() {
        SceneFragment root = open(RootScene.class);
        SceneFragment beforeDownloads = open(ListScene.class);
        TestScene downloads = (TestScene) open(DownloadScene.class);
        Bundle latestState = new Bundle();
        latestState.putString("label", "latest");
        downloads.onNewArguments(latestState);
        SceneFragment oldDetail = open(DetailScene.class);
        open(DownloadScene.class);

        for (int visit = 3; visit <= 6; visit++) {
            SceneFragment latestDetail = open(DetailScene.class);
            assertSame(downloads, open(DownloadScene.class));
            assertEquals(5, activity.getSceneCount());
            assertNull(oldDetail.getActivity());
            assertSame(latestState, downloads.lastArguments);
            assertNotNull(beforeDownloads.getActivity());
            oldDetail = latestDetail;
        }
        backTo(oldDetail);
        backTo(downloads);
        backTo(beforeDownloads);
        backTo(root);
    }

    private SceneFragment open(Class<? extends SceneFragment> sceneClass) {
        activity.startScene(new Announcer(sceneClass));
        settle();
        return activity.getTopScene();
    }

    private void backTo(SceneFragment expected) {
        activity.onBackPressed();
        settle();
        assertSame(expected, activity.getTopScene());
        assertNotNull(expected.getView());
    }

    private void settle() {
        activity.getSupportFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS);
    }

    private void assertViewOrder(SceneFragment... scenes) {
        List<View> views = new ArrayList<>();
        for (SceneFragment scene : scenes) {
            View view = new View(activity);
            view.setTag(R.id.fragment_tag, scene.getTag());
            views.add(view);
        }
        List<View> expected = new ArrayList<>(views);
        java.util.Collections.reverse(views);
        activity.sortSceneViews(views);
        assertEquals(expected, views);
    }
}
