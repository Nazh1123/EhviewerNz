package com.hippo.ehviewer.client;

import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.dao.QuickSearch;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class BookmarkSubscriptionPlannerTest {
    private static QuickSearch bookmark(String keyword) {
        QuickSearch q = new QuickSearch();
        q.subscribed = true;
        q.mode = ListUrlBuilder.MODE_NORMAL;
        q.category = EhUtils.NONE;
        q.advanceSearch = q.minRating = q.pageFrom = q.pageTo = -1;
        q.keyword = keyword;
        return q;
    }

    @Test public void mergesQuotedSpacedAndUnquotedUploadersUsingNativeUnion() {
        QuickSearch alice = bookmark("uploader: \"Alice Smith\"");
        QuickSearch bob = bookmark("uploader:Bob");
        List<BookmarkSubscriptionPlanner.Source> plans =
                BookmarkSubscriptionPlanner.plan(Arrays.asList(alice, bob));
        assertEquals(1, plans.size());
        assertTrue(plans.get(0).isMergedUploaderSearch());
        assertEquals("uploader:\"alice smith\" uploader:\"bob\"",
                plans.get(0).createBuilder().getKeyword());
        assertEquals("uploader: \"Alice Smith\"", alice.keyword);
        assertEquals(Collections.singletonList(alice),
                plans.get(0).matchingBookmarks("alice smith"));
        assertEquals(Collections.singletonList(bob), plans.get(0).matchingBookmarks("Bob"));
        assertTrue(plans.get(0).matchingBookmarks(null).isEmpty());
        assertTrue(plans.get(0).matchingBookmarks("Someone else").isEmpty());
    }

    @Test public void mergesUploaderPathWithoutActivatingIgnoredFilters() {
        QuickSearch path = bookmark("Alice");
        path.mode = ListUrlBuilder.MODE_UPLOADER;
        path.category = 123;
        path.advanceSearch = 42;
        path.minRating = 5;
        QuickSearch normal = bookmark("uploader:Bob");
        BookmarkSubscriptionPlanner.Source source =
                BookmarkSubscriptionPlanner.plan(Arrays.asList(path, normal)).get(0);
        ListUrlBuilder builder = source.createBuilder();
        assertTrue(source.isMergedUploaderSearch());
        assertEquals(ListUrlBuilder.MODE_NORMAL, builder.getMode());
        assertEquals(EhUtils.NONE, builder.getCategory());
        assertEquals(-1, builder.getAdvanceSearch());
        assertEquals(-1, builder.getMinRating());
        assertEquals(Collections.singletonList(path), source.matchingBookmarks("Alice"));
    }

    @Test public void keepsDifferentEffectiveFiltersSeparate() {
        QuickSearch base = bookmark("uploader:Alice");
        QuickSearch category = bookmark("uploader:Bob");
        category.category = 4;
        QuickSearch rating = bookmark("uploader:Carol");
        rating.advanceSearch = 0;
        rating.minRating = 4;
        QuickSearch pages = bookmark("uploader:Dave");
        pages.advanceSearch = 0;
        pages.pageFrom = 50;
        assertEquals(4, BookmarkSubscriptionPlanner.plan(
                Arrays.asList(base, category, rating, pages)).size());
    }

    @Test public void preservesIdenticalAdvancedFilters() {
        QuickSearch a = bookmark("uploader:Alice");
        QuickSearch b = bookmark("uploader:Bob");
        for (QuickSearch q : Arrays.asList(a, b)) {
            q.category = 4;
            q.advanceSearch = 0;
            q.minRating = 4;
            q.pageFrom = 50;
            q.pageTo = 200;
        }
        List<BookmarkSubscriptionPlanner.Source> plans =
                BookmarkSubscriptionPlanner.plan(Arrays.asList(a, b));
        assertEquals(1, plans.size());
        ListUrlBuilder result = plans.get(0).createBuilder();
        assertEquals(4, result.getCategory());
        assertEquals(0, result.getAdvanceSearch());
        assertEquals(4, result.getMinRating());
        assertEquals(50, result.getPageFrom());
        assertEquals(200, result.getPageTo());
    }

    @Test public void retainsComplexAndUnsafeQueriesAsIndependentSources() {
        List<QuickSearch> bookmarks = new ArrayList<>();
        for (String query : Arrays.asList("uploader:Alice l:english", "~uploader:Alice",
                "-uploader:Alice", "uploader:Alice uploader:Bob", "uploader:\"A*\"",
                "uploader:\"A\\B\"", "uploader:\"\"", "title:Alice", "a:Alice")) {
            bookmarks.add(bookmark(query));
        }
        List<BookmarkSubscriptionPlanner.Source> plans = BookmarkSubscriptionPlanner.plan(bookmarks);
        assertEquals(bookmarks.size(), plans.size());
        for (BookmarkSubscriptionPlanner.Source source : plans) assertFalse(source.isCombined());
        for (QuickSearch q : bookmarks) assertTrue(plans.stream().anyMatch(s -> s.getBookmarks().contains(q)));
    }

    @Test public void splitsAtByteBudgetWithoutLosingBookmarks() {
        List<QuickSearch> bookmarks = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            bookmarks.add(bookmark("uploader:\"\u4e0a\u4f20\u8005 " + i + "\""));
        }
        List<BookmarkSubscriptionPlanner.Source> plans = BookmarkSubscriptionPlanner.plan(bookmarks);
        assertTrue(plans.size() > 1);
        assertTrue(plans.size() < bookmarks.size());
        for (BookmarkSubscriptionPlanner.Source source : plans) {
            assertTrue(source.createBuilder().getKeyword().getBytes(StandardCharsets.UTF_8).length <= 200);
        }
        for (QuickSearch q : bookmarks) {
            int matches = 0;
            for (BookmarkSubscriptionPlanner.Source source : plans) {
                if (source.matchingBookmarks(BookmarkSubscriptionPlanner.getUploader(q)).contains(q)) matches++;
            }
            assertEquals(1, matches);
        }
    }

    @Test public void keepsOverlongSingletonUnmodifiedAndOutOfOtherBatches() {
        QuickSearch longName = bookmark("uploader:\"" + String.join("", Collections.nCopies(201, "x")) + "\"");
        List<BookmarkSubscriptionPlanner.Source> plans = BookmarkSubscriptionPlanner.plan(
                Arrays.asList(longName, bookmark("uploader:Alice"), bookmark("uploader:Bob")));
        assertEquals(2, plans.size());
        BookmarkSubscriptionPlanner.Source original = plans.stream()
                .filter(s -> s.getBookmarks().contains(longName)).findFirst().get();
        assertEquals(longName.keyword, original.createBuilder().getKeyword());
        assertFalse(original.isMergedUploaderSearch());
        assertEquals(1, plans.stream().filter(BookmarkSubscriptionPlanner.Source::isMergedUploaderSearch).count());
    }

    @Test public void allowsExactly200BytesButSplits201Bytes() {
        String a = String.join("", Collections.nCopies(88, "a"));
        String b = String.join("", Collections.nCopies(89, "b"));
        List<BookmarkSubscriptionPlanner.Source> exact = BookmarkSubscriptionPlanner.plan(
                Arrays.asList(bookmark("uploader:" + a), bookmark("uploader:" + b)));
        assertEquals(1, exact.size());
        assertEquals(200, exact.get(0).createBuilder().getKeyword().length());
        assertEquals(2, BookmarkSubscriptionPlanner.plan(Arrays.asList(
                bookmark("uploader:" + a), bookmark("uploader:" + b + "b"))).size());
    }

    @Test public void deduplicatesUploaderNamesButRetainsAllBookmarkAttributions() {
        QuickSearch a = bookmark("uploader:Alice");
        QuickSearch duplicate = bookmark("uploader:\"alice\"");
        QuickSearch b = bookmark("uploader:Bob");
        BookmarkSubscriptionPlanner.Source source = BookmarkSubscriptionPlanner.plan(
                Arrays.asList(a, duplicate, b)).get(0);
        assertEquals("uploader:\"alice\" uploader:\"bob\"", source.createBuilder().getKeyword());
        assertEquals(Arrays.asList(a, duplicate), source.matchingBookmarks("Alice"));
    }

    @Test public void ignoresUnsubscribedAndUnsupportedBookmarks() {
        QuickSearch a = bookmark("uploader:Alice");
        a.subscribed = false;
        QuickSearch b = bookmark("uploader:Bob");
        b.mode = ListUrlBuilder.MODE_SUBSCRIPTION;
        assertTrue(BookmarkSubscriptionPlanner.plan(Arrays.asList(a, b)).isEmpty());
    }

    @Test public void stableCacheKeyIdentifiesWholeQueryAndIgnoresPaginationCopies() {
        List<QuickSearch> bookmarks = Arrays.asList(bookmark("uploader:Alice"), bookmark("uploader:Bob"));
        BookmarkSubscriptionPlanner.Source source = BookmarkSubscriptionPlanner.plan(bookmarks).get(0);
        String key = source.getCacheKey();
        ListUrlBuilder copy = source.createBuilder();
        copy.setPageIndex(3);
        copy.setKeyword("changed");
        assertEquals(key, source.getCacheKey());
        assertEquals(key, BookmarkSubscriptionPlanner.plan(bookmarks).get(0).getCacheKey());
        assertNotEquals(key, BookmarkSubscriptionPlanner.plan(Collections.singletonList(bookmarks.get(0)))
                .get(0).getCacheKey());
        QuickSearch manualUnion = bookmark(source.createBuilder().getKeyword());
        assertNotEquals(key, BookmarkSubscriptionPlanner.plan(Collections.singletonList(manualUnion))
                .get(0).getCacheKey());
    }

    @Test public void emptySearchDoesNotShareCacheWithLiteralNullKeyword() {
        String emptyKey = BookmarkSubscriptionPlanner.plan(Collections.singletonList(bookmark(null)))
                .get(0).getCacheKey();
        String wordKey = BookmarkSubscriptionPlanner.plan(Collections.singletonList(bookmark("null")))
                .get(0).getCacheKey();
        assertNotEquals(emptyKey, wordKey);
    }

    @Test public void mergesUploadersWithIdenticalCommonConditions() {
        QuickSearch a = bookmark("uploader:Alice l:english -title:sample");
        QuickSearch b = bookmark("-title:sample uploader:Bob l:english");
        List<BookmarkSubscriptionPlanner.Source> plans = BookmarkSubscriptionPlanner.plan(Arrays.asList(a,b));
        assertEquals(1, plans.size());
        assertEquals("-title:\"sample\" l:\"english\" uploader:\"alice\" uploader:\"bob\"",
                plans.get(0).createBuilder().getKeyword());
        assertEquals(Collections.singletonList(a), plans.get(0).matchingBookmarks("ALICE"));
    }

    @Test public void neverExpandsMultipleChangingConditionsIntoCrossProduct() {
        assertEquals(2, BookmarkSubscriptionPlanner.plan(Arrays.asList(
                bookmark("uploader:Alice l:english"), bookmark("uploader:Bob l:chinese"))).size());
        assertEquals(2, BookmarkSubscriptionPlanner.plan(Arrays.asList(
                bookmark("a:alice l:english"), bookmark("a:bob l:chinese"))).size());
    }

    @Test public void tagUnionRetainsSharedConditionsAndRequiresServerAttribution() {
        QuickSearch a = bookmark("l:english a:\"first artist$\" -title:sample");
        QuickSearch b = bookmark("a:second l:english -title:sample");
        List<BookmarkSubscriptionPlanner.Source> plans = BookmarkSubscriptionPlanner.plan(Arrays.asList(a,b));
        assertEquals(1, plans.size());
        assertTrue(plans.get(0).requiresVerification());
        assertEquals("-title:\"sample\" l:\"english\" ~a:\"first artist$\" ~a:\"second\"",
                plans.get(0).createBuilder().getKeyword());
        assertTrue(plans.get(0).matchingBookmarks("irrelevant").isEmpty());
        assertEquals(2, plans.get(0).getBookmarks().size());
    }

    @Test public void tagPathConvertsToExactMatchWithoutActivatingIgnoredFilters() {
        QuickSearch path = bookmark("other:artbook");
        path.mode = ListUrlBuilder.MODE_TAG;
        path.category = 4;
        QuickSearch normal = bookmark("other:comic$");
        BookmarkSubscriptionPlanner.Source source = BookmarkSubscriptionPlanner.plan(Arrays.asList(path, normal)).get(0);
        assertTrue(source.requiresVerification());
        assertEquals("~other:\"artbook$\" ~other:\"comic$\"", source.createBuilder().getKeyword());
        assertEquals(EhUtils.NONE, source.createBuilder().getCategory());
    }

    @Test public void unsafePathIsNeverReinterpretedAsFieldQuery() {
        QuickSearch path = bookmark("a:alice");
        path.mode = ListUrlBuilder.MODE_UPLOADER;
        List<BookmarkSubscriptionPlanner.Source> sources = BookmarkSubscriptionPlanner.plan(
                Arrays.asList(path, bookmark("a:bob")));
        assertEquals(2, sources.size());
        assertTrue(sources.stream().anyMatch(s -> s.createBuilder().getMode() == ListUrlBuilder.MODE_UPLOADER
                && "a:alice".equals(s.createBuilder().getKeyword())));
    }

    @Test public void sameQueryCategoriesUseInclusionUnionAndKeepAttribution() {
        QuickSearch a = bookmark("a:alice"), b = bookmark("a:alice");
        a.category = 2; b.category = 4;
        List<BookmarkSubscriptionPlanner.Source> plans = BookmarkSubscriptionPlanner.plan(Arrays.asList(a,b));
        assertEquals(1, plans.size());
        assertEquals(6, plans.get(0).createBuilder().getCategory());
        com.hippo.ehviewer.client.data.GalleryInfo gallery = new com.hippo.ehviewer.client.data.GalleryInfo();
        gallery.category = 4;
        assertEquals(Collections.singletonList(b), plans.get(0).matchingGalleryBookmarks(gallery));
        QuickSearch accountDefault = bookmark("a:alice");
        assertEquals(2, BookmarkSubscriptionPlanner.plan(Arrays.asList(a,b,accountDefault)).size());
    }

    @Test public void categoryAndTermChangesAreNotCombined() {
        QuickSearch a = bookmark("a:alice"), b = bookmark("a:bob");
        a.category = 2; b.category = 4;
        assertEquals(2, BookmarkSubscriptionPlanner.plan(Arrays.asList(a,b)).size());
    }

    @Test public void orderingAndWhitespaceDoNotInvalidateSourceKeys() {
        List<QuickSearch> bookmarks = new ArrayList<>(Arrays.asList(bookmark("uploader:Alice l:english"),
                bookmark("l:english uploader:Bob"), bookmark("a:alice"), bookmark("a:bob")));
        List<String> first = new ArrayList<>();
        for (BookmarkSubscriptionPlanner.Source source : BookmarkSubscriptionPlanner.plan(bookmarks)) first.add(source.getCacheKey());
        Collections.reverse(bookmarks);
        List<String> second = new ArrayList<>();
        for (BookmarkSubscriptionPlanner.Source source : BookmarkSubscriptionPlanner.plan(bookmarks)) second.add(source.getCacheKey());
        assertEquals(first,second);
    }

    @Test public void unsupportedOperatorsRetainOriginalSearches() {
        for (String query : Arrays.asList("~a:alice ~a:bob", "weak:a:alice", "title:foo bar",
                "uploaderid:123", "a:al*", "comment:alice", "(a:alice)", "uploader:\"A%\"")) {
            QuickSearch original = bookmark(query);
            BookmarkSubscriptionPlanner.Source source = BookmarkSubscriptionPlanner.plan(Collections.singletonList(original)).get(0);
            assertEquals(query,source.createBuilder().getKeyword());
            assertFalse(source.isCombined());
        }
    }

    @Test public void manyTagAlternativesSplitWithoutDroppingOrRepeatingBookmarks() {
        List<QuickSearch> bookmarks = new ArrayList<>();
        for (int i=0;i<40;i++) bookmarks.add(bookmark("a:\"artist " + i + "$\""));
        List<BookmarkSubscriptionPlanner.Source> sources = BookmarkSubscriptionPlanner.plan(bookmarks);
        assertTrue(sources.size() > 1);
        assertTrue(sources.size() < 40);
        for (BookmarkSubscriptionPlanner.Source source : sources)
            assertTrue(source.createBuilder().getKeyword().getBytes(StandardCharsets.UTF_8).length <= 200);
        for (QuickSearch bookmark : bookmarks)
            assertEquals(1,sources.stream().filter(s -> s.getBookmarks().contains(bookmark)).count());
    }

    @Test public void sharedUploaderAllowsTagUnionButTitlesCannotBeOrAlternatives() {
        BookmarkSubscriptionPlanner.Source source = BookmarkSubscriptionPlanner.plan(Arrays.asList(
                bookmark("uploader:Alice a:first"),bookmark("uploader:Alice a:second"))).get(0);
        assertTrue(source.requiresVerification());
        assertEquals(2, BookmarkSubscriptionPlanner.plan(Arrays.asList(bookmark("title:first"),bookmark("title:second"))).size());
    }
}
