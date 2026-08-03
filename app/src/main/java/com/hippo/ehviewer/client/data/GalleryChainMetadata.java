/*
 * Copyright 2026 EhViewer contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hippo.ehviewer.client.data;

public class GalleryChainMetadata {
    public long gid = -1L;
    public String token;
    public String title;
    public String titleJpn;
    public String posted;

    public long parentGid = -1L;
    public String parentToken;
    public long firstGid = -1L;
    public String firstToken;
    public long currentGid = -1L;
    public String currentToken;
}
