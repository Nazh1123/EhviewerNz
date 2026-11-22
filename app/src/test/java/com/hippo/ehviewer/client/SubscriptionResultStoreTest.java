package com.hippo.ehviewer.client;

import android.app.Application;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.client.parser.GalleryListParser;
import com.hippo.ehviewer.dao.QuickSearch;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowSystemClock;

import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE,
        shadows = SubscriptionResultStoreTest.ContextShadow.class)
public class SubscriptionResultStoreTest {
    private File directory;
    private static String context, display;

    @Before public void setUp() throws Exception {
        SubscriptionResultStore.IO.submit(() -> {}).get();
        var app = RuntimeEnvironment.getApplication();
        Settings.initialize(app);
        directory = new File(app.getFilesDir(), "result-store-test");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Files.deleteIfExists(new File(directory, SubscriptionResultStore.FILE_NAME).toPath());
        context = "account-a"; display = "account-a:display-a";
    }

    @After public void finishWrites() throws Exception {
        SubscriptionResultStore.IO.submit(() -> {}).get();
    }

    private SubscriptionUpdateManager.RecentCheckSource source(boolean eh, long gid) {
        ListUrlBuilder builder = new ListUrlBuilder();
        BookmarkSubscriptionPlanner.Source plan = null;
        if (eh) builder.setMode(ListUrlBuilder.MODE_SUBSCRIPTION);
        else {
            QuickSearch bookmark = new QuickSearch();
            bookmark.id = 1L; bookmark.subscribed = true;
            bookmark.mode = ListUrlBuilder.MODE_NORMAL; bookmark.keyword = "test";
            bookmark.category = EhUtils.NONE;
            bookmark.advanceSearch = bookmark.minRating = bookmark.pageFrom = bookmark.pageTo = -1;
            plan = BookmarkSubscriptionPlanner.plan(List.of(bookmark)).get(0);
            builder.set(plan.createBuilder());
        }
        GalleryInfo gallery = new GalleryInfo();
        gallery.gid = gid; gallery.token = "token";
        gallery.title = "saved title"; gallery.titleJpn = "保存されたタイトル";
        gallery.thumb = "https://example.com/cover.jpg"; gallery.pages = 42;
        gallery.posted = "2026-11-11 00:00"; gallery.uploader = "uploader";
        gallery.rating = 4.75f; gallery.apiRating = 4.75f; gallery.firstGid = gid - 1;
        gallery.simpleTags = new String[]{"language:chinese"};
        var page = new GalleryListParser.Result();
        page.galleryInfoList.add(gallery);
        page.rawGalleries = List.of(gallery); page.rawResultCount = 25;
        page.rawSeenState = GallerySeenState.empty().observe(page.rawGalleries);
        page.rawPublicationTimes.put(gid, gallery.posted);
        page.rawTailPosted = gallery.posted; page.rawTailGid = gid;
        page.nextHref = "https://e-hentai.org/?next=" + gid;
        return SubscriptionUpdateManager.RecentCheckSource.fromDisplay(builder, plan, page, 0);
    }

    @Test public void coldStoreQueuesReadsAndWritesWithoutBlockingTheCaller() throws Exception {
        var prior = new SubscriptionResultStore(directory);
        prior.record(context, display, List.of(source(true, 200)));
        finishWrites();

        var release = new CountDownLatch(1);
        SubscriptionResultStore.IO.submit(() -> {
            release.await();
            return null;
        });
        try {
            var store = new SubscriptionResultStore(directory);
            assertFalse(store.getAsync().isDone());
            var recorded = CompletableFuture.runAsync(
                    () -> store.record(context, display, List.of(source(false, 199))));
            recorded.get(5, TimeUnit.SECONDS);
            var pending = store.getAsync();
            assertFalse(pending.isDone());
            release.countDown();
            assertEquals(2, pending.get(5, TimeUnit.SECONDS).sources.size());
            assertEquals(2, new SubscriptionResultStore(directory).get().sources.size());
        } finally {
            release.countDown();
        }
    }

