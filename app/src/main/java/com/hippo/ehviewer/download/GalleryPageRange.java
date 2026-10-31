package com.hippo.ehviewer.download;

import java.util.Arrays;
import java.util.BitSet;

/** Immutable, zero-based source pages. User input/output is one-based. */
public final class GalleryPageRange {
    private final int[] pages;
    public final int totalPages;

    private GalleryPageRange(int totalPages, int[] pages) {
        this.totalPages = totalPages;
        this.pages = pages;
    }

    public static GalleryPageRange parse(String input, int totalPages) {
        if (totalPages <= 0 || input == null || input.trim().isEmpty()) throw new IllegalArgumentException();
        BitSet selected = new BitSet();
        for (String item : input.replace('，', ',').split(",", -1)) {
            String part = item.trim();
            if (!part.matches("[0-9]+|[0-9]*\\s*-\\s*[0-9]*")) throw new IllegalArgumentException();
            try {
                int dash = part.indexOf('-');
                int first, last;
                if (dash < 0) first = last = Integer.parseInt(part);
                else {
                    String left = part.substring(0, dash).trim(), right = part.substring(dash + 1).trim();
                    if (left.isEmpty() && right.isEmpty()) throw new IllegalArgumentException();
                    first = left.isEmpty() ? 1 : Integer.parseInt(left);
                    last = right.isEmpty() ? totalPages : Integer.parseInt(right);
                }
                if (first < 1 || last < first || last > totalPages) throw new IllegalArgumentException();
                selected.set(first - 1, last);
            } catch (NumberFormatException e) { throw new IllegalArgumentException(e); }
        }
        if (selected.isEmpty()) throw new IllegalArgumentException();
        return new GalleryPageRange(totalPages, selected.stream().toArray());
    }

    public boolean contains(int page) { return Arrays.binarySearch(pages, page) >= 0; }
    public int size() { return pages.length; }
    public int[] pages() { return pages.clone(); }
    @Override public String toString() {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < pages.length; i++) {
            int first = pages[i], last = first;
            while (i + 1 < pages.length && pages[i + 1] == last + 1) last = pages[++i];
            if (result.length() > 0) result.append(',');
            result.append(first + 1);
            if (last != first) result.append('-').append(last + 1);
        }
        return result.toString();
    }

    public static int positionForSourcePage(int page, int[] available) {
        if (available.length == 0) return -1;
        int found = Arrays.binarySearch(available, page);
        if (found >= 0) return found;
        int next = -found - 1;
        if (next < available.length && available[next] == page + 1) return next;
        return Math.max(0, next - 1);
    }
}
