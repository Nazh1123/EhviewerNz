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
}
