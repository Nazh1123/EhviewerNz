/*
 * Copyright 2026 EhViewer contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hippo.ehviewer.client.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.hippo.ehviewer.client.data.GalleryChainMetadata;

import org.junit.Test;

public class GalleryChainMetadataParserTest {

    @Test
    public void testParseGalleryChainFields() {
        String body = "{\"gmetadata\":[{"
                + "\"gid\":2231376,\"token\":\"a7584a5932\","
                + "\"title\":\"Title\",\"title_jpn\":\"日本語\","
                + "\"posted\":\"1653702810\",\"filecount\":\"899\","
                + "\"parent_gid\":\"2197090\",\"parent_key\":\"2f440c5f01\","
                + "\"current_gid\":\"2924387\",\"current_key\":\"aa28f4a72a\","
                + "\"first_gid\":\"2043548\",\"first_key\":\"bdb0cd9ec2\"}]}";

        GalleryChainMetadata result = GalleryChainMetadataParser.parse(body);

        assertEquals(2231376L, result.gid);
        assertEquals("a7584a5932", result.token);
        assertEquals("Title", result.title);
        assertEquals("日本語", result.titleJpn);
        assertFalse(result.posted.isEmpty());
        assertEquals(899, result.pages);
        assertEquals(2197090L, result.parentGid);
        assertEquals("2f440c5f01", result.parentToken);
        assertEquals(2924387L, result.currentGid);
        assertEquals("aa28f4a72a", result.currentToken);
        assertEquals(2043548L, result.firstGid);
        assertEquals("bdb0cd9ec2", result.firstToken);
    }

    @Test
    public void testParseFirstGalleryWithoutParent() {
        String body = "{\"gmetadata\":[{\"gid\":10,\"token\":\"0123456789\","
                + "\"title\":\"First\",\"posted\":\"0\","
                + "\"first_gid\":\"10\",\"first_key\":\"0123456789\"}]}";

        GalleryChainMetadata result = GalleryChainMetadataParser.parse(body);

        assertEquals(-1L, result.parentGid);
        assertEquals(-1L, result.currentGid);
        assertEquals(10L, result.firstGid);
    }
}
