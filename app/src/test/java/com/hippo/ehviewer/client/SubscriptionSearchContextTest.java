package com.hippo.ehviewer.client;

import android.app.Application;
import android.content.Context;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.dao.Filter;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import okhttp3.Cookie;
import okhttp3.HttpUrl;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, shadows = {
        SubscriptionSearchContextTest.AppShadow.class,
        SubscriptionSearchContextTest.CookieStoreShadow.class,
        SubscriptionSearchContextTest.DbShadow.class})
public class SubscriptionSearchContextTest {
    private static final List<Cookie> cookies = new ArrayList<>();
    private static EhCookieStore store;

    @Before public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("context-test", Context.MODE_PRIVATE).edit().clear().commit();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre",
                context.getSharedPreferences("context-test", Context.MODE_PRIVATE));
        ReflectionHelpers.setStaticField(Settings.class, "sEhConfig", new EhConfig());
        ReflectionHelpers.setStaticField(EhFilter.class, "sInstance", null);
        store = new EhCookieStore(context);
        cookies.clear();
        cookie(EhCookieStore.KEY_IPD_MEMBER_ID, "123");
        cookie(EhCookieStore.KEY_IPD_PASS_HASH, "account-a");
    }

    private static void cookie(String name, String value) {
        cookies.removeIf(cookie -> cookie.name().equals(name));
        cookies.add(new Cookie.Builder().name(name).value(value).domain(EhUrl.DOMAIN_E).build());
    }

    @Test public void responseSessionCookiesDoNotInvalidateAnOtherwiseIdenticalSearch() {
        String before = SubscriptionSearchContext.key();
        cookie("sk", "new-session");
        cookie("__cf_bm", "new-challenge");
        assertEquals(before, SubscriptionSearchContext.key());
        cookie("sk", "rotated-session");
        cookies.removeIf(cookie -> cookie.name().equals("__cf_bm"));
        assertEquals(before, SubscriptionSearchContext.key());
    }

    @Test public void accountSiteSearchPreferencesAndFiltersStillInvalidateResults() {
        String before = SubscriptionSearchContext.key();
        cookie(EhCookieStore.KEY_IPD_PASS_HASH, "account-b");
        assertNotEquals(before, SubscriptionSearchContext.key());
        before = SubscriptionSearchContext.key();
        Settings.putGallerySite(EhUrl.SITE_EX);
        assertNotEquals(before, SubscriptionSearchContext.key());
        before = SubscriptionSearchContext.key();
        cookie("sp", "other-profile");
        assertNotEquals(before, SubscriptionSearchContext.key());
        before = SubscriptionSearchContext.key();
        Settings.putPersistentMinRatingHundredths(450);
        assertNotEquals(before, SubscriptionSearchContext.key());
        before = SubscriptionSearchContext.key();
        Filter filter = new Filter();
        filter.mode = EhFilter.MODE_TITLE;
        filter.enable = true;
        filter.text = "excluded";
        EhFilter.getInstance().getTitleFilterList().add(filter);
        assertNotEquals(before, SubscriptionSearchContext.key());
    }

    @Implements(EhApplication.class)
    public static class AppShadow {
        @Implementation protected static EhCookieStore getEhCookieStore(Context context) { return store; }
    }

    @Implements(EhCookieStore.class)
    public static class CookieStoreShadow {
        @Implementation protected List<Cookie> loadForRequest(HttpUrl url) { return new ArrayList<>(cookies); }
    }

    @Implements(EhDB.class)
    public static class DbShadow {
        @Implementation protected static List<Filter> getAllFilter() { return Collections.emptyList(); }
    }
}
