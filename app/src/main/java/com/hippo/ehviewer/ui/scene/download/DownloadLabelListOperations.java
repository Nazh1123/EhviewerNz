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

import androidx.annotation.NonNull;

import com.hippo.ehviewer.dao.DownloadLabel;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class DownloadLabelListOperations {

    private DownloadLabelListOperations() {
    }

    @NonNull
    static List<DownloadLabel> classifySelectedAtBottom(
            @NonNull List<DownloadLabel> labels, @NonNull Set<Long> selectedIds) {
        List<DownloadLabel> unselected = new ArrayList<>();
        List<DownloadLabel> selected = new ArrayList<>();
        for (DownloadLabel label : labels) {
            if (selectedIds.contains(label.getId())) {
                selected.add(label);
            } else {
                unselected.add(label);
            }
        }

        Collator collator = Collator.getInstance(Locale.CHINA);
        collator.setStrength(Collator.PRIMARY);
        selected.sort((left, right) -> {
            String leftLabel = left.getLabel() != null ? left.getLabel() : "";
            String rightLabel = right.getLabel() != null ? right.getLabel() : "";
            int result = collator.compare(leftLabel, rightLabel);
            return result != 0 ? result : leftLabel.compareTo(rightLabel);
        });
        unselected.addAll(selected);
        return unselected;
    }

    @NonNull
    static Set<Long> getRangeIds(@NonNull List<DownloadLabel> labels,
                                 long firstId, long secondId) {
        int firstPosition = findPosition(labels, firstId);
        int secondPosition = findPosition(labels, secondId);
        Set<Long> result = new LinkedHashSet<>();
        if (firstPosition < 0 || secondPosition < 0) {
            return result;
        }

        int start = Math.min(firstPosition, secondPosition);
        int end = Math.max(firstPosition, secondPosition);
        for (int i = start; i <= end; i++) {
            Long id = labels.get(i).getId();
            if (id != null) {
                result.add(id);
            }
        }
        return result;
    }

    @NonNull
    static List<Long> getSelectedIdsInOrder(@NonNull List<DownloadLabel> labels,
                                            @NonNull Set<Long> selectedIds) {
        List<Long> result = new ArrayList<>();
        for (DownloadLabel label : labels) {
            Long id = label.getId();
            if (id != null && selectedIds.contains(id)) {
                result.add(id);
            }
        }
        return result;
    }

    @NonNull
    static List<DownloadLabel> placeSelectedGroupAtAnchor(
            @NonNull List<DownloadLabel> currentOrder,
            @NonNull List<Long> selectedIdsInOriginalOrder,
            long anchorId) {
        int anchorPosition = findPosition(currentOrder, anchorId);
        if (anchorPosition < 0) {
            return new ArrayList<>(currentOrder);
        }

        Map<Long, DownloadLabel> labelsById = new HashMap<>();
        for (DownloadLabel label : currentOrder) {
            if (label.getId() != null) {
                labelsById.put(label.getId(), label);
            }
        }

        List<DownloadLabel> selectedGroup = new ArrayList<>();
        int anchorIndex = -1;
        for (Long id : selectedIdsInOriginalOrder) {
            DownloadLabel label = labelsById.get(id);
            if (label != null) {
                if (id == anchorId) {
                    anchorIndex = selectedGroup.size();
                }
                selectedGroup.add(label);
            }
        }
        if (anchorIndex < 0 || selectedGroup.isEmpty()) {
            return new ArrayList<>(currentOrder);
        }

        Set<DownloadLabel> selectedLabels = new HashSet<>(selectedGroup);
        List<DownloadLabel> result = new ArrayList<>(currentOrder.size());
        for (DownloadLabel label : currentOrder) {
            if (!selectedLabels.contains(label)) {
                result.add(label);
            }
        }

        int insertionPosition = Math.max(0,
                Math.min(anchorPosition - anchorIndex, result.size()));
        result.addAll(insertionPosition, selectedGroup);
        return result;
    }

    private static int findPosition(@NonNull List<DownloadLabel> labels, long id) {
        for (int i = 0; i < labels.size(); i++) {
            Long labelId = labels.get(i).getId();
            if (labelId != null && labelId == id) {
                return i;
            }
        }
        return -1;
    }
}
