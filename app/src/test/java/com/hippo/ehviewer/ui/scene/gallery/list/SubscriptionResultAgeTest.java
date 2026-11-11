package com.hippo.ehviewer.ui.scene.gallery.list;

import android.app.Application;
import android.content.res.Resources;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, qualifiers = "zh-rCN", manifest = Config.NONE)
public class SubscriptionResultAgeTest {
    @Test @Config(qualifiers = "en")
    public void englishSubscriptionTitlesUseShortNamesAndTimeUnits() {
        Resources resources = RuntimeEnvironment.getApplication().getResources();
        assertEquals("Global Subs - 12m ago", SubscriptionResultAge.title(resources,
                resources.getString(com.hippo.ehviewer.R.string.subscription_global_short), 720_000L));
        assertEquals("Bookmarks - 1.5h ago", SubscriptionResultAge.title(resources,
                resources.getString(com.hippo.ehviewer.R.string.subscription_bookmarks_short), 5_400_000L));
    }
    @Test public void elapsedTimeUsesInclusiveUnitBoundariesAndAtMostOneDecimalForHours() {
        Resources resources = RuntimeEnvironment.getApplication().getResources();
        String title = "全局订阅";
        assertEquals("全局订阅 - 0 秒前", SubscriptionResultAge.title(resources, title, -1L));
        assertEquals("全局订阅 - 36 秒前", SubscriptionResultAge.title(resources, title, 36_000L));
        assertEquals("全局订阅 - 60 秒前", SubscriptionResultAge.title(resources, title, 60_000L));
        assertEquals("全局订阅 - 1 分前", SubscriptionResultAge.title(resources, title, 60_001L));
        assertEquals("全局订阅 - 60 分前", SubscriptionResultAge.title(resources, title, 3_600_000L));
        assertEquals("全局订阅 - 1 时前", SubscriptionResultAge.title(resources, title, 3_600_001L));
        assertEquals("全局订阅 - 1.1 时前", SubscriptionResultAge.title(resources, title, 3_780_000L));
        assertEquals("全局订阅 - 1.5 时前", SubscriptionResultAge.title(resources, title, 5_400_000L));
        assertEquals("书签订阅 - 24 时前", SubscriptionResultAge.title(resources, "书签订阅", 86_400_000L));
    }
}
