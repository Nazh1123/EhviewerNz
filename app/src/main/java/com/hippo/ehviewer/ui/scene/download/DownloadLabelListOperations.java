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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class DownloadLabelListOperations {

    private DownloadLabelListOperations() {
    }

    @NonNull
    static List<DownloadLabel> placeSelectedBeforeFirstNonUnderscore(
            @NonNull List<DownloadLabel> labels, @NonNull Set<Long> selectedIds) {
        List<DownloadLabel> result = new ArrayList<>(labels.size());
        List<DownloadLabel> selected = new ArrayList<>();
        for (DownloadLabel label : labels) {
            if (selectedIds.contains(label.getId())) {
                selected.add(label);
            } else {
                result.add(label);
            }
        }

        int insertionPosition = result.size();
        for (int i = 0; i < result.size(); i++) {
            String label = result.get(i).getLabel();
            if (label == null || !label.startsWith("_")) {
                insertionPosition = i;
                break;
            }
        }
        result.addAll(insertionPosition, selected);
        return result;
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
