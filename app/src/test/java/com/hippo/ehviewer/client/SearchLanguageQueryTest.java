package com.hippo.ehviewer.client;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SearchLanguageQueryTest {

    @Test
    public void addsConfiguredLanguageToEmptyQuery() {
        assertEquals("l:\"chinese$\"", SearchLanguageQuery.toggle("", "chinese"));
    }

    @Test
    public void appendsConfiguredLanguageToCurrentQuery() {
        assertEquals("artist:test l:\"english$\"",
                SearchLanguageQuery.toggle("artist:test", "english"));
    }

    @Test
    public void removesExistingLanguageAndNormalizesSpacing() {
        assertEquals("artist:test rating:4",
                SearchLanguageQuery.toggle(
                        "artist:test  l:\"chinese$\"  rating:4", "chinese"));
    }

    @Test
    public void matchesExistingLanguageCaseInsensitively() {
        assertEquals("artist:test",
                SearchLanguageQuery.toggle(
                        "artist:test L:\"Chinese$\"", "chinese"));
    }

    @Test
    public void removesLanguageBeforeCommaSeparatedFilter() {
        assertEquals("f:\"*****$\"",
                SearchLanguageQuery.toggle(
                        "l:\"chinese$\", f:\"*****$\"", "chinese"));
    }

    @Test
    public void removesLanguageAfterCommaSeparatedFilter() {
        assertEquals("f:\"*****$\"",
                SearchLanguageQuery.toggle(
                        "f:\"*****$\", l:\"chinese$\"", "chinese"));
    }

    @Test
    public void preservesCommaStyleWhenAddingLanguage() {
        assertEquals("f:\"*****$\", artist:test, l:\"chinese$\"",
                SearchLanguageQuery.toggle(
                        "f:\"*****$\", artist:test", "chinese"));
    }

    @Test
    public void doesNotRewriteCommaInsideQuotedFilter() {
        assertEquals("f:\"one,two$\"",
                SearchLanguageQuery.toggle(
                        "f:\"one,two$\", l:\"chinese$\"", "chinese"));
    }
}
