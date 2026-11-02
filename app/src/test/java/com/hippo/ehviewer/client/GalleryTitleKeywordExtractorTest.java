package com.hippo.ehviewer.client;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class GalleryTitleKeywordExtractorTest {
    @Test public void languageNamesAreIgnoredAsArtistKeywords() {
        for (String language : List.of("English", "Chinese", "Japanese", "Korean",
                "Spanish", "French", "German", "Russian", "eNgLiSh")) {
            assertEquals(language, "Alice", GalleryTitleKeywordExtractor.extractArtistKeyword(
                    "[" + language + "] [Alice] Work"));
            assertNull(GalleryTitleKeywordExtractor.extractArtistKeyword("[" + language + "]"));
        }
        assertEquals("Englishman", GalleryTitleKeywordExtractor.extractArtistKeyword("[Englishman] Work"));
    }

    @Test public void fullSelectionReproducesOriginalSpacingAndUnicode() {
        String title = "[Alice-Smith]  Cafe\u0301\u00a0【日本語】★😀";
        assertEquals(title, GalleryTitleKeywordExtractor.joinSearchTokens(title,
                GalleryTitleKeywordExtractor.extractSearchTokens(title)));
        assertEquals(List.of("[", "Alice", "-", "Smith", "]", "Cafe\u0301", "【", "日本語",
                "】", "★", "😀"), GalleryTitleKeywordExtractor.extractSearchCandidates(title));
    }

    @Test public void adjacentSelectionRetainsPunctuationAndInternalWhitespace() {
        String title = "[Alice-Smith]  A story";
        List<GalleryTitleKeywordExtractor.SearchToken> tokens =
                GalleryTitleKeywordExtractor.extractSearchTokens(title);
        assertEquals("[Alice-Smith]  A", GalleryTitleKeywordExtractor.joinSearchTokens(
                title, tokens.subList(0, 6)));
    }

    @Test public void skippedTokensBecomeSpacesWithoutRestoringOmittedText() {
        String title = "[Alice-Smith] story";
        List<GalleryTitleKeywordExtractor.SearchToken> tokens =
                GalleryTitleKeywordExtractor.extractSearchTokens(title);
        assertEquals("Alice Smith story", GalleryTitleKeywordExtractor.joinSearchTokens(
                title, List.of(tokens.get(1), tokens.get(3), tokens.get(5))));
    }

    @Test public void selectionOrderFollowsSourceEvenWithRepeatedWords() {
        String title = "A-A A";
        List<GalleryTitleKeywordExtractor.SearchToken> tokens =
                GalleryTitleKeywordExtractor.extractSearchTokens(title);
        assertEquals("-A A", GalleryTitleKeywordExtractor.joinSearchTokens(
                title, List.of(tokens.get(3), tokens.get(2), tokens.get(1))));
    }

    @Test public void emptyTitlesAndSelectionsHaveNoSearchText() {
        assertTrue(GalleryTitleKeywordExtractor.extractSearchTokens(null).isEmpty());
        assertTrue(GalleryTitleKeywordExtractor.extractSearchTokens(" \u3000\u00a0").isEmpty());
        assertEquals("", GalleryTitleKeywordExtractor.joinSearchTokens("Title", List.of()));
    }
}
