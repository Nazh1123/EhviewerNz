package com.hippo.ehviewer.client;

import static org.junit.Assert.*;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import androidx.preference.PreferenceManager;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
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
    private final AtomicInteger listRequests = new AtomicInteger();
    private final AtomicInteger apiRequests = new AtomicInteger();
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
                        metadata.put(new JSONObject().put("gid", ids.getJSONArray(i).getLong(0))
                                .put("title", "gallery").put("title_jpn", "")
                                .put("category", "Manga").put("thumb", "https://example.org/thumb.jpg")
                                .put("uploader", "tester").put("posted", "1").put("filecount", "10")
                                .put("rating", secondPageMatches && listRequests.get() > 1 ? "4.51" : "4.50")
                                .put("tags", new JSONArray()));
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

    @Test public void usesApiDecimalsAndAdvancesPastExactlyOneEmptyBatch() throws Throwable {
        GalleryListParser.Result result = EhEngine.getGalleryList(null, client(true), FIRST,
                ListUrlBuilder.MODE_NORMAL, false, false, 451, true);
        assertEquals(2, listRequests.get());
        assertFalse(result.galleryInfoList.isEmpty());
        assertTrue(apiRequests.get() >= 2);
        assertEquals(4.51f, result.galleryInfoList.get(0).rating, 0f);
        assertEquals(AFTER_NEXT, result.nextHref);
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
