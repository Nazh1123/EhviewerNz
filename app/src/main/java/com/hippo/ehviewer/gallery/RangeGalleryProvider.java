package com.hippo.ehviewer.gallery;

import android.content.Context;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.download.GalleryDownloadRangeStore;
import com.hippo.ehviewer.download.GalleryDownloadRangeStore.ImageChanges;
import com.hippo.ehviewer.download.GalleryPageRange;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.unifile.UniFile;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Locale;

/** Fixed download-range pages; missing local files remain waiting slots in the reader. */
public final class RangeGalleryProvider extends DirGalleryProvider {
    private final Context context;
    private final GalleryInfo gallery;
    private final boolean watch;
    private final ThreadLocal<ImageChanges> imageChanges = new ThreadLocal<>();
    private int anchorSource;
    private boolean repositioning;
    private boolean sameSelection;
    private GalleryPageRange selection;
    private UniFile[] imageFiles;
    private long[] fileSizes = new long[0];
    private long[] fileTimes = new long[0];
    private BitSet changedFiles = new BitSet();
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
    public synchronized boolean hasSelectedPages() {
        updateSelection();
        return sourcePages.length > 0;
    }

    @Override public void run() {
        if (!watch) {
            super.run();
            return;
        }
        try (ImageChanges changes = GalleryDownloadRangeStore.trackImages(gallery.gid)) {
            imageChanges.set(changes);
            super.run();
        } finally {
            imageChanges.remove();
        }
    }

    @Override protected long imageFilesVersion() {
        ImageChanges changes = imageChanges.get();
        return changes != null ? 1L + changes.version() : 0L;
    }
    @Override protected boolean hasFixedPagePositions() { return true; }
    @Override protected boolean hasSamePageSelection() { return sameSelection; }
    @Override protected boolean hasImageContentChanges() { return !changedFiles.isEmpty(); }
    @Override protected boolean hasChangedImageFile(int index, UniFile previous, UniFile current) {
        return changedFiles.get(index) || super.hasChangedImageFile(index, previous, current);
    }

    @Override protected synchronized UniFile[] loadImageFiles() {
        updateSelection();
        ImageChanges tracker = imageChanges.get();
        var updates = tracker != null ? tracker.drain() : null;
        BitSet pages = updates != null ? updates.pages() : new BitSet();
        boolean rescan = updates == null || updates.rescan() || !sameSelection || imageFiles == null
                || (directory == null && !pages.isEmpty());
        changedFiles = new BitSet();
        if (rescan) {
            directory = SpiderDen.getExistingGalleryDownloadDir(gallery);
            UniFile[] files = new UniFile[sourcePages.length];
            if (fileSizes.length != files.length || !sameSelection) {
                fileSizes = new long[files.length];
                fileTimes = new long[files.length];
            }
            for (int i = 0; i < sourcePages.length; i++) {
                refreshImageFile(files, i, pages.get(sourcePages[i]));
            }
            imageFiles = files;
        } else if (!pages.isEmpty()) {
            UniFile[] files = imageFiles.clone();
            for (int page = pages.nextSetBit(0); page >= 0; page = pages.nextSetBit(page + 1)) {
                int index = Arrays.binarySearch(sourcePages, page);
                if (index >= 0) refreshImageFile(files, index, true);
            }
            imageFiles = files;
        }
        return imageFiles;
    }

    private void refreshImageFile(UniFile[] files, int index, boolean downloaded) {
        UniFile file = directory != null ? SpiderDen.findImageFile(directory, sourcePages[index]) : null;
        if (file != null && !SpiderDen.isReadableImage(file)) file = null;
        files[index] = file;
        long size = file != null ? file.length() : 0L;
        long time = file != null ? file.lastModified() : 0L;
        if ((downloaded && file != null) || size != fileSizes[index] || time != fileTimes[index]) {
            changedFiles.set(index);
        }
        fileSizes[index] = size;
        fileTimes[index] = time;
    }

    private void updateSelection() {
        GalleryDownloadRangeStore.Entry entry = GalleryDownloadRangeStore.peek(gallery.gid);
        GalleryPageRange range = entry != null ? entry.range() : null;
        total = range != null ? range.totalPages : gallery.pages;
        int[] next = range == selection ? sourcePages : range != null ? range.pages() : new int[0];
        start = Math.max(0, GalleryPageRange.positionForSourcePage(anchorSource, next));
        sameSelection = Arrays.equals(sourcePages, next);
        if (!sameSelection) repositioning = true;
        sourcePages = next;
        selection = range;
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
