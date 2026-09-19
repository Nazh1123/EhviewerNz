package com.hippo.ehviewer.client.parser;

import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.client.exception.ParseException;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MergedUploaderSearchParserTest {
    @Test(expected = ParseException.class)
    public void syntaxErrorIsNotSuccessfulEmptyCheck() throws Exception {
        GalleryListParser.parseMergedUploaderSearch("<p>Some terms use unsupported syntax, "
                + "are too short, or are otherwise not valid.</p><p>No hits found</p>",
                ListUrlBuilder.MODE_NORMAL);
    }

    @Test public void legitimateEmptyResultIsAllowed() throws Exception {
        GalleryListParser.Result result = GalleryListParser.parseMergedUploaderSearch(
                "<p>No hits found</p>", ListUrlBuilder.MODE_NORMAL);
        assertEquals(0, result.rawResultCount);
        assertTrue(result.galleryInfoList.isEmpty());
    }

    @Test(expected = ParseException.class)
    public void malformedPageCannotAdvanceBaseline() throws Exception {
        GalleryListParser.parseMergedUploaderSearch("<html>Service unavailable</html>",
                ListUrlBuilder.MODE_NORMAL);
    }

    @Test public void accountWithoutWatchedTagsIsSuccessfulEmptySubscription() throws Exception {
        GalleryListParser.Result result = GalleryListParser.parseMergedUploaderSearch(
                "<p>You do not have any watched tags. Add them in My Tags.</p>", ListUrlBuilder.MODE_SUBSCRIPTION);
        assertTrue(result.noWatchedTags);
        assertTrue(result.galleryInfoList.isEmpty());
    }

    @Test(expected = ParseException.class)
    public void termLimitWarningCannotBeAcceptedAsEmptyResult() throws Exception {
        GalleryListParser.parseMergedUploaderSearch(
                "<p>You can only use five inclusion terms.</p><p>No hits found</p>", ListUrlBuilder.MODE_NORMAL);
    }
}
