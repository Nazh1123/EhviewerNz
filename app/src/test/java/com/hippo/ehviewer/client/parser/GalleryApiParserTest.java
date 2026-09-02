/*
 * Copyright 2026 EhViewer contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.client.parser;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GalleryApiParserTest {

    @Test
    public void resolvesVersionRootStates() {
        assertEquals(100L, GalleryApiParser.resolveFirstGid(100L, "", ""));
        assertEquals(100L, GalleryApiParser.resolveFirstGid(200L, "100", ""));
        assertEquals(-1L, GalleryApiParser.resolveFirstGid(
                300L, "", "Key missing, or incorrect key provided."));
    }
}
