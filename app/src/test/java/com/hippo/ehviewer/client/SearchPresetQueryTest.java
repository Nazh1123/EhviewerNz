package com.hippo.ehviewer.client;

import org.junit.Test;

import static com.hippo.ehviewer.client.SearchPresetQuery.*;
import static org.junit.Assert.*;

public class SearchPresetQueryTest {
    private static final String LANGUAGE = "l:\"chinese$\"";
    private static final String AI = "o:\"ai generated$\"";

    @Test public void formatsDictionarySuggestionsAsSingleExactPresetTags() {
        assertEquals("o:\"ai generated$\"", SearchPresetQuery.formatSuggestion("other:ai generated"));
        assertEquals("a:\"alice$\"", SearchPresetQuery.formatSuggestion("artist:alice"));
        assertEquals("loc:\"beach$\"", SearchPresetQuery.formatSuggestion("location:beach"));
        assertNull(SearchPresetQuery.formatSuggestion("plain text"));
        assertNull(SearchPresetQuery.formatSuggestion("artist:"));
        assertNull(SearchPresetQuery.formatSuggestion("artist:bad\"quote"));
        assertNull(SearchPresetQuery.formatSuggestion("rows:artist"));
        assertNull(SearchPresetQuery.formatSuggestion("misc:keyword"));
    }

    @Test public void recognizesQuotedUnquotedAndFullNamespaceForms() {
        for (String token : new String[]{"l:chinese", "l:\"chinese\"", LANGUAGE,
                "language:Chinese$", "LANGUAGE:\"CHINESE$\""}) {
            assertEquals(token, INCLUDE, getState("hello " + token, LANGUAGE));
            assertEquals(token, EXCLUDE, getState("hello -" + token, LANGUAGE));
            assertEquals("hello", setState("hello " + token, LANGUAGE, NORMAL));
        }
    }

    @Test public void movesBetweenAllThreeStatesAndRemovesDuplicatesOfEitherSign() {
        String query = "f:stockings l:chinese -language:\"chinese$\" l:\"chinese\"";
        assertEquals("f:stockings " + LANGUAGE, setState(query, LANGUAGE, INCLUDE));
        query = setState(query, LANGUAGE, EXCLUDE);
        assertEquals("f:stockings -" + LANGUAGE, query);
        assertEquals(EXCLUDE, getState(query, LANGUAGE));
        assertEquals("f:stockings", setState(query, LANGUAGE, NORMAL));
        assertEquals(LANGUAGE, setState(null, LANGUAGE, INCLUDE));
        assertEquals("", setState("-l:chinese l:chinese", LANGUAGE, NORMAL));
    }

    @Test public void preservesCommasAndQuotedSpacesWithoutLeavingDanglingSeparators() {
        assertEquals("f:stockings, o:\"ai generated$\"", setState(
                "l:chinese, f:stockings, o:\"ai generated$\"", LANGUAGE, NORMAL));
        assertEquals("artist:test, f:stockings", setState(
                "artist:test, -l:chinese, f:stockings", LANGUAGE, NORMAL));
        assertEquals("artist:test", setState("artist:test, l:chinese", LANGUAGE, NORMAL));
        assertEquals("artist:test, f:stockings, " + LANGUAGE,
                setState("artist:test, f:stockings", LANGUAGE, INCLUDE));
        assertEquals("title:\"a, b\" " + LANGUAGE,
                setState("title:\"a, b\"", LANGUAGE, INCLUDE));
    }

    @Test public void doesNotMatchSubstringsOtherNamespacesOrQuotedSearchText() {
        String query = "l:chinese_test other:chinese title:\"l:chinese\"";
        assertEquals(NORMAL, getState(query, LANGUAGE));
        assertEquals(query, setState(query, LANGUAGE, NORMAL));
        assertEquals("f:\"stockings\"", setState("f:\"stockings\"", LANGUAGE, NORMAL));
    }

    @Test public void recognizesAndReplacesOrWithoutLeavingAConflictingTerm() {
        assertEquals(OR, getState("hello ~language:\"chinese$\"", LANGUAGE));
        assertEquals("hello -" + LANGUAGE, setState("hello ~l:chinese", LANGUAGE, EXCLUDE));
        assertEquals("hello", setState("hello ~l:chinese", LANGUAGE, NORMAL));
        assertEquals("hello ~" + LANGUAGE, setState("hello", LANGUAGE, OR));
        assertEquals(INCLUDE, getState("cosplayer:alice", "cos:alice"));
        assertEquals(INCLUDE, getState("lang:chinese", LANGUAGE));
    }

    @Test public void presetsOperateIndependentlyAndSupportCustomNamespaces() {
        String query = setState("hello " + AI, LANGUAGE, EXCLUDE);
        assertEquals(INCLUDE, getState(query, AI));
        assertEquals(EXCLUDE, getState(query, LANGUAGE));
        assertEquals("hello -" + LANGUAGE, setState(query, AI, NORMAL));
        assertEquals("-custom:\"a b$\"", setState("custom:\"a b\"", "custom:\"a b$\"", EXCLUDE));
        assertEquals(INCLUDE, getState("female:stockings", "f:\"stockings\""));
    }

    @Test public void lastConflictingOccurrenceDefinesState() {
        assertEquals(EXCLUDE, getState("l:chinese -l:\"chinese$\"", LANGUAGE));
        assertEquals(INCLUDE, getState("-l:chinese l:\"chinese$\"", LANGUAGE));
    }

    @Test public void validatesOneNamespacedTag() {
        for (String tag : new String[]{LANGUAGE, AI, "f:\"stockings\"", "custom:tag", " l:english "}) {
            assertTrue(tag, isValid(tag));
        }
        for (String tag : new String[]{"", "chinese", "l:", "l:\"\"", "l:$", "-l:chinese",
                "l:chinese o:test", "o:ai generated", "l:\"chinese", "l:chinese,o:test"}) {
            assertFalse(tag, isValid(tag));
        }
        assertEquals("untouched", setState("untouched", "invalid", INCLUDE));
    }
}
