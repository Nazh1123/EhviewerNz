package com.hippo.ehviewer.client;

import static org.junit.Assert.*;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import androidx.preference.PreferenceManager;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.client.parser.GalleryListParser;
import com.hippo.ehviewer.dao.DaoMaster;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import okio.Buffer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE,
        shadows = SearchRatingLoadingTest.ContextShadow.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SearchRatingLoadingTest {
    private SQLiteDatabase database;
    private static String subscriptionScope;
    private String html;
    private boolean distinctPages;
    private String apiPosted = "1";
    private final AtomicInteger listRequests = new AtomicInteger();
    private final AtomicInteger apiRequests = new AtomicInteger();
    private final java.util.Set<Long> apiGids = new java.util.HashSet<>();
    private JSONArray apiTags = new JSONArray();
    private final java.util.List<String> requestedUrls = new java.util.ArrayList<>();
    private static final String FIRST = "https://e-hentai.org/?f_sr=on&f_srdd=4";
    private static final String NEXT = FIRST + "&next=123";
    private static final String AFTER_NEXT = FIRST + "&next=100";

    @Before public void setUp() throws Exception {
        subscriptionScope = java.util.UUID.randomUUID().toString();
        Application app = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit();
        Settings.initialize(app);
        database = SQLiteDatabase.create(null);
        DaoMaster.createAllTables(new org.greenrobot.greendao.database.StandardDatabase(database), false);
        ReflectionHelpers.setStaticField(EhDB.class, "sDaoSession", new DaoMaster(database).newSession());
        ReflectionHelpers.setStaticField(EhFilter.class, "sInstance", null);
        EhEngine.initialize();
        try (InputStream input = getClass().getResourceAsStream("parser/GalleryListParserNew3.html")) {
            html = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        // Use a deterministic navigation URL while retaining real list markup.
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);
        doc.getElementById("unext").attr("href", NEXT);
        html = doc.outerHtml();
    }

    @After public void tearDown() {
        ReflectionHelpers.setStaticField(EhDB.class, "sDaoSession", null);
        ReflectionHelpers.setStaticField(EhFilter.class, "sInstance", null);
        EhEngine.sEhFilter = null;
        database.close();
    }

    private OkHttpClient client(boolean secondPageMatches) {
        return new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request();
            String body;
            if (request.method().equals("POST")) {
                apiRequests.incrementAndGet();
                try {
                    Buffer buffer = new Buffer();
                    request.body().writeTo(buffer);
                    JSONArray ids = new JSONObject(buffer.readUtf8()).getJSONArray("gidlist");
                    JSONArray metadata = new JSONArray();
                    for (int i = 0; i < ids.length(); i++) {
                        apiGids.add(ids.getJSONArray(i).getLong(0));
                        metadata.put(new JSONObject().put("gid", ids.getJSONArray(i).getLong(0))
                                .put("title", "gallery").put("title_jpn", "")
                                .put("category", "Manga").put("thumb", "https://example.org/thumb.jpg")
                                .put("uploader", "tester").put("posted", apiPosted).put("filecount", "10")
                                .put("rating", secondPageMatches && listRequests.get() > 1 ? "4.51" : "4.50")
                                .put("tags", apiTags));
                    }
                    body = new JSONObject().put("gmetadata", metadata).toString();
                } catch (Exception e) { throw new java.io.IOException(e); }
            } else {
                requestedUrls.add(request.url().toString());
                listRequests.incrementAndGet();
                body = html;
                if (listRequests.get() > 1) {
                    var doc = org.jsoup.Jsoup.parse(html);
                    doc.getElementById("unext").attr("href", AFTER_NEXT);
                    body = doc.outerHtml();
                    if (distinctPages) {
                        body = java.util.regex.Pattern.compile("/g/(\\d+)/").matcher(body)
                                .replaceAll(match -> "/g/" + (Long.parseLong(match.group(1)) - 10000) + "/");
                    }
                }
            }
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                    .message("OK").body(ResponseBody.create(MediaType.parse("text/plain"), body)).build();
        }).build();
    }

    @Test public void optionalInfoDoesNotDelayListAndRequiredTagsFetchOnlyMissingRows() throws Throwable {
        Settings.putBoolean(Settings.KEY_SHOW_THUMBNAIL_INFO_BAR, true);
        Settings.putBoolean(Settings.KEY_SHOW_THUMBNAIL_INFO_DETAILS, true);
        GalleryInfo missing = new GalleryInfo(); missing.gid = 1; missing.token = "token";
        GalleryInfo complete = new GalleryInfo(); complete.gid = 2; complete.token = "token";
        missing.thumb = complete.thumb = "https://example.org/thumb.jpg";
        complete.simpleTags = new String[0]; complete.pages = 20;
        var method = EhEngine.class.getDeclaredMethod("fillGalleryList", EhClient.Task.class, OkHttpClient.class,
                java.util.List.class, String.class, boolean.class, String.class, boolean.class, boolean.class, boolean.class);
        method.setAccessible(true);
        var rows = new java.util.ArrayList<>(java.util.List.of(missing, complete));
        method.invoke(null, null, client(false), rows, FIRST, true, null, false, false, true);
        assertEquals(0, apiRequests.get()); assertEquals(2, rows.size());
        EhEngine.sEhFilter.addFilter(new com.hippo.ehviewer.dao.Filter(null, EhFilter.MODE_TAG, "blocked", true));
        method.invoke(null, null, client(false), rows, FIRST, true, null, false, false, true);
        assertEquals(java.util.Set.of(1L), apiGids);
        assertEquals(20, complete.pages);
    }

    @Test public void usesApiDecimalsAndAdvancesPastExactlyOneEmptyBatch() throws Throwable {
        GalleryListParser.Result result = EhEngine.getGalleryList(null, client(true), FIRST,
                ListUrlBuilder.MODE_NORMAL, false, false, 451, true);
        assertEquals(2, listRequests.get());
        assertFalse(result.galleryInfoList.isEmpty());
        assertTrue(apiRequests.get() >= 2);
        assertEquals(4.51f, result.galleryInfoList.get(0).rating, 0f);
        assertEquals(AFTER_NEXT, result.nextHref);
    }

    @Test public void metadataCallTimeoutIsRetryableFailureRatherThanSilentCancellation() throws Throwable {
        try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer()) {
            server.start();
            server.enqueue(new okhttp3.mockwebserver.MockResponse()
                    .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE));
            OkHttpClient client = new OkHttpClient.Builder().callTimeout(300, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .retryOnConnectionFailure(false).addInterceptor(chain -> {
                        if (chain.request().method().equals("GET")) {
                            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                                    .code(200).message("OK").body(ResponseBody.create(MediaType.parse("text/html"), html)).build();
                        }
                        return chain.proceed(chain.request().newBuilder().url(server.url("/api.php")).build());
                    }).build();
            try {
                EhEngine.getGalleryList(null, client, FIRST, ListUrlBuilder.MODE_NORMAL,
                        true, false, 451, false);
                fail("Stalled metadata succeeded");
            } catch (java.io.InterruptedIOException expected) {
                assertEquals(2000, SubscriptionRetry.delayMillis(expected, 0));
            } finally {
                client.connectionPool().evictAll();
            }
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void defaultEngineThresholdFiltersButExplicitZeroBypassesIt() throws Throwable {
        Settings.putPersistentMinRatingHundredths(451);
        GalleryListParser.Result result = EhEngine.getGalleryList(null, client(false), FIRST,
                ListUrlBuilder.MODE_NORMAL);
        assertTrue(result.galleryInfoList.isEmpty());
        assertEquals(2, listRequests.get());
        result = EhEngine.getGalleryList(null, client(false), FIRST,
                ListUrlBuilder.MODE_NORMAL, false, false, 0, true);
        assertFalse(result.galleryInfoList.isEmpty());
        assertEquals(3, listRequests.get());
        assertEquals(451, Settings.getPersistentMinRatingHundredths());
    }

    @Test public void stopsAfterOneExtraBatchEvenWhenItAlsoFailsThreshold() throws Throwable {
        GalleryListParser.Result result = EhEngine.getGalleryList(null, client(false), FIRST,
                ListUrlBuilder.MODE_NORMAL, false, false, 451, true);
        assertTrue(result.galleryInfoList.isEmpty());
        assertEquals(2, listRequests.get());
    }

    @Test public void matchingBatchDoesNotFetchAnotherPage() throws Throwable {
        GalleryListParser.Result result = EhEngine.getGalleryList(null, client(false), FIRST,
                ListUrlBuilder.MODE_NORMAL, false, false, 450, true);
        assertFalse(result.galleryInfoList.isEmpty());
        assertEquals(1, listRequests.get());
    }

    @Test public void backwardNavigationDoesNotSkipAnEmptyBatch() throws Throwable {
        GalleryListParser.Result result = EhEngine.getGalleryList(null, client(false), FIRST,
                ListUrlBuilder.MODE_NORMAL, false, false, 451, false);
        assertTrue(result.galleryInfoList.isEmpty());
        assertEquals(1, listRequests.get());
    }

    @Test public void exhaustedSearchDoesNotRequestAnotherPage() throws Throwable {
        var doc = org.jsoup.Jsoup.parse(html);
        doc.getElementById("unext").remove();
        html = doc.outerHtml();
        GalleryListParser.Result result = EhEngine.getGalleryList(null, client(false), FIRST,
                ListUrlBuilder.MODE_NORMAL, false, false, 451, true);
        assertTrue(result.galleryInfoList.isEmpty());
        assertEquals(1, listRequests.get());
    }

    @Test public void mergedSubscriptionStripsSiteRatingOnInitialAndExtraPageButFiltersExactly() throws Throwable {
        distinctPages = true;
        GalleryListParser.Result result = EhEngine.getGalleryList(null, client(true), FIRST,
                ListUrlBuilder.MODE_NORMAL, true, false, 451, true);
        assertEquals(2, requestedUrls.size());
        for (String url : requestedUrls) {
            assertFalse(url, url.contains("f_sr"));
        }
        assertFalse(result.galleryInfoList.isEmpty());
        assertEquals(4.51f, result.galleryInfoList.get(0).rating, 0f);
    }

    private static SubscriptionHomepageMatcher homepageMatcher(String uploader) {
        com.hippo.ehviewer.dao.QuickSearch bookmark = new com.hippo.ehviewer.dao.QuickSearch();
        bookmark.mode = ListUrlBuilder.MODE_UPLOADER;
        bookmark.keyword = uploader;
        bookmark.subscribed = true;
        return new SubscriptionHomepageMatcher(java.util.List.of(bookmark));
    }

    @Test public void homepageCheckLoadsMetadataButKeepsRawOrderAndOnlyOnePage() throws Throwable {
        var raw = GalleryListParser.parseMergedUploaderSearch(html, ListUrlBuilder.MODE_NORMAL);
        GalleryListParser.Result result = EhEngine.getSubscriptionHomepagePage(null, client(false), FIRST,
                homepageMatcher("tester"));
        assertEquals(1, listRequests.get());
        assertEquals(raw.galleryInfoList.size(), result.subscriptionRawGalleries.size());
        assertEquals(raw.galleryInfoList.size(), result.galleryInfoList.size());
        assertEquals(raw.galleryInfoList.size(), apiGids.size());
        assertEquals(raw.galleryInfoList.get(0).posted, result.subscriptionRawGalleries.get(0).posted);
        assertEquals("tester", result.galleryInfoList.get(0).uploader);
        assertFalse(requestedUrls.get(0).contains("f_sr"));
    }

    @Test public void homepageCheckRetainsRawPageWhenNoLocalRulesMatch() throws Throwable {
        GalleryListParser.Result result = EhEngine.getSubscriptionHomepagePage(null, client(false), FIRST,
                homepageMatcher("someone else"));
        assertTrue(result.galleryInfoList.isEmpty());
        assertFalse(result.subscriptionRawGalleries.isEmpty());
        assertEquals(1, listRequests.get());
        assertNotNull(result.nextHref);
    }

    @Test public void homepageCheckAlsoAppliesLocalBlocklists() throws Throwable {
        com.hippo.ehviewer.dao.Filter blocked = new com.hippo.ehviewer.dao.Filter();
        blocked.mode = EhFilter.MODE_UPLOADER;
        blocked.text = "tester";
        blocked.enable = true;
        EhFilter.getInstance().addFilter(blocked);
        GalleryListParser.Result result = EhEngine.getSubscriptionHomepagePage(null, client(false), FIRST,
                homepageMatcher("tester"));
        assertTrue(result.galleryInfoList.isEmpty());
        assertFalse(result.subscriptionRawGalleries.isEmpty());
    }

    @Test public void homepageSeedAndKnownIdsSkipAllMetadataEvenWithDisplayRequirements() throws Throwable {
        Settings.putBoolean("show_gallery_pages", true);
        Settings.putBoolean(Settings.KEY_SHOW_THUMBNAIL_INFO_BAR, true);
        Settings.putBoolean(Settings.KEY_SHOW_THUMBNAIL_INFO_DETAILS, true);
        SubscriptionHomepageScan seed = new SubscriptionHomepageScan("");
        var first = EhEngine.getSubscriptionHomepagePage(null, client(false), FIRST, homepageMatcher("tester"), seed);
        assertTrue(first.galleryInfoList.isEmpty());
        assertFalse(first.subscriptionRawGalleries.isEmpty());
        assertEquals(0, apiRequests.get());
        seed.shouldContinue(first.subscriptionRawGalleries, false);
        var repeat = EhEngine.getSubscriptionHomepagePage(null, client(false), FIRST,
                homepageMatcher("tester"), new SubscriptionHomepageScan(seed.checkpoint()));
        assertTrue(repeat.galleryInfoList.isEmpty());
        assertEquals(first.rawResultCount, repeat.subscriptionRawGalleries.size());
        assertEquals(0, apiRequests.get());
    }

    @Test public void homepageFetchesMetadataOnlyForNewCandidatesWithUnknownUploader() throws Throwable {
        var doc = org.jsoup.Jsoup.parse(html);
        doc.select(".glhide a, .gl3e a").remove();
        html = doc.outerHtml();
        var raw = GalleryListParser.parseMergedUploaderSearch(html, ListUrlBuilder.MODE_NORMAL);
        assertTrue(raw.galleryInfoList.stream().allMatch(g -> g.uploader == null));
        SubscriptionHomepageScan seed = new SubscriptionHomepageScan("");
        seed.shouldContinue(raw.galleryInfoList.subList(1, raw.galleryInfoList.size()), false);
        var result = EhEngine.getSubscriptionHomepagePage(null, client(false), FIRST,
                homepageMatcher("tester"), new SubscriptionHomepageScan(seed.checkpoint()));
        assertEquals(java.util.Set.of(raw.galleryInfoList.get(0).gid), apiGids);
        assertEquals(1, result.galleryInfoList.size());
        assertEquals(raw.rawResultCount, result.subscriptionRawGalleries.size());
    }

    @Test public void homepageCoarseUploaderFilterSkipsMetadataForImpossibleCandidates() throws Throwable {
        var raw = GalleryListParser.parseMergedUploaderSearch(html, ListUrlBuilder.MODE_NORMAL);
        assertTrue(raw.galleryInfoList.stream().allMatch(g -> g.uploader != null));
        SubscriptionHomepageScan old = new SubscriptionHomepageScan("");
        GalleryInfo older = new GalleryInfo();
        older.gid = 1; older.posted = "2000-01-01 00:00";
        old.shouldContinue(java.util.List.of(older), false);
        var result = EhEngine.getSubscriptionHomepagePage(null, client(false), FIRST,
                homepageMatcher("nonexistent uploader for coarse rejection"), new SubscriptionHomepageScan(old.checkpoint()));
        assertTrue(result.galleryInfoList.isEmpty());
        assertEquals(0, apiRequests.get());
        assertFalse(result.subscriptionRawGalleries.isEmpty());
    }

    @Test public void nativeToHomepageHandoffSkipsKnownMetadataAndReportsArrivalOnFirstPass() throws Throwable {
        var doc = org.jsoup.Jsoup.parse(html);
        doc.select(".glhide a, .gl3e a").remove();
        html = doc.outerHtml();
        var raw = GalleryListParser.parseMergedUploaderSearch(html, ListUrlBuilder.MODE_NORMAL);
        GalleryInfo arrival = raw.galleryInfoList.get(0);
        GallerySeenState cursor = GallerySeenState.empty().observe(
                raw.galleryInfoList.subList(1, raw.galleryInfoList.size()));
        // Keep authoritative API timestamps consistent with the HTML fixture.
        apiPosted = Long.toString(GallerySeenState.postedMinute(arrival.posted) * 60);
        com.hippo.ehviewer.dao.QuickSearch bookmark = new com.hippo.ehviewer.dao.QuickSearch();
        bookmark.mode = ListUrlBuilder.MODE_UPLOADER;
        bookmark.keyword = "tester";
        bookmark.subscribed = true;
        SubscriptionHomepageMatcher matcher = new SubscriptionHomepageMatcher(java.util.List.of(bookmark),
                java.util.Map.of(bookmark, cursor));
        SubscriptionHomepageScan scan = new SubscriptionHomepageScan("", java.util.List.of(cursor));
        var result = EhEngine.getSubscriptionHomepagePage(null, client(false), FIRST, matcher, scan);
        assertEquals(java.util.Set.of(arrival.gid), apiGids);
        assertEquals(1, apiRequests.get());
        assertEquals(1, result.galleryInfoList.size());
        assertEquals(arrival.gid, result.galleryInfoList.get(0).gid);
        matcher.observe(result.galleryInfoList);
        GallerySeenState resumed = cursor.merge(matcher.observed(java.util.List.of(bookmark)));
        assertFalse(resumed.isNew(arrival));
    }

    private static GallerySeenState cursorAt(String posted) {
        GalleryInfo seen = new GalleryInfo();
        seen.gid = 1;
        seen.posted = posted;
        return GallerySeenState.empty().observe(java.util.List.of(seen));
    }

    private GalleryListParser.Result check(GallerySeenState cursor, int minimum) throws Throwable {
        // Even a caller accidentally enabling extra pages must not bypass the scanner's cursor.
        return EhEngine.getGalleryList(null, client(false), FIRST, ListUrlBuilder.MODE_NORMAL,
                true, true, minimum, true, null, cursor);
    }

    @Test public void subscriptionCheckDoesNotFetchExtraPageAfterRatingRejection() throws Throwable {
        GalleryListParser.Result result = check(cursorAt("2020-01-01 00:00"), 451);
        assertTrue(result.galleryInfoList.isEmpty());
        assertTrue(result.ratingFilteredAll);
        assertEquals(1, listRequests.get());
        assertEquals(NEXT, result.nextHref);
        assertTrue(result.rawResultCount > 0);
        assertEquals(result.rawResultCount, result.subscriptionRawGalleries.size());
    }

    @Test public void unchangedAndInitialChecksSkipAllMetadataIncludingDisplayRequirements() throws Throwable {
        Settings.putBoolean("show_gallery_pages", true);
        Settings.putBoolean(Settings.KEY_SHOW_THUMBNAIL_INFO_BAR, true);
        Settings.putBoolean(Settings.KEY_SHOW_THUMBNAIL_INFO_DETAILS, true);
        for (GallerySeenState cursor : java.util.List.of(cursorAt("2030-01-01 00:00"), GallerySeenState.empty())) {
            GalleryListParser.Result result = check(cursor, 451);
            assertTrue(result.galleryInfoList.isEmpty());
            assertFalse(result.subscriptionRawGalleries.isEmpty());
            assertEquals(GallerySeenState.postedMinute("2023-01-06 16:58"), result.rawSeenState.minute());
        }
        assertEquals(2, listRequests.get());
        assertEquals(0, apiRequests.get());
    }

    @Test public void checkFetchesExactMetadataOnlyForUnseenCandidates() throws Throwable {
        var raw = GalleryListParser.parseMergedUploaderSearch(html, ListUrlBuilder.MODE_NORMAL);
        GallerySeenState cursor = GallerySeenState.empty().observe(raw.galleryInfoList.subList(1,
                raw.galleryInfoList.size()));
        long candidate = raw.galleryInfoList.get(0).gid;
        GalleryListParser.Result result = check(cursor, 450);
        assertEquals(java.util.Set.of(candidate), apiGids);
        assertEquals(1, apiRequests.get());
        assertEquals(1, result.galleryInfoList.size());
        assertEquals(4.50f, result.galleryInfoList.get(0).rating, 0f);
        assertEquals(raw.rawResultCount, result.subscriptionRawGalleries.size());
    }

    @Test public void checkDefersDisplayMetadataAndUploaderAttributionUntilBrowsing() throws Throwable {
        var doc = org.jsoup.Jsoup.parse(html);
        doc.select(".glhide a, .gl3e a").remove();
        html = doc.outerHtml();
        Settings.putBoolean("show_gallery_pages", true);
        Settings.putBoolean(Settings.KEY_SHOW_THUMBNAIL_INFO_BAR, true);
        Settings.putBoolean(Settings.KEY_SHOW_THUMBNAIL_INFO_DETAILS, true);
        GalleryListParser.Result checked = check(cursorAt("2020-01-01 00:00"), 0);
        assertFalse(checked.galleryInfoList.isEmpty());
        assertTrue(checked.galleryInfoList.stream().allMatch(g -> g.uploader == null));
        assertEquals(0, apiRequests.get());
        var displayed = EhEngine.prepareSubscriptionList(null, client(false), FIRST,
                checked.subscriptionRawGalleries, true, 450);
        assertEquals(1, listRequests.get());
        assertTrue(apiRequests.get() > 0);
        assertEquals(checked.rawResultCount, displayed.galleryInfoList.size());
        assertTrue(displayed.galleryInfoList.stream().allMatch(g -> "tester".equals(g.uploader)
                && g.pages == 10 && g.simpleTags != null && g.firstGid != null));
        assertTrue(checked.subscriptionRawGalleries.stream().allMatch(g -> g.uploader == null));
    }

    @Test public void reusableCheckPreparesFullDisplayBeforeFilteringNewCandidates() throws Throwable {
        var doc = org.jsoup.Jsoup.parse(html);
        doc.select(".glhide a, .gl3e a").remove();
        html = doc.outerHtml();
        Settings.putBoolean("show_gallery_pages", true);
        Settings.putBoolean(Settings.KEY_SHOW_THUMBNAIL_INFO_BAR, true);
        Settings.putBoolean(Settings.KEY_SHOW_THUMBNAIL_INFO_DETAILS, true);
        for (GallerySeenState cursor : java.util.List.of(cursorAt("2030-01-01 00:00"), GallerySeenState.empty())) {
            var checked = EhEngine.getGalleryList(null, client(false), FIRST, ListUrlBuilder.MODE_NORMAL,
                    true, true, 450, false, null, cursor, true);
            assertTrue(checked.galleryInfoList.isEmpty());
            assertNotNull(checked.subscriptionDisplayGalleries);
            assertEquals(checked.rawResultCount, checked.subscriptionDisplayGalleries.size());
            assertTrue(checked.subscriptionDisplayGalleries.stream().allMatch(g -> "tester".equals(g.uploader)
                    && g.pages == 10 && g.simpleTags != null && g.firstGid != null));
        }
        assertEquals(2, listRequests.get());
        // The second check shares the metadata fetched while preparing the first display.
        assertEquals(1, apiRequests.get());
    }

    @Test public void checkStillResolvesMissingUploadersForEnabledBlockFilter() throws Throwable {
        var doc = org.jsoup.Jsoup.parse(html);
        doc.select(".glhide a, .gl3e a").remove();
        html = doc.outerHtml();
        EhEngine.sEhFilter.addFilter(new com.hippo.ehviewer.dao.Filter(null, EhFilter.MODE_UPLOADER, "tester", true));
        GalleryListParser.Result result = check(cursorAt("2020-01-01 00:00"), 0);
        assertTrue(apiRequests.get() > 0);
        assertTrue(result.galleryInfoList.isEmpty());
        assertFalse(result.subscriptionRawGalleries.isEmpty());
    }

    @Test public void checkStillLoadsMissingTagsForEnabledBlockFilter() throws Throwable {
        var doc = org.jsoup.Jsoup.parse(html);
        doc.select(".glname table").remove();
        html = doc.outerHtml();
        apiTags.put("female:blocked");
        EhEngine.sEhFilter.addFilter(new com.hippo.ehviewer.dao.Filter(null, EhFilter.MODE_TAG, "female:blocked", true));
        GalleryListParser.Result result = check(cursorAt("2020-01-01 00:00"), 0);
        assertTrue(apiRequests.get() > 0);
        assertTrue(result.galleryInfoList.isEmpty());
    }

    @Test public void utcApiMetadataKeepsTheUnfilteredPublicationBoundary() throws Throwable {
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"));
            apiPosted = "1767225630"; // 2026-01-01 00:00:30 UTC
            GalleryListParser.Result result = EhEngine.getGalleryList(null, client(false), FIRST,
                    ListUrlBuilder.MODE_NORMAL, false, false, 450, false);
            assertFalse(result.galleryInfoList.isEmpty());
            assertEquals("2026-01-01 00:00", result.galleryInfoList.get(0).posted);
            // The retained raw marker belongs to the original 2023 HTML, not the API override.
            assertEquals(GallerySeenState.postedMinute("2023-01-06 16:58"), result.rawSeenState.minute());
        } finally { java.util.TimeZone.setDefault(original); }
    }

    @org.robolectric.annotation.Implements(SubscriptionSearchContext.class)
    public static class ContextShadow {
        @org.robolectric.annotation.Implementation protected static String key() {
            return subscriptionScope;
        }
    }

    @Test public void popularLoadsExactRatingsAndKeepsEntireSnapshotForLocalProjection() throws Throwable {
        com.hippo.ehviewer.client.data.GalleryInfo old = new com.hippo.ehviewer.client.data.GalleryInfo();
        old.gid = 42;
        old.token = "token";
        GalleryListParser.Result result = EhEngine.getGalleryList(null, client(false), FIRST,
                ListUrlBuilder.MODE_WHATS_HOT, false, false, 451, true, java.util.List.of(old));
        assertEquals(1, requestedUrls.size());
        assertFalse(requestedUrls.get(0).contains("f_sr"));
        assertFalse(result.galleryInfoList.isEmpty());
        assertEquals(4.50f, result.galleryInfoList.get(0).rating, 0f);
        assertFalse(result.ratingFilteredAll);
        assertNotNull(result.ratingMetadata);
        assertEquals(4.50f, result.ratingMetadata.get(0).rating, 0f);
        assertNotNull(result.ratingMetadata.get(0).firstGid);
    }
}
