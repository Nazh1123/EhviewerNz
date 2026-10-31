package com.hippo.ehviewer.gallery;

import android.content.Context;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.download.GalleryDownloadRangeStore;
import com.hippo.ehviewer.download.GalleryPageRange;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.unifile.UniFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/** Local-only view of the readable pages belonging to the current download range. */
public final class RangeGalleryProvider extends DirGalleryProvider {
    private final Context context;
    private final GalleryInfo gallery;
    private final boolean watch;
    private int anchorSource;
    private boolean repositioning;
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
    public boolean hasReadablePages() { return loadImageFiles().length > 0; }
    @Override protected long imageFilesVersion() { return watch ? 1L + GalleryDownloadRangeStore.version(gallery.gid) : 0L; }
    @Override protected synchronized UniFile[] loadImageFiles() {
        GalleryDownloadRangeStore.Entry entry = GalleryDownloadRangeStore.peek(gallery.gid);
        directory = SpiderDen.getExistingGalleryDownloadDir(gallery);
        total = entry != null ? entry.range().totalPages : gallery.pages;
        ArrayList<UniFile> files = new ArrayList<>();
        ArrayList<Integer> pages = new ArrayList<>();
        if (entry != null && directory != null) for (int page : entry.range().pages()) {
            UniFile file = SpiderDen.findImageFile(directory, page);
            if (file != null && SpiderDen.isReadableImage(file)) { files.add(file); pages.add(page); }
        }
        int[] next = pages.stream().mapToInt(Integer::intValue).toArray();
        start = Math.max(0, GalleryPageRange.positionForSourcePage(anchorSource, next));
        if (!Arrays.equals(sourcePages, next)) repositioning = true;
        sourcePages = next;
        return files.toArray(new UniFile[0]);
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
    @Override public synchronized void putStartPage(int page) {
        if (page < 0 || page >= sourcePages.length || (repositioning && page != start)) return;
        repositioning = false;
        anchorSource = sourcePages[page];
        int source = anchorSource;
        long sequence = ++progressSequence;
        EhApplication.getExecutorService(context).execute(() -> {
            synchronized (RangeGalleryProvider.this) {
                if (sequence == progressSequence) GalleryDownloadRangeStore.get(context).saveReadingPage(gallery.gid, source);
            }
        });
    }
}
