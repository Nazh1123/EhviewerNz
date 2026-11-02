package com.hippo.ehviewer.client

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.data.ListUrlBuilder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Explicitly opt in: reads real homepage pages using the debug app's saved account. */
@RunWith(AndroidJUnit4::class)
class SubscriptionHomepageDeviceTest {
    @Test fun inspectLivePaginationAndReplayBoundaryMinutes() {
        assumeTrue("Pass testSubscriptionHomepage=1 for live site requests",
            InstrumentationRegistry.getArguments().getString("testSubscriptionHomepage") == "1")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = JSONObject()
        val requests = JSONArray()
        val findings = JSONArray()
        var phase = "seed"
        val started = SystemClock.elapsedRealtime()
        try {
            val bookmarks = EhDB.getSubscribedQuickSearch()
            val matcher = SubscriptionHomepageMatcher(bookmarks)
            report.put("subscribed", bookmarks.size)
            report.put("fallback", matcher.fallbackBookmarks().size)
            val client = EhApplication.getOkHttpClient(context).newBuilder()
                .callTimeout(45, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request = chain.request()
                    requests.put(JSONObject().put("phase", phase).put("method", request.method())
                        .put("path", request.url().encodedPath()))
                    chain.proceed(request)
                }.build()
            val builder = ListUrlBuilder().apply { category = EhConfig.ALL_CATEGORY }
            val seed = SubscriptionHomepageScan("")
            val first = EhEngine.getSubscriptionHomepagePage(null, client,
                builder.buildWithoutRatingFilter(), matcher, seed)
            assertNull("First page must not return a custom error", first.customErrorString)
            val rawFirst = requireNotNull(first.subscriptionRawGalleries)
            assertTrue("The live homepage must contain galleries", rawFirst.isNotEmpty())
            val next = requireNotNull(first.nextHref)
            assertTrue("Need a second live page", next.isNotBlank())
            assertTrue("First seed must close its partial floor minute", seed.shouldContinue(rawFirst, true))
            val second = EhEngine.getSubscriptionHomepagePage(null, client, next, matcher, seed)
            val rawSecond = requireNotNull(second.subscriptionRawGalleries)
            assertTrue(rawSecond.isNotEmpty())
            val raw = (rawFirst + rawSecond).toMutableList()
            var result = second
            var more = seed.shouldContinue(rawSecond, !second.nextHref.isNullOrBlank())
            var pages = 2
            while (more) {
                assertTrue("Baseline completion should remain bounded", pages < 8)
                result = EhEngine.getSubscriptionHomepagePage(null, client,
                    requireNotNull(result.nextHref), matcher, seed)
                val page = requireNotNull(result.subscriptionRawGalleries)
                raw.addAll(page)
                more = seed.shouldContinue(page, !result.nextHref.isNullOrBlank())
                pages++
            }
            assertTrue(seed.checkpoint().isNotEmpty())
            val seedPosts = (0 until requests.length()).count {
                requests.getJSONObject(it).getString("method") == "POST"
            }
            assertEquals("First baseline never needs subscription metadata", 0, seedPosts)
            report.put("seedPages", pages).put("seedMetadataRequests", seedPosts)
            report.put("firstCount", rawFirst.size).put("secondCount", rawSecond.size)
            report.put("matchedFirst", first.galleryInfoList.size)
                .put("matchedSecond", second.galleryInfoList.size)
            report.put("rawGalleries", JSONArray().apply {
                raw.forEach { put(JSONObject().put("gid", it.gid).put("posted", it.posted)) }
            })

            val repeat = SubscriptionHomepageScan(seed.checkpoint())
            assertTrue("An unchanged snapshot must not produce unread galleries",
                rawFirst.none { repeat.isNew(it) })
            phase = "repeat"
            val beforeRepeat = requests.length()
            val repeated = EhEngine.getSubscriptionHomepagePage(null, client,
                builder.buildWithoutRatingFilter(), matcher, repeat)
            val repeatedRaw = requireNotNull(repeated.subscriptionRawGalleries)
            val oldIds = raw.map { it.gid }.toSet()
            val changed = repeatedRaw.any { it.gid !in oldIds }
            val repeatPosts = (beforeRepeat until requests.length()).count {
                requests.getJSONObject(it).getString("method") == "POST"
            }
            if (!changed) assertEquals("Unchanged page must skip metadata", 0, repeatPosts)
            report.put("repeatHadNewIds", changed).put("repeatMetadataRequests", repeatPosts)
            assertFalse("Complete old floor needs no extra lookahead on an unchanged repeat",
                repeat.shouldContinue(rawFirst, true))

            // A real page replay placing the old anchor near the end of the current page.
            val anchorIndex = rawFirst.size - 2
            val prior = SubscriptionHomepageScan("")
            val priorEnd = anchorIndex + rawFirst.size
            assertTrue(prior.shouldContinue(raw.subList(anchorIndex, priorEnd), true))
            // The captured dataset is the complete finite source for this controlled replay.
            assertFalse(prior.shouldContinue(raw.subList(priorEnd, raw.size), false))
            val pass = SubscriptionHomepageScan(prior.checkpoint())
            assertTrue("Near-tail anchor must load the extra page", pass.shouldContinue(rawFirst, true))
            if (pass.shouldContinue(rawSecond, true)) {
                assertFalse("Only complete the frozen boundary after the normal extra page",
                    pass.shouldContinue(raw.drop(rawFirst.size + rawSecond.size), false))
            }
            report.put("extraPageBoundaryPassed", true)

            // Look for a real same-minute pair crossing the prior snapshot's page edge.
            for (anchor in maxOf(0, rawFirst.size - 15) until rawFirst.size) {
                val end = anchor + rawFirst.size
                if (end >= raw.size) break
                if (raw[end - 1].posted != raw[end].posted) continue
                val previousPage = raw.subList(anchor, end)
                val old = SubscriptionHomepageScan("")
                assertTrue(old.shouldContinue(previousPage, true))
                assertFalse(old.shouldContinue(raw.subList(end, raw.size), false))
                val scan = SubscriptionHomepageScan(old.checkpoint())
                assertFalse("Historical same-minute spill must not count as new", scan.isNew(raw[end]))
                val arrival = GalleryInfo().apply { gid = Long.MAX_VALUE; posted = raw[end].posted }
                assertTrue("A new ID in the same complete minute must remain detectable", scan.isNew(arrival))
                report.put("sameMinuteRegressionPassed", true).put("sameMinuteProbeGid", raw[end].gid)
                break
            }

            // A whole older page can be temporarily reordered ahead of the still-visible anchor.
            val oldAnchor = rawFirst.first()
            val anchorMinute = GallerySeenState.postedMinute(oldAnchor.posted)
            val olderPage = raw.filter {
                val minute = GallerySeenState.postedMinute(it.posted)
                minute > 0 && minute < anchorMinute
            }.take(16)
            if (olderPage.size == 16) {
                val scan = SubscriptionHomepageScan(seed.checkpoint())
                assertTrue("One older page must not stop before the real anchor",
                    scan.shouldContinue(olderPage, true))
                assertFalse(scan.shouldContinue(rawFirst, false))
                report.put("reorderedPageRegressionPassed", true)
            }
            report.put("completed", true)
        } finally {
            report.put("requests", requests).put("findings", findings)
                .put("elapsedMs", SystemClock.elapsedRealtime() - started)
            File(context.getExternalFilesDir(null), "subscription-homepage-fixed-device-report.json")
                .writeText(report.toString(2))
        }
    }
}
