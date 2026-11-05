package com.hippo.ehviewer.client;

import static org.junit.Assert.*;
import android.app.Application;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.client.parser.GalleryListParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE)
public class GalleryListRequestPipelineTest {
    private final List<EhRequest> requests = new ArrayList<>();
    private final List<GalleryListParser.Result> committed = new ArrayList<>();
    private final List<Exception> failures = new ArrayList<>();
    private GalleryListRequestPipeline pipeline;
    private boolean prefetch;
    private static final String FIRST = "https://e-hentai.org/?next=3";
    private static final String NEXT = "https://e-hentai.org/?next=2";

    @Before public void setUp() {
        prefetch = true;
        pipeline = new GalleryListRequestPipeline(requests::add, raw -> prefetch);
    }

    private EhRequest original(String url) {
        return new EhRequest().setMethod(EhClient.METHOD_GET_GALLERY_LIST).setBulk(true)
                .setEhConfig(new EhConfig()).setArgs(url, ListUrlBuilder.MODE_NORMAL, false, false, 450, false)
                .setCallback(new EhClient.Callback<GalleryListParser.Result>() {
                    @Override public void onSuccess(GalleryListParser.Result result) { committed.add(result); }
                    @Override public void onFailure(Exception error) { failures.add(error); }
                    @Override public void onCancel() { }
                });
    }

    private GalleryListParser.Result raw(String next) {
        GalleryListParser.Result result = new GalleryListParser.Result();
        result.nextHref = next;
        result.pages = -1;
        result.rawTailPosted = "2026-11-01 00:00";
        return result;
    }

    @SuppressWarnings("unchecked") private void succeed(int index, GalleryListParser.Result result) {
        ((EhClient.Callback<GalleryListParser.Result>) requests.get(index).getCallback()).onSuccess(result);
    }

    @Test public void nextHtmlStartsBeforeCurrentMetadataAndOnlyFilteredResultsAreCommitted() {
        pipeline.execute(original(FIRST));
        assertEquals(EhClient.METHOD_GET_GALLERY_LIST_HTML, requests.get(0).getMethod());
        GalleryListParser.Result first = raw(NEXT);
        succeed(0, first);
        assertEquals(3, requests.size());
        assertEquals(EhClient.METHOD_GET_GALLERY_LIST_HTML, requests.get(1).getMethod());
        assertEquals(NEXT, requests.get(1).getArgs()[0]);
        assertEquals(EhClient.METHOD_PREPARE_GALLERY_LIST, requests.get(2).getMethod());
        assertSame(first, requests.get(2).getArgs()[5]);
        assertTrue(committed.isEmpty());
        succeed(1, raw("https://e-hentai.org/?next=1"));
        assertEquals("Lookahead must stop after one page", 3, requests.size());
        succeed(2, first);
        assertEquals(List.of(first), committed);
        pipeline.execute(original(NEXT));
        assertEquals(5, requests.size());
        long nextHtmlRequests = requests.stream().filter(r -> r.getMethod() == EhClient.METHOD_GET_GALLERY_LIST_HTML
                && NEXT.equals(r.getArgs()[0])).count();
        assertEquals(1, nextHtmlRequests);
        for (EhRequest request : requests) assertTrue(request.isBulk());
    }

    @Test public void noLookaheadBeyondTheSelectedBoundary() {
        prefetch = false;
        pipeline.execute(original(FIRST));
        succeed(0, raw(NEXT));
        assertEquals(2, requests.size());
        assertEquals(EhClient.METHOD_PREPARE_GALLERY_LIST, requests.get(1).getMethod());
    }

    @Test public void cancellationStopsHtmlAndPreparationAndRejectsLateCallbacks() {
        EhRequest original = original(FIRST);
        pipeline.execute(original);
        GalleryListParser.Result first = raw(NEXT);
        succeed(0, first);
        pipeline.cancel();
        assertTrue(original.isCancelled());
        assertTrue(requests.get(1).isCancelled());
        assertTrue(requests.get(2).isCancelled());
        succeed(1, raw(null));
        succeed(2, first);
        assertTrue(committed.isEmpty());
        assertEquals(3, requests.size());
        pipeline.execute(original(FIRST));
        assertEquals(4, requests.size());
    }

    @Test public void originalRequestCancellationReachesItsCurrentPreparation() {
        EhRequest original = original(FIRST);
        pipeline.execute(original);
        succeed(0, raw(NEXT));
        original.cancel();
        assertTrue(requests.get(2).isCancelled());
        succeed(2, raw(NEXT));
        assertTrue(committed.isEmpty());
    }

    @Test public void prefetchedFailureIsReportedOnlyWhenThatPageIsRequested() {
        pipeline.execute(original(FIRST));
        GalleryListParser.Result first = raw(NEXT);
        succeed(0, first);
        Exception error = new java.io.IOException("offline");
        requests.get(1).getCallback().onFailure(error);
        assertTrue(failures.isEmpty());
        succeed(2, first);
        pipeline.execute(original(NEXT));
        assertEquals(List.of(error), failures);
        assertEquals(List.of(first), committed);
    }
}
