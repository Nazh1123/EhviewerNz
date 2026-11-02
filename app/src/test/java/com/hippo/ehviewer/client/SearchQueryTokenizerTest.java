package com.hippo.ehviewer.client;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class SearchQueryTokenizerTest {
    @Test public void preservesRangesAndQuotedSeparators() {
        String query = "word, ~o:\"full color$\" -l:chinese title:\"a, b\" l:\"unfinished tag";
        List<SearchQueryTokenizer.Term> terms = SearchQueryTokenizer.terms(query);
        assertEquals(5, terms.size());
        assertEquals("~o:\"full color$\"", query.substring(terms.get(1).start(), terms.get(1).end()));
        assertEquals("title:\"a, b\"", query.substring(terms.get(3).start(), terms.get(3).end()));
        assertNull(SearchQueryTokenizer.parseTag(query.substring(terms.get(4).start()), false));
    }

    @Test public void recognizesExplicitTagsAndKeepsTheirMatchingMode() {
        for (String raw : new String[]{"~lang:\"chinese$\"", "~LANGUAGE:Chinese$", "~l:\"chinese\"$"}) {
            var tag = SearchQueryTokenizer.parseTag(raw, false);
            assertNotNull(raw, tag);
            assertEquals("language:chinese", tag.identity());
            assertEquals('~', tag.operator());
            assertTrue(tag.exact());
        }
        assertFalse(SearchQueryTokenizer.parseTag("l:chinese", false).exact());
        assertEquals("cosplayer:alice", SearchQueryTokenizer.parseTag("cos:alice", false).identity());
        assertEquals("parody:example", SearchQueryTokenizer.parseTag("series:example", false).identity());
    }

    @Test public void normalizesSuggestionFragmentsWithoutRequiringCompleteSyntax() {
        assertEquals("artist:ali", SearchQueryTokenizer.suggestionKeyword("a:ali"));
        assertEquals("other:full color", SearchQueryTokenizer.suggestionKeyword(" ~o:\"full color$\" "));
        assertEquals("location:", SearchQueryTokenizer.suggestionKeyword("LOC:"));
        assertEquals("中文", SearchQueryTokenizer.suggestionKeyword("中文"));
        assertEquals("", SearchQueryTokenizer.suggestionKeyword("  "));
    }

    @Test public void doesNotGuessTagsFromOrdinaryTextOrOtherSearchQualifiers() {
        for (String raw : new String[]{"chinese", "title:chinese", "uploader:alice", "gid:123",
                "weak:l:chinese", "http://example.org", "l:", "l:$", "l:\"\"", "--l:chinese", "l:chi\"nese"}) {
            assertNull(raw, SearchQueryTokenizer.parseTag(raw, false));
        }
        assertNotNull(SearchQueryTokenizer.parseTag("custom:tag", true));
    }
}
