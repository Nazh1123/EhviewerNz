/*
 * Copyright 2026 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hippo.ehviewer.ui.scene.download;

import static org.junit.Assert.assertEquals;

import com.hippo.ehviewer.dao.DownloadLabel;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class DownloadLabelListOperationsTest {

    @Test
    public void selectedLabelsAreSortedAndMovedToBottom() {
        List<DownloadLabel> labels = labels("keep 1", "C: cos", "keep 2", "A: artist");
        Set<Long> selected = new HashSet<>(Arrays.asList(2L, 4L));

        List<DownloadLabel> result =
                DownloadLabelListOperations.classifySelectedAtBottom(labels, selected);

        assertEquals(Arrays.asList("keep 1", "keep 2", "A: artist", "C: cos"),
                names(result));
    }

    @Test
    public void rangeSelectionIncludesBothEndpointsAndItemsBetween() {
        List<DownloadLabel> labels = labels("one", "two", "three", "four", "five");

        assertEquals(new HashSet<>(Arrays.asList(2L, 3L, 4L)),
                DownloadLabelListOperations.getRangeIds(labels, 4L, 2L));
    }

    @Test
    public void groupDragKeepsOriginalSelectedOrderAroundAnchor() {
        List<DownloadLabel> originalOrder = labels("one", "two", "three", "four", "five");
        List<DownloadLabel> currentOrder = Arrays.asList(
                originalOrder.get(0), originalOrder.get(3), originalOrder.get(1),
                originalOrder.get(2), originalOrder.get(4));
        List<Long> selectedOrder = Arrays.asList(2L, 4L);

        List<DownloadLabel> result = DownloadLabelListOperations.placeSelectedGroupAtAnchor(
                currentOrder, selectedOrder, 4L);

        assertEquals(Arrays.asList("two", "four", "one", "three", "five"), names(result));
    }

    private static List<DownloadLabel> labels(String... names) {
        java.util.ArrayList<DownloadLabel> result = new java.util.ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            result.add(new DownloadLabel((long) i + 1, names[i], i));
        }
        return result;
    }

    private static List<String> names(List<DownloadLabel> labels) {
        return labels.stream().map(DownloadLabel::getLabel).collect(Collectors.toList());
    }
}
