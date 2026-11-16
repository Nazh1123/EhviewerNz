package com.hippo.ehviewer.gallery;

import android.content.Context;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.download.GalleryDownloadRangeStore;
import com.hippo.ehviewer.download.GalleryPageRange;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.unifile.UniFile;
import java.util.Arrays;
import java.util.Locale;

/** Fixed download-range pages; missing local files remain waiting slots in the reader. */
public final class RangeGalleryProvider extends DirGalleryProvider {
    private final Context context;
    private final GalleryInfo gallery;
    private final boolean watch;
    private int anchorSource;
    private boolean repositioning;
    private boolean sameSelection;
    private long[] fileSizes = new long[0];
    private long[] fileTimes = new long[0];
    private java.util.BitSet changedFiles = new java.util.BitSet();
    private long progressSequence;
    private volatile int start;
    private volatile int[] sourcePages = new int[0];
    private volatile UniFile directory;
    private volatile int total;

    public RangeGalleryProvider(Context context, GalleryInfo gallery, int sourceStartPage) {
        this(context, gallery, sourceStartPage, true);
    }
    private RangeGalleryProvider(Context context, GalleryInfo gallery, int sourceStartPage, boolean watch) {
        this.context = context.getApplicationContext();
        this.gallery = gallery;
        this.watch = watch;
        GalleryDownloadRangeStore.get(context);
        GalleryDownloadRangeStore.Entry entry = GalleryDownloadRangeStore.peek(gallery.gid);
        anchorSource = sourceStartPage >= 0 ? sourceStartPage : entry != null ? entry.readingPage() : 0;
    }
    public boolean hasSelectedPages() { return loadImageFiles().length > 0; }
    @Override protected long imageFilesVersion() { return watch ? 1L + GalleryDownloadRangeStore.version(gallery.gid) : 0L; }
    @Override protected boolean hasFixedPagePositions() { return true; }
    @Override protected boolean hasSamePageSelection() { return sameSelection; }
    @Override protected boolean hasImageContentChanges() { return !changedFiles.isEmpty(); }
    @Override protected boolean hasChangedImageFile(int index, UniFile previous, UniFile current) {
        return changedFiles.get(index) || super.hasChangedImageFile(index, previous, current);
    }
    @Override protected synchronized UniFile[] loadImageFiles() {
        GalleryDownloadRangeStore.Entry entry = GalleryDownloadRangeStore.peek(gallery.gid);
        directory = SpiderDen.getExistingGalleryDownloadDir(gallery);
        total = entry != null ? entry.range().totalPages : gallery.pages;
        int[] next = entry != null ? entry.range().pages() : new int[0];
        UniFile[] files = new UniFile[next.length];
        long[] sizes = new long[next.length];
        long[] times = new long[next.length];
        java.util.BitSet changes = new java.util.BitSet();
        if (directory != null) for (int index = 0; index < next.length; index++) {
            UniFile file = SpiderDen.findImageFile(directory, next[index]);
            if (file != null && SpiderDen.isReadableImage(file)) {
                files[index] = file;
                sizes[index] = file.length();
                times[index] = file.lastModified();
            }
            if (index >= fileSizes.length || sizes[index] != fileSizes[index] || times[index] != fileTimes[index])
                changes.set(index);
        }
        fileSizes = sizes;
        fileTimes = times;
        changedFiles = changes;
        start = Math.max(0, GalleryPageRange.positionForSourcePage(anchorSource, next));
        sameSelection = Arrays.equals(sourcePages, next);
        if (!sameSelection) repositioning = true;
        sourcePages = next;
        return files;
    }
    @Override public int getStartPage() { return start; }
    public synchronized int getAnchorSourcePage() { return anchorSource; }
    @Override public String getError() { return context.getString(R.string.range_download_no_pages); }
    @Override public int getSourcePageCount() { return total; }
    @Override public int getSourcePage(int page) {
        int[] pages = sourcePages;
        return page >= 0 && page < pages.length ? pages[page] : page;
    }
    @Override public String getImageFilename(int page) {
        return String.format(Locale.US, "%d-%s-%08d", gallery.gid, gallery.token, getSourcePage(page) + 1);
    }
    @Override public String getTranslationFilename(int page) { return SpiderDen.generateImageFilename(getSourcePage(page), ""); }
    @Override public UniFile getTranslationDirectory() { return directory; }
    @Override public String getTranslationIdentity() { return "eh:" + gallery.gid + ":range"; }
    @Override public GalleryProvider2 createTranslationProvider(Context context) {
        return new RangeGalleryProvider(context, gallery, anchorSource, false);
    }
    @Override public synchronized void onPageListUpdated(int currentIndex) {
        repositioning = false;
        if (currentIndex >= 0) start = currentIndex;
        putStartPage(currentIndex);
    }
    @Override public synchronized void putStartPage(int page) {
        if (page < 0 || page >= sourcePages.length || (repositioning && page != start)) return;
        repositioning = false;
        anchorSource = sourcePages[page];
        // The download list can return before the disk worker runs. Publish the latest
        // source position immediately, then persist the same shared value off the GL thread.
        GalleryDownloadRangeStore.get(context).recordReadingPage(gallery.gid, anchorSource);
        long sequence = ++progressSequence;
        EhApplication.getExecutorService(context).execute(() -> {
            synchronized (RangeGalleryProvider.this) {
                if (sequence == progressSequence) GalleryDownloadRangeStore.get(context).flushReadingPage(gallery.gid);
            }
        });
    }
}