    @Test public void bothSourcesRestoreFullMetadataFirstPageCursorAndOriginalTime() throws Exception {
        var eh = source(true, 200); var bookmark = source(false, 199);
        var store = new SubscriptionResultStore(directory);
        store.record(context, display, List.of(eh, bookmark));
        finishWrites();
        // Persisted wall-clock age must survive a new process/boot independently of uptime.
        File file = new File(directory, SubscriptionResultStore.FILE_NAME);
        JSONObject root = JSON.parseObject(new String(Files.readAllBytes(file.toPath()),
                java.nio.charset.StandardCharsets.UTF_8));
        long age = Duration.ofMinutes(90).toMillis();
        for (Object value : root.getJSONArray("sources")) {
            JSONObject saved = (JSONObject) value;
            saved.put("started", saved.getLongValue("started") - age);
            saved.put("completed", saved.getLongValue("completed") - age);
        }
        Files.write(file.toPath(), root.toJSONString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var restored = new SubscriptionResultStore(directory).get();
        assertNotNull(restored);
        assertTrue(restored.matchesContext());
        assertEquals(2, restored.sources.size());
        var saved = restored.sources.get(0);
        assertTrue(saved.isEhSubscription());
        assertEquals(eh.completedTimeMillis - age, saved.completedTimeMillis);
        assertFalse(saved.isFresh());
        assertEquals(0, saved.pageIndex);
        assertEquals(eh.nextHref, saved.nextHref);
        assertEquals(eh.boundaryGid, saved.boundaryGid);
        assertEquals(eh.rawPublicationTimes, saved.rawPublicationTimes);
        assertEquals(eh.observed.serialize(), saved.observed.serialize());
        GalleryInfo gallery = saved.galleryInfoList.get(0);
        assertEquals("saved title", gallery.title);
        assertEquals("保存されたタイトル", gallery.titleJpn);
        assertEquals("token", gallery.token);
        assertEquals("https://example.com/cover.jpg", gallery.thumb);
        assertEquals(42, gallery.pages);
        assertEquals(4.75f, gallery.apiRating, 0f);
        assertEquals(Long.valueOf(199), gallery.firstGid);
        assertArrayEquals(new String[]{"language:chinese"}, gallery.simpleTags);
    }

    @Test public void updatingOneSourceOrRecordingAnEmptyFailedRoundKeepsTheOtherSource() {
        var store = new SubscriptionResultStore(directory);
        var eh = source(true, 200); var bookmark = source(false, 199);
        store.record(context, display, List.of(eh, bookmark));
        store.record(context, display, List.of(source(true, 300)));
        store.record(context, display, List.of());
        var restored = new SubscriptionResultStore(directory).get();
        assertEquals(2, restored.sources.size());
        assertEquals(199, restored.sources.get(0).galleryInfoList.get(0).gid);
        assertEquals(300, restored.sources.get(1).galleryInfoList.get(0).gid);
    }

    @Test public void accountOrDisplayChangesRejectThePreviousSnapshot() {
        var store = new SubscriptionResultStore(directory);
        store.record(context, display, List.of(source(true, 200)));
        display = "account-a:display-b";
        assertFalse(new SubscriptionResultStore(directory).get().matchesContext());
        context = "account-b"; display = "account-b:display-a";
        assertFalse(new SubscriptionResultStore(directory).get().matchesContext());
        store.record(context, display, List.of(source(false, 300)));
        var restored = new SubscriptionResultStore(directory).get();
        assertTrue(restored.matchesContext());
        assertEquals(1, restored.sources.size());
        assertFalse(restored.sources.get(0).isEhSubscription());
    }

    @Test public void damagedFileFallsBackAndCanBeReplacedByANewSuccess() throws Exception {
        Files.write(new File(directory, SubscriptionResultStore.FILE_NAME).toPath(), "{broken".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var store = new SubscriptionResultStore(directory);
        assertNull(store.get());
        store.record(context, display, List.of(source(true, 200)));
        assertNotNull(new SubscriptionResultStore(directory).get());
    }

    @Test public void oneDamagedSourceDoesNotEraseTheOtherSource() throws Exception {
        var store = new SubscriptionResultStore(directory);
        store.record(context, display, List.of(source(true, 200), source(false, 199)));
        finishWrites();
        File file = new File(directory, SubscriptionResultStore.FILE_NAME);
        JSONObject root = JSON.parseObject(new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8));
        root.getJSONArray("sources").getJSONObject(0).put("completed", 0);
        Files.write(file.toPath(), root.toJSONString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var restored = new SubscriptionResultStore(directory).get();
        assertEquals(1, restored.sources.size());
        assertFalse(restored.sources.get(0).isEhSubscription());
    }

    @Test public void galleryTableSharesIdenticalRowsAndKeepsDistinctMetadataForTheSameGid() throws Exception {
        var eh = source(true, 200);
        var bookmark = source(false, 200);
        bookmark.galleryInfoList.get(0).title = "bookmark title";
        var sources = List.of(eh, bookmark);
        var global = SubscriptionDisplaySnapshot.merge(ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION,
                sources, java.util.Set.of(200L), context);
        var local = SubscriptionDisplaySnapshot.merge(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION,
                sources, java.util.Set.of(), context);
        var store = new SubscriptionResultStore(directory);
        store.recordSnapshots(context, display, sources, List.of(global, local));
        finishWrites();
        var file = new File(directory, SubscriptionResultStore.FILE_NAME).toPath();
        var root = JSON.parseObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertEquals(3, root.getIntValue("version"));
        assertEquals(2, root.getJSONArray("galleryTable").size());
        var restored = new SubscriptionResultStore(directory).get();
        assertEquals("saved title", restored.displaySnapshot(global.mode).galleries.get(0).title);
        assertEquals("bookmark title", restored.displaySnapshot(local.mode).galleries.get(0).title);
    }

    @Test public void restoresLegacyInlineGalleryRecords() throws Exception {
        var table = new SubscriptionGalleryTable(new JSONArray());
        var source = source(true, 200).toJson(table);
        source.put("galleries", table.rows);
        source.put("raw", table.rows);
        var cursor = new JSONObject();
        cursor.put("page", source);
        cursor.put("position", 1);
        int mode = ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION;
        var snapshot = new JSONObject();
        snapshot.put("mode", mode);
        snapshot.put("context", context);
        snapshot.put("completed", source.getLongValue("completed"));
        snapshot.put("freshness", source.getLongValue("started"));
        snapshot.put("batchSize", 25);
        snapshot.put("unreadCount", 1);
        snapshot.put("galleries", table.rows);
        snapshot.put("chronological", table.rows);
        snapshot.put("cursors", List.of(cursor));
        var root = new JSONObject();
        root.put("version", 2);
        root.put("context", context);
        root.put("display", display);
        root.put("sources", List.of(source));
        root.put("snapshots", List.of(snapshot));
        Files.write(new File(directory, SubscriptionResultStore.FILE_NAME).toPath(),
                root.toJSONString().getBytes(StandardCharsets.UTF_8));
        var restored = new SubscriptionResultStore(directory).get();
        assertEquals(java.util.Set.of(200L), restored.displaySnapshot(mode).unreadGids());
        assertEquals(1, restored.displaySnapshot(mode).cursors.get(0).position());
    }

    @Implements(SubscriptionSearchContext.class)
    public static class ContextShadow {
        @Implementation protected static String key() { return context; }
        @Implementation protected static String cacheKey() { return display; }
    }
}
