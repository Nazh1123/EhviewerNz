package com.hippo.ehviewer.download;

import static org.junit.Assert.*;
import com.hippo.ehviewer.dao.DownloadLabel;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class DownloadLabelTreeTest {
    private DownloadLabel item(long id, String name, boolean folder, Long parent) {
        return new DownloadLabel(id, name, 0, folder, parent);
    }
    private List<DownloadLabel> sample() {
        return new ArrayList<>(List.of(item(1, "Z folder", true, null),
                item(2, "z child", false, 1L), item(3, "a child", false, 1L),
                item(4, "z root", false, null), item(5, "a root", false, null),
                item(6, "A folder", true, null), item(7, "other child", false, 6L)));
    }
    private List<Long> ids(List<DownloadLabel> items) {
        return items.stream().map(DownloadLabel::getId).toList();
    }
    @Test public void sortingRootsCarriesChildrenAndSortsEachFolderIndependently() {
        List<DownloadLabel> items = sample();
        List<DownloadLabel> sorted = DownloadLabelTree.sortSelected(items, Set.of(1L, 2L, 3L, 4L, 5L, 6L));
        assertEquals(List.of(6L, 7L, 5L, 1L, 3L, 2L, 4L), ids(sorted));
        assertEquals(Long.valueOf(1), items.get(1).getParentId());
    }
    @Test public void selectingFolderAloneDoesNotSortItsChildren() {
        assertEquals(List.of(6L, 7L, 1L, 2L, 3L, 4L, 5L),
                ids(DownloadLabelTree.sortSelected(sample(), Set.of(1L, 6L))));
    }
    @Test public void nonAdjacentSelectionBecomesOneBlockInOriginalOrder() {
        assertEquals(List.of(1L, 3L, 5L, 6L, 7L, 2L, 4L),
                ids(DownloadLabelTree.move(sample(), Set.of(2L, 4L), 7, true)));
    }
    @Test public void targetFolderHeaderMeansFirstChild() {
        List<DownloadLabel> items = sample();
        List<DownloadLabel> moved = DownloadLabelTree.move(items, Set.of(4L, 5L), 1, false);
        assertEquals(List.of(1L, 4L, 5L, 2L, 3L, 6L, 7L), ids(moved));
        assertEquals(Long.valueOf(1), items.get(3).getParentId());
    }
    @Test public void bottomOfLastChildStaysInsideFolder() {
        List<DownloadLabel> items = sample();
        assertEquals(List.of(1L, 2L, 3L, 5L, 4L, 6L, 7L),
                ids(DownloadLabelTree.move(items, Set.of(5L), 3, true)));
        assertEquals(Long.valueOf(1), items.get(4).getParentId());
    }
    @Test public void topOfNextRootMovesOutsideFolder() {
        List<DownloadLabel> items = sample();
        assertEquals(List.of(1L, 3L, 2L, 4L, 5L, 6L, 7L),
                ids(DownloadLabelTree.move(items, Set.of(2L), 4, false)));
        assertNull(items.get(1).getParentId());
    }
    @Test public void folderCannotBeDroppedInsideFolderAndNothingMutates() {
        List<DownloadLabel> items = sample();
        assertEquals(ids(items), ids(DownloadLabelTree.move(items, Set.of(1L, 4L), 7, false)));
        assertNull(items.get(0).getParentId());
        assertNull(items.get(3).getParentId());
    }
    @Test public void folderMovesAsUnitAmongRoots() {
        assertEquals(List.of(4L, 5L, 1L, 2L, 3L, 6L, 7L),
                ids(DownloadLabelTree.move(sample(), Set.of(1L), 5, true)));
    }
    @Test public void folderHeaderWithSelectedFolderMeansAfterItsSubtreeAtRoot() {
        assertEquals(List.of(4L, 5L, 6L, 7L, 1L, 2L, 3L),
                ids(DownloadLabelTree.move(sample(), Set.of(1L), 6, true)));
    }
    @Test public void folderCanMovePastLastFolderWithoutBecomingNested() {
        List<DownloadLabel> items = sample();
        assertEquals(List.of(4L, 5L, 6L, 7L, 1L, 2L, 3L),
                ids(DownloadLabelTree.move(items, Set.of(1L), 7, true)));
        assertNull(items.get(0).getParentId());
    }
    @Test public void selectedFolderAndExplicitChildDoNotDuplicateChild() {
        assertEquals(List.of(4L, 5L, 1L, 2L, 3L, 6L, 7L),
                ids(DownloadLabelTree.move(sample(), Set.of(1L, 2L), 6, false)));
    }
    @Test public void ownDescendantIsNotADropTarget() {
        List<DownloadLabel> items = sample();
        assertEquals(ids(items), ids(DownloadLabelTree.move(items, Set.of(1L), 3, true)));
    }
    @Test public void normalizeRepairsOrphanAndKeepsChildrenTogether() {
        DownloadLabel orphan = item(9, "orphan", false, 99L);
        DownloadLabel folder = item(1, "folder", true, null);
        DownloadLabel child = item(2, "child", false, 1L);
        assertEquals(List.of(9L, 1L, 2L),
                ids(DownloadLabelTree.normalize(List.of(orphan, child, folder))));
        assertNull(orphan.getParentId());
    }
}
