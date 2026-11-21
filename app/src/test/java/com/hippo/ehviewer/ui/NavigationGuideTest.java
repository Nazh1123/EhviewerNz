package com.hippo.ehviewer.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Spinner;

import androidx.fragment.app.FragmentActivity;

import com.github.amlcurran.showcaseview.ShowcaseView;
import com.google.android.material.navigation.NavigationView;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.EhTopListDetail;
import com.hippo.ehviewer.client.data.topList.TopListInfo;
import com.hippo.ehviewer.client.data.topList.TopListItem;
import com.hippo.ehviewer.client.data.topList.TopListItemArray;
import com.hippo.ehviewer.ui.scene.topList.EhTopListScene;
import com.hippo.ehviewer.widget.ShowcaseGuide;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;

@RunWith(RobolectricTestRunner.class)
@Config(application = NavigationGuideTest.TestApplication.class, sdk = 28,
        manifest = Config.NONE, qualifiers = "zh-rCN-w480dp-h800dp")
public class NavigationGuideTest {
    private FragmentActivity activity;
    private FrameLayout host;

    @Before public void setUp() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.resetGuides();
        activity = Robolectric.buildActivity(FragmentActivity.class).setup().get();
        activity.setTheme(R.style.AppTheme_Main);
        host = new FrameLayout(activity);
        host.setId(View.generateViewId());
        activity.setContentView(host);
    }

    @Test public void drawerWaitsForPopularAndUsesTheStandardConfirmation() {
        NavigationView navigation = new NavigationView(activity);
        navigation.inflateMenu(R.menu.nav_drawer_main);
        host.addView(navigation, new FrameLayout.LayoutParams(320, 700));
        navigation.getMenu().findItem(R.id.nav_whats_hot).setVisible(false);

        ShowcaseGuide guide = new ShowcaseGuide(activity, navigation,
                () -> ReflectionHelpers.callStaticMethod(MainActivity.class, "findPopularGuideTarget",
                        ReflectionHelpers.ClassParameter.from(NavigationView.class, navigation)),
                R.string.whats_hot, "热门操作", Settings::finishGuideDrawerLongPress);
        render();
        assertNull(findShowcase());
        assertTrue(Settings.getGuideDrawerLongPress());

        navigation.getMenu().findItem(R.id.nav_whats_hot).setVisible(true);
        render();
        assertTrue(guide.isShowing());
        confirmShowcase();
        assertFalse(Settings.getGuideDrawerLongPress());
        assertFalse(guide.isShowing());
    }

    @Test public void yesterdayGuideWaitsForClickableGalleryRankingsAndUsesTheStandardConfirmation() {
        EhTopListScene scene = new EhTopListScene();
        // Provide a local ranking result after the view exists, without issuing a request.
        ReflectionHelpers.setField(scene, "hasFirstRefresh", true);
        activity.getSupportFragmentManager().beginTransaction().add(host.getId(), scene).commitNow();
        render();
        assertNull(findShowcase());
        assertTrue(Settings.getGuideTopListYesterday());

        EhTopListDetail detail = new EhTopListDetail();
        detail.galleryTopListInfo = rankings(EhTopListDetail.ListType.GALLERY);
        detail.uploaderTopListInfo = rankings(EhTopListDetail.ListType.UPLOADER);
        ReflectionHelpers.callInstanceMethod(scene, "onGetEhTopListDetailSuccess",
                ReflectionHelpers.ClassParameter.from(EhTopListDetail.class, detail),
                ReflectionHelpers.ClassParameter.from(int.class, 0));
        Spinner spinner = host.findViewById(R.id.top_list_spinner);
        spinner.setSelection(1);
        render();
        assertNull(findShowcase());
        assertTrue(Settings.getGuideTopListYesterday());
        spinner.setSelection(0);
        render();
        confirmShowcase();
        assertFalse(Settings.getGuideTopListYesterday());
        assertNull(findShowcase());
    }

    @Test public void resetMakesBothGuidesAvailableAgain() {
        Settings.finishGuideDrawerLongPress();
        Settings.finishGuideTopListYesterday();
        Settings.resetGuides();
        assertTrue(Settings.getGuideDrawerLongPress());
        assertTrue(Settings.getGuideTopListYesterday());
    }

    private ShowcaseView findShowcase() {
        ViewGroup content = activity.findViewById(android.R.id.content);
        for (int i = 0; i < content.getChildCount(); i++) {
            if (content.getChildAt(i) instanceof ShowcaseView guide) {
                return guide;
            }
        }
        return null;
    }

    private void confirmShowcase() {
        ShowcaseView guide = findShowcase();
        assertNotNull(guide);
        Button button = (Button) guide.getChildAt(0);
        assertEquals(activity.getString(R.string.get_it), button.getText().toString());
        assertTrue(button.performClick());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    }

    private void render() {
        for (int pass = 0; pass < 3; pass++) {
            host.measure(View.MeasureSpec.makeMeasureSpec(460, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(740, View.MeasureSpec.EXACTLY));
            host.layout(0, 0, 460, 740);
            host.getViewTreeObserver().dispatchOnPreDraw();
        }
    }

    private static TopListInfo rankings(EhTopListDetail.ListType type) {
        TopListItemArray emptyList = new TopListItemArray();
        emptyList.itemArray = new TopListItem[0];
        TopListInfo info = new TopListInfo();
        info.type = type;
        info.yesterdayTopList = emptyList;
        info.pastMonthTopList = emptyList;
        info.pastYearTopList = emptyList;
        info.allTimeTopList = emptyList;
        return info;
    }

    public static class TestApplication extends EhApplication {
        @Override public void onCreate() {
            // Keep the guides independent of application services and networking.
        }
    }
}
