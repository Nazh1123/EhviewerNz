package com.hippo.ehviewer.client;

import android.app.Application;
import android.preference.PreferenceManager;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.dao.QuickSearch;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE)
public class BookmarkSubscriptionTestTest {
    private final List<EhRequest> requests = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong(1000);

    @Before public void setUp() {
        PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication())
                .edit().clear().commit();
        Settings.initialize(RuntimeEnvironment.getApplication());
    }

    private static QuickSearch bookmark(String keyword) {
        QuickSearch q = new QuickSearch();
        q.subscribed = true;
        q.mode = ListUrlBuilder.MODE_NORMAL;
        q.category = EhUtils.NONE;
        q.advanceSearch = q.minRating = q.pageFrom = q.pageTo = -1;
        q.keyword = keyword;
        return q;
    }

    @Test public void mergesEnabledBookmarksMakesRealRequestsAndPersistsOnlyOnCompletion() {
        QuickSearch disabled = bookmark("ignored");
        disabled.subscribed = false;
        BookmarkSubscriptionTest runner = new BookmarkSubscriptionTest(Runnable::run,
                () -> Arrays.asList(bookmark("uploader:Alice"), bookmark("uploader:Bob"),
                        bookmark("unmergeable search"), disabled), requests::add, clock::get);
        assertEquals("-", BookmarkSubscriptionTest.getLastResult().bookmarkText());
        assertEquals("-", BookmarkSubscriptionTest.getLastResult().requestText());
        assertEquals("-", BookmarkSubscriptionTest.getLastResult().durationText());
        Settings.putBoolean(Settings.KEY_AUTO_SUBSCRIPTION_UPDATES_BOOKMARK, false);
        assertTrue(runner.start());
        assertFalse(runner.start());
        ShadowLooper.idleMainLooper();
        assertEquals(2, requests.size());
        for (EhRequest request : requests) {
            assertEquals(EhClient.METHOD_GET_GALLERY_LIST, request.getMethod());
            assertEquals(true, request.getArgs()[2]);
        }
        requests.get(0).getCallback().onSuccess(null);
        assertTrue(runner.isRunning());
        assertEquals("-", BookmarkSubscriptionTest.getLastResult().bookmarkText());
        clock.set(2250);
        requests.get(1).getCallback().onSuccess(null);
        assertFalse(runner.isRunning());
        BookmarkSubscriptionTest.Result result = BookmarkSubscriptionTest.getLastResult();
        assertEquals(3, result.bookmarks());
        assertEquals(2, result.requests());
        assertEquals("1.250", result.durationText());
        assertFalse(result.failed());
        Settings.initialize(RuntimeEnvironment.getApplication());
        assertEquals(result, BookmarkSubscriptionTest.getLastResult());
        assertTrue(runner.start());
        assertEquals(result, BookmarkSubscriptionTest.getLastResult());
        ShadowLooper.idleMainLooper();
        requests.get(2).getCallback().onFailure(new Exception("offline"));
        requests.get(3).getCallback().onSuccess(null);
        assertTrue(BookmarkSubscriptionTest.getLastResult().failed());
    }

    @Test public void zeroBookmarksRemainZeroAndReplacePreviousMeasurement() {
        BookmarkSubscriptionTest runner = new BookmarkSubscriptionTest(Runnable::run,
                Collections::emptyList, requests::add, clock::get);
        runner.start();
        ShadowLooper.idleMainLooper();
        assertTrue(requests.isEmpty());
        assertFalse(runner.isRunning());
        assertEquals("0", BookmarkSubscriptionTest.getLastResult().bookmarkText());
        assertEquals("0", BookmarkSubscriptionTest.getLastResult().requestText());
        assertEquals("0.000", BookmarkSubscriptionTest.getLastResult().durationText());
    }

    @Test public void boundedConcurrencyAndFailuresCompleteEveryPlannedSourceOnce() {
        List<QuickSearch> bookmarks = new ArrayList<>();
        for (int i = 0; i < 12; i++) bookmarks.add(bookmark("plain search " + i));
        BookmarkSubscriptionTest runner = new BookmarkSubscriptionTest(Runnable::run,
                () -> bookmarks, requests::add, clock::get);
        runner.start();
        ShadowLooper.idleMainLooper();
        assertEquals(8, requests.size());
        requests.get(0).getCallback().onFailure(new Exception("failed"));
        assertEquals(9, requests.size());
        requests.get(0).getCallback().onCancel();
        assertEquals(9, requests.size());
        for (int i = 1; i < 12; i++) requests.get(i).getCallback().onSuccess(null);
        assertFalse(runner.isRunning());
        assertEquals(12, BookmarkSubscriptionTest.getLastResult().requests());
        assertTrue(BookmarkSubscriptionTest.getLastResult().failed());
    }

    @Test public void preparationFailureLeavesUnknownCountsAsDashes() {
        BookmarkSubscriptionTest runner = new BookmarkSubscriptionTest(Runnable::run,
                () -> { throw new IllegalStateException("database unavailable"); },
                requests::add, clock::get);
        runner.start();
        ShadowLooper.idleMainLooper();
        assertFalse(runner.isRunning());
        assertEquals("-", BookmarkSubscriptionTest.getLastResult().bookmarkText());
        assertEquals("-", BookmarkSubscriptionTest.getLastResult().requestText());
        assertTrue(BookmarkSubscriptionTest.getLastResult().failed());
    }
}
