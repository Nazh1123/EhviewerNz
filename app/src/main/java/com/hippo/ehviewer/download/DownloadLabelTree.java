package com.hippo.ehviewer.download;

import com.hippo.ehviewer.dao.DownloadLabel;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Single-level folders, stored in display order with children immediately after their folder. */
public final class DownloadLabelTree {
    private DownloadLabelTree() {}

    public static List<DownloadLabel> normalize(List<DownloadLabel> items) {
        Set<Long> folders = new HashSet<>();
        for (DownloadLabel item : items) {
            if (item.getFolder()) {
                item.setParentId(null);
                folders.add(item.getId());
            }
        }
        for (DownloadLabel item : items) {
            if (!folders.contains(item.getParentId())) item.setParentId(null);
        }
        List<DownloadLabel> result = new ArrayList<>();
        for (DownloadLabel item : items) {
            if (item.getParentId() != null) continue;
            result.add(item);
            if (item.getFolder()) result.addAll(children(items, item.getId()));
        }
        return result;
    }

    public static List<DownloadLabel> children(List<DownloadLabel> items, Long parent) {
        List<DownloadLabel> result = new ArrayList<>();
        for (DownloadLabel item : items) {
            if (Objects.equals(parent, item.getParentId())) result.add(item);
        }
        return result;
    }

    public static Set<Long> withChildren(List<DownloadLabel> items, Set<Long> ids) {
        Set<Long> result = new HashSet<>(ids);
        for (DownloadLabel item : items) {
            if (item.getParentId() != null && ids.contains(item.getParentId())) {
                result.add(item.getId());
            }
        }
        return result;
    }

    public static List<DownloadLabel> sortSelected(List<DownloadLabel> items, Set<Long> ids) {
        List<DownloadLabel> roots = sortSiblings(children(items, null), ids);
        List<DownloadLabel> result = new ArrayList<>();
        for (DownloadLabel root : roots) {
            result.add(root);
            if (root.getFolder()) {
                result.addAll(sortSiblings(children(items, root.getId()), ids));
            }
        }
        return result;
    }

    private static List<DownloadLabel> sortSiblings(List<DownloadLabel> siblings, Set<Long> ids) {
        List<DownloadLabel> selected = new ArrayList<>();
        List<DownloadLabel> rest = new ArrayList<>();
        int first = -1;
        for (DownloadLabel item : siblings) {
            if (ids.contains(item.getId())) {
                if (first < 0) first = rest.size();
                selected.add(item);
            } else rest.add(item);
        }
        Collator collator = Collator.getInstance(Locale.CHINA);
        collator.setStrength(Collator.PRIMARY);
        selected.sort((a, b) -> {
            int comparison = collator.compare(a.getLabel(), b.getLabel());
            return comparison != 0 ? comparison : a.getLabel().compareTo(b.getLabel());
        });
        if (first >= 0) rest.addAll(first, selected);
        return rest;
    }

    /** Target is a row and its upper/lower half, independent of the row used to start dragging. */
    public static List<DownloadLabel> move(List<DownloadLabel> items, Set<Long> ids,
            long targetId, boolean after) {
        Set<Long> movingIds = withChildren(items, ids);
        DownloadLabel target = null;
        boolean hasFolder = false;
        for (DownloadLabel item : items) {
            if (Objects.equals(item.getId(), targetId)) target = item;
            if (ids.contains(item.getId()) && item.getFolder()) hasFolder = true;
        }
        if (target == null || movingIds.contains(targetId)) return new ArrayList<>(items);
        Long parent = target.getFolder() ? target.getId() : target.getParentId();
        if (hasFolder && parent != null) {
            // The bottom half of a folder's last child is its outside boundary for folder moves.
            if (!target.getFolder() && (!after || !isLastChild(items, target))) {
                return new ArrayList<>(items);
            }
            parent = null;
        }
        List<DownloadLabel> moving = new ArrayList<>();
        List<DownloadLabel> rest = new ArrayList<>();
        for (DownloadLabel item : items) {
            if (movingIds.contains(item.getId())) {
                // Children carried by a selected folder keep their ownership.
                if (!movingIds.contains(item.getParentId())) item.setParentId(parent);
                moving.add(item);
            } else rest.add(item);
        }
        int insertion = rest.indexOf(target) + (after ? 1 : 0);
        if (target.getFolder() && parent != null) insertion = rest.indexOf(target) + 1;
        rest.addAll(insertion, moving);
        return normalize(rest);
    }

    public static boolean isLastChild(List<DownloadLabel> items, DownloadLabel target) {
        int position = items.indexOf(target);
        return target.getParentId() != null && (position == items.size() - 1
                || !Objects.equals(target.getParentId(), items.get(position + 1).getParentId()));
    }
}
