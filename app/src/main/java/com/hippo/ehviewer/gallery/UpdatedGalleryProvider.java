package com.hippo.ehviewer.gallery;

import android.content.Context;
import android.util.Log;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.download.GalleryUpdateManager;
import com.hippo.ehviewer.download.GalleryUpdateRecord;
import com.hippo.ehviewer.download.GalleryUpdateRecordStore;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.unifile.UniFile;

import java.util.ArrayList;
import java.util.Locale;

/** Reads only surviving local images; no SpiderQueen or network requests are involved. */
public final class UpdatedGalleryProvider extends DirGalleryProvider {
    private final Context context;
    private final GalleryInfo gallery;
    private final long recordGid;
    private final long recordTime;
    private volatile int[] sourcePages = new int[0];
    private volatile int[] addedPositions = new int[0];
    private volatile int startPage;
    private volatile UniFile directory;
    private volatile String error;
    private long progressSequence;

    public UpdatedGalleryProvider(Context context, GalleryInfo gallery, long recordGid, long recordTime) {
        this.context = context.getApplicationContext();
        this.gallery = gallery;
        this.recordGid = recordGid > 0 ? recordGid : gallery.gid;
        this.recordTime = recordTime;
    }

    @Override protected UniFile[] loadImageFiles() {
        try {
            GalleryUpdateRecord record = GalleryUpdateRecordStore.get(context).find(recordGid);
            if (record == null || record.completedAt != recordTime || record.isFailure() || !record.complete) {
                error = context.getString(R.string.gallery_update_log_unavailable);
                return null;
            }
            directory = SpiderDen.getExistingGalleryDownloadDir(gallery);
            SpiderInfo current = GalleryUpdateManager.readDownloadedSpiderInfo(gallery.gid);
            if (directory == null || current == null) {
                error = context.getString(R.string.gallery_update_history_no_local);
                return null;
            }
            ArrayList<UniFile> files = new ArrayList<>();
            ArrayList<Integer> pages = new ArrayList<>();
            ArrayList<Integer> positions = new ArrayList<>();
            int[] mapped = record.resolveAddedPageMap(current);
            Integer[] order = new Integer[mapped.length];
            for (int i = 0; i < order.length; i++) order[i] = i;
            java.util.Arrays.sort(order, java.util.Comparator.comparingInt(index -> mapped[index]));
            for (int position : order) {
                int page = mapped[position];
                if (page < 0) continue;
                try {
                    UniFile file = SpiderDen.findImageFile(directory, page);
                    if (file != null && SpiderDen.isReadableImage(file)) {
                        files.add(file);
                        pages.add(page);
                        positions.add(position);
                    }
                } catch (RuntimeException e) {
                    Log.w("UpdatedGalleryProvider", "Skipping unreadable local page " + page, e);
                }
            }
            if (files.isEmpty()) {
                error = context.getString(R.string.gallery_update_history_no_pages);
                return null;
            }
            sourcePages = pages.stream().mapToInt(Integer::intValue).toArray();
            addedPositions = positions.stream().mapToInt(Integer::intValue).toArray();
            startPage = resumePosition(record.readingPage, addedPositions);
            return files.toArray(new UniFile[0]);
        } catch (RuntimeException e) {
            Log.w("UpdatedGalleryProvider", "Unable to resolve local update pages", e);
            error = context.getString(R.string.gallery_update_history_no_local);
            return null;
        }
    }

    static int resumePosition(int savedAddition, int[] additions) {
        int next = -1;
        for (int i = 0; i < additions.length; i++) {
            if (additions[i] == savedAddition) return i;
            if (additions[i] > savedAddition && (next < 0 || additions[i] < additions[next])) next = i;
        }
        return next >= 0 ? next : 0;
    }

    @Override public String getError() { return error; }
    @Override public int getStartPage() { return startPage; }
    @Override public int getSourcePage(int index) {
        int[] pages = sourcePages;
        return index >= 0 && index < pages.length ? pages[index] : index;
    }
    @Override public String getImageFilename(int index) {
        return String.format(Locale.US, "%d-%s-%08d", gallery.gid, gallery.token, getSourcePage(index) + 1);
    }
    @Override public String getTranslationFilename(int index) {
        return SpiderDen.generateImageFilename(getSourcePage(index), "");
    }
    @Override public UniFile getTranslationDirectory() { return directory; }
    @Override public String getTranslationIdentity() {
        return "eh:" + gallery.gid + ":update:" + recordGid + ":" + recordTime;
    }
    @Override public GalleryProvider2 createTranslationProvider(Context context) {
        return new UpdatedGalleryProvider(context, gallery, recordGid, recordTime);
    }
    @Override public synchronized void putStartPage(int page) {
        if (page < 0 || page >= sourcePages.length) return;
        int addition = addedPositions[page];
        long sequence = ++progressSequence;
        EhApplication.getExecutorService(context).execute(() -> {
            synchronized (UpdatedGalleryProvider.this) {
                if (sequence != progressSequence) return;
                GalleryUpdateRecordStore.get(context).saveReadingPage(recordGid, recordTime, addition);
            }
        });
    }
}
