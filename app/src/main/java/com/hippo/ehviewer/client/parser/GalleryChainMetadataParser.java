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

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.client.data.GalleryChainMetadata;
import com.hippo.lib.yorozuya.NumberUtils;

import org.jsoup.parser.Parser;

public final class GalleryChainMetadataParser {

    private GalleryChainMetadataParser() {
    }

    public static GalleryChainMetadata parse(String body) {
        JSONObject root = JSONObject.parseObject(body);
        JSONArray metadataArray = root != null ? root.getJSONArray("gmetadata") : null;
        if (metadataArray == null || metadataArray.isEmpty()) {
            throw new IllegalArgumentException("Empty gallery metadata");
        }

        JSONObject source = metadataArray.getJSONObject(0);
        if (source == null) {
            throw new IllegalArgumentException("Missing gallery metadata");
        }
        String error = source.getString("error");
        if (!isEmpty(error)) {
            throw new IllegalArgumentException(error);
        }

        GalleryChainMetadata result = new GalleryChainMetadata();
        result.gid = parseGid(source, "gid");
        result.token = safeString(source, "token");
        result.title = trim(safeString(source, "title"));
        result.titleJpn = trim(safeString(source, "title_jpn"));
        long postedSeconds = NumberUtils.parseLongSafely(safeString(source, "posted"), 0L);
        result.posted = postedSeconds > 0L
                ? ParserUtils.formatDate(postedSeconds * 1000L) : "";
        result.pages = NumberUtils.parseIntSafely(safeString(source, "filecount"), 0);

        result.parentGid = parseGid(source, "parent_gid");
        result.parentToken = safeString(source, "parent_key");
        result.firstGid = parseGid(source, "first_gid");
        result.firstToken = safeString(source, "first_key");
        result.currentGid = parseGid(source, "current_gid");
        result.currentToken = safeString(source, "current_key");
        return result;
    }

    private static long parseGid(JSONObject source, String key) {
        long gid = NumberUtils.parseLongSafely(safeString(source, key), -1L);
        return gid > 0L ? gid : -1L;
    }

    private static String safeString(JSONObject source, String key) {
        String value = source.getString(key);
        return value != null ? value : "";
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static String trim(String value) {
        // Do not route API JSON through Android's TextUtils-backed StringUtils. Besides
        // keeping this parser usable in local tests, jsoup handles the HTML entities
        // that can still occur in gallery titles after JSON decoding.
        return Parser.unescapeEntities(value != null ? value : "", false).trim();
    }
}
