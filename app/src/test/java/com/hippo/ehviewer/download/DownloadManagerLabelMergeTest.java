/*
 * Copyright 2026 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hippo.ehviewer.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.hippo.ehviewer.dao.DownloadInfo;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DownloadManagerLabelMergeTest {

    @Test
    public void sourceDownloadsAreRelabeledAndMergedInDateOrder() {
        DownloadInfo newestSource = info(1L, "source", 30L);
        DownloadInfo oldestSource = info(2L, "source", 10L);
        DownloadInfo destination = info(3L, "destination", 20L);
        List<DownloadInfo> source = Arrays.asList(newestSource, oldestSource);
        List<DownloadInfo> target = new ArrayList<>();
        target.add(destination);

        List<DownloadInfo> changed =
                DownloadManager.mergeDownloadInfoLists(source, target, "destination");

        assertEquals(Arrays.asList(newestSource, destination, oldestSource), target);
        assertEquals(Arrays.asList(newestSource, oldestSource), changed);
        assertEquals("destination", newestSource.label);
        assertEquals("destination", oldestSource.label);
        assertSame(newestSource, changed.get(0));
    }

    private static DownloadInfo info(long gid, String label, long time) {
        DownloadInfo info = new DownloadInfo(gid);
        info.label = label;
        info.time = time;
        return info;
    }
}
